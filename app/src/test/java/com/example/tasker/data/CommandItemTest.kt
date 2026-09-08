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
            createdAt = 123456789L
        )

        val json = original.toJson()
        val restored = CommandItem.fromJson(json)

        assertEquals("test-uuid-1234", restored.id)
        assertEquals("Ping Test", restored.name)
        assertEquals("ping -c 3 8.8.8.8", restored.command)
        assertTrue(restored.runAsRoot)
        assertFalse(restored.showOutput)
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
    }
}
