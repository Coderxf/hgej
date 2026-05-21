package com.dt.hgej.ui.qrcode

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dt.hgej.util.Utils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrScreen(
    onBack: () -> Unit,
    viewModel: QrViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("绿色出行码") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.loadData() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                }
            )
        }
    ) { padding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (uiState.error != null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(uiState.error!!, color = MaterialTheme.colorScheme.error)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // QR Code
                val qrCode = uiState.qrCode
                if (qrCode != null) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val imageData = qrCode.qrcodeImage ?: qrCode.qrcode
                            val qrBitmap = if (imageData != null) {
                                remember(imageData) {
                                    try {
                                        val raw = if (imageData == qrCode.qrcodeImage) {
                                            Utils.decodeBase64Image(imageData)
                                        } else {
                                            hexStringToByteArray(imageData)
                                        }
                                        if (raw != null) BitmapFactory.decodeByteArray(raw, 0, raw.size) else null
                                    } catch (_: Exception) { null }
                                }
                            } else null
                            if (qrBitmap != null) {
                                Image(
                                    bitmap = qrBitmap.asImageBitmap(),
                                    contentDescription = "乘车码",
                                    modifier = Modifier.size(200.dp)
                                )
                            }

                            Divider()

                            Text("余额: ${qrCode.money ?: "未知"}", style = MaterialTheme.typography.bodyLarge)
                            Text("卡号: ${qrCode.trafficCardNo ?: "未知"}", style = MaterialTheme.typography.bodyMedium)
                            Text("有效期: ${qrCode.deadTime ?: qrCode.deadline ?: "未知"}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                // Ticket Stats
                val tickets = uiState.tickets?.data
                if (tickets != null) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text("地铁券统计", style = MaterialTheme.typography.titleMedium)
                            Divider()
                            Text("总获得: ${tickets.total ?: "0"}")
                            Text("已使用: ${tickets.used ?: "0"}")
                            Text("已过期: ${tickets.expire ?: "0"}")
                            Text("2元券: ${tickets.twoYuan ?: "0"}")
                            Text("4元券: ${tickets.fourYuan ?: "0"}")
                            Text("6元券: ${tickets.sixYuan ?: "0"}")
                        }
                    }
                }
            }
        }
    }
}

private fun hexStringToByteArray(hex: String): ByteArray {
    val len = hex.length
    val data = ByteArray(len / 2)
    var i = 0
    while (i < len) {
        data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
        i += 2
    }
    return data
}
