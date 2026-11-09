package com.emcniece.trigger

import androidx.compose.runtime.Composable
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import com.emcniece.trigger.data.LogEntry
import com.emcniece.trigger.model.Action
import com.emcniece.trigger.model.HttpHeader
import com.emcniece.trigger.model.Macro
import com.emcniece.trigger.model.MatchMode
import com.emcniece.trigger.model.NetworkTransport
import com.emcniece.trigger.model.TextMatch
import com.emcniece.trigger.model.Trigger
import com.emcniece.trigger.ui.AboutContent
import com.emcniece.trigger.ui.LogContent
import com.emcniece.trigger.ui.MacroEditContent
import com.emcniece.trigger.ui.MacroListContent
import com.emcniece.trigger.ui.RequirementsCardContent
import com.emcniece.trigger.ui.TriggerTheme
import org.junit.Rule
import org.junit.Test

/**
 * Renders the screenshots used in the README from sample data.
 * Regenerate with `./gradlew updateScreenshots`, which writes PNGs to the docs/screenshots folder.
 */
class ReadmeScreenshots {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6,
        theme = "android:Theme.Material.Light.NoActionBar",
    )

    private fun shot(dark: Boolean = false, content: @Composable () -> Unit) =
        paparazzi.snapshot { TriggerTheme(dark = dark, dynamicColor = false, content = content) }

    @Test
    fun macroList() = shot { MacroList() }

    @Test
    fun macroListDark() {
        paparazzi.unsafeUpdateConfig(deviceConfig = DeviceConfig.PIXEL_6.copy(nightMode = NightMode.NIGHT))
        shot(dark = true) { MacroList() }
    }

    @Test
    fun macroEditor() {
        // Extra-tall canvas so the whole editor (trigger + actions) fits in one image.
        paparazzi.unsafeUpdateConfig(deviceConfig = DeviceConfig.PIXEL_6.copy(screenHeight = 5600))
        shot {
            MacroEditContent(
                initial = Samples.forwardSms,
                isNew = false,
                onBack = {},
                onSave = {},
                onDelete = {},
                onTest = { "" },
                requirements = {
                    RequirementsCardContent(listOf("Unrestricted battery use (reliable background runs)" to true)) {}
                },
            )
        }
    }

    @Test
    fun activityLog() = shot { LogContent(Samples.log, onBack = {}, onClear = {}) }

    @Test
    fun about() = shot { AboutContent(version = "0.1.0", onBack = {}, onOpen = {}) }

    @Composable
    private fun MacroList() = MacroListContent(
        macros = Samples.macros,
        missingPermissions = mapOf(Samples.whatsapp.id to listOf("Notification access")),
        onToggle = { _, _ -> },
        onAdd = {},
        onEdit = {},
        onLog = {},
        onAbout = {},
    )
}

private object Samples {
    val forwardSms = Macro(
        id = "sms",
        name = "Forward bank alerts",
        trigger = Trigger.SmsReceived(
            sender = TextMatch(MatchMode.EQUALS, "72265"),
            body = TextMatch(MatchMode.CONTAINS, "alert"),
        ),
        actions = listOf(
            Action.HttpRequest(
                method = "POST",
                url = "https://hooks.example.com/sms",
                headers = listOf(
                    HttpHeader("Content-Type", "application/json"),
                    HttpHeader("X-Webhook-Secret", "change-me"),
                ),
                body = "{\"from\": \"{sms_sender:json}\", \"text\": \"{sms_body:json}\"}",
            ),
            Action.ShowNotification(title = "Bank alert forwarded", text = "{sms_body}"),
        ),
    )

    val lowBattery = Macro(
        id = "battery",
        name = "Low battery ping",
        trigger = Trigger.BatteryLevel(threshold = 15, below = true),
        actions = listOf(Action.HttpRequest(url = "http://homeassistant.local:8123/api/webhook/phone-battery")),
    )

    val homeWifi = Macro(
        id = "wifi",
        name = "Home lights on",
        trigger = Trigger.NetworkChanged(connected = true, transport = NetworkTransport.WIFI),
        actions = listOf(Action.HttpRequest(method = "GET", url = "http://192.168.1.20/lights/porch/on")),
    )

    val whatsapp = Macro(
        id = "whatsapp",
        name = "Messages from Mom",
        trigger = Trigger.NotificationPosted("com.whatsapp", TextMatch(MatchMode.CONTAINS, "Mom")),
        actions = listOf(Action.Vibrate(1200), Action.ShowNotification()),
    )

    val morning = Macro(
        id = "morning",
        name = "Morning check-in",
        enabled = false,
        trigger = Trigger.DailyTime(7, 30),
        actions = listOf(Action.SendSms("+15555550123", "Up and about ☀️")),
    )

    val macros = listOf(forwardSms, lowBattery, homeWifi, whatsapp, morning)

    private const val T = 1_793_000_000_000L // fixed time so screenshots are stable
    val log = listOf(
        LogEntry(T, "Forward bank alerts", "HTTP request: POST https://hooks.example.com/sms → 200", true),
        LogEntry(T, "Forward bank alerts", "Show notification: shown", true),
        LogEntry(T - 95_000, "Home lights on", "HTTP retry #1 succeeded: GET http://192.168.1.20/lights/porch/on → 200", true),
        LogEntry(T - 140_000, "Home lights on", "HTTP request failed: GET http://192.168.1.20/lights/porch/on: connect timed out (retry queued)", false),
        LogEntry(T - 3_600_000, "Low battery ping", "HTTP request: POST http://homeassistant.local:8123/api/webhook/phone-battery → 200", true),
        LogEntry(T - 7_200_000, "Messages from Mom", "Vibrate: 1200ms", true),
        LogEntry(T - 7_200_000, "Messages from Mom", "Show notification: shown", true),
    )
}
