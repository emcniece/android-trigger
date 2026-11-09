package com.emcniece.trigger.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp

object AppLinks {
    const val AUTHOR = "Eric McNiece"
    const val AUTHOR_URL = "https://github.com/emcniece"
    const val REPO = "https://github.com/emcniece/android-trigger"
    const val FEATURE_REQUEST = "$REPO/issues/new?labels=enhancement"
    const val BUG_REPORT = "$REPO/issues/new?labels=bug"
    const val RELEASES = "$REPO/releases"
    const val LICENSE = "$REPO/blob/main/LICENSE"
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }
    AboutContent(version = version, onBack = onBack, onOpen = LocalUriHandler.current::openUri)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutContent(version: String, onBack: () -> Unit, onOpen: (String) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Trigger", style = MaterialTheme.typography.headlineMedium)
                Text("Version $version", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "Run custom actions when things happen on your phone — forward an SMS to a webhook, " +
                    "ping Home Assistant when the battery runs low, and more. Free for everyone, forever.",
                style = MaterialTheme.typography.bodyLarge,
            )

            Card(Modifier.fillMaxWidth()) {
                Link(Icons.Default.Person, "Author", AppLinks.AUTHOR) { onOpen(AppLinks.AUTHOR_URL) }
                HorizontalDivider()
                Link(Icons.Default.Build, "Source code", "github.com/emcniece/android-trigger") { onOpen(AppLinks.REPO) }
                HorizontalDivider()
                Link(Icons.Default.Info, "Releases", "Download the latest APK") { onOpen(AppLinks.RELEASES) }
            }

            Card(Modifier.fillMaxWidth()) {
                Link(Icons.Default.Star, "Request a feature", "Open a GitHub issue") { onOpen(AppLinks.FEATURE_REQUEST) }
                HorizontalDivider()
                Link(Icons.Default.Warning, "Report a bug", "Open a GitHub issue") { onOpen(AppLinks.BUG_REPORT) }
            }

            Text(
                "Released under the MIT License.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { onOpen(AppLinks.LICENSE) }.padding(4.dp),
            )
        }
    }
}

@Composable
private fun Link(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}
