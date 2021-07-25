package reactivemongo

import scala.concurrent.Future

import reactivemongo.api.{ Cursor, CursorFlattener, CursorProducer }

package object akkastream {
  /** Provides Akka Streams instances for CursorProducer typeclass. */
  implicit def cursorProducer[T] = new CursorProducer[T] {
    type ProducedCursor = AkkaStreamCursor.WithOps[T]

    // Returns a cursor with Akka Streams operations.
    def produce(c: Cursor.WithOps[T]): ProducedCursor =
      new AkkaStreamCursorImpl[T](c)
  }

  /** Provides flattener for Akka Streams cursor. */
  implicit object cursorFlattener extends CursorFlattener[AkkaStreamCursor] {
    def flatten[T](future: Future[AkkaStreamCursor[T]]): AkkaStreamCursor[T] =
      new AkkaStreamFlattenedCursor(future)
  }
}
