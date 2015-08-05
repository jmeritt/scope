package com.tjd.api

import scala.concurrent.{ ExecutionContext, Future, Await, Promise }
import scala.concurrent.duration._
import akka.actor.{ Actor, ActorSystem, ActorRef }
import akka.event.{ Logging, LoggingAdapter }
import akka.util.{ ByteString, Timeout }
import akka.stream.{ ActorMaterializer }
import akka.stream.scaladsl.{ Tcp, Source, Sink, Flow, RunnableGraph, FlowGraph, Broadcast, Zip }
import akka.stream.io.OutputStreamSink
import akka.stream.stage.{ StatefulStage, StageState, Context, SyncDirective }
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import spray.json._
import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport
import com.typesafe.config.{ Config, ConfigFactory }
import java.util.UUID
import java.net.InetSocketAddress
import java.io.{ PipedInputStream, PipedOutputStream }
import com.tjd.video.Transcoder

trait Protocols extends DefaultJsonProtocol with SprayJsonSupport with ScalaXmlSupport {

  implicit object UuidJsonFormat extends RootJsonFormat[UUID] {
    def write(x: UUID) = JsString(x.toString)

    def read(value: JsValue) = value match {
      case JsString(x) => UUID.fromString(x)
      case x           => deserializationError("Expected UUID as JsString, but got " + x)
    }
  }

  case class Person(id: UUID, firstName: String, lastName: String, short: String, email: String)
  implicit val personFormat = jsonFormat5(Person)

  case class CamStreamMeta(name: String, description: String, thumb: String)
  implicit val camMetaFormat = jsonFormat3(CamStreamMeta)

  case class Uplink(protocol: String, address: String, port: Int)
  implicit val uplinkFormat = jsonFormat3(Uplink)

  case class CamStream(id: UUID, synopsis: CamStreamMeta, producer: Person, uplink: Uplink)
  implicit val camFormat = jsonFormat4(CamStream)
}

trait Service extends Protocols {
  implicit val system: ActorSystem
  implicit val executor: ExecutionContext
  implicit val log: LoggingAdapter
  implicit val materializer: ActorMaterializer
  implicit val config: Config
  implicit val interface: String

  val routes = pathPrefix("cams") {
    get {
      pathEndOrSingleSlash {
        complete("OK")
      } ~
        path(JavaUUID) {
          id =>
            complete {
              <html>
                <head>
                  <title>HTTP Live Streaming Example</title>
                </head>
                <body>
                  <video controls="controls" autoplay="autoplay" src={ "http://localhost:8080/media/" + id.toString + "/" + "cam.m3u8" } height="300" width="400"/>
                </body>
              </html>
            }
        }
    } ~
      post {
        entity(as[CamStreamMeta]) {
          request =>
            handleWith {
              startTranscoder
            }
        }
      }
  } ~
    pathPrefix("media") {
      getFromBrowseableDirectory(config.getString("live.contentRoot"))
    }

  def redirectToStream(out: java.io.OutputStream) = Flow() { implicit b =>
    import FlowGraph.Implicits._

    // prepare graph elements
    val broadcast = b.add(Broadcast[ByteString](2))
    val sink = b.add(OutputStreamSink(() => out))
    val zero = b.add(Flow[ByteString].map { _ => ByteString.empty })

    // connect the graph
    broadcast.out(0) ~> sink
    broadcast.out(1) ~> zero.inlet

    // expose ports
    (broadcast.in, zero.outlet)
  }

  def startTranscoder(request: CamStreamMeta) = {
    val id = UUID.randomUUID();
    val source = Tcp().bind(config.getString("live.interface"), 0)
    val shutdownPromise = Promise[Tcp.ServerBinding]

    val handleTcpConnection = Flow[Tcp.IncomingConnection].map {
      conn =>
        val in = new java.io.PipedInputStream
        val out = new java.io.PipedOutputStream(in)
        conn.handleWith(redirectToStream(out))

        shutdownPromise.future.map { shutdown =>
          val xcoder = Transcoder(s"${config.getString("live.contentRoot")}$id", in)
          log.info("Transcoder for new cam {} exited with value:{}", id, xcoder.run)
          shutdown.unbind()
          log.info("Unbound cam {} from port {}", id, shutdown.localAddress.getPort)
        }

    }

    val bindingFuture = source.via(handleTcpConnection).to(Sink.ignore).run
    bindingFuture.map { binding =>
      log.info("Listening on tcp://{}:{}", binding.localAddress.getHostName, binding.localAddress.getPort)
      shutdownPromise.success(binding)

      CamStream(
        id,
        request,
        Person(id, "Jaime", "Meritt", "jmeritt", "jaime.meritt@gmail.com"),
        Uplink("tcp", binding.localAddress.getHostName, binding.localAddress.getPort))
    }
  }

}

object LiveMicroservice extends App with Service {

  implicit val system = ActorSystem("pornoscope-sys")
  val log: LoggingAdapter = Logging(system, getClass)
  implicit val executor: ExecutionContext = system.dispatcher
  implicit val materializer: ActorMaterializer = ActorMaterializer()

  implicit val config: Config = ConfigFactory.load()
  private val liveConfig = config.getConfig("live")
  implicit val interface = liveConfig.getString("interface")
  val port = liveConfig.getInt("httpPort")

  log.info(s"Starting server on http://$interface:$port")
  Http().bindAndHandle(handler = logRequestResult("log")(routes),
    interface = interface,
    port = port)
}