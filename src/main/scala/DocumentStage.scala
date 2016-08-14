package reactivemongo.akkastream

import scala.concurrent.{ ExecutionContext, Future }

import scala.util.{ Failure, Success, Try }

import akka.stream.{ Attributes, Outlet, SourceShape }
import akka.stream.stage.{ GraphStage, GraphStageLogic, OutHandler }

import reactivemongo.core.protocol.{
  ReplyDocumentIteratorExhaustedException,
  Response
}
import reactivemongo.api.{
  Cursor,
  CursorOps
}, Cursor.{ Cont, Done, ErrorHandler, Fail }

private[akkastream] class DocumentStage[T](
  cursor: AkkaStreamCursorImpl[T],
  maxDocs: Int,
  err: ErrorHandler[Option[T]]
)(implicit ec: ExecutionContext)
    extends GraphStage[SourceShape[T]] {

  override val toString = "ReactiveMongoDocument"
  val out: Outlet[T] = Outlet(s"${toString}.out")
  val shape: SourceShape[T] = SourceShape(out)

  private val nextResponse = cursor.nextResponse(maxDocs)
  private val logger = reactivemongo.util.LazyLogger(
    "reactivemongo.akkastream.DocumentStage"
  )

  @inline
  private def nextR(r: Response): Future[Option[Response]] = nextResponse(ec, r)

  def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with OutHandler {
      private var last = Option.empty[(Response, Iterator[T], Option[T])]

      private var request: () => Future[Option[Response]] = { () =>
        cursor.makeRequest(maxDocs).andThen {
          case Success(r) => {
            request = { () =>
              last.fold(Future.successful(Option.empty[Response])) {
                case (lastResponse, _, _) => nextR(lastResponse).andThen {
                  case Success(Some(response)) => last.foreach {
                    case (lr, _, _) =>
                      if (lr.reply.cursorID != response.reply.cursorID) try {
                        cursor.wrappee kill lr.reply.cursorID
                      } catch {
                        case reason: Throwable =>
                          logger.warn("fails to kill the cursor", reason)
                      }
                  }
                }
              }
            }
          }
        }.map(Some(_))
      }

      private def kill(): Unit = last.foreach {
        case (r, _, _) =>
          try {
            cursor.wrappee kill r.reply.cursorID
          } catch {
            case reason: Throwable =>
              logger.warn("fails to kill the cursor", reason)
          }

          last = None
      }

      private def onFailure(reason: Throwable): Unit = {
        val previous = last.flatMap(_._3)
        kill()

        err(previous, reason) match {
          case Cursor.Cont(_)     => ()
          case Cursor.Fail(error) => fail(out, error)
          case Cursor.Done(_)     => completeStage()
        }
      }

      private def nextD(r: Response, bulk: Iterator[T]): Unit =
        Try(bulk.next) match {
          case Failure(reason @ ReplyDocumentIteratorExhaustedException(_)) =>
            fail(out, reason)

          case Failure(reason @ CursorOps.Unrecoverable(_)) =>
            fail(out, reason)

          case Failure(reason) => err(last.flatMap(_._3), reason) match {
            case Cont(current @ Some(v)) => {
              last = Some((r, bulk, current))
              push(out, v)
            }

            case Cont(_) => {}

            case Done(current @ Some(v)) => {
              push(out, v)
              completeStage()
            }

            case Done(_)     => completeStage()
            case Fail(cause) => fail(out, cause)
          }

          case Success(v) => {
            last = Some((r, bulk, Some(v)))
            push(out, v)
          }
        }

      private val futureCB =
        getAsyncCallback((response: Try[Option[Response]]) => {
          response match {
            case Failure(reason) => onFailure(reason)

            case Success(resp @ Some(r)) => {
              last = None

              if (r.reply.numberReturned == 0) {
                completeStage()
              } else {
                val bulkIter = cursor.documentIterator(r).
                  take(maxDocs - r.reply.startingFrom)

                nextD(r, bulkIter)
              }
            }

            case Success(_) => {
              kill()

              last = None
              completeStage()
            }
          }
        }).invoke _

      def onPull(): Unit = last match {
        case Some((r, bulk, _)) if (bulk.hasNext) =>
          nextD(r, bulk)

        case _ =>
          request().onComplete(futureCB)
      }

      // TODO: cursor.wrappee.kill(resp.reply.cursorID)

      setHandler(out, this)
    }
}
