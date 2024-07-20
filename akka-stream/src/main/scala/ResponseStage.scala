package reactivemongo.akkastream

import scala.util.{ Failure, Success, Try }

import scala.concurrent.{ ExecutionContext, Future }

import akka.stream.{ Attributes, Outlet, SourceShape }
import akka.stream.stage.{ GraphStage, GraphStageLogic, OutHandler }

import reactivemongo.api.Cursor

import reactivemongo.core.errors.GenericDriverException
import reactivemongo.core.protocol.Response

import Cursor.ErrorHandler

private[akkastream] final class ResponseStage[T, Out](
    cursor: AkkaStreamCursorImpl[T],
    maxDocs: Int,
    suc: Response => Out,
    err: ErrorHandler[Option[Out]]
  )(implicit
    ec: ExecutionContext)
    extends GraphStage[SourceShape[Out]] {

  override val toString = "ReactiveMongoResponse"
  val out: Outlet[Out] = Outlet(s"${toString}.out")
  val shape: SourceShape[Out] = SourceShape(out)

  private val nextResponse = cursor.nextResponse(maxDocs)

  private val logger =
    reactivemongo.util.LazyLogger("reactivemongo.akkastream.ResponseStage")

  @inline
  private def next(r: Response): Future[Option[Response]] = nextResponse(ec, r)

  def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with OutHandler {
      private var last = Option.empty[(Response, Out)]

      private var request: () => Future[Option[Response]] = { () =>
        cursor
          .makeRequest(maxDocs)
          .andThen {
            case Success(_) => {
              request = { () =>
                last.fold(Future.successful(Option.empty[Response])) {
                  case (lastResponse, _) =>
                    // println(s"lastResponse = ${lastResponse.reply}")

                    next(lastResponse).andThen {
                      case Success(Some(response)) =>
                        last.foreach {
                          case (lr, _) =>
                            if (lr.reply.cursorID != response.reply.cursorID)
                              kill(lr)
                        }
                    }
                }
              }
            }
          }
          .map(Some(_))
      }

      private def killLast(): Unit = last.foreach { case (r, _) => kill(r) }

      @SuppressWarnings(Array("CatchException"))
      private def kill(r: Response): Unit = {
        try {
          cursor.wrappee.killCursor(r.reply.cursorID)
        } catch {
          case reason: Exception =>
            logger.warn(
              s"fails to kill the cursor (${r.reply.cursorID})",
              reason
            )
        }

        last = None
      }

      private def onFailure(reason: Throwable): Unit = {
        val previous = last.map(_._2)

        killLast()

        err(previous, reason) match {
          case Cursor.Cont(_) =>
            onPull()

          case Cursor.Done(_) =>
            completeStage()

          case Cursor.Fail(error) =>
            fail(out, error)

          case _ =>
            fail(out, new GenericDriverException("Erroneous cursor"))

        }
      }

      private val futureCB =
        getAsyncCallback({ (response: Try[Option[Response]]) =>
          response.map(_.map { r => r -> suc(r) }) match {
            case Failure(reason) => onFailure(reason)

            case Success(state @ Some((_, result))) => {
              last = state
              push(out, result)
            }

            case _ =>
              completeStage()
          }
        }).invoke _

      def onPull(): Unit = request().onComplete(futureCB)

      override def postStop(): Unit = {
        killLast()
        super.postStop()
      }

      setHandler(out, this)
    }
}
