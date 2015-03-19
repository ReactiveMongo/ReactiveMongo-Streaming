package reactivemongo.akkastreams

import scala.concurrent.{ ExecutionContext, Future }
import reactivemongo.api.{
  Cursor, CursorProducer, FlattenedCursor, WrappedCursor
}
import org.reactivestreams.Publisher
import akka.stream.FlowMaterializer
import akka.stream.scaladsl.{ Sink, Source }

sealed trait AkkaStreamsCursor[T] extends Cursor[T] {
  /** 
   * Returns the result of cursor as a stream source. 
   * 
   * @param maxDocs Maximum number of documents to be retrieved
   */
  def source(maxDocs: Int = Int.MaxValue)(implicit ec: ExecutionContext): Future[Source[T, Unit]]

  /**
   * Returns a Reactive Streams publisher from this cursor.
   * 
   * @param name Publisher name
   */
  def publisher(name: String, maxDocs: Int = Int.MaxValue)(implicit ec: ExecutionContext, fm: FlowMaterializer): Future[Publisher[T]]
}

class AkkaStreamsCursorImpl[T](val wrappee: Cursor[T])
    extends AkkaStreamsCursor[T] with WrappedCursor[T] {
  import Cursor.{ Cont, Fail }

  def source(maxDocs: Int = Int.MaxValue)(implicit ec: ExecutionContext): Future[Source[T, Unit]] = wrappee.foldWhile(Source.empty[T], maxDocs)(
    (src, res) => Cont(src.concat(Source(Future.successful(res))).
      mapMaterialized(_ => ())),
    (_, error) => Fail(error))

  def publisher(name: String, maxDocs: Int = Int.MaxValue)(implicit ec: ExecutionContext, fm: FlowMaterializer): Future[Publisher[T]] = source(maxDocs).map(_.runWith(Sink.publisher[T](name)))

}

class AkkaStreamsFlattenedCursor[T](val future: Future[AkkaStreamsCursor[T]])
    extends FlattenedCursor[T](future) with AkkaStreamsCursor[T] {

  def source(maxDocs: Int = Int.MaxValue)(implicit ec: ExecutionContext): Future[Source[T, Unit]] = future.flatMap(_.source(maxDocs))

  def publisher(name: String, maxDocs: Int = Int.MaxValue)(implicit ec: ExecutionContext, fm: FlowMaterializer): Future[Publisher[T]] = future.flatMap(_.publisher(name, maxDocs))
}
