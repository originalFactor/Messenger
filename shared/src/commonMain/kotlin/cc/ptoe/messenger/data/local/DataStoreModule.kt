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
import androidx.datastore.preferences.core.Preferences
import okio.Path

/**
 * Creates the single preferences DataStore.
 *
 * Android/Desktop keep the on-disk path
 * `<filesDir>/datastore/messenger_preferences.preferences_pb` so installs
 * keep their settings across the KMP migration. Web stores the same
 * serialized preferences in the browser's origin-private file system.
 */
expect fun createMessengerDataStore(filesDir: Path): DataStore<Preferences>
