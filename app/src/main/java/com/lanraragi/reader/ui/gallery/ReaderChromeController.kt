package com.lanraragi.reader.ui.gallery

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.lanraragi.framework.lib.glgallery.GalleryView
import com.lanraragi.reader.util.MathUtils
import com.lanraragi.reader.util.ViewUtils
import com.lanraragi.framework.widget.ColorView
import com.lanraragi.reader.R
import com.lanraragi.reader.settings.ReadingSettings
import com.lanraragi.reader.widget.GalleryHeader
import com.lanraragi.reader.widget.ReversibleSeekBar

/**
 * The reader window around the GL page view: fullscreen and system-bar
 * state, the header info (clock, progress, battery), the brightness mask,
 * keep-screen-on, orientation and the reader settings dialog.
 */
class ReaderChromeController(private val activity: Activity) {

    var systemUi: ImmersiveSystemUi? = null
        private set

    private var maskView: ColorView? = null
    private var clock: View? = null
    private var progress: TextView? = null
    private var battery: View? = null

    /** Translucent system bars for fullscreen reading; set before and after setContentView. */
    fun applyFullscreenWindowFlags() {
        val w = activity.window
        w.setFlags(
            WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION,
            WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION
        )
        w.setFlags(
            WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS,
            WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
        )
    }

    /**
     * recreate() keeps the window's decor view, so turning fullscreen off from
     * the reader menu inherits the immersive system UI flags and hidden
     * system bars of the instance being replaced. A fresh launch has none.
     */
    fun showSystemBarsLeftByFullscreen() {
        val w = activity.window
        w.clearFlags(
            WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION or
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
        )
        @Suppress("DEPRECATION") // ImmersiveSystemUi sets these legacy flags.
        w.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        WindowCompat.getInsetsController(w, w.decorView).show(WindowInsetsCompat.Type.systemBars())
    }

    /** Immersive system UI for fullscreen reading, hidden from the start. */
    fun setUpSystemUi() {
        if (ReadingSettings.getReadingFullscreen()) {
            applyFullscreenWindowFlags()
            val helper = ImmersiveSystemUi(activity)
            systemUi = helper
            helper.hide()
        }
    }

    /** Binds the header views and the brightness mask; returns the progress text. */
    fun bindHeader(): TextView {
        maskView = ViewUtils.requireView(activity, R.id.mask) as ColorView
        val clock = ViewUtils.requireView(activity, R.id.clock)
        this.clock = clock
        val progress = ViewUtils.requireView(activity, R.id.progress) as TextView
        this.progress = progress
        val battery = ViewUtils.requireView(activity, R.id.battery)
        this.battery = battery
        clock.visibility = if (ReadingSettings.getShowClock()) View.VISIBLE else View.GONE
        progress.visibility = if (ReadingSettings.getShowProgress()) View.VISIBLE else View.GONE
        battery.visibility = if (ReadingSettings.getShowBattery()) View.VISIBLE else View.GONE
        return progress
    }

    /**
     * Wires the bottom menu — page slider and auto page-turn button — to the
     * page view. [progress] is the header progress text the slider updates.
     */
    fun bindSlider(
        slider: GallerySliderController,
        input: GalleryInputHandler,
        galleryView: GalleryView,
        progress: TextView,
    ) {
        val seekBarPanel = ViewUtils.requireView(activity, R.id.seek_bar_panel)
        val autoTransferPanel = ViewUtils.requireView(activity, R.id.auto_transfer) as ImageView
        val leftText = ViewUtils.requireView(seekBarPanel, R.id.left) as TextView
        val rightText = ViewUtils.requireView(seekBarPanel, R.id.right) as TextView
        val seekBar = ViewUtils.requireView(seekBarPanel, R.id.seek_bar) as ReversibleSeekBar

        slider.setViews(
            seekBarPanel, autoTransferPanel, leftText, rightText, seekBar, progress
        )
        slider.setBubble(ViewUtils.requireView(activity, R.id.seek_bar_bubble) as TextView)
        slider.setSystemUi(systemUi)
        slider.setGalleryView(galleryView)

        input.autoTransferPanel = autoTransferPanel
        autoTransferPanel.setOnClickListener { v -> input.toggleAutoRead(v) }
    }

    /**
     * Wires the top bar into the menu [slider] shows and hides; it shows
     * [title] and follows the slider's page.
     */
    fun bindTopBar(
        slider: GallerySliderController,
        callback: ReaderTopBar.Callback,
        title: CharSequence?,
        memberTitleAt: (Int) -> String?,
    ) {
        val root = ViewUtils.requireView(activity, R.id.reader_top_bar)
        val topBar = ReaderTopBar(root, callback, memberTitleAt)
        topBar.setTitle(title)
        topBar.setPage(slider.currentIndex, slider.size)
        slider.setTopBar(root)
        slider.onPageChanged = topBar::setPage
        // The menu brings back the system status bar, which would sit on top
        // of the reader's own clock and battery; the top bar shows the page.
        val header = ViewUtils.requireView(activity, R.id.gallery_header)
        slider.onMenuVisibilityChanged = { shown ->
            header.animate().cancel()
            header.animate()
                .alpha(if (shown) 0f else 1f)
                .setDuration(HEADER_FADE_DURATION)
                .start()
        }
    }

    /** Keep-screen-on, orientation, brightness and the display cutout, from the saved settings. */
    fun applyInitialWindowSettings() {
        setKeepScreenOn(ReadingSettings.getKeepScreenOn())
        activity.requestedOrientation = resolveOrientation(ReadingSettings.getScreenRotation())
        setScreenLightness(
            ReadingSettings.getCustomScreenLightness(),
            ReadingSettings.getScreenLightness()
        )
        activity.window.attributes.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES

        val galleryHeader = activity.findViewById<GalleryHeader>(R.id.gallery_header)
        galleryHeader.setOnApplyWindowInsetsListener { _, insets ->
            galleryHeader.setDisplayCutout(insets.displayCutout)
            insets
        }
    }

    fun applySystemUiState(showSystemUi: Boolean) {
        val uiHelper = systemUi ?: return
        if (showSystemUi) {
            uiHelper.show()
        } else {
            uiHelper.hide()
        }
    }

    /** The window side of a settings-dialog change. */
    @Suppress("LongParameterList")
    fun applySettings(
        screenRotation: Int, keepScreenOn: Boolean,
        showClock: Boolean, showProgress: Boolean, showBattery: Boolean,
    ) {
        activity.requestedOrientation = resolveOrientation(screenRotation)
        setKeepScreenOn(keepScreenOn)
        clock?.visibility = if (showClock) View.VISIBLE else View.GONE
        progress?.visibility = if (showProgress) View.VISIBLE else View.GONE
        battery?.visibility = if (showBattery) View.VISIBLE else View.GONE
    }

    fun setScreenLightness(enable: Boolean, lightness: Int) {
        val maskView = maskView ?: return
        val w = activity.window
        val lp = w.attributes
        if (enable) {
            val clampedLightness = MathUtils.clamp(lightness, 0, 200)
            if (clampedLightness > 100) {
                maskView.color = 0
                lp.screenBrightness = Math.max((clampedLightness - 100) / 100.0f, 0.01f)
            } else {
                maskView.color = MathUtils.lerp(0xde, 0x00, clampedLightness / 100.0f) shl 24
                lp.screenBrightness = 0.01f
            }
        } else {
            maskView.color = 0
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        w.attributes = lp
    }

    /** The reader settings dialog; the lightness slider previews live. */
    fun showSettingsDialog(
        callback: GalleryMenuHelper.SettingsCallback,
        decorate: (AlertDialog) -> Unit,
    ) {
        val builder = AlertDialog.Builder(activity)
        val helper = GalleryMenuHelper(
            builder.context,
            callback,
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        setScreenLightness(true, progress)
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
            }
        )
        val dialog = builder.setTitle(R.string.gallery_menu_title)
            .setView(helper.view)
            .show()
        decorate(dialog)
    }

    fun clear() {
        systemUi = null
        maskView = null
        clock = null
        progress = null
        battery = null
    }

    private fun setKeepScreenOn(keepScreenOn: Boolean) {
        if (keepScreenOn) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    companion object {
        /** Matches the menu's slide-in timing. */
        private const val HEADER_FADE_DURATION = 150L

        @JvmStatic
        fun resolveOrientation(screenRotation: Int): Int = when (screenRotation) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            2 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            3 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}
