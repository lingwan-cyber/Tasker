package com.example.tasker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandItemTest {

    @Test
    fun testSerializationAndDeserialization() {
        val original = CommandItem(
            id = "test-uuid-1234",
            name = "Ping Test",
            command = "ping -c 3 8.8.8.8",
            runAsRoot = true,
            showOutput = false,
            autoCloseDelayMs = 500,
            createdAt = 123456789L
        )

        val json = original.toJson()
        val restored = CommandItem.fromJson(json)

        assertEquals("test-uuid-1234", restored.id)
        assertEquals("Ping Test", restored.name)
        assertEquals("ping -c 3 8.8.8.8", restored.command)
        assertTrue(restored.runAsRoot)
        assertFalse(restored.showOutput)
        assertEquals(500, restored.autoCloseDelayMs)
        assertEquals(123456789L, restored.createdAt)
    }

    @Test
    fun testDefaultValuesFromJson() {
        val json = org.json.JSONObject()
        val item = CommandItem.fromJson(json)

        assertEquals("Untitled Command", item.name)
        assertEquals("", item.command)
        assertFalse(item.runAsRoot)
        assertTrue(item.showOutput)
        assertEquals(0, item.autoCloseDelayMs)
    }

    @Test
    fun testDuplicateCommandItem() {
        val original = CommandItem(
            id = "orig-123",
            name = "510 dp",
            command = "wm density 400",
            runAsRoot = false,
            showOutput = true,
            autoCloseDelayMs = 250
        )
        val copy = original.copy(
            id = "new-456",
            name = "${original.name} (Copy)"
        )
        assertEquals("new-456", copy.id)
        assertEquals("510 dp (Copy)", copy.name)
        assertEquals("wm density 400", copy.command)
        assertEquals(false, copy.runAsRoot)
        assertEquals(true, copy.showOutput)
        assertEquals(250, copy.autoCloseDelayMs)
    }
}
