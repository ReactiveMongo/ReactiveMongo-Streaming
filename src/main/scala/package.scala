package reactivemongo

import scala.concurrent.Future
import reactivemongo.api.{ Cursor, CursorProducer }

package object akkastreams {
  /** Provides Akka Streams instances for CursorProducer typeclass. */
  implicit def cursorProducer[T] = new CursorProducer[T] {
    type ProducedCursor = AkkaStreamsCursor[T]

    // Returns a cursor with Akka Streams operations.
    def produce(base: Cursor[T]): AkkaStreamsCursor[T] =
      new AkkaStreamsCursorImpl[T](base)
    
  }

  /** Provides flattener for Akka Streams cursor. */
  implicit object cursorFlattener
      extends reactivemongo.api.CursorFlattener[AkkaStreamsCursor] {

    def flatten[T](future: Future[AkkaStreamsCursor[T]]): AkkaStreamsCursor[T] =
      new AkkaStreamsFlattenedCursor(future)
  }
}
