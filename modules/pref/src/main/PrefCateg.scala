package lila.pref

sealed abstract class PrefCateg(val slug: String)

object PrefCateg:

  case object Display      extends PrefCateg("display")
  case object ChessClock   extends PrefCateg("chess-clock")
  case object GameBehavior extends PrefCateg("game-behavior")
  case object Privacy      extends PrefCateg("privacy")

  val all: List[PrefCateg] = List(Display, ChessClock, GameBehavior, Privacy)

  def apply(slug: String) = all.find(_.slug == slug)
