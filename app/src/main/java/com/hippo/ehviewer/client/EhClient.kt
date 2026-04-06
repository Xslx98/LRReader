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

import android.content.Context
import com.hippo.ehviewer.Analytics
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.client.exception.CancelledException
import com.hippo.lib.yorozuya.SimpleHandler
import com.hippo.util.IoThreadPoolExecutor
import okhttp3.Call
import okhttp3.OkHttpClient
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class EhClient(context: Context) {

    private val mRequestThreadPool = IoThreadPoolExecutor.instance
    private val mOkHttpClient: OkHttpClient = ServiceRegistry.networkModule.okHttpClient
    private val mImageOkHttpClient: OkHttpClient = ServiceRegistry.networkModule.imageOkHttpClient

    @Suppress("UNCHECKED_CAST")
    fun execute(request: EhRequest) {
        if (!request.isCancelled) {
            val task = Task(request.method, request.callback as Callback<Any?>)
            mRequestThreadPool.execute(task)
            request.task = task
        } else {
            request.callback?.onCancel()
        }
    }

    inner class Task(
        private val mMethod: Int,
        @Volatile private var mCallback: Callback<Any?>?
    ) : Runnable {

        private val mCall = AtomicReference<Call?>()
        private val mStop = AtomicBoolean()

        @Throws(CancelledException::class)
        fun setCall(call: Call) {
            if (mStop.get()) {
                throw CancelledException()
            } else {
                mCall.lazySet(call)
            }
        }

        fun stop() {
            if (!mStop.get()) {
                mStop.lazySet(true)

                mCallback?.let { callback ->
                    SimpleHandler.getInstance().post { callback.onCancel() }
                }

                mCall.get()?.cancel()

                mCallback = null
                mCall.lazySet(null)
            }
        }

        override fun run() {
            // LANraragi: E-Hentai engine methods removed -- no background work
            val result: Any? = null
            SimpleHandler.getInstance().post { onPostExecute(result) }
        }

        @Suppress("UNCHECKED_CAST")
        private fun onPostExecute(result: Any?) {
            mCallback?.let { callback ->
                if (result !is CancelledException) {
                    if (result is Throwable) {
                        callback.onFailure(result as Exception)
                        Analytics.recordException(result)
                    } else {
                        callback.onSuccess(result)
                    }
                }
            }

            mCallback = null
            mCall.lazySet(null)
        }
    }

    interface Callback<E> {
        fun onSuccess(result: E)
        fun onFailure(e: Exception)
        fun onCancel()
    }

    companion object {
        const val TAG: String = "EhClient"

        const val METHOD_SIGN_IN = 0
        const val METHOD_GET_GALLERY_LIST = 1
        const val METHOD_GET_GALLERY_DETAIL = 3
        const val METHOD_GET_PREVIEW_SET = 4
        const val METHOD_GET_RATE_GALLERY = 5
        const val METHOD_GET_COMMENT_GALLERY = 6
        const val METHOD_GET_GALLERY_TOKEN = 7
        const val METHOD_GET_FAVORITES = 8
        const val METHOD_ADD_FAVORITES = 9
        const val METHOD_ADD_FAVORITES_RANGE = 10
        const val METHOD_MODIFY_FAVORITES = 11
        const val METHOD_GET_TORRENT_LIST = 12
        const val METHOD_GET_TOP_LIST = 13
        const val METHOD_GET_PROFILE = 14
        const val METHOD_VOTE_COMMENT = 15
        const val METHOD_IMAGE_SEARCH = 16
        const val METHOD_ARCHIVE_LIST = 17
        const val METHOD_ARCHIVER = 27
        const val METHOD_DOWNLOAD_ARCHIVE = 18
        const val METHOD_DOWNLOAD_ARCHIVER = 28
        const val METHOD_ADD_TAG = 20
        const val METHOD_EDIT_WATCHED = 21
        const val METHOD_DELETE_WATCHED = 22
        const val METHOD_GET_WATCHED = 23
        const val METHOD_GET_NEWS = 24
        const val METHOD_GET_HOME = 25
        const val METHOD_RESET_LIMIT = 26
    }
}
