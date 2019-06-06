package reactivemongo.akkastream

import java.util.Arrays

import scala.concurrent.{ ExecutionContext, Future }

import akka.util.ByteString

import akka.stream.Materializer
import akka.stream.scaladsl.{ Sink, Source }

import reactivemongo.util.{ LazyLogger, option }

import reactivemongo.core.errors.ReactiveMongoException
import reactivemongo.core.netty.ChannelBufferWritableBuffer

import reactivemongo.api.{
  BSONSerializationPack,
  ReadPreference,
  SerializationPack
}

import reactivemongo.api.collections.bson.{
  BSONCollection,
  BSONCollectionProducer
}

import reactivemongo.api.gridfs.{ FileToSave, GridFS => CoreFS, IdProducer }

import reactivemongo.bson.{
  BSONBinary,
  BSONDateTime,
  BSONDocument,
  BSONDocumentWriter,
  BSONInteger,
  BSONLong,
  BSONString,
  Subtype
}

/**
 * Akka-stream support for GridFS.
 *
 * @define fileParam the metadata of the file to store.
 * @define chunkSizeParam the size (in byte) of the chunks
 * @define IdTypeParam the type of the id of this file (generally `BSONObjectID` or `BSONValue`)
 */
final class GridFSStreams[P <: SerializationPack with Singleton](
    val gridfs: CoreFS[P]) {

  import GridFSStreams.logger
  import gridfs.{ chunks, defaultReadPreference, files, pack, ReadFile }
  import files.db

  /**
   * Returns an `Sink` that will consume data to put into a GridFS store.
   *
   * @param file $fileParam
   * @param chunkSize $chunkSizeParam (default: [[https://docs.mongodb.com/manual/core/gridfs/ 255kB]])
   */
  def sinkWithMD5[Id <: pack.Value](file: FileToSave[pack.type, Id], chunkSize: Int = 261120)(implicit readFileReader: pack.Reader[ReadFile[Id]], ec: ExecutionContext, idProducer: IdProducer[Id], docWriter: BSONDocumentWriter[file.pack.Document]): Sink[ByteString, Future[ReadFile[Id]]] = {
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
  def sink[Id <: pack.Value, M](file: FileToSave[pack.type, Id], digestInit: => M, digestUpdate: (M, Array[Byte]) => M, digestFinalize: M => Future[Option[Array[Byte]]], chunkSize: Int)(implicit readFileReader: pack.Reader[ReadFile[Id]], ec: ExecutionContext, idProducer: IdProducer[Id], docWriter: BSONDocumentWriter[file.pack.Document]): Sink[ByteString, Future[ReadFile[Id]]] = {
    def initial = new StoreState[Id, M](
      file, idProducer, Array.empty, 0, digestInit, digestUpdate, 0, chunkSize)

    Sink.foldAsync[StoreState[Id, M], Array[Byte]](initial) { (prev, chunk) =>
      logger.debug(s"Processing new enumerated chunk from n=${prev.n}...\n")

      prev.feed(chunk)
    }.contramap[ByteString](_.toArray[Byte]).
      mapMaterializedValue(_.flatMap(_.finish(readFileReader, digestFinalize)))
  }

  /**
   * Produces an enumerator of chunks of bytes from the `chunks` collection
   * matching the given file metadata.
   *
   * @param file the file to be read
   */
  def source[Id <: pack.Value](file: ReadFile[Id], readPreference: ReadPreference = defaultReadPreference)(implicit m: Materializer, idProducer: IdProducer[Id]): Source[ByteString, Future[State]] = {
    def selector = BSONDocument(idProducer("files_id" -> file.id)) ++ (
      "n" -> BSONDocument(
        f"$$gte" -> 0,
        f"$$lte" -> BSONLong(file.length / file.chunkSize + (
          if (file.length % file.chunkSize > 0) 1 else 0))))

    def cursor = asBSON(chunks.name).find(selector).
      sort(BSONDocument("n" -> 1)).cursor[BSONDocument](readPreference)

    reactivemongo.akkastream.cursorProducer[BSONDocument].
      produce(cursor).documentSource().flatMapConcat { doc =>
        doc.get("data") match {
          case Some(BSONBinary(data, _)) => {
            val array = Array.ofDim[Byte](data.readable)
            data.slice(data.readable).readBytes(array)

            Source.single(ByteString(array))
          }

          case _ => {
            val errmsg = s"not a chunk! failed assertion: data field is missing: ${BSONDocument pretty doc}"

            Source.failed[ByteString](ReactiveMongoException(errmsg))
          }
        }
      }
  }

  // ---

  /**
   * @param name the collection name
   */
  private def asBSON(name: String): BSONCollection = {
    implicit def producer = BSONCollectionProducer
    producer.apply(db, name, db.failoverStrategy)
  }

  /*
   * @param file $fileParam
   * @tparam Id $IdTypeParam
   */
  private final class StoreState[Id <: pack.Value, M](
      file: FileToSave[pack.type, Id],
      idProducer: IdProducer[Id],
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
          idProducer,
          if (left.isEmpty) Array.empty else left,
          n + normalizedChunkNumber,
          digestUpdate(md, chunk),
          digestUpdate,
          length + chunk.length,
          chunkSize)
      }
    }

    import reactivemongo.bson.utils.Converters

    def finish(
      readFileReader: pack.Reader[ReadFile[Id]],
      digestFinalize: M => Future[Option[Array[Byte]]])(
      implicit
      docWriter: BSONDocumentWriter[file.pack.Document],
      ec: ExecutionContext): Future[ReadFile[Id]] = {
      logger.debug(s"Writing last chunk #$n")

      val uploadDate = file.uploadDate.getOrElse(System.nanoTime() / 1000000)

      for {
        _ <- writeChunk(n, previous)
        md5 <- digestFinalize(md)

        bson: BSONDocument = BSONDocument(idProducer("_id" -> file.id)) ++ (
          "filename" -> file.filename.map(BSONString(_)),
          "chunkSize" -> BSONInteger(chunkSize),
          "length" -> BSONLong(length.toLong),
          "uploadDate" -> BSONDateTime(uploadDate),
          "contentType" -> file.contentType.map(BSONString(_)),
          "md5" -> md5.map(Converters.hex2Str),
          "metadata" -> option(!pack.isEmpty(file.metadata), file.metadata))

        res <- asBSON(files.name).insert.one(bson).map { _ =>
          val buf = ChannelBufferWritableBuffer()
          BSONSerializationPack.writeToBuffer(buf, bson)
          pack.readAndDeserialize(buf.toReadableBuffer, readFileReader)
        }
      } yield res
    }

    def writeChunk(n: Int, array: Array[Byte])(implicit ec: ExecutionContext) = {
      logger.debug(s"Writing chunk #$n")

      val bson = BSONDocument(
        idProducer("files_id" -> file.id),
        "n" -> BSONInteger(n),
        "data" -> BSONBinary(array, Subtype.GenericBinarySubtype))

      asBSON(chunks.name).insert.one(bson)
    }

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
    LazyLogger("reactivemongo.akkastream.GridFSStreams")

  /** Returns an Akka-stream support for given GridFS. */
  def apply[P <: SerializationPack with Singleton](
    gridfs: CoreFS[P]): GridFSStreams[P] = new GridFSStreams[P](gridfs)

}
