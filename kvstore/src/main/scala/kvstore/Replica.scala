package kvstore

import akka.actor.{OneForOneStrategy, Props, ActorRef, Actor}
import kvstore.Arbiter._
import scala.collection.immutable.Queue
import akka.actor.SupervisorStrategy.Restart
import scala.annotation.tailrec
import akka.pattern.{ask, pipe}
import akka.actor.Terminated
import scala.concurrent.duration._
import akka.actor.PoisonPill
import akka.actor.OneForOneStrategy
import akka.actor.SupervisorStrategy
import akka.util.Timeout

object Replica {

  sealed trait Operation {
    def key: String

    def id: Long
  }

  case class Insert(key: String, value: String, id: Long) extends Operation

  case class Remove(key: String, id: Long) extends Operation

  case class Get(key: String, id: Long) extends Operation

  sealed trait OperationReply

  case class OperationAck(id: Long) extends OperationReply

  case class OperationFailed(id: Long) extends OperationReply

  case class GetResult(key: String, valueOption: Option[String], id: Long) extends OperationReply

  def props(arbiter: ActorRef, persistenceProps: Props): Props = Props(new Replica(arbiter, persistenceProps))
}

class Replica(val arbiter: ActorRef, persistenceProps: Props) extends Actor {

  import Replica._
  import Replicator._
  import Persistence._
  import context.dispatcher

  /*
   * The contents of this actor is just a suggestion, you can implement it in any way you like.
   */

  var kv = Map.empty[String, String]
  // a map from secondary replicas to replicators
  var secondaries = Map.empty[ActorRef, ActorRef]
  // the current set of replicators
  var replicators = Set.empty[ActorRef]

  // the replica snapshotExpectedNumber
  var expectedSnapshot = 0

  override def preStart(): Unit = {
    arbiter ! Join
  }


  def receive = {
    case JoinedPrimary => context.become(leader)
    case JoinedSecondary => context.become(replica)
  }


  /* TODO Behavior for  the leader role. */
  val leader: Receive = {
    case Insert(key, value, id) => {
      kv += key -> value
      sender ! OperationAck(id)
    }
    case Remove(key, id) => {
      kv -= key
      sender ! OperationAck(id)
    }
    case Get(key, id) => {
      sender ! GetResult(key, kv get key, id)
    }
    case Replicas(replicas) => {
      replicas.filter(!_.equals(self)) // ignore primary
        .filter(!secondaries.contains(_)) // ignore already registered replicas
        .foreach(replica => {
        val replicator = context.actorOf(Replicator.props(replica))
        replicators += replicator
        secondaries += replica -> replicator;
        // start Replicate
        kv.foreach(p => replicator ! Replicate(p._1, Some(p._2), -100));
        // registering to replica stop to remove replicator
        context.watch(replica)
      })
    }


    // removing replicator when replica leaves the system
    case Terminated(secondaryRef) => {
      val replicator = secondaries get secondaryRef get; // get replicator
      replicators -= replicator;
      context.stop(replicator)
    }


  }


  /// SECONDARY

  /* TODO Behavior for the replica role. */
  val replica: Receive = {
    case Insert(key, value, id) => {
      sender ! OperationFailed(id)
    }
    case Remove(key, id) => {
      sender ! OperationFailed(id)
    }
    case Get(key, id) => {
      sender ! GetResult(key, kv get key, id)
    }
    case Snapshot(key, valueOption, seq) => {
      if (seq <= expectedSnapshot) {
        if (seq == expectedSnapshot) {
          valueOption.fold(kv -= key)(kv += key -> _)
          expectedSnapshot += 1
        }
        sender ! SnapshotAck(key, seq)
      }


    }
  }


}

