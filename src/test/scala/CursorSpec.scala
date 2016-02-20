import scala.collection.mutable.ListBuffer
import scala.concurrent.{ Future, Promise }

import reactivemongo.bson.{
  BSONDocument, BSONDocumentReader, BSONDocumentWriter
}
import reactivemongo.api.Cursor

// Reactive streams imports
import org.reactivestreams.{ Publisher, Subscriber, Subscription }
import akka.stream.scaladsl.{ Sink, Source }

import reactivemongo.akkastreams.AkkaStreamsCursor

object CursorSpec extends org.specs2.mutable.Specification {
  "Cursor" title

  implicit val system = akka.actor.ActorSystem("reactivemongo-akkastreams")
  implicit val materializer = akka.stream.ActorMaterializer.create(system)

  // ReactiveMongo extensions
  import reactivemongo.akkastreams.cursorProducer

  import Common._

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

      "with insert" in {
        implicit val writer = PersonWriter

        Future.sequence(fixtures.map(personColl.insert(_).map(_.ok))).
          aka("fixtures") must beEqualTo(List(true, true, true, true, true)).
          await(timeoutMillis)
      }
    }

    // TODO
  }

  "AkkaStream Cursor" should {
    object IdReader extends BSONDocumentReader[Int] {
      def read(doc: BSONDocument): Int = doc.getAs[Int]("id").get
    }

    val expectedList = List(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
    def toSeq(src: Source[Int, akka.NotUsed]): Future[Seq[Int]] =
      src.runWith(Sink.seq[Int])

    def collection(n: String) = {
      val col = db(s"akka_collection_$n")

      Future.sequence((0 until 10) map { id =>
        col.insert(BSONDocument("id" -> id))
      }) map { _ =>
        //println(s"-- all documents inserted in test collection $n")
        col
      }
    }

    @inline def cursor(n: String): AkkaStreamsCursor[Int] = {
      implicit val reader = IdReader
      Cursor.flatten(collection(n).map(_.find(BSONDocument()).
        sort(BSONDocument("id" -> 1)).cursor[Int]()))
    }

    "consume all the documents" in {
      toSeq(cursor("source1").source()).
        aka("sequence") must beEqualTo(expectedList).await(timeoutMillis)
    }

    "consume using a publisher" in {
      val pub: Publisher[_ <: Int] = cursor("source2").publisher(true)

      val (s, f) = promise[Int]
      pub subscribe s

      f must beEqualTo(List("subscribe",
        "0", "1", "2", "3", "4", "5", "6", "7", "8", "9")).
        await(timeoutMillis)
    }
  }

  // ---

  def promise[T]: (Subscriber[T], Future[List[String]]) = {
    val events = ListBuffer.empty[String]
    val p = Promise[List[String]]
    val sub = new Subscriber[T] {
      def onComplete(): Unit = { p.success(events.toList) }
      def onError(e: Throwable): Unit = events += "error"
      def onSubscribe(s: Subscription): Unit = {
        events += "subscribe"
        s request 10L
      }
      def onNext(n: T): Unit = { events += n.toString }
    }
    sub -> p.future
  }  
}
