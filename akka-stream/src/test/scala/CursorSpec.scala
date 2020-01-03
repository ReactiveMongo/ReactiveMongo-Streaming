import java.util.concurrent.TimeoutException
import java.util.concurrent.TimeUnit.MILLISECONDS

import scala.util.{ Failure, Try }
import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._

// Reactive streams imports
import org.reactivestreams.Publisher

import akka.stream.KillSwitches
import akka.stream.scaladsl.{ Keep, Sink, Source }

import akka.stream.testkit.TestSubscriber

import org.specs2.concurrent.ExecutionEnv

import reactivemongo.bson.{
  BSONDocument,
  BSONDocumentReader,
  BSONNumberLike
}

import reactivemongo.core.protocol.Response
import reactivemongo.core.actors.Exceptions.ClosedException

import reactivemongo.api.{ Cursor, DB, QueryOpts }
import reactivemongo.api.collections.bson.BSONCollection

import reactivemongo.akkastream.AkkaStreamCursor

import com.github.ghik.silencer.silent

final class CursorSpec(implicit ee: ExecutionEnv)
  extends org.specs2.mutable.Specification with CursorFixtures {

  "Cursor" title

  sequential

  implicit val system = akka.actor.ActorSystem(
    name = "reactivemongo-akkastream",
    defaultExecutionContext = Some(ee.ec))

  @silent
  implicit lazy val materializer = akka.stream.ActorMaterializer.create(system)

  import Common.primaryHost
  val db = Common.db
  @inline def timeout = Common.timeout

  // Akka-Contrib issue with Akka-Stream > 2.5.4
  //import akka.stream.contrib.TestKit.assertAllStagesStopped
  def assertAllStagesStopped[T](f: => T) = f

  "Response source" should {
    "be fully consumed" >> {
      "using a sequence sink" in assertAllStagesStopped {
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
        }.aka("sequence") must beTypedEqualTo(expected).awaitFor(timeout)
      }

      "using a publisher" in assertAllStagesStopped {
        val pub: Publisher[_ <: Response] =
          cursor("source2").responsePublisher(true)

        val c = TestSubscriber.manualProbe[Response]()

        pub subscribe c

        val sub = c.expectSubscription()
        sub.request(2)

        c.expectNext must beLike[Response] {
          case resp1 =>
            val r = resp1.reply
            r.numberReturned.aka("numberReturned #1") must_=== 3 and {
              (r.cursorID != 0L) aka "hasNext #1" must beTrue
            }
        } and {
          c.expectNext must beLike[Response] {
            case resp2 =>
              val r = resp2.reply
              r.numberReturned.aka("numberReturned #2") must_=== 3 and {
                (r.cursorID != 0L) aka "hasNext #2" must beTrue
              }
          }
        } and {
          expectNoMsg(c, 200.millis) must not(throwA[Throwable])
        } and {
          sub.request(3) must not(throwA[Throwable])
        } and {
          c.expectNext must beLike[Response] {
            case resp3 =>
              val r = resp3.reply
              r.numberReturned.aka("numberReturned #3") must_=== 3 and {
                (r.cursorID != 0L) aka "hasNext #3" must beTrue
              }
          }
        } and {
          c.expectNext must beLike[Response] {
            case resp4 =>
              val r = resp4.reply
              r.numberReturned.aka("numberReturned #4") must_=== 1 and {
                (r.cursorID != 0L) aka "hasNext #4" must beFalse
              }
          }
        } and {
          c.expectComplete() aka "completed" must not(throwA[Throwable])
        }
      }
    }

    "consumed with a max of 5 documents" in assertAllStagesStopped {
      val expected = 2 /* batches */ -> List(
        3 -> true, // 1st batch
        2 -> false
      )

      toSeq(cursor("source3").responseSource(5)).map { rs =>
        rs.size -> (rs.map { r =>
          r.reply.numberReturned -> (r.reply.cursorID != 0) // !Mongo3
        }).toList
      }.aka("sequence") must beTypedEqualTo(expected).awaitFor(timeout)
    }

    "consumed using a publisher with a max of 6 documents" in {
      assertAllStagesStopped {
        val pub: Publisher[_ <: Response] =
          cursor("source4").responsePublisher(true, 6)

        val c = TestSubscriber.manualProbe[Response]()

        pub subscribe c

        val sub = c.expectSubscription()
        sub.request(2)

        c.expectNext must beLike[Response] {
          case resp1 =>
            val r = resp1.reply
            r.numberReturned.aka("numberReturned #1") must_=== 3 and {
              (r.cursorID != 0L) aka "hasNext #1" must beTrue
            }
        } and {
          c.expectNext must beLike[Response] {
            case resp2 =>
              val r = resp2.reply
              r.numberReturned.aka("numberReturned #2") must_=== 3 and {
                (r.cursorID != 0L) aka "hasNext #2" must beFalse
              }
          }
        } and {
          c.expectComplete() aka "completed" must not(throwA[Throwable])
        }
      }
    }

    "handle errors" >> {
      "by failing with the default handler" in assertAllStagesStopped {
        val drv = Common.newDriver()

        def col(n: String) = for {
          c <- drv.connect(List(primaryHost))
          d <- c.database(db.name)
          res <- withFixtures(d.collection(n))
        } yield res

        val sink = Sink.fold(0) { (count, _: Response) =>
          if (count == 0) drv.close(3.second) // trigger error by closing
          count + 1
        }

        Await.result(
          cursor1("source5")(col).responseSource() runWith sink, timeout
        ) aka "result" must throwA[ClosedException](
            "This MongoConnection is closed"
          )
      }

      "by stopping with the Done handler" in assertAllStagesStopped {
        val drv = Common.newDriver()

        def col(n: String) = for {
          c <- drv.connect(List(primaryHost))
          d <- c.database(db.name)
          res <- withFixtures(d.collection(n))
        } yield res

        val sink = Sink.fold(0) { (count, _: Response) =>
          if (count == 1) drv.close(3.second) // trigger error by closing
          count + 1
        }
        @volatile var err = Option.empty[Throwable]

        cursor1("source6")(col).responseSource(
          err = Cursor.DoneOnError { (_, e) => err = Some(e) }
        ).runWith(sink) must beTypedEqualTo(2).awaitFor(timeout) and {
            err must beSome[Throwable].like {
              case reason: ClosedException =>
                reason.getMessage must beMatching(
                  ".*This MongoConnection is closed.*"
                )

              case reason => sys.error(s"Cause: $reason")
            }
          }
      }

      "by trying to continue" in assertAllStagesStopped {
        val drv = Common.newDriver()

        def col(n: String) = for {
          c <- drv.connect(List(primaryHost))
          d <- c.database(db.name)
          res <- withFixtures(d.collection(n))
        } yield res

        val sink = Sink.fold(0) { (count, _: Response) =>
          if (count == 2) drv.close(3.second) // trigger error by closing
          count + 1
        }
        @volatile var err = Option.empty[Throwable]

        Await.result(cursor1("source7")(col).responseSource(
          err = Cursor.ContOnError { (_, e) => err = Some(e) }
        ).runWith(sink), timeout) must beTypedEqualTo(3) and {
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
      "using a sequence sink" in assertAllStagesStopped {
        val expected = expectedSeq.sliding(3, 3).toSeq

        toSeq(cursor("source8").bulkSource()).map(_.map(_.toList)).
          aka("sequence") must beTypedEqualTo(expected).awaitFor(timeout)

      }

      "using a publisher" in assertAllStagesStopped {
        val pub: Publisher[_ <: Iterator[Int]] =
          cursor("source9").bulkPublisher(true)

        val c = TestSubscriber.manualProbe[Iterator[Int]]()

        pub subscribe c

        val sub = c.expectSubscription()
        sub.request(3)

        c.expectNext.toList aka "bulk #1" must_=== List(0, 1, 2) and {
          c.expectNext.toList aka "bulk #2" must_=== List(3, 4, 5)
        } and {
          c.expectNext.toList aka "bulk #3" must_=== List(6, 7, 8)
        } and {
          expectNoMsg(c, 200.millis) must not(throwA[Throwable])
        } and {
          sub.request(2) must not(throwA[Throwable])
        } and {
          c.expectNext.toList aka "bulk #4" must_=== List(9)
        } and {
          c.expectComplete() aka "completed" must not(throwA[Throwable])
        }
      }
    }

    "handle errors" >> {
      "by failing with the default handler" in {
        assertAllStagesStopped {
          val drv = Common.newDriver()

          def col(n: String) = for {
            c <- drv.connect(List(primaryHost))
            d <- c.database(db.name)
            res <- withFixtures(d.collection(n))
          } yield res

          val sink = Sink.fold(0) { (count, _: Iterator[Int]) =>
            if (count == 0) drv.close(3.second) // trigger error by closing
            count + 1
          }

          Await.result(
            cursor1("source10")(col).bulkSource() runWith sink, timeout
          ) aka "result" must throwA[ClosedException](
              "This MongoConnection is closed"
            )
        }
      }

      "by stopping with the Done handler" in {
        assertAllStagesStopped {
          val drv = Common.newDriver()

          def col(n: String) = for {
            c <- drv.connect(List(primaryHost))
            d <- c.database(db.name)
            res <- withFixtures(d.collection(n))
          } yield res

          val sink = Sink.fold(0) { (count, _: Iterator[Int]) =>
            if (count == 1) drv.close(3.second) // trigger error by closing
            count + 1
          }
          @volatile var err = Option.empty[Throwable]

          cursor1("source11")(col).bulkSource(
            err = Cursor.DoneOnError { (_, e) => err = Some(e) }
          ).runWith(sink) must beTypedEqualTo(2).awaitFor(timeout) and {
              err must beSome[Throwable].like {
                case reason: ClosedException =>
                  reason.getMessage must beMatching(
                    ".*This MongoConnection is closed.*"
                  )
              }
            }
        }
      }

      "by trying to continue" in assertAllStagesStopped {
        val drv = Common.newDriver()

        def col(n: String) = for {
          c <- drv.connect(List(primaryHost))
          d <- c.database(db.name)
          res <- withFixtures(d.collection(n))
        } yield res

        val sink = Sink.fold(0) { (count, _: Iterator[Int]) =>
          if (count == 2) drv.close(3.second) // trigger error by closing
          count + 1
        }
        @volatile var err = Option.empty[Throwable]

        Await.result(cursor1("source12")(col).bulkSource(
          err = Cursor.ContOnError { (_, e) => err = Some(e) }
        ).runWith(sink), timeout) must_=== 3 and {
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
      "using a sequence sink" in assertAllStagesStopped {
        toSeq(cursor("source13").documentSource()).
          aka("sequence") must beTypedEqualTo(expectedSeq).awaitFor(timeout)
      }

      "using a publisher" in assertAllStagesStopped {
        val pub: Publisher[Int] =
          cursor("source14").documentPublisher(true)

        val c = TestSubscriber.manualProbe[Int]()

        pub subscribe c

        val sub = c.expectSubscription()
        sub.request(4)

        c.expectNext aka "document #1" must_=== 0 and {
          c.expectNext aka "document #2" must_=== 1
        } and {
          c.expectNext aka "document #3" must_=== 2
        } and {
          c.expectNext aka "document #4" must_=== 3
        } and {
          expectNoMsg(c, 200.millis) must not(throwA[Throwable])
        } and {
          sub.request(5) must not(throwA[Throwable])
        } and {
          c.expectNext aka "document #5" must_=== 4
        } and {
          c.expectNext aka "document #6" must_=== 5
        } and {
          c.expectNext aka "document #7" must_=== 6
        } and {
          c.expectNext aka "document #8" must_=== 7
        } and {
          c.expectNext aka "document #9" must_=== 8
        } and {
          expectNoMsg(c, 500.millis) must not(throwA[Throwable])
        } and {
          sub.request(2) must not(throwA[Throwable])
        } and {
          c.expectNext aka "document #10" must_=== 9
        } and {
          c.expectComplete() aka "completed" must not(throwA[Throwable])
        }
      }

      val nDocs = 16517
      s"insert $nDocs records" in {
        val moreTime = FiniteDuration(
          timeout.toMillis * nDocs / 2, MILLISECONDS
        )

        val coll = db[BSONCollection](s"akka10_${System identityHashCode ee}")

        def insert(rem: Int, bulks: Seq[Future[Unit]]): Future[Unit] = {
          if (rem == 0) {
            Future.sequence(bulks).map(_ => {})
          } else {
            val len = if (rem < 256) rem else 256
            val prepared = nDocs - rem

            def bulk = coll.insert.many(
              for (i <- 0 until len) yield {
                val n = i + prepared
                BSONDocument("i" -> n, "record" -> s"record$n")
              }).map(_ => {})

            insert(rem - len, bulk +: bulks)
          }
        }

        insert(nDocs, Seq.empty) must beTypedEqualTo({}).
          await(1, moreTime) and {
            import reactivemongo.akkastream.cursorProducer
            val cursor: AkkaStreamCursor[BSONDocument] =
              coll.find(BSONDocument.empty).cursor[BSONDocument]()

            def src = cursor.documentSource()
            val consume = Sink.fold[(Long, Long), BSONDocument](0L -> 0L) {
              case ((count, n), doc) =>
                val i = doc.getAs[BSONNumberLike]("i").map(_.toLong).get
                (count + 1L) -> (n + i)
            }

            src.runWith(consume) must beTypedEqualTo(
              nDocs.toLong -> 136397386L
            ).await(1, moreTime)
          }
      }
    }

    "consumed with a max of 6 documents" >> {
      "with limit in the query operation" in {
        assertAllStagesStopped {
          toSeq(cursor("source15a").documentSource(6)).
            aka("sequence") must beTypedEqualTo(expectedSeq take 6).
            awaitFor(timeout)
        }
      }

      "with limit on the stream" in {
        assertAllStagesStopped {
          toSeq(cursor("source15b").documentSource(10).take(6)).
            aka("sequence") must beTypedEqualTo(expectedSeq take 6).
            awaitFor(timeout)
        }
      }
    }

    "consumed using a publisher with a max of 7 documents" in {
      assertAllStagesStopped {
        val pub: Publisher[Int] =
          cursor("source16").documentPublisher(true, 7)

        val c = TestSubscriber.manualProbe[Int]()

        pub subscribe c

        val sub = c.expectSubscription()
        sub.request(2)

        c.expectNext aka "document #1" must_=== 0 and {
          c.expectNext aka "document #2" must_=== 1
        } and {
          expectNoMsg(c, 200.millis) must not(throwA[Throwable])
        } and {
          sub.request(5) must not(throwA[Throwable])
        } and {
          c.expectNext aka "document #3" must_=== 2
        } and {
          c.expectNext aka "document #4" must_=== 3
        } and {
          c.expectNext aka "document #5" must_=== 4
        } and {
          c.expectNext aka "document #6" must_=== 5
        } and {
          c.expectNext aka "document #7" must_=== 6
        } and {
          c.expectComplete() aka "completed" must not(throwA[Throwable])
        }
      }
    }

    "handle errors" >> {
      "by failing with the default handler" in {
        assertAllStagesStopped {
          val drv = Common.newDriver()

          def col(n: String) = for {
            c <- drv.connect(List(primaryHost))
            d <- c.database(db.name)
            res <- withFixtures(d.collection(n))
          } yield res

          val sink = Sink.fold(0) { (count, _: Int) =>
            if (count == 0) drv.close(3.second) // trigger error by closing
            count + 1
          }

          Await.result(
            cursor1("source17")(col).documentSource() runWith sink, timeout
          ) aka "result" must throwA[ClosedException](
              "This MongoConnection is closed"
            )
        }
      }

      "by stopping with the Done handler" in {
        assertAllStagesStopped {
          val drv = Common.newDriver

          def col(n: String) = for {
            c <- drv.connect(List(primaryHost))
            d <- c.database(db.name)
            res <- withFixtures(d.collection(n))
          } yield res

          val sink = Sink.fold(0) { (count, _: Int) =>
            if (count == 1) drv.close(3.second) // trigger error by closing
            count + 1
          }
          @volatile var err = Option.empty[Throwable]

          cursor1("source18")(col).documentSource(
            err = Cursor.DoneOnError { (_, e) => err = Some(e) }
          ).runWith(sink) must beTypedEqualTo(3 /* = bulk size */ ).
            awaitFor(timeout) and {
              err must beSome[Throwable].like {
                case reason: ClosedException =>
                  reason.getMessage must beMatching(
                    ".*This MongoConnection is closed.*"
                  )
              }
            }
        }
      }

      "by trying to continue" in assertAllStagesStopped {
        val drv = Common.newDriver()

        def col(n: String) = for {
          c <- drv.connect(List(primaryHost))
          d <- c.database(db.name)
          res <- withFixtures(d.collection(n))
        } yield res

        val sink = Sink.fold(0) { (count, _: Int) =>
          if (count == 2) drv.close(3.second) // trigger error by closing
          count + 1
        }
        @volatile var err = Option.empty[Throwable]

        Await.result(cursor1("source19")(col).documentSource(
          err = Cursor.ContOnError { (_, e) => err = Some(e) }
        ).runWith(sink), timeout) must_=== 3 and {
          err must beSome[Throwable].like {
            case reason: ClosedException =>
              reason.getMessage must beMatching(
                ".*This MongoConnection is closed.*"
              )
          }
        }
      }
    }

    "work with large source" >> {
      // ReactiveMongo extensions
      import reactivemongo.akkastream.cursorProducer

      implicit def reader: reactivemongo.bson.BSONDocumentReader[Int] = IdReader
      val nDocs = 16517
      val coll = db(s"akka-large-1-${System identityHashCode db}")

      @silent("Use\\ reactivemongo-bson-api")
      def cursor = coll.find(BSONDocument.empty).
        sort(BSONDocument("id" -> 1)).cursor[Int]()

      s"insert $nDocs records" in {
        def insert(rem: Int, bulks: Seq[Future[Unit]]): Future[Unit] = {
          if (rem == 0) {
            Future.sequence(bulks).map(_ => {})
          } else {
            val len = if (rem < 256) rem else 256
            val prepared = nDocs - rem

            def bulk = coll.insert.many(
              for (i <- 0 until len) yield {
                val n = i + prepared
                BSONDocument("id" -> n, "record" -> s"record$n")
              }).map(_ => {})

            insert(rem - len, bulk +: bulks)
          }
        }

        insert(nDocs, Seq.empty).map { _ =>
          //println(s"inserted $nDocs records")
        } aka "fixtures" must beTypedEqualTo({}).awaitFor(timeout)
      }

      "using a fold sink" in assertAllStagesStopped {
        val source = cursor.documentSource()

        source.runWith(Sink.fold[Int, Int](-1) { (prev, i) =>
          val expected = prev + 1
          if (expected == i) expected else -1
        }) aka "fold result" must beTypedEqualTo(16516).awaitFor(timeout * 2)
      }

      "using a publisher" >> {
        "with consumer #1" in assertAllStagesStopped {
          val pub: Publisher[_ <: Int] = cursor.documentPublisher(true)
          val c = TestSubscriber.manualProbe[Int]()

          pub subscribe c

          val sub = c.expectSubscription()
          sub.request((nDocs + 2).toLong)

          (0 until nDocs).foldLeft(-1) { (prev, _) =>
            val expected = prev + 1
            val i = c.expectNext
            if (expected == i) expected else -1
          } aka "fold result" must beTypedEqualTo(16516) and {
            c.expectComplete aka "completed" must not(throwA[Throwable])
          }
        }

        "with consumer #2" in assertAllStagesStopped {
          val pub: Publisher[_ <: Int] = cursor.documentPublisher(true)
          val c = TestSubscriber.manualProbe[Int]()

          pub subscribe c

          val sub = c.expectSubscription()
          val half = nDocs / 2
          sub.request(half.toLong)

          (0 until half).foldLeft(-1) { (prev, _) =>
            val expected = prev + 1
            val i = c.expectNext
            if (expected == i) expected else -1
          } aka "fold result #1" must beTypedEqualTo(8257) and {
            expectNoMsg(c, 200.millis) must not(throwA[Throwable])
          } and {
            sub.request(3) must not(throwA[Throwable])
          } and {
            c.expectNext must_=== 8258
          } and {
            c.expectNext must_=== 8259
          } and {
            c.expectNext must_=== 8260
          } and {
            expectNoMsg(c, 500.millis) must not(throwA[Throwable])
          } and {
            sub.request(Int.MaxValue) must not(throwA[Throwable])
          } and {
            (0 to (half - 3)).foldLeft(8260) { (prev, _) =>
              val expected = prev + 1
              val i = c.expectNext
              if (expected == i) expected else -1
            } aka "fold result #2" must beTypedEqualTo(16516)
          } and {
            c.expectComplete aka "completed" must not(throwA[Throwable])
          }
        }
      }
    }
  }

  "Capped collection" should {
    import scala.concurrent.Promise

    def capped(n: String, database: DB, cb: Promise[Unit])(implicit ee: ExecutionEnv) = {
      val col = database(s"akka_${n}_${System identityHashCode ee}")

      // Concurrently populated the capped collection
      def populate: Future[Unit] =
        (0 until 10).foldLeft(Future(Thread.sleep(1000))) { (future, id) =>
          for {
            _ <- future
            _ <- col.insert.one(BSONDocument("id" -> id))
          } yield {
            try {
              Thread.sleep(200)
            } catch {
              case _: InterruptedException => println("no pause")
            }
          }
        }.map { _ =>
          cb.success(println(s"All fixtures inserted in test collection '$n'"))
          ()
        }

      Await.result((for {
        _ <- col.createCapped(4096, Some(10))
      } yield col), timeout) -> { () => populate }
      // (BSONCollection, () => Future[Unit])
    }

    @silent(".*QueryOpts.*")
    def tailable(cb: Promise[Unit], n: String, database: DB = db)(implicit ee: ExecutionEnv) = {
      // ReactiveMongo extensions
      import reactivemongo.akkastream.cursorProducer
      implicit val reader = IdReader

      val (cursor, populate) = capped(n, database, cb)

      cursor.find(BSONDocument.empty).
        options(QueryOpts().tailable).cursor[Int]() -> populate
      // (Cursor[Int], () => Future[Unit]
    }

    def recoverTimeout[A, B](f: => Future[A])(on: => B, to: FiniteDuration = timeout): Try[B] = {
      lazy val v = on
      Try(Await.result(f, to)).
        flatMap(_ => Failure(new Exception("Timeout expected"))).recover {
          case _: TimeoutException => println("Timeout"); v
        }
    }

    // ---

    "be consumed as response source using a sink" in assertAllStagesStopped {
      val ranges = scala.collection.mutable.TreeSet.empty[(Int, Int)]
      val done = Promise[Unit]()
      val (cursor, populate) = tailable(done, "source20")
      val src = cursor.responseSource()

      def consume = {
        val sink = Sink.foreach[Response] { resp =>
          if (resp.reply.numberReturned > 0) {
            ranges += (resp.reply.startingFrom -> resp.reply.numberReturned)
          }
          ()
        }

        src.viaMat(KillSwitches.single)(Keep.right).
          toMat(sink)(Keep.both).run()
      }

      populate()

      done.future must beTypedEqualTo({}).awaitFor(timeout) and {
        val (swtch, c) = consume

        {
          val res = recoverTimeout(c)(ranges.toList)
          swtch.shutdown()
          res
        } must beSuccessfulTry[List[(Int, Int)]].which {
          _.foldLeft((true, 0, 0)) {
            case ((ordered, last, count), (start, c)) =>
              if (start < last) (false, start, count + c)
              else (ordered, start, count + c)

          } must beLike[(Boolean, Int, Int)] {
            case (ordered, _, count) =>
              ordered must beTrue and (count must_=== 10)
          }
        }
      }
    }

    "be consumed as bulk source using a sink" in assertAllStagesStopped {
      val consumed = scala.collection.mutable.TreeSet.empty[Int]
      val done = Promise[Unit]()
      val (cursor, populate) = tailable(done, "source21")

      val src = cursor.bulkSource()
      def consume = {
        def sink = Sink.foreach[Iterator[Int]] { i => consumed ++= i; () }

        src.viaMat(KillSwitches.single)(Keep.right).
          toMat(sink)(Keep.both).run()
      }

      populate()

      done.future must beTypedEqualTo({}).awaitFor(timeout) and {
        val (swtch, c) = consume

        {
          val res = recoverTimeout(c)(consumed.toList)
          swtch.shutdown()
          res
        } must beSuccessfulTry(
          List(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
        )
      }
    }

    "be consumed as document source using a sink" in assertAllStagesStopped {
      val consumed = scala.collection.mutable.TreeSet.empty[Int]
      val done = Promise[Unit]()
      val (cursor, populate) = tailable(done, "source22")
      val src = cursor.documentSource()

      def consume = {
        val sink = Sink.foreach[Int] { i => consumed += i; () }

        src.viaMat(KillSwitches.single)(Keep.right).
          toMat(sink)(Keep.both).run()
      }

      populate()

      done.future must beTypedEqualTo({}).awaitFor(timeout) and {
        val (swtch, c) = consume

        {
          val res = recoverTimeout(c)(consumed.toList)
          swtch.shutdown()
          res
        } must beSuccessfulTry(
          List(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
        )
      }
    }
  }

  "Aggregation" should {
    implicit def reader = IdReader

    "should match index greater than or equal" in {
      import reactivemongo.akkastream.cursorProducer

      assertAllStagesStopped {
        val flatten = Cursor.flatten[Int, AkkaStreamCursor] _

        toSeq(flatten(collection("source23").map { col =>
          import col.BatchCommands.AggregationFramework.{
            Ascending,
            Match,
            Sort
          }

          @silent(".*with\\ comment.*")
          def cursor = col.aggregatorContext[Int](
            Match(BSONDocument("id" -> BSONDocument("$gte" -> 3))),
            List(Sort(Ascending("id")))
          ).prepared[AkkaStreamCursor.WithOps].cursor

          cursor
        }).documentSource()) must beTypedEqualTo(
          expectedSeq.filter(_ >= 3)
        ).awaitFor(timeout)
      }
    }
  }

  // ---

  @com.github.ghik.silencer.silent @inline def expectNoMsg[T](c: akka.stream.testkit.TestSubscriber.ManualProbe[T], timeout: FiniteDuration) = c.expectNoMsg(timeout)
}

sealed trait CursorFixtures { specs: CursorSpec =>
  // ReactiveMongo extensions
  import reactivemongo.akkastream.cursorProducer

  object IdReader extends BSONDocumentReader[Int] {
    def read(doc: BSONDocument): Int = doc.getAs[Int]("id").get
  }

  val expectedSeq = Seq(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)

  def toSeq[T](src: Source[T, _]): Future[Seq[T]] =
    src.runWith(Sink.seq[T])

  @inline def cursor(n: String)(implicit ee: ExecutionEnv): AkkaStreamCursor[Int] = cursor1(n)(collection(_))

  @silent(".*QueryOpts.*")
  @inline def cursor1(n: String)(col: String => Future[BSONCollection])(implicit ee: ExecutionEnv): AkkaStreamCursor[Int] = {
    implicit val reader = IdReader
    Cursor.flatten(col(n).map(_.find(BSONDocument()).
      options(QueryOpts(batchSizeN = 3)).
      sort(BSONDocument("id" -> 1)).cursor[Int]()))
  }

  def collection(n: String)(implicit ee: ExecutionEnv): Future[BSONCollection] =
    withFixtures(db(s"akka${n}_${System identityHashCode ee}"))

  def withFixtures(col: BSONCollection)(implicit ee: ExecutionEnv): Future[BSONCollection] =
    Future
      .sequence((0 until 10).map(n => col.insert.one(BSONDocument("id" -> n))))
      .map(_ => col)
}
