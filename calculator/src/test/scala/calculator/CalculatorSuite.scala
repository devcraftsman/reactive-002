package calculator

import org.scalatest.FunSuite

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.scalatest._

import TweetLength.MaxTweetLength

@RunWith(classOf[JUnitRunner])
class CalculatorSuite extends FunSuite with ShouldMatchers {

  /******************
   ** TWEET LENGTH **
   ******************/

  def tweetLength(text: String): Int =
    text.codePointCount(0, text.length)

  test("tweetRemainingCharsCount with a constant signal") {
    val result = TweetLength.tweetRemainingCharsCount(Var("hello world"))
    assert(result() == MaxTweetLength - tweetLength("hello world"))

    val tooLong = "foo" * 200
    val result2 = TweetLength.tweetRemainingCharsCount(Var(tooLong))
    assert(result2() == MaxTweetLength - tweetLength(tooLong))
  }

  test("tweetRemainingCharsCount with a supplementary char") {
    val result = TweetLength.tweetRemainingCharsCount(Var("foo blabla \uD83D\uDCA9 bar"))
    assert(result() == MaxTweetLength - tweetLength("foo blabla \uD83D\uDCA9 bar"))
  }

  test("modifing text gives new value of lenght") {
    val tweet = Var("Hello World")
    val count = TweetLength.tweetRemainingCharsCount(tweet);
    assert(count() == MaxTweetLength - tweetLength("Hello World"))
    tweet() = "hello"
    assert(count() == MaxTweetLength - tweetLength("Hello"))
  }

  test("passing null string return maxlength") {
    val text :String = null;
    val tweet = Var(text)
    val count = TweetLength.tweetRemainingCharsCount(tweet);
    assert(count() == MaxTweetLength);

  }



  test("colorForRemainingCharsCount with a constant signal") {
    val resultGreen1 = TweetLength.colorForRemainingCharsCount(Var(52))
    assert(resultGreen1() == "green")
    val resultGreen2 = TweetLength.colorForRemainingCharsCount(Var(15))
    assert(resultGreen2() == "green")


    val resultOrange1 = TweetLength.colorForRemainingCharsCount(Var(12))
    assert(resultOrange1() == "orange")
    val resultOrange2 = TweetLength.colorForRemainingCharsCount(Var(0))
    assert(resultOrange2() == "orange")
    val resultOrange3 = TweetLength.colorForRemainingCharsCount(Var(14))
    assert(resultOrange3() == "orange")

    val resultRed1 = TweetLength.colorForRemainingCharsCount(Var(-1))
    assert(resultRed1() == "red")
    val resultRed2 = TweetLength.colorForRemainingCharsCount(Var(-5))
    assert(resultRed2() == "red")
  }

  /******************
    ** Calculator **
    ******************/

  test("eval of literal got its value"){
    val result = Calculator.eval(Literal(5),Map.empty);
    assert (result == 5)
  }

  test("eval of Ref got original Value"){
    val a = Literal(5)
    val b = Signal(Ref("a"))
    val references = Map[String,Signal[Expr]](("a" -> Signal(a)))
    val result = Calculator.eval(b(),references)
    assert (result == 5)

  }

  test("eval of non exsting ref got nan"){
    val a = Literal(5)
    val b = Signal(Ref("c"))
    val references = Map[String,Signal[Expr]](("a" -> Signal(a)))
    val result = Calculator.eval(b(),references)
    assert (result.isNaN)

  }


  test("eval plus got sum result"){
    val a = Literal(5)
    val b = Literal(5)
    val plus = Signal(Plus(a,b))
    val references = Map[String,Signal[Expr]](("a" -> Signal(a)),("b" -> Signal(b)))
    val result = Calculator.eval(plus(),references)
    assert (result == 10)
  }

  test("eval minus got difference result"){
    val a = Literal(5)
    val b = Literal(5)
    val plus = Signal(Minus(a,b))
    val references = Map[String,Signal[Expr]](("a" -> Signal(a)),("b" -> Signal(b)))
    val result = Calculator.eval(plus(),references)
    assert (result == 0)
  }


  test("eval Times got multiply result"){
    val a = Literal(5)
    val b = Literal(5)
    val plus = Signal(Times(a,b))
    val references = Map[String,Signal[Expr]](("a" -> Signal(a)),("b" -> Signal(b)))
    val result = Calculator.eval(plus(),references)
    assert (result == 25)
  }

  test("eval Divide got division result"){
    val a = Literal(5)
    val b = Literal(5)
    val plus = Signal(Divide(a,b))
    val references = Map[String,Signal[Expr]](("a" -> Signal(a)),("b" -> Signal(b)))
    val result = Calculator.eval(plus(),references)
    assert (result == 1)
  }


}
