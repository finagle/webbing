package com.twitter.webbing.route

import com.twitter.util.Await
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.{AssertionsForJUnit, JUnitRunner}

@RunWith(classOf[JUnitRunner])
class PathRouterTest extends FunSuite with AssertionsForJUnit {

  import PathRouter._

  trait TestPathRouter extends PathRouter {
    case class TestRoutable(
        pending: Path = Path.empty,
        routed: Path = Path.empty)
        extends PathRoutable {

      def next = headAndNext map { case (_, n) => n }
      def headAndNext = pending.headOption map { p =>
        p -> copy(pending = pending.tail, routed = routed :+ p)
      }
      def isLeaf = pending.isEmpty
      def isRoot = routed.isEmpty
    }

    final type Routable = TestRoutable

    final type Excuse = Unit
    val defaultFailureExcuse = ()
    val defaultErrorExcuse = ()
  }

  test("capture and advance path elements") {
    new TestPathRouter {
      assert(Await.result(str(TestRoutable("foo" :: "bar" :: Nil))) ===
          Success("foo", TestRoutable("bar" :: Nil, "foo" :: Nil)))
      assert(Await.result(str(TestRoutable())) === Failure((), TestRoutable()))

      assert(Await.result(int(TestRoutable("1" :: "bar" :: Nil))) ===
          Success(1, TestRoutable("bar" :: Nil, "1" :: Nil)))

      assert(Await.result(int(TestRoutable("1.1" :: "bar" :: Nil))) ===
          Failure((), TestRoutable("1.1" :: "bar" :: Nil)))

      assert(Await.result(int(TestRoutable("1" :: "bar" :: Nil))) ===
          Success(1L, TestRoutable("bar" :: Nil, "1" :: Nil)))

      assert(Await.result(double(TestRoutable("1.1" :: "bar" :: Nil))) ===
          Success(1.1, TestRoutable("bar" :: Nil, "1.1" :: Nil)))
    }
  }

  test("implicitly match strings") {
    new TestPathRouter {
      val fooAsBar = new String("foo") as "bar"

      assert(Await.result(fooAsBar(TestRoutable("foo" :: Nil))) ===
          Success("bar", TestRoutable(Nil, "foo" :: Nil)))

      assert(Await.result(fooAsBar(TestRoutable("bar" :: Nil))) ===
          Failure((), TestRoutable("bar" :: Nil)))
    }
  }

  test("implicitly match regexes") {
    new TestPathRouter {
      val fooAsBar = "foo.*".r as "bar"

      assert(Await.result(fooAsBar(TestRoutable("foobar" :: Nil))) ===
          Success("bar", TestRoutable(Nil, "foobar" :: Nil)))

      assert(Await.result(fooAsBar(TestRoutable("bar" :: Nil))) ===
          Failure((), TestRoutable("bar" :: Nil)))
    }
  }

  test("implicitly match numbers") {
    new TestPathRouter {
      val numbers = root(1) / 1.1 / 11L

      assert(Await.result(numbers(TestRoutable("1" :: "1.1" :: "11" :: Nil))) ===
          Success(/(/(1, 1.1), 11L), TestRoutable(Nil, "1" :: "1.1" :: "11" :: Nil)))

      assert(Await.result(numbers(TestRoutable("2" :: "2.2" :: "22" :: Nil))) ===
          Failure((), TestRoutable("2" :: "2.2" :: "22" :: Nil)))
    }
  }

  test("anchor predicates") {
    new TestPathRouter {
      assert( Root(TestRoutable("foo" :: Nil)))
      assert(!Root(TestRoutable(Nil, "foo" :: Nil)))

      assert(!Leaf(TestRoutable("foo" :: Nil)))
      assert( Leaf(TestRoutable(Nil, "foo" :: Nil)))

      assert( Empty(TestRoutable()))
      assert(!Empty(TestRoutable("foo" :: Nil)))

      val foobar = absolute("foo" / "bar") ^^^ "foobar"
      assert(Await.result(foobar(TestRoutable("foo" :: "bar" :: Nil))) ===
          Success("foobar", TestRoutable(Nil, "foo" :: "bar" :: Nil)))

      assert(Await.result(foobar(TestRoutable("foo" :: "bar" :: Nil, "pre" :: Nil))) ===
          Failure((), TestRoutable("foo" :: "bar" :: Nil, "pre" :: Nil)))

      assert(Await.result(foobar(TestRoutable("foo" :: "bar" :: "bah" :: Nil))) ===
          Failure((), TestRoutable("bah" :: Nil, "foo" :: "bar" :: Nil)))
    }
  }
}
