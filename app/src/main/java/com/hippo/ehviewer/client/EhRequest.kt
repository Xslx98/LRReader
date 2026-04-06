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

package com.hippo.ehviewer.client

class EhRequest {

    var method: Int = 0
        private set
    var args: Array<out Any?>? = null
        private set
    var callback: EhClient.Callback<*>? = null
        private set

    @JvmField
    var task: EhClient.Task? = null

    private var mCancel = false

    fun setMethod(method: Int): EhRequest {
        this.method = method
        return this
    }

    fun setArgs(vararg args: Any?): EhRequest {
        this.args = args
        return this
    }

    fun setCallback(callback: EhClient.Callback<*>?): EhRequest {
        this.callback = callback
        return this
    }

    fun cancel() {
        if (!mCancel) {
            mCancel = true
            task?.stop()
            task = null
        }
    }

    val isCancelled: Boolean
        get() = mCancel
}
