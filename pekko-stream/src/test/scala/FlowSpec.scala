import scala.concurrent.Future

import reactivemongo.api.bson.{
  BSON,
  BSONDocument,
  BSONDocumentWriter,
  BSONInteger
}
import reactivemongo.api.bson.collection.{
  BSONCollection,
  BSONSerializationPack
}

import reactivemongo.pekkostream.Flows

import org.specs2.concurrent.ExecutionEnv

import com.github.ghik.silencer.silent
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{ ActorAttributes, Materializer, Supervision }
import org.apache.pekko.stream.scaladsl.{ Flow, Sink, Source }

final class FlowSpec(
    implicit
    ee: ExecutionEnv)
    extends org.specs2.mutable.Specification {

  "Flows".title

  implicit val system: ActorSystem = ActorSystem(
    name = "reactivemongo-pekkostream-flow",
    defaultExecutionContext = Some(ee.ec)
  )

  @silent
  implicit lazy val materializer: Materializer =
    org.apache.pekko.stream.ActorMaterializer.create(system)

  import Common.{ db, timeout }

  "Bulk insert flow" should {
    type BulkInsertFlow = Flow[Iterable[Int], Int, NotUsed]

    def bulkInsertSpec(
        label: String,
        f: Function2[FlowBuilder, BSONDocumentWriter[Int], BulkInsertFlow]
      ) =
      label >> {
        def spec(
            coll: BSONCollection,
            decider: Supervision.Decider
          )(implicit
            w: BSONDocumentWriter[Int]
          ): Future[Int] = {

          val builder = Flows(coll)
          val source = Source.fromIterator[Seq[Int]](() =>
            (0 until 5).map(_ +: Seq.empty[Int]).iterator
          )

          val flow = f(builder, w).withAttributes(
            ActorAttributes.supervisionStrategy(decider)
          )

          source.via(flow).runWith(Sink.fold(0) { (c, r) => c + r.n })
        }

        // ---

        implicit val intWriter: BSONDocumentWriter[Int] =
          BSONDocumentWriter[Int] { i =>
            if (i % 2 == 0) BSONDocument("number" -> BSONInteger(i))
            else throw new Exception("Foo")
          }

        "with 0 successful batch & failure using stop-on-error" in {
          val coll =
            db[BSONCollection](s"bulkinsert1_${System identityHashCode label}")

          spec(coll, Supervision.stoppingDecider)
            .aka("inserted") must throwA[Exception]("Foo")
            .awaitFor(timeout) and {
            coll.count() must beTypedEqualTo(1L).awaitFor(timeout)
          }
        }

        "with 3 successful batches over 5 using resume-on-error" in {
          val coll =
            db[BSONCollection](s"bulkinsert2_${System identityHashCode label}")

          spec(coll, Supervision.resumingDecider)
            .aka("inserted") must beTypedEqualTo(3).awaitFor(timeout) and {
            coll.count() must beTypedEqualTo(3L).awaitFor(timeout)
          }
        }

        "with everything successful" in {
          implicit val intWriter: BSONDocumentWriter[Int] =
            BSONDocumentWriter[Int] { i =>
              BSONDocument("number" -> BSONInteger(i))
            }

          val coll =
            db[BSONCollection](s"bulkinsert3_${System identityHashCode label}")

          spec(coll, Supervision.stoppingDecider)
            .aka("inserted") must beTypedEqualTo(5).awaitFor(timeout) and {
            coll.count() must beTypedEqualTo(5L).awaitFor(timeout)
          }
        }
      }

    // ---

    bulkInsertSpec(
      label = "be ordered",
      { (b, w) =>
        implicit val writer: BSONDocumentWriter[Int] = w
        b.insertMany[Int](parallelism = 2).map(_.n)
      }
    )

    bulkInsertSpec(
      label = "be unordered",
      { (b, w) =>
        implicit val writer: BSONDocumentWriter[Int] = w
        b.insertManyUnordered[Int](parallelism = 10).map(_.n)
      }
    )
  }

  "Insert flow" should {
    type InsertFlow = Flow[Int, Int, NotUsed]

    def insertSpec(
        label: String,
        f: Function2[FlowBuilder, BSONDocumentWriter[Int], InsertFlow]
      ) =
      label >> {
        def spec(
            coll: BSONCollection,
            decider: Supervision.Decider
          )(implicit
            w: BSONDocumentWriter[Int]
          ): Future[Int] = {

          val builder = Flows(coll)
          val source = Source.fromIterator[Int](() => (0 until 5).iterator)

          val flow = f(builder, w).withAttributes(
            ActorAttributes.supervisionStrategy(decider)
          )

          source.via(flow).runWith(Sink.fold(0) { (c, r) => c + r.n })
        }

        // ---

        implicit val intWriter: BSONDocumentWriter[Int] =
          BSONDocumentWriter[Int] { i =>
            if (i % 2 == 0) BSONDocument("number" -> BSONInteger(i))
            else throw new Exception("Foo")
          }

        "with 0 successful batch & failure using stop-on-error" in {
          val coll =
            db[BSONCollection](s"insert1_${System identityHashCode label}")

          spec(coll, Supervision.stoppingDecider)
            .aka("inserted") must throwA[Exception]("Foo")
            .awaitFor(timeout) and {
            coll.count() must beTypedEqualTo(1L).awaitFor(timeout)
          }
        }

        "with 3 successful batches over 5 using resume-on-error" in {
          val coll =
            db[BSONCollection](s"insert2_${System identityHashCode label}")

          spec(coll, Supervision.resumingDecider)
            .aka("inserted") must beTypedEqualTo(3).awaitFor(timeout) and {
            coll.count() must beTypedEqualTo(3L).awaitFor(timeout)
          }
        }

        "with everything successful" in {
          implicit val intWriter: BSONDocumentWriter[Int] =
            BSONDocumentWriter[Int] { i =>
              BSONDocument("number" -> BSONInteger(i))
            }

          val coll =
            db[BSONCollection](s"insert3_${System identityHashCode label}")

          spec(coll, Supervision.stoppingDecider)
            .aka("inserted") must beTypedEqualTo(5).awaitFor(timeout) and {
            coll.count() must beTypedEqualTo(5L).awaitFor(timeout)
          }
        }
      }

    insertSpec(
      label = "be ordered",
      { (b, w) =>
        implicit val writer: BSONDocumentWriter[Int] = w
        b.insertOne[Int](parallelism = 1).map(_.n)
      }
    )

    insertSpec(
      label = "be unordered",
      { (b, w) =>
        implicit val writer: BSONDocumentWriter[Int] = w
        b.insertOneUnordered[Int](parallelism = 5).map(_.n)
      }
    )
  }

  // ---

  case class UpElmt(
      q: BSONDocument,
      u: BSONDocument,
      multi: Boolean,
      upsert: Boolean)

  "Bulk update flow" should {
    type TestBuilder = FlowBuilder => (
        (String, Int) => UpElmt
      ) => Flow[Iterable[(String, Int)], Int, NotUsed]

    def updateSpec(label: String)(tb: TestBuilder) = label >> {
      def spec(
          coll: BSONCollection,
          decider: Supervision.Decider = Supervision.stoppingDecider
        )(f: (String, Int) => UpElmt
        ): Future[Int] = {
        val builder = Flows(coll)
        val flow = tb(builder)(f)
          .withAttributes(ActorAttributes.supervisionStrategy(decider))

        Source
          .fromIterator(() =>
            (0 until 5).map { i =>
              val start = i * 10

              Seq.iterate(start, 10)(_ + 1).map { n => s"id${n}" -> n }
            }.iterator
          )
          .via(flow)
          .runWith(Sink.fold(0) { (c, n) => c + n })
      }

      "with no existing document updated and upsert disabled" in {
        val coll =
          db[BSONCollection](s"upbulk1_${System identityHashCode label}")

        spec(coll) { (str, i) =>
          val selector = BSONDocument("_id" -> str)

          UpElmt(
            q = selector,
            u = selector ++ BSONDocument("value" -> i),
            multi = false,
            upsert = false
          )

        } must beTypedEqualTo(0).awaitFor(timeout)
      }

      "with all documents upserted successfully" in {
        val coll =
          db[BSONCollection](s"upbulk2_${System identityHashCode label}")

        spec(coll) { (str, i) =>
          val selector = BSONDocument("_id" -> str)

          UpElmt(
            q = selector,
            u = selector ++ BSONDocument("value" -> i),
            multi = false,
            upsert = true
          )
        } must beTypedEqualTo(50).awaitFor(timeout) and {
          coll.count() must beTypedEqualTo(50L).awaitFor(timeout)
        }
      }

      implicit def intWriter: BSONDocumentWriter[(String, Int)] =
        BSONDocumentWriter[(String, Int)] {
          case (s, i) =>
            if (i < 15 || i > 19) {
              BSONDocument("_id" -> s, "value" -> i)
            } else {
              throw new Exception("Foo")
            }
        }

      "with 40 documents upserted using resume-on-error" in {
        val coll =
          db[BSONCollection](s"upbulk3_${System identityHashCode label}")

        spec(coll, Supervision.resumingDecider) { (str, i) =>
          val selector = BSONDocument("_id" -> str)

          UpElmt(
            q = selector,
            u = BSON.writeDocument(str -> i).get,
            multi = false,
            upsert = true
          )
        } must beTypedEqualTo(40).awaitFor(timeout) and {
          // 40 as exception is thrown in the middle of the batch 10-20
          coll.count() must beTypedEqualTo(40L).awaitFor(timeout)
        }
      }

      "with only first batch upserted using resume-on-error" in {
        val coll =
          db[BSONCollection](s"upbulk4_${System identityHashCode label}")

        spec(coll) { (str, i) =>
          val selector = BSONDocument("_id" -> str)

          UpElmt(
            q = selector,
            u = BSON.writeDocument(str -> i).get,
            multi = false,
            upsert = true
          )
        } must throwA[Exception]("Foo").awaitFor(timeout) and {
          // 10 as exception is thrown in the middle of the batch 10-20
          coll.count() must beTypedEqualTo(10L).awaitFor(timeout)
        }
      }
    }

    // ---

    updateSpec("be ordered") { b =>
      { (f: ((String, Int) => UpElmt)) =>
        b.updateMany[(String, Int)](parallelism = 2) {
          case (upd, (str, v)) =>
            val e = f(str, v)
            upd.element(e.q, e.u, e.upsert, e.multi, collation = None)
        }.map(_.n)
      }
    }

    updateSpec("be ordered") { b =>
      { (f: ((String, Int) => UpElmt)) =>
        b.updateManyUnordered[(String, Int)](parallelism = 5) {
          case (upd, (str, v)) =>
            val e = f(str, v)
            upd.element(e.q, e.u, e.upsert, e.multi, collation = None)
        }.map(_.n)
      }
    }
  }

  "Update flow" should {
    type TestBuilder = FlowBuilder => (
        (String, Int) => UpElmt
      ) => Flow[(String, Int), Int, NotUsed]

    def updateSpec(label: String)(tb: TestBuilder) = label >> {
      def spec(
          coll: BSONCollection,
          decider: Supervision.Decider = Supervision.stoppingDecider
        )(f: (String, Int) => UpElmt
        ): Future[Int] = {
        val builder = Flows(coll)
        val flow = tb(builder)(f)
          .withAttributes(ActorAttributes.supervisionStrategy(decider))

        Source
          .fromIterator(() =>
            (0 until 5).iterator.flatMap { i =>
              val start = i * 10

              Seq.iterate(start, 10)(_ + 1).map { n => s"id${n}" -> n }
            }
          )
          .via(flow)
          .runWith(Sink.fold(0) { (c, n) => c + n })
      }

      "with no existing document updated and upsert disabled" in {
        val coll =
          db[BSONCollection](s"update1_${System identityHashCode label}")

        spec(coll) { (str, i) =>
          val selector = BSONDocument("_id" -> str)

          UpElmt(
            q = selector,
            u = selector ++ BSONDocument("value" -> i),
            multi = false,
            upsert = false
          )

        } must beTypedEqualTo(0).awaitFor(timeout)
      }

      "with all documents upserted successfully" in {
        val coll =
          db[BSONCollection](s"update2_${System identityHashCode label}")

        spec(coll) { (str, i) =>
          val selector = BSONDocument("_id" -> str)

          UpElmt(
            q = selector,
            u = selector ++ BSONDocument("value" -> i),
            multi = false,
            upsert = true
          )
        } must beTypedEqualTo(50).awaitFor(timeout) and {
          coll.count() must beTypedEqualTo(50L).awaitFor(timeout)
        }
      }

      implicit def intWriter: BSONDocumentWriter[(String, Int)] =
        BSONDocumentWriter[(String, Int)] {
          case (s, i) =>
            if (i < 15 || i > 19) {
              BSONDocument("_id" -> s, "value" -> i)
            } else {
              throw new Exception("Foo")
            }
        }

      "with 40 documents upserted using resume-on-error" in {
        val coll =
          db[BSONCollection](s"update3_${System identityHashCode label}")

        spec(coll, Supervision.resumingDecider) { (str, i) =>
          val selector = BSONDocument("_id" -> str)

          UpElmt(
            q = selector,
            u = BSON.writeDocument(str -> i).get,
            multi = false,
            upsert = true
          )
        } must beTypedEqualTo(45).awaitFor(timeout) and {
          coll.count() must beTypedEqualTo(45L).awaitFor(timeout)
        }
      }

      "with only first batch upserted using resume-on-error" in {
        val coll =
          db[BSONCollection](s"update4_${System identityHashCode label}")

        spec(coll) { (str, i) =>
          val selector = BSONDocument("_id" -> str)

          UpElmt(
            q = selector,
            u = BSON.writeDocument(str -> i).get,
            multi = false,
            upsert = true
          )
        } must throwA[Exception]("Foo").awaitFor(timeout) and {
          coll.count() must beTypedEqualTo(15L).awaitFor(timeout)
        }
      }
    }

    // ---

    updateSpec("be ordered") { b =>
      { (f: ((String, Int) => UpElmt)) =>
        b.updateOne[(String, Int)](parallelism = 2) {
          case (upd, (str, v)) =>
            val e = f(str, v)
            upd.element(e.q, e.u, e.upsert, e.multi, collation = None)
        }.map(_.n)
      }
    }

    updateSpec("be ordered") { b =>
      { (f: ((String, Int) => UpElmt)) =>
        b.updateOneUnordered[(String, Int)](parallelism = 5) {
          case (upd, (str, v)) =>
            val e = f(str, v)
            upd.element(e.q, e.u, e.upsert, e.multi, collation = None)
        }.map(_.n)
      }
    }
  }

  type FlowBuilder = Flows[BSONSerializationPack.type, _ <: BSONCollection]
}
