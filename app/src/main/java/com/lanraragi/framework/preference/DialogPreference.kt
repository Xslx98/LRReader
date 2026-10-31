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

package com.lanraragi.framework.preference

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.preference.Preference
import com.lanraragi.reader.R

/**
 * A base class for [Preference] objects that are
 * dialog-based. These preferences will, when clicked, open a dialog showing the
 * actual preference controls.
 */
abstract class DialogPreference : Preference, DialogInterface.OnClickListener, DialogInterface.OnDismissListener {

    private var mBuilder: AlertDialog.Builder? = null

    private var mDialogTitle: CharSequence? = null
    private var mDialogIcon: Drawable? = null
    private var mPositiveButtonText: CharSequence? = null
    private var mNegativeButtonText: CharSequence? = null
    private var mDialogLayoutResId = 0

    /** The dialog, if it is showing. */
    private var mDialog: AlertDialog? = null

    /** Which button was clicked. */
    private var mWhichButtonClicked = 0

    constructor(context: Context) : super(context) {
        init(context, null, 0, 0)
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        init(context, attrs, 0, 0)
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        init(context, attrs, defStyleAttr, 0)
    }

    private fun init(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) {
        val a = context.obtainStyledAttributes(attrs, R.styleable.DialogPreference, defStyleAttr, defStyleRes)
        // Fallback on the regular title of the preference (the one that is seen in the list)
        mDialogTitle = a.getString(R.styleable.DialogPreference_dialogTitle) ?: title
        mDialogIcon = a.getDrawable(R.styleable.DialogPreference_dialogIcon)
        mPositiveButtonText = a.getString(R.styleable.DialogPreference_positiveButtonText)
        mNegativeButtonText = a.getString(R.styleable.DialogPreference_negativeButtonText)
        mDialogLayoutResId = a.getResourceId(R.styleable.DialogPreference_dialogLayout, mDialogLayoutResId)
        a.recycle()
    }

    /** Sets the title of the dialog. This will be shown on subsequent dialogs. */
    fun setDialogTitle(dialogTitle: CharSequence?) {
        mDialogTitle = dialogTitle
    }

    fun setDialogTitle(dialogTitleResId: Int) {
        setDialogTitle(context.getString(dialogTitleResId))
    }

    /** Returns the title to be shown on subsequent dialogs. */
    fun getDialogTitle(): CharSequence? = mDialogTitle

    /** Sets the icon of the dialog. This will be shown on subsequent dialogs. */
    fun setDialogIcon(dialogIcon: Drawable?) {
        mDialogIcon = dialogIcon
    }

    /** Sets the icon (resource ID) of the dialog. This will be shown on subsequent dialogs. */
    fun setDialogIcon(@DrawableRes dialogIconRes: Int) {
        mDialogIcon = ContextCompat.getDrawable(context, dialogIconRes)
    }

    /** Returns the icon to be shown on subsequent dialogs. */
    fun getDialogIcon(): Drawable? = mDialogIcon

    /** Sets the text of the positive button of the dialog. This will be shown on subsequent dialogs. */
    fun setPositiveButtonText(positiveButtonText: CharSequence?) {
        mPositiveButtonText = positiveButtonText
    }

    fun setPositiveButtonText(@StringRes positiveButtonTextResId: Int) {
        setPositiveButtonText(context.getString(positiveButtonTextResId))
    }

    /** Returns the text of the positive button to be shown on subsequent dialogs. */
    fun getPositiveButtonText(): CharSequence? = mPositiveButtonText

    /** Sets the text of the negative button of the dialog. This will be shown on subsequent dialogs. */
    fun setNegativeButtonText(negativeButtonText: CharSequence?) {
        mNegativeButtonText = negativeButtonText
    }

    fun setNegativeButtonText(@StringRes negativeButtonTextResId: Int) {
        setNegativeButtonText(context.getString(negativeButtonTextResId))
    }

    /** Returns the text of the negative button to be shown on subsequent dialogs. */
    fun getNegativeButtonText(): CharSequence? = mNegativeButtonText

    /** Sets the layout resource that is inflated as the content View of subsequent dialogs. */
    fun setDialogLayoutResource(dialogLayoutResId: Int) {
        mDialogLayoutResId = dialogLayoutResId
    }

    /** Returns the layout resource that is used as the content View for subsequent dialogs. */
    fun getDialogLayoutResource(): Int = mDialogLayoutResId

    /**
     * Prepares the dialog builder to be shown when the preference is clicked.
     * Use this to set custom properties on the dialog.
     *
     * Do not [AlertDialog.Builder.create] or [AlertDialog.Builder.show].
     */
    protected open fun onPrepareDialogBuilder(builder: AlertDialog.Builder) {
    }

    override fun onClick() {
        if (mDialog?.isShowing == true) return

        showDialog(null)
    }

    /**
     * Shows the dialog associated with this Preference. This is normally initiated
     * automatically on clicking on the preference. Call this method if you need to
     * show the dialog on some other event.
     *
     * @param state Optional instance state to restore on the dialog
     */
    protected fun showDialog(state: Bundle?) {
        mWhichButtonClicked = DialogInterface.BUTTON_NEGATIVE

        val builder = AlertDialog.Builder(context)
            .setTitle(mDialogTitle)
            .setIcon(mDialogIcon)
            .setPositiveButton(mPositiveButtonText, this)
            .setNegativeButton(mNegativeButtonText, this)
        mBuilder = builder

        val contentView = onCreateDialogView()
        if (contentView != null) {
            onBindDialogView(contentView)
            builder.setView(contentView)
        }

        onPrepareDialogBuilder(builder)

        // Create the dialog
        val dialog = builder.create()
        mDialog = dialog
        if (state != null) {
            dialog.onRestoreInstanceState(state)
        }
        if (needInputMethod()) {
            requestInputMethod(dialog)
        }
        dialog.setOnDismissListener(this)
        dialog.show()

        onDialogCreated(dialog)
    }

    /**
     * Returns whether the preference needs to display a soft input method when the dialog
     * is displayed. Default is false. Subclasses should override this method if they need
     * the soft input method brought up automatically.
     */
    protected open fun needInputMethod(): Boolean = false

    /** Sets the required flags on the dialog window to enable input method window to show up. */
    private fun requestInputMethod(dialog: Dialog) {
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    }

    /**
     * Creates the content view for the dialog (if a custom content view is
     * required). By default, it inflates the dialog layout resource if it is set.
     */
    protected open fun onCreateDialogView(): View? {
        if (mDialogLayoutResId == 0) {
            return null
        }

        val builder = mBuilder ?: return null
        return LayoutInflater.from(builder.context).inflate(mDialogLayoutResId, null)
    }

    /** Binds views in the content View of the dialog to data. */
    protected open fun onBindDialogView(view: View) {}

    protected open fun onDialogCreated(dialog: AlertDialog) {}

    override fun onClick(dialog: DialogInterface, which: Int) {
        mWhichButtonClicked = which
    }

    override fun onDismiss(dialog: DialogInterface) {
        mDialog = null
        onDialogClosed(mWhichButtonClicked == DialogInterface.BUTTON_POSITIVE)
    }

    /**
     * Called when the dialog is dismissed and should be used to save data to
     * the SharedPreferences.
     *
     * @param positiveResult Whether the positive button was clicked (true), or
     * the negative button was clicked or the dialog was canceled (false).
     */
    protected abstract fun onDialogClosed(positiveResult: Boolean)

    /** Gets the dialog that is shown by this preference, or null if a dialog is not being shown. */
    fun getDialog(): Dialog? = mDialog

    override fun onDetached() {
        super.onDetached()
        val dialog = mDialog
        if (dialog == null || !dialog.isShowing) {
            return
        }

        dialog.dismiss()
    }

    override fun onSaveInstanceState(): Parcelable? {
        val superState = super.onSaveInstanceState()
        val dialog = mDialog
        if (dialog == null || !dialog.isShowing) {
            return superState
        }

        val myState = SavedState(superState)
        myState.isDialogShowing = true
        myState.dialogBundle = dialog.onSaveInstanceState()
        return myState
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state == null || state.javaClass != SavedState::class.java) {
            // Didn't save state for us in onSaveInstanceState
            super.onRestoreInstanceState(state)
            return
        }

        val myState = state as SavedState
        super.onRestoreInstanceState(myState.superState)
        if (myState.isDialogShowing) {
            showDialog(myState.dialogBundle)
        }
    }

    private class SavedState : BaseSavedState {
        var isDialogShowing = false
        var dialogBundle: Bundle? = null

        constructor(source: Parcel) : super(source) {
            isDialogShowing = source.readInt() == 1
            dialogBundle = source.readBundle(DialogPreference::class.java.classLoader)
        }

        constructor(superState: Parcelable?) : super(superState)

        override fun writeToParcel(dest: Parcel, flags: Int) {
            super.writeToParcel(dest, flags)
            dest.writeInt(if (isDialogShowing) 1 else 0)
            dest.writeBundle(dialogBundle)
        }

        companion object {
            @JvmField
            val CREATOR: Parcelable.Creator<SavedState> = object : Parcelable.Creator<SavedState> {
                override fun createFromParcel(source: Parcel): SavedState = SavedState(source)

                override fun newArray(size: Int): Array<SavedState?> = arrayOfNulls(size)
            }
        }
    }
}
