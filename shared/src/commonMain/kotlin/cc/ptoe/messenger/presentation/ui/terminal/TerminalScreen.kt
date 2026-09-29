/*
 * Copyright 2026 ECSDevs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cc.ptoe.messenger.presentation.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.action_back
import cc.ptoe.messenger.generated.resources.terminal_clear
import cc.ptoe.messenger.generated.resources.terminal_empty_hint
import cc.ptoe.messenger.generated.resources.terminal_exit_code
import cc.ptoe.messenger.generated.resources.terminal_history
import cc.ptoe.messenger.generated.resources.terminal_input_hint
import cc.ptoe.messenger.generated.resources.terminal_preparing_runtime
import cc.ptoe.messenger.generated.resources.terminal_retry
import cc.ptoe.messenger.generated.resources.terminal_runtime_failed
import cc.ptoe.messenger.generated.resources.terminal_send
import cc.ptoe.messenger.generated.resources.terminal_stop
import cc.ptoe.messenger.generated.resources.terminal_terminated
import cc.ptoe.messenger.generated.resources.terminal_timeout
import cc.ptoe.messenger.generated.resources.terminal_title
import cc.ptoe.messenger.presentation.viewmodel.TerminalViewModel
import org.jetbrains.compose.resources.stringResource

/**
 * 用户手动操作的内置终端。每条命令独立执行并流式回显输出；cd 的目录
 * 落点跨命令保持。运行时与 Agent 的 terminal 工具共用（Android 为打包的
 * Termux bootstrap），但不做只读策略过滤。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TerminalViewModel = viewModel(factory = TerminalViewModel.provideFactory())
) {
    val transcript by viewModel.transcript.collectAsStateWithLifecycle()
    val input by viewModel.input.collectAsStateWithLifecycle()
    val running by viewModel.running.collectAsStateWithLifecycle()
    val cwd by viewModel.cwd.collectAsStateWithLifecycle()
    val runtimeState by viewModel.runtimeState.collectAsStateWithLifecycle()
    val runtimeError by viewModel.runtimeError.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(Res.string.terminal_title)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.action_back)
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = viewModel::clearTranscript,
                        enabled = !running
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = stringResource(Res.string.terminal_clear)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        },
        bottomBar = {
            TerminalInputBar(
                input = input,
                onInputChange = viewModel::onInputChange,
                onSubmit = viewModel::submit,
                onStop = viewModel::stop,
                running = running,
                runtimeReady = runtimeState == TerminalViewModel.RuntimeState.Ready,
                cwd = cwd,
                history = viewModel.historyItems
            )
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (runtimeState) {
                TerminalViewModel.RuntimeState.Loading -> TerminalStatePane {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(Res.string.terminal_preparing_runtime),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                TerminalViewModel.RuntimeState.Failed -> TerminalStatePane {
                    Text(
                        text = stringResource(Res.string.terminal_runtime_failed, runtimeError),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = viewModel::prepareRuntime) {
                        Text(text = stringResource(Res.string.terminal_retry))
                    }
                }

                TerminalViewModel.RuntimeState.Ready -> TerminalTranscript(
                    entries = transcript,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
private fun TerminalStatePane(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            content()
        }
    }
}

@Composable
private fun TerminalTranscript(
    entries: List<TerminalViewModel.TerminalEntry>,
    modifier: Modifier = Modifier
) {
    if (entries.isEmpty()) {
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(Res.string.terminal_empty_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
        }
        return
    }

    val listState = rememberLazyListState()
    // 仅当视口停在底部时跟随新输出，避免翻阅历史时被强行拉回。
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible >= info.totalItemsCount - 1
        }
    }
    LaunchedEffect(entries) {
        if (atBottom) listState.scrollToItem(entries.lastIndex)
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items(entries) { entry -> TerminalEntryRow(entry) }
    }
}

@Composable
private fun TerminalEntryRow(entry: TerminalViewModel.TerminalEntry) {
    when (entry) {
        is TerminalViewModel.TerminalEntry.Command -> Text(
            text = "$ ${entry.text}",
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 18.sp
            ),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth()
        )

        is TerminalViewModel.TerminalEntry.Output -> Text(
            text = entry.text,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 18.sp
            ),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth()
        )

        is TerminalViewModel.TerminalEntry.Status -> Text(
            text = stringResource(Res.string.terminal_exit_code, entry.exitCode),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.fillMaxWidth()
        )

        TerminalViewModel.TerminalEntry.Terminated -> Text(
            text = stringResource(Res.string.terminal_terminated),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.fillMaxWidth()
        )

        TerminalViewModel.TerminalEntry.TimedOut -> Text(
            text = stringResource(Res.string.terminal_timeout),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.fillMaxWidth()
        )

        is TerminalViewModel.TerminalEntry.Info -> Text(
            text = entry.text,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun TerminalInputBar(
    input: String,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onStop: () -> Unit,
    running: Boolean,
    runtimeReady: Boolean,
    cwd: String,
    history: List<String>
) {
    var showHistory by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding()
            .imePadding()
    ) {
        if (running) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text(
            text = cwd,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                IconButton(
                    onClick = { showHistory = true },
                    enabled = history.isNotEmpty()
                ) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = stringResource(Res.string.terminal_history)
                    )
                }
                DropdownMenu(
                    expanded = showHistory,
                    onDismissRequest = { showHistory = false }
                ) {
                    history.asReversed().forEach { command ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = command,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            onClick = {
                                onInputChange(command)
                                showHistory = false
                            }
                        )
                    }
                }
            }
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier
                    .weight(1f)
                    .onPreviewKeyEvent { event ->
                        // 桌面端回车提交（移动端走 IME 的 Send 动作）。
                        if (event.type == KeyEventType.KeyDown &&
                            event.key == Key.Enter &&
                            runtimeReady && !running
                        ) {
                            onSubmit()
                            true
                        } else {
                            false
                        }
                    },
                placeholder = { Text(text = stringResource(Res.string.terminal_input_hint)) },
                singleLine = true,
                enabled = runtimeReady,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSubmit() })
            )
            if (running) {
                IconButton(onClick = onStop) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = stringResource(Res.string.terminal_stop),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            } else {
                IconButton(onClick = onSubmit, enabled = input.isNotBlank() && runtimeReady) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(Res.string.terminal_send),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
