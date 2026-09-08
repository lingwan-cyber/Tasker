package com.example.tasker.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.example.tasker.R
import com.example.tasker.data.CommandRepository
import com.example.tasker.runner.ExecutionResult
import com.example.tasker.runner.ShellExecutor
import com.example.tasker.theme.TaskerTheme
import kotlinx.coroutines.launch

class ExecuteCommandActivity : ComponentActivity() {

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        processIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        processIntent(intent)
    }

    private fun processIntent(intent: android.content.Intent) {
        val commandId = intent.getStringExtra(EXTRA_COMMAND_ID) ?: ""
        var commandName = intent.getStringExtra(EXTRA_COMMAND_NAME) ?: ""
        var commandText = intent.getStringExtra(EXTRA_COMMAND_TEXT) ?: ""
        var runAsRoot = intent.getBooleanExtra(EXTRA_RUN_AS_ROOT, false)
        var showOutput = intent.getBooleanExtra(EXTRA_SHOW_OUTPUT, true)
        var autoCloseDelayMs = intent.getIntExtra(EXTRA_AUTO_CLOSE_DELAY_MS, 0)

        // Fallback to repository if extra was missing
        if (commandText.isBlank() && commandId.isNotBlank()) {
            val item = CommandRepository.getInstance(this).getById(commandId)
            if (item != null) {
                commandName = item.name
                commandText = item.command
                runAsRoot = item.runAsRoot
                showOutput = item.showOutput
                autoCloseDelayMs = item.autoCloseDelayMs
            }
        }

        if (commandText.isBlank()) {
            Toast.makeText(this, "Command not found or empty", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (!showOutput) {
            // Background silent mode with Toast
            Toast.makeText(this, "Executing: $commandName...", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                val result = ShellExecutor.execute(commandText, runAsRoot, this@ExecuteCommandActivity)
                if (result.exitCode == 0) {
                    val msg = if (result.stdout.isNotBlank()) {
                        "✓ $commandName: ${result.stdout.lines().firstOrNull()?.take(50)}"
                    } else {
                        "✓ $commandName completed"
                    }
                    Toast.makeText(this@ExecuteCommandActivity, msg, Toast.LENGTH_SHORT).show()
                } else {
                    val err = if (result.stderr.isNotBlank()) {
                        result.stderr.lines().firstOrNull()?.take(60) ?: "Exit ${result.exitCode}"
                    } else {
                        "Exit code: ${result.exitCode}"
                    }
                    Toast.makeText(
                        this@ExecuteCommandActivity,
                        "✗ $commandName failed: $err",
                        Toast.LENGTH_LONG
                    ).show()
                }
                finish()
            }
            return
        }

        enableEdgeToEdge()
        setContent {
            TaskerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    CommandExecutionScreen(
                        name = commandName,
                        command = commandText,
                        runAsRoot = runAsRoot,
                        autoCloseDelayMs = autoCloseDelayMs,
                        onClose = { finish() },
                        onCopyOutput = { text ->
                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Command Output", text))
                            Toast.makeText(this, "Output copied to clipboard", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
    }

    companion object {
        const val EXTRA_COMMAND_ID = "extra_command_id"
        const val EXTRA_COMMAND_NAME = "extra_command_name"
        const val EXTRA_COMMAND_TEXT = "extra_command_text"
        const val EXTRA_RUN_AS_ROOT = "extra_run_as_root"
        const val EXTRA_SHOW_OUTPUT = "extra_show_output"
        const val EXTRA_AUTO_CLOSE_DELAY_MS = "extra_auto_close_delay_ms"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandExecutionScreen(
    name: String,
    command: String,
    runAsRoot: Boolean,
    autoCloseDelayMs: Int = 0,
    onClose: () -> Unit,
    onCopyOutput: (String) -> Unit
) {
    var isRunning by remember { mutableStateOf(true) }
    var result by remember { mutableStateOf<ExecutionResult?>(null) }
    val coroutineScope = rememberCoroutineScope()

    val context = androidx.compose.ui.platform.LocalContext.current

    fun runCmd() {
        isRunning = true
        result = null
        coroutineScope.launch {
            val res = ShellExecutor.execute(command, runAsRoot, context)
            result = res
            isRunning = false
            if (res.exitCode == 0) {
                // Succeeded with no runtime error:
                if (autoCloseDelayMs > 0) {
                    kotlinx.coroutines.delay(autoCloseDelayMs.toLong())
                }
                onClose()
            }
        }
    }

    LaunchedEffect(Unit) {
        runCmd()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(name, maxLines = 1, fontWeight = FontWeight.Bold)
                        Text(
                            if (runAsRoot) "Running as Root (su)" else "Standard Shell (sh)",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (runAsRoot) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onClose) {
                        Text("✕", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            // Command snippet box
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .padding(12.dp)
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "$ ",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = command,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Status bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (isRunning) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Running...",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                } else {
                    result?.let { res ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = if (res.exitCode == 0) Color(0xFF16A34A) else Color(0xFFDC2626)
                            ) {
                                Text(
                                    text = if (res.exitCode == 0) "SUCCESS (Exit 0)" else "EXIT ${res.exitCode}",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "${res.durationMs} ms",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }

                Row {
                    result?.let { res ->
                        val combined = buildString {
                            if (res.stdout.isNotBlank()) appendLine(res.stdout)
                            if (res.stderr.isNotBlank()) appendLine(res.stderr)
                        }
                        IconButton(
                            onClick = { onCopyOutput(combined) },
                            enabled = combined.isNotBlank()
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_copy),
                                contentDescription = "Copy Output",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    IconButton(
                        onClick = { runCmd() },
                        enabled = !isRunning
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_play),
                            contentDescription = "Rerun",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Output Console Terminal Area
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                ) {
                    val res = result
                    if (isRunning) {
                        Text(
                            text = "Executing command...",
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF94A3B8),
                            fontSize = 13.sp
                        )
                    } else if (res != null) {
                        val scrollState = rememberScrollState()
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(scrollState)
                        ) {
                            if (res.stdout.isNotBlank()) {
                                Text(
                                    text = res.stdout,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFFF8FAFC),
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp
                                )
                            }
                            if (res.stderr.isNotBlank()) {
                                if (res.stdout.isNotBlank()) Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = res.stderr,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFFF87171),
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp
                                )
                            }
                            if (res.stdout.isBlank() && res.stderr.isBlank()) {
                                Text(
                                    text = "(Command produced no output)",
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFF64748B),
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onClose,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Close")
            }
        }
    }
}
