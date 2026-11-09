package com.emcniece.trigger.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.emcniece.trigger.model.Action
import com.emcniece.trigger.model.Macro
import java.io.IOException

class ActionException(message: String) : Exception(message)

object ActionRunner {

    /** Runs one action; returns a short success message or throws. */
    suspend fun run(
        context: Context,
        macro: Macro,
        action: Action,
        vars: Map<String, String>,
        allowRetry: Boolean,
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
        }
    }

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
            headers = Http.parseHeaders(t(action.headers)),
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
