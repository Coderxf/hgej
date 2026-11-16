package com.dt.hgej.ui.qrcode

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dt.hgej.data.api.ApiService
import com.dt.hgej.data.local.PreferencesManager
import com.dt.hgej.data.model.QrCodeResponse
import com.dt.hgej.data.model.StoredAccount
import com.dt.hgej.data.model.SubwayTicketResponse
import com.dt.hgej.repository.QrRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class QrUiState(
    val isLoading: Boolean = false,
    val qrCode: QrCodeResponse? = null,
    val tickets: SubwayTicketResponse? = null,
    val currentAwardType: String = "1",
    val error: String? = null,
    val activeAccount: StoredAccount? = null
) {
    /** 乘车码归属账户的展示名：优先昵称，回退 loginName */
    val accountLabel: String
        get() = activeAccount?.name?.takeIf { it.isNotBlank() }
            ?: activeAccount?.loginName?.take(16) ?: ""
}

class QrViewModel(application: Application) : AndroidViewModel(application) {

    private val apiService = ApiService()
    private val qrRepository = QrRepository(apiService)
    private val prefsManager = PreferencesManager(application)

    private val _uiState = MutableStateFlow(QrUiState())
    val uiState: StateFlow<QrUiState> = _uiState.asStateFlow()

    /** 记录已加载的账户，活动账户变化时自动重载（切换账户后乘车码/券跟着换人） */
    private var loadedLogin: String = ""

    init {
        viewModelScope.launch {
            combine(
                prefsManager.accountsFlow,
                prefsManager.activeLoginFlow
            ) { accounts, active -> accounts to active }.collect { (accounts, active) ->
                val activeAcc = accounts.firstOrNull { it.loginName == active }
                    ?: accounts.firstOrNull()
                if ((activeAcc?.loginName ?: "") != loadedLogin) {
                    loadData()
                }
            }
        }
    }

    fun loadData() {
        viewModelScope.launch {
            _uiState.value = QrUiState(
                isLoading = true,
                currentAwardType = _uiState.value.currentAwardType
            )
            val acc = prefsManager.activeAccount()
            loadedLogin = acc?.loginName ?: ""

            if (acc == null || acc.sesId.isBlank()) {
                _uiState.value = QrUiState(isLoading = false, error = "请先登录")
                return@launch
            }

            val qrResult = qrRepository.getGreenTravelCode(acc.loginName, acc.sesId)
            val ticketResult = qrRepository.getSubwayTickets(acc.loginName, acc.sesId, _uiState.value.currentAwardType)

            _uiState.value = QrUiState(
                isLoading = false,
                qrCode = qrResult.getOrNull(),
                tickets = ticketResult.getOrNull(),
                currentAwardType = _uiState.value.currentAwardType,
                error = if (qrResult.isFailure) qrResult.exceptionOrNull()?.message else null,
                activeAccount = acc
            )
        }
    }

    fun switchAwardType(awardType: String) {
        if (_uiState.value.currentAwardType == awardType) return
        _uiState.value = _uiState.value.copy(currentAwardType = awardType)
        viewModelScope.launch {
            val acc = prefsManager.activeAccount() ?: return@launch
            val result = qrRepository.getSubwayTickets(acc.loginName, acc.sesId, awardType)
            _uiState.value = _uiState.value.copy(
                tickets = result.getOrNull()
            )
        }
    }
}
