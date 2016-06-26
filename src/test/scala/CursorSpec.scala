import scala.collection.mutable.ListBuffer

import java.util.concurrent.TimeoutException

import scala.concurrent.{ Await, Future, Promise }
import scala.concurrent.duration._

// Reactive streams imports
import org.reactivestreams.{ Publisher, Subscriber, Subscription }

import akka.stream.scaladsl.{ Sink, Source }

import org.specs2.concurrent.{ ExecutionEnv => EE }

import reactivemongo.bson.{
  BSONDocument, BSONDocumentReader, BSONDocumentWriter
}

import reactivemongo.core.protocol.Response
import reactivemongo.core.actors.Exceptions.ClosedException

import reactivemongo.api.{ Cursor, QueryOpts }
import reactivemongo.api.collections.bson.BSONCollection

import reactivemongo.akkastream.AkkaStreamCursor

class CursorSpec extends org.specs2.mutable.Specification {
  "Cursor" title

  implicit val system = akka.actor.ActorSystem("reactivemongo-akkastream")
  implicit val materializer = akka.stream.ActorMaterializer.create(system)

  // ReactiveMongo extensions
  import reactivemongo.akkastream.cursorProducer

  import Common._

  "Response source" should {
    object IdReader extends BSONDocumentReader[Int] {
      def read(doc: BSONDocument): Int = doc.getAs[Int]("id").get
    }

    val expectedList = List(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
    def toSeq[T](src: Source[T, akka.NotUsed]): Future[Seq[T]] =
      src.runWith(Sink.seq[T])

    def withFixtures(col: BSONCollection)(implicit ee: EE): Future[BSONCollection] = {
      Future.sequence((0 until 10) map { id =>
        col.insert(BSONDocument("id" -> id))
      }) map { _ =>
        //println(s"-- all documents inserted in test collection $n")
        col
      }
    }

    def collection(n: String)(implicit ee: EE): Future[BSONCollection] =
      withFixtures(db(s"akka_collection_$n"))

    @inline def cursor1(n: String)(col: String => Future[BSONCollection])(implicit ee: EE): AkkaStreamCursor[Int] = {
      implicit val reader = IdReader
      Cursor.flatten(col(n).map(_.find(BSONDocument()).
        options(QueryOpts(batchSizeN = 3)).
        sort(BSONDocument("id" -> 1)).cursor[Int]()))
    }

    @inline def cursor(n: String)(implicit ee: EE): AkkaStreamCursor[Int] = cursor1(n)(collection(_))

    "be fully consumed" >> {
      "using a sequence sink" in { implicit ee: EE =>
        val expected = 4/* batches */ -> List(
          3 -> true, // 1st batch
          3 -> true, // 2nd batch
          3 -> true, // 3rd batch
          1 -> false) //4th batch

        toSeq(cursor("source1").responseSource()).map { rs =>
          rs.size -> (rs.map { r =>
            r.reply.numberReturned -> (r.reply.cursorID != 0)
          }).toList
        }.aka("sequence") must beEqualTo(expected).await(0, timeout)
      }

      "using a publisher" in { implicit ee: EE =>
        val pub: Publisher[_ <: Response] =
          cursor("source2").responsePublisher(true)

        val (s, f) = promise[Response] { r =>
          s"${r.reply.numberReturned}@${r.reply.cursorID != 0}"
        }

        pub subscribe s

        f must beEqualTo(List(
          "subscribe", "3@true", "3@true", "3@true", "1@false")).
          await(0, timeout)
      }
    }

    "consumed with a max of 5 documents" in { implicit ee: EE =>
        val expected = 2/* batches */ -> List(
          3 -> true, // 1st batch
          3 -> true) // 2nd batch - true has the max stop before end
      // got 6 docs, as the batch size is 3

        toSeq(cursor("source3").responseSource(5)).map { rs =>
          rs.size -> (rs.map { r =>
            r.reply.numberReturned -> (r.reply.cursorID != 0)
          }).toList
        }.aka("sequence") must beEqualTo(expected).await(0, timeout)

    }

    "consumed using a publisher with a max of 6 documents" in {
      implicit ee: EE =>
      val pub: Publisher[_ <: Response] =
        cursor("source4").responsePublisher(true, 6)

      val (s, f) = promise[Response] { r =>
        s"${r.reply.numberReturned}@${r.reply.cursorID != 0}"
      }

      pub subscribe s

      f must beEqualTo(List("subscribe", "3@true", "3@true")).await(0, timeout)
    }

    "handle errors" >> {
      "by failing with the default handler" in { implicit ee: EE =>
        val drv = reactivemongo.api.MongoDriver()
        val con = drv.connection(List("localhost:27017"))
        def col(n: String) = con.database(db.name).flatMap { d =>
          withFixtures(d.collection(n))
        }

        val sink = Sink.fold(0) { (count, r: Response) =>
          if (count == 0) drv.close(1.second) // trigger error by closing
          count + 1
        }

        Await.result(
          cursor1("source5")(col).responseSource() runWith sink, timeout).
          aka("result") must throwA[ClosedException](
            "This MongoConnection is closed")

      }

      "by stopping with the Done handler" in { implicit ee: EE =>
        val drv = reactivemongo.api.MongoDriver()
        val con = drv.connection(List("localhost:27017"))
        def col(n: String) = con.database(db.name).flatMap { d =>
          withFixtures(d.collection(n))
        }

        val sink = Sink.fold(0) { (count, r: Response) =>
          if (count == 1) drv.close(1.second) // trigger error by closing
          count + 1
        }
        @volatile var err = Option.empty[Throwable]

        cursor1("source6")(col).responseSource(
          err = Cursor.DoneOnError { (_, e) => err = Some(e) }
        ).runWith(sink) must beEqualTo(2).await(0, timeout) and {
          err must beSome[Throwable].like {
            case reason: ClosedException =>
              reason.getMessage must beMatching(
                ".*This MongoConnection is closed.*")
          }
        }
      }

      "by trying to continue" in { implicit ee: EE =>
        val drv = reactivemongo.api.MongoDriver()
        val con = drv.connection(List("localhost:27017"))
        def col(n: String) = con.database(db.name).flatMap { d =>
          withFixtures(d.collection(n))
        }

        val sink = Sink.fold(0) { (count, r: Response) =>
          if (count == 2) drv.close(1.second) // trigger error by closing
          count + 1
        }
        @volatile var err = Option.empty[Throwable]

        Await.result(cursor1("source7")(col).responseSource(
          err = Cursor.ContOnError { (_, e) => err = Some(e) }
        ).runWith(sink), timeout) must throwA[TimeoutException] and {
          err must beSome[Throwable].like {
            case reason: ClosedException =>
              reason.getMessage must beMatching(
                ".*This MongoConnection is closed.*")
          }
        }
      }
    }
  }

  /*
  "Person collection" should {
    case class Person(name: String, age: Int)

    object PersonWriter extends BSONDocumentWriter[Person] {
      def write(p: Person): BSONDocument =
        BSONDocument("age" -> p.age, "name" -> p.name)
    }

    lazy val personColl = db("akkastream_person")
    lazy val coll2 = db(s"akkastream_${System identityHashCode personColl}")

    // ---

    "be provided the fixtures" >> {
      val fixtures = List(
        Person("Jack", 25),
        Person("James", 16),
        Person("John", 34),
        Person("Jane", 24),
        Person("Joline", 34))

      "with insert" in { implicit ee: EE =>
        implicit val writer = PersonWriter

        Future.sequence(fixtures.map(personColl.insert(_).map(_.ok))).
          aka("fixtures") must beEqualTo(List(true, true, true, true, true)).
          await(0, timeout)
      }
    }
  }
   */

  // ---

  def promise[T](f: T => String): (Subscriber[T], Future[List[String]]) = {
    val events = ListBuffer.empty[String]
    val p = Promise[List[String]]
    val sub = new Subscriber[T] {
      def onComplete(): Unit = { p.success(events.toList) }
      def onError(e: Throwable): Unit = events += "error"
      def onSubscribe(s: Subscription): Unit = {
        events += "subscribe"
        s request 10L
      }
      def onNext(n: T): Unit = { events += f(n) }
    }
    sub -> p.future
  }  
}
