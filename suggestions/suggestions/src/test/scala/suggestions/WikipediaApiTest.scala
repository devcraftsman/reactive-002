package suggestions


import language.postfixOps
import scala.concurrent._
import scala.concurrent.duration._
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
    val remoteComputation = (n: Int) => Observable.just(0 to n: _*)
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

  test("WikipediaApi should correctly use concatRecovered - 2") {
    val requests = Observable.just(1, 2, 3, 4, 5)
    val computation = (num: Int) => if (num != 4) Observable.just(num) else Observable.error(new Exception)
    val responses = requests concatRecovered computation
    var gotException = false
    var completed = true
    var sum: Int = 0
    responses.subscribe(
      onNext = {
        case Success(x) =>
          sum = sum + x
          println(s"In test: $x")
        case Failure(ex) =>
          gotException = true
          println(s"Found an exception: $ex")
      },

      onError = ex => {
        println(s"Found an exception: $ex"); gotException = true
      },

      onCompleted = () => completed = true

    )

    assert(sum == (11) && gotException && completed, s"Sum: $sum, gotException: $gotException, completed: $completed")
  }

}
