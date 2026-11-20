package com.example.tasker.runner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeControllerTest {

    @Test
    fun parseVolumePercentageAndKeywords() {
        val pct = VolumeController.parseSingle("volume notification 50%")
        assertNotNull(pct)
        assertEquals(VolumeStream.NOTIFICATION, pct!!.stream)
        assertEquals(VolumeAction.SetPercent(50), pct.action)
        assertFalse(pct.showUi)

        val mute = VolumeController.parseSingle("volume notif mute --ui")
        assertNotNull(mute)
        assertEquals(VolumeStream.NOTIFICATION, mute!!.stream)
        assertEquals(VolumeAction.Mute, mute.action)
        assertTrue(mute.showUi)

        val max = VolumeController.parseSingle("volume notification max")
        assertEquals(VolumeAction.Max, max?.action)

        val get = VolumeController.parseSingle("volume notification get")
        assertEquals(VolumeAction.Get, get?.action)

        val up = VolumeController.parseSingle("volume notification +2")
        assertEquals(VolumeAction.AdjustStep(2), up?.action)

        val down = VolumeController.parseSingle("volume notification down")
        assertEquals(VolumeAction.AdjustStep(-1), down?.action)
    }

    @Test
    fun parseMediaSessionCommand() {
        val spec = VolumeController.parseSingle("cmd media_session volume --stream 5 --set 4")
        assertNotNull(spec)
        assertEquals(VolumeStream.NOTIFICATION, spec!!.stream)
        assertEquals(VolumeAction.SetStep(4), spec.action)

        val ringGet = VolumeController.parseSingle("cmd media_session volume --stream 2 --get")
        assertNotNull(ringGet)
        assertEquals(VolumeStream.RING, ringGet!!.stream)
        assertEquals(VolumeAction.Get, ringGet.action)
    }

    @Test
    fun parseChainedCommands() {
        val specs = VolumeController.parseAll("wm density reset && volume notification 30% && volume ring 60%")
        assertEquals(2, specs.size)
        assertEquals(VolumeStream.NOTIFICATION, specs[0].stream)
        assertEquals(VolumeAction.SetPercent(30), specs[0].action)
        assertEquals(VolumeStream.RING, specs[1].stream)
        assertEquals(VolumeAction.SetPercent(60), specs[1].action)
    }

    @Test
    fun invalidCommandsReturnNull() {
        assertNull(VolumeController.parseSingle("df -h /data"))
        assertNull(VolumeController.parseSingle("volume unknownstream 50%"))
        assertNull(VolumeController.parseSingle("volume notification invalid"))
        assertFalse(VolumeController.isVolumeCommand("wm density reset"))
    }

    @Test
    fun resolveTargetStepMath() {
        assertEquals(0, VolumeController.resolveTargetStep(VolumeAction.SetPercent(0), current = 5, min = 0, max = 7))
        assertEquals(4, VolumeController.resolveTargetStep(VolumeAction.SetPercent(50), current = 1, min = 0, max = 7))
        assertEquals(7, VolumeController.resolveTargetStep(VolumeAction.SetPercent(100), current = 1, min = 0, max = 7))
        assertEquals(7, VolumeController.resolveTargetStep(VolumeAction.SetStep(99), current = 1, min = 0, max = 7))
        assertEquals(0, VolumeController.resolveTargetStep(VolumeAction.AdjustStep(-5), current = 2, min = 0, max = 7))
        assertEquals(71, VolumeController.stepToPercent(step = 5, min = 0, max = 7))
    }
}
