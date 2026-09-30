package com.darkvvpn.app.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.darkvvpn.app.R
import com.darkvvpn.app.viewmodel.UpdateBadge
import com.darkvvpn.app.viewmodel.UpdateUiState

/**
 * NOVA-X-PANEL styled yellow update badge button.
 *
 * Prominently displayed when an update is available, matching the yellow
 * update badge in the panel. Tapping it triggers download and installation
 * directly from within the badge.
 */
@Composable
fun YellowUpdateBadge(
    badge: UpdateBadge?,
    state: UpdateUiState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (badge == null && state !is UpdateUiState.Available) return

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "scale",
    )

    val yellowBase = Color(0xFFFFB800)
    val yellowBright = Color(0xFFFFD600)
    val darkInk = Color(0xFF1E1400)

    val isDownloading = state is UpdateUiState.Available && state.isDownloading
    val readyToInstall = state is UpdateUiState.Available && state.readyToInstall != null
    val downloadFraction = (state as? UpdateUiState.Available)?.downloadFraction
    val versionText = badge?.version ?: (state as? UpdateUiState.Available)?.release?.versionName ?: ""

    val labelText = when {
        readyToInstall -> stringResource(R.string.update_badge_install, versionText)
        isDownloading -> {
            val pct = downloadFraction?.let { (it * 100).toInt() }
            if (pct != null) {
                stringResource(R.string.update_badge_downloading_pct, pct)
            } else {
                stringResource(R.string.update_badge_downloading)
            }
        }
        else -> stringResource(R.string.update_badge_available, versionText)
    }

    Box(
        modifier = modifier
            .scale(if (isDownloading) 1.0f else pulseScale)
            .clip(RoundedCornerShape(50))
            .background(
                Brush.horizontalGradient(
                    listOf(yellowBase, yellowBright),
                ),
            )
            .border(
                width = 1.dp,
                color = Color(0xFFFFF3B0),
                shape = RoundedCornerShape(50),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 3.5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (isDownloading) {
                CircularProgressIndicator(
                    progress = { downloadFraction ?: 0f },
                    modifier = Modifier.size(13.dp),
                    color = darkInk,
                    strokeWidth = 2.dp,
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(darkInk.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (readyToInstall) Icons.Filled.Download else Icons.Filled.Bolt,
                        contentDescription = null,
                        tint = darkInk,
                        modifier = Modifier.size(11.dp),
                    )
                }
            }

            Spacer(Modifier.width(5.dp))

            Text(
                text = labelText,
                color = darkInk,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                letterSpacing = 0.2.sp,
            )
        }
    }
}
