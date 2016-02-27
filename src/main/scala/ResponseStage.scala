package reactivemongo.akkastream

import scala.concurrent.{ ExecutionContext, Future }

import scala.util.{ Failure, Success, Try }

import akka.NotUsed
import akka.stream.{ Attributes, Outlet, SourceShape }
import akka.stream.stage.{ GraphStage, GraphStageLogic, OutHandler }

import reactivemongo.core.protocol.Response
import reactivemongo.api.Cursor, Cursor.ErrorHandler

private[akkastream] class ResponseStage[T](
  cursor: AkkaStreamCursorImpl[T],
  maxDocs: Int,
  err: ErrorHandler[Option[Response]])(implicit ec: ExecutionContext)
    extends GraphStage[SourceShape[Response]] {

  val out: Outlet[Response] = Outlet("ReactiveMongoResponse")
  val shape: SourceShape[Response] = SourceShape(out)

  private val nextResponse = cursor.nextResponse(maxDocs)

  @inline
  private def next(r: Response): Future[Option[Response]] = nextResponse(ec, r)

  def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {
      private var lastResponse = Option.empty[Response]

      private var request: () => Future[Option[Response]] = { () =>
        cursor.makeRequest(maxDocs).andThen {
          case Success(r) => {
            request = { () =>
              lastResponse.fold(Future.successful(Option.empty[Response]))(next)
            }
          }
        }.map(Some(_))
      }

      private def onFailure(reason: Throwable): Unit = {
        val previous = lastResponse
        lastResponse = None

        err(previous, reason) match {
          case Cursor.Cont(_) => ()
          case Cursor.Fail(error) => fail(out, error)
          case Cursor.Done(_) => completeStage()
        }
      }

      private def handle(response: Try[Option[Response]]): Unit = {
        response match {
          case Failure(reason) => {
            onFailure(reason)
          }

          case Success(Some(r)) => {
            lastResponse = Some(r)
            push(out, r)
          }

          case Success(_) => {
            lastResponse = None
            completeStage()
          }
        }
      }

      setHandler(out, new OutHandler {
        val asyncCallback =
          getAsyncCallback[Try[Option[Response]]](handle).invoke _

        def onPull(): Unit = request().onComplete(asyncCallback)
      })
    }
}
