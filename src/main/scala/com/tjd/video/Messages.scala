package com.tjd.video
import akka.actor.ActorRef

/**
 * @author jmeritt
 */
object Messages {
  case class Person(id: String, firstName: String, lastName: String, short: String, email: String)
  case class CamStreamMeta(name: String, description: String, thumb: String)
  case class Uplink(protocol: String, address: String, port: Int)
  case class CamStream(id: String, synopsis: CamStreamMeta, producer: Person, uplink: Uplink)

  case class Enter(person: Person, actor: ActorRef)
  case class Leave(person: Person)
  case class Shout(person: Person, message: String)
  case class Contribute(person: Person, amount: Int)
  case class ContributeAnnounce(contrib: Contribute, total: Int)

}