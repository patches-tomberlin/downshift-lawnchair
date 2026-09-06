package app.lawnchair.ui.icons

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import com.android.launcher3.R

/**
 * Downshift's hand-drawn glyph set for the hotseat controls pill, ported from the paused
 * custom-Compose app (`downshift-launcher`'s `MinimalIcons.kt`) rather than redrawn from scratch.
 */

/** A plain bell outline -- the Silencer's inactive state. */
@Composable
fun BellIcon(size: Dp, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(size)) {
        drawBellShape(this.size.width, this.size.height, color)
    }
}

/** [BellIcon] crossed by a diagonal slash -- the Silencer's active (muted) state. */
@Composable
fun BellSlashIcon(size: Dp, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        drawBellShape(w, h, color)
        drawLine(
            color = color,
            start = Offset(w * 0.16f, h * 0.86f),
            end = Offset(w * 0.84f, h * 0.14f),
            strokeWidth = w * 0.09f,
            cap = StrokeCap.Round,
        )
    }
}

private fun DrawScope.drawBellShape(w: Float, h: Float, color: Color) {
    val stroke = Stroke(width = w * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val domeCenter = Offset(w * 0.5f, h * 0.40f)
    val domeRadius = w * 0.24f
    val flareRadius = w * 0.32f
    val baseY = h * 0.68f

    // Dome: the rounded top half of the bell, mouth-down.
    drawArc(
        color = color,
        startAngle = 180f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(domeCenter.x - domeRadius, domeCenter.y - domeRadius),
        size = Size(domeRadius * 2f, domeRadius * 2f),
        style = stroke,
    )
    // Sides, flaring outward from the dome down to the wider base.
    drawLine(color = color, start = Offset(domeCenter.x - domeRadius, domeCenter.y), end = Offset(domeCenter.x - flareRadius, baseY), strokeWidth = stroke.width, cap = StrokeCap.Round)
    drawLine(color = color, start = Offset(domeCenter.x + domeRadius, domeCenter.y), end = Offset(domeCenter.x + flareRadius, baseY), strokeWidth = stroke.width, cap = StrokeCap.Round)
    // Base rim.
    drawLine(color = color, start = Offset(domeCenter.x - flareRadius, baseY), end = Offset(domeCenter.x + flareRadius, baseY), strokeWidth = stroke.width, cap = StrokeCap.Round)
    // Clapper, hanging just below the rim.
    drawArc(
        color = color,
        startAngle = 15f,
        sweepAngle = 150f,
        useCenter = false,
        topLeft = Offset(domeCenter.x - w * 0.09f, baseY - h * 0.02f),
        size = Size(w * 0.18f, h * 0.14f),
        style = stroke,
    )
    // Hanger knob at the very top.
    drawLine(
        color = color,
        start = Offset(domeCenter.x, domeCenter.y - domeRadius),
        end = Offset(domeCenter.x, domeCenter.y - domeRadius - h * 0.06f),
        strokeWidth = stroke.width,
        cap = StrokeCap.Round,
    )
}

/** A filled, solid person silhouette -- the Personal profile glyph. */
@Composable
fun FilledPersonIcon(size: Dp, color: Color, modifier: Modifier = Modifier) {
    Icon(
        painter = painterResource(id = R.drawable.ic_profile_person),
        contentDescription = null,
        tint = color,
        modifier = modifier.size(size),
    )
}

/** A filled office-building silhouette -- the Work profile glyph. */
@Composable
fun FilledBuildingIcon(size: Dp, color: Color, modifier: Modifier = Modifier) {
    Icon(
        painter = painterResource(id = R.drawable.ic_profile_work),
        contentDescription = null,
        tint = color,
        modifier = modifier.size(size),
    )
}
