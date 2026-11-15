package com.dt.hgej.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import com.dt.hgej.data.api.ApiService
import com.dt.hgej.data.local.PreferencesManager
import com.dt.hgej.data.model.UpdateInfo
import com.dt.hgej.data.model.UserConfig
import com.dt.hgej.data.model.UserInfoResponse
import com.dt.hgej.repository.QrRepository
import com.dt.hgej.repository.TaskRepository
import com.dt.hgej.service.ExchangeScheduler
import com.dt.hgej.update.UpdateManager
import com.dt.hgej.util.Utils
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

data class MainUiState(
    val config: UserConfig = UserConfig(),
    val isLoggedIn: Boolean = false,
    val userName: String = "",
    val remainIntegral: Int = 0,
    val logs: List<String> = emptyList(),
    val schedulerRunning: Boolean = false,
    val isDoingDailyTask: Boolean = false,
    val updateInfo: UpdateInfo? = null,
    val isDownloading: Boolean = false,
    val downloadProgress: Int = 0
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val apiService = ApiService()
    private val taskRepository = TaskRepository(apiService)
    private val qrRepository = QrRepository(apiService)
    private val prefsManager = PreferencesManager(application)
    private val scheduler = ExchangeScheduler(
        apiService,
        { msg -> addLog(msg) },
        { running ->
            _uiState.value = _uiState.value.copy(schedulerRunning = running)
        }
    )

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var pendingApkFile: File? = null

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                pendingApkFile?.let { file ->
                    if (file.exists()) {
                        file.delete()
                        addLog("已清理更新缓存")
                    }
                    pendingApkFile = null
                }
            }
        })
        // 配置不监听 DataStore 回显：防抖落盘后回发的旧值会覆盖用户正在输入的新值；
        // 登录后的配置刷新由下方 isLoggedIn 收集器负责
        viewModelScope.launch {
            prefsManager.isLoggedIn.collect { loggedIn ->
                _uiState.value = _uiState.value.copy(isLoggedIn = loggedIn)
                if (loggedIn) {
                    val config = prefsManager.getConfig()
                    _uiState.value = _uiState.value.copy(config = config)
                    queryUserInfo(config)
                }
            }
        }
        viewModelScope.launch {
            val config = prefsManager.getConfig()
            val loggedIn = prefsManager.isLoggedIn.first()
            autoFillRunTime()
            if (!loggedIn && config.loginName.isNotBlank() && config.sesId.isNotBlank()) {
                prefsManager.setLoggedIn(true)
                addLog("检测到已有凭证，自动登录")
            }
        }
        addLog("程序已启动")
        checkUpdate()
    }

    /**
     * 启动时检查更新，有新版本时弹窗提示，检查失败静默忽略
     */
    private fun checkUpdate() {
        viewModelScope.launch {
            val info = UpdateManager.checkForUpdate(getApplication())
            if (info != null) {
                _uiState.value = _uiState.value.copy(updateInfo = info)
                addLog("发现新版本: v${info.versionName ?: info.versionCode}")
            }
        }
    }

    fun dismissUpdate() {
        _uiState.value = _uiState.value.copy(updateInfo = null, isDownloading = false, downloadProgress = 0)
    }

    /**
     * 强制更新入口（版本号五连击触发）：忽略版本号比较，
     * 直接拉取服务器包走下载安装流程，供开发者重复安装同一版本测试
     */
    fun forceCheckUpdate() {
        viewModelScope.launch {
            addLog("强制检查更新（忽略版本号）...")
            val info = UpdateManager.checkForUpdate(getApplication(), force = true)
            if (info != null) {
                _uiState.value = _uiState.value.copy(updateInfo = info)
                addLog("已获取服务器版本: v${info.versionName ?: info.versionCode}，可在弹窗中下载安装")
            } else {
                addLog("强制更新失败：服务器配置不可用")
            }
        }
    }

    fun downloadAndInstall() {
        val info = _uiState.value.updateInfo ?: return
        val url = info.downloadUrl ?: return

        if (!UpdateManager.canRequestPackageInstalls(getApplication())) {
            UpdateManager.startInstallPermissionSetting(getApplication())
            addLog("请先允许安装未知来源应用")
            return
        }

        _uiState.value = _uiState.value.copy(isDownloading = true, downloadProgress = 0)
        addLog("开始下载新版本...")

        viewModelScope.launch {
            val file = UpdateManager.downloadApk(url, getApplication()) { progress ->
                _uiState.value = _uiState.value.copy(downloadProgress = progress)
            }
            if (file != null) {
                addLog("下载完成，准备安装")
                _uiState.value = _uiState.value.copy(isDownloading = false, downloadProgress = 100)
                pendingApkFile = file
                UpdateManager.installApk(getApplication(), file)
            } else {
                addLog("下载失败，请检查网络后重试")
                _uiState.value = _uiState.value.copy(isDownloading = false, downloadProgress = 0)
            }
        }
    }

    private suspend fun queryUserInfo(config: UserConfig? = null) {
        val cfg = config ?: _uiState.value.config
        if (cfg.loginName.isBlank() || cfg.sesId.isBlank()) return
        val info = try {
            apiService.getUserInfo(cfg.loginName, cfg.sesId)
        } catch (e: Exception) {
            addLog("获取用户信息失败: ${e.message}")
            null
        } ?: return
        if (info.result == "0") {
            val name = info.name ?: info.sensitive_name ?: ""
            val integral = info.remain_integral?.toIntOrNull() ?: 0
            _uiState.value = _uiState.value.copy(userName = name, remainIntegral = integral)
            if (name.isNotBlank()) {
                if (integral > 0) {
                    addLog("当前用户: $name  积分: $integral")
                } else {
                    addLog("当前用户: $name")
                }
            }
        } else {
            _uiState.value = _uiState.value.copy(isLoggedIn = false, userName = "", remainIntegral = 0)
            addLog("登录会话已失效，请重新登录")
            prefsManager.setLoggedIn(false)
        }
    }

    fun autoFillRunTime() {
        val nextTime = Utils.getNextRunTime()
        val newConfig = _uiState.value.config.copy(runTime = nextTime)
        updateConfig(newConfig)
        addLog("自动填入执行时间: $nextTime")
    }

    fun applyManualCredentials() {
        val config = _uiState.value.config
        if (config.loginName.isBlank() || config.sesId.isBlank()) {
            addLog("请先填写 login_name 和 ses_id")
            return
        }
        viewModelScope.launch {
            prefsManager.setLoggedIn(true)
            addLog("应用配置凭证成功")
        }
    }

    private var configSaveJob: Job? = null

    fun updateConfig(config: UserConfig) {
        _uiState.value = _uiState.value.copy(config = config)
        // 输入过程每个字符都会触发，300ms 防抖后只落盘最后一次
        configSaveJob?.cancel()
        configSaveJob = viewModelScope.launch {
            delay(300)
            prefsManager.saveConfig(config)
        }
    }

    fun startExchange() {
        val config = _uiState.value.config
        if (config.loginName.isBlank() || config.sesId.isBlank()) {
            addLog("请先登录或填写 ses_id")
            return
        }
        if (_uiState.value.schedulerRunning) return
        scheduler.start(config)
        addLog("启动兑换任务")
    }

    fun stopExchange() {
        scheduler.stop()
        addLog("停止兑换任务")
    }

    fun doDailyTask() {
        val config = _uiState.value.config
        if (config.loginName.isBlank() || config.sesId.isBlank()) {
            addLog("请先登录")
            return
        }
        if (_uiState.value.isDoingDailyTask) return
        _uiState.value = _uiState.value.copy(isDoingDailyTask = true)

        viewModelScope.launch {
            addLog("开始每日任务...")
            val result = taskRepository.executeDailyWorkflow(
                config.loginName,
                config.sesId
            ) { msg -> addLog(msg) }
            result.onSuccess { addLog("每日任务完成") }
                .onFailure { addLog("每日任务失败: ${it.message}") }
            _uiState.value = _uiState.value.copy(isDoingDailyTask = false)
        }
    }

    fun getQrCode() {
        val config = _uiState.value.config
        if (config.loginName.isBlank() || config.sesId.isBlank()) {
            addLog("请先登录")
            return
        }
        viewModelScope.launch {
            val result = qrRepository.getGreenTravelCode(config.loginName, config.sesId)
            result.onSuccess {
                addLog("乘车码获取成功")
            }.onFailure {
                addLog("乘车码失败: ${it.message}")
            }
        }
    }

    fun logout() {
        scheduler.stop()
        viewModelScope.launch {
            prefsManager.logout()
            _uiState.value = _uiState.value.copy(
                schedulerRunning = false,
                isDoingDailyTask = false,
                config = UserConfig(),
                remainIntegral = 0
            )
        }
        addLog("已退出登录")
    }

    fun clearLogs() {
        _uiState.value = _uiState.value.copy(logs = emptyList())
    }

    override fun onCleared() {
        // 兑换任务运行在独立协程作用域，退出页面时必须显式停止，
        // 否则任务会在 ViewModel 销毁后继续发请求
        scheduler.stop()
        super.onCleared()
    }

    private fun addLog(msg: String) {
        val time = Utils.currentTimeString()
        val entry = "[$time] $msg"
        val currentLogs = _uiState.value.logs.toMutableList()
        currentLogs.add(entry)
        if (currentLogs.size > 500) {
            currentLogs.removeAt(0)
        }
        _uiState.value = _uiState.value.copy(logs = currentLogs)
    }
}
