package com.dt.hgej.ui.main

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dt.hgej.data.model.UserConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onNavigateToLogin: () -> Unit,
    onNavigateToQr: () -> Unit,
    viewModel: MainViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val versionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (_: Exception) { "" }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("杭工e家助手")
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "v$versionName",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    if (uiState.isLoggedIn) {
                        TextButton(onClick = { viewModel.logout() }) {
                            Text("退出")
                        }
                    } else {
                        TextButton(onClick = onNavigateToLogin) {
                            Text("登录")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Status card
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("登录状态: ${if (uiState.isLoggedIn) "已登录" else "未登录"}", style = MaterialTheme.typography.bodyMedium)
                    if (uiState.isLoggedIn) {
                        if (uiState.userName.isNotBlank()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("用户: ${uiState.userName}", style = MaterialTheme.typography.bodySmall)
                                if (uiState.remainIntegral > 0) {
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "积分: ${uiState.remainIntegral}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                        Text("账号: ${uiState.config.loginName}", style = MaterialTheme.typography.bodySmall)
                        Text("SES_ID: ${uiState.config.sesId.take(16)}...", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // Config form
            Text("配置", style = MaterialTheme.typography.titleMedium)
            ConfigForm(
                config = uiState.config,
                onConfigChange = viewModel::updateConfig,
                onAutoFill = viewModel::autoFillRunTime
            )
            if (uiState.config.loginName.isNotBlank() && uiState.config.sesId.isNotBlank()) {
                OutlinedButton(
                    onClick = { viewModel.applyManualCredentials() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("应用配置凭证")
                }
            }

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (uiState.schedulerRunning) {
                    Button(
                        onClick = { viewModel.stopExchange() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("停止")
                    }
                } else {
                    Button(
                        onClick = { viewModel.startExchange() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("开始兑换")
                    }
                }

                OutlinedButton(
                    onClick = { viewModel.doDailyTask() },
                    enabled = !uiState.isDoingDailyTask && uiState.isLoggedIn,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("每日任务")
                }

                OutlinedButton(
                    onClick = onNavigateToQr,
                    enabled = uiState.isLoggedIn,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("乘车码")
                }
            }

            // Log panel
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("运行日志", style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { viewModel.clearLogs() }) {
                    Icon(Icons.Filled.Delete, contentDescription = "清空日志", modifier = Modifier.size(18.dp))
                }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                val listState = rememberLazyListState()
                LaunchedEffect(uiState.logs.size) {
                    if (uiState.logs.isNotEmpty()) {
                        listState.animateScrollToItem(uiState.logs.size - 1)
                    }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(uiState.logs) { log ->
                        Text(
                            text = log,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 14.sp
                        )
                    }
                }
            }
        }
    }

    uiState.updateInfo?.let { info ->
        AlertDialog(
            onDismissRequest = { if (!info.forceUpdate) viewModel.dismissUpdate() },
            title = { Text("发现新版本 v${info.versionName ?: info.versionCode}") },
            text = {
                Text(info.changelog?.takeIf { it.isNotBlank() } ?: "修复已知问题，优化使用体验")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        info.downloadUrl?.takeIf { it.isNotBlank() }?.let { url ->
                            try {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            } catch (_: Exception) {
                            }
                        }
                        if (!info.forceUpdate) viewModel.dismissUpdate()
                    }
                ) {
                    Text("立即更新")
                }
            },
            dismissButton = {
                if (!info.forceUpdate) {
                    TextButton(onClick = { viewModel.dismissUpdate() }) {
                        Text("稍后再说")
                    }
                }
            }
        )
    }
}

@Composable
fun ConfigForm(
    config: UserConfig,
    onConfigChange: (UserConfig) -> Unit,
    onAutoFill: () -> Unit = {}
) {
    val clipboardManager = LocalClipboardManager.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = config.loginName,
            onValueChange = { onConfigChange(config.copy(loginName = it)) },
            label = { Text("LOGIN_NAME") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodySmall,
            trailingIcon = {
                Row {
                    IconButton(onClick = { onConfigChange(config.copy(loginName = "")) }) {
                        Icon(Icons.Filled.Clear, contentDescription = "清除", modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = {
                        clipboardManager.getText()?.text?.let { onConfigChange(config.copy(loginName = it)) }
                    }) {
                        Icon(Icons.Filled.ContentPaste, contentDescription = "粘贴", modifier = Modifier.size(18.dp))
                    }
                }
            }
        )
        OutlinedTextField(
            value = config.sesId,
            onValueChange = { onConfigChange(config.copy(sesId = it)) },
            label = { Text("SES_ID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodySmall,
            trailingIcon = {
                Row {
                    IconButton(onClick = { onConfigChange(config.copy(sesId = "")) }) {
                        Icon(Icons.Filled.Clear, contentDescription = "清除", modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = {
                        clipboardManager.getText()?.text?.let { onConfigChange(config.copy(sesId = it)) }
                    }) {
                        Icon(Icons.Filled.ContentPaste, contentDescription = "粘贴", modifier = Modifier.size(18.dp))
                    }
                }
            }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = config.exchangeId,
                onValueChange = { onConfigChange(config.copy(exchangeId = it)) },
                label = { Text("EXCHANGE_ID") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                value = config.runCount,
                onValueChange = { onConfigChange(config.copy(runCount = it)) },
                label = { Text("次数") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodySmall
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = config.runTime,
                onValueChange = { onConfigChange(config.copy(runTime = it)) },
                label = { Text("执行时间 (yyyy-MM-dd HH:mm:ss)") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodySmall,
                trailingIcon = {
                    IconButton(onClick = onAutoFill) {
                        Icon(Icons.Filled.AccessTime, contentDescription = "自动填充时间")
                    }
                }
            )
            OutlinedTextField(
                value = config.timeSleep,
                onValueChange = { onConfigChange(config.copy(timeSleep = it)) },
                label = { Text("间隔(秒)") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodySmall
            )
        }
    }
}
