package com.example.tasker.data

import org.json.JSONObject
import java.util.UUID

data class CommandItem(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val command: String,
    val runAsRoot: Boolean = false,
    val showOutput: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("command", command)
            put("runAsRoot", runAsRoot)
            put("showOutput", showOutput)
            put("createdAt", createdAt)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): CommandItem {
            return CommandItem(
                id = json.optString("id", UUID.randomUUID().toString()),
                name = json.optString("name", "Untitled Command"),
                command = json.optString("command", ""),
                runAsRoot = json.optBoolean("runAsRoot", false),
                showOutput = json.optBoolean("showOutput", true),
                createdAt = json.optLong("createdAt", System.currentTimeMillis())
            )
        }
    }
}
