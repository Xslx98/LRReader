/*
 * Copyright 2016 Hippo Seven
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

@file:Suppress("UnnecessaryAbstractClass", "UseCheckOrError") // TODO(2.4-idiom)

package com.lanraragi.framework.scene

import android.app.Application
import android.util.SparseArray
import com.lanraragi.reader.util.IntIdGenerator

abstract class SceneApplication : Application() {

    private val idGenerator = IntIdGenerator()
    private val stageMap = SparseArray<StageActivity>()

    @JvmName("registerStageActivity")
    internal fun registerStageActivity(stage: StageActivity) {
        val id = idGenerator.nextId()
        stageMap.put(id, stage)
        stage.onRegister(id)
    }

    @JvmName("registerStageActivity")
    internal fun registerStageActivity(stage: StageActivity, id: Int) {
        if (stageMap.indexOfKey(id) >= 0) {
            throw IllegalStateException("The id exists: $id")
        }

        stageMap.put(id, stage)
        stage.onRegister(id)
    }

    internal fun unregisterStageActivity(id: Int) {
        val index = stageMap.indexOfKey(id)
        if (index >= 0) {
            val stage = stageMap.valueAt(index)
            stageMap.remove(id)
            stage.onUnregister()
        }
    }

    fun findStageActivityById(id: Int): StageActivity? = stageMap.get(id)
}
