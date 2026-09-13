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

package cc.ptoe.messenger.presentation.utils

import kotlin.math.round

/** Locale-independent "%.1f" replacement (String.format is JVM-only). */
fun formatOneDecimal(value: Float): String {
    val rounded = round(value * 10f) / 10f
    val asInt = rounded.toInt()
    return if (rounded == asInt.toFloat()) "$asInt.0" else rounded.toString()
}

/** Double overload — Cloud sync documents store temperature/topP as Double. */
fun formatOneDecimal(value: Double): String = formatOneDecimal(value.toFloat())

/** Locale-independent "%.2f" replacement. */
fun formatTwoDecimals(value: Float): String {
    val rounded = round(value * 100f) / 100f
    val asInt = rounded.toInt()
    if (rounded == asInt.toFloat()) return "$asInt.00"
    val oneDecimal = formatOneDecimal(value)
    return if (oneDecimal.length == 3) "${oneDecimal}0" else rounded.toString()
}

/**
 * 上下文窗口 token 数的紧凑展示：272_000 → "272K"，1_000_000 → "1M"，
 * 1_500_000 → "1.5M"。0 = 未知/不限，返回空串（调用方据此隐藏标签）。
 */
fun formatContextWindow(tokens: Long): String {
    if (tokens <= 0L) return ""
    fun compact(value: Float): String = formatOneDecimal(value).removeSuffix(".0")
    val millions = tokens / 1_000_000f
    return if (millions >= 1f) "${compact(millions)}M" else "${compact(tokens / 1_000f)}K"
}
