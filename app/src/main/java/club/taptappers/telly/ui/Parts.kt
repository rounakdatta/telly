package club.taptappers.telly.ui

import android.os.Build
import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import club.taptappers.telly.R
import club.taptappers.telly.data.Track
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlin.math.abs

/**
 * Blur and frosted glass need a GPU path that JVM screenshot tests don't have; they switch it off and get the same
 * layout with flat translucent fills. On a phone it is always on (and the libraries fall back by themselves below
 * Android 12).
 */
val LocalEffects = staticCompositionLocalOf { true }

/** Does the person want less motion? (Developer options "animation scale off" is how Android says so.) */
@Composable
fun reducedMotion(): Boolean {
    val ctx = LocalContext.current
    return remember { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

/**
 * The room's light: a glow in the hour's colour (or the artwork's, blurred, where the phone can blur), fading into
 * near-black. It breathes very slowly; with reduced motion it simply stays.
 */
@Composable
fun Ambient(tint: Color, art: String? = null) {
    val still = reducedMotion()
    val breath by if (still) remember { mutableFloatStateOf(0.36f) } else rememberInfiniteTransition(label = "ambient").animateFloat(
        initialValue = 0.30f, targetValue = 0.42f,
        animationSpec = infiniteRepeatable(tween(9000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath",
    )
    Box(Modifier.fillMaxSize().background(C.bg)) {
        if (art != null && Build.VERSION.SDK_INT >= 31 && LocalEffects.current) {
            AsyncImage(
                model = art, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().fillMaxHeight(0.7f).blur(80.dp).alpha(0.5f),
            )
        }
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.radialGradient(listOf(tint.copy(alpha = breath), Color.Transparent),
                center = Offset(size.width * 0.12f, -size.height * 0.04f), radius = size.width * 1.25f))
            drawRect(Brush.radialGradient(listOf(tint.copy(alpha = breath * 0.35f), Color.Transparent),
                center = Offset(size.width * 1.05f, size.height * 0.5f), radius = size.width * 0.95f))
            drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.5f to C.bg.copy(alpha = 0.55f), 1f to C.bg))
        }
    }
}

/** A deterministic, muted two-colour tile for a song without artwork yet (same song, same colours, every time). */
fun placeholderBrush(seed: String): Brush {
    val h = abs(seed.hashCode())
    val hue = (h % 360).toFloat()
    val a = Color.hsl(hue, 0.28f, 0.26f)
    val b = Color.hsl((hue + 40f) % 360f, 0.30f, 0.16f)
    return Brush.linearGradient(listOf(a, b))
}

@Composable
fun Art(url: String?, seed: String, size: Dp, corner: Dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(corner)
    Box(modifier.size(size).clip(shape).background(placeholderBrush(seed))) {
        if (url != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalPlatformContext.current).data(url).crossfade(220).build(),
                contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize(),
            )
        }
    }
}

/** Four covers in a square, or one when there aren't four. */
@Composable
fun Mosaic(tracks: List<Track>, size: Dp, corner: Dp, modifier: Modifier = Modifier) {
    val arts = tracks.filter { it.thumb != null }.distinctBy { it.thumb }.take(4)
    val shape = RoundedCornerShape(corner)
    if (arts.size < 4) {
        val t = arts.firstOrNull() ?: tracks.firstOrNull()
        Art(t?.thumb, t?.id ?: "telly", size, corner, modifier)
        return
    }
    Box(modifier.size(size).clip(shape)) {
        val half = size / 2
        arts.forEachIndexed { i, t ->
            Art(t.thumb, t.id, half, 0.dp, Modifier.padding(start = if (i % 2 == 1) half else 0.dp, top = if (i >= 2) half else 0.dp))
        }
    }
}

@Composable
fun GlassCircle(icon: Int, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    Box(
        modifier.size(size).clip(CircleShape).background(C.glass).border(1.dp, C.hairline, CircleShape)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(painterResource(icon), contentDescription = null, tint = C.text, modifier = Modifier.size(size * 0.5f)) }
}

/** The main action on a screen: white, black type. */
@Composable
fun PrimaryPill(text: String, icon: Int?, onClick: () -> Unit, modifier: Modifier = Modifier, busy: Boolean = false, enabled: Boolean = true) {
    Row(
        modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).background(if (enabled) Color.White else Color.White.copy(alpha = 0.4f))
            .clickable(enabled = enabled && !busy, role = Role.Button, onClick = onClick).padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), color = Color.Black, strokeWidth = 2.dp)
        } else if (icon != null) {
            Icon(painterResource(icon), contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(20.dp))
        }
        if (busy || icon != null) Spacer(Modifier.width(10.dp))
        Text(text, style = T.bodyStrong, color = Color.Black, maxLines = 1)
    }
}

@Composable
fun GlassPill(text: String, icon: Int?, onClick: () -> Unit, modifier: Modifier = Modifier, iconTint: Color = Color.Unspecified) {
    Row(
        modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).background(C.glass).border(1.dp, C.hairline, RoundedCornerShape(24.dp))
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(painterResource(icon), contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = T.bodyStrong, color = C.text, maxLines = 1)
    }
}

@Composable
fun Chip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: Int? = null, busy: Boolean = false, enabled: Boolean = true) {
    Row(
        modifier.height(38.dp).clip(RoundedCornerShape(19.dp)).background(C.glass).border(1.dp, C.hairline, RoundedCornerShape(19.dp))
            .clickable(enabled = enabled && !busy, role = Role.Button, onClick = onClick).padding(horizontal = 15.dp)
            .alpha(if (enabled || busy) 1f else 0.45f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(14.dp), color = C.text, strokeWidth = 1.6.dp)
            Spacer(Modifier.width(8.dp))
        } else if (icon != null) {
            Icon(painterResource(icon), contentDescription = null, tint = C.text, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(7.dp))
        }
        Text(text, style = T.caption, color = C.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun PlayCircle(url: String?, settling: Boolean, onPlay: (String) -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(44.dp).clip(CircleShape).background(if (url != null) Color.White else C.glass)
            .clickable(enabled = url != null, role = Role.Button, onClickLabel = "Play on YouTube Music") { url?.let(onPlay) }
            .semantics { contentDescription = if (url != null) "Play on YouTube Music" else "Saving to YouTube Music" },
        contentAlignment = Alignment.Center,
    ) {
        when {
            url != null -> Icon(painterResource(R.drawable.ic_play), null, tint = Color.Black, modifier = Modifier.size(20.dp).padding(start = 2.dp))
            settling -> CircularProgressIndicator(Modifier.size(18.dp), color = C.text2, strokeWidth = 2.dp)
        }
    }
}
