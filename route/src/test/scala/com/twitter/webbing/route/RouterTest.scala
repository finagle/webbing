package com.twitter.webbing.route

import com.twitter.util.{Await, Future}
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.{AssertionsForJUnit, JUnitRunner}

@RunWith(classOf[JUnitRunner])
class RouterTest extends FunSuite with AssertionsForJUnit {
  trait TestRouter extends Router {
    final type Routable = Unit
    final type Excuse = String
    val defaultFailureExcuse = "the dog did it"
    val defaultErrorExcuse = "the dog died"

    val truthy = mkPredicate { _ => true }
    val falsey = mkPredicate { _ => false }
  }

  test("value makes a Success") {
    new TestRouter {
      val term = value(true)
      assert(Await.result(term()) === Success(true, ()))
    }
  }

  test("fail makes a Failure") {
    new TestRouter {
      val term = fail("nopers")
      assert(Await.result(term()) === Failure("nopers", ()))
    }
  }

  test("~ joins routes unless either fails") {
    new TestRouter {
      val term = value(0) ~ value("1")
      assert(Await.result(term()) === Success(new ~(0, "1"), ()))

      val leftFail = fail("left") ~ value("1")
      assert(Await.result(leftFail()) === Failure("left", ()))

      val rightFail = value(0) ~ fail("right")
      assert(Await.result(rightFail()) === Failure("right", ()))
    }
  }

  test("<~ captures the left route") {
    new TestRouter {
      val term = value(0) <~ value("1")
      assert(Await.result(term()) === Success(0, ()))
    }
  }

  test("~> captures the right route") {
    new TestRouter {
      val term = value(0) ~> value("1")
      assert(Await.result(term()) === Success("1", ()))
    }
  }

  test("/ joins routes unless either fails") {
    new TestRouter {
      val term = value(0) / value("1")
      assert(Await.result(term()) === Success(/(0, "1"), ()))

      val leftFail = fail("left") / value("1")
      assert(Await.result(leftFail()) === Failure("left", ()))

      val rightFail = value(0) / fail("right")
      assert(Await.result(rightFail()) === Failure("right", ()))
    }
  }

  test("/~> captures the right route") {
    new TestRouter {
      val term = value(0) /~> value("1")
      assert(Await.result(term()) === Success("1", ()))
    }
  }

  test("<~/ captures the left route") {
    new TestRouter {
      val term = value(0) <~/ value("1")
      assert(Await.result(term()) === Success(0, ()))
    }
  }

  test("fallback with orElse") {
    new TestRouter {
      val zeroOrOne = value(0) orElse value(1)
      assert(Await.result(zeroOrOne()) === Success(0, ()))

      val failOrOne = fail("ugh") orElse value(1)
      assert(Await.result(failOrOne()) === Success(1, ()))
    }
  }

  test("map result value") {
    new TestRouter {
      val zeroPlusOne = value(0) map { _ + 1 }
      assert(Await.result(zeroPlusOne()) === Success(1, ()))

      val zeroPlusTwo = value(0) ^^ { _ + 2 }
      assert(Await.result(zeroPlusTwo()) === Success(2, ()))

      val failPlusOne = fail("meep") map { i: Int => i + 1 }
      assert(Await.result(failPlusOne()) === Failure("meep", ()))
    }
  }

  test("route as another value") {
    new TestRouter {
      val zeroAs = value(0) as "hahah"
      assert(Await.result(zeroAs()) === Success("hahah", ()))

      val zeroAsSym = value(0) ^^^ "whoa"
      assert(Await.result(zeroAsSym()) === Success("whoa", ()))

      val failAs = fail("meep") as "weee"
      assert(Await.result(failAs()) === Failure("meep", ()))
    }
  }

  test("collect routes") {
    new TestRouter {
      val someSup = value(Some("doom")) ^? { case Some(sup) => sup.reverse }
      assert(Await.result(someSup()) === Success("mood", ()))

      val nothing = value(Option("doom")) collect { case None => "yo" }
      assert(Await.result(nothing()) === Failure(defaultFailureExcuse, ()))
    }
  }

  test("routes bind to predicates") {
    new TestRouter {
      val zero = value(0) when truthy
      assert(Await.result(zero()) === Success(0, ()))

      val zeroFails = value(0) when falsey withFailureExcuse "idk"
      assert(Await.result(zeroFails()) === Failure("idk", ()))
    }
  }

  test("route flatMap") {
    new TestRouter {
      def sum(a: Route[Int], b: Route[Int]) =
        a flatMap { i =>
          b map { _ + i }
        }
      assert(Await.result(sum(value(1), value(2))()) === Success(3, ()))
      assert(Await.result(sum(value(1), fail("ugh"))()) === Failure("ugh", ()))
      assert(Await.result(sum(fail("wagh"), value(2))()) === Failure("wagh", ()))
    }
  }

  test("route repetition") {
    new TestRouter {
      def range(ceil: Int) = {
        val iter = (0 until ceil).toIterator
        mkRoute { _ =>
          if (iter.hasNext) Future.value(Success(iter.next(), ()))
          else Future.value(Failure("no mas", ()))
        }
      }

      assert(Await.result(range(10)*()) === Success(0 until 10, ()))
      assert(Await.result(range(10)+()) === Success(0 until 10, ()))
      assert(Await.result(repN(11, range(9))()) === Failure("no mas", ()))

      assert(Await.result(fail("noway")*()) === Success(Nil, ()))
      assert(Await.result(fail("noway")+()) === Failure("noway", ()))
    }
  }

  test("opt makes a route optional") {
    new TestRouter {
      assert(Await.result(opt(fail("none"))()) === Success(None, ()))
      assert(Await.result(opt(value(0))()) === Success(Some(0), ()))
    }
  }

  test("not inverts predicate") {
    new TestRouter {
      val notFalse = not(mkPredicate { _ => false })
      assert( notFalse())
      assert(!not(notFalse)())
      assert( not(not(notFalse))())
    }
  }
}
