package reactivemongo.akkastream

import scala.concurrent.{ ExecutionContext, Future }

import reactivemongo.util.sameThreadExecutionContext

import reactivemongo.api.{ SerializationPack, WriteConcern }

import reactivemongo.api.commands.WriteResult

import reactivemongo.api.collections.GenericCollection

import akka.NotUsed
import akka.stream.scaladsl.Flow

/**
 * Flow builder to stream data to MongoDB.
 *
 * @tparam P the type of the serialization pack of the target collection
 * @define writeConcernParam an optional write concern (if `None` the default one is used)
 * @define parallelismParam the write parallelism
 * @define bypassDocumentValidationParam if true bypass the document validation
 * @define elementParam the function to prepare each update element from a `T` value
 */
sealed trait Flows[P <: SerializationPack, C <: GenericCollection[P]] {
  /** The target collection */
  val collection: C

  import collection.{ pack, MultiBulkWriteResult }

  /**
   * Prepares a flow to orderedly insert documents in the specified collection.
   *
   * @param parallelism $parallelismParam
   * @param writeConcern $writeConcernParam
   * @param bypassDocumentValidation $bypassDocumentValidationParam
   *
   * {{{
   * import akka.NotUsed
   * import akka.stream.Materializer
   * import akka.stream.scaladsl.{ Sink, Source }
   *
   * import reactivemongo.api.bson.BSONDocument
   * import reactivemongo.api.bson.collection.BSONCollection
   *
   * import reactivemongo.akkastream.Flows
   *
   * def insert(
   *   coll: BSONCollection,
   *   src: => Source[BSONDocument, NotUsed]
   * )(implicit m: Materializer) = {
   *   val flow = Flows(coll).insertOne[BSONDocument](parallelism = 1)
   *
   *   src.via(flow).runWith(Sink.fold(0) { (c, res) => c + res.n })
   * }
   * }}}
   */
  def insertOne[T](
    parallelism: Int,
    writeConcern: Option[WriteConcern] = None,
    bypassDocumentValidation: Boolean = false
  )(
    implicit
    w: pack.Writer[T]): Flow[T, WriteResult, NotUsed] = {

    val builder = insertOp(writeConcern, bypassDocumentValidation)

    Flow[T].named(
      s"${collection.name}.insertOne").mapAsync(parallelism) { doc =>
        implicit def ec: ExecutionContext = sameThreadExecutionContext

        builder.one(doc)
      }
  }

  /**
   * Prepares a flow to orderedly insert documents in the specified collection.
   *
   * @param parallelism $parallelismParam
   * @param writeConcern $writeConcernParam
   * @param bypassDocumentValidation $bypassDocumentValidationParam
   *
   * {{{
   * import akka.NotUsed
   * import akka.stream.Materializer
   * import akka.stream.scaladsl.{ Sink, Source }
   *
   * import reactivemongo.api.bson.BSONDocument
   * import reactivemongo.api.bson.collection.BSONCollection
   *
   * import reactivemongo.akkastream.Flows
   *
   * def insert(
   *   coll: BSONCollection,
   *   src: => Source[BSONDocument, NotUsed]
   * )(implicit m: Materializer) = {
   *   val flow = Flows(coll).insertOneUnordered[BSONDocument](parallelism = 10)
   *
   *   src.via(flow).runWith(Sink.fold(0) { (c, res) => c + res.n })
   * }
   * }}}
   */
  def insertOneUnordered[T](
    parallelism: Int,
    writeConcern: Option[WriteConcern] = None,
    bypassDocumentValidation: Boolean = false
  )(
    implicit
    w: pack.Writer[T]): Flow[T, WriteResult, NotUsed] = {

    val builder = insertOp(writeConcern, bypassDocumentValidation)

    Flow[T].named(s"${collection.name}.insertOneUnordered").
      mapAsyncUnordered(parallelism) { doc =>
        implicit def ec: ExecutionContext = sameThreadExecutionContext

        builder.one(doc)
      }
  }

  /**
   * Prepares a flow to orderedly insert batches in the specified collection.
   *
   * @param parallelism $parallelismParam
   * @param writeConcern $writeConcernParam
   * @param bypassDocumentValidation $bypassDocumentValidationParam
   *
   * {{{
   * import akka.NotUsed
   * import akka.stream.Materializer
   * import akka.stream.scaladsl.{ Flow, Sink, Source }
   *
   * import reactivemongo.api.bson.BSONDocument
   * import reactivemongo.api.bson.collection.BSONCollection
   *
   * import reactivemongo.akkastream.Flows
   *
   * def insert(
   *   coll: BSONCollection,
   *   src: => Source[BSONDocument, NotUsed]
   * )(implicit m: Materializer) = {
   *   val flow: Flow[
   *     Iterable[BSONDocument],
   *     coll.MultiBulkWriteResult,
   *     NotUsed
   *   ] = Flows(coll).insertMany[BSONDocument](parallelism = 1)
   *
   *   val batchSrc: Source[Iterable[BSONDocument], NotUsed] = src.grouped(128)
   *
   *   batchSrc.via(flow).runWith(Sink.fold(0) { (c, res) => c + res.n })
   * }
   * }}}
   */
  def insertMany[T](
    parallelism: Int,
    writeConcern: Option[WriteConcern] = None,
    bypassDocumentValidation: Boolean = false
  )(implicit w: pack.Writer[T]): Flow[Iterable[T], MultiBulkWriteResult, NotUsed] = {

    val builder = insertOp(writeConcern, bypassDocumentValidation)

    Flow[Iterable[T]].named(
      s"${collection.name}.insertMany").mapAsync(parallelism) { bulk =>
        implicit def ec: ExecutionContext = sameThreadExecutionContext

        builder.many(bulk)
      }
  }

  /**
   * Prepares a flow to unorderedly insert batches in the specified collection.
   *
   * @param parallelism $parallelismParam
   * @param writeConcern $writeConcernParam
   * @param bypassDocumentValidation $bypassDocumentValidationParam
   *
   * {{{
   * import akka.NotUsed
   * import akka.stream.Materializer
   * import akka.stream.scaladsl.{ Sink, Source }
   *
   * import reactivemongo.api.bson.BSONDocumentWriter
   * import reactivemongo.api.bson.collection.BSONCollection
   *
   * import reactivemongo.akkastream.Flows
   *
   * def insertUnordered[T](
   *   coll: BSONCollection,
   *   src: => Source[T, NotUsed]
   * )(implicit m: Materializer, w: BSONDocumentWriter[T]) = {
   *   val batchSrc: Source[Iterable[T], NotUsed] = src.grouped(128)
   *
   *   val builder = Flows(coll)
   *
   *   batchSrc.via(builder.insertManyUnordered[T](parallelism = 10)).
   *     runWith(Sink.fold(0) { (c, res) => c + res.n })
   * }
   * }}}
   */
  def insertManyUnordered[T](
    parallelism: Int,
    writeConcern: Option[WriteConcern] = None,
    bypassDocumentValidation: Boolean = false
  )(implicit w: pack.Writer[T]): Flow[Iterable[T], MultiBulkWriteResult, NotUsed] = {

    val builder = insertOp(writeConcern, bypassDocumentValidation)

    Flow[Iterable[T]].named(
      s"${collection.name}.insertManyUnordered").
      mapAsyncUnordered(parallelism) { bulk =>
        implicit def ec: ExecutionContext = sameThreadExecutionContext

        builder.many(bulk)
      }
  }

  /**
   * Prepares a flow to orderedly update batches in the specified collection.
   *
   * @param parallelism $parallelismParam
   * @param writeConcern $writeConcernParam
   * @param bypassDocumentValidation $bypassDocumentValidationParam
   * @param element $elementParam
   * @see [[reactivemongo.api.collections.GenericCollection.UpdateBuilder]]`.element`
   *
   * {{{
   * import akka.NotUsed
   * import akka.stream.scaladsl.Flow
   *
   * import reactivemongo.api.bson.BSONDocument
   * import reactivemongo.api.bson.collection.BSONCollection
   *
   * import reactivemongo.akkastream.Flows
   *
   * def myUpdateFlow(coll: BSONCollection): Flow[
   *   Iterable[(String, BSONDocument)],
   *   coll.MultiBulkWriteResult,
   *   NotUsed
   * ] = Flows(coll).updateMany[(String, BSONDocument)](parallelism = 2) {
   *   case (upBuilder, (idStr, doc)) => upBuilder.element(
   *     q = BSONDocument("_id" -> idStr), u = doc,
   *     multi = false, upsert = true)
   * }
   * }}}
   */
  def updateMany[T](
    parallelism: Int,
    writeConcern: Option[WriteConcern] = None,
    bypassDocumentValidation: Boolean = false
  )(element: (collection.UpdateBuilder, T) => Future[collection.UpdateElement]): Flow[Iterable[T], MultiBulkWriteResult, NotUsed] = {
    val builder = updateOp(writeConcern, bypassDocumentValidation)

    Flow[Iterable[T]].named(
      s"${collection.name}.updateMany").mapAsync(parallelism) { bulk =>
        implicit def ec: ExecutionContext = sameThreadExecutionContext

        Future.sequence(bulk.map { element(builder, _) }).flatMap {
          builder.many(_)
        }
      }
  }

  /**
   * Prepares a flow to orderedly update batches in the specified collection.
   *
   * @param parallelism $parallelismParam
   * @param writeConcern $writeConcernParam
   * @param bypassDocumentValidation $bypassDocumentValidationParam
   * @param element $elementParam
   * @see [[reactivemongo.api.collections.GenericCollection.UpdateBuilder]]`.element`
   *
   * {{{
   * import scala.concurrent.Future
   *
   * import akka.NotUsed
   * import akka.stream.Materializer
   * import akka.stream.scaladsl.{ Source, Sink }
   *
   * import reactivemongo.api.bson.BSONDocument
   * import reactivemongo.api.bson.collection.BSONCollection
   *
   * import reactivemongo.akkastream.Flows
   *
   * def myUpdate(
   *   coll: BSONCollection,
   *   src: Source[(String, BSONDocument), NotUsed]
   * )(implicit m: Materializer): Future[Int] = {
   *   import scala.language.existentials // required for 'flow' val bellow
   *
   *   val flow = Flows(coll).
   *     updateManyUnordered[(String, BSONDocument)](parallelism = 2) {
   *       case (upBuilder, (idStr, doc)) => upBuilder.element(
   *         q = BSONDocument("_id" -> idStr), u = doc,
   *         multi = false, upsert = true)
   *       }
   *
   *   src.grouped(10). // batching 10 per 10 updates
   *     via(flow).runWith(Sink.fold(0) { _ + _.n })
   * }
   * }}}
   */
  def updateManyUnordered[T](
    parallelism: Int,
    writeConcern: Option[WriteConcern] = None,
    bypassDocumentValidation: Boolean = false
  )(element: (collection.UpdateBuilder, T) => Future[collection.UpdateElement]): Flow[Iterable[T], MultiBulkWriteResult, NotUsed] = {
    val builder = updateOp(writeConcern, bypassDocumentValidation)

    Flow[Iterable[T]].named(
      s"${collection.name}.updateManyUnordered").
      mapAsyncUnordered(parallelism) { bulk =>
        implicit def ec: ExecutionContext = sameThreadExecutionContext

        Future.sequence(bulk.map { element(builder, _) }).flatMap {
          builder.many(_)
        }
      }
  }

  /**
   * Prepares a flow to orderedly update documents in the specified collection.
   *
   * @param parallelism $parallelismParam
   * @param writeConcern $writeConcernParam
   * @param bypassDocumentValidation $bypassDocumentValidationParam
   * @param element $elementParam
   * @see [[reactivemongo.api.collections.GenericCollection.UpdateBuilder]]`.element`
   *
   * {{{
   * import akka.NotUsed
   * import akka.stream.scaladsl.Flow
   *
   * import reactivemongo.api.bson.BSONDocument
   * import reactivemongo.api.bson.collection.BSONCollection
   *
   * import reactivemongo.akkastream.Flows
   *
   * def myUpdateFlow(coll: BSONCollection): Flow[
   *   (String, BSONDocument),
   *   coll.UpdateWriteResult,
   *   NotUsed
   * ] = Flows(coll).updateOne[(String, BSONDocument)](parallelism = 1) {
   *   case (upBuilder, (idStr, doc)) => upBuilder.element(
   *     q = BSONDocument("_id" -> idStr), u = doc,
   *     multi = false, upsert = true)
   * }
   * }}}
   */
  def updateOne[T](
    parallelism: Int,
    writeConcern: Option[WriteConcern] = None,
    bypassDocumentValidation: Boolean = false
  )(element: (collection.UpdateBuilder, T) => Future[collection.UpdateElement]): Flow[T, collection.UpdateWriteResult, NotUsed] = {
    val builder = updateOp(writeConcern, bypassDocumentValidation)
    implicit def iw = collection.pack.IdentityWriter

    Flow[T].named(s"${collection.name}.updateOne").mapAsync(parallelism) { v =>
      implicit def ec: ExecutionContext = sameThreadExecutionContext

      element(builder, v).flatMap { e =>
        builder.one(e.q, e.u, e.upsert, e.multi, e.collation, e.arrayFilters)
      }
    }
  }

  /**
   * Prepares a flow to orderedly update batches in the specified collection.
   *
   * @param parallelism $parallelismParam
   * @param writeConcern $writeConcernParam
   * @param bypassDocumentValidation $bypassDocumentValidationParam
   * @param element $elementParam
   * @see [[reactivemongo.api.collections.GenericCollection.UpdateBuilder]]`.element`
   *
   * {{{
   * import scala.concurrent.Future
   *
   * import akka.NotUsed
   * import akka.stream.Materializer
   * import akka.stream.scaladsl.{ Source, Sink }
   *
   * import reactivemongo.api.bson.BSONDocument
   * import reactivemongo.api.bson.collection.BSONCollection
   *
   * import reactivemongo.akkastream.Flows
   *
   * def myUpdate(
   *   coll: BSONCollection,
   *   src: Source[(String, BSONDocument), NotUsed]
   * )(implicit m: Materializer): Future[Int] = {
   *   import scala.language.existentials // required for 'flow' val bellow
   *
   *   val flow = Flows(coll).
   *     updateOneUnordered[(String, BSONDocument)](parallelism = 10) {
   *       case (upBuilder, (idStr, doc)) => upBuilder.element(
   *         q = BSONDocument("_id" -> idStr), u = doc,
   *         multi = false, upsert = true)
   *       }
   *
   *   src.via(flow).runWith(Sink.fold(0) { _ + _.n })
   * }
   * }}}
   */
  def updateOneUnordered[T](
    parallelism: Int,
    writeConcern: Option[WriteConcern] = None,
    bypassDocumentValidation: Boolean = false
  )(element: (collection.UpdateBuilder, T) => Future[collection.UpdateElement]): Flow[T, collection.UpdateWriteResult, NotUsed] = {
    val builder = updateOp(writeConcern, bypassDocumentValidation)
    implicit def iw = collection.pack.IdentityWriter

    Flow[T].named(s"${collection.name}.updateOneUnordered").
      mapAsyncUnordered(parallelism) { v =>
        implicit def ec: ExecutionContext = sameThreadExecutionContext

        element(builder, v).flatMap { e =>
          builder.one(e.q, e.u, e.upsert, e.multi, e.collation, e.arrayFilters)
        }
      }
  }

  // ---

  @inline private def insertOp(
    writeConcern: Option[WriteConcern],
    bypassDocumentValidation: Boolean
  ) = writeConcern match {
    case Some(wc) =>
      collection.insert(ordered = true, wc, bypassDocumentValidation)

    case _ =>
      collection.insert(ordered = true, bypassDocumentValidation)
  }

  @inline private def updateOp(
    writeConcern: Option[WriteConcern],
    bypassDocumentValidation: Boolean
  ) = writeConcern match {
    case Some(wc) =>
      collection.update(ordered = true, wc, bypassDocumentValidation)

    case _ =>
      collection.update(ordered = true, bypassDocumentValidation)
  }
}

/** Flow builder utility */
object Flows {
  /**
   * Resolves a flow builder for the specified collection.
   *
   * {{{
   * import reactivemongo.api.bson.collection.BSONCollection
   * import reactivemongo.akkastream.Flows
   *
   * def flowFor(c: BSONCollection) = Flows(c)
   * }}}
   */
  def apply[P <: SerializationPack](
    collection: GenericCollection[P]): Flows[P, collection.type] = {
    val coll: collection.type = collection
    new Flows[P, collection.type] {
      val collection: coll.type = coll
    }
  }
}
