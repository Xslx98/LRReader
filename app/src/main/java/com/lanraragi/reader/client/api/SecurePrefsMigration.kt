package com.lanraragi.reader.client.api

import android.content.SharedPreferences

/** One-time copy of the legacy credential store into [KeystoreSecurePrefs] (audit C49). */
internal object SecurePrefsMigration {

    /** Copy every entry of [source] into [target] in one commit; false if the commit failed. */
    fun copyAll(source: SharedPreferences, target: SharedPreferences): Boolean {
        val editor = target.edit()
        for ((key, value) in source.all) {
            when (value) {
                is String -> editor.putString(key, value)
                is Long -> editor.putLong(key, value)
                is Int -> editor.putInt(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toMutableSet())
                else -> Unit
            }
        }
        return editor.commit()
    }
}
