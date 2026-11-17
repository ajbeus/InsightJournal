package com.adam.insightjournal.ui.splash

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.adam.insightjournal.R

/** Width / height of res/drawable-nodpi/splash_art.webp. */
private const val ART_ASPECT = 617f / 1115f
private val Gold = Color(0xFFE9B872)

/**
 * The full-screen loading artwork shown while the app starts. It always uses the
 * brand colors, whatever theme the user picked.
 *
 * The artwork is less tall than most phone screens. On a taller screen it sits at
 * the bottom at full width and the space above is filled with the color of the
 * artwork's top edge, with a short fade so no seam shows. On a wider screen
 * (tablet, landscape) it is cropped instead, keeping the bottom.
 */
@Composable
fun SplashScreen(modifier: Modifier = Modifier) {
    val sky = colorResource(R.color.splash_background)
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(sky)
            // Swallow taps so nothing underneath reacts while the splash is up.
            .pointerInput(Unit) {}
            .semantics { contentDescription = "Insight Journal is loading" },
    ) {
        val screenIsTaller = maxWidth / maxHeight < ART_ASPECT
        if (screenIsTaller) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().aspectRatio(ART_ASPECT)) {
                Image(
                    painter = painterResource(R.drawable.splash_art),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .background(Brush.verticalGradient(listOf(sky, Color.Transparent))),
                )
            }
        } else {
            Image(
                painter = painterResource(R.drawable.splash_art),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.BottomCenter,
                modifier = Modifier.fillMaxSize(),
            )
        }
        CircularProgressIndicator(
            color = Gold,
            trackColor = Gold.copy(alpha = 0.18f),
            strokeWidth = 3.dp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 40.dp)
                .size(34.dp),
        )
    }
}
