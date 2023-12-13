package reactivemongo

import scala.concurrent.Future

import reactivemongo.api.{ Cursor, CursorFlattener, CursorProducer }

package object pekkostream {

  type PekkoCursorProducer[T] = CursorProducer[T] {
    type ProducedCursor = PekkoStreamCursor.WithOps[T]
  }

  /** Provides Pekko Streams instances for CursorProducer typeclass. */
  implicit def cursorProducer[T]: PekkoCursorProducer[T] =
    new CursorProducer[T] {
      type ProducedCursor = PekkoStreamCursor.WithOps[T]

      // Returns a cursor with Pekko Streams operations.
      def produce(c: Cursor.WithOps[T]): ProducedCursor =
        new PekkoStreamCursorImpl[T](c)
    }

  /** Provides flattener for Pekko Streams cursor. */
  implicit object cursorFlattener extends CursorFlattener[PekkoStreamCursor] {

    def flatten[T](future: Future[PekkoStreamCursor[T]]): PekkoStreamCursor[T] =
      new pekkostreamFlattenedCursor(future)
  }
}
