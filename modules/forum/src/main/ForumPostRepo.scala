package lila.forum

import Filter.*
import org.joda.time.DateTime
import reactivemongo.akkastream.{ cursorProducer, AkkaStreamCursor }
import reactivemongo.api.ReadPreference

import lila.db.dsl.{ *, given }
import lila.user.User

final class ForumPostRepo(val coll: Coll, filter: Filter = Safe)(using
    scala.concurrent.ExecutionContext
):

  import ForumPost.Id

  def forUser(user: Option[User]) =
    withFilter(user.filter(_.marks.troll).fold[Filter](Safe) { u =>
      SafeAnd(u.id)
    })
  def withFilter(f: Filter) = if (f == filter) this else new ForumPostRepo(coll, f)
  def unsafe                = withFilter(Unsafe)

  import BSONHandlers.given

  private val noTroll = $doc("troll" -> false)
  private val trollFilter = filter match
    case Safe       => noTroll
    case SafeAnd(u) => $or(noTroll, $doc("userId" -> u))
    case Unsafe     => $empty

  def byIds(ids: Seq[Id]) = coll.byIds[ForumPost, ForumPost.Id](ids)

  def byCategAndId(categSlug: String, id: Id): Fu[Option[ForumPost]] =
    coll.one[ForumPost](selectCateg(categSlug) ++ $id(id))

  def countBeforeNumber(topicId: String, number: Int): Fu[Int] =
    coll.countSel(selectTopic(topicId) ++ $doc("number" -> $lt(number)))

  def isFirstPost(topicId: String, postId: Id): Fu[Boolean] =
    coll.primitiveOne[String](selectTopic(topicId), $sort.createdAsc, "_id") dmap { _ contains postId }

  def countByTopic(topic: ForumTopic): Fu[Int] =
    coll.countSel(selectTopic(topic.id))

  def lastByCateg(categ: ForumCateg): Fu[Option[ForumPost]] =
    coll.find(selectCateg(categ.id)).sort($sort.createdDesc).one[ForumPost]

  def lastByTopic(topic: ForumTopic): Fu[Option[ForumPost]] =
    coll.find(selectTopic(topic.id)).sort($sort.createdDesc).one[ForumPost]

  def recentInCategs(nb: Int)(categIds: List[String], langs: List[String]): Fu[List[ForumPost]] =
    coll
      .find(selectCategs(categIds) ++ selectLangs(langs) ++ selectNotErased)
      .sort($sort.createdDesc)
      .cursor[ForumPost]()
      .list(nb)

  def recentInCateg(categId: String, nb: Int): Fu[List[ForumPost]] =
    coll
      .find(selectCateg(categId) ++ selectNotErased)
      .sort($sort.createdDesc)
      .cursor[ForumPost]()
      .list(nb)

  def allByUserCursor(user: User): AkkaStreamCursor[ForumPost] =
    coll
      .find($doc("userId" -> user.id))
      .cursor[ForumPost](ReadPreference.secondaryPreferred)

  def countByCateg(categ: ForumCateg): Fu[Int] =
    coll.countSel(selectCateg(categ.id))

  def remove(post: ForumPost): Funit =
    coll.delete.one($id(post.id)).void

  def removeByTopic(topicId: String): Funit =
    coll.delete.one(selectTopic(topicId)).void

  def selectTopic(topicId: String) = $doc("topicId" -> topicId) ++ trollFilter

  def selectCateg(categId: String)         = $doc("categId" -> categId) ++ trollFilter
  def selectCategs(categIds: List[String]) = $doc("categId" $in categIds) ++ trollFilter

  val selectNotErased = $doc("erasedAt" $exists false)

  def selectLangs(langs: List[String]) =
    if (langs.isEmpty) $empty
    else $doc("lang" $in langs)

  def findDuplicate(post: ForumPost): Fu[Option[ForumPost]] =
    coll.one[ForumPost](
      $doc(
        "createdAt" $gt DateTime.now.minusHours(1),
        "userId" -> ~post.userId,
        "text"   -> post.text
      )
    )

  def sortQuery = $sort.createdAsc

  def idsByTopicId(topicId: String): Fu[List[Id]] =
    coll.distinctEasy[Id, List]("_id", $doc("topicId" -> topicId), ReadPreference.secondaryPreferred)

  def allUserIdsByTopicId(topicId: String): Fu[List[User.ID]] =
    coll.distinctEasy[User.ID, List](
      "userId",
      $doc("topicId" -> topicId) ++ selectNotErased,
      ReadPreference.secondaryPreferred
    )

  def nonGhostCursor =
    coll
      .find($doc("userId" $ne User.ghostId))
      .cursor[ForumPost](ReadPreference.secondaryPreferred)
