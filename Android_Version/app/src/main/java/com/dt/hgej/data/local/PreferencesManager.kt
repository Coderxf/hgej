package com.dt.hgej.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dt.hgej.data.model.StoredAccount
import com.dt.hgej.data.model.UserConfig
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "hgej_prefs")

/**
 * 凭据与配置存储。
 *
 * 多账户模型（v1.1.7 起）：
 * - ACCOUNTS_JSON：List<StoredAccount> 的 JSON 序列化，凭据按账户隔离
 * - ACTIVE_LOGIN：当前活动账户的 loginName
 * - 排程配置（EXCHANGE_ID/RUN_TIME/RUN_COUNT/TIME_SLEEP）全局共享，所有账户同一张排程表
 * - 旧版平铺单槽字段（LOGIN_NAME/SES_ID/USER_ID）仅在迁移时读取，迁移后不再写入
 */
class PreferencesManager(private val context: Context) {

    companion object Keys {
        // 旧版单账户字段（仅迁移读取）
        val LOGIN_NAME = stringPreferencesKey("login_name")
        val SES_ID = stringPreferencesKey("ses_id")
        val USER_ID = stringPreferencesKey("user_id")
        val EXCHANGE_ID = stringPreferencesKey("exchange_id")
        val RUN_TIME = stringPreferencesKey("run_time")
        val RUN_COUNT = stringPreferencesKey("run_count")
        val TIME_SLEEP = stringPreferencesKey("time_sleep")
        val TERM_ID = stringPreferencesKey("term_id")

        // 多账户字段
        val ACCOUNTS_JSON = stringPreferencesKey("accounts_json")
        val ACTIVE_LOGIN = stringPreferencesKey("active_login")
    }

    private val gson = Gson()
    private val accountsType = object : TypeToken<List<StoredAccount>>() {}.type

    /** 设备稳定 ID（16 位十六进制）：设备维度，多账户共享 */
    suspend fun getOrCreateTermId(): String {
        context.dataStore.data.first()[TERM_ID]?.takeIf { it.isNotBlank() }?.let { return it }
        val chars = "0123456789abcdef"
        val secureRandom = java.security.SecureRandom()
        val id = (1..16).map { chars[secureRandom.nextInt(chars.length)] }.joinToString("")
        context.dataStore.edit { prefs -> prefs[TERM_ID] = id }
        return id
    }

    // ================= 排程配置（全局共享） =================

    /** 排程配置（不含凭据）：loginName/sesId/userId 恒为空串，凭据从账户列表取 */
    val scheduleConfig: Flow<UserConfig> = context.dataStore.data.map { prefs ->
        UserConfig(
            exchangeId = prefs[EXCHANGE_ID] ?: "10",
            runTime = prefs[RUN_TIME] ?: "",
            runCount = prefs[RUN_COUNT] ?: "100",
            timeSleep = prefs[TIME_SLEEP] ?: "0.2"
        )
    }

    suspend fun saveSchedule(config: UserConfig) {
        context.dataStore.edit { prefs ->
            prefs[EXCHANGE_ID] = config.exchangeId
            prefs[RUN_TIME] = config.runTime
            prefs[RUN_COUNT] = config.runCount
            prefs[TIME_SLEEP] = config.timeSleep
        }
    }

    suspend fun getSchedule(): UserConfig = scheduleConfig.first()

    // ================= 账户列表 =================

    private suspend fun loadAccounts(prefs: Preferences): List<StoredAccount> {
        prefs[ACCOUNTS_JSON]?.let { json ->
            try {
                return gson.fromJson(json, accountsType) ?: emptyList()
            } catch (e: Exception) {
                return emptyList()
            }
        }
        // 一次性迁移：旧版平铺凭据 → 账户列表
        val legacyName = prefs[LOGIN_NAME] ?: ""
        val legacySes = prefs[SES_ID] ?: ""
        val legacyUid = prefs[USER_ID] ?: ""
        if (legacyName.isNotBlank() && legacySes.isNotBlank()) {
            return listOf(
                StoredAccount(
                    loginName = legacyName, userId = legacyUid.ifBlank { legacyName },
                    sesId = legacySes, savedAt = System.currentTimeMillis()
                )
            )
        }
        return emptyList()
    }

    private suspend fun persistAccounts(prefs: MutablePreferences, accounts: List<StoredAccount>, active: String?) {
        prefs[ACCOUNTS_JSON] = gson.toJson(accounts)
        prefs[ACTIVE_LOGIN] = active ?: ""
    }

    val accountsFlow: Flow<List<StoredAccount>> = context.dataStore.data.map { loadAccounts(it) }

    val activeLoginFlow: Flow<String> = context.dataStore.data.map { it[ACTIVE_LOGIN] ?: "" }

    /** 当前活动账户；无活动或活动项已被删除时回退到列表第一个 */
    suspend fun activeAccount(): StoredAccount? {
        val prefs = context.dataStore.data.first()
        val accounts = loadAccounts(prefs)
        if (accounts.isEmpty()) return null
        val active = prefs[ACTIVE_LOGIN]
        return accounts.firstOrNull { it.loginName == active } ?: accounts.first()
    }

    /**
     * 追加或刷新账户并置为活动：已有同名账户仅更新凭据/昵称，否则追加。
     * 登录成功（新会话必有效）走默认 resetInvalid=true 清除失效标记；
     * 仅回写昵称等场景传 false，避免误清校验结果。
     */
    suspend fun upsertAccount(
        loginName: String, sesId: String, userId: String,
        name: String = "", resetInvalid: Boolean = true
    ) {
        context.dataStore.edit { prefs ->
            val accounts = loadAccounts(prefs).toMutableList()
            val existing = accounts.firstOrNull { it.loginName == loginName }
            if (existing != null) {
                accounts[accounts.indexOf(existing)] = existing.copy(
                    sesId = sesId, userId = userId.ifBlank { existing.userId },
                    name = name.ifBlank { existing.name },
                    invalid = if (resetInvalid) false else existing.invalid,
                    savedAt = System.currentTimeMillis()
                )
            } else {
                accounts.add(
                    StoredAccount(
                        loginName = loginName, userId = userId.ifBlank { loginName },
                        sesId = sesId, name = name, savedAt = System.currentTimeMillis()
                    )
                )
            }
            persistAccounts(prefs, accounts, loginName)
        }
    }

    /** 标记账户会话失效/恢复（U005 校验结果持久化，供账户列表红色标注） */
    suspend fun markAccountInvalid(loginName: String, invalid: Boolean) {
        context.dataStore.edit { prefs ->
            val accounts = loadAccounts(prefs).toMutableList()
            val idx = accounts.indexOfFirst { it.loginName == loginName }
            if (idx >= 0 && accounts[idx].invalid != invalid) {
                accounts[idx] = accounts[idx].copy(invalid = invalid)
                persistAccounts(prefs, accounts, prefs[ACTIVE_LOGIN])
            }
        }
    }

    suspend fun setActiveAccount(loginName: String) {
        context.dataStore.edit { prefs ->
            if (loadAccounts(prefs).any { it.loginName == loginName }) {
                prefs[ACTIVE_LOGIN] = loginName
            }
        }
    }

    /** 删除账户；删的是活动账户时自动切到剩余列表第一个。返回删除后的活动账户（可为 null） */
    suspend fun removeAccount(loginName: String): StoredAccount? {
        var result: StoredAccount? = null
        context.dataStore.edit { prefs ->
            val remaining = loadAccounts(prefs).filterNot { it.loginName == loginName }
            result = remaining.firstOrNull()
            persistAccounts(prefs, remaining, result?.loginName)
        }
        return result
    }

    /** 兼容旧调用语义：是否还有账户（=是否处于登录态） */
    val isLoggedIn: Flow<Boolean> = accountsFlow.map { it.isNotEmpty() }
}
