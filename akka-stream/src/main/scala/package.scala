package reactivemongo

import scala.concurrent.Future
import reactivemongo.api.{ Cursor, CursorProducer, CursorOps }

package object akkastream {
  /** Provides Akka Streams instances for CursorProducer typeclass. */
  implicit def cursorProducer[T] = new CursorProducer[T] {
    type BaseCursor = Cursor[T] with CursorOps[T]
    type ProducedCursor = AkkaStreamCursor[T]

    // Returns a cursor with Akka Streams operations.
    def produce(base: Cursor[T]): AkkaStreamCursor[T] = base match {
      case c: BaseCursor @unchecked => new AkkaStreamCursorImpl[T](c)
      case c =>
        sys.error(s"expected Cursor with CursorOps, got ${c.getClass.getName}")
    }
  }

  /** Provides flattener for Akka Streams cursor. */
  implicit object cursorFlattener
      extends reactivemongo.api.CursorFlattener[AkkaStreamCursor] {

    def flatten[T](future: Future[AkkaStreamCursor[T]]): AkkaStreamCursor[T] =
      new AkkaStreamFlattenedCursor(future)
  }
}
