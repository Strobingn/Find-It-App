package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The floating "glass" treatment every piece of Map-workspace chrome sits on: a translucent
 * [MaterialTheme.colorScheme.surface] panel outlined with a white hairline.
 *
 * The design reference specifies a backdrop blur behind these panels. Compose has no portable
 * backdrop-blur (RenderEffect is API 31+ and cannot sample siblings behind a Surface), so the
 * panel leans on a higher fill alpha instead — the terrain still reads through, and the hairline
 * border keeps the panel edge legible over both bright and dark ground.
 */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(14.dp),
    content: @Composable () -> Unit,
) {
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
        modifier = modifier,
        content = content,
    )
}

/** A 44dp square action in the Map workspace's floating right rail. */
@Composable
fun RailIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
) {
    val background = if (active) MaterialTheme.colorScheme.primary else Color.Transparent
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        active -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = background,
        shape = RoundedCornerShape(9.dp),
        modifier = modifier
            .size(44.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription, tint = tint)
        }
    }
}

/**
 * A small pill of status text — the sun reading and detail/refine line that float over the top-left
 * of the map. [mono] switches to the monospace face the design uses for anything numeric.
 */
@Composable
fun StatusPill(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    GlassPanel(shape = RoundedCornerShape(8.dp), modifier = modifier, content = content)
}

/** Text styled for the inside of a [StatusPill]. */
@Composable
fun PillText(
    text: String,
    mono: Boolean = false,
    emphasis: Boolean = true,
    maxLines: Int = 1,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontFamily = if (mono) FontFamily.Monospace else null,
        fontWeight = if (mono) FontWeight.Medium else FontWeight.Normal,
        color = if (emphasis) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}
