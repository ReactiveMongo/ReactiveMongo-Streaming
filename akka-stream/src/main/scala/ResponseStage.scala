package reactivemongo.akkastream

import scala.concurrent.{ ExecutionContext, Future, Promise }

import scala.util.{ Failure, Success, Try }

import akka.stream.{ Attributes, Outlet, SourceShape }
import akka.stream.stage.{ GraphStageWithMaterializedValue, GraphStageLogic, OutHandler }

import reactivemongo.core.protocol.Response
import reactivemongo.api.Cursor, Cursor.ErrorHandler

private[akkastream] class ResponseStage[T, Out](
    cursor: AkkaStreamCursorImpl[T],
    maxDocs: Int,
    suc: Response => Out,
    err: ErrorHandler[Option[Out]]
)(implicit ec: ExecutionContext)
  extends GraphStageWithMaterializedValue[SourceShape[Out], Future[State]] {

  override val toString = "ReactiveMongoResponse"
  val out: Outlet[Out] = Outlet(s"${toString}.out")
  val shape: SourceShape[Out] = SourceShape(out)

  private val nextResponse = cursor.nextResponse(maxDocs)
  private val logger = reactivemongo.util.LazyLogger(
    "reactivemongo.akkastream.ResponseStage"
  )

  @inline
  private def next(r: Response): Future[Option[Response]] = nextResponse(ec, r)

  def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[State]) = {

    val shutdownPromise = Promise[State]

    (new GraphStageLogic(shape) with OutHandler {
      private var count: Long = 0L

      private var last = Option.empty[(Response, Out)]

      private var request: () => Future[Option[Response]] = { () =>
        cursor.makeRequest(maxDocs).andThen {
          case Success(_) => {
            request = { () =>
              last.fold(Future.successful(Option.empty[Response])) {
                case (lastResponse, _) => next(lastResponse).andThen {
                  case Success(Some(response)) => last.foreach {
                    case (lr, _) =>
                      if (lr.reply.cursorID != response.reply.cursorID) kill(lr)
                  }
                }
              }
            }
          }
        }.map(Some(_))
      }

      private def killLast(): Unit = last.foreach {
        case (r, _) => kill(r)
      }

      @SuppressWarnings(Array("CatchException"))
      private def kill(r: Response): Unit = {
        try {
          cursor.wrappee kill r.reply.cursorID
        } catch {
          case reason: Exception => logger.warn(
            s"fails to kill the cursor (${r.reply.cursorID})", reason
          )
        }

        last = None
      }

      private def onFailure(reason: Throwable): Unit = {
        val previous = last.map(_._2)

        err(previous, reason) match {
          case Cursor.Cont(_) =>
            ()
            killLast()
          case Cursor.Fail(error) =>
            fail(error)
          case Cursor.Done(_) =>
            stopWithError(reason)
        }
      }

      private def stop(): Unit = {
        killLast()
        shutdownPromise.success(State.Successful(count))
        completeStage()
      }

      private def stopWithError(reason: Throwable): Unit = {
        killLast()
        shutdownPromise.success(State.Failed(count, reason))
        completeStage()
      }

      private def fail(reason: Throwable): Unit = {
        killLast()
        shutdownPromise.success(State.Failed(count, reason))
        failStage(reason)
      }

      private val futureCB =
        getAsyncCallback((response: Try[Option[Response]]) => {
          response.map(_.map { r => r -> suc(r) }) match {
            case Failure(reason) => onFailure(reason)

            case Success(state @ Some((_, result))) => {
              last = state
              count += 1
              push(out, result)
            }

            case _ =>
              stop()
          }
        }).invoke _

      def onPull(): Unit = request().onComplete(futureCB)

      override def postStop(): Unit = {
        killLast()
        shutdownPromise.trySuccess(State.Successful(count))
        super.postStop()
      }

      setHandler(out, this)
    }, shutdownPromise.future)
  }
}
