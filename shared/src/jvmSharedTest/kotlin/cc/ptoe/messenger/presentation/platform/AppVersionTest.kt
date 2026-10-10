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

package cc.ptoe.messenger.presentation.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole project reports one version scheme: the semantic version in the
 * repository-root `VERSION` file as the name, the git commit count as the
 * code. Android/Wear/Runtime get both from the manifest, Desktop/Web from the
 * generated constants these tests read.
 */
class AppVersionTest {

    @Test
    fun theVersionNameIsASemanticVersion() {
        val parts = APP_VERSION_NAME.split(".")
        assertEquals("VERSION must be MAJOR.MINOR.PATCH: $APP_VERSION_NAME", 3, parts.size)
        for (part in parts) {
            assertTrue(
                "each VERSION component must be numeric: $APP_VERSION_NAME",
                part.isNotEmpty() && part.all { it.isDigit() },
            )
        }
    }

    @Test
    fun theVersionCodeIsAPositiveCommitCount() {
        // The root build script derives it from `git rev-list --count HEAD`;
        // a checked-out build therefore always has one. Zero would mean the
        // generated constant was never populated.
        assertTrue("expected a positive commit count, got $APP_VERSION_CODE", APP_VERSION_CODE > 0)
    }
}
