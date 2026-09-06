package app.lawnchair.ui.preferences.components

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Hand-rolled Saturation/Value box + Hue slider + Alpha slider + hex/opacity text input,
 * matching the classic advanced-color-picker layout. No third-party dependency and no
 * eyedropper by design. HSV<->RGB conversion leans on the platform's own
 * android.graphics.Color.RGBToHSV/HSVToColor (well-tested, avoids hand-rolled color math);
 * everything else -- gradients, gestures, layout -- is plain Compose.
 *
 * Continuous drag updates only recompose this component's own preview (cheap); the external
 * [onColorChangeFinished] callback -- which the caller wires to a DataStore write plus a
 * widget redraw that also re-fetches weather -- fires only once per gesture (drag-end or a
 * tap), the same onValueChangeFinished convention Compose's own Slider uses. Firing that on
 * every drag pixel would spam the widget host and the weather API dozens of times a second.
 */
@Composable
fun AdvancedColorPicker(
    initialColorArgb: Int,
    onColorChangeFinished: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val seedHsv = remember(initialColorArgb) {
        val hsv = FloatArray(3)
        AndroidColor.RGBToHSV(
            AndroidColor.red(initialColorArgb),
            AndroidColor.green(initialColorArgb),
            AndroidColor.blue(initialColorArgb),
            hsv,
        )
        hsv
    }
    var hue by remember { mutableFloatStateOf(seedHsv[0]) }
    var saturation by remember { mutableFloatStateOf(seedHsv[1]) }
    var brightness by remember { mutableFloatStateOf(seedHsv[2]) }
    var alpha by remember { mutableFloatStateOf(AndroidColor.alpha(initialColorArgb) / 255f) }

    fun currentArgb(): Int {
        val opaque = Color.hsv(hue, saturation, brightness).toArgb()
        return (opaque and 0x00FFFFFF) or ((alpha * 255f).roundToInt().coerceIn(0, 255) shl 24)
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 3.dp,
        shadowElevation = 2.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            SaturationValueBox(
                hue = hue,
                saturation = saturation,
                value = brightness,
                onValueChange = { s, v -> saturation = s; brightness = v },
                onValueChangeFinished = { onColorChangeFinished(currentArgb()) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )

            Spacer(modifier = Modifier.height(16.dp))

            HueSlider(
                hue = hue,
                onValueChange = { hue = it },
                onValueChangeFinished = { onColorChangeFinished(currentArgb()) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp),
            )

            Spacer(modifier = Modifier.height(12.dp))

            AlphaSlider(
                hue = hue,
                saturation = saturation,
                brightness = brightness,
                alpha = alpha,
                onValueChange = { alpha = it },
                onValueChangeFinished = { onColorChangeFinished(currentArgb()) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp),
            )

            Spacer(modifier = Modifier.height(16.dp))

            HexAndOpacityRow(
                hue = hue,
                saturation = saturation,
                brightness = brightness,
                alpha = alpha,
                onHexCommit = { newHue, newSaturation, newBrightness ->
                    hue = newHue
                    saturation = newSaturation
                    brightness = newBrightness
                    onColorChangeFinished(currentArgb())
                },
                onOpacityCommit = { newAlpha ->
                    alpha = newAlpha
                    onColorChangeFinished(currentArgb())
                },
            )
        }
    }
}

private val ThumbSize = 20.dp
private val SliderThumbWidth = 6.dp

/** A pointerInput pair: tap fires onTap immediately; drag reports live position and fires onDragFinished once released. */
private fun Modifier.dragAndTap(
    onTap: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragFinished: () -> Unit,
): Modifier = this
    .pointerInput(Unit) { detectTapGestures(onTap = onTap) }
    .pointerInput(Unit) {
        detectDragGestures(
            onDragEnd = onDragFinished,
            onDrag = { change, _ -> change.consume(); onDrag(change.position) },
        )
    }

@Composable
private fun SaturationValueBox(
    hue: Float,
    saturation: Float,
    value: Float,
    onValueChange: (saturation: Float, value: Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val pureHueColor = Color.hsv(hue, 1f, 1f)
    val density = LocalDensity.current
    val thumbRadiusPx = with(density) { (ThumbSize / 2).toPx() }

    fun updateFromOffset(offset: Offset) {
        if (boxSize.width == 0 || boxSize.height == 0) return
        val newSaturation = (offset.x / boxSize.width).coerceIn(0f, 1f)
        val newValue = (1f - offset.y / boxSize.height).coerceIn(0f, 1f)
        onValueChange(newSaturation, newValue)
    }

    Box(
        modifier = modifier
            .onSizeChanged { boxSize = it }
            .background(Brush.horizontalGradient(listOf(Color.White, pureHueColor)))
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            .dragAndTap(
                onTap = { offset -> updateFromOffset(offset); onValueChangeFinished() },
                onDrag = { offset -> updateFromOffset(offset) },
                onDragFinished = onValueChangeFinished,
            ),
    ) {
        val thumbX = saturation * boxSize.width - thumbRadiusPx
        val thumbY = (1f - value) * boxSize.height - thumbRadiusPx
        ColorThumb(
            color = Color.hsv(hue, saturation, value),
            modifier = Modifier.offset { IntOffset(thumbX.roundToInt(), thumbY.roundToInt()) },
        )
    }
}

@Composable
private fun HueSlider(
    hue: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var width by remember { mutableStateOf(0) }
    val hueGradientColors = remember {
        listOf(0f, 60f, 120f, 180f, 240f, 300f, 359.999f).map { Color.hsv(it, 1f, 1f) }
    }
    val density = LocalDensity.current
    val thumbHalfWidthPx = with(density) { (SliderThumbWidth / 2).toPx() }

    fun updateFromX(x: Float) {
        if (width == 0) return
        onValueChange((x / width * 360f).coerceIn(0f, 359.999f))
    }

    Box(
        modifier = modifier
            .onSizeChanged { width = it.width }
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.horizontalGradient(hueGradientColors))
            .dragAndTap(
                onTap = { offset -> updateFromX(offset.x); onValueChangeFinished() },
                onDrag = { offset -> updateFromX(offset.x) },
                onDragFinished = onValueChangeFinished,
            ),
    ) {
        val thumbX = hue / 360f * width - thumbHalfWidthPx
        SliderThumbBar(modifier = Modifier.offset { IntOffset(thumbX.roundToInt(), 0) })
    }
}

@Composable
private fun AlphaSlider(
    hue: Float,
    saturation: Float,
    brightness: Float,
    alpha: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var width by remember { mutableStateOf(0) }
    val opaqueColor = Color.hsv(hue, saturation, brightness)
    val density = LocalDensity.current
    val thumbHalfWidthPx = with(density) { (SliderThumbWidth / 2).toPx() }

    fun updateFromX(x: Float) {
        if (width == 0) return
        onValueChange((x / width).coerceIn(0f, 1f))
    }

    Box(
        modifier = modifier
            .onSizeChanged { width = it.width }
            .clip(RoundedCornerShape(14.dp))
            .drawBehind { drawCheckerboard() }
            .background(Brush.horizontalGradient(listOf(opaqueColor.copy(alpha = 0f), opaqueColor)))
            .dragAndTap(
                onTap = { offset -> updateFromX(offset.x); onValueChangeFinished() },
                onDrag = { offset -> updateFromX(offset.x) },
                onDragFinished = onValueChangeFinished,
            ),
    ) {
        val thumbX = alpha * width - thumbHalfWidthPx
        SliderThumbBar(modifier = Modifier.offset { IntOffset(thumbX.roundToInt(), 0) })
    }
}

private fun DrawScope.drawCheckerboard(cellPx: Float = 18f) {
    val cols = (size.width / cellPx).toInt() + 1
    val rows = (size.height / cellPx).toInt() + 1
    for (row in 0 until rows) {
        for (col in 0 until cols) {
            val isLight = (row + col) % 2 == 0
            drawRect(
                color = if (isLight) Color(0xFFEEEEEE) else Color(0xFFBBBBBB),
                topLeft = Offset(col * cellPx, row * cellPx),
                size = Size(cellPx, cellPx),
            )
        }
    }
}

@Composable
private fun ColorThumb(color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(ThumbSize)
            .background(Color.White, CircleShape)
            .padding(2.dp)
            .background(color, CircleShape),
    )
}

@Composable
private fun SliderThumbBar(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(SliderThumbWidth)
            .background(Color.White, RoundedCornerShape(3.dp))
            .padding(1.dp)
            .background(Color.Black.copy(alpha = 0.25f), RoundedCornerShape(2.dp)),
    )
}

@Composable
private fun HexAndOpacityRow(
    hue: Float,
    saturation: Float,
    brightness: Float,
    alpha: Float,
    onHexCommit: (hue: Float, saturation: Float, brightness: Float) -> Unit,
    onOpacityCommit: (Float) -> Unit,
) {
    val currentHex = remember(hue, saturation, brightness) {
        "%06X".format(Color.hsv(hue, saturation, brightness).toArgb() and 0xFFFFFF)
    }
    var hexText by remember(currentHex) { mutableStateOf(currentHex) }
    val currentOpacityPercent = remember(alpha) { (alpha * 100f).roundToInt() }
    var opacityText by remember(currentOpacityPercent) { mutableStateOf(currentOpacityPercent.toString()) }

    fun commitHex() {
        val rgbInt = hexText.toIntOrNull(16) ?: return
        val hsvOut = FloatArray(3)
        AndroidColor.RGBToHSV((rgbInt shr 16) and 0xFF, (rgbInt shr 8) and 0xFF, rgbInt and 0xFF, hsvOut)
        onHexCommit(hsvOut[0], hsvOut[1], hsvOut[2])
    }

    fun commitOpacity() {
        val percent = opacityText.toIntOrNull()?.coerceIn(0, 100) ?: return
        onOpacityCommit(percent / 100f)
    }

    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "#",
            modifier = Modifier.padding(top = 16.dp, end = 4.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = hexText,
            onValueChange = { hexText = it.uppercase().filter { c -> c.isDigit() || c in 'A'..'F' }.take(6) },
            singleLine = true,
            label = { Text("Hex") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commitHex() }),
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
        )
        OutlinedTextField(
            value = opacityText,
            onValueChange = { opacityText = it.filter(Char::isDigit).take(3) },
            singleLine = true,
            label = { Text("Opacity") },
            trailingIcon = { Text("%") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commitOpacity() }),
            modifier = Modifier.width(110.dp),
        )
    }
}
