package com.example.tasker.ui.main

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.tasker.R
import com.example.tasker.data.CommandItem
import com.example.tasker.runner.ExecutionResult
import com.example.tasker.runner.ShellExecutor
import com.example.tasker.shortcut.ShortcutHelper
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    viewModel: MainScreenViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var commandToEdit by remember { mutableStateOf<CommandItem?>(null) }
    var isCreatingNew by remember { mutableStateOf(false) }
    var commandToDelete by remember { mutableStateOf<CommandItem?>(null) }
    var commandToTest by remember { mutableStateOf<CommandItem?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Tasker",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                        Text(
                            text = "Shell Command Shortcuts",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { isCreatingNew = true },
                icon = {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_add),
                        contentDescription = "Add Command"
                    )
                },
                text = { Text("New Command") }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (val state = uiState) {
                is MainScreenUiState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                is MainScreenUiState.Success -> {
                    if (state.commands.isEmpty()) {
                        EmptyStateView(
                            onAddClick = { isCreatingNew = true },
                            modifier = Modifier.align(Alignment.Center)
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(state.commands, key = { it.id }) { item ->
                                CommandCard(
                                    item = item,
                                    onPinShortcut = {
                                        ShortcutHelper.createHomeScreenShortcut(context, item)
                                    },
                                    onRun = {
                                        commandToTest = item
                                    },
                                    onDuplicate = {
                                        val copy = viewModel.duplicateCommand(item.id)
                                        if (copy != null) {
                                            Toast.makeText(context, "Duplicated \"${item.name}\"", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    onEdit = {
                                        commandToEdit = item
                                    },
                                    onDelete = {
                                        commandToDelete = item
                                    }
                                )
                            }
                            item {
                                Spacer(modifier = Modifier.height(72.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    // Add / Edit Dialog
    if (isCreatingNew || commandToEdit != null) {
        CommandEditDialog(
            initial = commandToEdit,
            onDismiss = {
                isCreatingNew = false
                commandToEdit = null
            },
            onSave = { savedItem ->
                viewModel.saveCommand(savedItem)
                isCreatingNew = false
                commandToEdit = null
                Toast.makeText(context, "Saved \"${savedItem.name}\"", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // Delete Confirmation Dialog
    commandToDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { commandToDelete = null },
            title = { Text("Delete Command") },
            text = { Text("Are you sure you want to delete \"${item.name}\"?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteCommand(item.id)
                        commandToDelete = null
                        Toast.makeText(context, "Deleted", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { commandToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // In-App Test Run Dialog
    commandToTest?.let { item ->
        TestExecutionDialog(
            command = item,
            onDismiss = { commandToTest = null }
        )
    }
}

@Composable
fun CommandCard(
    item: CommandItem,
    onPinShortcut: () -> Unit,
    onRun: () -> Unit,
    onDuplicate: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )

                Row {
                    IconButton(onClick = onDuplicate, modifier = Modifier.size(32.dp)) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_copy),
                            contentDescription = "Duplicate",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_edit),
                            contentDescription = "Edit",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_delete),
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Badges
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (item.runAsRoot) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Text(
                            text = "ROOT",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        text = if (item.showOutput) {
                            if (item.autoCloseDelayMs == 0) "Instant Auto-Close" else "Close: ${item.autoCloseDelayMs}ms"
                        } else "Silent / Toast",
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Shell command snippet
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = Color(0xFF1E293B),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "$ ",
                        color = Color(0xFF38BDF8),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    Text(
                        text = item.command,
                        color = Color(0xFFF1F5F9),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Actions row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onPinShortcut,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_shortcut),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Pin Shortcut", fontSize = 13.sp)
                }

                FilledTonalButton(
                    onClick = onRun,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_play),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Run", fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
fun EmptyStateView(onAddClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_terminal_shortcut),
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No Shell Commands Yet",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Create custom shell commands and pin 1-tap shortcuts to your home screen.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onAddClick) {
            Text("Create First Command")
        }
    }
}

@Composable
fun CommandEditDialog(
    initial: CommandItem?,
    onDismiss: () -> Unit,
    onSave: (CommandItem) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var commandText by remember { mutableStateOf(initial?.command ?: "") }
    var runAsRoot by remember { mutableStateOf(initial?.runAsRoot ?: false) }
    var showOutput by remember { mutableStateOf(initial?.showOutput ?: true) }
    var delayText by remember { mutableStateOf((initial?.autoCloseDelayMs ?: 0).toString()) }

    val presets = listOf(
        "434 dp" to "wm density reset && settings put system font_scale 1.0",
        "511 dp" to "wm density 382 && settings put system font_scale 0.85",
        "550 dp" to "wm density $(( $(wm size | grep -o '[0-9]*x[0-9]*' | tail -1 | cut -dx -f1) * 160 / 550 )) && settings put system font_scale 0.85",
        "Reset Density" to "wm density reset",
        "Font Default" to "settings put system font_scale 1.0",
        "Font Small" to "settings put system font_scale 0.85",
        "Disk Free" to "df -h /data",
        "Date" to "date",
        "WhoAmI" to "id"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (initial == null) "Create Shell Command" else "Edit Shell Command",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Shortcut Name / Label") },
                    placeholder = { Text("e.g. Ping Google") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = commandText,
                    onValueChange = { commandText = it },
                    label = { Text("Shell Command") },
                    placeholder = { Text("e.g. ping -c 4 8.8.8.8") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4
                )

                Text(
                    text = "Quick Presets:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(presets) { (label, cmd) ->
                        SuggestionChip(
                            onClick = {
                                if (name.isBlank()) name = label
                                commandText = cmd
                            },
                            label = { Text(label, fontSize = 12.sp) }
                        )
                    }
                }

                HorizontalDivider()

                // Show Output Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Show Output Window", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        Text(
                            if (showOutput) "Opens dialog with terminal output" else "Runs silently and notifies via Toast",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    Switch(
                        checked = showOutput,
                        onCheckedChange = { showOutput = it }
                    )
                }

                if (showOutput) {
                    OutlinedTextField(
                        value = delayText,
                        onValueChange = { input ->
                            if (input.all { it.isDigit() } && input.length <= 6) {
                                delayText = input
                            }
                        },
                        label = { Text("Auto-Close Delay (ms)") },
                        placeholder = { Text("0") },
                        supportingText = {
                            val parsedDelay = delayText.toIntOrNull() ?: 0
                            Text(
                                if (parsedDelay == 0) "0 ms = Closes instantly once command succeeds"
                                else "Waits ${parsedDelay} ms before closing on success"
                            )
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    val quickDelays = listOf(0, 300, 500, 1000, 2000)
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(quickDelays) { ms ->
                            val isSelected = (delayText.toIntOrNull() ?: 0) == ms
                            FilterChip(
                                selected = isSelected,
                                onClick = { delayText = ms.toString() },
                                label = { Text(if (ms == 0) "0 ms (Instant)" else "${ms} ms", fontSize = 12.sp) }
                            )
                        }
                    }
                }

                // Run As Root Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Run as Root (su)", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        Text(
                            "Requires rooted device with su binary",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    Switch(
                        checked = runAsRoot,
                        onCheckedChange = { runAsRoot = it }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val parsedDelay = delayText.toIntOrNull() ?: 0
                    val finalItem = (initial ?: CommandItem(name = "", command = "")).copy(
                        name = name.trim().ifBlank { "Shell Command" },
                        command = commandText.trim(),
                        runAsRoot = runAsRoot,
                        showOutput = showOutput,
                        autoCloseDelayMs = parsedDelay
                    )
                    onSave(finalItem)
                },
                enabled = commandText.isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun TestExecutionDialog(
    command: CommandItem,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var isRunning by remember { mutableStateOf(true) }
    var result by remember { mutableStateOf<ExecutionResult?>(null) }
    val coroutineScope = rememberCoroutineScope()

    fun run() {
        isRunning = true
        result = null
        coroutineScope.launch {
            val res = ShellExecutor.execute(command.command, command.runAsRoot, context)
            result = res
            isRunning = false
        }
    }

    LaunchedEffect(Unit) {
        run()
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = command.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (command.runAsRoot) "Root (su)" else "Standard Shell (sh)",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (command.runAsRoot) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Text("✕", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Command box
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "$ ${command.command}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(8.dp)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Status
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (isRunning) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Executing...", fontSize = 12.sp)
                        }
                    } else {
                        result?.let { res ->
                            Text(
                                text = "Exit ${res.exitCode} (${res.durationMs}ms)",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (res.exitCode == 0) Color(0xFF16A34A) else Color(0xFFDC2626)
                            )
                        }
                    }

                    Row {
                        result?.let { res ->
                            val out = buildString {
                                if (res.stdout.isNotBlank()) appendLine(res.stdout)
                                if (res.stderr.isNotBlank()) appendLine(res.stderr)
                            }
                            IconButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Output", out))
                                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_copy),
                                    contentDescription = "Copy",
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        IconButton(
                            onClick = { run() },
                            enabled = !isRunning,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_play),
                                contentDescription = "Rerun",
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Terminal Output Area
                Surface(
                    color = Color(0xFF0F172A),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    Box(modifier = Modifier.fillMaxSize().padding(10.dp)) {
                        val res = result
                        if (isRunning) {
                            Text("Running command...", color = Color(0xFF94A3B8), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        } else if (res != null) {
                            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                                if (res.stdout.isNotBlank()) {
                                    Text(
                                        text = res.stdout,
                                        fontFamily = FontFamily.Monospace,
                                        color = Color(0xFFF8FAFC),
                                        fontSize = 12.sp
                                    )
                                }
                                if (res.stderr.isNotBlank()) {
                                    if (res.stdout.isNotBlank()) Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = res.stderr,
                                        fontFamily = FontFamily.Monospace,
                                        color = Color(0xFFF87171),
                                        fontSize = 12.sp
                                    )
                                }
                                if (res.stdout.isBlank() && res.stderr.isBlank()) {
                                    Text("(No output produced)", color = Color(0xFF64748B), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Done")
                }
            }
        }
    }
}
