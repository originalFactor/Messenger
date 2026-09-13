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

package cc.ptoe.messenger.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import cc.ptoe.messenger.data.local.dao.AgentDao
import cc.ptoe.messenger.data.local.dao.ConversationDao
import cc.ptoe.messenger.data.local.dao.MessageDao
import cc.ptoe.messenger.data.local.dao.ModelDao
import cc.ptoe.messenger.data.local.dao.ProviderDao
import cc.ptoe.messenger.data.local.entity.AgentEntity
import cc.ptoe.messenger.data.local.entity.ConversationEntity
import cc.ptoe.messenger.data.local.entity.MessageEntity
import cc.ptoe.messenger.data.local.entity.ModelEntity
import cc.ptoe.messenger.data.local.entity.ProviderEntity

@Database(
    entities = [
        ProviderEntity::class,
        ModelEntity::class,
        AgentEntity::class,
        ConversationEntity::class,
        MessageEntity::class
    ],
    version = 12,
    exportSchema = false
)
abstract class MessengerDatabase : RoomDatabase() {
    abstract fun providerDao(): ProviderDao
    abstract fun modelDao(): ModelDao
    abstract fun agentDao(): AgentDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
}
