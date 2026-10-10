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

import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.swing)
    // Main.kt constructs AppContainer (RoomDatabase.Builder / okio.Path / HttpClient leak
    // through shared's public desktop API) and configures the Coil ImageLoader directly.
    implementation(libs.coil3.compose)
    implementation(libs.coil3.network.ktor3)
    implementation(libs.okio)
    implementation(libs.ktor.client.core)
    implementation(libs.androidx.room.runtime)
}

compose.desktop {
    application {
        mainClass = "cc.ptoe.messenger.MainKt"
        jvmArgs("--add-opens", "java.desktop/sun.awt=ALL-UNNAMED")
        jvmArgs("--add-opens", "java.desktop/java.awt.peer=ALL-UNNAMED")
        if (System.getProperty("os.name").contains("Mac")) {
            jvmArgs("--add-opens", "java.desktop/sun.lwawt=ALL-UNNAMED")
            jvmArgs("--add-opens", "java.desktop/sun.lwawt.macosx=ALL-UNNAMED")
        }
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Messenger"
            // Same scheme as androidApp/wear/runtime: the semantic version name
            // from the repository-root VERSION file, with the git commit count as
            // the monotonic build component. jpackage requires dotted digits and
            // an increasing product version for upgrade ordering, so the MSI/Deb
            // product version is MAJOR.MINOR.<commitCount>, derived in the root
            // build script (rootProject.ext["packageVersion"]).
            packageVersion = rootProject.ext["packageVersion"] as String
            // Required runtime modules for the packaged JRE. Suggested by suggestModules, plus
            // java.sql/sqlite-jdbc, TLS EC certificates, DNS naming and desktop/AWT internals.
            modules(
                "java.instrument",
                "java.management",
                "java.naming",
                "java.security.jgss",
                "java.sql",
                "java.desktop",
                "jdk.crypto.ec",
                "jdk.unsupported",
            )
            windows {
                shortcut = true
                menu = true
                menuGroup = "Messenger"
                iconFile.set(project.file("src/main/resources/icon.ico"))
            }
        }
        // ProGuard (enabled by default for the release build type) fails because
        // OkHttp's optional platform classes (org.conscrypt, org.openjsse,
        // org.bouncycastle.jsse, org.graalvm) and Skiko's JBR adapter
        // (com.jetbrains.SharedTextures) are not on the Desktop classpath.
        // Disable it, mirroring the androidApp R8-disabled approach.
        buildTypes {
            release {
                proguard {
                    isEnabled.set(false)
                }
            }
        }
    }
}
