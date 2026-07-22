package com.hippo.ehviewer.ui.fragment

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import com.hippo.ehviewer.Analytics
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.settings.PrivacySettings
import com.hippo.ehviewer.settings.SecuritySettings
import com.lanraragi.reader.client.api.LRRAuthManager
import kotlinx.coroutines.launch

class PrivacyFragment : BasePreferenceFragmentCompat(), Preference.OnPreferenceChangeListener {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.privacy_settings)
        findPreference<Preference>(PrivacySettings.KEY_ENABLE_ANALYTICS)?.onPreferenceChangeListener = this
        findPreference<Preference>(KEY_CLEAR_SEARCH_HISTORY)?.setOnPreferenceClickListener {
            confirmClearSearchHistory()
            true
        }
    }

    private fun confirmClearSearchHistory() {
        val context = context ?: return
        AlertDialog.Builder(context)
            .setTitle(R.string.settings_privacy_clear_search_history)
            .setMessage(R.string.clear_search_history_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val profileId = LRRAuthManager.getActiveProfileId()
                lifecycleScope.launch {
                    try {
                        ServiceRegistry.dataModule.searchHistoryRepository.clearAll(profileId)
                        Toast.makeText(
                            getContext(), R.string.clear_search_history_done, Toast.LENGTH_SHORT
                        ).show()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to clear search history", e)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        findPreference<Preference>(KEY_PATTERN_PROTECTION)?.setSummary(
            if (!SecuritySettings.hasPattern()) {
                R.string.settings_privacy_pattern_protection_not_set
            } else {
                R.string.settings_privacy_pattern_protection_set
            }
        )
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean {
        val key = preference.key
        if (PrivacySettings.KEY_ENABLE_ANALYTICS == key) {
            if (newValue is Boolean && newValue) {
                activity?.let { Analytics.start(it) }
            }
            return true
        }
        return true
    }

    companion object {
        private const val TAG = "PrivacyFragment"
        private const val KEY_PATTERN_PROTECTION = "pattern_protection"
        private const val KEY_CLEAR_SEARCH_HISTORY = "clear_search_history"
    }
}
