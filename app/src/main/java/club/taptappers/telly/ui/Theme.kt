@file:OptIn(ExperimentalTextApi::class)

package club.taptappers.telly.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import club.taptappers.telly.R

/** One family, Inter (variable), drawn tight at display sizes the way album-art typography is. */
private fun inter(w: Int) = Font(R.font.inter, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

val Inter = FontFamily(inter(400), inter(500), inter(600), inter(700), inter(800))

object T {
    val display = TextStyle(fontFamily = Inter, fontWeight = FontWeight.ExtraBold, fontSize = 40.sp, lineHeight = 44.sp, letterSpacing = (-1.3).sp)
    val title = TextStyle(fontFamily = Inter, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp, lineHeight = 32.sp, letterSpacing = (-0.7).sp)
    val headline = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 19.sp, lineHeight = 24.sp, letterSpacing = (-0.35).sp)
    val body = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = (-0.15).sp)
    val bodyStrong = body.copy(fontWeight = FontWeight.SemiBold)
    val reading = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 27.sp, letterSpacing = (-0.1).sp)
    val caption = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 19.sp, letterSpacing = (-0.05).sp)
    val small = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.1.sp)
    /** Durations and counts: tabular figures, so 9 -> 10 doesn't nudge the line. */
    val tabular = caption.copy(fontFeatureSettings = "tnum")
}

object C {
    val bg = Color(0xFF0A0A0B)
    val text = Color(0xFFF5F5F7)
    val text2 = Color(0xA6FFFFFF)
    val text3 = Color(0x6BFFFFFF)
    val card = Color(0x0FFFFFFF)
    val glass = Color(0x17FFFFFF)
    val hairline = Color(0x14FFFFFF)
    val border = Color(0x1FFFFFFF)
    val sheet = Color(0xFF161618)
    val problem = Color(0xFFFF8A80)
}

/** The light in the room by hour: dawn rose, day blue, dusk amber, night indigo. */
fun hourTint(hour: Int): Color = when (hour) {
    in 5..7 -> Color(0xFFC07A98)
    in 8..15 -> Color(0xFF4F93BD)
    in 16..18 -> Color(0xFFD9904A)
    else -> Color(0xFF6268D6)
}

@Composable
fun TellyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = C.bg, surface = C.sheet, onBackground = C.text, onSurface = C.text,
            primary = Color.White, onPrimary = Color.Black, surfaceVariant = C.sheet,
        ),
        content = content,
    )
}
