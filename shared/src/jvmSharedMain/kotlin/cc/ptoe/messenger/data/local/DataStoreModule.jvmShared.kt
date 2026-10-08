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

package cc.ptoe.messenger.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import okio.FileSystem
import okio.Path
import okio.SYSTEM

/**
 * Android + Desktop preferences DataStore, on the same on-disk path the
 * pre-KMP app used: `<filesDir>/datastore/messenger_preferences.preferences_pb`.
 * The path and serializer must not change, or existing installs lose their
 * settings.
 */
actual fun createMessengerDataStore(filesDir: Path): DataStore<Preferences> {
    val file = filesDir.resolve("datastore").resolve("messenger_preferences.preferences_pb")
    return PreferenceDataStoreFactory.create(
        storage = OkioStorage(
            fileSystem = FileSystem.SYSTEM,
            serializer = PreferencesSerializer,
            producePath = { file }
        )
    )
}
