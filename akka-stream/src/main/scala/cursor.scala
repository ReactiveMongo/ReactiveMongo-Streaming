package reactivemongo.akkastream

import scala.concurrent.{ ExecutionContext, Future }

import reactivemongo.core.protocol.Response
import reactivemongo.api.{
  Cursor,
  CursorOps,
  FlattenedCursor,
  WrappedCursor
}, Cursor.{ ErrorHandler, FailOnError }

import org.reactivestreams.Publisher

import akka.stream.Materializer
import akka.stream.scaladsl.{ Sink, Source }

/** Completion state, with number of documents emitted and possibly the last relevant error */
sealed trait State {}
object State {
  final case class Successful(count: Long) extends State
  final case class Failed(count: Long, error: Throwable) extends State
}

/**
 * @define errParam The binary operator to be applied when failing to get the next response. Exception or [[reactivemongo.api.Cursor$.Fail Fail]] raised within the `suc` function cannot be recovered by this error handler.
 * @define maxDocsParam the maximum number of documents to be retrieved
 * @define materializerParam the stream materializer
 * @define fanoutParam see [[http://doc.akka.io/api/akka/2.4.7/index.html#akka.stream.scaladsl.Sink$@asPublisher[T](fanout:Boolean):akka.stream.scaladsl.Sink[T,org.reactivestreams.Publisher[T]] Sink.asPublisher]] (default: false)
 * @define materialization It materializes a [[Future]] of [[State]] (for now with no detail, for future extension)
 */
sealed trait AkkaStreamCursor[T] extends Cursor[T] {

  /**
   * Returns a source of responses.
   * $materialization.
   *
   * @param maxDocs $maxDocsParam
   * @param err $errParam
   * @param m $materializerParam
   */
  def responseSource(maxDocs: Int = Int.MaxValue, err: ErrorHandler[Option[Response]] = FailOnError())(implicit m: Materializer): Source[Response, Future[State]]

  /**
   * Returns a Reactive Streams publisher of responses from this cursor.
   *
   * @param fanout $fanoutParam
   * @param maxDocs $maxDocsParam
   * @param err $errParam
   * @param m $materializerParam
   */
  def responsePublisher(fanout: Boolean = false, maxDocs: Int = Int.MaxValue, err: ErrorHandler[Option[Response]] = FailOnError())(implicit m: Materializer): Publisher[Response] = responseSource(maxDocs, err).runWith(Sink.asPublisher[Response](fanout))

  /**
   * Returns a source of document bulks
   * (see [[reactivemongo.api.QueryOpts.batchSize]]).
   * $materialization.
   *
   * @param maxDocs $maxDocsParam
   * @param err $errParam
   * @param m $materializerParam
   */
  def bulkSource(maxDocs: Int = Int.MaxValue, err: ErrorHandler[Option[Iterator[T]]] = FailOnError())(implicit m: Materializer): Source[Iterator[T], Future[State]]

  /**
   * Returns a Reactive Streams publisher of bulks from this cursor.
   *
   * @param fanout $fanoutParam
   * @param maxDocs $maxDocsParam
   * @param err $errParam
   * @param m $materializerParam
   */
  def bulkPublisher(fanout: Boolean = false, maxDocs: Int = Int.MaxValue, err: ErrorHandler[Option[Iterator[T]]] = FailOnError())(implicit m: Materializer): Publisher[Iterator[T]] = bulkSource(maxDocs, err).runWith(Sink.asPublisher[Iterator[T]](fanout))

  /**
   * Returns a source of documents.
   * $materialization.
   *
   * @param maxDocs $maxDocsParam
   * @param err $errParam
   * @param m $materializerParam
   */
  def documentSource(maxDocs: Int = Int.MaxValue, err: ErrorHandler[Option[T]] = FailOnError())(implicit m: Materializer): Source[T, Future[State]]

  /**
   * Returns a Reactive Streams publisher of documents from this cursor.
   *
   * @param fanout $fanoutParam
   * @param maxDocs $maxDocsParam
   * @param err $errParam
   * @param m $materializerParam
   */
  def documentPublisher(fanout: Boolean = false, maxDocs: Int = Int.MaxValue, err: ErrorHandler[Option[T]] = FailOnError())(implicit m: Materializer): Publisher[T] = documentSource(maxDocs, err).runWith(Sink.asPublisher[T](fanout))

}

private[akkastream] class AkkaStreamCursorImpl[T](
    val wrappee: Cursor[T] with CursorOps[T]
) extends WrappedCursor[T] with AkkaStreamCursor[T] {

  def responseSource(maxDocs: Int = Int.MaxValue, err: ErrorHandler[Option[Response]] = FailOnError())(implicit m: Materializer): Source[Response, Future[State]] = {
    implicit def ec: ExecutionContext = m.executionContext

    Source.fromGraph(
      new ResponseStage[T, Response](this, maxDocs, identity[Response], err)
    )
  }

  def bulkSource(maxDocs: Int = Int.MaxValue, err: ErrorHandler[Option[Iterator[T]]] = FailOnError())(implicit m: Materializer): Source[Iterator[T], Future[State]] = {
    implicit def ec: ExecutionContext = m.executionContext

    Source.fromGraph(
      new ResponseStage[T, Iterator[T]](
        this, maxDocs, wrappee.documentIterator(_), err
      )
    )
  }

  def documentSource(maxDocs: Int = Int.MaxValue, err: ErrorHandler[Option[T]] = FailOnError())(implicit m: Materializer): Source[T, Future[State]] = {
    implicit def ec: ExecutionContext = m.executionContext

    Source.fromGraph(new DocumentStage[T](this, maxDocs, err))
  }

  // ---

  private[akkastream] def makeRequest(maxDocs: Int)(implicit ctx: ExecutionContext): Future[Response] = wrappee.makeRequest(maxDocs)

  private[akkastream] def nextResponse(maxDocs: Int): (ExecutionContext, Response) => Future[Option[Response]] = wrappee.nextResponse(maxDocs)

  private[akkastream] def documentIterator(response: Response, ctx: ExecutionContext): Iterator[T] = wrappee.documentIterator(response)

}

class AkkaStreamFlattenedCursor[T](
    val cursor: Future[AkkaStreamCursor[T]]
)
  extends FlattenedCursor[T](cursor) with AkkaStreamCursor[T] {

  def responseSource(maxDocs: Int = Int.MaxValue, err: ErrorHandler[Option[Response]] = FailOnError())(implicit m: Materializer): Source[Response, Future[State]] = {
    implicit def ec: ExecutionContext = m.executionContext

    ReactiveMongoFlattenSource(cursor.map(_.responseSource(maxDocs, err))).mapMaterializedValue(_.flatMap(identity))
  }

  def bulkSource(maxDocs: Int = Int.MaxValue, err: ErrorHandler[Option[Iterator[T]]] = FailOnError())(implicit m: Materializer): Source[Iterator[T], Future[State]] = {
    implicit def ec: ExecutionContext = m.executionContext

    ReactiveMongoFlattenSource(cursor.map(_.bulkSource(maxDocs, err))).mapMaterializedValue(_.flatMap(identity))
  }

  def documentSource(maxDocs: Int = Int.MaxValue, err: ErrorHandler[Option[T]] = FailOnError())(implicit m: Materializer): Source[T, Future[State]] = {
    implicit def ec: ExecutionContext = m.executionContext

    ReactiveMongoFlattenSource(cursor.map(_.documentSource(maxDocs, err))).mapMaterializedValue(_.flatMap(identity))
  }
}
