package com.emcniece.trigger.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.emcniece.trigger.model.Action
import com.emcniece.trigger.model.Macro
import com.emcniece.trigger.model.Trigger
import com.emcniece.trigger.model.TriggerType
import com.emcniece.trigger.model.VolumeStream
import com.emcniece.trigger.triggers.AlarmScheduler

/** Something the user must grant for a macro to work. */
sealed class Requirement(val label: String, val optional: Boolean = false) {
    abstract fun isMet(context: Context): Boolean

    class Runtime(label: String, val permissions: List<String>, optional: Boolean = false) : Requirement(label, optional) {
        override fun isMet(context: Context) = permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    class Special(
        label: String,
        optional: Boolean,
        private val check: (Context) -> Boolean,
        val intent: (Context) -> Intent,
    ) : Requirement(label, optional) {
        override fun isMet(context: Context) = check(context)
    }

    override fun equals(other: Any?) = other is Requirement && other.label == label
    override fun hashCode() = label.hashCode()
}

object Requirements {
    private val receiveSms = Requirement.Runtime("Receive SMS", listOf(Manifest.permission.RECEIVE_SMS))
    private val sendSms = Requirement.Runtime("Send SMS", listOf(Manifest.permission.SEND_SMS))
    private val postNotifications = Requirement.Runtime("Post notifications", listOf(Manifest.permission.POST_NOTIFICATIONS))
    private val phoneState = Requirement.Runtime("Phone state (call events)", listOf(Manifest.permission.READ_PHONE_STATE))
    private fun callLog(optional: Boolean) =
        Requirement.Runtime("Call log (caller number)", listOf(Manifest.permission.READ_CALL_LOG), optional)

    @SuppressLint("InlinedApi")
    private val bluetoothConnect = Requirement.Runtime("Nearby devices (Bluetooth)", listOf(Manifest.permission.BLUETOOTH_CONNECT))

    private val notificationAccess = Requirement.Special(
        "Notification access",
        optional = false,
        check = { NotificationManagerCompat.getEnabledListenerPackages(it).contains(it.packageName) },
        intent = { Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) },
    )

    private val exactAlarms = Requirement.Special(
        "Exact alarms (on-time scheduling)",
        optional = true,
        check = { AlarmScheduler.canScheduleExact(it) },
        intent = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${it.packageName}"))
            } else {
                Intent(Settings.ACTION_SETTINGS)
            }
        },
    )

    private val overlay = Requirement.Special(
        "Display over other apps (open apps and links in the background)",
        optional = false,
        check = { Settings.canDrawOverlays(it) },
        intent = { Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${it.packageName}")) },
    )

    private fun dndAccess(optional: Boolean) = Requirement.Special(
        "Do Not Disturb access",
        optional = optional,
        check = { it.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted },
        intent = { Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) },
    )

    @SuppressLint("BatteryLife")
    private val batteryUnrestricted = Requirement.Special(
        "Unrestricted battery use (reliable background runs)",
        optional = true,
        check = { it.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(it.packageName) },
        intent = { Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${it.packageName}")) },
    )

    fun forMacro(macro: Macro): List<Requirement> = buildList {
        when (macro.trigger.type) {
            TriggerType.SMS -> add(receiveSms)
            TriggerType.NOTIFICATION -> add(notificationAccess)
            TriggerType.TIME -> add(exactAlarms)
            TriggerType.POWER, TriggerType.BATTERY, TriggerType.NETWORK, TriggerType.HEADPHONES, TriggerType.SCREEN ->
                if (Build.VERSION.SDK_INT >= 33) add(postNotifications)
            TriggerType.CALL -> {
                add(phoneState)
                // A number filter can't match without the number; otherwise it's just a nice-to-have.
                add(callLog(optional = !(macro.trigger as Trigger.PhoneCall).number.isActive))
                if (Build.VERSION.SDK_INT >= 33) add(postNotifications)
            }
            TriggerType.BLUETOOTH -> if (Build.VERSION.SDK_INT >= 31) add(bluetoothConnect)
            TriggerType.BOOT -> Unit
        }
        if (macro.actions.any { it is Action.SendSms }) add(sendSms)
        if (macro.actions.any { it is Action.OpenApp || it is Action.OpenUrl }) add(overlay)
        when {
            macro.actions.any { it is Action.DoNotDisturb } -> add(dndAccess(optional = false))
            // Ring/notification volume can only be changed while DND is on with DND access.
            macro.actions.any { it is Action.SetVolume && it.stream in setOf(VolumeStream.RING, VolumeStream.NOTIFICATION) } ->
                add(dndAccess(optional = true))
        }
        if (macro.actions.any { it is Action.ShowNotification } && Build.VERSION.SDK_INT >= 33) add(postNotifications)
        add(batteryUnrestricted)
    }.distinct()

    fun unmet(context: Context, macro: Macro) = forMacro(macro).filterNot { it.isMet(context) }
}
