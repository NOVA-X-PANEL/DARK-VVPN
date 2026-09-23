package com.darkvvpn.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.darkvvpn.app.R
import com.darkvvpn.app.ui.theme.BrandViolet
import com.darkvvpn.app.ui.theme.Ink850
import com.darkvvpn.app.ui.theme.Ink900
import kotlinx.coroutines.delay

private const val SPLASH_MILLIS = 1500L

@Composable
fun SplashScreen(onFinished: () -> Unit) {
    var visible by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.72f,
        animationSpec = tween(durationMillis = 700),
        label = "splashScale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 700),
        label = "splashAlpha",
    )

    LaunchedEffect(Unit) {
        visible = true
        delay(SPLASH_MILLIS)
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(Ink900, Ink850, BrandViolet.copy(alpha = 0.22f))),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .scale(scale)
                .alpha(alpha),
        ) {
            // A plain bitmap drawable, NOT R.mipmap.ic_launcher. On API 26+ the
            // launcher icon resolves to an <adaptive-icon> XML, which Compose's
            // painterResource cannot rasterize — it throws
            // "Only VectorDrawables and rasterized asset types are supported"
            // and takes the app down on launch. Any drawable handed to Compose
            // must be a VectorDrawable or a real bitmap; see
            // ComposeResourceSafetyTest.
            Image(
                painter = painterResource(id = R.drawable.splash_logo),
                contentDescription = null,
                modifier = Modifier.size(140.dp),
            )
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                fontSize = 30.sp,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(top = 22.dp),
            )
            Text(
                text = "Secure • Private • Fast",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
