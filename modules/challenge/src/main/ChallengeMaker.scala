package lila.challenge

import Challenge.TimeControl
import com.github.blemale.scaffeine.Cache
import scala.concurrent.duration.*

import lila.game.{ Game, GameRepo, Pov, Rematches }
import lila.memo.CacheApi
import lila.user.User

final class ChallengeMaker(
    userRepo: lila.user.UserRepo,
    gameRepo: GameRepo,
    rematches: Rematches
)(using ec: scala.concurrent.ExecutionContext):

  def makeRematchFor(gameId: GameId, dest: User): Fu[Option[Challenge]] =
    gameRepo game gameId flatMap {
      _ ?? { game =>
        game.opponentByUserId(dest.id).flatMap(_.userId) ?? userRepo.byId flatMap {
          _ ?? { challenger =>
            Pov(game, challenger) ?? { pov =>
              makeRematch(pov, challenger, dest) dmap some
            }
          }
        }
      }
    }

  private[challenge] def makeRematchOf(game: Game, challenger: User): Fu[Option[Challenge]] =
    Pov.ofUserId(game, challenger.id) ?? { pov =>
      pov.opponent.userId ?? userRepo.byId flatMap {
        _ ?? { dest =>
          makeRematch(pov, challenger, dest) dmap some
        }
      }
    }

  // pov of the challenger
  private def makeRematch(pov: Pov, challenger: User, dest: User): Fu[Challenge] = for {
    initialFen <- gameRepo initialFen pov.game
    nextGameId <- rematches.offer(pov.ref)
  } yield
    val timeControl = (pov.game.clock, pov.game.daysPerTurn) match
      case (Some(clock), _) => TimeControl.Clock(clock.config)
      case (_, Some(days))  => TimeControl.Correspondence(days)
      case _                => TimeControl.Unlimited
    Challenge.make(
      variant = pov.game.variant,
      initialFen = initialFen,
      timeControl = timeControl,
      mode = pov.game.mode,
      color = (!pov.color).name,
      challenger = Challenge.toRegistered(pov.game.variant, timeControl)(challenger),
      destUser = dest.some,
      rematchOf = pov.gameId.some,
      id = nextGameId.some
    )
