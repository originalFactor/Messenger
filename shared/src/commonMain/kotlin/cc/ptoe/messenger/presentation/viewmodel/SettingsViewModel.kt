/*
 * Copyright 2026 ECSDevs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cc.ptoe.messenger.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewModelScope
import kotlin.reflect.KClass
import cc.ptoe.messenger.data.local.AppPreferences
import cc.ptoe.messenger.data.local.ThemePreferences
import cc.ptoe.messenger.data.cloud.CloudSyncResult
import cc.ptoe.messenger.data.cloud.CloudSyncRepository
import cc.ptoe.messenger.data.cloud.CloudCardPreview
import cc.ptoe.messenger.data.cloud.CloudLoginOutcome
import cc.ptoe.messenger.data.cloud.CloudRedeemResponse
import cc.ptoe.messenger.presentation.theme.ThemeMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import cc.ptoe.messenger.di.AppContainerHolder

class SettingsViewModel(
    private val themePreferences: ThemePreferences,
    private val appPreferences: AppPreferences,
    private val cloudSyncRepository: CloudSyncRepository
) : ViewModel() {

    val themeMode = themePreferences.themeMode
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ThemeMode.SYSTEM
        )

    val userAvatar = appPreferences.userAvatar
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    val cloudUser = cloudSyncRepository.user
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val cloudServerUrl = cloudSyncRepository.serverUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "https://messenger.ptoe.cc")
    val cloudSyncError = cloudSyncRepository.syncError

    fun setCloudServerUrl(url: String, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch { onResult(runCatching { cloudSyncRepository.setServerUrl(url) }) }
    }

    fun login(email: String, password: String, serverUrl: String, onResult: (Result<CloudLoginOutcome>) -> Unit) {
        viewModelScope.launch {
            onResult(runCatching {
                withContext(Dispatchers.IO) {
                    cloudSyncRepository.login(email, password, serverUrl)
                        .also { cloudSyncRepository.setServerUrl(serverUrl) }
                }
            })
        }
    }

    fun register(email: String, password: String, serverUrl: String, onResult: (Result<CloudLoginOutcome>) -> Unit) {
        viewModelScope.launch {
            onResult(runCatching {
                withContext(Dispatchers.IO) {
                    cloudSyncRepository.register(email, password, serverUrl)
                        .also { cloudSyncRepository.setServerUrl(serverUrl) }
                }
            })
        }
    }

    fun logout(onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch { onResult(runCatching { cloudSyncRepository.logout() }) }
    }

    fun completeLogin(
        outcome: CloudLoginOutcome,
        useLocalData: Boolean,
        onResult: (Result<CloudSyncResult>) -> Unit
    ) {
        viewModelScope.launch {
            onResult(runCatching {
                withContext(Dispatchers.IO) {
                    cloudSyncRepository.completeLogin(useLocalData)
                }
            })
        }
    }

    fun changePassword(currentPassword: String, newPassword: String, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            onResult(runCatching {
                withContext(Dispatchers.IO) {
                    cloudSyncRepository.changePassword(currentPassword, newPassword)
                }
            })
        }
    }

    fun deleteAccount(currentPassword: String, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            onResult(runCatching {
                withContext(Dispatchers.IO) {
                    cloudSyncRepository.deleteAccount(currentPassword)
                }
            })
        }
    }

    fun previewRedeemCard(code: String, onResult: (Result<CloudCardPreview>) -> Unit) {
        viewModelScope.launch {
            onResult(runCatching {
                withContext(Dispatchers.IO) {
                    cloudSyncRepository.previewRedeemCard(code)
                }
            })
        }
    }

    fun redeemCard(code: String, onResult: (Result<CloudRedeemResponse>) -> Unit) {
        viewModelScope.launch {
            onResult(runCatching {
                withContext(Dispatchers.IO) {
                    cloudSyncRepository.redeemCard(code)
                }
            })
        }
    }

    fun upload(serverUrl: String? = null, onResult: (Result<CloudSyncResult>) -> Unit) {
        viewModelScope.launch {
            onResult(runCatching {
                serverUrl?.let { cloudSyncRepository.setServerUrl(it) }
                cloudSyncRepository.upload()
            })
        }
    }

    fun restore(serverUrl: String? = null, onResult: (Result<CloudSyncResult>) -> Unit) {
        viewModelScope.launch {
            onResult(runCatching {
                serverUrl?.let { cloudSyncRepository.setServerUrl(it) }
                cloudSyncRepository.downloadAndRestore()
            })
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            themePreferences.setThemeMode(mode)
        }
    }

    fun setUserAvatar(avatar: String?) {
        viewModelScope.launch {
            appPreferences.setUserAvatar(avatar)
            if (cloudUser.value != null) {
                runCatching { cloudSyncRepository.uploadUserAvatar(avatar) }
            }
        }
    }

    fun clearAllData() {
        viewModelScope.launch {
            AppContainerHolder.instance.clearAllDataAndReinit()
        }
    }

    companion object {
        fun provideFactory(
            themePreferences: ThemePreferences,
            appPreferences: AppPreferences,
            cloudSyncRepository: CloudSyncRepository
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T {
                return SettingsViewModel(themePreferences, appPreferences, cloudSyncRepository) as T
            }
        }
    }
}
