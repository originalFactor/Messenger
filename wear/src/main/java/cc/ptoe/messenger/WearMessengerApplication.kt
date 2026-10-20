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

package cc.ptoe.messenger

import android.app.Application
import cc.ptoe.messenger.data.WearBridgeClient
import cc.ptoe.messenger.data.WearChatPreferences
import cc.ptoe.messenger.data.WearChatRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class WearMessengerApplication : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var wearChatRepository: WearChatRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        wearChatRepository = WearChatRepository(
            preferences = WearChatPreferences(this),
            bridgeClient = WearBridgeClient(this, applicationScope),
            scope = applicationScope
        )
    }

    companion object {
        lateinit var instance: WearMessengerApplication
            private set
    }
}
