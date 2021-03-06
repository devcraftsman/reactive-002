package kvstore

import akka.actor.{OneForOneStrategy, Props, ActorRef, Actor}
import kvstore.Arbiter._
import scala.collection.immutable.Queue
import akka.actor.SupervisorStrategy.{Resume, Restart}
import scala.annotation.tailrec
import akka.pattern.{ask, pipe}
import akka.actor.Terminated
import scala.concurrent.duration._
import akka.actor.PoisonPill
import akka.actor.OneForOneStrategy
import akka.actor.SupervisorStrategy
import akka.util.Timeout
import scala.language.postfixOps

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
  var persistence: ActorRef = ActorRef.noSender;

  var replicatorWaiting = Map.empty[Long, ActorRef]

  var clientWaiting = Map.empty[Long, ActorRef]

  var persistAcks = Map.empty[Long, Persist]

  var isLeader: Boolean = false;

  var waitingTick = 0

  var waitingReplicateTick = 0

  var replicatorsReplicatedWaiting = Map.empty[Long, Set[ActorRef]];

  override val supervisorStrategy = {
    OneForOneStrategy(10, 1 minutes) {
      case _: PersistenceException => {
        Resume
      }
    }
  }


  override def preStart(): Unit = {
    context.system.scheduler.scheduleOnce(100 millis, self, "tick")
    context.system.scheduler.scheduleOnce(100 millis, self, "replicateTick")
    persistence = context.actorOf(persistenceProps)
    arbiter ! Join
  }


  def receive = {
    case JoinedPrimary => {
      isLeader = true;
      context.become(leader)
    }
    case JoinedSecondary => context.become(replica)
  }


  /* TODO Behavior for  the leader role. */
  val leader: Receive = {
    case Insert(key, value, id) => {
      kv += key -> value
      replicators.foreach(_ ! Replicate(key, Some(value), id))
      clientWaiting += id -> sender
      val persist = Persist(key, Some(value), id)
      persistAcks += id -> persist
      persistence ! persist
      replicatorsReplicatedWaiting += id -> replicators
      context.become(waitingLeaderPersist)
    }
    case Remove(key, id) => {
      kv -= key
      replicators.foreach(_ ! Replicate(key, None, id))
      clientWaiting += id -> sender
      val persist = Persist(key, None, id)
      persistAcks += id -> persist
      persistence ! persist
      context.become(waitingLeaderPersist)
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
      context.unwatch(secondaryRef)
    }


  }

  val waitingReplicated: Receive = {
    case Replicated(key, id) => {
      replicatorsReplicatedWaiting.get(id) match {
        case Some(repls) => {
          replicatorsReplicatedWaiting += id -> (repls - sender)
          if (repls.size == 1) {
            clientWaiting.get(id) match {
              case Some(client) => {
                client ! OperationAck(id)
                waitingReplicateTick = 0;
                context.become(leader)
              } // all replica finished, persistence finished => positive hack
              case None => {}
            }
          }
        }
        case None => {}
      }
    }
    case "replicateTick" => {
      waitingReplicateTick += 1
      if (waitingReplicateTick >= 10) {
        clientWaiting.foreach(p => p._2 ! OperationFailed(p._1))
        context.become(leader)
      } else {
        context.system.scheduler.scheduleOnce(100 millis, self, "replicateTick")
      }

    }

  }

  val waitingLeaderPersist: Receive = {
    case Persisted(key, id) => {
      waitingTick = 0
      if (replicators.size > 0) {
        context.become(waitingReplicated)
      } else {
        clientWaiting.get(id) match {
          case Some(client) => {
            client ! OperationAck(id)
            context.become(leader)
          }
          case None => {}
        }
      }
    }
    case Get(key, id) => {
      sender ! GetResult(key, kv get key, id)
    }
    case "tick" => {
      waitingTick += 1
      if (waitingTick < 10) {
        context.system.scheduler.scheduleOnce(100 millis, self, "tick")
        persistAcks.foreach(p => persistence ! p._2)
      } else {
        clientWaiting.foreach(p => p._2 ! OperationFailed(p._1))
      }

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
    case s: Snapshot => {
      if (s.seq <= expectedSnapshot) {
        if (s.seq == expectedSnapshot) {
          s.valueOption.fold(kv -= s.key)(kv += s.key -> _)
          val persist = Persist(s.key, s.valueOption, s.seq)
          replicatorWaiting += s.seq -> sender
          persistAcks += s.seq -> persist
          persistence ! persist
          context.become(waitingReplicaPersist)
        } else {
          sender ! SnapshotAck(s.key, s.seq)
        }
      }


    }
  }

  val waitingReplicaPersist: Receive = {
    case Persisted(key, id) => {
      expectedSnapshot += 1
      replicatorWaiting.get(id) match {
        case Some(actor) => actor ! SnapshotAck(key, id)
        case None => {}
      }
      replicatorWaiting -= id;
      persistAcks -= id
      context.become(replica)
    }
    case Get(key, id) => {
      sender ! GetResult(key, kv get key, id)
    }
    case "tick" => {
      context.system.scheduler.scheduleOnce(100 millis, self, "tick")
      persistAcks.foreach(p => persistence ! p._2)
    }
  }


}

