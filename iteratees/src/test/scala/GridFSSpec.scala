import scala.concurrent.{ Await, Future }
import scala.concurrent.duration.FiniteDuration

import play.api.libs.iteratee._

import reactivemongo.api.bson.{ BSONDocument, BSONValue }
import reactivemongo.bson.utils.Converters

import reactivemongo.api.bson.collection.BSONSerializationPack

import reactivemongo.api.gridfs.ReadFile

import reactivemongo.play.iteratees.GridFS

import org.specs2.concurrent.ExecutionEnv

import com.github.ghik.silencer.silent

final class GridFSSpec(implicit ee: ExecutionEnv)
  extends org.specs2.mutable.Specification
  with org.specs2.specification.AfterAll {

  "GridFS (with Iteratee support)" title

  sequential

  // ---

  lazy val db = {
    val _db = Common.connection.database(
      s"iteratees-gridfs-${System identityHashCode this}",
      Common.failoverStrategy
    )

    Await.result(_db.flatMap { d => d.drop.map(_ => d) }, Common.timeout)
  }

  def afterAll = { db.drop(); () }

  // ---

  "Default connection" should {
    val prefix = s"fs${System identityHashCode db}"

    @inline def gfs: GridFS[BSONSerializationPack.type] =
      GridFS(db.gridfs(BSONSerializationPack, prefix))

    gridFsSpec(gfs, Common.timeout)
  }

  // ---

  type GFile = ReadFile[BSONValue, BSONDocument]

  @silent("DefaultReadFileReader\\ in\\ object\\ Implicits\\ is\\ deprecated")
  def gridFsSpec(
    gfs: GridFS[BSONSerializationPack.type],
    timeout: FiniteDuration) = {

    val fs = gfs.gridfs

    "ensure the indexes are ok" in {
      fs.ensureIndex() must beTrue.await(1, timeout)
    }

    val filename2 = s"file2-${System identityHashCode gfs}"
    lazy val file2 = fs.fileToSave(Some(filename2), Some("text/plain"))
    lazy val content2 = (100 to 200).view.map(_.toByte).toArray

    "store a file with computed MD5" in {
      gfs.save(Enumerator(content2), file2).map(_.filename).
        aka("filename") must beSome(filename2).await(1, timeout)
    }

    "find the files" in {
      def find(n: String): Future[Option[GFile]] =
        fs.find(BSONDocument("filename" -> n)).headOption

      @silent("DefaultFileToSave\\ in\\ package\\ gridfs\\ is\\ deprecated")
      def matchFile(
        actual: GFile,
        expected: fs.FileToSave[BSONValue],
        content: Array[Byte]) = actual.filename must_=== expected.filename and {
        actual.uploadDate must beSome
      } and (actual.contentType must_=== expected.contentType) and {
        import scala.collection.mutable.ArrayBuilder
        def res = gfs.enumerate(actual) |>>>
          Iteratee.fold(ArrayBuilder.make[Byte]()) { _ ++= _ }

        val buf = new java.io.ByteArrayOutputStream()

        res.map(_.result()) must beTypedEqualTo(content).awaitFor(timeout) and {
          fs.readToOutputStream(actual, buf).
            map(_ => buf.toByteArray) must beTypedEqualTo(content).
            awaitFor(timeout)
        }
      }

      find(filename2) aka "file #2" must beSome[GFile].which { actual =>
        def expectedMd5 = Converters.hex2Str(Converters.md5(content2))

        matchFile(actual, file2, content2) and {
          actual.md5 must beSome[String].which {
            _ aka "MD5" must_== expectedMd5
          }
        }
      }.awaitFor(timeout)
    }
  }
}
