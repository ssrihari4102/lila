package lila.round

import scala.concurrent.duration.FiniteDuration

import lila.game.{ Game, Pov }
import lila.user.User
import play.api.libs.json.JsObject

private case class AnimationDuration(value: FiniteDuration) extends AnyVal

final class OnStart(f: GameId => Unit) extends (GameId => Unit):
  def apply(g: GameId) = f(g)

final class TellRound(f: (GameId, Any) => Unit) extends ((GameId, Any) => Unit):
  def apply(g: GameId, msg: Any) = f(g, msg)

final class IsSimulHost(f: User.ID => Fu[Boolean]) extends (User.ID => Fu[Boolean]):
  def apply(u: User.ID) = f(u)

final private class ScheduleExpiration(f: Game => Unit) extends (Game => Unit):
  def apply(g: Game) = f(g)

final class IsOfferingRematch(f: Pov => Boolean) extends (Pov => Boolean):
  def apply(p: Pov) = f(p)

case class ChangeFeatured(pov: Pov, mgs: JsObject)
