package com.example.tasker.shortcut

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.example.tasker.R
import com.example.tasker.data.CommandItem
import com.example.tasker.ui.ExecuteCommandActivity

object ShortcutHelper {

    fun createHomeScreenShortcut(context: Context, command: CommandItem): Boolean {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            Toast.makeText(
                context,
                "Your launcher does not support pinning shortcuts to the home screen.",
                Toast.LENGTH_LONG
            ).show()
            return false
        }

        val launchIntent = Intent(context, ExecuteCommandActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(ExecuteCommandActivity.EXTRA_COMMAND_ID, command.id)
            putExtra(ExecuteCommandActivity.EXTRA_COMMAND_NAME, command.name)
            putExtra(ExecuteCommandActivity.EXTRA_COMMAND_TEXT, command.command)
            putExtra(ExecuteCommandActivity.EXTRA_RUN_AS_ROOT, command.runAsRoot)
            putExtra(ExecuteCommandActivity.EXTRA_SHOW_OUTPUT, command.showOutput)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val shortcutInfo = ShortcutInfoCompat.Builder(context, "tasker_cmd_${command.id}")
            .setShortLabel(command.name.take(15))
            .setLongLabel(command.name)
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_terminal_shortcut))
            .setIntent(launchIntent)
            .build()

        val success = ShortcutManagerCompat.requestPinShortcut(context, shortcutInfo, null)
        if (success) {
            Toast.makeText(
                context,
                "Requested home screen shortcut for \"${command.name}\"",
                Toast.LENGTH_SHORT
            ).show()
        }
        return success
    }
}
