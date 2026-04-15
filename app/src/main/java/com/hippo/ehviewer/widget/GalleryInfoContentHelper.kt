/*
 * Copyright 2019 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer.widget

import android.os.Parcelable
import com.hippo.widget.ContentLayout
import com.lanraragi.reader.domain.Archive

abstract class GalleryInfoContentHelper : ContentLayout.ContentHelper<Archive>() {

    override fun onAddData(data: Archive) {
        // No-op: Archive is a value type, no map tracking needed
    }

    override fun onAddData(data: List<Archive>) {
        // No-op
    }

    override fun onRemoveData(data: Archive) {
        // No-op
    }

    override fun onRemoveData(data: List<Archive>) {
        // No-op
    }

    override fun onClearData() {
        // No-op
    }

    override fun saveInstanceState(superState: Parcelable): Parcelable {
        return super.saveInstanceState(superState)
    }

    override fun restoreInstanceState(state: Parcelable): Parcelable {
        return super.restoreInstanceState(state)
    }
}
