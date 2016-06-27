import scala.collection.mutable.ListBuffer

import java.util.concurrent.TimeoutException

import scala.concurrent.{ Await, Future, Promise }
import scala.concurrent.duration._
import java.util.concurrent.TimeUnit.MILLISECONDS

// Reactive streams imports
import org.reactivestreams.{ Publisher, Subscriber, Subscription }

import akka.stream.scaladsl.{ Sink, Source }

import org.specs2.concurrent.{ ExecutionEnv => EE }

import reactivemongo.bson.{
  BSONDocument,
  BSONDocumentReader,
  BSONNumberLike
}

import reactivemongo.core.protocol.Response
import reactivemongo.core.actors.Exceptions.ClosedException

import reactivemongo.api.{ Cursor, QueryOpts }
import reactivemongo.api.collections.bson.BSONCollection

import reactivemongo.akkastream.AkkaStreamCursor

class CursorSpec extends org.specs2.mutable.Specification with CursorFixtures {
  "Cursor" title

  implicit val system = akka.actor.ActorSystem("reactivemongo-akkastream")
  implicit val materializer = akka.stream.ActorMaterializer.create(system)

  import Common.primaryHost
  val db = Common.db
  @inline def timeout = Common.timeout

  "Response source" should {
    "be fully consumed" >> {
      "using a sequence sink" in { implicit ee: EE =>
        val expected = 4 /* batches */ -> List(
          3 -> true, // 1st batch
          3 -> true, // 2nd batch
          3 -> true, // 3rd batch
          1 -> false
        ) //4th batch

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
          "subscribe", "3@true", "3@true", "3@true", "1@false"
        )).
          await(0, timeout)
      }
    }

    "consumed with a max of 5 documents" in { implicit ee: EE =>
      val expected = 2 /* batches */ -> List(
        3 -> true, // 1st batch
        3 -> true
      ) // 2nd batch - true has the max stop before end
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
        val con = drv.connection(List(primaryHost))
        def col(n: String) = con.database(db.name).flatMap { d =>
          withFixtures(d.collection(n))
        }

        val sink = Sink.fold(0) { (count, r: Response) =>
          if (count == 0) drv.close(3.second) // trigger error by closing
          count + 1
        }

        Await.result(
          cursor1("source5")(col).responseSource() runWith sink, timeout
        ).
          aka("result") must throwA[ClosedException](
            "This MongoConnection is closed"
          )

      }

      "by stopping with the Done handler" in { implicit ee: EE =>
        val drv = reactivemongo.api.MongoDriver()
        val con = drv.connection(List(primaryHost))
        def col(n: String) = con.database(db.name).flatMap { d =>
          withFixtures(d.collection(n))
        }

        val sink = Sink.fold(0) { (count, r: Response) =>
          if (count == 1) drv.close(3.second) // trigger error by closing
          count + 1
        }
        @volatile var err = Option.empty[Throwable]

        cursor1("source6")(col).responseSource(
          err = Cursor.DoneOnError { (_, e) => err = Some(e) }
        ).runWith(sink) must beEqualTo(2).await(0, timeout) and {
            err must beSome[Throwable].like {
              case reason: ClosedException =>
                reason.getMessage must beMatching(
                  ".*This MongoConnection is closed.*"
                )
            }
          }
      }

      "by trying to continue" in { implicit ee: EE =>
        val drv = reactivemongo.api.MongoDriver()
        val con = drv.connection(List(primaryHost))
        def col(n: String) = con.database(db.name).flatMap { d =>
          withFixtures(d.collection(n))
        }

        val sink = Sink.fold(0) { (count, r: Response) =>
          if (count == 2) drv.close(3.second) // trigger error by closing
          count + 1
        }
        @volatile var err = Option.empty[Throwable]

        Await.result(cursor1("source7")(col).responseSource(
          err = Cursor.ContOnError { (_, e) => err = Some(e) }
        ).runWith(sink), timeout) must throwA[TimeoutException] and {
          err must beSome[Throwable].like {
            case reason: ClosedException =>
              reason.getMessage must beMatching(
                ".*This MongoConnection is closed.*"
              )
          }
        }
      }
    }
  }

  "Bulk source" should {
    "be fully consumed" >> {
      "using a sequence sink" in { implicit ee: EE =>
        val expected = expectedList.sliding(3, 3).toList

        toSeq(cursor("source5").bulkSource()).map(_.map(_.toList)).
          aka("sequence") must beEqualTo(expected).await(0, timeout)

      }

      "using a publisher" in { implicit ee: EE =>
        val pub: Publisher[_ <: Iterator[Int]] =
          cursor("source6").bulkPublisher(true)

        val (s, f) = promise[Iterator[Int]] { _.mkString("(", ",", ")") }

        pub subscribe s

        f must beEqualTo(List(
          "subscribe", "(0,1,2)", "(3,4,5)", "(6,7,8)", "(9)"
        )).
          await(0, timeout)
      } tag "wip"
    }

    "handle errors" >> {
      "by failing with the default handler" in { implicit ee: EE =>
        val drv = reactivemongo.api.MongoDriver()
        val con = drv.connection(List(primaryHost))
        def col(n: String) = con.database(db.name).flatMap { d =>
          withFixtures(d.collection(n))
        }

        val sink = Sink.fold(0) { (count, bulk: Iterator[Int]) =>
          if (count == 0) drv.close(3.second) // trigger error by closing
          count + 1
        }

        Await.result(
          cursor1("source10")(col).bulkSource() runWith sink, timeout
        ) aka "result" must throwA[ClosedException](
            "This MongoConnection is closed"
          )
      }

      "by stopping with the Done handler" in { implicit ee: EE =>
        val drv = reactivemongo.api.MongoDriver()
        val con = drv.connection(List(primaryHost))
        def col(n: String) = con.database(db.name).flatMap { d =>
          withFixtures(d.collection(n))
        }

        val sink = Sink.fold(0) { (count, bulk: Iterator[Int]) =>
          if (count == 1) drv.close(3.second) // trigger error by closing
          count + 1
        }
        @volatile var err = Option.empty[Throwable]

        cursor1("source11")(col).bulkSource(
          err = Cursor.DoneOnError { (_, e) => err = Some(e) }
        ).runWith(sink) must beEqualTo(2).await(0, timeout) and {
            err must beSome[Throwable].like {
              case reason: ClosedException =>
                reason.getMessage must beMatching(
                  ".*This MongoConnection is closed.*"
                )
            }
          }
      }

      "by trying to continue" in { implicit ee: EE =>
        val drv = reactivemongo.api.MongoDriver()
        val con = drv.connection(List(primaryHost))
        def col(n: String) = con.database(db.name).flatMap { d =>
          withFixtures(d.collection(n))
        }

        val sink = Sink.fold(0) { (count, bulk: Iterator[Int]) =>
          if (count == 2) drv.close(3.second) // trigger error by closing
          count + 1
        }
        @volatile var err = Option.empty[Throwable]

        Await.result(cursor1("source12")(col).bulkSource(
          err = Cursor.ContOnError { (_, e) => err = Some(e) }
        ).runWith(sink), timeout) must throwA[TimeoutException] and {
          err must beSome[Throwable].like {
            case reason: ClosedException =>
              reason.getMessage must beMatching(
                ".*This MongoConnection is closed.*"
              )
          }
        }
      }
    }
  }

  "Document source" should {
    "be fully consumed" >> {
      "using a sequence sink" in { implicit ee: EE =>
        toSeq(cursor("source7").documentSource()).
          aka("sequence") must beEqualTo(expectedList).await(0, timeout)
      }

      "using a publisher" in { implicit ee: EE =>
        val pub: Publisher[Int] =
          cursor("source8").documentPublisher(true)

        val (s, f) = promise[Int](_.toString)

        pub subscribe s

        f must beEqualTo(List(
          "subscribe", "0", "1", "2", "3", "4", "5", "6", "7", "8", "9"
        )).
          await(0, timeout)
      } tag "wip"

      val nDocs = 16517
      s"insert $nDocs records" in { implicit ee: EE =>
        val moreTime = FiniteDuration(
          timeout.toMillis * nDocs / 2, MILLISECONDS
        )

        val coll = db[BSONCollection](s"akka10_${System identityHashCode ee}")
        val futs: Seq[Future[Unit]] = for (i <- 0 until nDocs) yield {
          coll.insert(BSONDocument(
            "i" -> i, "record" -> s"record$i"
          )).map(_ => {})
        }

        Future.sequence(futs).map { _ =>
          //println(s"inserted $nDocs records")
        } aka "fixtures" must beEqualTo({}).await(1, moreTime) and {
          import reactivemongo.akkastream.cursorProducer
          val cursor: AkkaStreamCursor[BSONDocument] =
            coll.find(BSONDocument.empty).cursor[BSONDocument]()

          def src = cursor.documentSource()
          val consume = Sink.fold[(Long, Long), BSONDocument](0L -> 0L) {
            case ((count, n), doc) =>
              val i = doc.getAs[BSONNumberLike]("i").map(_.toLong).get
              (count + 1L) -> (n + i)
          }

          src.runWith(consume) must beEqualTo(
            nDocs.toLong -> 136397386L
          ).await(1, moreTime)
        }
      }
    }

    "consumed with a max of 6 documents" in { implicit ee: EE =>
      toSeq(cursor("source9").documentSource(6)).
        aka("sequence") must beEqualTo(expectedList take 6).await(0, timeout)

    }

    "consumed using a publisher with a max of 7 documents" in {
      implicit ee: EE =>
        val pub: Publisher[Int] = cursor("source10").documentPublisher(true, 7)

        val (s, f) = promise[Int](_.toString)

        pub subscribe s

        f must beEqualTo(List(
          "subscribe",
          "0", "1", "2", "3", "4", "5", "6"
        )).await(0, timeout)
    }

    "handle errors" >> {
      "by failing with the default handler" in { implicit ee: EE =>
        val drv = reactivemongo.api.MongoDriver()
        val con = drv.connection(List(primaryHost))
        def col(n: String) = con.database(db.name).flatMap { d =>
          withFixtures(d.collection(n))
        }

        val sink = Sink.fold(0) { (count, _: Int) =>
          if (count == 0) drv.close(3.second) // trigger error by closing
          count + 1
        }

        Await.result(
          cursor1("source17")(col).documentSource() runWith sink, timeout
        ).
          aka("result") must throwA[ClosedException](
            "This MongoConnection is closed"
          )

      }

      "by stopping with the Done handler" in { implicit ee: EE =>
        val drv = reactivemongo.api.MongoDriver()
        val con = drv.connection(List(primaryHost))
        def col(n: String) = con.database(db.name).flatMap { d =>
          withFixtures(d.collection(n))
        }

        val sink = Sink.fold(0) { (count, _: Int) =>
          if (count == 1) drv.close(3.second) // trigger error by closing
          count + 1
        }
        @volatile var err = Option.empty[Throwable]

        cursor1("source18")(col).documentSource(
          err = Cursor.DoneOnError { (_, e) => err = Some(e) }
        ).runWith(sink) must beEqualTo(3 /* = bulk size */ ).
          await(0, timeout) and {
            err must beSome[Throwable].like {
              case reason: ClosedException =>
                reason.getMessage must beMatching(
                  ".*This MongoConnection is closed.*"
                )
            }
          }
      }

      "by trying to continue" in { implicit ee: EE =>
        val drv = reactivemongo.api.MongoDriver()
        val con = drv.connection(List(primaryHost))
        def col(n: String) = con.database(db.name).flatMap { d =>
          withFixtures(d.collection(n))
        }

        val sink = Sink.fold(0) { (count, _: Int) =>
          if (count == 2) drv.close(3.second) // trigger error by closing
          count + 1
        }
        @volatile var err = Option.empty[Throwable]

        Await.result(cursor1("source19")(col).documentSource(
          err = Cursor.ContOnError { (_, e) => err = Some(e) }
        ).runWith(sink), timeout) must throwA[TimeoutException] and {
          err must beSome[Throwable].like {
            case reason: ClosedException =>
              reason.getMessage must beMatching(
                ".*This MongoConnection is closed.*"
              )
          }
        }
      }
    }
  }

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

sealed trait CursorFixtures { specs: CursorSpec =>
  // ReactiveMongo extensions
  import reactivemongo.akkastream.cursorProducer

  object IdReader extends BSONDocumentReader[Int] {
    def read(doc: BSONDocument): Int = doc.getAs[Int]("id").get
  }

  val expectedList = List(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
  def toSeq[T](src: Source[T, akka.NotUsed]): Future[Seq[T]] =
    src.runWith(Sink.seq[T])

  def withFixtures(col: BSONCollection)(implicit ee: EE): Future[BSONCollection] = Future.sequence((0 until 10) map { id =>
    col.insert(BSONDocument("id" -> id))
  }) map { _ =>
    //println(s"-- all documents inserted in test collection $n")
    col
  }

  def collection(n: String)(implicit ee: EE): Future[BSONCollection] =
    withFixtures(db(s"akka${n}_${System identityHashCode ee}"))

  @inline def cursor1(n: String)(col: String => Future[BSONCollection])(implicit ee: EE): AkkaStreamCursor[Int] = {
    implicit val reader = IdReader
    Cursor.flatten(col(n).map(_.find(BSONDocument()).
      options(QueryOpts(batchSizeN = 3)).
      sort(BSONDocument("id" -> 1)).cursor[Int]()))
  }

  @inline def cursor(n: String)(implicit ee: EE): AkkaStreamCursor[Int] =
    cursor1(n)(collection(_))
}
