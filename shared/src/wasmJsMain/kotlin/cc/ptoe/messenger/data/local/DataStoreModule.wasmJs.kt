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
import androidx.datastore.core.okio.WebOpfsStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import okio.Path

/**
 * Preferences live in the browser's origin-private file system, so settings
 * survive reloads. The session-scoped web actual of the preferences
 * DataStore would lose them, and localStorage's 5 MB quota is not where a
 * chat app's preferences belong.
 *
 * [filesDir] is intentionally unused: OPFS has a single origin-private root,
 * so there is no directory to resolve.
 */
actual fun createMessengerDataStore(filesDir: Path): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(
        storage = WebOpfsStorage(
            serializer = PreferencesSerializer,
            name = "messenger_preferences"
        )
    )
