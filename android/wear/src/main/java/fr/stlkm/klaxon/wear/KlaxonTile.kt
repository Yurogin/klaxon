package fr.stlkm.klaxon.wear

import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture

/** Tuile : le salon, un gros bouton « Tout le monde » (klaxon bref sans ouvrir l'appli), et combien vous êtes. */
class KlaxonTile : TileService() {
    override fun onTileRequest(request: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        Watch.load(this)
        val clicked = request.currentState.lastClickableId == "all"
        if (clicked) Watch.send(this, "/klaxon/quick", "*")
        val s = Watch.state.value
        val status = when {
            s == null || !s.running -> "Ouvre Klaxon sur le téléphone"
            clicked -> "📯 Klaxonné !"
            !s.online -> "Connexion…"
            s.peers.isEmpty() -> "Personne d'autre"
            else -> "${s.peers.size + 1} dans le salon"
        }
        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion("1")
            .setFreshnessIntervalMillis(0)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout(s?.room ?: "Klaxon", status, s?.peers?.isNotEmpty() == true)))
            .build()
        return CallbackToFutureAdapter.getFuture { it.set(tile); "tuile" }
    }

    override fun onTileResourcesRequest(request: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        CallbackToFutureAdapter.getFuture { it.set(ResourceBuilders.Resources.Builder().setVersion("1").build()); "ressources" }

    private fun text(t: String, size: Float, color: Int, bold: Boolean = false) = LayoutElementBuilders.Text.Builder()
        .setText(t)
        .setMaxLines(1)
        .setFontStyle(LayoutElementBuilders.FontStyle.Builder()
            .setSize(sp(size))
            .setColor(argb(color))
            .setWeight(if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL)
            .build())
        .build()

    private fun spacer(h: Float) = LayoutElementBuilders.Spacer.Builder().setHeight(dp(h)).build()

    private fun layout(room: String, status: String, canHonk: Boolean): LayoutElementBuilders.LayoutElement {
        val openApp = ModifiersBuilders.Clickable.Builder().setId("open").setOnClick(
            ActionBuilders.LaunchAction.Builder().setAndroidActivity(
                ActionBuilders.AndroidActivity.Builder().setPackageName(packageName).setClassName(MainActivity::class.java.name).build()
            ).build()
        ).build()
        val button = LayoutElementBuilders.Box.Builder()
            .setWidth(dp(150f))
            .setHeight(dp(64f))
            .setModifiers(ModifiersBuilders.Modifiers.Builder()
                .setBackground(ModifiersBuilders.Background.Builder()
                    .setColor(argb(if (canHonk) YELLOW else 0xFF4A4535.toInt()))
                    .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(32f)).build())
                    .build())
                .setClickable(ModifiersBuilders.Clickable.Builder().setId("all")
                    .setOnClick(ActionBuilders.LoadAction.Builder().build()).build())
                .build())
            .addContent(text("📯 TOUS", 20f, INK, bold = true))
            .build()
        val column = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(text(room, 15f, YELLOW, bold = true))
            .addContent(spacer(10f))
            .addContent(button)
            .addContent(spacer(10f))
            .addContent(text(status, 13f, MUTED))
            .build()
        return LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(openApp).build())
            .addContent(column)
            .build()
    }

    companion object {
        const val YELLOW = 0xFFFFC629.toInt()
        const val INK = 0xFF1A1A1A.toInt()
        const val MUTED = 0xFF8A8F9E.toInt()
    }
}
