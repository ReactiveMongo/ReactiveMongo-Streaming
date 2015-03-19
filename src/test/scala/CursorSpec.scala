import scala.collection.mutable.ListBuffer
import scala.concurrent.{ Await, Future, Promise }
import reactivemongo.api.Cursor
import reactivemongo.bson.BSONDocument
import org.reactivestreams.{ Publisher, Subscriber, Subscription }
import akka.stream.scaladsl.{ Sink, Source }

object CursorSpec extends org.specs2.mutable.Specification {
  "Akka Streams cursor" title

  import Common._

  implicit val system = akka.actor.ActorSystem("reactivemongo-akkastreams")
  implicit val materializer = 
    akka.stream.ActorFlowMaterializer.create(system)

  "Cursor" should {
    "read from collection" >> {
      def collection(n: String) = {
        val col = db(s"somecollection_$n")

        Future.sequence((0 until 10) map { id =>
          col.insert(BSONDocument("id" -> id))
        }) map { _ =>
          println(s"-- all documents inserted in test collection $n")
          col
        }
      }

      @inline def cursor(n: String) = {
        implicit val reader = IdReader
        import reactivemongo.akkastreams.cursorProducer

        Cursor.flatten(collection(n).map(_.find(BSONDocument()).
          sort(BSONDocument("id" -> 1)).cursor[Int]))
      }

      "successfully as Akka source" in {
        val src: Future[Source[Int, Unit]] = cursor("source1a").source()
        val res: Future[Future[List[Int]]] = src.map(_.runWith(
          Sink.fold[List[Int], Int](List.empty[Int])((ls, i) => i :: ls)))

        res.flatMap(identity) must beEqualTo(
          List(9, 8, 7, 6, 5, 4, 3, 2, 1, 0)).await(timeoutMillis)
      }

      "successfully as publisher" in {
        val pub: Future[Publisher[Int]] =
          cursor("source1b").publisher("publisher1")

        pub.flatMap { p =>
          val (s, f) = promise[Int]
          p subscribe s
          f
        } must beEqualTo(List("subscribe",
          "0", "1", "2", "3", "4", "5", "6", "7", "8", "9")).
          await(timeoutMillis)
      }
    }

    "read from capped collection" >> {
      def collection(n: String) = {
        val col = db(s"somecollection_captail_$n")
        col.createCapped(4096, Some(10))

        Future {
          (0 until 10).foreach { id =>
            col.insert(BSONDocument("id" -> id))
            Thread.sleep(500)
          }
          println(s"-- all documents inserted in test collection $n")
        }

        col
      }

      @inline def tailable(n: String) = {
        implicit val reader = IdReader
        import reactivemongo.akkastreams.cursorProducer
        
        collection(n).find(BSONDocument()).options(
          reactivemongo.api.QueryOpts().tailable).cursor[Int]
      }

      "using tailable source" in {
        val src: Future[Source[Int, Unit]] = tailable("source2").source(5)
        val res: Future[Future[String]] = src.map(_.runWith(
          Sink.fold[String, Int]("")((str, i) => str + i.toString)))

        res.flatMap(identity) must beEqualTo("01234").await(1000)
      }

      "with timeout using source w/o maxDocs" in {
        Await.result(tailable("source3").source(/* no max */).map(
          _.runWith(Sink.ignore)), timeout).
          aka("stream processing") must throwA[Exception]
      }

      "using tailable publisher" in {
        val pub: Future[Publisher[Int]] =
          tailable("source4").publisher("publisher2", 5)

        pub.flatMap { p =>
          val (s, f) = promise[Int]
          p subscribe s
          f
        } must beEqualTo(List("subscribe", "0", "1", "2", "3", "4")).
          await(timeoutMillis)
      }
    }
  }

  object IdReader extends reactivemongo.bson.BSONDocumentReader[Int] {
    def read(doc: BSONDocument): Int = doc.getAs[Int]("id").get
  }

  def promise[T]: (Subscriber[T], Future[List[String]]) = {
    val events = ListBuffer.empty[String]
    val p = Promise[List[String]]
    val sub = new Subscriber[T] {
      def onComplete(): Unit = p.success(events.toList)
      def onError(e: Throwable): Unit = events += "error"
      def onSubscribe(s: Subscription): Unit = {
        events += "subscribe"
        s request 10L
      }
      def onNext(n: T): Unit = events += n.toString
    }
    sub -> p.future
  }
}
