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

import cc.ptoe.messenger.domain.model.Agent
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.agent_builtin_default_name
import cc.ptoe.messenger.generated.resources.agent_builtin_title_name
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentDisplayNameTest {

    @Test
    fun defaultAgentSeedNameIsLocalized() {
        assertEquals(
            Res.string.agent_builtin_default_name,
            builtinAgentNameResource(id = "a1", isDefault = true, name = Agent.DEFAULT_AGENT_NAME)
        )
        // The historical English seed value maps to the same resource.
        assertEquals(
            Res.string.agent_builtin_default_name,
            builtinAgentNameResource(id = "a1", isDefault = true, name = "Default Agent")
        )
    }

    @Test
    fun builtinTitleAgentSeedNameIsLocalized() {
        assertEquals(
            Res.string.agent_builtin_title_name,
            builtinAgentNameResource(
                id = Agent.BUILTIN_TITLE_AGENT_ID,
                isDefault = false,
                name = Agent.BUILTIN_TITLE_AGENT_NAME
            )
        )
    }

    @Test
    fun renamedBuiltinsAreShownVerbatim() {
        assertNull(builtinAgentNameResource(id = "a1", isDefault = true, name = "My Assistant"))
        assertNull(
            builtinAgentNameResource(
                id = Agent.BUILTIN_TITLE_AGENT_ID,
                isDefault = false,
                name = "My Titler"
            )
        )
    }

    @Test
    fun userAgentsAreNeverLocalizedEvenWhenTheyHoldTheTitleRole() {
        // A user Agent that took over the title role keeps its own name.
        assertNull(builtinAgentNameResource(id = "user-1", isDefault = false, name = "标题生成"))
        // ...and a regular Agent that happens to be named like the seed too.
        assertNull(builtinAgentNameResource(id = "user-2", isDefault = false, name = "默认 Agent"))
    }
}
