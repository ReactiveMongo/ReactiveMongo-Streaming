package reactivemongo.akkastream

import scala.concurrent.{ ExecutionContext, Future }

import reactivemongo.core.protocol.Response
import reactivemongo.api.{
  Cursor, FlattenedCursor, WrappedCursor
}, Cursor.{ ErrorHandler, FailOnError }

import org.reactivestreams.Publisher

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{ Flow, Sink, Source }

/**
  * @define errDescription The binary operator to be applied when failing to get the next response. Exception or [[reactivemongo.api.Cursor$.Fail Fail]] raised within the `suc` function cannot be recovered by this error handler.
  * @define maxDocs the maximum number of documents to be retrieved
  */
sealed trait AkkaStreamCursor[T] extends Cursor[T] {
  /** 
   * Returns a source of responses.
   * 
   * @param maxDocs $maxDocs
   * @param err $errDescription
   */
  def responseSource(maxDocs: Int = Int.MaxValue, err: ErrorHandler[Option[Response]] = FailOnError())(implicit m: Materializer): Source[Response, NotUsed]

  /**
   * Returns a Reactive Streams publisher of responses from this cursor.
   * 
   * @param fanout see [[http://doc.akka.io/api/akka/2.4.7/index.html#akka.stream.scaladsl.Sink$@asPublisher[T](fanout:Boolean):akka.stream.scaladsl.Sink[T,org.reactivestreams.Publisher[T]] Sink.asPublisher]]
   * @param maxDocs $maxDocs
   * @param err $errDescription
   */
  def responsePublisher(fanout: Boolean, maxDocs: Int = Int.MaxValue, err: ErrorHandler[Option[Response]] = FailOnError())(implicit m: Materializer): Publisher[Response] = responseSource(maxDocs, err).runWith(Sink.asPublisher[Response](fanout))

  def bulkSource(maxDocs: Int = Int.MaxValue, err: ErrorHandler[Iterator[T]] = FailOnError())(implicit m: Materializer): Source[Iterator[T], NotUsed]

}

private[akkastream] class AkkaStreamCursorImpl[T](
  val wrappee: Cursor[T]) extends WrappedCursor[T] with AkkaStreamCursor[T] {

  def responseSource(maxDocs: Int = Int.MaxValue, err: ErrorHandler[Option[Response]] = FailOnError())(implicit m: Materializer): Source[Response, NotUsed] = {
    implicit def ec: ExecutionContext = m.executionContext
    Source.fromGraph(new ResponseStage[T](this, maxDocs, err))
  }

  def bulkSource(maxDocs: Int = Int.MaxValue, err: ErrorHandler[Iterator[T]] = FailOnError())(implicit m: Materializer): Source[Iterator[T], NotUsed] = {
    implicit def ec: ExecutionContext = m.executionContext
    def responses: Source[Response, NotUsed] =
      Source.fromGraph(new ResponseStage[T](this, maxDocs, {
        (lastResponse, reason) => ???
      }))

    val parallelism = 1 // TODO

    //def foo(r: Response) = this.documentIterator(r.reply, r.documents)

    ???
  }
}

class AkkaStreamFlattenedCursor[T](
  val cursor: Future[AkkaStreamCursor[T]])
    extends FlattenedCursor[T](cursor) with AkkaStreamCursor[T] {

  def responseSource(maxDocs: Int = Int.MaxValue, err: ErrorHandler[Option[Response]] = FailOnError())(implicit m: Materializer): Source[Response, NotUsed] = {
    implicit def ec: ExecutionContext = m.executionContext

    Source.fromFuture(
      cursor.map(_.responseSource(maxDocs, err))).flatMapMerge(1, identity)
  }

  def bulkSource(maxDocs: Int = Int.MaxValue, err: ErrorHandler[Iterator[T]] = FailOnError())(implicit m: Materializer): Source[Iterator[T], NotUsed] = {
    implicit def ec: ExecutionContext = m.executionContext

    Source.fromFuture(
      cursor.map(_.bulkSource(maxDocs, err))).flatMapMerge(1, identity)
  }
}
