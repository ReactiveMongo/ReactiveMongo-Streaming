package reactivemongo.akkastreams

import scala.concurrent.{ ExecutionContext, Future }
import reactivemongo.api.{
  Cursor, CursorProducer, FlattenedCursor, WrappedCursor
}

import org.reactivestreams.Publisher

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{ Sink, Source }

sealed trait AkkaStreamsCursor[T] extends Cursor[T] {
  /** 
   * Returns the result of cursor as a stream source. 
   * 
   * @param maxDocs the maximum number of documents to be retrieved
   */
  def source(maxDocs: Int = Int.MaxValue)(implicit ec: ExecutionContext): Source[T, NotUsed]

  /**
   * Returns a Reactive Streams publisher from this cursor.
   * 
   * @param fanout see [[http://doc.akka.io/api/akka/2.4.2/index.html#akka.stream.scaladsl.Sink$@asPublisher[T](fanout:Boolean):akka.stream.scaladsl.Sink[T,org.reactivestreams.Publisher[T]] Sink.asPublisher]]
   * @param maxDocs the maximum number of documents to be retrieved
   */
  def publisher(fanout: Boolean, maxDocs: Int = Int.MaxValue)(implicit ec: ExecutionContext, mat: Materializer): Publisher[T]
}

sealed trait AkkaPublisher[T] { cursor: AkkaStreamsCursor[T] =>
  def publisher(fanout: Boolean, maxDocs: Int = Int.MaxValue)(implicit ec: ExecutionContext, mat: Materializer): Publisher[T] = source(maxDocs).runWith(Sink.asPublisher[T](fanout))
}

class AkkaStreamsCursorImpl[T](val wrappee: Cursor[T])
    extends AkkaStreamsCursor[T] with WrappedCursor[T] with AkkaPublisher[T] {
  import Cursor.{ Cont, Fail }

  def source(maxDocs: Int = Int.MaxValue)(implicit ec: ExecutionContext): Source[T, NotUsed] = Source.fromFuture(
    wrappee.foldWhile(Source.empty[T], maxDocs)(
      (src, res) => Cont(src.concat(Source single res).
        mapMaterializedValue(_ => akka.NotUsed)),
      (_, error) => Fail(error))).flatMapMerge(1, identity)

}

class AkkaStreamsFlattenedCursor[T](
  val underlying: Future[AkkaStreamsCursor[T]])
    extends FlattenedCursor[T](underlying)
    with AkkaStreamsCursor[T] with AkkaPublisher[T] {

  def source(maxDocs: Int = Int.MaxValue)(implicit ec: ExecutionContext): Source[T, NotUsed] = Source.fromFuture(underlying.map(_.source(maxDocs))).flatMapMerge(1, identity)
}
