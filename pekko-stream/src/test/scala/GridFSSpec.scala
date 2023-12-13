import scala.concurrent.Future

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{ Sink, Source }
import org.apache.pekko.util.ByteString

import reactivemongo.api.bson._

import reactivemongo.api.gridfs.FileToSave

import reactivemongo.pekkostream.GridFSStreams

import org.specs2.concurrent.ExecutionEnv

import org.apache.commons.codec.digest.DigestUtils.md5Hex

final class GridFSSpec(
    implicit
    ee: ExecutionEnv)
    extends org.specs2.mutable.Specification {

  "GridFS".title

  sequential

  // ---

  import Common.{ db, timeout }

  implicit val system: ActorSystem = ActorSystem(
    name = "reactivemongo-pekkostream",
    defaultExecutionContext = Some(ee.ec)
  )

  implicit lazy val materializer: Materializer =
    org.apache.pekko.stream.Materializer.createMaterializer(system)

  // ---

  lazy val gfs = {
    val prefix = s"fs${System identityHashCode db}"

    def resolve = db.gridfs(prefix)

    resolve
  }

  type GFile = gfs.ReadFile[BSONValue]

  lazy val streams = GridFSStreams(gfs)

  "GridFS" should {
    "ensure the indexes are ok" in {
      gfs.ensureIndex() must beTrue.await(2, timeout)
    }

    val filename1 = s"file1-${System identityHashCode gfs}"
    lazy val file1 = gfs.fileToSave(Some(filename1), Some("text/plain"))
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
          expected: FileToSave[_, _],
          content: Array[Byte]
        ) = actual.filename must_=== expected.filename and {
        actual.uploadDate must beSome
      } and (actual.contentType must_=== expected.contentType) and {
        def consume() = streams
          .source(actual)
          .runWith(Sink.fold(Seq.newBuilder[Byte]) { _ ++= _ })

        consume().map(_.result().toArray).aka("consumed") must beTypedEqualTo(
          content
        ).await(1, timeout)
      }

      find(filename1) aka "file #1" must beSome[GFile].which { actual =>
        val bytes1 = content1.toArray

        matchFile(actual, file1, bytes1) and {
          actual.md5 must beSome[String].which {
            _ aka "MD5" must_=== md5Hex(bytes1)
          }
        }
      }.await(1, timeout)
    }

    "delete the files from GridFS" in {
      gfs.remove(file1.id).map(_.n) must beTypedEqualTo(1).await(1, timeout)
    }
  }
}
