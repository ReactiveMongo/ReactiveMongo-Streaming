package reactivemongo.akkastream

import scala.concurrent.{ ExecutionContext, Future }

import scala.util.{ Failure, Success, Try }

import akka.stream.{ Attributes, Outlet, SourceShape }
import akka.stream.stage.{ GraphStage, GraphStageLogic, OutHandler }

import reactivemongo.core.errors.GenericDriverException
import reactivemongo.core.protocol.{
  ReplyDocumentIteratorExhaustedException,
  Response
}
import reactivemongo.api.{
  Cursor,
  CursorOps
}, Cursor.{ Cont, Done, ErrorHandler, Fail }

private[akkastream] final class DocumentStage[T](
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
  private def nextR(r: Response): Future[Option[Response]] =
    nextResponse(ec, r)

  def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with OutHandler {
      private var last = Option.empty[(Response, Iterator[T], Option[T])]

      @inline private def tailable = cursor.wrappee.tailable

      private var request: () => Future[Option[Response]] = { () =>
        cursor.makeRequest(maxDocs).andThen {
          case Success(r) if (
            !tailable || r.reply.numberReturned > 0
          ) => onFirst()
        }.map(Some(_))
      }

      private def onFirst(): Unit = {
        request = { () =>
          last.fold(Future.successful(Option.empty[Response])) {
            case (lastResponse, _, _) => nextR(lastResponse).andThen {
              case Success(Some(response)) => last.foreach {
                case (lr, _, _) =>
                  if (lr.reply.cursorID != response.reply.cursorID) kill(lr)
              }
            }
          }
        }
      }

      private def killLast(): Unit = last.foreach {
        case (r, _, _) =>
          kill(r)
      }

      @SuppressWarnings(Array("CatchException"))
      private def kill(r: Response): Unit = {
        try {
          cursor.wrappee killCursor r.reply.cursorID
        } catch {
          case reason: Exception => logger.warn(
            s"Fails to kill the cursor (${r.reply.cursorID})", reason
          )
        }

        last = None
      }

      private def onFailure(reason: Throwable): Unit = {
        val previous = last.flatMap(_._3)

        killLast()

        err(previous, reason) match {
          case Cursor.Cont(_) => onPull()
          case Cursor.Done(_) => completeStage()

          case Cursor.Fail(error) =>
            fail(out, error)

          case _ =>
            fail(out, new GenericDriverException("Erroneous cursor"))

        }
      }

      private def nextD(r: Response, bulk: Iterator[T]): Unit = {
        Try(bulk.next()) match {
          case Failure(reason: ReplyDocumentIteratorExhaustedException) =>
            fail(out, reason)

          case Failure(reason @ CursorOps.UnrecoverableException(_)) =>
            fail(out, reason)

          case Failure(reason) => err(last.flatMap(_._3), reason) match {
            case Cont(current @ Some(v)) => {
              last = Some((r, bulk, current))
              push(out, v)
            }

            case Cont(_) => ()

            case Done(Some(v)) => {
              push(out, v)
              completeStage()
            }

            case Done(_) =>
              completeStage()

            case Fail(cause) =>
              fail(out, cause)

            case _ =>
              fail(out, new GenericDriverException("Erroneous cursor"))
          }

          case Success(v) => {
            last = Some((r, bulk, Some(v)))
            push(out, v)
          }
        }
      }

      private val futureCB = getAsyncCallback(asyncCallback).invoke _

      private def asyncCallback: Try[Option[Response]] => Unit = {
        case Failure(reason) => onFailure(reason)

        case Success(Some(r)) => {
          if (r.reply.numberReturned == 0) {
            if (tailable) onPull()
            else completeStage()
          } else {
            last = None

            val bulkIter = cursor.documentIterator(r).
              take(maxDocs - r.reply.startingFrom)

            nextD(r, bulkIter)
          }
        }

        case _ => {
          if (!tailable) {
            completeStage()
          } else {
            last = None
            //Thread.sleep(1000) // TODO
            onPull()
          }
        }
      }

      def onPull(): Unit = last match {
        case Some((r, bulk, _)) if (bulk.hasNext) =>
          nextD(r, bulk)

        case _ if (tailable && !cursor.wrappee.connection.active) => {
          // Complete tailable source if the connection is no longer active
          completeStage()
        }

        case _ =>
          request().onComplete(futureCB)
      }

      override def postStop(): Unit = {
        killLast()
        super.postStop()
      }

      setHandler(out, this)
    }
}
