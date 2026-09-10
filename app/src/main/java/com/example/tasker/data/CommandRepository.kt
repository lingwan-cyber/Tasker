package com.example.tasker.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class CommandRepository(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private val _commands = MutableStateFlow<List<CommandItem>>(emptyList())
    val commands: StateFlow<List<CommandItem>> = _commands.asStateFlow()

    init {
        loadCommands()
    }

    private fun loadCommands() {
        val jsonString = prefs.getString(KEY_COMMANDS, null)
        if (jsonString.isNullOrBlank()) {
            val defaults = getDefaultCommands()
            saveCommandsInternal(defaults)
            _commands.value = defaults
        } else {
            try {
                val array = JSONArray(jsonString)
                val list = mutableListOf<CommandItem>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    var item = CommandItem.fromJson(obj)
                    // Remove ping and uptime as requested
                    if (item.name.contains("Ping", ignoreCase = true) || item.name.contains("Uptime", ignoreCase = true)) {
                        continue
                    }
                    // Update commands to 434 dp (native default), 511 dp (small), and 550 dp
                    if (item.name.contains("510") || item.name == "434 dp") {
                        item = item.copy(
                            name = "434 dp",
                            command = "wm density reset && settings put system font_scale 1.0"
                        )
                    } else if (item.name == "511 dp") {
                        item = item.copy(
                            name = "511 dp",
                            command = "wm density 382 && settings put system font_scale 0.85"
                        )
                    } else if (item.name == "550 dp") {
                        item = item.copy(
                            name = "550 dp",
                            command = "wm density $(( $(wm size | grep -o '[0-9]*x[0-9]*' | tail -1 | cut -dx -f1) * 160 / 550 )) && settings put system font_scale 0.85"
                        )
                    }
                    list.add(item)
                }

                // Ensure 550 dp command is available
                val has550 = list.any { it.name == "550 dp" }
                if (!has550) {
                    val idx511 = list.indexOfFirst { it.name == "511 dp" }
                    val insertPos = if (idx511 >= 0) idx511 + 1 else if (list.size >= 2) 2 else list.size
                    list.add(insertPos, CommandItem(
                        name = "550 dp",
                        command = "wm density $(( $(wm size | grep -o '[0-9]*x[0-9]*' | tail -1 | cut -dx -f1) * 160 / 550 )) && settings put system font_scale 0.85",
                        runAsRoot = false,
                        showOutput = true
                    ))
                }

                // Ensure 511 dp command is available
                val has511 = list.any { it.name == "511 dp" }
                if (!has511) {
                    val insertPos = if (list.isNotEmpty()) 1 else 0
                    list.add(insertPos, CommandItem(
                        name = "511 dp",
                        command = "wm density 382 && settings put system font_scale 0.85",
                        runAsRoot = false,
                        showOutput = true
                    ))
                }

                // Ensure 434 dp command is available
                val has434 = list.any { it.name == "434 dp" }
                if (!has434) {
                    list.add(0, CommandItem(
                        name = "434 dp",
                        command = "wm density reset && settings put system font_scale 1.0",
                        runAsRoot = false,
                        showOutput = true
                    ))
                }

                saveCommandsInternal(list)
                _commands.value = list
            } catch (e: Exception) {
                _commands.value = getDefaultCommands()
            }
        }
    }

    private fun getDefaultCommands(): List<CommandItem> {
        return listOf(
            CommandItem(
                name = "434 dp",
                command = "wm density reset && settings put system font_scale 1.0",
                runAsRoot = false,
                showOutput = true
            ),
            CommandItem(
                name = "511 dp",
                command = "wm density 382 && settings put system font_scale 0.85",
                runAsRoot = false,
                showOutput = true
            ),
            CommandItem(
                name = "550 dp",
                command = "wm density $(( $(wm size | grep -o '[0-9]*x[0-9]*' | tail -1 | cut -dx -f1) * 160 / 550 )) && settings put system font_scale 0.85",
                runAsRoot = false,
                showOutput = true
            ),
            CommandItem(
                name = "Reset Display Density",
                command = "wm density reset",
                runAsRoot = false,
                showOutput = true
            ),
            CommandItem(
                name = "Storage Disk Free",
                command = "df -h /data",
                runAsRoot = false,
                showOutput = true
            )
        )
    }

    fun getById(id: String): CommandItem? {
        return _commands.value.firstOrNull { it.id == id }
    }

    fun save(item: CommandItem) {
        val currentList = _commands.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == item.id }
        if (index != -1) {
            currentList[index] = item
        } else {
            currentList.add(0, item)
        }
        saveCommandsInternal(currentList)
        _commands.value = currentList
    }

    fun delete(id: String) {
        val updatedList = _commands.value.filter { it.id != id }
        saveCommandsInternal(updatedList)
        _commands.value = updatedList
    }

    fun duplicate(id: String): CommandItem? {
        val currentList = _commands.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == id }
        if (index == -1) return null
        val original = currentList[index]
        val duplicated = original.copy(
            id = java.util.UUID.randomUUID().toString(),
            name = "${original.name} (Copy)",
            createdAt = System.currentTimeMillis()
        )
        currentList.add(index + 1, duplicated)
        saveCommandsInternal(currentList)
        _commands.value = currentList
        return duplicated
    }

    private fun saveCommandsInternal(items: List<CommandItem>) {
        val array = JSONArray()
        items.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_COMMANDS, array.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "tasker_commands_prefs"
        private const val KEY_COMMANDS = "commands_list"

        @Volatile
        private var instance: CommandRepository? = null

        fun getInstance(context: Context): CommandRepository {
            return instance ?: synchronized(this) {
                instance ?: CommandRepository(context).also { instance = it }
            }
        }
    }
}
