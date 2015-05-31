package kvstore

import akka.actor.Props
import akka.actor.Actor
import akka.actor.ActorRef
import scala.concurrent.duration._
import scala.language.postfixOps

object Replicator {
  case class Replicate(key: String, valueOption: Option[String], id: Long)
  case class Replicated(key: String, id: Long)
  
  case class Snapshot(key: String, valueOption: Option[String], seq: Long)
  case class SnapshotAck(key: String, seq: Long)

  def props(replica: ActorRef): Props = Props(new Replicator(replica))
}

class Replicator(val replica: ActorRef) extends Actor {
  import Replicator._
  import Replica._
  import context.dispatcher
  
  /*
   * The contents of this actor is just a suggestion, you can implement it in any way you like.
   */

  override def preStart() =
    context.system.scheduler.scheduleOnce(100 millis, self, "tick")

  override def postRestart(reason: Throwable) = {}



  // map from sequence number to pair of sender and request
  var acks = Map.empty[Long, (ActorRef, Replicate)]

  var _seqCounter = 0L
  def nextSeq = {
    val ret = _seqCounter
    _seqCounter += 1
    ret
  }

  
  /* TODO Behavior for the Replicator. */
  def receive: Receive = {
    case r: Replicate => {
      val sequenceNumber = nextSeq
      acks += sequenceNumber ->(sender, r)
      val snapshot =
        replica ! Snapshot(r.key, r.valueOption, sequenceNumber)
    }
    case "tick" => {
      context.system.scheduler.scheduleOnce(100 millis, self, "tick")
      acks.foreach(p => replica ! Snapshot(p._2._2.key, p._2._2.valueOption, p._1))
    }

    case SnapshotAck(key, seq) => {
      acks.get(seq).fold()(p => p._1 ! Replicated(p._2.key, p._2.id))
      acks -= seq;

    }
  }


}
