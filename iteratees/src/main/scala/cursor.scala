package reactivemongo.play.iteratees

import scala.concurrent.{ ExecutionContext, Future }

import reactivemongo.api.{
  Cursor,
  FlattenedCursor,
  WrappedCursor
}

import play.api.libs.iteratee.{ Concurrent, Enumerator }

import Cursor.{ ErrorHandler, FailOnError }

sealed trait PlayIterateesCursor[T] extends Cursor[T] {
  /**
   * Produces an Enumerator of documents.
   *
   * @param maxDocs Enumerate up to `maxDocs` documents.
   * @param err The binary operator to be applied when failing to get the next response. Exception or `Fail` raised within the `suc` function cannot be recovered by this error handler. Only the errors when reading the inputs from the DB will be handle: if then an `Iteratee` is failing to process, the error is out of this mechanism scope.
   *
   * @return an Enumerator of documents.
   */
  def enumerator(maxDocs: Int = Int.MaxValue, err: ErrorHandler[Unit] = FailOnError[Unit]())(implicit ctx: ExecutionContext): Enumerator[T]

  /**
   * Produces an Enumerator of Iterator of documents.
   *
   * @param maxDocs Enumerate up to `maxDocs` documents.
   * @param err The binary operator to be applied when failing to get the next response. Exception or `Fail` raised within the `suc` function cannot be recovered by this error handler. Only the errors when reading the inputs from the DB will be handle: if then an `Iteratee` is failing to process, the error is out of this mechanism scope.
   *
   * @return an Enumerator of Iterators of documents.
   */
  def bulkEnumerator(maxDocs: Int = Int.MaxValue, err: ErrorHandler[Unit] = FailOnError[Unit]())(implicit ctx: ExecutionContext): Enumerator[Iterator[T]]
}

final class PlayIterateesCursorImpl[T](val wrappee: Cursor[T])
  extends PlayIterateesCursor[T] with WrappedCursor[T] {
  import Cursor.{ Cont, Fail, State }

  private def errorHandler[A](chan: Concurrent.Channel[A], err: ErrorHandler[Unit]): ErrorHandler[Unit] = {
    val after: State[Unit] => State[Unit] = {
      case f @ Fail(e) => {
        chan.end(e)
        f
      }

      case st => st
    }

    (v, e) => after(err(v, e))
  }

  override def enumerator(maxDocs: Int = Int.MaxValue, err: ErrorHandler[Unit] = FailOnError[Unit]())(implicit ctx: ExecutionContext): Enumerator[T] =
    Concurrent.unicast[T] { chan =>
      wrappee.foldWhile({}, maxDocs)(
        (_, res) => Cont(chan push res), errorHandler(chan, err)).
        onComplete { case _ => chan.eofAndEnd() }
    }

  override def bulkEnumerator(maxDocs: Int = Int.MaxValue, err: ErrorHandler[Unit] = FailOnError[Unit]())(implicit ctx: ExecutionContext): Enumerator[Iterator[T]] = Concurrent.unicast[Iterator[T]] { chan =>
    wrappee.foldBulks({}, maxDocs)(
      (_, bulk) => Cont(chan push bulk), errorHandler(chan, err)).
      onComplete { case _ => chan.eofAndEnd() }
  }
}

final class PlayIterateesFlattenedCursor[T](
  cursor: Future[PlayIterateesCursor[T]]) extends FlattenedCursor[T](cursor) with PlayIterateesCursor[T] {

  override def enumerator(maxDocs: Int = Int.MaxValue, err: ErrorHandler[Unit] = FailOnError[Unit]())(implicit ctx: ExecutionContext): Enumerator[T] = Enumerator.flatten(cursor.map(_.enumerator(maxDocs, err)))

  override def bulkEnumerator(maxDocs: Int = Int.MaxValue, err: ErrorHandler[Unit] = FailOnError[Unit]())(implicit ctx: ExecutionContext): Enumerator[Iterator[T]] = Enumerator.flatten(cursor.map(_.bulkEnumerator(maxDocs, err)))
}
