package com.darkvvpn.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.darkvvpn.app.data.model.VpnState
import com.darkvvpn.app.ui.theme.BrandRed
import com.darkvvpn.app.ui.theme.BrandTeal
import com.darkvvpn.app.ui.theme.BrandViolet
import com.darkvvpn.app.ui.theme.BrandVioletLight
import com.darkvvpn.app.ui.theme.Ink500
import com.darkvvpn.app.ui.theme.Ink700

/**
 * The screen's centrepiece: one large, state-aware button that starts and stops
 * the tunnel.
 *
 * Visual language:
 *  - grey ring + power icon  → disconnected
 *  - pulsing violet arc      → connecting / disconnecting
 *  - green ring + shield     → connected
 *  - red ring                → error
 */
@Composable
fun ConnectionOrb(
    state: VpnState,
    statusLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    diameter: androidx.compose.ui.unit.Dp = 248.dp,
    enabled: Boolean = true,
) {
    val accent by animateColorAsState(
        targetValue = when {
            state.isConnected -> BrandTeal
            state is VpnState.Error -> BrandRed
            state.isBusy -> BrandVioletLight
            else -> Ink500
        },
        label = "orbAccent",
    )

    val infinite = rememberInfiniteTransition(label = "orbPulse")
    val pulse by infinite.animateFloat(
        initialValue = 0.86f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "orbPulseValue",
    )
    val sweep by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(durationMillis = 1100)),
        label = "orbSweep",
    )

    val icon: ImageVector = when {
        state.isConnected -> Icons.Filled.Shield
        state.isBusy -> Icons.Filled.Lock
        else -> Icons.Filled.PowerSettingsNew
    }

    Box(
        modifier = modifier
            .size(diameter)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2f
            val center = Offset(size.width / 2f, size.height / 2f)

            // Soft outer glow, brighter while the tunnel is up.
            val glowAlpha = if (state.isConnected) 0.34f else 0.16f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(accent.copy(alpha = glowAlpha), Color.Transparent),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )

            // Track ring.
            drawCircle(
                color = Ink700,
                radius = radius * 0.74f,
                center = center,
                style = Stroke(width = 10f),
            )

            // Accent ring — pulses while busy and settles when idle.
            val ringRadius = radius * 0.74f * if (state.isBusy) pulse else 1f
            drawCircle(
                color = accent,
                radius = ringRadius,
                center = center,
                style = Stroke(width = 10f),
            )

            // Indeterminate sweep while connecting/disconnecting.
            if (state.isBusy) {
                drawArc(
                    color = BrandViolet,
                    startAngle = sweep,
                    sweepAngle = 70f,
                    useCenter = false,
                    topLeft = Offset(center.x - radius * 0.74f, center.y - radius * 0.74f),
                    size = Size(radius * 0.74f * 2f, radius * 0.74f * 2f),
                    style = Stroke(width = 10f, cap = StrokeCap.Round),
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (state.isConnected) BrandTeal else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(64.dp),
            )
            Text(
                text = statusLabel,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}
