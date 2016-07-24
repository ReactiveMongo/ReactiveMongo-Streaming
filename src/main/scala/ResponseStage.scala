package reactivemongo.akkastream

import scala.concurrent.{ ExecutionContext, Future }

import scala.util.{ Failure, Success, Try }

import akka.stream.{ Attributes, Outlet, SourceShape }
import akka.stream.stage.{ GraphStage, GraphStageLogic, OutHandler }

import reactivemongo.core.protocol.Response
import reactivemongo.api.Cursor, Cursor.ErrorHandler

private[akkastream] class ResponseStage[T, Out](
  cursor: AkkaStreamCursorImpl[T],
  maxDocs: Int,
  suc: Response => Out,
  err: ErrorHandler[Option[Out]]
)(implicit ec: ExecutionContext)
    extends GraphStage[SourceShape[Out]] {

  override val toString = "ReactiveMongoResponse"
  val out: Outlet[Out] = Outlet(s"${toString}.out")
  val shape: SourceShape[Out] = SourceShape(out)

  private val nextResponse = cursor.nextResponse(maxDocs)

  @inline
  private def next(r: Response): Future[Option[Response]] = nextResponse(ec, r)

  def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with OutHandler {
      private var last = Option.empty[(Response, Out)]

      private var request: () => Future[Option[Response]] = { () =>
        cursor.makeRequest(maxDocs).andThen {
          case Success(r) => {
            request = { () =>
              last.fold(Future.successful(Option.empty[Response])) {
                case (lastResponse, _) => next(lastResponse)
              }
            }
          }
        }.map(Some(_))
      }

      private def onFailure(reason: Throwable): Unit = {
        val previous = last.map(_._2)
        last = None

        err(previous, reason) match {
          case Cursor.Cont(_)     => ()
          case Cursor.Fail(error) => fail(out, error)
          case Cursor.Done(_)     => completeStage()
        }
      }

      private val futureCB =
        getAsyncCallback((response: Try[Option[Response]]) => {
          response.map(_.map { r => r -> suc(r) }) match {
            case Failure(reason) => onFailure(reason)

            case Success(state @ Some((_, result))) => {
              last = state
              push(out, result)
            }

            case Success(_) => {
              last = None
              completeStage()
            }
          }
        }).invoke _

      def onPull(): Unit = request().onComplete(futureCB)

      setHandler(out, this)
    }
}
