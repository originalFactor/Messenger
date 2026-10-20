/*
 * Copyright 2026 originalFactor
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
import cc.ptoe.messenger.domain.model.Provider
import cc.ptoe.messenger.domain.repository.ModelRepository
import cc.ptoe.messenger.domain.repository.ProviderRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ProviderWithModelCount(
    val provider: Provider,
    val modelCount: Int
)

class ProvidersViewModel(
    private val providerRepository: ProviderRepository,
    private val modelRepository: ModelRepository
) : ViewModel() {

    val providersWithModelCount = combine(
        providerRepository.getAll(),
        modelRepository.getAll()
    ) { providers, models ->
        providers.map { provider ->
            val modelCount = models.count { it.providerId == provider.id }
            ProviderWithModelCount(
                provider = provider,
                modelCount = modelCount
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    fun deleteProvider(providerId: String) {
        viewModelScope.launch {
            providerRepository.delete(providerId)
        }
    }

    companion object {
        fun provideFactory(
            providerRepository: ProviderRepository,
            modelRepository: ModelRepository
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T {
                return ProvidersViewModel(providerRepository, modelRepository) as T
            }
        }
    }
}
