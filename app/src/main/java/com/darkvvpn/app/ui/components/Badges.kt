package com.darkvvpn.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.darkvvpn.app.data.model.PingQuality
import com.darkvvpn.app.data.model.VpnProtocol

/** Rounded monogram standing in for a country flag (the skeleton ships no image set). */
@Composable
fun CountryAvatar(
    countryCode: String,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 42.dp,
) {
    val code = countryCode.take(2).uppercase().ifBlank { "??" }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = code,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Latency chip whose colour encodes the [PingQuality] bucket. */
@Composable
fun PingBadge(
    pingMs: Int?,
    modifier: Modifier = Modifier,
) {
    val quality = when (val p = pingMs) {
        null -> PingQuality.UNKNOWN
        in 0..80 -> PingQuality.EXCELLENT
        in 81..160 -> PingQuality.GOOD
        in 161..300 -> PingQuality.FAIR
        else -> PingQuality.POOR
    }
    val tint = quality.color()
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(tint.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(tint),
        )
        Text(
            text = pingMs?.let { "$it ms" } ?: "—",
            style = MaterialTheme.typography.labelMedium,
            color = tint,
        )
    }
}

/** Protocol label chip, e.g. `VLESS`, `WireGuard`. */
@Composable
fun ProtocolChip(
    protocol: VpnProtocol,
    modifier: Modifier = Modifier,
) {
    Text(
        text = protocol.label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

private fun PingQuality.color(): Color = when (this) {
    PingQuality.EXCELLENT -> Color(0xFF3DDCB4)
    PingQuality.GOOD -> Color(0xFF7CDA6A)
    PingQuality.FAIR -> Color(0xFFFFC24B)
    PingQuality.POOR -> Color(0xFFFF5A6E)
    PingQuality.UNKNOWN -> Color(0xFF8A8AA3)
}
