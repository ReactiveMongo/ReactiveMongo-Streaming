package reactivemongo.play

import scala.concurrent.Future
import reactivemongo.api.{ Cursor, CursorProducer }

package object iteratees {
  /** Provides Play Iteratees instances for CursorProducer typeclass. */
  implicit def cursorProducer[T] = new CursorProducer[T] {
    type ProducedCursor = PlayIterateesCursor[T]

    // Returns a cursor with Play Iteratees operations.
    def produce(base: Cursor.WithOps[T]): PlayIterateesCursor[T] =
      new PlayIterateesCursorImpl[T](base)

  }

  /** Provides flattener for Play Iteratees cursor. */
  implicit object cursorFlattener
    extends reactivemongo.api.CursorFlattener[PlayIterateesCursor] {

    def flatten[T](future: Future[PlayIterateesCursor[T]]): PlayIterateesCursor[T] = new PlayIterateesFlattenedCursor(future)
  }

  /*
  import scala.language.implicitConversions
  import reactivemongo.api.bson.compat
  import scala.concurrent.ExecutionContext
  import reactivemongo.bson.{ BSONDocument => LegacyDocument }
  import reactivemongo.api.bson.BSONDocument
  import play.api.libs.iteratee.{ Iteratee, Enumeratee }

  @deprecated("Use reactivemongo-bson-api", "0.19.0")
  implicit def toDocumentIteratorIteratee[T](it: Iteratee[Iterator[LegacyDocument], T])(implicit ec: ExecutionContext): Iteratee[Iterator[BSONDocument], T] =
    Enumeratee.map[Iterator[LegacyDocument]] { _.map(compat.toDocument) }.
      transform(it)

  @deprecated("Use reactivemongo-bson-api", "0.19.0")
  implicit def toDocumentIteratee[T](it: Iteratee[LegacyDocument, T])(implicit ec: ExecutionContext): Iteratee[BSONDocument, T] =
    Enumeratee.map[LegacyDocument](compat.toDocument).transform(it)
   */
}
