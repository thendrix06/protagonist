package capture.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import capture.presentation.StatusGlyph
import capture.presentation.StatusStyle

/**
 * Status chip = glyph + text label + color. Never color alone, so statuses stay distinguishable for color-blind
 * operators and in monochrome screenshots.
 */
@Composable
fun StatusChip(
    style: StatusStyle,
    modifier: Modifier = Modifier,
    text: String = style.label,
    spokenPrefix: String? = null,
) {
    val content = style.contentArgb.asColor()
    Row(
        modifier =
            modifier
                .background(style.containerArgb.asColor(), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .clearAndSetSemantics { contentDescription = listOfNotNull(spokenPrefix, text).joinToString(" ") },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphIcon(style.glyph, content, 14.dp)
        Text(text, color = content, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun GlyphIcon(
    glyph: StatusGlyph,
    color: Color,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.size(size)) { drawGlyph(glyph, color) }
}

fun DrawScope.drawGlyph(
    glyph: StatusGlyph,
    color: Color,
) {
    val w = size.width
    val h = size.height
    val c = Offset(w / 2, h / 2)
    val r = minOf(w, h) / 2
    val stroke = Stroke(width = r * 0.3f, cap = StrokeCap.Round)
    when (glyph) {
        StatusGlyph.FILLED_CIRCLE -> {
            drawCircle(color, r, c)
        }

        StatusGlyph.HALF_CIRCLE -> {
            drawCircle(color, r * 0.85f, c, style = stroke)
            drawArc(color, 90f, 180f, useCenter = true, topLeft = Offset(c.x - r, c.y - r), size = Size(2 * r, 2 * r))
        }

        StatusGlyph.HOLLOW_RING -> {
            drawCircle(color, r * 0.8f, c, style = stroke)
        }

        StatusGlyph.QUESTION_DIAMOND -> {
            val p =
                Path().apply {
                    moveTo(c.x, 0f)
                    lineTo(w, c.y)
                    lineTo(c.x, h)
                    lineTo(0f, c.y)
                    close()
                }
            drawPath(p, color, style = stroke)
            drawCircle(color, r * 0.18f, c)
        }

        StatusGlyph.HOURGLASS -> {
            drawLine(color, Offset(0f, 0f), Offset(w, h), stroke.width)
            drawLine(color, Offset(w, 0f), Offset(0f, h), stroke.width)
            drawLine(color, Offset(0f, 0f), Offset(w, 0f), stroke.width)
            drawLine(color, Offset(0f, h), Offset(w, h), stroke.width)
        }

        StatusGlyph.SQUARE -> {
            drawRect(color, Offset(w * 0.1f, h * 0.1f), Size(w * 0.8f, h * 0.8f))
        }

        StatusGlyph.TRIANGLE_RIGHT -> {
            drawPath(
                Path().apply {
                    moveTo(0f, 0f)
                    lineTo(w, c.y)
                    lineTo(0f, h)
                    close()
                },
                color,
            )
        }

        StatusGlyph.CROSSHAIR -> {
            drawCircle(color, r * 0.7f, c, style = stroke)
            drawLine(color, Offset(c.x, 0f), Offset(c.x, h), stroke.width * 0.7f)
            drawLine(color, Offset(0f, c.y), Offset(w, c.y), stroke.width * 0.7f)
        }

        StatusGlyph.DOT_PULSE -> {
            drawCircle(color, r * 0.45f, c)
            drawCircle(color, r * 0.9f, c, style = Stroke(r * 0.15f))
        }

        StatusGlyph.CHECK -> {
            drawPath(
                Path().apply {
                    moveTo(w * 0.1f, h * 0.55f)
                    lineTo(w * 0.4f, h * 0.85f)
                    lineTo(w * 0.9f, h * 0.15f)
                },
                color,
                style = stroke,
            )
        }

        StatusGlyph.CROSS -> {
            drawLine(color, Offset(w * 0.15f, h * 0.15f), Offset(w * 0.85f, h * 0.85f), stroke.width, StrokeCap.Round)
            drawLine(color, Offset(w * 0.85f, h * 0.15f), Offset(w * 0.15f, h * 0.85f), stroke.width, StrokeCap.Round)
        }

        StatusGlyph.SLASH -> {
            drawCircle(color, r * 0.8f, c, style = stroke)
            drawLine(color, Offset(w * 0.2f, h * 0.8f), Offset(w * 0.8f, h * 0.2f), stroke.width)
        }

        StatusGlyph.EXCLAMATION -> {
            drawLine(color, Offset(c.x, h * 0.05f), Offset(c.x, h * 0.62f), stroke.width * 1.2f, StrokeCap.Round)
            drawCircle(color, r * 0.16f, Offset(c.x, h * 0.88f))
        }

        StatusGlyph.WARNING_TRIANGLE -> {
            drawPath(
                Path().apply {
                    moveTo(c.x, 0f)
                    lineTo(w, h)
                    lineTo(0f, h)
                    close()
                },
                color,
                style = stroke,
            )
            drawLine(color, Offset(c.x, h * 0.35f), Offset(c.x, h * 0.7f), stroke.width * 0.8f)
        }

        StatusGlyph.BROKEN_LINK -> {
            drawLine(color, Offset(0f, c.y), Offset(w * 0.38f, c.y), stroke.width, StrokeCap.Round)
            drawLine(color, Offset(w * 0.62f, c.y), Offset(w, c.y), stroke.width, StrokeCap.Round)
            drawLine(color, Offset(w * 0.45f, h * 0.15f), Offset(w * 0.55f, h * 0.85f), stroke.width * 0.6f)
        }
    }
}
