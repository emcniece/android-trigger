package com.emcniece.trigger.engine

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.emcniece.trigger.Graph
import com.emcniece.trigger.model.Action
import com.emcniece.trigger.model.DndMode
import com.emcniece.trigger.model.VolumeStream
import com.emcniece.trigger.model.Macro
import java.io.IOException
import kotlin.math.roundToInt

class ActionException(message: String) : Exception(message)

object ActionRunner {

    /** Runs one action; returns a short success message or throws. */
    suspend fun run(
        context: Context,
        macro: Macro,
        action: Action,
        vars: Map<String, String>,
        eventVars: Map<String, String>,
        allowRetry: Boolean,
        depth: Int,
    ): String {
        fun t(s: String) = Templates.render(s, vars)
        return when (action) {
            is Action.HttpRequest -> http(context, macro, action, ::t, allowRetry)
            is Action.ShowNotification -> {
                Notifications.showMacroNotification(context, t(action.title), t(action.text))
                "shown"
            }
            is Action.SendSms -> sendSms(context, t(action.phoneNumber).trim(), t(action.message))
            is Action.Vibrate -> vibrate(context, action.durationMs)
            is Action.OpenApp -> openApp(context, action.packageName.trim())
            is Action.OpenUrl -> openUrl(context, t(action.url).trim())
            is Action.SetVolume -> setVolume(context, action)
            is Action.DoNotDisturb -> setDnd(context, action.mode)
            is Action.SendIntent -> sendIntent(context, action, ::t)
            is Action.RunMacro -> runOtherMacro(context, action, eventVars, allowRetry, depth)
        }
    }

    // ---- Opening apps and links ----

    /**
     * Android blocks starting activities from the background unless the app may draw over
     * other apps. Fail with a clear message instead of being silently ignored.
     */
    private fun checkCanStartActivity(context: Context) {
        if (isForeground(context) || Settings.canDrawOverlays(context)) return
        throw ActionException("Android blocks this in the background; allow \"Display over other apps\" for Trigger")
    }

    private fun isForeground(context: Context): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    private fun openApp(context: Context, packageName: String): String {
        if (packageName.isEmpty()) throw ActionException("no app chosen")
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: throw ActionException("$packageName isn't installed or can't be opened")
        checkCanStartActivity(context)
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "opened $packageName"
    }

    private fun openUrl(context: Context, url: String): String {
        if (url.isEmpty()) throw ActionException("no URL")
        val uri = Uri.parse(url)
        if (uri.scheme.isNullOrEmpty()) throw ActionException("\"$url\" needs a scheme such as https://")
        checkCanStartActivity(context)
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            throw ActionException("no app can open $url")
        }
        return "opened $url"
    }

    // ---- Sound ----

    private fun setVolume(context: Context, action: Action.SetVolume): String {
        val am = context.getSystemService(AudioManager::class.java)
        val stream = when (action.stream) {
            VolumeStream.MEDIA -> AudioManager.STREAM_MUSIC
            VolumeStream.RING -> AudioManager.STREAM_RING
            VolumeStream.NOTIFICATION -> AudioManager.STREAM_NOTIFICATION
            VolumeStream.ALARM -> AudioManager.STREAM_ALARM
        }
        val min = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) am.getStreamMinVolume(stream) else 0
        val max = am.getStreamMaxVolume(stream)
        val level = (min + (max - min) * action.percent.coerceIn(0, 100) / 100.0).roundToInt()
        val name = action.stream.label.lowercase()
        try {
            am.setStreamVolume(stream, level, 0)
            // Some vendor ROMs (e.g. ColorOS) silently ignore setStreamVolume from background apps.
            // Fall back to stepping, which they may still allow, then verify.
            if (am.getStreamVolume(stream) != level) stepVolume(am, stream, level)
        } catch (_: SecurityException) {
            throw ActionException("needs Do Not Disturb access to change $name volume")
        }
        val now = am.getStreamVolume(stream)
        if (now != level && kotlin.math.abs(now - level) > (max - min) / 15) {
            throw ActionException("the system ignored the change ($name is still $now/$max); some phones block volume changes from background apps")
        }
        return "$name set to $now/$max"
    }

    private fun stepVolume(am: AudioManager, stream: Int, target: Int) {
        repeat(30) {
            val current = am.getStreamVolume(stream)
            if (current == target) return
            am.adjustStreamVolume(stream, if (current < target) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
            val after = am.getStreamVolume(stream)
            if (after == current) return // not moving: blocked, or the step overshoots the target
            if ((current < target) != (after < target) && after != target) return // crossed the target
        }
    }

    private fun setDnd(context: Context, mode: DndMode): String {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) throw ActionException("Do Not Disturb access not granted")
        nm.setInterruptionFilter(
            when (mode) {
                DndMode.OFF -> NotificationManager.INTERRUPTION_FILTER_ALL
                DndMode.PRIORITY -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
                DndMode.ALARMS -> NotificationManager.INTERRUPTION_FILTER_ALARMS
                DndMode.TOTAL_SILENCE -> NotificationManager.INTERRUPTION_FILTER_NONE
            },
        )
        return mode.label.lowercase()
    }

    // ---- Intents and other macros ----

    private fun sendIntent(context: Context, action: Action.SendIntent, t: (String) -> String): String {
        val intentAction = t(action.intentAction).trim()
        if (intentAction.isEmpty()) throw ActionException("no intent action")
        val intent = Intent(intentAction)
        t(action.packageName).trim().takeIf { it.isNotEmpty() }?.let { intent.setPackage(it) }
        action.extras.forEach { (k, v) ->
            val key = t(k).trim()
            if (key.isNotEmpty()) intent.putExtra(key, t(v))
        }
        context.sendBroadcast(intent)
        return "sent $intentAction" + (intent.`package`?.let { " to $it" } ?: "")
    }

    private suspend fun runOtherMacro(
        context: Context,
        action: Action.RunMacro,
        eventVars: Map<String, String>,
        allowRetry: Boolean,
        depth: Int,
    ): String {
        if (depth >= MAX_DEPTH) throw ActionException("stopped: macros calling macros more than $MAX_DEPTH deep (loop?)")
        val target = Graph.macros.get(action.macroId) ?: throw ActionException("macro \"${action.macroName}\" no longer exists")
        if (action.checkConditions) {
            val blocking = target.blockingConstraints(AndroidDeviceState(context))
            if (blocking.isNotEmpty()) return "skipped \"${target.displayName}\", needs: ${blocking.joinToString { it.describe() }}"
        }
        MacroEngine.runMacro(context, target, eventVars, allowRetry, depth + 1)
        return "ran \"${target.displayName}\""
    }

    private const val MAX_DEPTH = 5

    private suspend fun http(
        context: Context,
        macro: Macro,
        action: Action.HttpRequest,
        t: (String) -> String,
        allowRetry: Boolean,
    ): String {
        val call = HttpCall(
            method = action.method,
            url = t(action.url).trim(),
            headers = action.headers
                .map { t(it.name).trim() to t(it.value).trim() }
                .filter { it.first.isNotEmpty() }
                .toMap(),
            body = t(action.body),
        )
        Http.validate(call)
        val code = try {
            Http.execute(call)
        } catch (e: IOException) {
            if (allowRetry) HttpRetryWorker.enqueue(context, call, macro.displayName)
            throw ActionException("${call.method} ${call.url}: ${e.message ?: e.javaClass.simpleName}" + retryNote(allowRetry))
        }
        if (code in 200..399) return "${call.method} ${call.url} → $code"
        if (allowRetry && Http.isRetryable(code)) HttpRetryWorker.enqueue(context, call, macro.displayName)
        throw ActionException("${call.method} ${call.url} → $code" + retryNote(allowRetry && Http.isRetryable(code)))
    }

    private fun retryNote(queued: Boolean) = if (queued) " (retry queued)" else ""

    private fun sendSms(context: Context, to: String, message: String): String {
        if (to.isEmpty()) throw ActionException("no phone number")
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            throw ActionException("SEND_SMS permission not granted")
        }
        val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        } ?: throw ActionException("SMS not available on this device")
        val parts = sms.divideMessage(message)
        sms.sendMultipartTextMessage(to, null, parts, null, null)
        return "sent to $to"
    }

    private fun vibrate(context: Context, ms: Long): String {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
        if (vibrator == null || !vibrator.hasVibrator()) throw ActionException("no vibrator")
        vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        return "${ms}ms"
    }
}
