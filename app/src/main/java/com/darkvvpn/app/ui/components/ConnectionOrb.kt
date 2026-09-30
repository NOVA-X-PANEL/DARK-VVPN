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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.darkvvpn.app.R
import com.darkvvpn.app.data.model.VpnState
import com.darkvvpn.app.ui.theme.BrandCyan
import com.darkvvpn.app.ui.theme.BrandRed
import com.darkvvpn.app.ui.theme.Ink600
import com.darkvvpn.app.ui.theme.Ink700
import com.darkvvpn.app.ui.theme.Ink750
import com.darkvvpn.app.ui.theme.Ink850

/**
 * The clean neon power button center-piece: one elegant, state-aware button
 * that starts and stops the tunnel.
 */
@Composable
fun ConnectionOrb(
    state: VpnState,
    statusLabel: String = "",
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    diameter: androidx.compose.ui.unit.Dp = 180.dp,
    enabled: Boolean = true,
) {
    val accent by animateColorAsState(
        targetValue = when {
            state.isConnected -> BrandCyan
            state is VpnState.Error -> BrandRed
            state.isBusy -> BrandCyan
            else -> Ink600
        },
        label = "orbAccent",
    )

    val infinite = rememberInfiniteTransition(label = "orbPulse")
    val pulse by infinite.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.05f,
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

            val ringWidth = 3.dp.toPx()
            val ringRadius = radius * 0.76f

            // Soft outer radial glow when active
            val glowAlpha = if (state.isConnected) 0.22f else 0.06f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(accent.copy(alpha = glowAlpha), Color.Transparent),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )

            // Inner dark circular fill
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Ink750, Ink850),
                    center = center,
                    radius = ringRadius,
                ),
                radius = ringRadius,
                center = center,
            )

            // Track ring
            drawCircle(
                color = Ink700,
                radius = ringRadius,
                center = center,
                style = Stroke(width = ringWidth),
            )

            // Accent ring
            val pulseRadius = ringRadius * if (state.isBusy) pulse else 1f
            drawCircle(
                color = accent,
                radius = pulseRadius,
                center = center,
                style = Stroke(width = ringWidth),
            )

            // Indeterminate sweep while connecting/disconnecting
            if (state.isBusy) {
                drawArc(
                    color = BrandCyan,
                    startAngle = sweep,
                    sweepAngle = 70f,
                    useCenter = false,
                    topLeft = Offset(center.x - ringRadius, center.y - ringRadius),
                    size = Size(ringRadius * 2f, ringRadius * 2f),
                    style = Stroke(width = ringWidth * 1.2f, cap = StrokeCap.Round),
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.PowerSettingsNew,
                contentDescription = null,
                tint = if (state.isConnected) BrandCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(46.dp),
            )
            Spacer(Modifier.height(6.dp))
            val actionText = when {
                state.isConnected -> stringResource(R.string.home_disconnect)
                state.isBusy -> stringResource(R.string.home_status_connecting)
                else -> stringResource(R.string.home_connect)
            }
            Text(
                text = actionText,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = if (state.isConnected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
