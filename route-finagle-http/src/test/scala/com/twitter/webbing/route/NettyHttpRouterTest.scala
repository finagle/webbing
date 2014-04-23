package com.twitter.webbing.route

import com.twitter.finagle.http.{Request, Status}
import com.twitter.util.Await
import com.twitter.webbing.route.NettyHttpRouter._
import org.jboss.netty.handler.codec.http.{HttpVersion, HttpMethod}
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.{AssertionsForJUnit, JUnitRunner}

@RunWith(classOf[JUnitRunner])
class NettyHttpRouterTest extends FunSuite with AssertionsForJUnit {

  test("routable creation") {
    new NettyHttpRouter {
      val req = Request(HttpMethod.DELETE, "/foo/bar/bah?q=a&q=b")
      req.version = HttpVersion.HTTP_1_0
      req.headerMap("x-foo") = "bar"
      assert(mkHttpRoutable(req) === HttpRoutable(
        method = HttpMethod.DELETE,
        headers = Headers("x-foo" -> "bar"),
        path = "foo" :: "bar" :: "bah" :: Nil,
        index = 0,
        params = Map("q" -> Seq("a", "b")),
        version = HttpVersion.HTTP_1_0))
    }
  }

  test("method predicates") {
    new NettyHttpRouter {
      val methods = HttpMethod.GET | HttpMethod.HEAD
      assert( methods(HttpRoutable(method = HttpMethod.GET)))
      assert( methods(HttpRoutable(method = HttpMethod.HEAD)))
      assert(!methods(HttpRoutable(method = HttpMethod.PUT)))
    }
  }

  test("capture headers") {
    new NettyHttpRouter {
      val req = HttpRoutable(headers = Headers("Foo-Bar" -> "ugh", "FOO-bar" -> "buh"))
      assert(Await.result(header("foo-bar")(req))  === Success("ugh", req))
      assert(Await.result(header("boo-far")(req))  === Failure(Status.BadRequest, req))
      assert(Await.result(headers("foo-BAR")(req)) === Success("ugh" :: "buh" :: Nil, req))
      assert(Await.result(headers("boo-FAR")(req)) === Failure(Status.BadRequest, req))
    }
  }

  test("capture params") {
    new NettyHttpRouter {
      val req = HttpRoutable(params = Params("foo-bar" -> "ugh", "foo-bar" -> "buh"))
      assert(Await.result(param("foo-bar")(req))  === Success("ugh", req))
      assert(Await.result(param("boo-far")(req))  === Failure(Status.BadRequest, req))
      assert(Await.result(params("foo-bar")(req)) === Success("ugh" :: "buh" :: Nil, req))
      assert(Await.result(params("boo-far")(req)) === Failure(Status.BadRequest, req))
    }
  }

  test("route by path") {
    new NettyHttpRouter {
      val path = "foo" / 1 /~> "bar[a-z]*".r

      assert(Await.result(path(HttpRoutable(path = "foo" :: "1" :: "barf" :: "megh" :: Nil))) ===
        Success("barf", HttpRoutable(
          path = "foo" :: "1" :: "barf" :: "megh" :: Nil,
          index = 3)))

      assert(Await.result(path(HttpRoutable(path = "foo" :: "1" :: "bar0" :: "megh" :: Nil))) ===
        Failure(Status.NotFound, HttpRoutable(
          path = "foo" :: "1" :: "bar0" :: "megh" :: Nil,
          index = 2)))
    }
  }
}
