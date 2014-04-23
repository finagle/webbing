package com.twitter.webbing.route

import java.net.URLDecoder
import scala.util.matching.Regex
import com.twitter.util.Future

object PathRouter {
  /** Tokenized, decoded path elements */
  type Path = Seq[String]
  object Path {
    val empty: Path = Nil

    def urldecode(path: String): Path =
      path.split("/").map(URLDecoder.decode(_, "UTF-8")).filterNot(_.isEmpty)
  }
}

trait PathRouter extends Router {

  /** A Routable that supports path consumption. */
  trait PathRoutable {
    /** True iff no path elements have been consumed */
    def isRoot: Boolean

    /** True iff there are no path elements left to consume */
    def isLeaf: Boolean

    /**
     * If there are path elements to consume, returns the next path element and an advanced Routable.
     * Otherwise None.
     */
    def headAndNext: Option[(String, Routable)]
  }

  type Routable <: PathRoutable

  protected def pathRoute[T](f: String => Option[T], e: Excuse = defaultFailureExcuse): Route[T] =
    mkRoute { r0 =>
      r0.headAndNext flatMap { case (p, r1) =>
        f(p).map(_ -> r1)
      } match {
        case Some((t, r1)) => Future.value(Success(t, r1))
        case None          => Future.value(Failure(e, r0))
      }
    }

  /** Captures a path element as a String */
  val str: Route[String] = pathRoute { p => Some(p) }

  /**
   * Lifts a raw String into a Route[String] that succeeds if the
   * next path element is equal to the raw string.
   */
  implicit def pathLiteralRoute(literal: String): Route[String] =
    pathRoute { p => Some(p) filter(_ equals literal) }

  /**
   * Lifts a raw Regex into a Route[String] that succeeds if the
   * regex matches the next path element.
   */
  implicit def pathRegexRoute(re: Regex): Route[String] =
    pathRoute { p =>
      if (re.pattern.matcher(p).matches) Some(p)
      else None
    }

  /** Captures a path element as an Int or fails. */
  val int: Route[Int] =
    pathRoute { p =>
      try Some(p.toInt)
      catch { case _: NumberFormatException => None }
    }

  /**
   * Lifts a raw Int into a Route[Int] that succeeds if the
   * the next path element is the string representation of this value.
   */
  implicit def intToPath(n: Int): Route[Int] =
    pathRoute { p =>
      try Some(p.toInt).filter(_ == n)
      catch { case _: NumberFormatException => None }
    }

  /** Captures a path element as a Long or fails. */
  val long: Route[Long] =
    pathRoute { p =>
      try Some(p.toLong)
      catch { case _: NumberFormatException => None }
    }

  /**
   * Lifts a raw Long into a Route[Long] that succeeds if the
   * the next path element is the string representation of this value.
   */
  implicit def longToPath(n: Long): Route[Long] =
    pathRoute { p =>
      try Some(p.toLong).filter(_ == n)
      catch { case _: NumberFormatException => None }
    }

  /** Captures a path element as a Double or fails. */
  val double: Route[Double] =
    pathRoute { p =>
      try Some(p.toDouble)
      catch { case _: NumberFormatException => None }
    }

  /**
   * Lifts a raw Double into a Route[Double] that succeeds if the
   * the next path element is the string representation of this value.
   */
  implicit def doubleToPath(n: Double): Route[Double] =
    pathRoute { p =>
      try Some(p.toDouble).filter(_ == n)
      catch { case _: NumberFormatException => None }
    }

/* XXX path predicates sort of make no sense?
 *
  protected def pathPredicate(f: String => Boolean, e: Excuse = defaultFailureExcuse): Predicate =
    mkPredicate { r =>
      r.headAndNext match {
        case Some((hd, _)) => f(hd)
        case _ => false
      }
    }

  implicit def pathLiteralPredicate(s: String): Predicate =
    pathPredicate(_ equals s)

  implicit def pathRegexPredicate(re: Regex): Predicate =
    pathPredicate { p => re.pattern.matcher(p).matches }
 */

  /** Ensure that no path elements have been consumed */
  val Root: Predicate = mkPredicate(_.isRoot)

  /** Ensure that there are no further path elements to consume. */
  val Leaf: Predicate = mkPredicate(_.isLeaf)

  /** Ensure that there are no routable elements */
  val Empty: Predicate = Root & Leaf

  def root[T](r: => Route[T]): Route[T] = when(Root) ~> r

  def leaf[T](r: => Route[T]): Route[T] = r when(Leaf)

  /** Ensure that the route absolutely describes a path from root to leaf */
  def absolute[T](r: Route[T]): Route[T] = root(leaf(r))
}
