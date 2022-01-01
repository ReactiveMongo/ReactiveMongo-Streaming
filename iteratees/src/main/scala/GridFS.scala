package reactivemongo.play.iteratees

import java.util.Arrays

import scala.concurrent.{ ExecutionContext, Future }

import reactivemongo.api.bson.collection.BSONCollectionProducer

import reactivemongo.api.{ Cursor, DB, SerializationPack }
import reactivemongo.api.collections.{
  GenericCollection,
  GenericCollectionProducer
}
import reactivemongo.api.gridfs.{ GridFS => CoreFS }

import play.api.libs.iteratee.{ Concurrent, Enumerator, Iteratee }

final class GridFS[P <: SerializationPack] private[iteratees] (
    val gridfs: CoreFS[P]) { self =>

  import GridFS.logger
  import gridfs.{ FileToSave, ReadFile, defaultReadPreference, pack }

  /**
   * Saves the content provided by the given enumerator with the given metadata.
   *
   * @param enumerator Producer of content.
   * @param file Metadata of the file to store.
   * @param chunkSize Size of the chunks. Defaults to 256kB.
   *
   * @return A future of a ReadFile[Id].
   */
  def save[Id <: pack.Value](
      enumerator: Enumerator[Array[Byte]],
      file: FileToSave[Id],
      chunkSize: Int = 262144
    )(implicit
      ec: ExecutionContext
    ): Future[ReadFile[Id]] =
    (enumerator |>>> iteratee(file, chunkSize)).flatMap(f => f)

  def iteratee[Id <: pack.Value](
      file: FileToSave[Id],
      chunkSize: Int = 262144
    )(implicit
      ec: ExecutionContext
    ): Iteratee[Array[Byte], Future[ReadFile[Id]]] = {
    import java.security.MessageDigest

    val digestUpdate = { (md: MessageDigest, chunk: Array[Byte]) =>
      md.update(chunk)
      md
    }
    val digestFinalize = { md: MessageDigest => Future(md.digest()) }

    final case class Chunk(
        previous: Array[Byte],
        n: Int,
        md: MessageDigest,
        length: Int) {
      def feed(chunk: Array[Byte]): Future[Chunk] = {
        val wholeChunk = concat(previous, chunk)

        val normalizedChunkNumber = wholeChunk.length / chunkSize

        logger.debug(
          s"wholeChunk size is ${wholeChunk.length} => ${normalizedChunkNumber}"
        )

        val zipped =
          for (i <- 0 until normalizedChunkNumber)
            yield Arrays.copyOfRange(
              wholeChunk,
              i * chunkSize,
              (i + 1) * chunkSize
            ) -> i

        val left = Arrays.copyOfRange(
          wholeChunk,
          normalizedChunkNumber * chunkSize,
          wholeChunk.length
        )

        Future.traverse(zipped) { ci => writeChunk(n + ci._2, ci._1) }.map {
          _ =>
            logger.debug("all futures for the last given chunk are redeemed.")
            Chunk(
              if (left.isEmpty) Array.empty else left,
              n + normalizedChunkNumber,
              digestUpdate(md, chunk),
              length + chunk.length
            )
        }
      }

      import reactivemongo.api.bson.Digest

      @inline def finish(): Future[ReadFile[Id]] =
        digestFinalize(md).map(Digest.hex2Str).flatMap { md5Hex =>
          gridfs.finalizeFile[Id](
            file,
            previous,
            n,
            chunkSize,
            length.toLong,
            Some(md5Hex)
          )
        }

      @inline def writeChunk(n: Int, bytes: Array[Byte]) =
        gridfs.writeChunk(file.id, n, bytes)
    }

    def digestInit = MessageDigest.getInstance("MD5")

    Iteratee
      .foldM(Chunk(Array.empty, 0, digestInit, 0)) {
        (previous, chunk: Array[Byte]) =>
          logger.debug(
            s"Processing new enumerated chunk from n=${previous.n}...\n"
          )
          previous.feed(chunk)
      }
      .map(_.finish)
  }

  /**
   * Produces an enumerator of chunks of bytes from the `chunks` collection
   * matching the given file metadata.
   *
   * @param file the file to be read
   */
  def enumerate[Id <: pack.Value](
      file: ReadFile[Id]
    )(implicit
      ec: ExecutionContext
    ): Enumerator[Array[Byte]] = {
    val cursor = gridfs.chunks(file, defaultReadPreference)

    @inline def pushChunk(
        chan: Concurrent.Channel[Array[Byte]],
        bytes: Array[Byte]
      ): Cursor.State[Unit] = Cursor.Cont(chan push bytes)

    Concurrent.unicast[Array[Byte]] { chan =>
      cursor
        .foldWhile({})((_, doc) => pushChunk(chan, doc), Cursor.FailOnError())
        .onComplete { case _ => chan.eofAndEnd() }
    }
  }

  // ---

  /** Concats two array - fast way */
  private def concat[T](
      a1: Array[T],
      a2: Array[T]
    )(implicit
      m: Manifest[T]
    ): Array[T] = {
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

object GridFS {

  private[iteratees] val logger =
    reactivemongo.util.LazyLogger("reactivemongo.play.iteratees.GridFS")

  def apply[P <: SerializationPack with Singleton](
      db: DB,
      prefix: String = "fs"
    )(implicit
      producer: GenericCollectionProducer[P, GenericCollection[P]] =
        BSONCollectionProducer
    ): GridFS[P] = apply(gridfs = db.gridfs[P](producer.pack, prefix))

  /** Returns an Iteratee support for given GridFS. */
  def apply[P <: SerializationPack with Singleton](
      gridfs: CoreFS[P]
    ): GridFS[P] = new GridFS[P](gridfs)

}
