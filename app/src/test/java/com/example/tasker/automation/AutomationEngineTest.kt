package com.example.tasker.automation

import com.example.tasker.data.AutomationRule
import com.example.tasker.data.TriggerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationEngineTest {

    @Test
    fun ssidNormalizationStripsQuotesAndPlaceholders() {
        assertEquals("HomeWifi", AutomationEngine.normalizeSsid("\"HomeWifi\""))
        assertEquals("Office-5G", AutomationEngine.normalizeSsid("  Office-5G  "))
        assertNull(AutomationEngine.normalizeSsid("<unknown ssid>"))
        assertNull(AutomationEngine.normalizeSsid("\"<unknown ssid>\""))
        assertNull(AutomationEngine.normalizeSsid("0x"))
        assertNull(AutomationEngine.normalizeSsid("   "))
        assertNull(AutomationEngine.normalizeSsid(null))
    }

    @Test
    fun wifiConnectAndDisconnectTransitionsFireOnce() {
        val officeJoin = AutomationRule(
            id = "r1",
            name = "Office Quiet",
            triggerType = TriggerType.WIFI_CONNECT,
            wifiSsid = "OfficeWiFi",
            notificationVolumePercent = 15
        )
        val officeLeave = AutomationRule(
            id = "r2",
            name = "Leave Office Loud",
            triggerType = TriggerType.WIFI_DISCONNECT,
            wifiSsid = "OfficeWiFi",
            notificationVolumePercent = 80
        )
        val rules = listOf(officeJoin, officeLeave)

        // Joining OfficeWiFi from null -> triggers r1 only
        val onJoin = AutomationEngine.evaluateWifiTransition(rules, previousSsid = null, currentSsid = "\"OfficeWiFi\"")
        assertEquals(listOf("r1"), onJoin.map { it.id })

        // Staying on OfficeWiFi -> no duplicate trigger
        val onStay = AutomationEngine.evaluateWifiTransition(rules, previousSsid = "OfficeWiFi", currentSsid = "OfficeWiFi")
        assertTrue(onStay.isEmpty())

        // Leaving OfficeWiFi -> triggers r2 only
        val onLeave = AutomationEngine.evaluateWifiTransition(rules, previousSsid = "OfficeWiFi", currentSsid = null)
        assertEquals(listOf("r2"), onLeave.map { it.id })
    }

    @Test
    fun locationGeofenceEnterAndExitHysteresis() {
        val officeLat = 37.4220
        val officeLng = -122.0841

        val enterRule = AutomationRule(
            id = "loc_in",
            name = "Arrive Office",
            triggerType = TriggerType.LOCATION_ENTER,
            latitude = officeLat,
            longitude = officeLng,
            radiusMeters = 150f,
            notificationVolumePercent = 10
        )
        val exitRule = AutomationRule(
            id = "loc_out",
            name = "Leave Office",
            triggerType = TriggerType.LOCATION_EXIT,
            latitude = officeLat,
            longitude = officeLng,
            radiusMeters = 150f,
            notificationVolumePercent = 85
        )
        val rules = listOf(enterRule, exitRule)

        // 1. Start far away (~1km north) -> neither triggers, state is outside
        val (fired1, state1) = AutomationEngine.evaluateLocationTransition(
            rules, latitude = 37.4310, longitude = officeLng, previousInsideByRuleId = emptyMap()
        )
        assertTrue(fired1.isEmpty())
        assertEquals(false, state1["loc_in"])
        assertEquals(false, state1["loc_out"])

        // 2. Move inside office -> enterRule fires
        val (fired2, state2) = AutomationEngine.evaluateLocationTransition(
            rules, latitude = officeLat, longitude = officeLng, previousInsideByRuleId = state1
        )
        assertEquals(listOf("loc_in"), fired2.map { it.id })
        assertEquals(true, state2["loc_in"])

        // 3. Stay inside office -> no duplicate fire
        val (fired3, state3) = AutomationEngine.evaluateLocationTransition(
            rules, latitude = officeLat + 0.0001, longitude = officeLng, previousInsideByRuleId = state2
        )
        assertTrue(fired3.isEmpty())

        // 4. Leave office -> exitRule fires
        val (fired4, state4) = AutomationEngine.evaluateLocationTransition(
            rules, latitude = 37.4310, longitude = officeLng, previousInsideByRuleId = state3
        )
        assertEquals(listOf("loc_out"), fired4.map { it.id })
        assertEquals(false, state4["loc_out"])
    }

    @Test
    fun ruleJsonSerializationRoundTrip() {
        val rule = AutomationRule(
            id = "rule-42",
            name = "Home Wi-Fi",
            enabled = true,
            triggerType = TriggerType.WIFI_CONNECT,
            wifiSsid = "MyHome",
            latitude = 37.4220,
            longitude = -122.0841,
            radiusMeters = 200f,
            notificationVolumePercent = 75,
            customCommand = "",
            lastTriggeredAt = 99999L,
            lastResultSummary = "✓ Notification volume set to 5/7 (71%)"
        )
        val restored = AutomationRule.fromJson(rule.toJson())
        assertEquals(rule.id, restored.id)
        assertEquals(rule.name, restored.name)
        assertEquals(rule.triggerType, restored.triggerType)
        assertEquals(rule.wifiSsid, restored.wifiSsid)
        assertEquals(rule.radiusMeters, restored.radiusMeters, 0.01f)
        assertEquals(rule.notificationVolumePercent, restored.notificationVolumePercent)
        assertEquals("volume notification 75%", restored.effectiveCommand())
        assertEquals(rule.lastResultSummary, restored.lastResultSummary)
        assertTrue(restored.enabled)
        assertFalse(restored.triggerType.isLocation)
    }
}
