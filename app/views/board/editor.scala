package views.html.board

import controllers.routes

import lila.api.{ Context, given }
import lila.app.templating.Environment.{ given, * }
import lila.app.ui.ScalatagsTemplate.{ *, given }
import lila.common.String.html.safeJsonValue

object editor:

  def apply(
      fen: Option[String],
      positionsJson: String,
      endgamePositionsJson: String
  )(implicit ctx: Context) =
    views.html.base.layout(
      title = trans.boardEditor.txt(),
      moreJs = frag(
        jsModule("editor"),
        embedJsUnsafeLoadThen(
          s"""const data=${safeJsonValue(bits.editorJsData(fen))};data.positions=$positionsJson;
data.endgamePositions=$endgamePositionsJson;LichessEditor(document.getElementById('board-editor'), data);"""
        )
      ),
      moreCss = cssTag("editor"),
      chessground = false,
      zoomable = true,
      openGraph = lila.app.ui
        .OpenGraph(
          title = "Chess board editor",
          url = s"$netBaseUrl${routes.Editor.index.url}",
          description = "Load opening positions or create your own chess position on a chess board editor"
        )
        .some
    )(
      main(id := "board-editor")(
        div(cls   := "board-editor")(
          div(cls := "spare"),
          div(cls := "main-board")(chessgroundBoard),
          div(cls := "spare")
        )
      )
    )
