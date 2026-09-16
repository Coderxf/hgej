package com.dt.hgej.ui.qrcode

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dt.hgej.data.api.ApiService
import com.dt.hgej.data.local.PreferencesManager
import com.dt.hgej.data.model.QrCodeResponse
import com.dt.hgej.data.model.SubwayTicketResponse
import com.dt.hgej.repository.QrRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class QrUiState(
    val isLoading: Boolean = false,
    val qrCode: QrCodeResponse? = null,
    val tickets: SubwayTicketResponse? = null,
    val currentAwardType: String = "1",
    val error: String? = null
)

class QrViewModel(application: Application) : AndroidViewModel(application) {

    private val apiService = ApiService()
    private val qrRepository = QrRepository(apiService)
    private val prefsManager = PreferencesManager(application)

    private val _uiState = MutableStateFlow(QrUiState())
    val uiState: StateFlow<QrUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    fun loadData() {
        viewModelScope.launch {
            _uiState.value = QrUiState(
                isLoading = true,
                currentAwardType = _uiState.value.currentAwardType
            )
            val config = prefsManager.getConfig()

            if (config.loginName.isBlank() || config.sesId.isBlank()) {
                _uiState.value = QrUiState(isLoading = false, error = "请先登录")
                return@launch
            }

            val qrResult = qrRepository.getGreenTravelCode(config.loginName, config.sesId)
            val ticketResult = qrRepository.getSubwayTickets(config.loginName, config.sesId, _uiState.value.currentAwardType)

            _uiState.value = QrUiState(
                isLoading = false,
                qrCode = qrResult.getOrNull(),
                tickets = ticketResult.getOrNull(),
                currentAwardType = _uiState.value.currentAwardType,
                error = if (qrResult.isFailure) qrResult.exceptionOrNull()?.message else null
            )
        }
    }

    fun switchAwardType(awardType: String) {
        if (_uiState.value.currentAwardType == awardType) return
        _uiState.value = _uiState.value.copy(currentAwardType = awardType)
        viewModelScope.launch {
            val config = prefsManager.getConfig()
            val result = qrRepository.getSubwayTickets(config.loginName, config.sesId, awardType)
            _uiState.value = _uiState.value.copy(
                tickets = result.getOrNull()
            )
        }
    }
}
