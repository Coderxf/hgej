package com.dt.hgej.ui.login

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dt.hgej.data.api.ApiService
import com.dt.hgej.data.local.PreferencesManager
import com.dt.hgej.data.model.CaptchaResponse
import com.dt.hgej.repository.AuthRepository
import com.dt.hgej.util.Utils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LoginUiState(
    val phone: String = "",
    val password: String = "",
    val smsCode: String = "",
    val captchaCode: String = "",
    val captchaImage: ByteArray? = null,
    val captchaData: CaptchaResponse? = null,
    val isPasswordMode: Boolean = true,
    val isLoading: Boolean = false,
    val error: String? = null,
    val loginSuccess: Boolean = false,
    val smsSent: Boolean = false,
    val countdown: Int = 0
)

class LoginViewModel(application: Application) : AndroidViewModel(application) {

    private val apiService = ApiService()
    private val authRepository = AuthRepository(apiService)
    private val prefsManager = PreferencesManager(application)

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    init {
        fetchCaptcha()
    }

    fun fetchCaptcha() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null, captchaCode = "")
            val result = authRepository.getCaptcha()
            result.onSuccess { captcha ->
                val imgBytes = Utils.decodeBase64Image(captcha.img)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    captchaData = captcha,
                    captchaImage = imgBytes
                )
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message
                )
            }
        }
    }

    fun updatePhone(phone: String) {
        _uiState.value = _uiState.value.copy(phone = phone)
    }

    fun updatePassword(password: String) {
        _uiState.value = _uiState.value.copy(password = password)
    }

    fun updateCaptchaCode(code: String) {
        _uiState.value = _uiState.value.copy(captchaCode = code)
    }

    fun updateSmsCode(code: String) {
        _uiState.value = _uiState.value.copy(smsCode = code)
    }

    fun toggleMode() {
        val current = _uiState.value.isPasswordMode
        _uiState.value = _uiState.value.copy(isPasswordMode = !current)
    }

    fun login() {
        val state = _uiState.value
        if (state.phone.isBlank()) {
            _uiState.value = state.copy(error = "请输入手机号")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            if (state.isPasswordMode) {
                if (state.password.isBlank()) {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = "请输入密码")
                    return@launch
                }
                val result = authRepository.passwordLogin(
                    state.phone,
                    state.password,
                    state.captchaCode,
                    state.captchaData?.imgUniCode ?: ""
                )
                result.onSuccess { loginResp ->
                    prefsManager.saveLoginInfo(
                        loginResp.login_name ?: state.phone,
                        loginResp.ses_id ?: "",
                        loginResp.user_id ?: state.phone
                    )
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        loginSuccess = true
                    )
                }.onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = e.message
                    )
                }
            } else {
                if (!state.smsSent) {
                    if (state.captchaCode.isBlank()) {
                        _uiState.value = _uiState.value.copy(isLoading = false, error = "请输入验证码")
                        return@launch
                    }
                    val result = authRepository.sendSms(state.phone, state.captchaData, state.captchaCode)
                    result.onSuccess {
                        _uiState.value = _uiState.value.copy(smsSent = true, isLoading = false)
                        startCountdown()
                    }.onFailure { e ->
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = e.message
                        )
                    }
                } else {
                    if (state.smsCode.isBlank()) {
                        _uiState.value = _uiState.value.copy(isLoading = false, error = "请输入短信验证码")
                        return@launch
                    }
                    val result = authRepository.smsLogin(state.phone, state.smsCode)
                    result.onSuccess { loginResp ->
                        prefsManager.saveLoginInfo(
                            loginResp.login_name ?: state.phone,
                            loginResp.ses_id ?: "",
                            loginResp.user_id ?: state.phone
                        )
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            loginSuccess = true
                        )
                    }.onFailure { e ->
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = e.message
                        )
                    }
                }
            }
        }
    }

    private fun startCountdown() {
        viewModelScope.launch {
            for (i in 60 downTo 0) {
                _uiState.value = _uiState.value.copy(countdown = i)
                if (i == 0) {
                    _uiState.value = _uiState.value.copy(smsSent = false)
                    break
                }
                kotlinx.coroutines.delay(1000)
            }
        }
    }
}
