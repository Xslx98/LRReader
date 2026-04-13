package com.hippo.ehviewer.ui.scene

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.textfield.TextInputEditText
import com.hippo.ehviewer.R
import com.hippo.ehviewer.dao.ServerProfile
import com.lanraragi.reader.client.api.LRRAuthManager
import com.lanraragi.reader.client.api.LRRUrlHelper

/**
 * Extracted dialog construction for [ServerListScene].
 * Builds add/edit/delete/options dialogs for server profiles,
 * delegating business logic to [ServerListViewModel].
 *
 * This is a stateless helper — constructed with context references
 * and the ViewModel, then called to show dialogs.
 */
internal class ServerListDialogHelper(
    private val contextProvider: () -> Context?,
    private val activityProvider: () -> FragmentActivity?,
    private val stringProvider: (Int) -> String,
    private val stringFormatProvider: (Int, Any) -> String,
    private val viewModel: ServerListViewModel
) {

    // -------------------------------------------------------------------------
    // Profile options (long-press)
    // -------------------------------------------------------------------------

    fun showProfileOptions(
        profile: ServerProfile,
        position: Int,
        onEdit: (ServerProfile, Int) -> Unit
    ) {
        val ctx = contextProvider() ?: return

        val options = arrayOf(
            stringProvider(R.string.lrr_edit_server),
            stringProvider(R.string.lrr_delete_server)
        )

        AlertDialog.Builder(ctx)
            .setTitle(profile.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> onEdit(profile, position)
                    1 -> showDeleteConfirmation(profile)
                }
            }
            .show()
    }

    // -------------------------------------------------------------------------
    // Delete confirmation
    // -------------------------------------------------------------------------

    fun showDeleteConfirmation(profile: ServerProfile) {
        val ctx = contextProvider() ?: return

        AlertDialog.Builder(ctx)
            .setTitle(R.string.lrr_delete_server)
            .setMessage(stringFormatProvider(R.string.lrr_delete_server_confirm, profile.name))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                viewModel.deleteProfile(profile)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // -------------------------------------------------------------------------
    // Edit dialog
    // -------------------------------------------------------------------------

    fun showEditDialog(profile: ServerProfile, position: Int) {
        val ctx = contextProvider() ?: return

        val dialogView = LayoutInflater.from(ctx).inflate(R.layout.dialog_edit_server, null)
        val nameEdit = dialogView.findViewById<TextInputEditText>(R.id.edit_server_name)
        val urlEdit = dialogView.findViewById<TextInputEditText>(R.id.edit_server_url)
        val apiKeyEdit = dialogView.findViewById<TextInputEditText>(R.id.edit_api_key)
        val cleartextRow = dialogView.findViewById<LinearLayout>(R.id.cleartext_row)
        val cleartextCheckbox = dialogView.findViewById<MaterialCheckBox>(R.id.checkbox_allow_cleartext)

        // Pre-fill current values
        nameEdit.setText(profile.name)
        urlEdit.setText(profile.url)
        val existingKey = LRRAuthManager.getApiKeyForProfile(profile.id)
        if (existingKey != null) {
            apiKeyEdit.setText(existingKey)
        }
        cleartextCheckbox.isChecked = profile.allowCleartext
        cleartextRow.visibility =
            if (profile.url.lowercase().startsWith("http://")) View.VISIBLE else View.GONE

        urlEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString()?.trim().orEmpty().lowercase()
                cleartextRow.visibility = if (text.startsWith("http://")) View.VISIBLE else View.GONE
            }
        })

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.lrr_edit_server)
            .setView(dialogView)
            .setPositiveButton(R.string.lrr_edit_server_save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        // Prevent keyboard from resizing the underlying Activity layout
        val activity = activityProvider()
        val originalSoftInputMode: Int = if (activity?.window != null) {
            val mode = activity.window.attributes.softInputMode
            activity.window.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            )
            mode
        } else {
            WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
        }

        dialog.setOnDismissListener {
            activity?.window?.setSoftInputMode(originalSoftInputMode)
        }

        dialog.show()

        // Override positive button to add validation
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val newName = nameEdit.text?.toString()?.trim().orEmpty()
            val newUrl = urlEdit.text?.toString()?.trim().orEmpty()
            val newKey = apiKeyEdit.text?.toString()?.trim().orEmpty()

            if (newName.isEmpty()) {
                nameEdit.error = stringProvider(R.string.name_is_empty)
                return@setOnClickListener
            }
            if (newUrl.isEmpty()) {
                urlEdit.error = stringProvider(R.string.lrr_server_url_empty)
                return@setOnClickListener
            }

            val normalizedInput = LRRUrlHelper.normalizeUrl(newUrl)

            // Validate cleartext consent for explicit http:// URLs
            if (normalizedInput.lowercase().startsWith("http://")
                && !cleartextCheckbox.isChecked
            ) {
                Toast.makeText(ctx, R.string.lrr_allow_cleartext_required, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            // Disable button during connection test
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
            Toast.makeText(ctx, R.string.lrr_test_connection, Toast.LENGTH_SHORT).show()

            viewModel.testAndSaveEditedProfile(
                profile = profile,
                position = position,
                newName = newName,
                newUrl = normalizedInput,
                newKey = newKey
            )
        }

        // Store dialog ref so Scene can dismiss on EditSaved event
        currentEditDialog = dialog
        currentEditUrlEdit = urlEdit
    }

    // -------------------------------------------------------------------------
    // Add dialog
    // -------------------------------------------------------------------------

    fun showAddDialog() {
        val ctx = contextProvider() ?: return

        val dialogView = LayoutInflater.from(ctx).inflate(R.layout.dialog_edit_server, null)
        val nameEdit = dialogView.findViewById<TextInputEditText>(R.id.edit_server_name)
        val urlEdit = dialogView.findViewById<TextInputEditText>(R.id.edit_server_url)
        val apiKeyEdit = dialogView.findViewById<TextInputEditText>(R.id.edit_api_key)
        val cleartextRow = dialogView.findViewById<LinearLayout>(R.id.cleartext_row)
        val cleartextCheckbox = dialogView.findViewById<MaterialCheckBox>(R.id.checkbox_allow_cleartext)

        cleartextCheckbox.isChecked = false
        cleartextRow.visibility = View.GONE

        urlEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString()?.trim().orEmpty().lowercase()
                cleartextRow.visibility = if (text.startsWith("http://")) View.VISIBLE else View.GONE
            }
        })

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.lrr_add_server)
            .setView(dialogView)
            .setPositiveButton(R.string.lrr_save_and_connect, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = nameEdit.text?.toString()?.trim().orEmpty()
            val url = urlEdit.text?.toString()?.trim().orEmpty()
            val apiKey = apiKeyEdit.text?.toString()?.trim().orEmpty()

            if (name.isEmpty()) {
                nameEdit.error = stringProvider(R.string.name_is_empty)
                return@setOnClickListener
            }
            if (url.isEmpty()) {
                urlEdit.error = stringProvider(R.string.lrr_server_url_empty)
                return@setOnClickListener
            }

            val normalizedInput = LRRUrlHelper.normalizeUrl(url)
            val isHttpUrl = normalizedInput.lowercase().startsWith("http://")
            if (isHttpUrl && !cleartextCheckbox.isChecked) {
                Toast.makeText(ctx, R.string.lrr_allow_cleartext_required, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val profileAllowCleartext = if (isHttpUrl) cleartextCheckbox.isChecked else true

            // Disable button during connection test
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
            Toast.makeText(ctx, R.string.lrr_test_connection, Toast.LENGTH_SHORT).show()

            viewModel.testAndAddProfile(
                name = name,
                normalizedUrl = normalizedInput,
                apiKey = apiKey,
                allowCleartext = profileAllowCleartext
            )
        }

        // Store dialog ref so Scene can dismiss on ProfileAdded event
        currentAddDialog = dialog
    }

    // -------------------------------------------------------------------------
    // Reauth dialog
    // -------------------------------------------------------------------------

    fun showReauthDialog() {
        val ctx = contextProvider() ?: return
        AlertDialog.Builder(ctx)
            .setTitle(R.string.reauth_required_title)
            .setMessage(R.string.reauth_required_message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    fun showSecureStorageErrorDialog() {
        val ctx = contextProvider() ?: return
        AlertDialog.Builder(ctx)
            .setTitle(R.string.lrr_keystore_failed_title)
            .setMessage(R.string.lrr_secure_storage_write_failed)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // -------------------------------------------------------------------------
    // Dialog references for Scene event handling
    // -------------------------------------------------------------------------

    /** Current edit dialog, so the Scene can dismiss it on EditSaved. */
    var currentEditDialog: AlertDialog? = null
        private set

    /** URL edit field in current edit dialog, for updating resolved URL. */
    var currentEditUrlEdit: TextInputEditText? = null
        private set

    /** Current add dialog, so the Scene can dismiss it on ProfileAdded. */
    var currentAddDialog: AlertDialog? = null
        private set

    fun clearDialogRefs() {
        currentEditDialog = null
        currentEditUrlEdit = null
        currentAddDialog = null
    }
}
