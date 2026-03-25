package com.hippo.ehviewer.ui.splash

import android.content.Intent
import android.os.Bundle
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ui.EhActivity
import com.hippo.ehviewer.ui.MainActivity

/**
 * Splash screen for LR Reader.
 * Simply shows the splash layout briefly, then launches MainActivity.
 */
class SplashActivity : EhActivity() {

    override fun getThemeResId(theme: Int): Int {
        return R.style.SplashTheme
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.splash_layout)

        // Launch MainActivity immediately
        val intentIn = intent
        val restart = intentIn.getBooleanExtra(KEY_RESTART, false)
        val intent = Intent(this@SplashActivity, MainActivity::class.java)
        if (restart) {
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            intent.putExtra(KEY_RESTART, true)
        }
        startActivity(intent)
        finish()
    }

    companion object {
        const val KEY_RESTART: String = "restart"
    }
}
