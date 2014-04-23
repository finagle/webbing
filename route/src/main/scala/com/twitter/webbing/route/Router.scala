package com.twitter.webbing.route

import com.twitter.util.Future

/**
 * An abstract route combinator.
 *
 * Provides facilities for asynchronous request processing via combinators.
 */
trait Router {

  /** Input type */
  type Routable

  /** Type of value returned when not routed successfully */
  type Excuse

  /** The go-to excuse if a Routable cannot be routed. */
  def defaultFailureExcuse: Excuse
  def defaultErrorExcuse: Excuse

  /** The result of applying a Route to a Routable */
  sealed trait Result[+T] {
    /** Holds the state of a Routable at a given point in evaluation. */
    def routable: Routable

    def collectOrFail[U](f: PartialFunction[T, U], fail: T => Excuse): Result[U]
    def asyncMap[U](f: T => Future[U]): Future[Result[U]]
    def map[U](f: T => U): Result[U]
    def orElse[U >: T](r: => Future[Result[U]]): Future[Result[U]]
  }

  /** A successful Route result with a value */
  case class Success[+T](value: T, routable: Routable) extends Result[T] {

    def collectOrFail[U](pf: PartialFunction[T, U], fail: T => Excuse): Result[U] = {
      if (pf isDefinedAt value) copy(value = pf(value))
      else Failure(fail(value), routable)
    }

    def asyncMap[U](af: T => Future[U]): Future[Result[U]] = af(value) map(Success(_, routable))
    def map[U](f: T => U): Result[U] = Success(f(value), routable)
    def andThen[U](f: T => Routable => Future[Result[U]]) = f(value)(routable)
    def orElse[U >: T](r: => Future[Result[U]]) = Future.value(this)
  }

  /**
   * A Result indicating that routing was not successful.
   */
  case class Failure(excuse: Excuse, routable: Routable) extends Result[Nothing] {
    def collectOrFail[U](f: PartialFunction[Nothing, U], fail: Nothing => Excuse): Result[U] = this
    def asyncMap[U](f: Nothing => Future[U]): Future[Result[U]] = Future.value(this)
    def map[U](f: Nothing => U): Result[U] = this
    def andThen[U](f: Nothing => Routable => Future[Result[U]]) = Future.value(this)
    def orElse[U >: Nothing](other: => Future[Result[U]]) = other
  }

  /** An unrecoverable routing error. */
  trait Error extends Throwable {
    def routable: Routable
    def excuse: Excuse
  }

  object Error {
    def apply[T](e: Excuse, r: Routable): Error =
      new Error {
        val excuse = e
        val routable = r
      }

    /** Make an Error with the default Excuse */
    def apply[T](r: Routable): Error = apply(defaultErrorExcuse, r)

    /** Error matcher */
    def unapply[T](any: Any): Option[(Excuse, Routable)] =
      any match {
        case e: Error => Some(e.excuse -> e.routable)
        case _ => None
      }
  }

  /** Make a routing function a route combinator */
  protected def mkRoute[T](f: Routable => Future[Result[T]]): Route[T] =
    new Route[T]  { def apply(r: Routable) = f(r) }

  /**
   * A routing primitive. Asynchronously processes a generic Routable type into a Result.
   */
  trait Route[+T] extends (Routable => Future[Result[T]]) {

    /** Route the routable */
    def apply(routable: Routable): Future[Result[T]]

    /** Create a new Route by applying the provided function to successful results. */
    def flatMap[U](f: T => Routable => Future[Result[U]]): Route[U] =
      mkRoute { r0 =>
        this(r0) flatMap {
          case Success(t, r1) => f(t)(r1)
          case failure: Failure => Future.value(failure)
        }
      }

    /** An alias for `flatMap` */
    def >> [U](f: T => Routable => Future[Result[U]]): Route[U] = flatMap(f)

    /** Create a new Route by applying the provided asynchronous function to successful result values. */
    def asyncMap[U](f: T => Future[U]): Route[U] =
      flatMap { t => r => f(t) map(Success(_, r)) }

    /** An alias for `asyncMap` */
    def ^> [U](f: T => Future[U]): Route[U] = asyncMap(f)

    /** Map the route to a new result type */
    def map[U](f: T => U): Route[U] = asyncMap { t => Future(f(t)) }

    /** An alias for `map` */
    def ^^ [U](f: T => U): Route[U] = map(f)

    /** Discard the result of this Route and use the given value as a result. */
    def as[U](u: => U): Route[U] = {
      lazy val v = u
      map { _ => v }
    }

    /** An alias for `as` */
    def ^^^ [U](u: => U): Route[U] = as(u)

    /**
     * Succeeds with the result of the partial function if it is defined on the result value,
     * or else fails with the given excuser.
     */
    def collectOrFail[U](f: PartialFunction[T, U], e: T => Excuse): Route[U] =
      mkRoute { r0 =>
        this(r0) map(_.collectOrFail(f, e))
      }

    /** Succeeds with the result of the partial function if it is defined on the result value. */
    def collect[U](f: PartialFunction[T, U]): Route[U] =
      collectOrFail(f, _ => defaultFailureExcuse)

    /** An alias for `collect` */
    def ^? [U](f: PartialFunction[T, U]): Route[U] = collect(f)

    /** Use the other route if this one fails */
    def orElse[U >: T](other: => Route[U]): Route[U] = {
      lazy val that = other
      mkRoute { r0 =>
        this(r0) flatMap(_ orElse that(r0))
      }
    }

    /** Use another route if this one fails */
    def | [U >: T](other: => Route[U]): Route[U] = orElse(other)

    /*
     * Parser-combinator-style sequencing.
     */

    /** Sequencing */
    def ~ [U](r: => Route[U]): Route[~[T, U]] =
      for (a <- this; b <- r) yield new ~(a, b)

    /** If both this and the given route succeed, use the result of this route. */
    def <~ [U](r: => Route[U]): Route[T] =
      this ~ r ^^ { case t ~ _ => t }

    /** If both this and the given route succeed, use the result of the given route. */
    def ~> [U](r: => Route[U]): Route[U] =
      this ~ r ^^ { case _ ~ u => u }

    /*
     * Path-style sequencing
     */

    /** Sequencing for routables that represent a path. */
    def / [U](that: => Route[U]): Route[/[T, U]] =
      this ~ that ^^ { case t ~ u => new /(t, u) }

    /** If both this and the given route succeed, use the result of this route. */
    def <~/ [U](r: => Route[U]): Route[T] =
      this <~ r

    /** If both this and the given route succeed, use the result of the given route. */
    def /~> [U](r: => Route[U]): Route[U] =
      this ~> r

    /** Create a Route that fails if the provided predicate does not evaluate to true. */
    def when(predicate: => Predicate): Route[T] = {
      lazy val p = predicate
      mkRoute { r0 =>
        this(r0) map {
          case s@Success(_, r1) if p(r1) => s
          case Success(_, r1) => Failure(defaultFailureExcuse, r1)
          case failure: Failure => failure
        }
      }
    }

    /** Repeat this route zero or more times */
    def * : Route[Seq[T]] = rep(this)

    /** Repeat this route one or more times */
    def + : Route[Seq[T]] = rep1(this)

    /** An alias for `opt` */
    def ? : Route[Option[T]] = opt(this)

    /** Use the given excuse for failures */
    def withFailureExcuse(e: Excuse): Route[T] =
      mkRoute { r =>
        this(r) map {
          case Failure(_, routable) => Failure(e, routable)
          case other => other
        }
      }

    /** Use the given excuse for unrecoverable errors */
    def withErrorExcuse(e: Excuse): Route[T] =
      mkRoute { r =>
        this(r) rescue {
          case Error(_, routable) => Future.exception(Error(e, routable))
        }
      }

  }

  /** Construct a predicate from the given predicate function. */
  protected def mkPredicate(p: Routable => Boolean): Predicate =
    new Predicate { def apply(r: Routable) = p(r) }

 /** Expresses an assertion on a Routable. Cannot modify a Routable. */
 trait Predicate extends (Routable => Boolean) {
    def apply(r: Routable): Boolean

    /** Create a Predicate that is true iff both this and the provided predicate are true */
    def and(other: => Predicate): Predicate = {
      lazy val that = other
      mkPredicate { r =>
        this(r) && that(r)
      }
    }

    /** An alias for `and` */
    def & (p: => Predicate): Predicate = and(p)

    /** Create a predicate that is true if either this predicate is true or the other predicate is true. */
    def orElse(other: => Predicate): Predicate = {
      lazy val that = other
      mkPredicate { r =>
        this(r) || that(r)
      }
    }
 
    /** An alias for 'orElse' */
    def | (p: => Predicate): Predicate = orElse(p)
  }

  /** Create a Route that errors if the given Route fails. */
  def commit[T](route: => Route[T]): Route[T] = {
    lazy val next = route
    mkRoute { r0 =>
      next(r0) flatMap {
        case Failure(e, r1) => Future.exception(Error(e, r1))
        case ok => Future.value(ok)
      }
    }
  }

  /** Create a Route that always fails. */
  def fail(e: Excuse = defaultFailureExcuse): Route[Nothing] =
    mkRoute { r => Future.value(Failure(e, r)) }

  /** Create a Route that always errors */
  def error(e: Excuse = defaultErrorExcuse): Route[Nothing] =
    mkRoute { r => Future.exception(Error(e, r)) }

  /** Create a Route that always succeeds with the given value. */
  def value[T](t: => T): Route[T] = {
    lazy val v = t
    mkRoute { r => Future.value(Success(v, r)) }
  }

  /** Create an optional Route that succeeds with None if the provided Route fails. */
  def opt[T](r: => Route[T]): Route[Option[T]] =
    r map(Some(_)) orElse value(None)

  /** Succeeds if the phrase repeats zero or more times */
  def rep[T](phrase: => Route[T]): Route[Seq[T]] =
    rep1(phrase) | value(Seq.empty)

  /** Succeeds if the phrase repeats one or more times */
  def rep1[T](phrase: => Route[T]): Route[Seq[T]] =
    rep1(phrase, phrase)

  /** Succeeds if `init` succeeds and `phrase` repeats zero or more times */
  def rep1[T](init: => Route[T], phrase: => Route[T]): Route[Seq[T]] = {
    lazy val prefix = init
    lazy val repeat = phrase

    def continue(r0: Routable, accum: Seq[T]): Future[Result[Seq[T]]] =
      repeat(r0) flatMap {
        case Success(hd, r1) => continue(r1, accum :+ hd)
        case Failure(_, r1)  => Future.value(Success(accum, r1))
      }

    mkRoute { r0 =>
      prefix(r0) flatMap {
        case Success(hd, r1) => continue(r1, hd :: Nil)
        case Failure(e,  r1) => Future.value(Failure(e, r1))
      }
    }
  }

  /** Succeeds if the phrase succeeds a fixed number of times */
  def repN[T](num: Int, route: => Route[T]): Route[Seq[T]] =
    if (num == 0) value(Nil)
    else {
      lazy val repeat = route
      def continue(input: Routable, accum: Seq[T] = Nil): Future[Result[Seq[T]]] =
        if (accum.length == num) Future.value(Success(accum, input))
        else repeat(input) flatMap {
          case Success(hd, next) => continue(next, accum :+ hd)
          case Failure(e, next) => Future.value(Failure(e, next))
        }

      mkRoute(continue(_))
    }

  /** Create a Unit-route that fails if the predicate does not evaluate to true */
  def when(p: => Predicate): Route[Unit] =
    value() when(p)

  /** Negate the given Predicate. */
  def not(p: => Predicate): Predicate =
    mkPredicate(!p(_))

  case class ~[+A, +B](_1: A, _2: B)
  case class /[+A, +B](_1: A, _2: B)
}
