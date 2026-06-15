package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private val TARGET_SIZE_KB = intPreferencesKey("target_size_kb")
    private val GOOGLE_EMAIL = stringPreferencesKey("google_email")
    private val DRIVE_FOLDER_ID = stringPreferencesKey("drive_folder_id")
    private val DRIVE_FOLDER_NAME = stringPreferencesKey("drive_folder_name")
    private val ENABLE_AI_ANALYSIS = androidx.datastore.preferences.core.booleanPreferencesKey("enable_ai_analysis")
    private val NAME_BEFORE_TYPE = androidx.datastore.preferences.core.booleanPreferencesKey("name_before_type")
    private val SHOW_CONFIRMATION = androidx.datastore.preferences.core.booleanPreferencesKey("show_confirmation")

    val targetSizeKb: Flow<Int> = context.dataStore.data
        .map { preferences ->
            preferences[TARGET_SIZE_KB] ?: 500 // Default 500 KB
        }

    val enableAiAnalysis: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[ENABLE_AI_ANALYSIS] ?: true // Default to true
        }

    val showConfirmation: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[SHOW_CONFIRMATION] ?: true // Default to true
        }

    val nameBeforeType: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[NAME_BEFORE_TYPE] ?: true // Default to true (Name_Type)
        }

    val googleEmail: Flow<String?> = context.dataStore.data
        .map { preferences ->
            preferences[GOOGLE_EMAIL]
        }

    val driveFolderId: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[DRIVE_FOLDER_ID] ?: "root"
        }

    val driveFolderName: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[DRIVE_FOLDER_NAME] ?: "My Drive"
        }

    suspend fun setTargetSizeKb(size: Int) {
        context.dataStore.edit { preferences ->
            preferences[TARGET_SIZE_KB] = size
        }
    }

    suspend fun setEnableAiAnalysis(enable: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[ENABLE_AI_ANALYSIS] = enable
        }
    }

    suspend fun setShowConfirmation(show: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[SHOW_CONFIRMATION] = show
        }
    }

    suspend fun setNameBeforeType(nameBefore: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[NAME_BEFORE_TYPE] = nameBefore
        }
    }

    suspend fun setGoogleEmail(email: String?) {
        context.dataStore.edit { preferences ->
            if (email == null) {
                preferences.remove(GOOGLE_EMAIL)
            } else {
                preferences[GOOGLE_EMAIL] = email
            }
        }
    }

    suspend fun setDriveFolder(id: String, name: String) {
        context.dataStore.edit { preferences ->
            preferences[DRIVE_FOLDER_ID] = id
            preferences[DRIVE_FOLDER_NAME] = name
        }
    }
}

