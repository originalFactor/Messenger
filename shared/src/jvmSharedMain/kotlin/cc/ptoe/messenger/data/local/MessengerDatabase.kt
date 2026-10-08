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
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
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
import cc.ptoe.messenger.data.local.dao.ProjectDao
import cc.ptoe.messenger.data.local.entity.ProjectEntity

@Database(
    entities = [
        ProviderEntity::class,
        ModelEntity::class,
        AgentEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        ProjectEntity::class
    ],
    version = 21,
    exportSchema = false
)
abstract class MessengerDatabase : RoomDatabase() {
    abstract fun providerDao(): ProviderDao
    abstract fun modelDao(): ModelDao
    abstract fun agentDao(): AgentDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun projectDao(): ProjectDao

    companion object {
        /**
         * v14：models 表新增模型能力字段（输入/输出模态、工具调用、思考、JSON 输出、temperature 支持）。
         * 新增列须带默认值以满足 SQLite 对已有行的 NOT NULL 约束。
         */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(connection: SQLiteConnection) {
                connection.prepare(
                    "ALTER TABLE models ADD COLUMN inputModalities TEXT NOT NULL DEFAULT 'text'"
                ).step()
                connection.prepare(
                    "ALTER TABLE models ADD COLUMN outputModalities TEXT NOT NULL DEFAULT 'text'"
                ).step()
                connection.prepare(
                    "ALTER TABLE models ADD COLUMN supportsToolCalling INTEGER NOT NULL DEFAULT 0"
                ).step()
                connection.prepare(
                    "ALTER TABLE models ADD COLUMN supportsThinking INTEGER NOT NULL DEFAULT 0"
                ).step()
                connection.prepare(
                    "ALTER TABLE models ADD COLUMN supportsJsonOutput INTEGER NOT NULL DEFAULT 0"
                ).step()
                connection.prepare(
                    "ALTER TABLE models ADD COLUMN supportsTemperature INTEGER NOT NULL DEFAULT 0"
                ).step()
            }
        }

        /**
         * v15：agents 表新增 role 角色列（chat=聊天 / title=标题生成），
         * 支撑内置标题生成智能体（builtin-title-agent）。
         */
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(connection: SQLiteConnection) {
                connection.prepare(
                    "ALTER TABLE agents ADD COLUMN role TEXT NOT NULL DEFAULT 'chat'"
                ).step()
            }
        }

        /**
         * v16：agents 表新增 toolsEnabled 工具开关列（是否随请求声明内置工具）。
         */
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(connection: SQLiteConnection) {
                connection.prepare(
                    "ALTER TABLE agents ADD COLUMN toolsEnabled INTEGER NOT NULL DEFAULT 0"
                ).step()
            }
        }

        /**
         * v17：agents 表新增每工具开关列——toolsFollowDefault（是否跟随默认
         * Agent 的工具配置）+ toolsConfig（Map<String,Boolean> 的 JSON，键为工具
         * 函数名；空串 = 默认全开，缺失键视为开启）。
         */
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(connection: SQLiteConnection) {
                connection.prepare(
                    "ALTER TABLE agents ADD COLUMN toolsFollowDefault INTEGER NOT NULL DEFAULT 0"
                ).step()
                connection.prepare(
                    "ALTER TABLE agents ADD COLUMN toolsConfig TEXT NOT NULL DEFAULT ''"
                ).step()
            }
        }

        /**
         * v18：conversations 表新增会话级设置列——overrideToolsEnabled（工具总
         * 开关的会话级覆盖，null = 跟随 Agent 生效值）+ writable（本会话的
         * Agent 只读/可写模式，取代原 DataStore 全局记忆）。
         */
        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(connection: SQLiteConnection) {
                connection.prepare(
                    "ALTER TABLE conversations ADD COLUMN overrideToolsEnabled INTEGER"
                ).step()
                connection.prepare(
                    "ALTER TABLE conversations ADD COLUMN writable INTEGER NOT NULL DEFAULT 0"
                ).step()
            }
        }

        /**
         * v19：conversations 表新增 overrideToolsConfig（每工具开关会话级覆盖的
         * JSON，null = 跟随 Agent 的每工具配置）。该列晚于 v18 发布，故独立迁移。
         */
        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(connection: SQLiteConnection) {
                connection.prepare(
                    "ALTER TABLE conversations ADD COLUMN overrideToolsConfig TEXT"
                ).step()
            }
        }

        /**
         * v20：agents 表新增 description 描述列（用户可读的一句话介绍，
         * 列表/编辑页展示用，不随请求发给模型）。
         */
        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(connection: SQLiteConnection) {
                connection.prepare(
                    "ALTER TABLE agents ADD COLUMN description TEXT NOT NULL DEFAULT ''"
                ).step()
            }
        }

        /**
         * v21：新增 projects 表（项目即工作区）+ conversations.projectId 列。
         * 删除项目时 projectId 置空，会话保留为普通会话（与 Rust store 的
         * `ON DELETE SET NULL` 同语义）。
         */
        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(connection: SQLiteConnection) {
                connection.prepare(
                    """
                    CREATE TABLE IF NOT EXISTS projects (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        workspace TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                ).step()
                connection.prepare("ALTER TABLE conversations ADD COLUMN projectId TEXT").step()
                connection.prepare(
                    "CREATE INDEX IF NOT EXISTS index_conversations_projectId ON conversations(projectId)"
                ).step()
            }
        }
    }
}
