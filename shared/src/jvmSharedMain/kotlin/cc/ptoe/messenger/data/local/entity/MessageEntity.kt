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

package cc.ptoe.messenger.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("conversationId")]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String,
    /**
     * Plain-text projection. For multimodal messages this is the
     * concatenation of text parts only — image data is not inlined here.
     */
    val content: String,
    /**
     * JSON-encoded [ContentPart] list. Stored as text for forward
     * compatibility (new part types don't require a schema bump);
     * [MessageRepositoryImpl] handles the (de)serialization so callers
     * see the typed [cc.ptoe.messenger.domain.model.Message.parts]
     * list directly.
     *
     * Empty / null means the message only had text and [content] is
     * authoritative.
     */
    val partsJson: String? = null,
    val timestamp: Long,
    val status: String,
    val errorMessage: String? = null
)
