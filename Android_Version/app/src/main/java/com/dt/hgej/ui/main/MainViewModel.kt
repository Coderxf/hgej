package com.dt.hgej.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dt.hgej.data.api.ApiService
import com.dt.hgej.data.local.PreferencesManager
import com.dt.hgej.data.model.UserConfig
import com.dt.hgej.repository.QrRepository
import com.dt.hgej.repository.TaskRepository
import com.dt.hgej.service.ExchangeScheduler
import com.dt.hgej.util.Utils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MainUiState(
    val config: UserConfig = UserConfig(),
    val isLoggedIn: Boolean = false,
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
                if (config.runTime.isBlank()) {
                    autoFillRunTime()
                }
            }
        }
        viewModelScope.launch {
            prefsManager.isLoggedIn.collect { loggedIn ->
                _uiState.value = _uiState.value.copy(isLoggedIn = loggedIn)
            }
        }
        addLog("程序已启动")
    }

    fun autoFillRunTime() {
        val nextTime = Utils.getNextRunTime()
        val newConfig = _uiState.value.config.copy(runTime = nextTime)
        updateConfig(newConfig)
        addLog("自动填入执行时间: $nextTime")
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
            val result = qrRepository.getGreenTravelCode(config.userId, config.sesId)
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
                config = UserConfig()
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
