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

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Parcelable
import com.hippo.ehviewer.FavouriteStatusRouter
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.lib.yorozuya.IntIdGenerator
import com.hippo.widget.ContentLayout

@SuppressLint("UseSparseArrays")
abstract class GalleryInfoContentHelper : ContentLayout.ContentHelper<GalleryInfo>() {

    private var map: MutableMap<Long, GalleryInfo> = HashMap()
    private val listener: FavouriteStatusRouter.Listener

    init {
        listener = FavouriteStatusRouter.Listener { gid, slot ->
            val info = map[gid]
            if (info != null) {
                info.favoriteSlot = slot
            }
        }
        ServiceRegistry.dataModule.favouriteStatusRouter.addListener(listener)
    }

    fun destroy() {
        ServiceRegistry.dataModule.favouriteStatusRouter.removeListener(listener)
    }

    override fun onAddData(data: GalleryInfo) {
        map[data.gid] = data
    }

    override fun onAddData(data: List<GalleryInfo>) {
        for (info in data) {
            map[info.gid] = info
        }
    }

    override fun onRemoveData(data: GalleryInfo) {
        map.remove(data.gid)
    }

    override fun onRemoveData(data: List<GalleryInfo>) {
        for (info in data) {
            map.remove(info.gid)
        }
    }

    override fun onClearData() {
        map.clear()
    }

    override fun saveInstanceState(superState: Parcelable): Parcelable {
        val bundle = super.saveInstanceState(superState) as Bundle

        // KNOWN-ISSUE (P2): inherits ContentHelper's global-state design for data persistence
        val router = ServiceRegistry.dataModule.favouriteStatusRouter
        val id = router.saveDataMap(map)
        bundle.putInt(KEY_DATA_MAP, id)

        return bundle
    }

    override fun restoreInstanceState(state: Parcelable): Parcelable {
        val bundle = state as Bundle

        val id = bundle.getInt(KEY_DATA_MAP, IntIdGenerator.INVALID_ID)
        if (id != IntIdGenerator.INVALID_ID) {
            val router = ServiceRegistry.dataModule.favouriteStatusRouter
            val restoredMap = router.restoreDataMap(id)
            if (restoredMap != null) {
                this.map = restoredMap
            }
        }

        return super.restoreInstanceState(state)
    }

    companion object {
        private const val KEY_DATA_MAP = "data_map"
    }
}
