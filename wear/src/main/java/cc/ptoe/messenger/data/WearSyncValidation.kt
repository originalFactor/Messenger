/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data

import org.json.JSONArray
import org.json.JSONObject

/** Reject malformed snapshots before any cached watch state is replaced. */
internal fun validateWearSyncSnapshot(agents: JSONArray, conversations: JSONArray, messages: JSONObject, complete: Boolean) {
    fun requiredId(item: JSONObject, key: String): String =
        (item.opt(key) as? String)?.takeIf { it.isNotBlank() } ?: error("Invalid sync $key")
    fun requiredString(item: JSONObject, key: String) {
        check(item.opt(key) is String) { "Invalid sync $key" }
    }
    fun requiredBoolean(item: JSONObject, key: String) {
        check(item.opt(key) is Boolean) { "Invalid sync $key" }
    }
    fun requiredTimestamp(item: JSONObject, key: String) {
        val value = item.opt(key) as? Number ?: error("Invalid sync $key")
        val number = value.toDouble()
        check(number.isFinite() && number == value.toLong().toDouble()) { "Invalid sync $key" }
    }

    val agentIds = mutableSetOf<String>()
    for (index in 0 until agents.length()) {
        val agent = agents.opt(index) as? JSONObject ?: error("Invalid sync agent")
        check(agentIds.add(requiredId(agent, "id"))) { "Duplicate sync agent" }
        requiredString(agent, "name")
        requiredBoolean(agent, "isDefault")
        requiredBoolean(agent, "isReady")
        if (agent.has("hasDefaultModel")) requiredBoolean(agent, "hasDefaultModel")
    }
    val conversationIds = mutableSetOf<String>()
    for (index in 0 until conversations.length()) {
        val conversation = conversations.opt(index) as? JSONObject ?: error("Invalid sync conversation")
        val id = requiredId(conversation, "id")
        requiredId(conversation, "agentId")
        requiredString(conversation, "title")
        requiredTimestamp(conversation, "createdAt")
        requiredTimestamp(conversation, "updatedAt")
        if (conversation.has("lastMessage")) {
            val preview = conversation.opt("lastMessage")
            check(preview == JSONObject.NULL || preview is String) { "Invalid sync lastMessage" }
        }
        check(conversationIds.add(id)) { "Duplicate sync conversation" }
        if (complete) check(messages.opt(id) is JSONArray) { "Incomplete sync messages" }
    }
    val keys = messages.keys()
    while (keys.hasNext()) {
        val conversationId = keys.next()
        check(conversationId.isNotBlank()) { "Invalid sync message conversation" }
        val rows = messages.opt(conversationId) as? JSONArray ?: error("Invalid sync message batch")
        val rowIds = mutableSetOf<String>()
        for (index in 0 until rows.length()) {
            val row = rows.opt(index) as? JSONObject ?: error("Invalid sync message")
            check(rowIds.add(requiredId(row, "id"))) { "Duplicate sync message" }
            val role = requiredId(row, "role")
            check(role in setOf("user", "assistant", "tool", "system")) { "Invalid sync message role" }
            requiredString(row, "content")
            requiredTimestamp(row, "timestamp")
            requiredBoolean(row, "isPending")
            requiredBoolean(row, "isError")
            if (complete) check(row.has("conversationId")) { "Missing sync message conversation" }
            if (row.has("conversationId")) {
                check(requiredId(row, "conversationId") == conversationId) { "Sync message conversation mismatch" }
            }
        }
    }
}
