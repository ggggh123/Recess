package com.example.personal_studio.data.deep

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.SecureRandom
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

private val Context.deepPrefs by preferencesDataStore("deep-mode")

@Singleton
internal class DeepPrefs @Inject constructor(@ApplicationContext context: Context) {
    private val store = context.deepPrefs
    private val accepted = booleanPreferencesKey("privacy_accepted")
    private val device = stringPreferencesKey("device_key")
    private val chosenModel = stringPreferencesKey("choice_model")
    private val chosenEffort = stringPreferencesKey("choice_effort")
    val privacyAccepted = store.data.map { it[accepted] == true }

    /** 上次选的模型和推理等级（新开的深度对话和验算也用它）。 */
    val choice = store.data.map { p -> p[chosenModel]?.let { DeepChoice(it, p[chosenEffort]) } }

    suspend fun setChoice(choice: DeepChoice) {
        store.edit {
            it[chosenModel] = choice.model
            if (choice.effort == null) it.remove(chosenEffort) else it[chosenEffort] = choice.effort
        }
    }

    suspend fun accept() { store.edit { it[accepted] = true } }

    suspend fun deviceKey(): String {
        store.data.first()[device]?.let { return it }
        return checkNotNull(store.edit { prefs ->
            if (prefs[device] == null) {
                val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
                prefs[device] = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            }
        }[device])
    }
}
