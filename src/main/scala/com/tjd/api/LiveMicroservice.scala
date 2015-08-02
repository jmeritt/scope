package com.tjd.api

import scala.concurrent.{ExecutionContext, Future, Await}
import scala.concurrent.duration._

import akka.actor.{Actor, ActorSystem, ActorRef}
import akka.event.{ Logging, LoggingAdapter }
import akka.util.{ByteString, Timeout}

import akka.stream.{ActorMaterializer}
import akka.stream.scaladsl.{Tcp, Source, Sink, Flow, FlowGraph}
import akka.stream.io.OutputStreamSink

import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import spray.json._

import com.typesafe.config.{ Config, ConfigFactory }

import java.util.UUID
import java.net.InetSocketAddress

trait Protocols extends DefaultJsonProtocol with SprayJsonSupport {

	implicit object UuidJsonFormat extends RootJsonFormat[UUID] {
		def write(x: UUID) = JsString(x.toString)

				def read(value: JsValue) = value match {
				case JsString(x) => UUID.fromString(x)
				case x           => deserializationError("Expected UUID as JsString, but got " + x)
		}
	}

case class User(id: UUID, firstName: String, lastName: String, short: String, email: String)
implicit val userFormat = jsonFormat5(User)

case class CamStreamMeta(name: String, description: String, thumb: String)
implicit val camMetaFormat = jsonFormat3(CamStreamMeta)

case class Uplink(protocol: String, address: String, port: Int)
implicit val uplinkFormat = jsonFormat3(Uplink)

case class CamStream(id: UUID, synopsis: CamStreamMeta, /*producer: User,*/ uplink: Uplink)
implicit val camFormat = jsonFormat3(CamStream)
}

//class TranscodingActor(producer: ActorRef) extends Actor with Protocols
//{
//  val transcoder = new Transcoder("TESTING")
//  
//  import context.system
//  IO(Udp) ! Udp.Bind(self, new InetSocketAddress("localhost", 0))
// 
//  def receive = {
//    case Udp.Bound(local) =>
//      val uplink = Uplink("udp", local.getHostName, local.getPort)
//      producer ! uplink
//      context.become(ready(sender))
//  }
// 
//  def ready(socket: ActorRef): Receive = {
//    case Udp.Received(data, remote) =>
//      val processed = // parse data etc., e.g. using PipelineStage
//      socket ! Udp.Send(data, remote) // example server echoes back
//    case Udp.Unbind  => socket ! Udp.Unbind
//    case Udp.Unbound => context.stop(self)
//  }
//}

trait Service extends Protocols {
	implicit val system: ActorSystem
	implicit val executor: ExecutionContext
	implicit val log: LoggingAdapter
	implicit val materializer: ActorMaterializer
	implicit val config: Config
	implicit var tcpPort: Int
	implicit val interface: String


	val routes = pathPrefix("cams") {
		get {
			pathEndOrSingleSlash {
				complete("OK")
			} ~
			path(JavaUUID) {
				id =>
				complete(s"OK, you want more $id")
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
	}  





	def startTranscoder(request: CamStreamMeta) = Future {

    import java.io.{PipedInputStream, PipedOutputStream}
    val id = UUID.randomUUID();
    val input = new PipedInputStream();
		val accumulator = new PipedOutputStream(input)
    val xcoder = new Transcoder(id.toString(), input)

		val transcodeFlow = Flow[ByteString]
    .map{
			bs =>
			bs.foreach { accumulator.write(_) }
      ByteString.empty
		}

		val connections  = Tcp().bindAndHandle(handler = transcodeFlow, interface = "localhost", port = 0)
    connections.onSuccess{
     case binding => xcoder.start 
    }
		
    val binding = Await.result(connections, (5 seconds)).asInstanceOf[Tcp.ServerBinding];
		CamStream(
				id,
				request,
				Uplink("tcp", binding.localAddress.getHostName, binding.localAddress.getPort))
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
			implicit var tcpPort = liveConfig.getInt("tcpStartPort")

			log.info(s"Starting server on http://$interface:$port")
			Http().bindAndHandle(handler = logRequestResult("log")(routes),
			interface = interface,
			port = port)
}