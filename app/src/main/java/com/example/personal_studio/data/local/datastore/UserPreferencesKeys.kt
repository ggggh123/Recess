package com.example.personal_studio.data.local.datastore

import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

internal object UserPreferencesKeys {
    val API_KEY = stringPreferencesKey("api_key")
    val API_BASE_URL = stringPreferencesKey("api_base_url")
    val LLM_MODEL = stringPreferencesKey("llm_model")
    /** `ApiFormat.id` of the custom endpoint. */
    val API_FORMAT = stringPreferencesKey("api_format")
    /** `ReasoningLevel.id`. */
    val REASONING = stringPreferencesKey("llm_reasoning")
    val TEMPERATURE = floatPreferencesKey("llm_temperature")
    val MAX_TOKENS = intPreferencesKey("llm_max_tokens")
    val TOP_P = floatPreferencesKey("llm_top_p")
    /** 问答的自定义系统提示词；没有这个键表示用默认的。 */
    val CHAT_SYSTEM_PROMPT = stringPreferencesKey("chat_system_prompt")

    // Legacy keys — migrated to API_KEY on first launch, then ignored.
    val LEGACY_OPENROUTER_API_KEY = stringPreferencesKey("openrouter_api_key")
}
