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

package com.hippo.ehviewer.preference

import android.app.Dialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.os.Parcelable
import android.util.AttributeSet
import android.view.View
import android.widget.ProgressBar
import androidx.appcompat.app.AlertDialog
import androidx.customview.view.AbsSavedState
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.lib.yorozuya.IntIdGenerator
import com.hippo.preference.DialogPreference
import kotlinx.coroutines.launch

abstract class TaskPreference : DialogPreference {

    private var mTask: Task? = null
    private var mTaskId: Int = IntIdGenerator.INVALID_ID

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    override fun onPrepareDialogBuilder(builder: AlertDialog.Builder) {
        builder.setTitle(null)
        builder.setView(R.layout.preference_dialog_task)
        builder.setCancelable(false)
    }

    override fun onDialogCreated(dialog: AlertDialog) {
        if (mTask == null) {
            mTask = onCreateTask().also { task ->
                task.setPreference(this)
                mTaskId = (context.applicationContext as EhApplication).putGlobalStuff(task)
                task.launch()
            }
        }
    }

    protected fun onTaskEnd() {
        // Dismiss dialog
        dialog?.dismiss()
        // Clear async
        mTask = null
        mTaskId = IntIdGenerator.INVALID_ID
    }

    protected abstract fun onCreateTask(): Task

    override fun onDetached() {
        super.onDetached()
        mTask?.setPreference(null)
    }

    override fun onSaveInstanceState(): Parcelable? {
        val superState = super.onSaveInstanceState()
        val myState = SavedState(superState)
        myState.asyncTaskId = mTaskId
        return myState
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state == null || state.javaClass != SavedState::class.java) {
            // Didn't save state for us in onSaveInstanceState
            super.onRestoreInstanceState(state)
            return
        }

        val myState = state as SavedState
        mTaskId = myState.asyncTaskId
        if (IntIdGenerator.INVALID_ID != mTaskId) {
            val o = (context.applicationContext as EhApplication).getGlobalStuff(mTaskId)
            if (o is Task) {
                mTask = o
                mTask!!.setPreference(this)
            }
        }
        if (mTask == null) {
            mTaskId = IntIdGenerator.INVALID_ID
        }

        // EH-LEGACY: should show 'no reopen' dialog when no task is running

        super.onRestoreInstanceState(myState.superState)
    }

    private class SavedState : AbsSavedState {
        var asyncTaskId: Int = 0

        constructor(source: Parcel) : super(source, SavedState::class.java.classLoader) {
            asyncTaskId = source.readInt()
        }

        constructor(superState: Parcelable?) : super(superState!!)

        override fun writeToParcel(dest: Parcel, flags: Int) {
            super.writeToParcel(dest, flags)
            dest.writeInt(asyncTaskId)
        }

        companion object {
            @JvmField
            val CREATOR: Parcelable.Creator<SavedState> = object : Parcelable.Creator<SavedState> {
                override fun createFromParcel(source: Parcel): SavedState = SavedState(source)
                override fun newArray(size: Int): Array<SavedState?> = arrayOfNulls(size)
            }
        }
    }

    abstract class Task(context: Context) : Runnable {

        private val mApplication: EhApplication = context.applicationContext as EhApplication
        @Volatile
        private var mPreference: TaskPreference? = null
        @Volatile
        private var mCancelled: Boolean = false
        private val mMainHandler: Handler = Handler(Looper.getMainLooper())

        fun getApplication(): EhApplication = mApplication

        fun getPreference(): TaskPreference? = mPreference

        fun setPreference(preference: TaskPreference?) {
            mPreference = preference
        }

        fun isCancelled(): Boolean = mCancelled

        fun cancel() {
            mCancelled = true
        }

        /** Subclasses implement their work here. Runs on IO thread. */
        protected abstract fun doWork(): Any?

        /** Call from doWork() to update progress bar. Thread-safe. */
        @Suppress("unused")
        protected fun publishProgress(current: Int, total: Int) {
            mMainHandler.post { onProgressUpdate(current, total) }
        }

        private fun onProgressUpdate(current: Int, total: Int) {
            val pref = mPreference ?: return
            val dialog: Dialog = pref.dialog ?: return
            val bar = dialog.findViewById<ProgressBar>(R.id.task_progress) ?: return
            bar.visibility = View.VISIBLE
            if (total <= 0) {
                bar.isIndeterminate = true
            } else {
                bar.isIndeterminate = false
                val clamped = current.coerceIn(0, total)
                bar.max = total
                bar.progress = clamped
            }
        }

        /**
         * Called on the UI thread after doWork() completes.
         * Subclasses may override to handle the result (e.g. show a Toast).
         * Must call super to ensure cleanup.
         */
        @Suppress("unused")
        protected open fun onPostExecute(result: Any?) {
            mApplication.removeGlobalStuff(this)
            mPreference?.onTaskEnd()
        }

        override fun run() {
            val result = doWork()
            mMainHandler.post { onPostExecute(result) }
        }

        /** Launch this task on the application-wide IO coroutine scope. */
        fun launch() {
            ServiceRegistry.coroutineModule.ioScope.launch { run() }
        }
    }
}
