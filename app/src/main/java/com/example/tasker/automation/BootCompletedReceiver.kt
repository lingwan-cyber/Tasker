package com.example.tasker.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-registers background Wi-Fi/location monitoring after device reboot or app update.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            AutomationHelper.syncMonitoring(context, fromBoot = true)
        }
    }
}
