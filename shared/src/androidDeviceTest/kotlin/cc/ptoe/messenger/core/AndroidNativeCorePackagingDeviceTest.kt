/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.core

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.file.Files
import java.util.Comparator
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies the native library selected for the device-test APK can actually execute. */
class AndroidNativeCorePackagingDeviceTest {
    @Test fun uniffiOpensStoreUsingPackagedNativeLibrary() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "native-core-package-")
        val store = directory.resolve("core.db")
        try {
            CoreHandle.open(store.toString(), null, 1L).use { }
            BundledSQLiteDriver().open(store.toString()).use { connection ->
                connection.prepare("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'agents'").use { query ->
                    assertTrue("Rust Core did not initialize its store", query.step())
                }
            }
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}
