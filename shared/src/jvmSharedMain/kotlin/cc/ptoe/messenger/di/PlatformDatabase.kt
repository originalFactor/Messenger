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

package cc.ptoe.messenger.di

import androidx.room.RoomDatabase
import cc.ptoe.messenger.data.local.MessengerDatabase

/**
 * The platform's Room database builder. Declared in the jvmShared intermediate
 * source set (which androidMain and desktopMain both extend) because only the
 * leaf targets know how to locate the file: Android goes through a Context,
 * Desktop through [AppDirs].
 */
internal expect fun platformDatabaseBuilder(appDirs: AppDirs): RoomDatabase.Builder<MessengerDatabase>
