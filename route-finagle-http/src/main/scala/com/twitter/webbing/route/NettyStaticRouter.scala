package com.twitter.webbing.route

import com.twitter.finagle.Service
import com.twitter.finagle.httpx._
import com.twitter.io.{Buf, Reader}
import com.twitter.logging.Logger
import com.twitter.util.Future
import java.io.{FileInputStream, File, InputStream}
import org.apache.commons.io.IOUtils

object NettyStaticRouter {
  private val log = Logger("NettyStaticRouter")

  type Loader = Service[String, Buf]

  case class NotFoundException(path: String)
      extends Exception("Not found: %s".format(path))

  /** Serves files from a local file system under the given root. */
  class DirectoryLoader(root: File)
      extends Loader {

    def apply(path: String): Future[Buf] = {
      new File(root, path) match {
        case f if f.isFile && f.canRead && !f.getPath.contains("../") =>
          Reader.readAll(Reader.fromFile(f))
        case _ => throw NotFoundException(path)
      }
    }
  }

  /** Serves packaged resources */
  class ResourcesLoader(
      obj: Any = this,
      root: String = "/"
  ) extends Loader {

    private[this] val log = Logger("resources")

    private[this] val cls = obj.getClass

    private[this] val cleanRoot = root.stripSuffix("/") + "/"
    private[this] def lookup(p: String) = cleanRoot + p.stripPrefix("/")

    def apply(path: String): Future[Buf] = {
      val p = lookup(path)
      log.debug("getting resource: %s", p)
      Option(cls.getResourceAsStream(p)) match {
        case Some(s) if s.available > 0 =>
          log.debug("loading...")
          Reader.readAll(Reader.fromStream(s))
        case _ =>
          log.warning("not found: %s", p)
          throw NotFoundException(path)
      }
    }
  }

  private val Ext = """[^.]+\.(.+)""".r
}

/** A router for serving static assets */
trait NettyStaticRouter { self: NettyHttpRouter =>

  import NettyStaticRouter._

  val staticLoader: Loader

  val contentTypes: Map[String, String] = Map(
    "css" -> "text/css",
    "gif" -> "image/gif",
    "html" -> MediaType.Html,
    "jpg" -> "image/jpeg",
    "jpeg" -> "image/jpeg",
    "js" -> MediaType.Javascript,
    "json" -> MediaType.Json,
    "min.css" -> "text/css",
    "min.js" -> MediaType.Javascript,
    "png" -> "image/png",
    "txt" -> "text/plain")

  /** A route consisting of all unconsumed path segments */
  val pathSegments = (str *)

  /** A route that loads the given path if it exists */
  def staticFileRoute(path: String): Route[Buf] =
    mkRoute { in =>
      staticLoader(path) map(Success(_, in)) handle {
        case nfe: NotFoundException => Failure(Status.NotFound, in)
      }
    }

  /** A route that serves a static file if it exists. */
  def staticFile(path: String, contentType: Option[String]): Route[Response] = {
    log.debug("%s [%s]", path, contentType getOrElse "")
    staticFileRoute(path) map { loaded =>
      log.debug("%s loaded %d bytes", path, loaded.length)
      val rsp = new Response.Ok
      rsp.content = loaded
      rsp.contentLength = loaded.length
      contentType foreach { ct =>
        rsp.headerMap.set(Fields.ContentType, ct)
      }
      rsp
    }
  }

  val staticRoute: Route[Response] = when(Method.Get) ~> {
    pathSegments >> { segments =>
      val path = segments mkString "/"
      val contentType = segments.lastOption match {
        case Some(Ext(e)) => contentTypes.get(e)
        case _ => None
      }
      staticFile(path, contentType)
    }
  }

}
