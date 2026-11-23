package com.dt.hgej.ui.qrcode

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dt.hgej.util.ScreenCapture
import com.dt.hgej.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrScreen(
    onBack: () -> Unit,
    viewModel: QrViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val activity = remember { context.findActivity() }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // 乘车码场景增强：屏幕常亮 + 临时最高亮度（扫码可读性），离开页面恢复
    DisposableEffect(Unit) {
        val window = activity?.window
        val oldBrightness = window?.attributes?.screenBrightness
        if (window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.attributes = window.attributes.apply { screenBrightness = 1f }
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (window != null && oldBrightness != null) {
                window.attributes = window.attributes.apply { screenBrightness = oldBrightness }
            }
        }
    }

    suspend fun doCapture() {
        val window = activity?.window ?: return
        val bitmap = ScreenCapture.captureWindow(window)
        if (bitmap == null) {
            snackbarHostState.showSnackbar("截图失败，请重试")
            return
        }
        val fileName = "绿色出行码_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val savedPath = withContext(Dispatchers.IO) {
            ScreenCapture.saveToGallery(context, bitmap, fileName)
        }
        snackbarHostState.showSnackbar(
            if (savedPath != null) "截图已保存到相册: $savedPath" else "保存失败，请检查存储空间"
        )
    }

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            scope.launch { doCapture() }
        } else {
            scope.launch { snackbarHostState.showSnackbar("未授予存储权限，无法保存截图") }
        }
    }

    fun onCaptureClick() {
        if (ScreenCapture.needsStoragePermission() &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            scope.launch { doCapture() }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                    IconButton(onClick = { onCaptureClick() }) {
                        Icon(Icons.Filled.PhotoCamera, contentDescription = "截图保存")
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
                if (qrCode == null) {
                    // 加载完成但无数据（上游偶发失败）：给出明确空态而非白屏
                    Text(
                        "暂未获取到乘车码，请点击右上角刷新重试",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (qrCode != null) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // 账户归属提示：明确当前乘车码属于哪个账户
                            if (uiState.accountLabel.isNotBlank()) {
                                Text(
                                    "当前账户: ${uiState.accountLabel}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
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
                            } else {
                                // 解码失败显式提示，不再白屏
                                Text(
                                    "乘车码数据异常，请点击右上角刷新重试",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }

                            // 有效期倒计时 + 过期自动刷新（动态码过期重取是官方 App 标准行为）
                            val deadSec = (qrCode.deadTime ?: qrCode.deadline)?.toLongOrNull()
                            if (deadSec != null && deadSec > 0) {
                                var remain by remember(deadSec) {
                                    mutableStateOf((deadSec - System.currentTimeMillis() / 1000).toInt())
                                }
                                var autoRefreshed by remember(deadSec) { mutableStateOf(false) }
                                LaunchedEffect(deadSec) {
                                    while (isActive && remain > 0) {
                                        delay(1000)
                                        remain = (deadSec - System.currentTimeMillis() / 1000).toInt()
                                    }
                                    if (remain <= 0 && !autoRefreshed && isActive) {
                                        autoRefreshed = true
                                        viewModel.loadData()   // 过期自动刷新一次
                                    }
                                }
                                if (remain > 0) {
                                    val color = if (remain <= 30) MaterialTheme.colorScheme.error
                                                else MaterialTheme.colorScheme.onSurfaceVariant
                                    Text(
                                        "剩余有效 " + if (remain >= 60) "${remain / 60}分${remain % 60}秒" else "${remain}秒",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = color
                                    )
                                } else if (!autoRefreshed) {
                                    Text(
                                        "已过期，正在自动刷新...",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }

                            Divider()

                            Text("余额: ${qrCode.money ?: "未知"}", style = MaterialTheme.typography.bodyLarge)
                            Text("卡号: ${qrCode.trafficCardNo ?: "未知"}", style = MaterialTheme.typography.bodyMedium)
                            Text("有效期: ${Utils.formatTimestamp(qrCode.deadTime ?: qrCode.deadline)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                // Metro Coupons
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("地铁优惠券", style = MaterialTheme.typography.titleMedium)
                        Divider()

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            listOf("1" to "2元券", "2" to "4元券", "3" to "6元券").forEach { (type, label) ->
                                FilterChip(
                                    selected = uiState.currentAwardType == type,
                                    onClick = { viewModel.switchAwardType(type) },
                                    label = { Text(label) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        val ticketResp = uiState.tickets
                        if (ticketResp != null && ticketResp.result == "0") {
                            Text("2元券: ${ticketResp.num_2 ?: "0"}  4元券: ${ticketResp.num_4 ?: "0"}  6元券: ${ticketResp.num_6 ?: "0"}")
                            Text("总获得: ${ticketResp.totalObtained}  已使用: ${ticketResp.use_num ?: "0"}  已过期: ${ticketResp.expire_num ?: "0"}")
                            Divider()

                            val records = ticketResp.recordList
                            if (!records.isNullOrEmpty()) {
                                records.forEach { record ->
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                                        )
                                    ) {
                                        Column(modifier = Modifier.padding(8.dp)) {
                                            Text("券名称: ${record.exchange_name ?: record.award_name ?: record.award_type ?: "-"}", style = MaterialTheme.typography.bodySmall)
                                            Text("领取时间: ${record.create_time ?: "-"}", style = MaterialTheme.typography.bodySmall)
                                            if (record.expire_time != null) {
                                                Text("过期时间: ${record.expire_time}", style = MaterialTheme.typography.bodySmall)
                                            }
                                            Text("状态: ${record.use_state_name ?: if (record.use_state == "1") "未使用" else if (record.use_state == "2") "已使用" else record.use_state ?: "-"}", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            } else {
                                Text("暂无优惠券记录", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
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
