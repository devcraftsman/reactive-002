package quickcheck

import common._

import org.scalacheck._
import Arbitrary._
import Gen._
import Prop._

abstract class QuickCheckHeap extends Properties("Heap") with IntHeap {

  property("min1") = forAll { a: Int =>
    val h = insert(a, empty)
    findMin(h) == a
  }

  property("delete min from empty") = forAll { a: Int =>
    val h = insert(a, empty)
    val h1 = deleteMin(h)
    isEmpty(h1) == true
  }



  property("add two element to empty always find min") = forAll { (a: Int, b: Int) =>
    val h = insert(a, empty)
    val h1 = insert(b, h)
    val min = findMin(h1)
    if (a <= b) min == a else min == b
  }


  property("minimun of melding") = forAll { (m1 :H,m2:H) =>
    val minM1 = findMin(m1);
    val minM2 = findMin(m2);
    val melded = meld(m1,m2);
    val minMeld = findMin(melded);
    if (minM1 <= minM2) minMeld == minM1 else minMeld == minM2

  }

  property("melding an empty get the original") = forAll {h:H =>
    val h1 = meld(h,empty);
    isEmpty(h1) == isEmpty(h)
  }

  property("melding an empty get the same min") = forAll {h:H =>
    val h1 = meld(h,empty);
    findMin(h1) == findMin(h)
  }

  property("adding value become min if is the minimun") = forAll{ (a:Int,h:H) =>
    val min = findMin(h)
    val h1 = insert(a,h)
    val minH1 = findMin(h1)
    if (a<= min) minH1 == a && findMin(deleteMin(h1)) == min else minH1 == min
  }

  property("insert 2 times the same value get the same min") = forAll{ a:Int =>
    val h = insert(a,empty);
    val h1 = insert(a,h);
    findMin(h) == findMin(h1) && findMin(h) == findMin(deleteMin(h1))
  }

  property("minEmpty") = forAll { a: Int =>
    val h = insert(a, empty)
    isEmpty(h) == false && isEmpty(deleteMin(h)) == true
  }

  property("recursive finding get ordered list of element") = forAll {t:H =>
    val Empty = empty;
    def findElm(h:H, list : List[Int]) : List[Int] = h match {
      case Empty => list
      case _ => findElm(deleteMin(h),findMin(h)::list);
    }

    val orderedList = findElm(t,Nil);
    orderedList == orderedList.sortWith(_>_)

  }

  property("sorting a list") = forAll { (h1:H, h2:H) =>
    val l1 = orderedListFromHeap(h1)
    l1.sorted == l1
  }
  

  property("melding and sorting") = forAll { (h1:H, h2:H) =>
    val l1 = orderedListFromHeap(h1)
    val l2 = orderedListFromHeap(h2)
    val melded = meld(h1,h2)
    val lMelded = orderedListFromHeap(melded)
    (l1 ++ l2).sorted == lMelded
  }

  def orderedListFromHeap(heap: H): List[Int] =
    if (isEmpty(heap)) Nil else findMin(heap) :: orderedListFromHeap(deleteMin(heap))


  lazy val genHeap: Gen[H] = for {
    v <- arbitrary[Int]
    h <- oneOf(const(empty), genHeap)
  } yield insert(v, h)

  implicit lazy val arbHeap: Arbitrary[H] = Arbitrary(genHeap)

}
