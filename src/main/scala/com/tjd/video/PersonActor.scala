package com.tjd.video

import akka.actor.Actor
import akka.actor.ActorLogging

/**
 * @author jmeritt
 */
object PersonActor {

}

class PersonActor extends Actor with ActorLogging {
  
  
  def receive = {
    case shout: Messages.Shout =>
      println(shout.message)

    case announce: Messages.ContributeAnnounce =>
      println(announce.total)
  }
}