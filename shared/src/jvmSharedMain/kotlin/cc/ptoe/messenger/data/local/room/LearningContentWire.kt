/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.room

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Typed, strict Core v9 content schema; missing optional fields get the same defaults as Core. */
@Serializable
internal sealed interface LearningContentWire {
    @Serializable @SerialName("course") data class Course(val description: String = "") : LearningContentWire
    @Serializable @SerialName("knowledgePoint") data class KnowledgePoint(val parentId: String? = null, val description: String = "", val position: Long = 0) : LearningContentWire
    @Serializable @SerialName("item") data class Item(val itemKind: ItemKind, val question: String = "", val originalAnswer: String = "", val correctAnswer: String = "", val explanation: String = "", val errorReason: String = "") : LearningContentWire
    @Serializable @SerialName("material") data class Material(val materialKind: MaterialKind, val text: String = "", val url: String? = null, val assetId: String? = null, val artifactId: String? = null) : LearningContentWire
    @Serializable @SerialName("plan") data class Plan(val goal: String = "", val targetAt: Long? = null) : LearningContentWire
    @Serializable @SerialName("task") data class Task(val planId: String, val itemId: String? = null, val status: TaskStatus, val position: Long = 0, val dueAt: Long? = null) : LearningContentWire
    @Serializable @SerialName("reviewCard") data class ReviewCard(val itemId: String, val paused: Boolean = false) : LearningContentWire
}
@Serializable internal enum class ItemKind { @SerialName("mistake") MISTAKE, @SerialName("note") NOTE, @SerialName("exercise") EXERCISE }
@Serializable internal enum class MaterialKind { @SerialName("text") TEXT, @SerialName("image") IMAGE, @SerialName("file") FILE, @SerialName("link") LINK, @SerialName("artifact") ARTIFACT }
@Serializable internal enum class TaskStatus { @SerialName("pending") PENDING, @SerialName("inProgress") IN_PROGRESS, @SerialName("completed") COMPLETED }
