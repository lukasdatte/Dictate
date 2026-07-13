package net.devemperor.dictate.companion.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter

/**
 * The tray/window icon, **drawn in code**.
 *
 * A painted icon rather than a resource file: the tray needs a `Painter`, a resource would have to be
 * bundled and looked up at three different scales, and this is a microphone dot in a rounded square.
 * jpackage's own installer icons are a separate matter (see the release runbook).
 */
object CompanionIcon : Painter() {

    override val intrinsicSize: Size = Size(32f, 32f)

    override fun DrawScope.onDraw() {
        val body = size.minDimension
        drawRoundRect(
            color = Color(0xFF4F5DFF),
            size = Size(body, body),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(body * 0.25f),
        )
        // The microphone: a capsule with a stand.
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(body * 0.40f, body * 0.22f),
            size = Size(body * 0.20f, body * 0.34f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(body * 0.10f),
        )
        drawRect(
            color = Color.White,
            topLeft = Offset(body * 0.47f, body * 0.60f),
            size = Size(body * 0.06f, body * 0.16f),
        )
    }
}
