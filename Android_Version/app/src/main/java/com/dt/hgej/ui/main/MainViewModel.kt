package com.dt.hgej.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import com.dt.hgej.data.api.ApiService
import com.dt.hgej.data.local.CredentialsSyncer
import com.dt.hgej.data.local.PreferencesManager
import com.dt.hgej.data.model.StoredAccount
import com.dt.hgej.data.model.UpdateInfo
import com.dt.hgej.data.model.UserConfig
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

data class MainUiState(
    val schedule: UserConfig = UserConfig(),
    val accounts: List<StoredAccount> = emptyList(),
    val activeLogin: String = "",
    val activeAccount: StoredAccount? = null,
    val userName: String = "",
    val remainIntegral: Int = 0,
    val logs: List<String> = emptyList(),
    val schedulerRunning: Boolean = false,
    val isDoingDailyTask: Boolean = false,
    val updateInfo: UpdateInfo? = null,
    val isDownloading: Boolean = false,
    val downloadProgress: Int = 0
) {
    /** 有账户即视为已登录 */
    val isLoggedIn: Boolean get() = accounts.isNotEmpty()

    /** 活动账户显示名：优先昵称，回退 loginName */
    val activeDisplayName: String
        get() = activeAccount?.name?.takeIf { it.isNotBlank() } ?: activeLogin
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val apiService = ApiService()
    private val taskRepository = TaskRepository(apiService)
    private val qrRepository = QrRepository(apiService)
    private val prefsManager = PreferencesManager(application)
    private val syncer = CredentialsSyncer(application)
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

    /** 记录上次已查询用户信息的账户，避免排程输入防抖触发重复网络请求 */
    private var lastQueriedLogin: String = ""

    /** 记录上次已填充凭据输入框的账户与会话（重登换新会话时也要刷新填充） */
    private var lastAppliedLogin: String = ""
    private var lastAppliedSesId: String = ""

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
        // schedule 状态单一所有者：全部写入都在本收集器内完成，杜绝并发协程互相覆盖。
        // 首次收集：读存储初始排程 + 填充当前账户凭据 + autoFill；此后仅在
        // loginName/ses_id 任一变化时刷新凭据填充（覆盖切换账户与重登换新两种场景）
        var initialized = false
        viewModelScope.launch {
            combine(
                prefsManager.accountsFlow,
                prefsManager.activeLoginFlow
            ) { accounts, active -> accounts to active }.collect { (accounts, active) ->
                val activeAcc = accounts.firstOrNull { it.loginName == active }
                    ?: accounts.firstOrNull()
                _uiState.value = _uiState.value.copy(
                    accounts = accounts,
                    activeAccount = activeAcc,
                    activeLogin = activeAcc?.loginName ?: ""
                )
                val login = activeAcc?.loginName ?: ""
                val ses = activeAcc?.sesId ?: ""

                if (!initialized) {
                    initialized = true
                    val stored = prefsManager.getSchedule()
                    _uiState.value = _uiState.value.copy(
                        schedule = stored.copy(
                            loginName = login,
                            sesId = ses,
                            userId = activeAcc?.userId ?: ""
                        )
                    )
                    lastAppliedLogin = login
                    lastAppliedSesId = ses
                    autoFillRunTime()
                } else if (login != lastAppliedLogin || ses != lastAppliedSesId) {
                    lastAppliedLogin = login
                    lastAppliedSesId = ses
                    _uiState.value = _uiState.value.copy(
                        schedule = _uiState.value.schedule.copy(
                            loginName = login,
                            sesId = ses,
                            userId = activeAcc?.userId ?: ""
                        )
                    )
                }

                if (login.isNotBlank() && login != lastQueriedLogin) {
                    lastQueriedLogin = login
                    refreshActiveUserInfo(activeAcc)
                }
                // 启动首次收集后：多账户时后台全量校验一次（失效标红，供菜单提示）
                if (!initialized) {
                    initialized = true
                    if (accounts.size > 1) validateAllAccounts()
                }
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

    /** 查询活动账户的用户信息（姓名/积分）；失效时持久化失效标记（菜单红色标注） */
    private suspend fun refreshActiveUserInfo(account: StoredAccount?) {
        if (account == null || account.sesId.isBlank()) {
            _uiState.value = _uiState.value.copy(userName = "", remainIntegral = 0)
            return
        }
        val info = try {
            apiService.getUserInfo(account.loginName, account.sesId)
        } catch (e: Exception) {
            addLog("获取用户信息失败: ${e.message}")
            null
        } ?: return
        if (info.result == "0") {
            val name = info.name ?: info.sensitive_name ?: ""
            val integral = info.remain_integral?.toIntOrNull() ?: 0
            _uiState.value = _uiState.value.copy(userName = name, remainIntegral = integral)
            if (account.invalid) {
                prefsManager.markAccountInvalid(account.loginName, false)
            }
            if (name.isNotBlank()) {
                // 查到昵称后回写账户（顶栏/列表显示用），不重置校验标记
                prefsManager.upsertAccount(
                    account.loginName, account.sesId, account.userId, name,
                    resetInvalid = false
                )
                if (integral > 0) {
                    addLog("当前账户: $name  积分: $integral")
                } else {
                    addLog("当前账户: $name")
                }
            }
        } else {
            _uiState.value = _uiState.value.copy(userName = "", remainIntegral = 0)
            prefsManager.markAccountInvalid(account.loginName, true)
            addLog("账户 ${displayName(account)} 会话已失效，请重新登录该账户")
        }
    }

    /** 全量校验：逐账户 U005 校验并持久化失效标记。启动时后台执行一次 */
    private var validatingAll = false
    private fun validateAllAccounts() {
        if (validatingAll) return
        validatingAll = true
        viewModelScope.launch {
            try {
                val accounts = prefsManager.accountsFlow.first().filter { it.sesId.isNotBlank() }
                if (accounts.size <= 1) {
                    // 单账户由活动账户查询覆盖
                    return@launch
                }
                addLog("正在校验全部 ${accounts.size} 个账户会话...")
                var invalidCount = 0
                for (acc in accounts) {
                    val info = try {
                        apiService.getUserInfo(acc.loginName, acc.sesId)
                    } catch (e: Exception) {
                        addLog("校验 ${displayName(acc)} 失败: ${e.message}")
                        continue
                    } ?: continue
                    if (info.result != "0") {
                        prefsManager.markAccountInvalid(acc.loginName, true)
                        invalidCount++
                        addLog("账户 ${displayName(acc)} 会话已失效，请重新登录")
                    } else {
                        if (acc.invalid) {
                            prefsManager.markAccountInvalid(acc.loginName, false)
                        }
                        // 顺带回写昵称（不重置校验标记由 markAccountInvalid 单独处理）
                        val name = info.name ?: info.sensitive_name ?: ""
                        if (name.isNotBlank() && name != acc.name) {
                            prefsManager.upsertAccount(
                                acc.loginName, acc.sesId, acc.userId, name,
                                resetInvalid = false
                            )
                        }
                    }
                }
                if (invalidCount > 0) {
                    addLog("校验完成：$invalidCount 个账户失效（账户菜单中红色标注），重新登录即可恢复")
                } else {
                    addLog("校验完成：全部账户会话有效")
                }
            } finally {
                validatingAll = false
            }
        }
    }

    private fun displayName(account: StoredAccount): String =
        account.name.takeIf { it.isNotBlank() } ?: account.loginName.take(8)

    fun autoFillRunTime() {
        val nextTime = Utils.getNextRunTime()
        updateConfig(_uiState.value.schedule.copy(runTime = nextTime))
        addLog("自动填入执行时间: $nextTime")
    }

    /** 手动粘贴凭据：作为账户加入/刷新并置为活动 */
    fun applyManualCredentials() {
        val schedule = _uiState.value.schedule
        if (schedule.loginName.isBlank() || schedule.sesId.isBlank()) {
            addLog("请先填写 login_name 和 ses_id")
            return
        }
        viewModelScope.launch {
            prefsManager.upsertAccount(schedule.loginName, schedule.sesId, schedule.loginName)
            addLog("账户 ${schedule.loginName.take(8)}… 已添加/更新并设为当前")
        }
    }

    private var configSaveJob: Job? = null

    /** 排程配置变更（仅落盘排程字段，凭据在账户列表中隔离） */
    fun updateConfig(config: UserConfig) {
        _uiState.value = _uiState.value.copy(schedule = config)
        // 输入过程每个字符都会触发，300ms 防抖后只落盘最后一次
        configSaveJob?.cancel()
        configSaveJob = viewModelScope.launch {
            delay(300)
            prefsManager.saveSchedule(config)
        }
    }

    /** 抢票：只执行当前活动账户（多账户仅保存/切换，执行始终单账户） */
    fun startExchange() {
        val acc = _uiState.value.activeAccount
        if (acc == null || acc.sesId.isBlank()) {
            addLog("没有可用账户（ses_id 为空），请先登录")
            return
        }
        if (acc.invalid) {
            addLog("账户 ${displayName(acc)} 会话已失效，请先重新登录该账户")
            return
        }
        if (_uiState.value.schedulerRunning) return
        val config = UserConfig(
            loginName = acc.loginName,
            sesId = acc.sesId,
            userId = acc.userId.ifBlank { acc.loginName },
            exchangeId = _uiState.value.schedule.exchangeId,
            runTime = _uiState.value.schedule.runTime,
            runCount = _uiState.value.schedule.runCount,
            timeSleep = _uiState.value.schedule.timeSleep
        )
        scheduler.start(config)
        addLog("启动兑换任务：当前账户 ${displayName(acc)}")
    }

    fun stopExchange() {
        scheduler.stop()
        addLog("停止兑换任务")
    }

    /** 每日任务：只执行当前活动账户 */
    fun doDailyTask() {
        val acc = _uiState.value.activeAccount
        if (acc == null || acc.sesId.isBlank()) {
            addLog("没有可用账户，请先登录")
            return
        }
        if (acc.invalid) {
            addLog("账户 ${displayName(acc)} 会话已失效，请先重新登录该账户")
            return
        }
        if (_uiState.value.isDoingDailyTask) return
        _uiState.value = _uiState.value.copy(isDoingDailyTask = true)

        viewModelScope.launch {
            val tag = displayName(acc)
            addLog("开始每日任务（当前账户: $tag）")
            val result = taskRepository.executeDailyWorkflow(
                acc.loginName,
                acc.sesId
            ) { msg -> addLog("[$tag] $msg") }
            result.onSuccess { addLog("[$tag] 每日任务完成") }
                .onFailure { addLog("[$tag] 每日任务失败: ${it.message}") }
            _uiState.value = _uiState.value.copy(isDoingDailyTask = false)
        }
    }

    fun getQrCode() {
        val acc = _uiState.value.activeAccount
        if (acc == null || acc.sesId.isBlank()) {
            addLog("请先登录")
            return
        }
        viewModelScope.launch {
            val result = qrRepository.getGreenTravelCode(acc.loginName, acc.sesId)
            result.onSuccess {
                addLog("乘车码获取成功")
            }.onFailure {
                addLog("乘车码失败: ${it.message}")
            }
        }
    }

    /** 切换活动账户（抢票任务针对旧账户集，切换前先停止） */
    fun switchAccount(loginName: String) {
        if (loginName == _uiState.value.activeLogin) return
        scheduler.stop()
        viewModelScope.launch {
            prefsManager.setActiveAccount(loginName)
            addLog("已切换到账户 ${loginName.take(8)}…")
        }
    }

    /** 移除当前活动账户；还有其他账户则自动切到剩余第一个，全部删完才是未登录。
     *  注意：只删本机，服务器凭据库保留该账户——青龙定时任务仍需继续运行 */
    fun logout() {
        scheduler.stop()
        val target = _uiState.value.activeLogin
        if (target.isBlank()) return
        viewModelScope.launch {
            val next = prefsManager.removeAccount(target)
            lastQueriedLogin = ""   // 允许新活动账户重新查询
            _uiState.value = _uiState.value.copy(remainIntegral = 0, userName = "")
            if (next != null) {
                addLog("已移除本机账户 ${target.take(8)}…（服务器凭据保留，定时任务不受影响），切换到 ${next.loginName.take(8)}…")
            } else {
                addLog("已移除本机账户 ${target.take(8)}…（服务器凭据保留），无剩余账户")
            }
        }
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
