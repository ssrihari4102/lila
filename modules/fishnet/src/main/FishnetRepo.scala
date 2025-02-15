package lila.fishnet

import org.joda.time.DateTime
import reactivemongo.api.bson.*
import scala.concurrent.duration.*

import lila.db.dsl.{ *, given }
import lila.memo.CacheApi.*

final private class FishnetRepo(
    analysisColl: Coll,
    clientColl: Coll,
    cacheApi: lila.memo.CacheApi
)(using ec: scala.concurrent.ExecutionContext):

  import BSONHandlers.given

  private val clientCache = cacheApi[Client.Key, Option[Client]](32, "fishnet.client") {
    _.expireAfterWrite(10 minutes)
      .buildAsyncFuture { key =>
        clientColl.one[Client]($id(key))
      }
  }

  def getClient(key: Client.Key)        = clientCache get key
  def getEnabledClient(key: Client.Key) = getClient(key).map { _.filter(_.enabled) }
  def getOfflineClient: Fu[Client] =
    getEnabledClient(Client.offline.key) getOrElse fuccess(Client.offline)
  def updateClientInstance(client: Client, instance: Client.Instance): Fu[Client] =
    client.updateInstance(instance).fold(fuccess(client)) { updated =>
      clientColl.update.one($id(client.key), $set("instance" -> updated.instance)) >>-
        clientCache.invalidate(client.key) inject updated
    }
  def addClient(client: Client)     = clientColl.insert.one(client)
  def deleteClient(key: Client.Key) = clientColl.delete.one($id(key)) >>- clientCache.invalidate(key)
  def enableClient(key: Client.Key, v: Boolean): Funit =
    clientColl.update.one($id(key), $set("enabled" -> v)).void >>- clientCache.invalidate(key)
  def allRecentClients =
    clientColl.list[Client](
      $doc(
        "instance.seenAt" $gt Client.Instance.recentSince
      )
    )

  def addAnalysis(ana: Work.Analysis)    = analysisColl.insert.one(ana).void
  def getAnalysis(id: Work.Id)           = analysisColl.byId[Work.Analysis](id)
  def updateAnalysis(ana: Work.Analysis) = analysisColl.update.one($id(ana.id), ana).void
  def deleteAnalysis(ana: Work.Analysis) = analysisColl.delete.one($id(ana.id)).void
  def updateOrGiveUpAnalysis(ana: Work.Analysis, update: Work.Analysis => Work.Analysis) =
    if (ana.isOutOfTries)
      logger.warn(s"Give up on analysis $ana")
      deleteAnalysis(ana)
    else updateAnalysis(update(ana))

  object status:
    private def system(v: Boolean)   = $doc("sender.system" -> v)
    private def acquired(v: Boolean) = $doc("acquired" $exists v)
    private def oldestSeconds(system: Boolean): Fu[Int] =
      analysisColl
        .find($doc("sender.system" -> system) ++ acquired(false), $doc("createdAt" -> true).some)
        .sort($sort asc "createdAt")
        .one[Bdoc]
        .map(~_.flatMap(_.getAsOpt[DateTime]("createdAt").map { date =>
          (nowSeconds - date.getSeconds).toInt atLeast 0
        }))

    def compute =
      for {
        all            <- analysisColl.countSel($empty)
        userAcquired   <- analysisColl.countSel(system(false) ++ acquired(true))
        userQueued     <- analysisColl.countSel(system(false) ++ acquired(false))
        userOldest     <- oldestSeconds(false)
        systemAcquired <- analysisColl.countSel(system(true) ++ acquired(true))
        systemQueued =
          all - userAcquired - userQueued - systemAcquired // because counting this is expensive (no useful index)
        systemOldest <- oldestSeconds(true)
      } yield Monitor.Status(
        user = Monitor.StatusFor(acquired = userAcquired, queued = userQueued, oldest = userOldest),
        system = Monitor.StatusFor(acquired = systemAcquired, queued = systemQueued, oldest = systemOldest)
      )

  def getSimilarAnalysis(work: Work.Analysis): Fu[Option[Work.Analysis]] =
    analysisColl.one[Work.Analysis]($doc("game.id" -> work.game.id))

  private[fishnet] def toKey(keyOrUser: String): Fu[Client.Key] =
    clientColl.primitiveOne[String](
      $or(
        "_id" $eq keyOrUser,
        "userId" $eq lila.user.User.normalize(keyOrUser)
      ),
      "_id"
    ) orFail "client not found" map { Client.Key(_) }
