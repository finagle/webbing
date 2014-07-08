package com.twitter.webbing.route

import com.twitter.finagle.{Filter, Service}
import com.twitter.finagle.http.{Request, Status}
import com.twitter.util.Future
import com.twitter.webbing.route.PathRouter.Path
import java.net.URLEncoder
import org.jboss.netty.handler.codec.http._
import scala.collection.JavaConverters._
import scala.collection.{immutable, Map}
import com.twitter.io.{Buf, Reader}
import com.twitter.logging.Logger

object NettyHttpRouter {

  /** URI Query parameters */
  type Params = Map[String, Seq[String]]
  object Params {
    val empty: Params = Map.empty

    def apply(params: (String, String)*): Params =
      params groupBy { case (k, _) => k } mapValues { _.map { case (_, v) => v } }

    def encode(params: Params): String =
      if (params.isEmpty) ""
      else {
        params.map { case (k, v) =>
          URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(k, "UTF-8")
       }.mkString("?", "&", "")
     }
  }

  /** Map of lowercase header keys to values */
  type Headers = Map[String, Seq[String]]
  object Headers {
    val empty: Headers = Map.empty

    def apply(headers: (String, String)*): Headers =
      headers.groupBy { case (k, _) => k.toLowerCase } mapValues { kvs =>
        kvs map { case (_, v) => v }
      }

    def apply(headers: HttpHeaders): Headers =
      apply(headers.asScala.toSeq map { e => e.getKey -> e.getValue }: _*)
  }

  /** Matcher for URIs in form /path?k=v&p=q */
  object Uri {
    def unapply(uri: String): Option[(Path, Params)] = {
      val qsd = new QueryStringDecoder(uri)
      val path = Path.urldecode(qsd.getPath)
      val params = qsd.getParameters.asScala.mapValues(_.asScala)
      Some(path -> params)
    }
  }

}

/**
 * A router for netty's HttpRequest (as provided by finagle-http).
 */
trait NettyHttpRouter extends PathRouter {

  import NettyHttpRouter._

  /**
   * A partially-routed, immutable http request type.
   *
   * A routable request contains tokenized and url-decoded path elements.
   *
   * This routable type does *not* wrap netty HttpRequest because HttpRequest is mutable.
   * Immutability is necessary so that routes may be composed without side-effects.
   *
   * TODO body? -- XXX for now a string which isn't great...
   */
  case class HttpRoutable(
      method:  HttpMethod = HttpMethod.GET,
      path: Path = Path.empty,
      index:  Int = 0,
      params:  Params = Params.empty,
      headers: Headers = Headers.empty,
      version: HttpVersion = HttpVersion.HTTP_1_1,
      body: String = "")
      extends PathRoutable {

    require(0 <= index && index <= path.length)

    lazy val uri:  String = path.mkString("/", "/", "") + Params.encode(params)

    def isLeaf = (index == path.length)
    def isRoot = (index == 0)

    def advance: Option[HttpRoutable] =
      headAndNext map { case (_, next) => next }

    def headAndNext: Option[(String, HttpRoutable)] =
      if (index < path.length) {
        val head = path(index)
        val next = copy(index = index + 1)
        Some(head -> next)
      } else None
  }

  /** Build an HttpRoutable from a netty HttpRequest. */
  protected def mkHttpRoutable(request: HttpRequest) = {
    val Uri(path, params) = request.getUri
    HttpRoutable(
      method = request.getMethod,
      path = path,
      params = params,
      headers = Headers(request.headers),
      version = request.getProtocolVersion,
      body = Request(request).contentString)
  }

  /** An immutable representation of a netty Http request. */
  final type Routable = HttpRoutable

  /** On failure or error, provide an HTTP response code. */
  final type Excuse = HttpResponseStatus

  val defaultFailureExcuse = Status.NotFound
  val defaultErrorExcuse = Status.InternalServerError

  /** Get the first value of the given header, or fail. */
  def header(name: String, excuse: HttpResponseStatus = Status.BadRequest): Route[String] =
    headers(name, excuse) collect { case Seq(v, _*) => v }

  /** Get all values of the given header, or fail. */
  def headers(name: String, excuse: HttpResponseStatus = Status.BadRequest): Route[Seq[String]] =
    mkRoute { r =>
      r.headers.get(name.toLowerCase) match {
        case Some(vs) if vs.nonEmpty => Future.value(Success(vs, r))
        case _ => Future.value(Failure(excuse, r))
      }
    }

  /** Route to http method */
  val method: Route[HttpMethod] =
    mkRoute { r => Future.value(Success(r.method, r)) }

  implicit def methodPredicate(m: HttpMethod): Predicate =
    mkPredicate(_.method equals m)

  /** Get the value of the first query parameter with the given name. */
  def param(name: String, excuse: HttpResponseStatus = Status.BadRequest): Route[String] =
    params(name, excuse) collect { case Seq(v, _*) => v }

  /** Get all values of query parameters with the given name. */
  def params(name: String, excuse: HttpResponseStatus = Status.BadRequest): Route[Seq[String]] =
    mkRoute { r =>
      r.params.get(name) match {
        case Some(values) => Future.value(Success(values, r))
        case None => Future.value(Failure(excuse, r))
      }
    }

  val wholeBody: Route[String] =
    mkRoute { r => Future.value(Success(r.body, r)) }

  /** Use a Route to process requests, passing its results to a downstream service. */
  def filter[Req](route: Route[Req]): Filter[HttpRequest, HttpResponse, Req, HttpResponse] =
    Filter.mk { (request, service) =>
      val routable = mkHttpRoutable(request)
      route(routable) flatMap {
        case Success(req, _) => service(req)
        case Failure(excuse,  _) => Future.value(new DefaultHttpResponse(request.getProtocolVersion, excuse))
      } handle {
        case Error(excuse, _) => new DefaultHttpResponse(request.getProtocolVersion, excuse)
      }
    }

  /** Use a Route that produces http responses to act as an http server.. */
  def service(route: Route[HttpResponse]): Service[HttpRequest, HttpResponse] =
    filter[HttpResponse](route) andThen
    Service.mk(Future.value(_))
}
