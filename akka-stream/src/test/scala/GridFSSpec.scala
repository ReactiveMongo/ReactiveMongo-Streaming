import scala.concurrent.Future

import akka.util.ByteString

import akka.stream.scaladsl.{ Sink, Source }

import reactivemongo.bson._
import reactivemongo.bson.utils.Converters

import reactivemongo.api.BSONSerializationPack
import reactivemongo.api.gridfs.{ ReadFile, DefaultFileToSave, GridFS }
import reactivemongo.api.gridfs.Implicits._

import reactivemongo.akkastream.GridFSStreams

import org.specs2.concurrent.ExecutionEnv

final class GridFSSpec(implicit ee: ExecutionEnv)
  extends org.specs2.mutable.Specification {

  "GridFS" title

  sequential

  // ---

  import Common.{ db, timeout }

  implicit val system = akka.actor.ActorSystem(
    name = "reactivemongo-akkastream",
    defaultExecutionContext = Some(ee.ec))

  implicit val materializer = akka.stream.ActorMaterializer.create(system)

  // ---

  "Default connection" should {
    val prefix = s"fs${System identityHashCode db}"
    lazy val gfs = GridFS[BSONSerializationPack.type](db, prefix)
    lazy val streams = GridFSStreams(gfs)

    "ensure the indexes are ok" in {
      gfs.ensureIndex() must beTrue.await(2, timeout)
    }

    val filename1 = s"file1-${System identityHashCode gfs}"
    lazy val file1 = DefaultFileToSave(Some(filename1), Some("text/plain"))
    lazy val content1 = (100 to 200).view.map(_.toByte)

    "store a file with computed MD5" in {
      def source = Source.fromIterator { () =>
        content1.iterator.map(ByteString(_))
      }

      def store(): Future[Option[String]] =
        source.runWith(streams.sinkWithMD5(file1)).map(_.filename)

      store() aka "filename" must beSome(filename1).await(1, timeout)
    }

    "find the files" in {
      def find(n: String): Future[Option[GFile]] =
        gfs.find(BSONDocument("filename" -> n)).headOption

      def matchFile(
        actual: GFile,
        expected: DefaultFileToSave,
        content: Array[Byte]) = actual.filename must_=== expected.filename and {
        actual.uploadDate must beSome
      } and (actual.contentType must_=== expected.contentType) and {
        import scala.collection.mutable.ArrayBuilder

        def consume() = streams.source(actual).
          runWith(Sink.fold(ArrayBuilder.make[Byte]()) { _ ++= _ })

        val buf = new java.io.ByteArrayOutputStream()

        consume.map(_.result()) must beTypedEqualTo(content).
          await(1, timeout) and {
            gfs.readToOutputStream(actual, buf).
              map(_ => buf.toByteArray) must beTypedEqualTo(content).
              await(1, timeout)
          }
      }

      find(filename1) aka "file #1" must beSome[GFile].which { actual =>
        val bytes1 = content1.toArray
        def expectedMd5 = Converters.hex2Str(Converters.md5(bytes1))

        matchFile(actual, file1, bytes1) and {
          actual.md5 must beSome[String].which {
            _ aka "MD5" must_== expectedMd5
          }
        }
      }.await(1, timeout)
    }

    "delete the files from GridFS" in {
      gfs.remove(file1.id).map(_.n) must beTypedEqualTo(1).await(1, timeout)
    }
  }

  // ---

  type GFile = ReadFile[BSONSerializationPack.type, BSONValue]
}
