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

package cc.ptoe.messenger.presentation.utils

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime

object DateTimeUtils {

    private val zone: TimeZone get() = TimeZone.currentSystemDefault()

    fun formatMessageTime(timestamp: Long): String {
        // 消息气泡左下角始终显示时间（HH:mm），具体日期由日期分隔符横幅展示
        val dt = Instant.fromEpochMilliseconds(timestamp).toLocalDateTime(zone)
        return "${dt.hour.pad2()}:${dt.minute.pad2()}"
    }

    fun isSameDay(a: Long, b: Long): Boolean {
        val da = Instant.fromEpochMilliseconds(a).toLocalDateTime(zone).date
        val db = Instant.fromEpochMilliseconds(b).toLocalDateTime(zone).date
        return da == db
    }

    /**
     * 用于消息列表日期分隔符显示：今天 / 昨天 / 具体日期
     */
    fun formatDateSeparator(timestamp: Long): String {
        val target = Instant.fromEpochMilliseconds(timestamp).toLocalDateTime(zone).date
        val today = Clock.System.now().toLocalDateTime(zone).date

        if (target == today) return "今天"
        if (target == today.minus(1, DateTimeUnit.DAY)) return "昨天"

        return if (target.year == today.year) {
            "${target.month.number}月${target.day}日"
        } else {
            "${target.year}年${target.month.number}月${target.day}日"
        }
    }

    private fun Int.pad2(): String = toString().padStart(2, '0')
}
