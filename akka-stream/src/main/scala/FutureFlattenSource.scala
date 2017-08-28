package reactivemongo.akkastream

import akka.stream.{ Attributes, Graph, Outlet, SourceShape }
import akka.stream.scaladsl.{ Keep, Sink, Source }
import akka.stream.stage.{ GraphStageLogic, GraphStageWithMaterializedValue, InHandler, OutHandler }

import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.util.Try

/* Backported from akka 2.5: to be removed in favour of Source.fromFutureSource when akka 2.4 support is dropped*/
private final class ReactiveMongoFlattenSource[T, M](
    futureSource: Future[Graph[SourceShape[T], M]])
  extends GraphStageWithMaterializedValue[SourceShape[T], Future[M]] {

  val out: Outlet[T] = Outlet("ReactiveMongoFlattenSource.out")
  override val shape = SourceShape(out)

  override def initialAttributes = Attributes.name("reactiveMongoFlattenSource")

  override def createLogicAndMaterializedValue(attr: Attributes): (GraphStageLogic, Future[M]) = {
    val materialized = Promise[M]()

    val logic = new GraphStageLogic(shape) with InHandler with OutHandler {
      private val sinkIn = new SubSinkInlet[T]("ReactiveMongoFlattenSource.in")

      override def preStart(): Unit =
        futureSource.value match {
          case Some(it) ⇒
            // this optimisation avoids going through any execution context, in similar vein to FastFuture
            onFutureSourceCompleted(it)
          case _ ⇒
            val cb = getAsyncCallback[Try[Graph[SourceShape[T], M]]](onFutureSourceCompleted).invoke _
            futureSource.onComplete(cb)(sameThreadExecutionContext) // could be optimised FastFuture-like
        }

      // initial handler (until future completes)
      setHandler(out, new OutHandler {
        def onPull(): Unit = {}

        override def onDownstreamFinish(): Unit = {
          if (!materialized.isCompleted) {
            // make sure we always yield the matval if possible, even if downstream cancelled
            // before the source was materialized
            val matValFuture = futureSource.map { gr ⇒
              // downstream finish means it cancelled, so we push that signal through into the future materialized source
              Source.fromGraph(gr).to(Sink.cancelled)
                .withAttributes(attr)
                .run()(subFusingMaterializer)
            }(sameThreadExecutionContext)
            materialized.completeWith(matValFuture)
          }

          super.onDownstreamFinish()
        }
      })

      def onPush(): Unit =
        push(out, sinkIn.grab())

      def onPull(): Unit =
        sinkIn.pull()

      override def onUpstreamFinish(): Unit =
        completeStage()

      override def postStop(): Unit =
        if (!sinkIn.isClosed) sinkIn.cancel()

      def onFutureSourceCompleted(result: Try[Graph[SourceShape[T], M]]): Unit = {
        result.map { graph ⇒
          val runnable = Source.fromGraph(graph).toMat(sinkIn.sink)(Keep.left)
          val matVal = subFusingMaterializer.materialize(runnable)
          materialized.success(matVal)

          setHandler(out, this)
          sinkIn.setHandler(this)

          if (isAvailable(out)) {
            sinkIn.pull()
          }

        }.failed.foreach { t ⇒
          sinkIn.cancel()
          materialized.failure(t)
          failStage(t)
        }
      }
    }

    (logic, materialized.future)
  }

  override def toString: String = "ReactiveMongoFlattenSource"
}

private[akkastream] object ReactiveMongoFlattenSource {
  def apply[T, M](futureSource: Future[Graph[SourceShape[T], M]]) = Source.fromGraph(new ReactiveMongoFlattenSource(futureSource))
}

/**
 * WARNING: Not A General Purpose ExecutionContext!
 *
 * This is an execution context which runs everything on the calling thread.
 * It is very useful for actions which are known to be non-blocking and
 * non-throwing in order to save a round-trip to the thread pool.
 */
private object sameThreadExecutionContext extends ExecutionContext {
  override def execute(runnable: Runnable): Unit = runnable.run()
  override def reportFailure(t: Throwable): Unit =
    throw new IllegalStateException("exception in sameThreadExecutionContext", t)
}