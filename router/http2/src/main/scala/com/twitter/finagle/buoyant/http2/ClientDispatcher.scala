package com.twitter.finagle.buoyant.http2

import com.twitter.concurrent.AsyncQueue
import com.twitter.finagle.Service
import com.twitter.finagle.netty4.{BufAsByteBuf, ByteBufAsBuf}
import com.twitter.finagle.stats.{StatsReceiver, NullStatsReceiver}
import com.twitter.finagle.transport.Transport
import com.twitter.logging.Logger
import com.twitter.io.{Buf, Reader, Writer}
import com.twitter.util.{Closable, Future, Promise, Return, Stopwatch, Throw}
import io.netty.buffer.ByteBufAllocator
import io.netty.handler.codec.http2._
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{AtomicReference, AtomicInteger}

object ClientDispatcher {
  private val log = Logger.get(getClass.getName)

  private val MaxStreamId = math.pow(2, 31) - 1

  private def mkHeadersFrame(
    streamId: Int,
    orig: Headers,
    eos: Boolean
  ): Http2HeadersFrame = {
    val headers = orig match {
      case h: Netty4Headers => h.underlying
      case hs =>
        val headers = new DefaultHttp2Headers
        for ((k, v) <- hs.toSeq) headers.add(k, v)
        headers
    }
    new DefaultHttp2HeadersFrame(headers, eos).setStreamId(streamId)
  }

  /*
   * Helpers for matching stream state transitions.
   */

  // private object ActiveResponseInit {
  //   def unapply(states: StateTransition): Option[(Inbound, TrailerPromise)] =
  //     states match {
  //       case (StreamState.RemoteIdle(), StreamState.RemoteActive(rw, t)) => Some((rw, t))
  //       case _ => None
  //     }
  // }

  // private object ActiveResponse {
  //   def unapply(states: StateTransition): Option[Inbound] =
  //     states match {
  //       case (StreamState.RemoteActive(_, _), StreamState.RemoteActive(rw, _)) => Some(rw)
  //       case _ => None
  //     }
  // }

  // private object ActiveResponseEnd {
  //   def unapply(states: StateTransition): Option[(Inbound, TrailerPromise)] =
  //     states match {
  //       case (StreamState.RemoteActive(rw, t), StreamState.RemoteClosed()) => Some((rw, t))
  //       case _ => None
  //     }
  // }

  // private object FullResponse {
  //   def unapply(states: StateTransition): Boolean = states match {
  //     case (StreamState.RemoteIdle(), StreamState.RemoteClosed()) => true
  //     case _ => false
  //   }
  // }
}

/**
 * Multiplexes HTTP/2 request/responses onto HTTP/2 streams over a
 * shared connection transport.
 */
class ClientDispatcher(
  transport: Transport[Http2StreamFrame, Http2StreamFrame],
  allocator: ByteBufAllocator,
  statsReceiver: StatsReceiver = NullStatsReceiver
) extends Service[Request, Response] {

  import ClientDispatcher._

  // TODO handle overflow
  private[this] val _id = new AtomicInteger(3) // ID=1 is reserved for HTTP/1 upgrade
  private[this] def nextId() = _id.getAndAdd(2)
  private[this] val liveStreams = new ConcurrentHashMap[Int, Stream]

  private[this] val streamStats = statsReceiver.scope("streams")
  private[this] val liveStreamsGauge = streamStats.addGauge("live") { liveStreams.size }
  private[this] val streamStateStats = streamStats.scope("state")

  private[this] val transportStats = statsReceiver.scope("transport")
  private[this] val transportReadTimes = transportStats.stat("read_us")
  private[this] val transportWriteTimes = transportStats.stat("write_us")

  private[this] val dequeueTimes = streamStats.stat("dequeue_us")
  private[this] val queueLengths = streamStats.stat("qlen")

  private[this] val requestDurations = statsReceiver.stat("request_duration_ms")
  private[this] val responseDurations = statsReceiver.stat("response_duration_ms")

  /**
   * Write a request on the underlying connection and return its
   * response when it is received.
   */
  def apply(req: Request): Future[Response] = {
    // Initialize a new Stream; and store it so that a response may be
    // demultiplexed to it.
    val stream = Stream(nextId())
    if (liveStreams.putIfAbsent(stream.id, stream) != null) {
      val e = new IllegalStateException(s"stream ${stream.id} already exists")
      return Future.exception(e)
    }

    req.data match {
      case None =>
        stream.writeHeaders(req.headers, eos = true).before {
          stream.readResponse()
        }

      // New request, with data to be sent.
      case Some(data) =>
        stream.writeHeaders(req.headers, eos = false).before {
          // Start reading a response...
          val reading = stream.readResponse()

          // Also, finish streaming the request data...
          val clock = Stopwatch.start()
          val writing = stream.writeStream(data)
          writing.onSuccess(_ => requestDurations.add(clock().inMillis))

          // If the response fails, try to stop writing data.
          reading.onFailure(writing.raise(_))

          // If the request fails, try to stop reading data.
          writing.onFailure { e =>
            log.error(e, s"client.dispatch: ${stream.id} writing error")
            reading.raise(e)
          }

          reading
        }
    }
  }

  /**
   * Continually read frames from the HTTP2 transport. Demultiplex
   * frames from the transport onto a per-stream receive queue.
   */
  private[this] val reading = {
    def loop(): Future[Unit] = {
      val readSnap = Stopwatch.start()
      transport.read().flatMap { frame =>
        transportReadTimes.add(readSnap().inMicroseconds)

        frame.streamId match {
          case 0 =>
            log.error(s"Dropping ${frame.name} message on the connection")

          case id =>
            liveStreams.get(id) match {
              case null =>
                log.error(s"Dropping ${frame.name} message on unknown stream ${id}")

              case stream =>
                stream.recvq.offer(frame)
            }
        }
        loop()
      }
    }

    loop().onFailure { e =>
      log.error(e, "client.dispatch: readLoop")
    }
  }

  private case class Stream(id: Int) {

    val recvq: AsyncQueue[Http2StreamFrame] =
      new AsyncQueue

    @volatile private[this] var isRequestFinished, isResponseFinished = false

    private[this] def clearIfClosed(): Unit =
      if (isRequestFinished && isResponseFinished) {
        val _ = liveStreams.remove(id, this)
      }

    def writeHeaders(headers: Headers, eos: Boolean = false): Future[Unit] = {
      require(!isRequestFinished)
      write(mkHeadersFrame(id, headers, eos)).ensure {
        isRequestFinished = eos
        clearIfClosed()
      }
    }

    /** Write a request stream */
    def writeStream(data: DataStream): Future[Unit] = {
      def loop(): Future[Unit] =
        data.read().flatMap {
          case DataStream.Data(buf, eos, release) =>
            val write = writeData(buf, eos = eos).before(release())
            if (eos) {
              write.ensure {
                isRequestFinished = true
                clearIfClosed()
              }
            } else write.before(loop())

          case DataStream.Trailers(trailers) =>
            writeHeaders(trailers, eos = true)
        }

      require(!isRequestFinished)
      loop()
    }

    private[this] def write(f: Http2StreamFrame): Future[Unit] = {
      val snap = Stopwatch.start()
      transport.write(f).onSuccess { _ =>
        transportWriteTimes.add(snap().inMicroseconds)
      }
    }

    private[this] def writeData(buf: Buf, eos: Boolean = false): Future[Unit] = {
      require(!isRequestFinished)
      val bb = BufAsByteBuf.Owned(buf).retain()
      write(new DefaultHttp2DataFrame(bb, eos).setStreamId(id)).ensure {
        isRequestFinished = eos
        clearIfClosed()
        val _ = bb.release()
      }
    }

    def readResponse(): Future[Response] = {
      // Start out by reading response headers from the stream
      // queue. Once a response is initialized, if data is expected,
      // continue reading from the queue until an end stream message is
      // encounetered.
      val snap = Stopwatch.start()
      recvq.poll().onSuccess { _ =>
        dequeueTimes.add(snap().inMicroseconds)
      }.map {
        case f: Http2HeadersFrame if f.isEndStream =>
          isResponseFinished = true
          clearIfClosed()
          Response(ResponseHeaders(f.headers), None)

        case f: Http2HeadersFrame =>
          val clock = Stopwatch.start()
          val sendq = new AsyncQueue[DataStream.Value]
          val data = new AQDataStream(sendq)
          val reading = readStream(sendq).ensure {
            isResponseFinished = true
            clearIfClosed
          }
          reading.onSuccess(_ => responseDurations.add(clock().inMillis))
          // reading.ensure(clearIfClosing(s, this))
          Response(ResponseHeaders(f.headers), Some(data))

        case frame =>
          // clearIfClosing(s, this)
          throw new IllegalArgumentException(s"Expected response HEADERS; received ${frame.name}")
      }
    }

    private[this] def readStream(sendq: AsyncQueue[DataStream.Value]): Future[Unit] = {
      // TODO readq.drain and aggregate
      def loop(): Future[Unit] = {
        queueLengths.add(recvq.size)
        val snap = Stopwatch.start()
        recvq.poll().onSuccess { _ =>
          dequeueTimes.add(snap().inMicroseconds)
        }.flatMap {
          case f: Http2DataFrame =>
            // Retain the data since we're writing it as an owned
            // buffer on the writer. The reader is responsible for
            // releasing buffers when they are unused.
            val buf = ByteBufAsBuf.Owned(f.content.retain())
            val sz = f.content.readableBytes + f.padding
            val release: () => Future[Unit] = () => {
              f.content.release()
              updateCapacity(sz)
            }
            sendq.offer(DataStream.Data(buf, f.isEndStream, release))
            if (f.isEndStream) {
              isResponseFinished = true
              clearIfClosed()
              Future.Unit
            } else loop()

          case f: Http2HeadersFrame if f.isEndStream =>
            sendq.offer(DataStream.Trailers(Headers(f.headers)))
            Future.Unit

          case f =>
            Future.exception(new IllegalStateException(s"Read unexpected ${f.name} frame on stream"))
        }
      }

      loop()
    }

    private[this] def updateCapacity(bytes: Int): Future[Unit] =
      write(new DefaultHttp2WindowUpdateFrame(bytes).setStreamId(id))
  }
}