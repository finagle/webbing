package com.twitter.webbing.route

import com.twitter.finagle.Service
import com.twitter.finagle.http.{Status, MediaType, Response}
import com.twitter.logging.Logger
import com.twitter.util.{FuturePool, Future}
import java.io.{FileInputStream, File, InputStream}
import org.apache.commons.io.IOUtils
import org.jboss.netty.buffer.{ChannelBuffer, ChannelBuffers}
import org.jboss.netty.handler.codec.http.{HttpResponse, HttpMethod}

object NettyStaticRouter {
  private val log = Logger("NettyStaticRouter")

  case class Loaded(buffer: ChannelBuffer, length: Int)
  type Loader = Service[String, Loaded]

  case class NotFoundException(path: String)
      extends Exception("Not found: %s".format(path))

  private[this] def load(input: InputStream): Loaded = {
    log.debug("loading...")
    val bytes = IOUtils.toByteArray(input)
    input.read(bytes)
    Loaded(ChannelBuffers.wrappedBuffer(bytes), bytes.length)
  }

  /** Serves files from a local file system under the given root. */
  class DirectoryLoader(
      root: File,
      pool: FuturePool = FuturePool.immediatePool)
      extends Loader {

    def apply(path: String): Future[Loaded] = pool {
      new File(root, path) match {
        case f if f.isFile && f.canRead && !f.getPath.contains("../") =>
          load(new FileInputStream(f))
        case _ => throw NotFoundException(path)
      }
    }
  }

  /** Serves packaged resources */
  class ResourcesLoader(
      obj: Any = this,
      root: String = "/",
      pool: FuturePool = FuturePool.unboundedPool)
      extends Loader {

    private[this] val log = Logger("resources")

    private[this] val cls = obj.getClass

    private[this] val cleanRoot = root.stripSuffix("/") + "/"
    private[this] def lookup(p: String) = cleanRoot + p.stripPrefix("/")

    def apply(path: String): Future[Loaded] = pool {
      val p = lookup(path)
      log.debug("getting resource: %s", p)
      Option(cls.getResourceAsStream(p)) match {
        case Some(s) if s.available > 0 =>
          load(s)
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
  def staticFileRoute(path: String): Route[Loaded] =
    mkRoute { in =>
      staticLoader(path) map(Success(_, in)) handle {
        case nfe: NotFoundException => Failure(Status.NotFound, in)
      }
    }

  /** A route that serves a static file if it exists. */
  def staticFile(path: String, contentType: Option[String]): Route[HttpResponse] = {
    log.debug("%s [%s]", path, contentType getOrElse "")
    staticFileRoute(path) map { loaded =>
      log.debug("%s loaded %d bytes", path, loaded.length)
      val rsp = Response()
      rsp.content = loaded.buffer
      rsp.contentLength = loaded.length
      contentType foreach { ct =>
        rsp.contentType = ct
      }
      rsp.httpResponse
    }
  }

  val staticRoute: Route[HttpResponse] = when(HttpMethod.GET) ~> {
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
