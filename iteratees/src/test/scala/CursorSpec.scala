import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._

import play.api.libs.iteratee.Iteratee

import reactivemongo.bson.{
  BSONDocument,
  BSONDocumentReader,
  BSONDocumentWriter
}
import reactivemongo.api.{
  Cursor,
  DB,
  QueryOpts
}, Cursor.{ ContOnError, FailOnError }
import reactivemongo.core.protocol.Response
import reactivemongo.play.iteratees.PlayIterateesCursor

import org.specs2.concurrent.ExecutionEnv

class CursorSpec(implicit ee: ExecutionEnv)
  extends org.specs2.mutable.Specification {

  "Cursor" title

  sequential

  import Common._
  import reactivemongo.play.iteratees.cursorProducer

  "BSON collection" should {
    "be provided the fixtures" in {
      val fixtures = List(
        Person("Jack", 25),
        Person("James", 16),
        Person("John", 34),
        Person("Jane", 24),
        Person("Joline", 34)
      )

      implicit val writer = PersonWriter

      Future.sequence(fixtures.map(personColl.insert(_).map(_.ok))).
        aka("fixtures") must beEqualTo(List(true, true, true, true, true)).
        awaitFor(timeout)
    }

    "read empty cursor" >> {
      def cursor: PlayIterateesCursor[BSONDocument] =
        personColl.find(BSONDocument("plop" -> "plop")).cursor[BSONDocument]()

      "with success using enumerate" in {
        val cur = cursor
        val enumerator = cur.enumerator(10)

        (enumerator |>>> Iteratee.fold(0) { (r, doc) => r + 1 }).
          aka("read") must beEqualTo(0).awaitFor(timeout)
      }
    }

    "read documents until error" in {
      implicit val reader = new SometimesBuggyPersonReader
      val enumerator = personColl.find(BSONDocument()).
        cursor[Person]().enumerator()

      var i = 0
      (enumerator |>>> Iteratee.foreach { doc =>
        i += 1
        //println(s"\tgot doc: $doc")
      } map (_ => -1)).
        recover({ case _ => i }) must beEqualTo(3).awaitFor(timeout)
    }

    "read documents skipping errors" in {
      implicit val reader = new SometimesBuggyPersonReader
      val enumerator = personColl.find(BSONDocument()).
        cursor[Person]().enumerator(err = ContOnError[Unit]())

      var i = 0
      (enumerator |>>> Iteratee.foreach { doc =>
        i += 1
        //println(s"\t(skipping [$i]) got doc: $doc")
      }).map(_ => i) must beEqualTo(4).awaitFor(timeout)
    }

    val nDocs = 16517
    s"insert $nDocs records" in {
      def insert(rem: Int, bulks: Seq[Future[Unit]]): Future[Unit] = {
        if (rem == 0) {
          Future.sequence(bulks).map(_ => {})
        } else {
          val len = if (rem < 256) rem else 256
          val prepared = nDocs - rem

          def bulk = coll2.insert[BSONDocument](ordered = false).many(
            for (i <- 0 until len) yield {
              val n = i + prepared
              BSONDocument("i" -> n, "record" -> s"record$n")
            }).map(_ => {})

          insert(rem - len, bulk +: bulks)
        }
      }

      insert(nDocs, Seq.empty) must beEqualTo({}).awaitFor(20.seconds)
    } tag "wip"

    "enumerate" >> {
      s"all the $nDocs documents" in {
        var i = 0
        coll2.find(BSONDocument.empty).cursor[BSONDocument]().enumerator() |>>> (
          Iteratee.foreach { e: BSONDocument =>
            //println(s"doc $i => $e")
            i += 1
          }
        ).map(_ => i) must beEqualTo(16517).awaitFor(21.seconds)
      }

      "only 1024 documents" in {
        var i = 0
        coll2.find(BSONDocument.empty).cursor[BSONDocument]().
          enumerator(1024) |>>> (Iteratee.foreach { e: BSONDocument =>
            //println(s"doc $i => $e")
            i += 1
          }).map(_ => i) must beEqualTo(1024).awaitFor(timeout)
      }
    }

    "enumerate bulks" >> {
      "for all the documents" in {
        var i = 0
        coll2.find(BSONDocument.empty).cursor[BSONDocument]().
          bulkEnumerator() |>>> (
            Iteratee.foreach { it: Iterator[BSONDocument] =>
              //println(s"doc $i => $e")
              i += it.size
            }
          ).map(_ => i) must beEqualTo(16517).awaitFor(21.seconds)
      }

      "for only 1024 documents" in {
        var i = 0
        coll2.find(BSONDocument.empty).cursor[BSONDocument]().
          bulkEnumerator(1024) |>>> (
            Iteratee.foreach { it: Iterator[BSONDocument] =>
              //println(s"doc $i => $e")
              i += it.size
            }
          ).map(_ => i) must beEqualTo(1024).awaitFor(timeout)
      }
    }

    "enumerate responses" >> {
      s"for all the $nDocs documents" in {
        var i = 0
        coll2.find(BSONDocument.empty).cursor[BSONDocument]().
          responseEnumerator() |>>> (Iteratee.foreach { r: Response =>
            //println(s"doc $i => $e")
            i += r.reply.numberReturned
          }).map(_ => i) must beEqualTo(16517).awaitFor(21.seconds)
      }

      "for only 1024 documents" in {
        var i = 0
        coll2.find(BSONDocument.empty).cursor[BSONDocument]().
          responseEnumerator(1024) |>>> (Iteratee.foreach { r: Response =>
            //println(s"doc $i => $e")
            i += r.reply.numberReturned
          }).map(_ => i) must beEqualTo(1024).awaitFor(timeout)
      }
    }

    "stop on error" >> {
      val drv = Common.newDriver
      def con = drv.connection(List(primaryHost), DefaultOptions)
      def scol(n: String = coll2.name) =
        Await.result(con.database(db.name).map(_.collection(n)), timeout)

      "when enumerating responses" >> {
        "if fails while processing with existing documents (#1)" in {
          var count = 0
          val inc = Iteratee.foreach[Response] { _ =>
            if (count == 1) sys.error("Foo")

            count = count + 1
          }
          val c = scol()
          val cursor = c.find(BSONDocument.empty).options(
            QueryOpts(batchSizeN = 2)
          ).cursor()

          (cursor.responseEnumerator(10, FailOnError[Unit]()) |>>> inc).
            map(_ => count).recover({ case _ => count }).
            aka("enumerating") must beEqualTo(1).awaitFor(timeout)

        }

        "if fails while processing with existing documents (#2)" in {
          // As the error is on the consuming side (Iteratee)
          var count = 0
          var i = 0
          val inc = Iteratee.foreach[Response] { _ =>
            i = i + 1
            if (i % 2 == 0) sys.error("Foo")
            count = count + 1
          }
          val c = scol()
          val cursor = c.find(BSONDocument.empty).options(QueryOpts(
            batchSizeN = 4
          )).cursor()

          (cursor.responseEnumerator(128, ContOnError[Unit]()) |>>> inc).
            recover({ case _ => count }) must beEqualTo(1).awaitFor(timeout)
        }

        "if fails while processing w/o documents (#1)" in {
          var count = 0
          val inc = Iteratee.foreach[Response] { _ =>
            count = count + 1
            sys.error("Foo")
          }
          val c = scol(System.identityHashCode(inc).toString)
          val cursor = c.find(BSONDocument.empty).options(
            QueryOpts(batchSizeN = 2)
          ).cursor()

          (cursor.responseEnumerator(10, FailOnError[Unit]()) |>>> inc).
            recover({ case _ => count }) must beEqualTo(1).awaitFor(timeout)
        }

        "if fails while processing w/o documents (#2)" in {
          // As the error is on the consuming side (Iteratee)
          var count = 0
          val inc = Iteratee.foreach[Response] { _ =>
            count = count + 1
            sys.error("Foo")
          }
          val c = scol(System.identityHashCode(inc).toString)
          val cursor = c.find(BSONDocument.empty).
            options(QueryOpts(batchSizeN = 2)).cursor()

          (cursor.responseEnumerator(64, ContOnError[Unit]()) |>>> inc).
            recover({ case _ => count }) must beEqualTo(1).awaitFor(timeout)
        }

        "if fails to send request" in {
          var count = 0
          val inc = Iteratee.foreach[Response] { _ => count = count + 1 }
          val c = scol()
          val cursor = c.find(BSONDocument.empty).cursor()

          c.db.connection.askClose().map(_ => {}).
            aka("closed") must beTypedEqualTo({}).awaitFor(timeout) and {
              // Close connection to make the related cursor erroneous

              (cursor.responseEnumerator(10, FailOnError[Unit]()) |>>> inc).
                map(_ => count).recover({ case _ => count }).
                aka("count") must beEqualTo(0).awaitFor(timeout)
            }
        } tag "wip"
      }

      "when enumerating bulks" >> {
        "if fails while processing with existing documents (#1)" in {
          var count = 0
          val inc = Iteratee.foreach[Iterator[BSONDocument]] { _ =>
            if (count == 1) sys.error("Foo")

            count = count + 1
          }
          val c = scol()
          val cursor = c.find(BSONDocument.empty).options(
            QueryOpts(batchSizeN = 2)
          ).cursor()

          (cursor.bulkEnumerator(10, FailOnError[Unit]()) |>>> inc).
            recover({ case _ => count }).
            aka("enumerating") must beEqualTo(1).awaitFor(timeout)
        }

        "if fails while processing with existing documents (#2)" in {
          var count = 0
          var i = 0
          val inc = Iteratee.foreach[Iterator[BSONDocument]] { x =>
            i = i + 1
            if (i % 2 == 0) sys.error("Foo")
            count = count + 1
          }
          val cursor = scol().find(BSONDocument.empty).options(QueryOpts(
            batchSizeN = 4
          )).cursor()

          def consumed: Future[Unit] =
            cursor.bulkEnumerator(32, ContOnError[Unit]()) |>>> inc

          consumed.map(_ => -1).recover({ case _ => count }).
            aka("result") must beEqualTo(1).awaitFor(timeout)
        }

        "if fails while processing w/o documents (#1)" in {
          var count = 0
          val inc = Iteratee.foreach[Iterator[BSONDocument]] { _ =>
            count = count + 1
            sys.error("Foo")
          }
          val c = scol(System.identityHashCode(inc).toString)
          val cursor = c.find(BSONDocument.empty).options(
            QueryOpts(batchSizeN = 2)
          ).cursor()

          (cursor.bulkEnumerator(10, FailOnError[Unit]()) |>>> inc).
            recover({ case _ => count }) must beEqualTo(1).awaitFor(timeout)
        }

        "if fails while processing w/o documents (#2)" in {
          var count = 0
          val inc = Iteratee.foreach[Iterator[BSONDocument]] { _ =>
            count = count + 1
            sys.error("Foo")
          }
          val c = scol(System.identityHashCode(inc).toString)
          val cursor = c.find(BSONDocument.empty).
            options(QueryOpts(batchSizeN = 2)).cursor()

          (cursor.bulkEnumerator(64, ContOnError[Unit]()) |>>> inc).
            recover({ case _ => count }) must beEqualTo(1).awaitFor(timeout)
        }

        "if fails to send request" in {
          var count = 0
          val inc = Iteratee.foreach[Iterator[BSONDocument]] { _ =>
            count = count + 1
          }
          val c = scol()
          val cursor = c.find(BSONDocument.empty).cursor()

          c.db.connection.askClose().map(_ => {}).
            aka("closed") must beTypedEqualTo({}).awaitFor(timeout) and {
              // Close connection to make the related cursor erroneous

              (cursor.bulkEnumerator(10, FailOnError[Unit]()) |>>> inc).
                recover({ case _ => count }) must beEqualTo(0).awaitFor(timeout)
            }
        }
      }

      "when enumerating documents" >> {
        "if fails while processing with existing documents" in {
          var count = 0
          val inc = Iteratee.foreach[BSONDocument] { _ =>
            if (count == 5) sys.error("Foo")

            count = count + 1
          }
          val c = scol()
          val cursor = c.find(BSONDocument.empty).cursor()

          (cursor.enumerator(10, FailOnError[Unit]()) |>>> inc).
            recover({ case _ => count }) must beEqualTo(5).awaitFor(timeout)
        }

        "if fails to send request" in {
          var count = 0
          val inc = Iteratee.foreach[BSONDocument] { _ => count = count + 1 }
          val c = scol()
          val cursor = c.find(BSONDocument.empty).cursor()

          c.db.connection.askClose().map(_ => {}).
            aka("closed") must beTypedEqualTo({}).awaitFor(timeout) and {
              // Close connection to make the related cursor erroneous

              (cursor.enumerator(10, FailOnError[Unit]()) |>>> inc).
                recover({ case _ => count }) must beEqualTo(0).awaitFor(timeout)
            }
        }
      }

      "Driver instance must be closed" in {
        drv.close() must not(throwA[Exception])
      }
    }

    "continue on error" >> {
      val drv = Common.newDriver
      def con = drv.connection(List(primaryHost), DefaultOptions)
      def scol(n: String = coll2.name) =
        Await.result(con.database(db.name).map(_(n)), timeout)

      "when enumerating responses" >> {
        "if fails to send request" in {
          var count = 0
          val inc = Iteratee.foreach[Response] { _ => count = count + 1 }
          val c = scol()
          val cursor = c.find(BSONDocument.empty).cursor()

          c.db.connection.askClose().map(_ => {}).
            aka("closed") must beTypedEqualTo({}).awaitFor(timeout) and {
              // Close connection to make the related cursor erroneous

              (cursor.responseEnumerator(128, ContOnError[Unit]()) |>>> inc).
                map(_ => count) must beEqualTo(0).awaitFor(timeout)
            }
        }
      }

      "when enumerating bulks" >> {
        "if fails to send request" in {
          var count = 0
          val inc = Iteratee.foreach[Iterator[BSONDocument]] {
            _ => count = count + 1
          }
          val c = scol()
          val cursor = c.find(BSONDocument.empty).cursor()

          c.db.connection.askClose().map(_ => {}).
            aka("closed") must beTypedEqualTo({}).awaitFor(timeout) and {
              // Close connection to make the related cursor erroneous

              (cursor.bulkEnumerator(128, ContOnError[Unit]()) |>>> inc).
                map(_ => count) must beEqualTo(0).awaitFor(timeout)
            }
        }
      }

      "when enumerating documents" >> {
        "if fails while processing with existing documents" in {
          var count = 0
          var i = 0
          val inc = Iteratee.foreach[BSONDocument] { _ =>
            i = i + 1
            if (i % 2 == 0) sys.error("Foo")
            count = count + 1
          }
          val c = scol()
          val cursor = c.find(BSONDocument.empty).options(QueryOpts(
            batchSizeN = 4
          )).cursor()

          (cursor.enumerator(128, ContOnError[Unit]()) |>>> inc).
            recover({ case _ => count }) must beEqualTo(1).awaitFor(timeout)
        }

        "if fails while processing w/o documents" in {
          var count = 0
          val inc = Iteratee.foreach[BSONDocument] { _ =>
            count = count + 1
            sys.error("Foo")
          }
          val c = scol(System.identityHashCode(inc).toString)
          val cursor = c.find(BSONDocument.empty).
            options(QueryOpts(batchSizeN = 2)).cursor()

          (cursor.enumerator(64, ContOnError[Unit]()) |>>> inc).map(_ => count).
            aka("enumerating") must beEqualTo(0).awaitFor(timeout)
        }

        "if fails to send request" in {
          var count = 0
          val inc = Iteratee.foreach[BSONDocument] {
            _ => count = count + 1
          }
          val c = scol()
          val cursor = c.find(BSONDocument.empty).cursor()

          c.db.connection.askClose().map(_ => {}).
            aka("closed") must beTypedEqualTo({}).awaitFor(timeout) and {
              // Close connection to make the related cursor erroneous

              (cursor.enumerator(128, ContOnError[Unit]()) |>>> inc).
                map(_ => count) must beEqualTo(0).awaitFor(timeout)
            }
        }
      }

      "Driver instance must be closed" in {
        drv.close() must not(throwA[Exception])
      }
    }
  }

  "BSON Cursor" should {
    object IdReader extends BSONDocumentReader[Int] {
      def read(doc: BSONDocument): Int = doc.getAs[Int]("id").get
    }

    val expectedList = List(9, 8, 7, 6, 5, 4, 3, 2, 1, 0)
    def toList =
      Iteratee.fold[Int, List[Int]](List.empty[Int]) { (l, i) => i :: l }

    "read from collection" >> {
      def collection(n: String) = {
        val col = db(s"somecollection_$n")

        Future.sequence((0 until 10) map { id =>
          col.insert[BSONDocument](ordered = true).one(BSONDocument("id" -> id))
        }) map { _ =>
          logger.debug(s"-- all documents inserted in test collection $n")
          col
        }
      }

      def cursor(n: String): PlayIterateesCursor[Int] = {
        implicit val reader = IdReader
        Cursor.flatten(collection(n).map(_.find(BSONDocument()).
          sort(BSONDocument("id" -> 1)).cursor[Int]()))
      }

      "successfully using cursor enumerator" >> {
        "per document" in {
          (cursor("senum1").enumerator(10) |>>> toList).
            aka("enumerated") must beEqualTo(expectedList).awaitFor(timeout)
        }

        "per bulk" in {
          val collect = Iteratee.fold[Iterator[Int], List[Int]](
            List.empty[Int]
          ) { _ ++ _ }

          (cursor("senum2").bulkEnumerator(10) |>>> collect).map(_.reverse).
            aka("enumerated") must beEqualTo(expectedList).awaitFor(timeout)
        }
      }
    }

    "read from capped collection" >> {
      def collection(n: String, database: DB) = {
        val col = database(s"somecollection_captail_$n")

        col.createCapped(4096, Some(10)).flatMap { _ =>
          (0 until 10).foldLeft(Future successful {}) { (f, id) =>
            f.flatMap(_ =>
              col.insert[BSONDocument](ordered = true).
                one(BSONDocument("id" -> id)).map(_ => Thread.sleep(200)))

          }.map(_ =>
            logger.debug(s"-- all documents inserted in test collection $n"))
        }

        col
      }

      @inline def tailable(n: String, database: DB = db) = {
        implicit val reader = IdReader
        collection(n, database).find(BSONDocument()).options(
          QueryOpts().tailable
        ).cursor[Int]()
      }

      "successfully using tailable enumerator with maxDocs" in {
        (tailable("tenum1").enumerator(10) |>>> toList).
          aka("enumerated") must beEqualTo(expectedList).awaitFor(timeout)
      }

      "with timeout using tailable enumerator w/o maxDocs" in {
        Await.result((tailable("tenum2").enumerator() |>>> toList), timeout).
          aka("enumerated") must throwA[java.util.concurrent.TimeoutException]
      }
    }
  }

  // ---

  lazy val personColl = db("playiteratees_person")
  lazy val coll2 = db(s"playiteratees_${System identityHashCode personColl}")

  case class Person(name: String, age: Int)

  class SometimesBuggyPersonReader extends BSONDocumentReader[Person] {
    private var i = 0
    def read(doc: BSONDocument): Person = {
      i += 1
      if (i % 4 == 0) throw CustomException("hey hey hey")
      else Person(doc.getAs[String]("name").get, doc.getAs[Int]("age").get)
    }
  }

  object PersonWriter extends BSONDocumentWriter[Person] {
    def write(p: Person): BSONDocument =
      BSONDocument("age" -> p.age, "name" -> p.name)
  }

  case class CustomException(msg: String) extends Exception(msg)
}
