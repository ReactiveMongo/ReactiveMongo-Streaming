package reactivemongo.play.iteratees

import java.util.Arrays

import scala.concurrent.{ ExecutionContext, Future }

import play.api.libs.iteratee.{ Concurrent, Enumerator, Iteratee }

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

import reactivemongo.util.{ LazyLogger, option }

import reactivemongo.core.netty.ChannelBufferWritableBuffer
import reactivemongo.core.errors.ReactiveMongoException

import reactivemongo.api.{
  BSONSerializationPack,
  Cursor,
  DB,
  DBMetaCommands,
  SerializationPack
}
import reactivemongo.api.collections.{
  GenericCollection,
  GenericCollectionProducer
}
import reactivemongo.api.collections.bson.{
  BSONCollection,
  BSONCollectionProducer
}

import reactivemongo.api.gridfs.{
  FileToSave,
  GridFS => BaseGridFS,
  IdProducer
}

// TODO: Same for Akka
final class GridFS[P <: SerializationPack with Singleton] private[iteratees] (db: DB with DBMetaCommands, prefix: String = "fs")(implicit producer: GenericCollectionProducer[P, GenericCollection[P]] = BSONCollectionProducer) extends BaseGridFS[P](db, prefix)(producer) {
  import GridFS.logger

  /**
   * Saves the content provided by the given enumerator with the given metadata.
   *
   * @param enumerator Producer of content.
   * @param file Metadata of the file to store.
   * @param chunkSize Size of the chunks. Defaults to 256kB.
   *
   * @return A future of a ReadFile[Id].
   */
  override def save[Id <: pack.Value](enumerator: Enumerator[Array[Byte]], file: FileToSave[pack.type, Id], chunkSize: Int = 262144)(implicit readFileReader: pack.Reader[ReadFile[Id]], ec: ExecutionContext, idProducer: IdProducer[Id], docWriter: BSONDocumentWriter[file.pack.Document]): Future[ReadFile[Id]] = (enumerator |>>> iteratee(file, chunkSize)).flatMap(f => f)

  override def iteratee[Id <: pack.Value](file: FileToSave[pack.type, Id], chunkSize: Int = 262144)(implicit readFileReader: pack.Reader[ReadFile[Id]], ec: ExecutionContext, idProducer: IdProducer[Id], docWriter: BSONDocumentWriter[file.pack.Document]): Iteratee[Array[Byte], Future[ReadFile[Id]]] = {
    import java.security.MessageDigest

    val digestUpdate = { (md: MessageDigest, chunk: Array[Byte]) =>
      md.update(chunk)
      md
    }
    val digestFinalize = { md: MessageDigest => Future(md.digest()) }

    case class Chunk(
        previous: Array[Byte],
        n: Int,
        md: MessageDigest,
        length: Int) {
      def feed(chunk: Array[Byte]): Future[Chunk] = {
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
          Chunk(
            if (left.isEmpty) Array.empty else left,
            n + normalizedChunkNumber,
            digestUpdate(md, chunk),
            length + chunk.length)
        }
      }

      import reactivemongo.bson.utils.Converters

      def finish(): Future[ReadFile[Id]] = {
        logger.debug(s"Writing last chunk #$n")

        val uploadDate = file.uploadDate.getOrElse(System.currentTimeMillis)

        for {
          _ <- writeChunk(n, previous)
          md5 <- digestFinalize(md)
          bson = BSONDocument(idProducer("_id" -> file.id)) ++ (
            "filename" -> file.filename.map(BSONString(_)),
            "chunkSize" -> BSONInteger(chunkSize),
            "length" -> BSONLong(length.toLong),
            "uploadDate" -> BSONDateTime(uploadDate),
            "contentType" -> file.contentType.map(BSONString(_)),
            "md5" -> Converters.hex2Str(md5),
            "metadata" -> option(!pack.isEmpty(file.metadata), file.metadata))

          res <- asBSON(files.name).insert.one(bson).map { _ =>
            val buf = ChannelBufferWritableBuffer()
            BSONSerializationPack.writeToBuffer(buf, bson)
            pack.readAndDeserialize(buf.toReadableBuffer, readFileReader)
          }
        } yield res
      }

      def writeChunk(n: Int, array: Array[Byte]) = {
        logger.debug(s"Writing chunk #$n")

        val bson = BSONDocument(
          "files_id" -> file.id,
          "n" -> BSONInteger(n),
          "data" -> BSONBinary(array, Subtype.GenericBinarySubtype))

        asBSON(chunks.name).insert.one(bson)
      }
    }

    def digestInit = MessageDigest.getInstance("MD5")

    Iteratee.foldM(Chunk(Array.empty, 0, digestInit, 0)) {
      (previous, chunk: Array[Byte]) =>
        logger.debug(s"Processing new enumerated chunk from n=${previous.n}...\n")
        previous.feed(chunk)
    }.map(_.finish)
  }

  /**
   * Produces an enumerator of chunks of bytes from the `chunks` collection
   * matching the given file metadata.
   *
   * @param file the file to be read
   */
  override def enumerate[Id <: pack.Value](file: ReadFile[Id])(implicit ec: ExecutionContext, idProducer: IdProducer[Id]): Enumerator[Array[Byte]] = {
    def selector = BSONDocument(idProducer("files_id" -> file.id)) ++ (
      "n" -> BSONDocument(
        f"$$gte" -> 0,
        f"$$lte" -> BSONLong(file.length / file.chunkSize + (
          if (file.length % file.chunkSize > 0) 1 else 0))))

    @inline def cursor = asBSON(chunks.name).find(selector).
      sort(BSONDocument("n" -> 1)).cursor[BSONDocument](defaultReadPreference)

    @inline def pushChunk(chan: Concurrent.Channel[Array[Byte]], doc: BSONDocument): Cursor.State[Unit] = doc.get("data") match {
      case Some(BSONBinary(data, _)) => {
        val array = new Array[Byte](data.readable)
        data.slice(data.readable).readBytes(array)
        Cursor.Cont(chan push array)
      }

      case _ => {
        val errmsg = s"not a chunk! failed assertion: data field is missing: ${BSONDocument pretty doc}"

        logger.error(errmsg)
        Cursor.Fail(ReactiveMongoException(errmsg))
      }
    }

    Concurrent.unicast[Array[Byte]] { chan =>
      cursor.foldWhile({})(
        (_, doc) => pushChunk(chan, doc),
        Cursor.FailOnError()).onComplete { case _ => chan.eofAndEnd() }
    }
  }

  // ---

  /**
   * @param name the collection name
   */
  @inline private def asBSON(name: String): BSONCollection = {
    implicit def producer = BSONCollectionProducer
    producer.apply(db, name, db.failoverStrategy)
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

object GridFS {
  private[iteratees] val logger =
    LazyLogger("reactivemongo.play.iteratees.GridFS")

  def apply[P <: SerializationPack with Singleton](db: DB with DBMetaCommands, prefix: String = "fs")(implicit producer: GenericCollectionProducer[P, GenericCollection[P]] = BSONCollectionProducer) = new GridFS(db, prefix)(producer)
}
