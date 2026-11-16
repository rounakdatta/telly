package club.taptappers.telly.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import club.taptappers.telly.R
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild

/** Frosted glass over whatever scrolls beneath; a flat translucent fill where effects are off. */
@Composable
fun Modifier.glass(haze: HazeState): Modifier =
    if (LocalEffects.current) this.hazeChild(haze, style = glassStyle()) else this.background(Color(0xF01B1B1E))

/** Marks the content the glass blurs. */
@Composable
fun Modifier.glassSource(haze: HazeState): Modifier = if (LocalEffects.current) this.haze(haze) else this

fun glassStyle() = HazeStyle(
    backgroundColor = C.bg,
    tints = listOf(HazeTint(Color.White.copy(alpha = 0.07f))),
    blurRadius = 28.dp,
    noiseFactor = 0f,
    fallbackTint = HazeTint(Color(0xF01B1B1E)),
)

/**
 * The one way in: type (or speak) what the day holds, or how a playlist should change. Sits at the bottom, in
 * thumb's reach, frosted over whatever scrolls beneath it. With nothing typed the round button is a microphone;
 * with something typed it sends. What was sent leaves the box at once and shows in the exchange card above it
 * (`above`); if it fails, it comes back to the box.
 */
@Composable
fun Composer(
    text: String,
    onText: (String) -> Unit,
    placeholder: String,
    sending: Boolean,
    onSend: () -> Unit,
    onMic: (() -> Unit)?,
    haze: HazeState,
    modifier: Modifier = Modifier,
    above: @Composable () -> Unit = {},
) {
    val view = LocalView.current
    val send = {
        if (text.isNotBlank() && !sending) {
            // CONFIRM exists from Android 11; on 10 a key tap is the nearest feel.
            view.performHapticFeedback(if (android.os.Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.KEYBOARD_TAP)
            onSend()
        }
    }
    Column(modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
        above()
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(28.dp)).glass(haze)
                .border(1.dp, C.border, RoundedCornerShape(28.dp)).padding(start = 20.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = text,
                onValueChange = onText,
                textStyle = T.body.copy(color = C.text),
                cursorBrush = SolidColor(C.text),
                maxLines = 4,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                modifier = Modifier.weight(1f).padding(vertical = 10.dp).semantics { contentDescription = placeholder },
                decorationBox = { inner ->
                    Box {
                        if (text.isEmpty()) Text(placeholder, style = T.body, color = C.text3, maxLines = 1)
                        inner()
                    }
                },
            )
            Spacer(Modifier.width(8.dp))
            when {
                sending -> Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = C.text, strokeWidth = 2.dp)
                }
                text.isNotBlank() -> Box(
                    Modifier.size(44.dp).clip(CircleShape).background(Color.White)
                        .clickable(role = Role.Button, onClickLabel = "Send", onClick = send).semantics { contentDescription = "Send" },
                    contentAlignment = Alignment.Center,
                ) { Icon(painterResource(R.drawable.ic_send), null, tint = Color.Black, modifier = Modifier.size(22.dp)) }
                onMic != null -> Box(
                    Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = "Speak instead", onClick = onMic)
                        .semantics { contentDescription = "Speak instead" },
                    contentAlignment = Alignment.Center,
                ) { Icon(painterResource(R.drawable.ic_mic), null, tint = C.text2, modifier = Modifier.size(22.dp)) }
            }
        }
    }
}
