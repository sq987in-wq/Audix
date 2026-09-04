package app.candela.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The alignment coach (audit section 4).
 *
 * The audit is blunt that this is the product, not decoration: "Humans fix
 * angle/glare/shake in ~3 seconds when shown a number; they flail for minutes
 * when shown nothing." The commercial thing being sold is "session protocol +
 * alignment coach + thermal governor", not a decoder.
 *
 * Design consequences that follow from that, and from the audit's kill #5
 * (per-frame recomposition):
 *
 *  - Metrics arrive at up to 30 Hz. Every value here is passed as a primitive to
 *    a small composable so recomposition scope stays tight; nothing allocates a
 *    list or a data class per frame.
 *  - The corner guide is a Canvas, not a stack of Boxes — one draw pass.
 *  - The single most useful signal is the plain-language hint. A blur score of
 *    48.5 means nothing to a user; "hold still" does.
 */

private val Good = Color(0xFF3DDC84)
private val Warn = Color(0xFFFFB300)
private val Bad = Color(0xFFFF5252)
private val Ink = Color(0xFFE8EAF0)
private val Dim = Color(0xFF9AA3B2)

@Composable
fun CoachHud(
    blur: Double,
    blurMin: Double,
    contrast: Double,
    contrastMin: Double,
    motionStable: Boolean,
    gatePass: Boolean,
    hint: String,
    fps: Double,
    recovered: Int,
    total: Int,
    decodeMs: Double,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Color(0xE60F1115), RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        // The headline: one word the user can act on.
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(if (gatePass) Good else if (motionStable) Warn else Bad)
            Spacer(Modifier.width(10.dp))
            Text(
                text = hint,
                color = Ink,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Spacer(Modifier.height(14.dp))

        // Sharpness and contrast as ratios against their LEARNED thresholds, so
        // "1.0" always means "exactly good enough" regardless of the device or
        // the lighting the session calibrated in.
        MeterRow(
            label = "Sharpness",
            value = blur,
            threshold = blurMin,
            format = { "%.0f".format(it) },
        )
        Spacer(Modifier.height(8.dp))
        MeterRow(
            label = "Contrast",
            value = contrast,
            threshold = contrastMin,
            format = { "%.2f".format(it) },
        )
        Spacer(Modifier.height(8.dp))
        BinaryRow("Steadiness", motionStable, if (motionStable) "steady" else "moving")

        Spacer(Modifier.height(14.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Stat("Recovered", if (total > 0) "$recovered / $total" else "—")
            Stat("Rate", "%.0f/s".format(fps))
            Stat("Decode", if (decodeMs > 0) "%.0f ms".format(decodeMs) else "—")
        }

        if (total > 0) {
            Spacer(Modifier.height(12.dp))
            ProgressBar(recovered.toFloat() / total.coerceAtLeast(1))
        }
    }
}

@Composable
private fun StatusDot(color: Color) {
    Canvas(Modifier.width(12.dp).height(12.dp)) {
        drawCircle(color = color, radius = size.minDimension / 2f)
    }
}

/**
 * A meter normalised against its threshold. The bar fills to 100% at the
 * threshold and keeps growing (clamped) beyond it, so the user can see headroom
 * rather than a bar that pins the moment it is merely adequate.
 */
@Composable
private fun MeterRow(
    label: String,
    value: Double,
    threshold: Double,
    format: (Double) -> String,
) {
    val ratio = if (threshold <= 0.0) 1f else (value / threshold).toFloat()
    val fill by animateFloatAsState(
        targetValue = (ratio / 1.6f).coerceIn(0f, 1f),
        label = "meter",
    )
    val color = when {
        ratio >= 1.0f -> Good
        ratio >= 0.75f -> Warn
        else -> Bad
    }
    Column {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, color = Dim, fontSize = 13.sp)
            Text(
                "${format(value)}  /  ${format(threshold)}",
                color = color,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.height(4.dp))
        Canvas(Modifier.fillMaxWidth().height(6.dp)) {
            drawLine(
                color = Color(0xFF232733),
                start = Offset(0f, size.height / 2),
                end = Offset(size.width, size.height / 2),
                strokeWidth = size.height,
                cap = StrokeCap.Round,
            )
            if (fill > 0f) {
                drawLine(
                    color = color,
                    start = Offset(0f, size.height / 2),
                    end = Offset(size.width * fill, size.height / 2),
                    strokeWidth = size.height,
                    cap = StrokeCap.Round,
                )
            }
            // Threshold tick at the "just good enough" point.
            val tick = size.width / 1.6f
            drawLine(
                color = Ink.copy(alpha = 0.5f),
                start = Offset(tick, 0f),
                end = Offset(tick, size.height),
                strokeWidth = 1.5f,
            )
        }
    }
}

@Composable
private fun BinaryRow(label: String, ok: Boolean, text: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = Dim, fontSize = 13.sp)
        Text(
            text,
            color = if (ok) Good else Bad,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(label, color = Dim, fontSize = 11.sp)
        Text(
            value,
            color = Ink,
            fontSize = 15.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun ProgressBar(progress: Float) {
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), label = "progress")
    Canvas(Modifier.fillMaxWidth().height(8.dp)) {
        drawLine(
            color = Color(0xFF232733),
            start = Offset(0f, size.height / 2),
            end = Offset(size.width, size.height / 2),
            strokeWidth = size.height,
            cap = StrokeCap.Round,
        )
        if (p > 0f) {
            drawLine(
                color = Good,
                start = Offset(0f, size.height / 2),
                end = Offset(size.width * p, size.height / 2),
                strokeWidth = size.height,
                cap = StrokeCap.Round,
            )
        }
    }
}

/**
 * Corner guide tracking the QR rect (audit section 4).
 *
 * Drawn as four L-brackets rather than a full rectangle: a solid outline invites
 * the user to align the QR *inside* it, which is wrong — the brackets should sit
 * ON the QR's corners. Colour encodes the gate verdict so the frame itself is a
 * signal without reading any number.
 */
@Composable
fun CornerGuideOverlay(
    rectLeft: Float,
    rectTop: Float,
    rectWidth: Float,
    rectHeight: Float,
    locked: Boolean,
    modifier: Modifier = Modifier,
) {
    val color = if (locked) Good else Warn
    Box(modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val w = if (rectWidth > 0) rectWidth else size.width * 0.6f
            val h = if (rectHeight > 0) rectHeight else size.height * 0.6f
            val l = if (rectWidth > 0) rectLeft else (size.width - w) / 2f
            val t = if (rectHeight > 0) rectTop else (size.height - h) / 2f
            val arm = minOf(w, h) * 0.18f
            val stroke = Stroke(width = 4f, cap = StrokeCap.Round)

            fun corner(cx: Float, cy: Float, dx: Float, dy: Float) {
                drawLine(color, Offset(cx, cy), Offset(cx + dx * arm, cy), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(cx, cy), Offset(cx, cy + dy * arm), stroke.width, StrokeCap.Round)
            }
            corner(l, t, 1f, 1f)
            corner(l + w, t, -1f, 1f)
            corner(l, t + h, 1f, -1f)
            corner(l + w, t + h, -1f, -1f)

            if (!locked) {
                drawRect(
                    color = color.copy(alpha = 0.06f),
                    topLeft = Offset(l, t),
                    size = Size(w, h),
                )
            }
        }
    }
}
