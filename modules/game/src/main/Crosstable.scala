package lila.game

import scala.util.Success

case class Crosstable(
    users: Crosstable.Users,
    results: List[Crosstable.Result] // chronological order, oldest to most recent
):

  def user1 = users.user1
  def user2 = users.user2
  def user  = users.user

  def nonEmpty = results.nonEmpty option this

  def nbGames                            = users.nbGames
  def showScore                          = users.showScore
  def showOpponentScore                  = users.showOpponentScore
  def fromPov(userId: lila.user.User.ID) = copy(users = users fromPov userId)

  lazy val size = results.size

  def fillSize = Crosstable.maxGames - size

object Crosstable:

  val maxGames = 20

  def empty(u1: lila.user.User.ID, u2: lila.user.User.ID) =
    Crosstable(
      Users(User(u1, 0), User(u2, 0)),
      Nil
    )

  case class User(id: lila.user.User.ID, score: Int) // score is x10
  case class Users(user1: User, user2: User):

    val nbGames = (user1.score + user2.score) / 10

    def user(id: lila.user.User.ID): Option[User] =
      if (id == user1.id) Some(user1)
      else if (id == user2.id) Some(user2)
      else None

    def toList = List(user1, user2)

    def showScore(userId: lila.user.User.ID) =
      val byTen = user(userId) ?? (_.score)
      s"${byTen / 10}${(byTen % 10 != 0).??("½")}" match
        case "0½" => "½"
        case x    => x

    def showOpponentScore(userId: lila.user.User.ID) =
      if (userId == user1.id) showScore(user2.id).some
      else if (userId == user2.id) showScore(user1.id).some
      else none

    def fromPov(userId: lila.user.User.ID) =
      if (userId == user2.id) copy(user1 = user2, user2 = user1)
      else this

    def winnerId =
      if (user1.score > user2.score) Some(user1.id)
      else if (user1.score < user2.score) Some(user2.id)
      else None

  case class Result(gameId: GameId, winnerId: Option[lila.user.User.ID])

  case class Matchup(users: Users): // score is x10
    def fromPov(userId: lila.user.User.ID) = copy(users = users fromPov userId)
    def nonEmpty                           = users.nbGames > 0

  case class WithMatchup(crosstable: Crosstable, matchup: Option[Matchup]):
    def fromPov(userId: lila.user.User.ID) =
      copy(
        crosstable fromPov userId,
        matchup map (_ fromPov userId)
      )

  private[game] def makeKey(u1: lila.user.User.ID, u2: lila.user.User.ID): String =
    if (u1 < u2) s"$u1/$u2" else s"$u2/$u1"

  import reactivemongo.api.bson.*
  import lila.db.BSON
  import lila.db.dsl.*

  object BSONFields:
    val id         = "_id"
    val score1     = "s1"
    val score2     = "s2"
    val results    = "r"
    val lastPlayed = "d"

  private[game] given crosstableHandler: BSON[Crosstable] with
    import BSONFields.*
    def reads(r: BSON.Reader): Crosstable =
      r str id split '/' match
        case Array(u1Id, u2Id) =>
          Crosstable(
            users = Users(User(u1Id, r intD score1), User(u2Id, r intD score2)),
            results = r.get[List[String]](results).map { r =>
              r drop 8 match {
                case ""  => Result(GameId(r), Some(u1Id))
                case "-" => Result(GameId(r take 8), Some(u2Id))
                case "=" => Result(GameId(r take 8), none)
                case _   => sys error s"Invalid result string $r"
              }
            }
          )
        case x => sys error s"Invalid crosstable id $x"
    def writeResult(result: Result, u1: String): String =
      val flag = result.winnerId match
        case Some(wid) if wid == u1 => ""
        case Some(_)                => "-"
        case None                   => "="
      s"${result.gameId}$flag"
    def writes(w: BSON.Writer, o: Crosstable) =
      BSONDocument(
        id      -> makeKey(o.user1.id, o.user2.id),
        score1  -> o.user1.score,
        score2  -> o.user2.score,
        results -> o.results.map { writeResult(_, o.user1.id) }
      )

  private[game] given BSONDocumentReader[Matchup] with
    import BSONFields.*
    def readDocument(doc: Bdoc) =
      val r = new BSON.Reader(doc)
      r str id split '/' match
        case Array(u1Id, u2Id) =>
          Success {
            Matchup(Users(User(u1Id, r intD score1), User(u2Id, r intD score2)))
          }
        case x => lila.db.BSON.handlerBadValue(s"Invalid crosstable id $x")
