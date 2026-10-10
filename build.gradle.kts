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

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

/**
 * 从 .env 文件加载环境变量（不覆盖已设置的系统环境变量）
 */
fun loadDotEnv() {
    val envFile = rootDir.resolve(".env")
    if (!envFile.exists()) return
    envFile.readLines().forEach { line ->
        val trimmed = line.trim()
        if (trimmed.isBlank() || trimmed.startsWith("#")) return@forEach
        val eqIndex = trimmed.indexOf('=')
        if (eqIndex < 0) return@forEach
        val key = trimmed.substring(0, eqIndex).trim()
        val value = trimmed.substring(eqIndex + 1).trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")
        if (System.getenv(key) == null) {
            System.setProperty(key, value)
        }
    }
}

loadDotEnv()

/**
 * 版本方案：Android(androidApp/wear/runtime) / Desktop / Web / TUI 使用同一套
 * 语义化版本名 + git commit 计数版本号。
 *
 * - versionName: 仓库根目录 `VERSION` 文件里的语义化版本（MAJOR.MINOR.PATCH），
 *   五个客户端展示的是同一个字符串；本地构建可用 VERSION_NAME 环境变量覆盖
 * - versionCode: git commit 总数（单调递增，满足 Android 对版本号的整数要求），
 *   可用 VERSION_CODE 环境变量覆盖
 * - packageVersion: Desktop 原生分发包（jpackage）只接受纯数字点分版本，
 *   且必须逐次递增，因此为 `MAJOR.MINOR.<commitCount>`
 *   （1.0.0 的第 268 次提交 -> 1.0.268）
 *
 * 发布标签形如 `v1.0.0`，且必须与 VERSION 文件一致（release.yml 会校验）。
 * Rust 侧（messenger-core 的 build.rs）读同一个 VERSION 文件。
 */
fun getEnv(key: String): String? {
    return System.getenv(key) ?: System.getProperty(key)
}

private val SEMVER = Regex("""^\d+\.\d+\.\d+$""")
private val NUMERIC_DOTTED = Regex("""^\d+(\.\d+)*$""")

/** VERSION 文件是版本名的唯一来源；缺失或格式不对就直接失败，不静默回退。 */
fun versionNameFromFile(): String {
    val versionFile = layout.projectDirectory.file("VERSION")
    if (!versionFile.asFile.isFile) {
        throw GradleException(
            "VERSION file missing at ${versionFile.asFile} — it is versionName's single source of truth."
        )
    }
    // providers.fileContents 是配置缓存跟踪的读取方式：文件一变，配置缓存即失效。
    val text = providers.fileContents(versionFile).asText.get().trim()
    if (text.isEmpty()) {
        throw GradleException("VERSION file is empty at ${versionFile.asFile}.")
    }
    if (!SEMVER.matches(text)) {
        throw GradleException(
            "VERSION must contain a semantic version MAJOR.MINOR.PATCH, found \"$text\" in ${versionFile.asFile}."
        )
    }
    return text
}

fun computeVersionName(): String {
    val envValue = getEnv("VERSION_NAME")?.takeIf { it.isNotBlank() }
    return envValue ?: versionNameFromFile()
}

fun computeVersionCode(): Int {
    val envValue = getEnv("VERSION_CODE")
    if (envValue != null) {
        // 显式设置了却写错，比默默用 1 更危险（版本号会倒退到不可升级）。
        return envValue.toIntOrNull()?.takeIf { it > 0 }
            ?: throw GradleException("VERSION_CODE must be a positive integer, found \"$envValue\".")
    }
    return try {
        val output = providers.exec {
            commandLine("git", "rev-list", "--count", "HEAD")
            workingDir = rootDir
            isIgnoreExitValue = true
        }.standardOutput.asText.get().trim()
        output.toIntOrNull()?.takeIf { it > 0 } ?: 1
    } catch (e: Exception) {
        1
    }
}

/** jpackage 的 --app-version 只接受纯数字点分版本，去掉 -prerelease / +build 元数据。 */
fun computePackageVersion(versionName: String): String {
    val numeric = versionName.substringBefore('-').substringBefore('+').trim()
    if (!NUMERIC_DOTTED.matches(numeric)) {
        throw GradleException(
            "Cannot derive a numeric native-package version from versionName \"$versionName\": " +
                "jpackage requires dotted digits. Use a VERSION file / VERSION_NAME such as 1.0.0."
        )
    }
    return numeric
}

/**
 * Desktop 原生分发包的产品版本：`MAJOR.MINOR.<commitCount>`。
 *
 * 不能直接用语义版本名——MSI 的 ProductVersion 必须逐次递增，否则同版本号的
 * 新包无法作为升级安装（会提示 "another version is already installed"）。
 * commit 计数天然单调，且 `major.minor.<build>` 正好是 MSI 接受的形态
 * （major/minor ≤ 255，build ≤ 65535；超过 6 万次提交时构建会在此显式失败）。
 */
fun computeNativePackageVersion(versionName: String, versionCode: Int): String {
    val numeric = computePackageVersion(versionName)
    // 显式按分量取，不能用 substringAfter 的缺省回退：VERSION_NAME 若被覆盖成
    // 单段（"1"），substringAfter('.') 会返回整串，静默拼出错误的 1.1.N。
    val parts = numeric.split('.')
    if (parts.size < 2) {
        throw GradleException(
            "Native package versions need at least MAJOR.MINOR, but versionName \"$versionName\" " +
                "has only ${parts.size} numeric component(s)."
        )
    }
    for (part in parts.take(2)) {
        require(part.toIntOrNull()?.let { it <= 255 } == true) {
            "MSI product versions cap the major and minor fields at 255, found \"$part\" in \"$versionName\"."
        }
    }
    require(versionCode <= 65535) {
        "versionCode $versionCode exceeds the 65535 MSI build-component limit; " +
            "the Windows package version needs a different scheme at this point."
    }
    return "${parts[0]}.${parts[1]}.$versionCode"
}

val verName = computeVersionName()
val verCode = computeVersionCode()
ext["versionName"] = verName
ext["versionCode"] = verCode
ext["packageVersion"] = computeNativePackageVersion(verName, verCode)
ext["keystoreFile"] = rootDir.resolve("keyring/messenger-release.jks")
ext["keystorePassword"] = getEnv("KEYSTORE_PASSWORD") ?: ""
ext["keyAlias"] = getEnv("KEY_ALIAS") ?: "messenger"
ext["keyPassword"] = getEnv("KEY_PASSWORD") ?: ""

// No global exclude of the com.google.guava:listenablefuture:1.0 stub: the
// collision it historically guarded against (guava-18.0 via AndroidMath) is gone,
// and stripping the stub breaks apps that have no other provider of the
// com.google.common.util.concurrent.ListenableFuture interface —
// androidx.profileinstaller (pulled by Compose) extends it at runtime, so its
// App Startup initializer crashed the wear app with NoClassDefFoundError on launch.
// Full Guava only ever appears on build-time tooling classpaths (KSP/Room, lint),
// which never collide with the stub in AGP's checkDuplicateClasses.
