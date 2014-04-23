package com.twitter.webbing

import com.twitter.app.Flag
import com.twitter.finagle.http._
import com.twitter.finagle.{Service, Filter, Http}
import com.twitter.logging.Logger
import com.twitter.server.TwitterServer
import com.twitter.util.{Await, Future}
import com.twitter.webbing.route._
import java.net.InetSocketAddress
import org.apache.commons.codec.binary.Base64
import org.jboss.netty.handler.codec.http.HttpMethod._
import org.jboss.netty.handler.codec.http.{HttpRequest, HttpResponse}

/*
 * Note: this could easily be split into multiple files if there were a real application
 */

/**
 * An example web server using webbing.
 *
 * The web server is fairly simple and does the following:
 *
 *   1. Serve a toy API under /api/1
 *   2. Serve / as /index.html
 *   3. Otherwise, serve static files
 *
 * The API exposed under /api/1 is as follows:
 *
 *   GET /api/1/users
 *     return a list of users
 *
 *   GET /api/1/:username
 *     returns a list of pets the user has
 *
 *   GET /api/1/:username/:pet-type
 *     returns a list of the pets of this type
 *
 * And if api.ro is not set to true:
 *
 *   PUT /api/1/:username/:pet-type/:pet-name
 *     Add a pet to the user. must be authenticated
 */
object Example extends TwitterServer {

  /*
   * Configuration
   */

  val httpAddr: Flag[InetSocketAddress] =
    flag("http.addr",
      new InetSocketAddress(8080),
      "Web server address")

  val readOnly: Flag[Boolean] =
    flag("api.ro", false, "Make the API read-only")

  val defaultPets = Map(
    "ver" -> Set(Pet("dog", "boojum")))

  /*
   * Web server set up
   */
  def main() {
    val roApi = readOnly()
    val petDb = new PetDb(defaultPets)

    val router = new NettyHttpRouter
        with BasicAuthentication
        with ReverseUsernameAuthenticator
        with Api {
      val handlers = new PetHandlers(petDb)

      val validKinds = Set("dog", "cat", "bird", "rock")

      val authedWriteApi =
        if (roApi) fail(Status.NotFound)
        else writeApi

      val api =
        root("api" / 1) /~> (readApi | authedWriteApi)

      val apiService: Service[HttpRequest, HttpResponse] = service(api)
    }

    val addr = httpAddr()
    val addrRepr = "%s=%s:%d".format(name, addr.getAddress.getHostAddress, addr.getPort)
    log.info("serving %s", addrRepr)
    val server = Http.serve(addrRepr, router.apiService)
    closeOnExit(server)
    Await.result(server)
  }
}

object NettyToFinagle extends Filter[HttpRequest, HttpResponse, Request, Response] {
  def apply(req: HttpRequest, service: Service[Request, Response]): Future[HttpResponse] =
    service(Request(req)) map { _.httpResponse }
}

case class NoSuchUser(name: String) extends Throwable
case class AlreadyExists(user: String, pet: Pet) extends Throwable

case class Pet(kind: String, name: String)

/** A toy pet database */
class PetDb(init: Map[String, Set[Pet]]) {
  private[this] val log = Logger("projects")

  private[this] var petsByUser = init

  def users: Future[Set[String]] =
    Future.value(synchronized(petsByUser.keySet))

  def get(user: String): Future[Set[Pet]] =
    synchronized {
      petsByUser.get(user) match {
        case Some(u) => Future.value(u)
        case _ => Future.exception(NoSuchUser(user))
      }
    }

  def get(user: String, kind: String): Future[Set[String]] =
    get(user) map { pets =>
      pets filter { _.kind == kind } map { _.name }
    }

  def add(user: String, pet: Pet): Future[Unit] =
    synchronized {
      val pets = petsByUser getOrElse(user, Set.empty)
      if (pets contains pet) {
        Future exception AlreadyExists(user, pet)
      } else {
        log.info("Adding a pet to %s: %s", user, pet)
        petsByUser = petsByUser + (user -> (pets + pet))
        Future.Unit
      }
    }

}

class PetHandlers(db: PetDb) {

  def listResponse(items: Iterable[String]): HttpResponse = {
    val rsp = Response()
    val content = items.mkString("", "\n", "\n")
    rsp.contentString = content
    rsp.contentLength = content.length
    rsp.contentType = "text/plain"
    rsp
  }

  def users: Future[HttpResponse] =
    db.users map { listResponse(_) }

  def petsOf(user: String): Future[HttpResponse] =
    db.get(user) map { pets =>
      val lines = pets map { p => p.kind + " " + p.name }
      listResponse(lines)
    } handle {
      case _: NoSuchUser => Response(Status.NotFound)
    }

  def petsOf(user: String, kind: String): Future[HttpResponse] =
    db.get(user, kind) map { names =>
      listResponse(names)
    } handle {
      case _: NoSuchUser => Response(Status.NotFound)
    }

  def exists(user: String, kind: String, name: String): Future[HttpResponse] =
    db.get(user, kind) map { names =>
      Response(
        if (names(name)) Status.NoContent
        else Status.NotFound)
    } handle {
      case _: NoSuchUser => Response(Status.NotFound)
    }

  def addPet(user: String, kind: String, name: String, authedUser: String): Future[HttpResponse] =
    if (user == authedUser) {
      db.add(user, Pet(kind, name)) map { _ =>
        Response(Status.Created)
      } handle {
        case AlreadyExists(_, _) => Response(Status.Conflict)
      }
    } else Future.value(Response(Status.Unauthorized))
}

trait Api { self: NettyHttpRouter =>
  def handlers: PetHandlers

  def authenticated: Route[String]

  def validKinds: Set[String]

  val user = str
  val kind = str ^? { case k if validKinds(k) => k }
  val name = str

  lazy val readApi: Route[HttpResponse] =
    when(GET) ~>
      ( leaf("users") ^> { _ => handlers.users }
      | leaf(user)  ^> { u => handlers.petsOf(u) }
      | leaf(user / kind) ^> { case u/k   => handlers.petsOf(u, k) }
      | leaf(user / kind / name) ^> { case u/k/n => handlers.exists(u, k, n) })

  lazy val writeApi: Route[HttpResponse] =
    authenticated >> { authedUser =>
      (user / kind / name when Leaf & PUT) ^> { case u/k/n =>
        handlers.addPet(u, k, n, authedUser)
      }
    }

}

trait HokeyAuthenticator { self: NettyHttpRouter =>
  val authenticated = param("u")
}

trait BasicAuthentication { self: NettyHttpRouter =>
  val Basic = """^Basic (.+)$""".r
  val UserPass = """^([^:]+):(.*)$""".r

  object B64 {
    def unapply(b64: String): Option[String] =
      Some(new String(Base64.decodeBase64(b64), "UTF-8"))
  }

  object Authorization {
    def unapply(s: String): Option[(String, String)] = s match {
      case Basic(B64(UserPass(u, p))) => Some(u -> p)
      case _ => None
    }
  }

  def authenticate(user: String, pass: String): Future[Boolean]

  val authenticated =
    header("Authorization") asyncMap {
      case Authorization(user, pass) =>
        authenticate(user, pass) map {
          case true  => Some(user)
          case false => None
        }
      case _ => Future.value(None)
    } collect {
      case Some(u) => u
    } orElse fail(Status.Unauthorized)
}

trait ReverseUsernameAuthenticator {
  def authenticate(user: String, pass: String): Future[Boolean] =
    Future.value(pass == user.reverse)
}
