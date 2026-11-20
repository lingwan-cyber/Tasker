package com.example.tasker.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.tasker.automation.AutomationHelper
import com.example.tasker.data.AutomationRepository
import com.example.tasker.data.AutomationRule
import com.example.tasker.data.CommandItem
import com.example.tasker.data.CommandRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

sealed interface MainScreenUiState {
    object Loading : MainScreenUiState
    data class Success(val commands: List<CommandItem>) : MainScreenUiState
}

class MainScreenViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val repository = CommandRepository.getInstance(appContext)
    private val automationRepository = AutomationRepository.getInstance(appContext)

    val uiState: StateFlow<MainScreenUiState> =
        repository.commands
            .map { MainScreenUiState.Success(it) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = MainScreenUiState.Success(repository.commands.value)
            )

    val automationRules: StateFlow<List<AutomationRule>> = automationRepository.rules
    val monitoringEnabled: StateFlow<Boolean> = automationRepository.monitoringEnabled
    val observedWifiSsid: StateFlow<String?> = automationRepository.lastWifiSsid
    val observedLocationText: StateFlow<String?> = automationRepository.lastLocationText

    fun saveCommand(command: CommandItem) {
        repository.save(command)
    }

    fun deleteCommand(id: String) {
        repository.delete(id)
    }

    fun duplicateCommand(id: String): CommandItem? {
        return repository.duplicate(id)
    }

    fun saveRule(rule: AutomationRule) {
        automationRepository.save(rule)
        AutomationHelper.syncMonitoring(appContext)
    }

    fun deleteRule(id: String) {
        automationRepository.delete(id)
        AutomationHelper.syncMonitoring(appContext)
    }

    fun setRuleEnabled(id: String, enabled: Boolean) {
        automationRepository.setRuleEnabled(id, enabled)
        AutomationHelper.syncMonitoring(appContext)
    }

    fun setMonitoringEnabled(enabled: Boolean) {
        automationRepository.setMonitoringEnabled(enabled)
        AutomationHelper.syncMonitoring(appContext)
    }

    fun evaluateRulesNow() {
        if (automationRepository.monitoringEnabled.value) {
            AutomationHelper.requestImmediateEvaluation(appContext)
        }
    }
}
