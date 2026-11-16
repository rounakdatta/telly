package club.taptappers.telly

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import club.taptappers.telly.ui.App
import club.taptappers.telly.ui.TellyModel
import club.taptappers.telly.ui.TellyTheme

class MainActivity : ComponentActivity() {
    private val model: TellyModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Always dark, so the system bars get light icons whatever the phone's own theme is.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = model.setForeground(true)
            override fun onStop(owner: LifecycleOwner) = model.setForeground(false)
        })
        setContent { TellyTheme { App(model) } }
    }
}
