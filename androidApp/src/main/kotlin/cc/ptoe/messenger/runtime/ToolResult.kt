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

import android.os.Parcel
import android.os.Parcelable

/** Result of one workspace operation crossing the AIDL boundary. */
data class ToolResult(
    val output: String,
    val isError: Boolean
) : Parcelable {
    constructor(parcel: Parcel) : this(
        parcel.readString() ?: "",
        parcel.readByte() != 0.toByte()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(output)
        parcel.writeByte(if (isError) 1 else 0)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<ToolResult> {
        override fun createFromParcel(parcel: Parcel): ToolResult = ToolResult(parcel)
        override fun newArray(size: Int): Array<ToolResult?> = arrayOfNulls(size)
    }
}
