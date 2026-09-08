package com.example.tasker.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
    private val repository = CommandRepository.getInstance(application)

    val uiState: StateFlow<MainScreenUiState> =
        repository.commands
            .map { MainScreenUiState.Success(it) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = MainScreenUiState.Success(repository.commands.value)
            )

    fun saveCommand(command: CommandItem) {
        repository.save(command)
    }

    fun deleteCommand(id: String) {
        repository.delete(id)
    }

    fun duplicateCommand(id: String): CommandItem? {
        return repository.duplicate(id)
    }
}
