package com.emcniece.trigger

import com.emcniece.trigger.engine.Http
import com.emcniece.trigger.engine.Templates
import com.emcniece.trigger.model.Action
import com.emcniece.trigger.model.AppJson
import com.emcniece.trigger.model.HttpHeader
import com.emcniece.trigger.model.Macro
import com.emcniece.trigger.model.MatchMode
import com.emcniece.trigger.model.NetworkTransport
import com.emcniece.trigger.model.TextMatch
import com.emcniece.trigger.model.Trigger
import com.emcniece.trigger.model.TriggerEvent
import com.emcniece.trigger.model.TriggerType
import com.emcniece.trigger.model.Variables
import org.junit.Assert.assertEquals
import com.emcniece.trigger.data.ImportException
import com.emcniece.trigger.data.MacroTransfer
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTest {
    @Test fun textMatchModes() {
        assertTrue(TextMatch().matches(null))
        assertTrue(TextMatch(MatchMode.CONTAINS, "code").matches("Your CODE is 1234"))
        assertFalse(TextMatch(MatchMode.CONTAINS, "code", ignoreCase = false).matches("Your CODE is 1234"))
        assertTrue(TextMatch(MatchMode.REGEX, "\\b\\d{4}\\b").matches("Your code is 1234"))
        assertFalse(TextMatch(MatchMode.REGEX, "([").matches("anything")) // invalid regex never matches
        assertTrue(TextMatch(MatchMode.STARTS_WITH, "+1").matches("+15555550123"))
    }

    @Test fun smsTriggerFilters() {
        val t = Trigger.SmsReceived(body = TextMatch(MatchMode.CONTAINS, "alarm"))
        val hit = TriggerEvent(TriggerType.SMS, mapOf("sms_sender" to "123", "sms_body" to "ALARM tripped"))
        val miss = TriggerEvent(TriggerType.SMS, mapOf("sms_sender" to "123", "sms_body" to "hello"))
        assertTrue(t.matches(hit))
        assertFalse(t.matches(miss))
        assertFalse(t.matches(TriggerEvent(TriggerType.BOOT)))
    }

    @Test fun batteryCrossing() {
        val t = Trigger.BatteryLevel(threshold = 20, below = true)
        fun ev(level: Int, prev: Int) = TriggerEvent(TriggerType.BATTERY, mapOf("battery_level" to "$level", "battery_previous" to "$prev"))
        assertTrue(t.matches(ev(20, 21)))
        assertFalse(t.matches(ev(19, 20))) // already below; don't re-fire
        assertFalse(t.matches(ev(22, 21)))
        val up = Trigger.BatteryLevel(threshold = 80, below = false)
        assertTrue(up.matches(ev(80, 79)))
    }

    @Test fun networkScope() {
        val t = Trigger.NetworkChanged(connected = true, transport = NetworkTransport.WIFI)
        assertTrue(t.matches(TriggerEvent(TriggerType.NETWORK, mapOf("network_scope" to "wifi", "network_state" to "connected"))))
        assertFalse(t.matches(TriggerEvent(TriggerType.NETWORK, mapOf("network_scope" to "any", "network_state" to "connected"))))
    }

    @Test fun templates() {
        val vars = mapOf("sms_body" to "He said \"hi\"\nbye", "sms_sender" to "+1 555")
        assertEquals("""{"t": "He said \"hi\"\nbye"}""", Templates.render("""{"t": "{sms_body:json}"}""", vars))
        assertEquals("q=%2B1+555", Templates.render("q={sms_sender:url}", vars))
        assertEquals("{unknown} {", Templates.render("{unknown} {", vars))
    }

    @Test fun variableProblems() {
        val sms = TriggerType.SMS
        assertEquals(emptyList<String>(), Variables.problems("""{"text": "{sms_body:json}", "at": "{timestamp}"}""", sms))
        assertEquals(listOf("Unknown variable {sms_message}"), Variables.problems("{sms_message}", sms))
        assertEquals(
            listOf("{battery_level} isn't set by the “SMS received” trigger"),
            Variables.problems("Battery {battery_level}%", sms),
        )
        assertEquals(listOf("Unknown format in {sms_body:xml}; use :json or :url"), Variables.problems("{sms_body:xml}", sms))
        // JSON braces and repeated tokens aren't double-reported.
        assertEquals(listOf("Unknown variable {nope}"), Variables.problems("""{"a": {"b": 1}} {nope} {nope}""", sms))
        // Every variable the engine sets for a trigger is accepted.
        for (type in TriggerType.entries) {
            assertEquals(emptyList<String>(), Variables.problems(type.sampleVars().keys.joinToString(" ") { "{$it}" }, type))
        }
    }

    @Test fun headerParsing() {
        val h = Http.parseHeaders("Content-Type: application/json\nAuthorization: Bearer a:b\n\nbad line")
        assertEquals(mapOf("Content-Type" to "application/json", "Authorization" to "Bearer a:b"), h)
    }

    @Test fun legacyStringHeadersStillLoad() {
        val json = """{"kind":"http","method":"POST","url":"https://x",""" +
            """"headers":"Content-Type: application/json\nX-Webhook-Secret: s3:cr3t","body":""}"""
        val action = AppJson.decodeFromString(Action.serializer(), json) as Action.HttpRequest
        assertEquals(
            listOf(HttpHeader("Content-Type", "application/json"), HttpHeader("X-Webhook-Secret", "s3:cr3t")),
            action.headers,
        )
    }

    @Test fun exportRoundTrip() {
        val macros = listOf(Macro(name = "A", actions = listOf(Action.Vibrate(100))), Macro(name = "B", trigger = Trigger.Boot))
        val text = MacroTransfer.encode(macros, "0.1.1")
        assertTrue(text.contains("\"format\": \"trigger-macros\""))
        assertEquals(macros, MacroTransfer.decode(text))
    }

    @Test fun importAcceptsBareListBackup() {
        // Same shape as files/macros.json, e.g. a backup pulled with adb.
        val backup = AppJson.encodeToString(ListSerializer(Macro.serializer()), listOf(Macro(id = "x", name = "Backup")))
        assertEquals("Backup", MacroTransfer.decode(backup).single().name)
    }

    @Test fun importRejectsOtherJson() {
        for (bad in listOf("""{"hello": 1}""", "not json", "42", "[1, 2]", """[{"trigger": {"kind": "nope"}}]""")) {
            try {
                MacroTransfer.decode(bad)
                fail("expected ImportException for $bad")
            } catch (_: ImportException) {
            }
        }
    }

    @Test fun keepBothRenamesConflicts() {
        val incoming = listOf(Macro(id = "a", name = "Same"), Macro(id = "b", name = "New"))
        val result = MacroTransfer.asCopies(incoming, existingIds = setOf("a"))
        assertNotEquals("a", result[0].id)
        assertEquals("Same (copy)", result[0].name)
        assertEquals(incoming[1], result[1])
    }

    @Test fun macroJsonRoundTrip() {
        val m = Macro(
            name = "Forward",
            trigger = Trigger.SmsReceived(sender = TextMatch(MatchMode.EQUALS, "123")),
            actions = listOf(
                Action.HttpRequest(url = "https://x", headers = listOf(HttpHeader("X-Webhook-Secret", "abc"))),
                Action.Vibrate(200),
            ),
        )
        val json = AppJson.encodeToString(Macro.serializer(), m)
        assertEquals(m, AppJson.decodeFromString(Macro.serializer(), json))
        val boot = m.copy(trigger = Trigger.Boot)
        assertEquals(boot, AppJson.decodeFromString(Macro.serializer(), AppJson.encodeToString(Macro.serializer(), boot)))
    }
}
