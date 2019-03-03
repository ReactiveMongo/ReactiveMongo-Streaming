package reactivemongo.akkastream

import scala.concurrent.{ ExecutionContext, Future }

import reactivemongo.core.protocol.Response
import reactivemongo.api.{
  Cursor,
  CursorOps,
  FlattenedCursor,
  WrappedCursor,
  WrappedCursorOps
}, Cursor.{ ErrorHandler, FailOnError }

import org.reactivestreams.Publisher

import akka.stream.Materializer
import akka.stream.scaladsl.{ Sink, Source }

/** For future extension */
sealed trait State {}

/** Companion object */
object State {
  private[akkastream] val materialized = Future.successful(new State {})
}

/**
 * @define errParam The binary operator to be applied when failing to get the next response. Exception or [[reactivemongo.api.Cursor$.Fail Fail]] raised within the `suc` function cannot be recovered by this error handler.
 * @define maxDocsParam the maximum number of documents to be retrieved
 * @define materializerParam the stream materializer
 * @define fanoutParam see [[http://doc.akka.io/api/akka/2.4.7/index.html#akka.stream.scaladsl.Sink$@asPublisher[T](fanout:Boolean):akka.stream.scaladsl.Sink[T,org.reactivestreams.Publisher[T]] Sink.asPublisher]] (default: false)
 * @define materialization It materializes a `Future` of [[State]] (for now with no detail, for future extension)
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
   * (see `reactivemongo.api.QueryOpts.batchSize`).
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

object AkkaStreamCursor {
  type WithOps[T] = AkkaStreamCursor[T] with CursorOps[T]
}

private[akkastream] class AkkaStreamCursorImpl[T](
    val wrappee: Cursor.WithOps[T]
) extends WrappedCursor[T] with WrappedCursorOps[T] with AkkaStreamCursor[T] {
  @inline def opsWrappee = wrappee

  def responseSource(maxDocs: Int = Int.MaxValue, err: ErrorHandler[Option[Response]] = FailOnError())(implicit m: Materializer): Source[Response, Future[State]] = {
    implicit def ec: ExecutionContext = m.executionContext

    Source.fromGraph(
      new ResponseStage[T, Response](this, maxDocs, identity[Response], err)
    ).mapMaterializedValue(_ => State.materialized)
  }

  def bulkSource(maxDocs: Int = Int.MaxValue, err: ErrorHandler[Option[Iterator[T]]] = FailOnError())(implicit m: Materializer): Source[Iterator[T], Future[State]] = {
    implicit def ec: ExecutionContext = m.executionContext

    Source.fromGraph(
      new ResponseStage[T, Iterator[T]](
        this, maxDocs, wrappee.documentIterator(_), err
      )
    ).mapMaterializedValue(_ => State.materialized)
  }

  def documentSource(maxDocs: Int = Int.MaxValue, err: ErrorHandler[Option[T]] = FailOnError())(implicit m: Materializer): Source[T, Future[State]] = {
    implicit def ec: ExecutionContext = m.executionContext

    Source.fromGraph(new DocumentStage[T](this, maxDocs, err)).
      mapMaterializedValue(_ => State.materialized)
  }
}

class AkkaStreamFlattenedCursor[T](
    val cursor: Future[AkkaStreamCursor[T]]
) extends FlattenedCursor[T](cursor) with AkkaStreamCursor[T] {

  def responseSource(maxDocs: Int = Int.MaxValue, err: ErrorHandler[Option[Response]] = FailOnError())(implicit m: Materializer): Source[Response, Future[State]] = {
    implicit def ec: ExecutionContext = m.executionContext

    Source.fromFuture(
      cursor.map(_.responseSource(maxDocs, err))
    ).flatMapMerge(1, identity).mapMaterializedValue(_ => State.materialized)
  }

  def bulkSource(maxDocs: Int = Int.MaxValue, err: ErrorHandler[Option[Iterator[T]]] = FailOnError())(implicit m: Materializer): Source[Iterator[T], Future[State]] = {
    implicit def ec: ExecutionContext = m.executionContext

    Source.fromFuture(cursor.map(_.bulkSource(maxDocs, err))).
      flatMapMerge(1, identity).mapMaterializedValue(_ => State.materialized)
  }

  def documentSource(maxDocs: Int = Int.MaxValue, err: ErrorHandler[Option[T]] = FailOnError())(implicit m: Materializer): Source[T, Future[State]] = {
    implicit def ec: ExecutionContext = m.executionContext

    Source.fromFuture(cursor.map(_.documentSource(maxDocs, err))).
      flatMapMerge(1, identity).mapMaterializedValue(_ => State.materialized)
  }
}
