package reactivemongo

import scala.concurrent.Future
import reactivemongo.api.{ Cursor, CursorProducer }

package object akkastream {
  /** Provides Akka Streams instances for CursorProducer typeclass. */
  implicit def cursorProducer[T] = new CursorProducer[T] {
    type ProducedCursor = AkkaStreamCursor[T]

    // Returns a cursor with Akka Streams operations.
    def produce(base: Cursor[T]): AkkaStreamCursor[T] =
      new AkkaStreamCursorImpl[T](base)
    
  }

  /** Provides flattener for Akka Streams cursor. */
  implicit object cursorFlattener
      extends reactivemongo.api.CursorFlattener[AkkaStreamCursor] {

    def flatten[T](future: Future[AkkaStreamCursor[T]]): AkkaStreamCursor[T] =
      new AkkaStreamFlattenedCursor(future)
  }
}
