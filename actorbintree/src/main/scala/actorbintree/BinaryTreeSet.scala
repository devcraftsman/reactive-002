/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package actorbintree

import akka.actor._
import scala.collection.immutable.Queue

object BinaryTreeSet {

  trait Operation {
    def requester: ActorRef

    def id: Int

    def elem: Int
  }

  trait OperationReply {
    def id: Int
  }

  /** Request with identifier `id` to insert an element `elem` into the tree.
    * The actor at reference `requester` should be notified when this operation
    * is completed.
    */
  case class Insert(requester: ActorRef, id: Int, elem: Int) extends Operation

  /** Request with identifier `id` to check whether an element `elem` is present
    * in the tree. The actor at reference `requester` should be notified when
    * this operation is completed.
    */
  case class Contains(requester: ActorRef, id: Int, elem: Int) extends Operation

  /** Request with identifier `id` to remove the element `elem` from the tree.
    * The actor at reference `requester` should be notified when this operation
    * is completed.
    */
  case class Remove(requester: ActorRef, id: Int, elem: Int) extends Operation

  /** Request to perform garbage collection */
  case object GC

  /** Holds the answer to the Contains request with identifier `id`.
    * `result` is true if and only if the element is present in the tree.
    */
  case class ContainsResult(id: Int, result: Boolean) extends OperationReply

  /** Message to signal successful completion of an insert or remove operation. */
  case class OperationFinished(id: Int) extends OperationReply

}


class BinaryTreeSet extends Actor with Stash {

  import BinaryTreeSet._
  import BinaryTreeNode._

  def createRoot: ActorRef = context.actorOf(BinaryTreeNode.props(0, initiallyRemoved = true))

  var root = createRoot

  // optional
  var pendingQueue = Queue.empty[Operation]

  // optional
  def receive = normal

  // optional
  /** Accepts `Operation` and `GC` messages. */
  val normal: Receive = {
    case op: Operation => root ! op
      case GC => {
        val newRoot = context.actorOf(BinaryTreeNode.props(0, initiallyRemoved = true))
        root ! CopyTo(newRoot)
        context become garbageCollecting(newRoot)
      }

  }

  // optional

  /** Handles messages while garbage collection is performed.
    * `newRoot` is the root of the new binary tree where we want to copy
    * all non-removed elements into.
    */
  def garbageCollecting(newRoot: ActorRef): Receive = {
    case op : Operation =>  stash()

    case CopyFinished => {
      // switch root
      root ! PoisonPill
      root = newRoot

      unstashAll()

      context.become(normal)
      // empty the queue

    }

  }

}

object BinaryTreeNode {

  trait Position

  case object Left extends Position

  case object Right extends Position

  case class CopyTo(treeNode: ActorRef)

  case object CopyFinished

  def props(elem: Int, initiallyRemoved: Boolean) = Props(classOf[BinaryTreeNode], elem, initiallyRemoved)
}

class BinaryTreeNode(val elem: Int, initiallyRemoved: Boolean) extends Actor {

  import BinaryTreeNode._
  import BinaryTreeSet._

  var subtrees = Map[Position, ActorRef]()
  var removed = initiallyRemoved

  // optional
  def receive = normal

  // optional
  /** Handles `Operation` messages and `CopyTo` requests. */
  val normal: Receive = {
    case op: Operation if (op.elem < elem && subtrees.isDefinedAt(Left)) => subtrees(Left) ! op
    case op: Operation if (op.elem > elem && subtrees.isDefinedAt(Right)) => subtrees(Right) ! op

    case Insert(req, id, el) if (el == elem) => {
      removed = false
      req ! OperationFinished(id)
    }
    case Insert(req, id, el) if (el < elem) => {
      val left = context.actorOf(props(el, false))
      subtrees = subtrees + ((Left, left))
      req ! OperationFinished(id)
    }
    case Insert(req, id, el) if (el > elem) => {
      val right = context.actorOf(props(el, false))
      subtrees = subtrees + ((Right, right))
      req ! OperationFinished(id)
    }

    case Contains(req, id, el) if (el == elem) => req ! ContainsResult(id, !removed)
    case Contains(req, id, _) => req ! ContainsResult(id, false)

    case Remove(req, id, el) => {
      removed ||= el == elem
      req ! OperationFinished(id)
    }

    case CopyTo(newRoot) =>{
        val descendant = Set[ActorRef]() ++ subtrees.values
        removed match {
          case true if (descendant.isEmpty) => {
            context.parent ! CopyFinished
          } // no child
          case _ => {
            if (!removed) newRoot ! Insert(self,-1,elem)
            descendant foreach( _ ! CopyTo(newRoot))
            context.become(copying(descendant,removed))
          }
        }
    }

  }

  /** `expected` is the set of ActorRefs whose replies we are waiting for,
    * `insertConfirmed` tracks whether the copy of this node to the new tree has been confirmed.
    */
  def copying(expected: Set[ActorRef], insertConfirmed: Boolean): Receive = {
    case CopyFinished => expected.size match {
      // our (ONLY) child is done, and so is our self insert!
      case size: Int if (size <= 1)   => context.parent ! CopyFinished
      case _ => context.become(copying(expected - sender, insertConfirmed))
    }

    case OperationFinished(-1) => {
      expected.size match {
        // we're not expecting anything, and we just heard that our
        // insert is done
        case 0 => context.parent ! CopyFinished
        // we're expecting something, but at least our self insert is
        // done
        case _ => context.become(copying(expected, true))
      }
    }

  }


}
