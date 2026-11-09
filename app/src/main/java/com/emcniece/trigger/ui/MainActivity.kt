package com.emcniece.trigger.ui

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.emcniece.trigger.Graph

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { TriggerTheme { AppRoot() } }
    }

    override fun onResume() {
        super.onResume()
        // We're in the foreground, so this is a safe moment to (re)start the monitor service.
        Graph.syncBackgroundWork(this)
    }
}

@Composable
fun TriggerTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}

/** Routes: "list", "log", "edit:" (new) or "edit:<id>". */
@Composable
fun AppRoot() {
    var route by rememberSaveable { mutableStateOf("list") }
    BackHandler(enabled = route != "list") { route = "list" }
    when {
        route == "log" -> LogScreen(onBack = { route = "list" })
        route.startsWith("edit:") -> MacroEditScreen(
            macroId = route.removePrefix("edit:").ifEmpty { null },
            onDone = { route = "list" },
        )
        else -> MacroListScreen(
            onAdd = { route = "edit:" },
            onEdit = { route = "edit:$it" },
            onLog = { route = "log" },
        )
    }
}

/** Increments every time the host lifecycle resumes; use as a key to re-check permissions. */
@Composable
fun rememberResumeTick(): Int {
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return tick
}
