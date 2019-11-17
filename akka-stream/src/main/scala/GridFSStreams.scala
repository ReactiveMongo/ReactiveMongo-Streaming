package reactivemongo.akkastream

import java.util.Arrays

import scala.concurrent.{ ExecutionContext, Future }

import akka.util.ByteString

import akka.stream.Materializer
import akka.stream.scaladsl.{ Sink, Source }

import reactivemongo.api.ReadPreference

import reactivemongo.api.gridfs.{ FileToSave, GridFS => CoreFS }

/**
 * Akka-stream support for GridFS.
 *
 * @define fileParam the metadata of the file to store.
 * @define chunkSizeParam the size (in byte) of the chunks
 * @define IdTypeParam the type of the id of this file (generally `BSONObjectID` or `BSONValue`)
 */
sealed trait GridFSStreams {
  private[akkastream] type Pack <: Compat.SerPack

  val gridfs: CoreFS[Pack]

  import GridFSStreams.logger
  import gridfs.{ defaultReadPreference, pack, ReadFile }

  /**
   * Returns an `Sink` that will consume data to put into a GridFS store.
   *
   * @param file $fileParam
   * @param chunkSize $chunkSizeParam (default: [[https://docs.mongodb.com/manual/core/gridfs/ 255kB]])
   */
  def sinkWithMD5[Id <: pack.Value](
    file: FileToSave[pack.type, Id],
    chunkSize: Int = 261120)(
    implicit
    ec: ExecutionContext): Sink[ByteString, Future[ReadFile[Id]]] = {
    import java.security.MessageDigest

    sink[Id, MessageDigest](file, MessageDigest.getInstance("MD5"),
      { (md: MessageDigest, chunk) => md.update(chunk); md },
      { md: MessageDigest => Future(md.digest()).map(Some(_)) },
      chunkSize)
  }

  /**
   * Returns an `Sink` that will consume data to put into a GridFS store.
   *
   * @param file $fileParam
   * @param digestInit the factory for the message digest
   * @param digestUpdate the function to update the digest
   * @param digestFinalize the function to finalize the digest
   * @param chunkSize $chunkSizeParam
   *
   * @tparam Id $IdTypeParam
   * @tparam M the type of the message digest
   */
  def sink[Id <: pack.Value, M](file: FileToSave[pack.type, Id], digestInit: => M, digestUpdate: (M, Array[Byte]) => M, digestFinalize: M => Future[Option[Array[Byte]]], chunkSize: Int)(implicit ec: ExecutionContext): Sink[ByteString, Future[ReadFile[Id]]] = {
    def initial = new StoreState[Id, M](
      file, Array.empty, 0, digestInit, digestUpdate, 0, chunkSize)

    Sink.foldAsync[StoreState[Id, M], Array[Byte]](initial) { (prev, chunk) =>
      logger.debug(s"Processing new enumerated chunk from n=${prev.n}...\n")

      prev.feed(chunk)
    }.contramap[ByteString](_.toArray[Byte]).
      mapMaterializedValue(_.flatMap(_.finish(digestFinalize)))
  }

  /**
   * Produces an enumerator of chunks of bytes from the `chunks` collection
   * matching the given file metadata.
   *
   * @param file the file to be read
   */
  def source[Id <: pack.Value](file: ReadFile[Id], readPreference: ReadPreference = defaultReadPreference)(implicit m: Materializer): Source[ByteString, Future[State]] = {
    @com.github.ghik.silencer.silent
    implicit def ec = m.executionContext

    def cursor = gridfs.chunks(file, readPreference)

    reactivemongo.akkastream.cursorProducer[Array[Byte]].
      produce(cursor).documentSource().map(ByteString(_))
  }

  // ---

  /*
   * @param file $fileParam
   * @tparam Id $IdTypeParam
   */
  private final class StoreState[Id <: pack.Value, M](
      file: FileToSave[pack.type, Id],
      previous: Array[Byte],
      val n: Int,
      md: M,
      digestUpdate: (M, Array[Byte]) => M,
      length: Int,
      chunkSize: Int
  ) {
    def feed(chunk: Array[Byte])(implicit ec: ExecutionContext): Future[StoreState[Id, M]] = {
      val wholeChunk = concat(previous, chunk)

      val normalizedChunkNumber = wholeChunk.length / chunkSize

      logger.debug(s"wholeChunk size is ${wholeChunk.length} => ${normalizedChunkNumber}")

      val zipped =
        for (i <- 0 until normalizedChunkNumber)
          yield Arrays.copyOfRange(
          wholeChunk, i * chunkSize, (i + 1) * chunkSize) -> i

      val left = Arrays.copyOfRange(
        wholeChunk, normalizedChunkNumber * chunkSize, wholeChunk.length)

      Future.traverse(zipped) { ci =>
        writeChunk(n + ci._2, ci._1)
      }.map { _ =>
        logger.debug("all futures for the last given chunk are redeemed.")
        new StoreState[Id, M](
          file,
          if (left.isEmpty) Array.empty else left,
          n + normalizedChunkNumber,
          digestUpdate(md, chunk),
          digestUpdate,
          length + chunk.length,
          chunkSize)
      }
    }

    import reactivemongo.bson.utils.Converters

    @inline def finish(
      digestFinalize: M => Future[Option[Array[Byte]]])(
      implicit
      ec: ExecutionContext): Future[ReadFile[Id]] =
      digestFinalize(md).map(_.map(Converters.hex2Str)).flatMap { md5Hex =>
        gridfs.finalizeFile[Id](
          file, previous, n, chunkSize, length.toLong, md5Hex)
      }

    @inline def writeChunk(n: Int, bytes: Array[Byte])(implicit ec: ExecutionContext) = gridfs.writeChunk(file.id, n, bytes)

    /** Concats two array - fast way */
    private def concat[T](a1: Array[T], a2: Array[T])(implicit m: Manifest[T]): Array[T] = {
      var i, j = 0
      val result = new Array[T](a1.length + a2.length)
      while (i < a1.length) {
        result(i) = a1(i)
        i = i + 1
      }
      while (j < a2.length) {
        result(i + j) = a2(j)
        j = j + 1
      }
      result
    }
  }
}

object GridFSStreams {
  private[akkastream] lazy val logger =
    reactivemongo.util.LazyLogger("reactivemongo.akkastream.GridFSStreams")

  /** Returns an Akka-stream support for given GridFS. */
  def apply[P <: Compat.SerPack](gridfs: CoreFS[P]) = {
    def gfs = gridfs

    new GridFSStreams {
      type Pack = P
      val gridfs = gfs
    }
  }
}
