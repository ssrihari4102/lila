package lila.shutup

import reactivemongo.api.bson.*

import lila.db.dsl.*
import lila.game.{ Game, GameRepo }
import lila.hub.actorApi.shutup.PublicSource
import lila.user.{ User, UserRepo }

final class ShutupApi(
    coll: Coll,
    gameRepo: GameRepo,
    userRepo: UserRepo,
    relationApi: lila.relation.RelationApi,
    reporter: lila.hub.actors.Report
)(using ec: scala.concurrent.ExecutionContext):

  private given BSONDocumentHandler[UserRecord] = Macros.handler
  import PublicLine.given

  def getPublicLines(userId: User.ID): Fu[List[PublicLine]] =
    coll
      .find($doc("_id" -> userId), $doc("pub" -> 1).some)
      .one[Bdoc]
      .map {
        ~_.flatMap(_.getAsOpt[List[PublicLine]]("pub"))
      }

  def publicForumMessage(userId: User.ID, text: String) = record(userId, text, TextType.PublicForumMessage)
  def teamForumMessage(userId: User.ID, text: String)   = record(userId, text, TextType.TeamForumMessage)
  def publicChat(userId: User.ID, text: String, source: PublicSource) =
    record(userId, text, TextType.PublicChat, source.some)

  def privateChat(chatId: String, userId: User.ID, text: String) =
    gameRepo.getSourceAndUserIds(GameId(chatId)) flatMap {
      case (source, _) if source.has(lila.game.Source.Friend) => funit // ignore challenges
      case (_, userIds) =>
        record(userId, text, TextType.PrivateChat, none, userIds find (userId !=))
    }

  def privateMessage(userId: User.ID, toUserId: User.ID, text: String) =
    record(userId, text, TextType.PrivateMessage, none, toUserId.some)

  private def record(
      userId: User.ID,
      text: String,
      textType: TextType,
      source: Option[PublicSource] = None,
      toUserId: Option[User.ID] = None
  ): Funit =
    userRepo isTroll userId flatMap {
      case true => funit
      case false =>
        toUserId ?? { relationApi.fetchFollows(_, userId) } flatMap {
          case true => funit
          case false =>
            val analysed = Analyser(text)
            val pushPublicLine = source.ifTrue(analysed.badWords.nonEmpty) ?? { source =>
              $doc(
                "pub" -> $doc(
                  "$each"  -> List(PublicLine.make(text, source)),
                  "$slice" -> -20
                )
              )
            }
            val push = $doc(
              textType.key -> $doc(
                "$each"  -> List(BSONDouble(analysed.ratio)),
                "$slice" -> -textType.rotation
              )
            ) ++ pushPublicLine
            coll
              .findAndUpdateSimplified[UserRecord](
                selector = $id(userId),
                update = $push(push),
                fetchNewObject = true,
                upsert = true
              )
              .flatMap {
                case None             => fufail(s"can't find user record for $userId")
                case Some(userRecord) => legiferate(userRecord, analysed)
              }
        }
    }

  private def legiferate(userRecord: UserRecord, analysed: TextAnalysis): Funit =
    (analysed.critical || userRecord.reports.exists(_.unacceptable)) ?? {
      val text = (analysed.critical ?? "Critical comm alert\n") ++ reportText(userRecord)
      reporter ! lila.hub.actorApi.report.Shutup(userRecord.userId, text, analysed.critical)
      coll.update
        .one(
          $id(userRecord.userId),
          $unset(
            TextType.PublicForumMessage.key,
            TextType.TeamForumMessage.key,
            TextType.PrivateMessage.key,
            TextType.PrivateChat.key,
            TextType.PublicChat.key
          )
        )
        .void
    }

  private def reportText(userRecord: UserRecord) =
    userRecord.reports
      .collect {
        case r if r.unacceptable =>
          s"${r.textType.name}: ${r.nbBad} dubious (out of ${r.ratios.size})"
      }
      .mkString("\n")
