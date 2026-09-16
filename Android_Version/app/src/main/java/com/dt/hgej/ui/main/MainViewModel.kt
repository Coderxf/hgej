package com.dt.hgej.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dt.hgej.data.api.ApiService
import com.dt.hgej.data.local.PreferencesManager
import com.dt.hgej.data.model.UserConfig
import com.dt.hgej.data.model.UserInfoResponse
import com.dt.hgej.repository.QrRepository
import com.dt.hgej.repository.TaskRepository
import com.dt.hgej.service.ExchangeScheduler
import com.dt.hgej.util.Utils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class MainUiState(
    val config: UserConfig = UserConfig(),
    val isLoggedIn: Boolean = false,
    val userName: String = "",
    val remainIntegral: Int = 0,
    val logs: List<String> = emptyList(),
    val schedulerRunning: Boolean = false,
    val isDoingDailyTask: Boolean = false
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

    init {
        viewModelScope.launch {
            prefsManager.userConfig.collect { config ->
                _uiState.value = _uiState.value.copy(config = config)
            }
        }
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
    }

    private suspend fun queryUserInfo(config: UserConfig? = null) {
        val cfg = config ?: _uiState.value.config
        if (cfg.loginName.isBlank() || cfg.sesId.isBlank()) return
        val info = apiService.getUserInfo(cfg.loginName, cfg.sesId)
        if (info != null && info.result == "0") {
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

    fun updateConfig(config: UserConfig) {
        _uiState.value = _uiState.value.copy(config = config)
        viewModelScope.launch {
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
