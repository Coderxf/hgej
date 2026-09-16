package com.dt.hgej.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dt.hgej.data.model.UserConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "hgej_prefs")

class PreferencesManager(private val context: Context) {

    companion object Keys {
        val LOGIN_NAME = stringPreferencesKey("login_name")
        val SES_ID = stringPreferencesKey("ses_id")
        val USER_ID = stringPreferencesKey("user_id")
        val EXCHANGE_ID = stringPreferencesKey("exchange_id")
        val RUN_TIME = stringPreferencesKey("run_time")
        val RUN_COUNT = stringPreferencesKey("run_count")
        val TIME_SLEEP = stringPreferencesKey("time_sleep")
        val IS_LOGGED_IN = booleanPreferencesKey("is_logged_in")
    }

    val userConfig: Flow<UserConfig> = context.dataStore.data.map { prefs ->
        UserConfig(
            loginName = prefs[LOGIN_NAME] ?: "",
            sesId = prefs[SES_ID] ?: "",
            userId = prefs[USER_ID] ?: "",
            exchangeId = prefs[EXCHANGE_ID] ?: "10",
            runTime = prefs[RUN_TIME] ?: "",
            runCount = prefs[RUN_COUNT] ?: "100",
            timeSleep = prefs[TIME_SLEEP] ?: "0.08"
        )
    }

    val isLoggedIn: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[IS_LOGGED_IN] == true
    }

    suspend fun saveLoginInfo(loginName: String, sesId: String, userId: String) {
        context.dataStore.edit { prefs ->
            prefs[LOGIN_NAME] = loginName
            prefs[SES_ID] = sesId
            prefs[USER_ID] = userId
            prefs[IS_LOGGED_IN] = true
        }
    }

    suspend fun saveConfig(config: UserConfig) {
        context.dataStore.edit { prefs ->
            prefs[LOGIN_NAME] = config.loginName
            prefs[SES_ID] = config.sesId
            prefs[USER_ID] = config.userId
            prefs[EXCHANGE_ID] = config.exchangeId
            prefs[RUN_TIME] = config.runTime
            prefs[RUN_COUNT] = config.runCount
            prefs[TIME_SLEEP] = config.timeSleep
        }
    }

    suspend fun getConfig(): UserConfig = userConfig.first()

    suspend fun setLoggedIn(value: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[IS_LOGGED_IN] = value
        }
    }

    suspend fun logout() {
        context.dataStore.edit { prefs ->
            prefs[LOGIN_NAME] = ""
            prefs[SES_ID] = ""
            prefs[USER_ID] = ""
            prefs[IS_LOGGED_IN] = false
        }
    }
}
