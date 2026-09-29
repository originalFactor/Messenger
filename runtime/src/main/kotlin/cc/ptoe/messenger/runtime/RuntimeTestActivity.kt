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

package cc.ptoe.messenger.runtime

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * Manual probe: runs a few commands through [TermuxRuntime] in this app's
 * own process so the SELinux domain behavior (legacy untrusted_app_27 exec
 * rights) can be verified without the main app installed.
 */
class RuntimeTestActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = TextView(this).apply {
            setPadding(48, 48, 48, 48)
            typeface = Typeface.MONOSPACE
            textSize = 13f
            text = "Preparing runtime…"
        }
        setContentView(ScrollView(this).apply { addView(text) })

        thread {
            val result = try {
                val r = kotlinx.coroutines.runBlocking {
                    TermuxRuntime.execute(
                        context = this@RuntimeTestActivity,
                        command = "id; uname -a; ls",
                        timeoutMs = 120_000L,
                        workingDir = null,
                        onOutput = null
                    )
                }
                "exit=${r.exitCode}\n${r.output}"
            } catch (t: Throwable) {
                "ERROR: ${t.javaClass.simpleName}: ${t.message}"
            }
            runOnUiThread { text.text = result }
        }
    }
}
