package com.hippo.ehviewer.ui.scene.gallery.detail

import androidx.lifecycle.ViewModel
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.client.data.GalleryDetail
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.dao.DownloadInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ViewModel for [GalleryDetailScene]. Manages gallery detail state, gallery info,
 * loading state, and favorite status.
 *
 * The Scene observes [StateFlow] properties and updates the UI accordingly.
 * View references, dialog display, helpers, and navigation remain in the Scene.
 */
class GalleryDetailViewModel : ViewModel() {

    // -------------------------------------------------------------------------
    // State constants
    // -------------------------------------------------------------------------

    companion object {
        const val STATE_INIT = -1
        const val STATE_NORMAL = 0
        const val STATE_REFRESH = 1
        const val STATE_REFRESH_HEADER = 2
        const val STATE_FAILED = 3
    }

    // -------------------------------------------------------------------------
    // Navigation arguments (set once from Bundle)
    // -------------------------------------------------------------------------

    private val _action = MutableStateFlow<String?>(null)

    /** The action that opened this scene (ACTION_GALLERY_INFO, ACTION_GID_TOKEN, etc.). */
    val action: StateFlow<String?> = _action.asStateFlow()

    private val _gid = MutableStateFlow(0L)

    /** Gallery ID, used when action is ACTION_GID_TOKEN. */
    val gid: StateFlow<Long> = _gid.asStateFlow()

    private val _token = MutableStateFlow<String?>(null)

    /** Gallery token, used when action is ACTION_GID_TOKEN. */
    val token: StateFlow<String?> = _token.asStateFlow()

    // -------------------------------------------------------------------------
    // Gallery data
    // -------------------------------------------------------------------------

    private val _galleryInfo = MutableStateFlow<GalleryInfo?>(null)

    /** The initial gallery info passed via arguments (before detail is loaded). */
    val galleryInfo: StateFlow<GalleryInfo?> = _galleryInfo.asStateFlow()

    private val _galleryDetail = MutableStateFlow<GalleryDetail?>(null)

    /** The full gallery detail, loaded from the LANraragi API. */
    val galleryDetail: StateFlow<GalleryDetail?> = _galleryDetail.asStateFlow()

    private val _downloadInfo = MutableStateFlow<DownloadInfo?>(null)

    /** Download info when opened from downloads scene. */
    val downloadInfo: StateFlow<DownloadInfo?> = _downloadInfo.asStateFlow()

    // -------------------------------------------------------------------------
    // Loading state
    // -------------------------------------------------------------------------

    private val _state = MutableStateFlow(STATE_INIT)

    /** Current UI state: STATE_INIT, STATE_NORMAL, STATE_REFRESH, STATE_REFRESH_HEADER, or STATE_FAILED. */
    val state: StateFlow<Int> = _state.asStateFlow()

    // -------------------------------------------------------------------------
    // Setters
    // -------------------------------------------------------------------------

    fun setAction(action: String?) {
        _action.value = action
    }

    fun setGid(gid: Long) {
        _gid.value = gid
    }

    fun setToken(token: String?) {
        _token.value = token
    }

    fun setGalleryInfo(info: GalleryInfo?) {
        _galleryInfo.value = info
    }

    fun setGalleryDetail(detail: GalleryDetail?) {
        _galleryDetail.value = detail
    }

    fun setDownloadInfo(info: DownloadInfo?) {
        _downloadInfo.value = info
    }

    fun setState(state: Int) {
        _state.value = state
    }

    // -------------------------------------------------------------------------
    // Derived accessors
    // -------------------------------------------------------------------------

    /**
     * Returns the effective gallery ID, preferring galleryDetail > galleryInfo > gid argument.
     * Returns -1 if none is available.
     */
    fun getEffectiveGid(): Long {
        val detail = _galleryDetail.value
        if (detail != null) return detail.gid

        val info = _galleryInfo.value
        if (info != null) return info.gid

        if (GalleryDetailScene.ACTION_GID_TOKEN == _action.value) {
            return _gid.value
        }
        return -1
    }

    /**
     * Returns the effective token, preferring galleryDetail > galleryInfo > token argument.
     */
    fun getEffectiveToken(): String? {
        val detail = _galleryDetail.value
        if (detail != null) return detail.token

        val info = _galleryInfo.value
        if (info != null) return info.token

        if (GalleryDetailScene.ACTION_GID_TOKEN == _action.value) {
            return _token.value
        }
        return null
    }

    /**
     * Returns the effective uploader string, preferring galleryDetail > galleryInfo.
     */
    fun getEffectiveUploader(): String? {
        return _galleryDetail.value?.uploader ?: _galleryInfo.value?.uploader
    }

    /**
     * Returns the effective category, preferring galleryDetail > galleryInfo.
     * Returns -1 if none is available.
     */
    fun getEffectiveCategory(): Int {
        val detail = _galleryDetail.value
        if (detail != null) return detail.category

        val info = _galleryInfo.value
        if (info != null) return info.category

        return -1
    }

    /**
     * Returns the best available gallery info object (detail preferred over info).
     */
    fun getEffectiveGalleryInfo(): GalleryInfo? {
        return _galleryDetail.value ?: _galleryInfo.value
    }

    // -------------------------------------------------------------------------
    // Cache lookup
    // -------------------------------------------------------------------------

    /**
     * Attempts to load gallery detail from cache if not already present.
     * Returns true if data is available or a request should be made,
     * false if the gid is invalid.
     */
    fun tryLoadFromCache(): Boolean {
        if (_galleryDetail.value != null) return true

        val gid = getEffectiveGid()
        if (gid == -1L) return false

        val cached = ServiceRegistry.dataModule.galleryDetailCache.get(gid)
        if (cached != null) {
            _galleryDetail.value = cached
            return true
        }
        return true
    }
}
