/*
 * Copyright 2026 LR Reader
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

package com.hippo.scene

/**
 * Registry of Scene fragment factories, replacing reflection-based
 * Class.forName().newInstance() in StageActivity.
 */
object SceneFactory {
    private val factories = HashMap<String, () -> SceneFragment>()

    fun register(className: String, factory: () -> SceneFragment) {
        factories[className] = factory
    }

    fun create(className: String): SceneFragment {
        val factory = factories[className]
            ?: throw IllegalArgumentException(
                "Unknown scene class: $className. Did you forget to register it?"
            )
        return factory()
    }

    fun isRegistered(className: String): Boolean = factories.containsKey(className)
}
