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

package cc.ptoe.messenger.presentation.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.navigation.NavBackStackEntry

private const val TRANSITION_DURATION_MS = 350

/**
 * 页面相对位置（值越大越“靠后”）。切换页面时比较两个路由的顺序：
 * 目标页更靠后 = 前进（新页从右/下滑入），更靠前 = 返回（从左/上滑入）。
 * 顺序按底部导航分支分组：会话 → 智能体 → 设置，子页面跟随各自父分支。
 */
private val RouteOrders: Map<String, Int> = mapOf(
    Screen.Conversations.route to 0,
    Screen.Chat.route to 1,
    Screen.ConversationSettings.route to 2,
    Screen.ConversationRename.route to 3,
    Screen.Agents.route to 10,
    Screen.AgentEdit.route to 11,
    Screen.AgentMarket.route to 11,
    Screen.AgentMarketDetail.route to 12,
    Screen.Settings.route to 20,
    Screen.CloudSettings.route to 21,
    Screen.Licenses.route to 21,
    Screen.Providers.route to 21,
    Screen.ProviderDetail.route to 22,
    Screen.ProviderEdit.route to 22
)

/** 目标路由是否比起始路由更靠后（前进方向）。 */
private fun isForward(initialRoute: String?, targetRoute: String?): Boolean =
    navPageOrder(targetRoute) >= navPageOrder(initialRoute)

/** 路由的页面相对位置；未知路由按最靠前处理。 */
fun navPageOrder(route: String?): Int = route?.let { RouteOrders[it] } ?: 0

/**
 * NavHost 过渡动画：前进方向新页从右（Compact）/ 下（Rail 布局）滑入；
 * 后退方向从左 / 上滑入。
 */
fun navEnterTransition(vertical: Boolean): AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    if (isForward(initialState.destination.route, targetState.destination.route)) {
        if (vertical) {
            slideInVertically(tween(TRANSITION_DURATION_MS)) { it }
        } else {
            slideInHorizontally(tween(TRANSITION_DURATION_MS)) { it }
        }
    } else {
        if (vertical) {
            slideInVertically(tween(TRANSITION_DURATION_MS)) { -it }
        } else {
            slideInHorizontally(tween(TRANSITION_DURATION_MS)) { -it }
        }
    }
}

/** NavHost 过渡动画：被新页覆盖时向相反方向滑出（前进 = 滑向左/上）。 */
fun navExitTransition(vertical: Boolean): AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    if (isForward(initialState.destination.route, targetState.destination.route)) {
        if (vertical) {
            slideOutVertically(tween(TRANSITION_DURATION_MS)) { -it }
        } else {
            slideOutHorizontally(tween(TRANSITION_DURATION_MS)) { -it }
        }
    } else {
        if (vertical) {
            slideOutVertically(tween(TRANSITION_DURATION_MS)) { it }
        } else {
            slideOutHorizontally(tween(TRANSITION_DURATION_MS)) { it }
        }
    }
}

/**
 * 返回（pop）时露出的上一页：滑入并渐显。Android 上导航库会把该过渡
 * 接到预测性返回手势上，跟手播放。
 */
fun navPopEnterTransition(vertical: Boolean): AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    if (isForward(initialState.destination.route, targetState.destination.route)) {
        if (vertical) {
            slideInVertically(tween(TRANSITION_DURATION_MS)) { it } + fadeIn(tween(TRANSITION_DURATION_MS))
        } else {
            slideInHorizontally(tween(TRANSITION_DURATION_MS)) { it } + fadeIn(tween(TRANSITION_DURATION_MS))
        }
    } else {
        if (vertical) {
            slideInVertically(tween(TRANSITION_DURATION_MS)) { -it } + fadeIn(tween(TRANSITION_DURATION_MS))
        } else {
            slideInHorizontally(tween(TRANSITION_DURATION_MS)) { -it } + fadeIn(tween(TRANSITION_DURATION_MS))
        }
    }
}

/**
 * 被返回手势/返回键弹出的页：划出（滑向右/下）并渐淡——与页内二级页
 * （见 presentation/platform/BackHandler）的预测性返回设计一致。
 */
fun navPopExitTransition(vertical: Boolean): AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    if (isForward(initialState.destination.route, targetState.destination.route)) {
        if (vertical) {
            slideOutVertically(tween(TRANSITION_DURATION_MS)) { -it } + fadeOut(tween(TRANSITION_DURATION_MS))
        } else {
            slideOutHorizontally(tween(TRANSITION_DURATION_MS)) { -it } + fadeOut(tween(TRANSITION_DURATION_MS))
        }
    } else {
        if (vertical) {
            slideOutVertically(tween(TRANSITION_DURATION_MS)) { it } + fadeOut(tween(TRANSITION_DURATION_MS))
        } else {
            slideOutHorizontally(tween(TRANSITION_DURATION_MS)) { it } + fadeOut(tween(TRANSITION_DURATION_MS))
        }
    }
}
