@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package club.taptappers.telly.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import club.taptappers.telly.R

/**
 * "What do you want to learn today?" A subject, a mood, a place or an era ("20th-century history", "something
 * romantic", "Indiranagar"). Every story that day leans on it.
 */
@Composable
fun LensSheet(current: String?, ideas: List<String>, onSet: (String) -> Unit, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by rememberSaveable { mutableStateOf(current.orEmpty()) }
    val set = { t: String -> onDismiss(); onSet(t) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = C.sheet, scrimColor = Color.Black.copy(alpha = 0.55f)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 22.dp).padding(bottom = 22.dp)) {
            Text("What do you want to learn today?", style = T.title, color = C.text)
            Spacer(Modifier.height(8.dp))
            Text("A subject, a mood, a place or an era. Every story today leans on it, and now and then one looks back at an older one.",
                style = T.caption, color = C.text2)
            Spacer(Modifier.height(18.dp))
            val shape = RoundedCornerShape(16.dp)
            BasicTextField(
                value = text, onValueChange = { if (it.length <= 80) text = it }, singleLine = true,
                textStyle = T.body.copy(color = C.text), cursorBrush = SolidColor(C.text),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) set(text) }),
                modifier = Modifier.fillMaxWidth().clip(shape).background(C.glass).border(1.dp, C.hairline, shape)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                decorationBox = { inner ->
                    if (text.isEmpty()) Text("Popular culture in the 2000s…", style = T.body, color = C.text3)
                    inner()
                },
            )
            Spacer(Modifier.height(14.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ideas.forEach { Chip(it, onClick = { set(it) }, icon = R.drawable.ic_spark) }
            }
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryPill("Learn this today", null, onClick = { set(text) }, enabled = text.isNotBlank())
                if (current != null) {
                    Spacer(Modifier.width(2.dp))
                    GlassPill("No subject", null, onClick = { set("") })
                }
            }
        }
    }
}
