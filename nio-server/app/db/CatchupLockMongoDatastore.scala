package db

import akka.http.scaladsl.util.FastFuture
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.json._
import play.modules.reactivemongo.ReactiveMongoApi
import reactivemongo.api.indexes.{Index, IndexType}
import reactivemongo.bson.{BSONDocument}
import reactivemongo.play.json.collection.JSONCollection
import play.modules.reactivemongo.json.ImplicitBSONHandlers._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

class CatchupLockMongoDatastore(val reactiveMongoApi: ReactiveMongoApi)(
    implicit val ec: ExecutionContext)
    extends DataStoreUtils {

  override def collectionName(tenant: String): String = "catchupLock"

  override def indices: Seq[Index] = Seq(
    Index(
      Seq("expireAt" -> IndexType.Ascending),
      name = Some("expire_at"),
      options = BSONDocument("expireAfterSeconds" -> 0)
    )
  )

  implicit def format: Format[CatchupLock] = CatchupLock.catchupLockFormats

  def init() = {
    Logger.debug("### init lock datastore ###")

    storedCollection.flatMap { col =>
      for {
        _ <- col.drop(failIfNotFound = false)
        _ <- col.create()
        _ <- Future.sequence(indices.map(i => col.indexesManager.ensure(i)))
      } yield ()
    }
  }

  def storedCollection: Future[JSONCollection] =
    reactiveMongoApi.database.map(_.collection("catchupLock"))

  def createLock(tenant: String) = {
    storedCollection
      .flatMap(
        _.find(Json.obj("tenant" -> tenant))
          .one[CatchupLock]
      )
      .flatMap {
        case None =>
          storedCollection.flatMap(
            _.insert(format.writes(CatchupLock(tenant)).as[JsObject]).map(_.ok))
        case Some(catchupItem) =>
          Logger.debug(s"Stored collection already locked for tenant $tenant")
          FastFuture.successful(false)
      }
  }

  def findLock(tenant: String) = {
    storedCollection
      .flatMap(
        _.find(Json.obj("tenant" -> tenant))
          .one[CatchupLock]
      )
  }
}

case class CatchupLock(tenant: String, expireAt: DateTime = DateTime.now)

object CatchupLock {
  implicit val catchupLockFormats: Format[CatchupLock] =
    new Format[CatchupLock] {
      override def reads(json: JsValue): JsResult[CatchupLock] =
        (Try {
          JsSuccess(
            CatchupLock(
              tenant = (json \ "tenant").as[String],
              expireAt = (json \ "expireAt").as(jodaDateFormat)
            ))
        } recover {
          case e => JsError(e.getMessage)
        }).get

      override def writes(c: CatchupLock): JsValue = Json.obj(
        "tenant" -> c.tenant,
        "expireAt" -> jodaDateFormat.writes(c.expireAt)
      )
    }

  implicit val jodaDateFormat = new Format[org.joda.time.DateTime] {
    override def reads(d: JsValue): JsResult[DateTime] = {
      JsSuccess(
        new DateTime(d.as[JsObject].\("$date").as[JsNumber].value.toLong))
    }

    override def writes(d: DateTime): JsValue = {
      JsObject(Seq("$date" -> JsNumber(d.getMillis)))
    }
  }
}
