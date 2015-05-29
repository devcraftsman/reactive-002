package suggestions



import language.postfixOps
import scala.collection.mutable
import scala.concurrent._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Try, Success, Failure}
import rx.lang.scala._
import org.scalatest._
import gui._

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner


@RunWith(classOf[JUnitRunner])
class WikipediaApiTest extends FunSuite {

  object mockApi extends WikipediaApi {
    def wikipediaSuggestion(term: String) = Future {
      if (term.head.isLetter) {
        for (suffix <- List(" (Computer Scientist)", " (Footballer)")) yield term + suffix
      } else {
        List(term)
      }
    }
    def wikipediaPage(term: String) = Future {
      "Title: " + term
    }
  }

  import mockApi._

  test("WikipediaApi should make the stream valid using sanitized") {
    val notvalid = Observable.just("erik", "erik meijer", "martin")
    val valid = notvalid.sanitized

    var count = 0
    var completed = false

    val sub = valid.subscribe(
      term => {
        assert(term.forall(_ != ' '))
        count += 1
      },
      t => assert(false, s"stream error $t"),
      () => completed = true
    )
    assert(completed && count == 3, "completed: " + completed + ", event count: " + count)
  }
  test("WikipediaApi should correctly use concatRecovered") {
    val requests = Observable.just(1, 2, 3)
    val remoteComputation = (n: Int) => Observable.just(0 to n : _*)
    val responses = requests concatRecovered remoteComputation
    val sum = responses.foldLeft(0) { (acc, tn) =>
      tn match {
        case Success(n) => acc + n
        case Failure(t) => throw t
      }
    }
    var total = -1
    val sub = sum.subscribe {
      s => total = s
    }
    assert(total == (1 + 1 + 2 + 1 + 2 + 3), s"Sum: $total")
  }

  test("sanitize is ok"){
    val inputs = Observable.from(List("A","AB ","A B","ABC "," AB C D"))

    val observed = mutable.Buffer[String]()
    val sub = inputs.sanitized subscribe {
      observed += _
    }

    assert(observed == Seq("A", "AB", "A_B", "ABC", "AB_C_D"), observed)
  }

  test("recovered is ok"){
    val inputs:Observable[Int] = Observable.from(List(1,2,3,4,5,6))
    val errorInputs:Observable[Int] = inputs.map(elm => elm match {
      case elm if( elm % 2 == 0) => elm
      case _ => throw new IllegalArgumentException("Not Even number")
    })

    val observed = mutable.Buffer[String]()
    val sub = errorInputs.recovered.subscribe(t => t match{
      case Success(elm) => observed += "OK:"+_
      case Failure(t) => observed += "FAILED"
    })

    assert(observed == Seq("FAILED", "OK:2", "FAILED", "OK:4", "FAILED","OK:6"), observed)
  }

}
