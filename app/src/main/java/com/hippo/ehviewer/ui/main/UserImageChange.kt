package com.hippo.ehviewer.ui.main

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentUris
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import androidx.core.net.toUri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.transition.Slide
import android.transition.TransitionSet
import android.transition.Visibility
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.lifecycle.lifecycleScope
import com.hippo.content.FileProvider
import com.hippo.ehviewer.R
import com.hippo.ehviewer.callBack.ImageChangeCallBack
import com.hippo.ehviewer.settings.AppearanceSettings
import com.hippo.ehviewer.util.ImageDecodeUtils
import com.hippo.ehviewer.callBack.PermissionCallBack
import com.hippo.ehviewer.ui.MainActivity
import com.hippo.util.FileUtils
import com.hippo.util.PermissionRequester
import com.hippo.widget.AvatarImageView
import com.yalantis.ucrop.UCrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

class UserImageChange(
    private val activity: ComponentActivity,
    private val dialogType: Int,
    private val layoutInflater: LayoutInflater,
    private val rootLayoutInflater: LayoutInflater,
    private val imageChangeCallBack: ImageChangeCallBack,
    private val cameraLauncher: ActivityResultLauncher<Intent>,
    private val albumLauncher: ActivityResultLauncher<Intent>,
    private val cropLauncher: ActivityResultLauncher<Intent>,
) : PermissionCallBack {

    private val key: String = if (dialogType == CHANGE_AVATAR) {
        AppearanceSettings.USER_AVATAR_IMAGE
    } else {
        AppearanceSettings.USER_BACKGROUND_IMAGE
    }

    private var popupWindow: PopupWindow? = null
    @Suppress("unused")
    private val alertDialog: android.app.AlertDialog? = null

    private var imageUri: Uri? = null
    private var outputImage: File? = null
    private var cropFile: File? = null

    fun showImageChangeDialog() {
        // Skip confirmation dialog, go directly to picker
        yes()
    }

    @SuppressLint("InflateParams")
    private fun yes() {
        val relativeLayout = layoutInflater.inflate(
            R.layout.background_change_bottom_pop, null
        ) as RelativeLayout
        val startCamera = relativeLayout.findViewById<TextView>(R.id.take_photo_with_camera)
        startCamera.setOnClickListener { startCamera() }

        val startAlbum = relativeLayout.findViewById<TextView>(R.id.choose_from_the_album)
        startAlbum.setOnClickListener { startAlbum() }

        // Reset to default button
        val resetDefault = relativeLayout.findViewById<TextView>(R.id.reset_to_default)
        if (resetDefault != null) {
            resetDefault.visibility = View.VISIBLE
            resetDefault.setOnClickListener { resetToDefault() }
        }

        popupWindow = PopupWindow(
            relativeLayout,
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        ).apply {
            isOutsideTouchable = true
            isTouchable = true
            isFocusable = true
        }
        // Dismiss when tapping the dimmed overlay
        relativeLayout.setOnClickListener { popupWindow?.dismiss() }

        val enterTransitionSet = TransitionSet().apply {
            duration = 300
            addTransition(Slide(Gravity.BOTTOM).apply {
                mode = Visibility.MODE_IN
            })
            ordering = TransitionSet.ORDERING_TOGETHER
        }
        popupWindow?.enterTransition = enterTransitionSet

        val exitTransitionSet = TransitionSet().apply {
            duration = 300
            addTransition(Slide(Gravity.BOTTOM).apply {
                mode = Visibility.MODE_OUT
            })
            ordering = TransitionSet.ORDERING_TOGETHER
        }
        popupWindow?.exitTransition = exitTransitionSet

        // Anchor to a real, attached view (the activity content root) — a freshly
        // inflated activity_main has no window token, so showAtLocation against it risks
        // BadTokenException / mispositioning and needlessly inflates the whole layout.
        popupWindow?.showAtLocation(
            activity.findViewById(android.R.id.content),
            Gravity.BOTTOM, 0, 0
        )
    }

    @Suppress("unused")
    private fun cancel(dialog: DialogInterface, which: Int) {
        if (which == DialogInterface.BUTTON_NEGATIVE) {
            dialog.dismiss()
        }
    }

    private fun startCamera() {
        val popup = popupWindow ?: return
        popup.dismiss()
        val cameraDir = File(activity.externalCacheDir, "camera")
        cameraDir.mkdirs()
        val output = if (dialogType == CHANGE_BACKGROUND) {
            File(cameraDir, "background_image.jpg")
        } else {
            File(cameraDir, "avatar_image.jpg")
        }
        outputImage = output
        imageUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // 大于等于版本24（7.0）的场合
            val authority = activity.application.packageName + ".fileprovider"
            FileProvider.getUriForFile(activity, authority, output)
        } else {
            // 小于android 版本7.0（24）的场合
            Uri.fromFile(output)
        }

        // ACTION_IMAGE_CAPTURE delegates to an external camera app and the app no longer
        // declares the CAMERA permission, so no runtime grant is needed — launch directly
        // instead of going through the permission requester (which launched the camera
        // before the grant resolved and crashed with SecurityException).
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        intent.putExtra(MediaStore.EXTRA_OUTPUT, imageUri)
        cameraLauncher.launch(intent)
    }

    private fun startAlbum() {
        val popup = popupWindow ?: return
        popup.dismiss()
        PermissionRequester.request(
            activity, Manifest.permission.WRITE_EXTERNAL_STORAGE,
            activity.getString(R.string.request_storage_permission),
            REQUEST_STORAGE_PERMISSION, this
        )
    }

    fun handleCameraResult(resultCode: Int, avatar: AvatarImageView?) {
        if (resultCode != Activity.RESULT_OK) return
        val uri = imageUri
        if (uri != null) {
            startCrop(uri)
            return
        }
        saveImageFromCamera(avatar)
    }

    fun handleAlbumResult(resultCode: Int, data: Intent?, avatar: AvatarImageView?) {
        if (resultCode != Activity.RESULT_OK) return
        if (data == null) return
        val pickedUri = data.data
        if (pickedUri != null) {
            startCrop(pickedUri)
            return
        }
        saveImageFromAlbum(data, avatar)
    }

    fun handleCropResult(resultCode: Int, data: Intent?, avatar: AvatarImageView?) {
        if (resultCode != Activity.RESULT_OK) {
            if (resultCode == UCrop.RESULT_ERROR && data != null) {
                val cropError = UCrop.getError(data)
                if (cropError != null) {
                    Toast.makeText(activity, cropError.message, Toast.LENGTH_SHORT).show()
                }
            }
            return
        }
        saveCroppedImage(data, avatar)
    }

    private fun startCrop(sourceUri: Uri) {
        val filename = if (dialogType == CHANGE_AVATAR) "avatar_cropped.jpg" else "background_cropped.jpg"
        cropFile = File(activity.externalCacheDir, filename)
        val destUri = Uri.fromFile(cropFile)

        val options = UCrop.Options().apply {
            setShowCropFrame(dialogType != CHANGE_AVATAR)
            setShowCropGrid(dialogType != CHANGE_AVATAR)
            setCircleDimmedLayer(dialogType == CHANGE_AVATAR)
            setToolbarColor(Color.BLACK)
            setToolbarWidgetColor(Color.WHITE)
            setCompressionQuality(90)
        }

        val aspectX: Float
        val aspectY: Float
        val maxW: Int
        val maxH: Int
        if (dialogType == CHANGE_AVATAR) {
            aspectX = 1f
            aspectY = 1f
            maxW = 512
            maxH = 512
        } else {
            // Nav header: match_parent x 160dp, roughly 2:1
            aspectX = 2f
            aspectY = 1f
            maxW = 1080
            maxH = 540
        }

        UCrop.of(sourceUri, destUri)
            .withAspectRatio(aspectX, aspectY)
            .withMaxResultSize(maxW, maxH)
            .withOptions(options)
            .start(activity, cropLauncher)
    }

    private fun saveCroppedImage(data: Intent?, avatar: AvatarImageView?) {
        if (data == null) return
        val resultUri = UCrop.getOutput(data) ?: return
        val croppedPath = resultUri.path ?: return

        // Copy cropped file to persistent filesDir to avoid cache cleanup issues.
        // Copy + decode are disk work — run them off the main thread;
        // lifecycleScope drops the UI update if the Activity is gone.
        val persistentName = if (dialogType == CHANGE_AVATAR) "avatar_image.jpg" else "background_image.jpg"
        val persistentFile = File(activity.filesDir, persistentName)
        val croppedFile = File(croppedPath)

        activity.lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                try {
                    copyFile(croppedFile, persistentFile)
                    // Delete old saved image (only if it's different from the new persistent file)
                    val oldFile = AppearanceSettings.getUserImageFile(key)
                    if (oldFile != null && oldFile.absolutePath != persistentFile.absolutePath) {
                        oldFile.delete()
                    }
                    AppearanceSettings.saveFilePath(key, persistentFile.absolutePath)
                    true
                } catch (e: IOException) {
                    false
                }
            }
            if (!saved) {
                Toast.makeText(activity, activity.getString(R.string.error_save_image), Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (dialogType == CHANGE_BACKGROUND) {
                imageChangeCallBack.backgroundSourceChange(persistentFile)
            } else if (avatar != null) {
                val bitmap = withContext(Dispatchers.IO) {
                    ImageDecodeUtils.decodeSampledBitmap(persistentFile.absolutePath)
                }
                avatar.setImageBitmap(bitmap)
            }
        }
    }

    private fun resetToDefault() {
        popupWindow?.dismiss()

        // Read the stored path before clearing it, then unlink off-main.
        val oldFile = AppearanceSettings.getUserImageFile(key)
        activity.lifecycleScope.launch(Dispatchers.IO) {
            oldFile?.delete()
        }

        // Clear saved path
        AppearanceSettings.saveFilePath(key, "")

        if (dialogType == CHANGE_BACKGROUND) {
            imageChangeCallBack.backgroundSourceChange(null)
        } else {
            // Reload default avatar in MainActivity
            if (activity is MainActivity) {
                activity.loadAvatar()
            }
        }
    }

    private fun saveImageFromCamera(avatar: AvatarImageView?) {
        val output = outputImage ?: return
        AppearanceSettings.saveFilePath(key, output.path)
        if (dialogType == CHANGE_BACKGROUND) {
            imageChangeCallBack.backgroundSourceChange(File(output.path))
        } else if (avatar != null) {
            // Decode off the main thread; set on main when ready.
            activity.lifecycleScope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    ImageDecodeUtils.decodeSampledBitmap(output.path)
                }
                avatar.setImageBitmap(bitmap)
            }
        }
    }

    private fun saveImageFromAlbum(data: Intent, avatar: AvatarImageView?) {
        // Path resolution hits the ContentResolver and saveImage copies the
        // file — both are disk work, so run them off the main thread.
        activity.lifecycleScope.launch {
            val imagePath = try {
                withContext(Dispatchers.IO) { resolveAlbumImagePath(data) }
            } catch (e: NumberFormatException) {
                e.printStackTrace()
                Toast.makeText(
                    activity,
                    activity.getString(R.string.error_cant_get_image),
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            // 根据图片路径显示图片
            saveImage(imagePath, avatar)
        }
    }

    /**
     * Resolve the picked album [data] to a filesystem path. Queries the
     * ContentResolver — call from a background dispatcher. Throws
     * [NumberFormatException] for a malformed downloads-document id (the
     * caller surfaces it as a toast).
     */
    private fun resolveAlbumImagePath(data: Intent): String? {
        var imagePath: String? = null
        val uri = data.data
        if (DocumentsContract.isDocumentUri(activity, uri)) {
            // 如果是document类型的Uri，则通过document id处理
            val docId = DocumentsContract.getDocumentId(uri)
            checkNotNull(uri)
            if ("com.android.providers.media.documents" == uri.authority) {
                val id = docId.split(":")[1]
                // 解析出数字格式的id
                val selection = MediaStore.Images.Media._ID + "=" + id
                imagePath = getImagePath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, selection)
            } else if ("com.android.providers.downloads.documents" == uri.authority) {
                val contentUri = ContentUris.withAppendedId(
                    "content: //downloads/public_downloads".toUri(),
                    docId.toLong()
                )
                imagePath = getImagePath(contentUri, null)
            }
        } else {
            checkNotNull(uri)
            if ("content".equals(uri.scheme, ignoreCase = true)) {
                // 如果是content类型的Uri，则使用普通方式处理
                imagePath = getImagePath(uri, null)
            } else if ("file".equals(uri.scheme, ignoreCase = true)) {
                // 如果是file类型的Uri，直接获取图片路径即可
                imagePath = uri.path
            }
        }
        return imagePath
    }

    private suspend fun saveImage(imagePath: String?, avatar: AvatarImageView?) {
        if (imagePath == null) {
            return
        }

        val wantBitmap = dialogType != CHANGE_BACKGROUND && avatar != null
        val (newImagePath, bitmap) = withContext(Dispatchers.IO) {
            val oldFile = AppearanceSettings.getUserImageFile(key)
            oldFile?.delete()

            val newFile = File(imagePath)
            val toFile = File(activity.externalCacheDir, newFile.name)
            if (!toFile.exists()) {
                try {
                    toFile.createNewFile()
                } catch (ioException: IOException) {
                    ioException.printStackTrace()
                }
            }
            FileUtils.copyFile(newFile, toFile)
            AppearanceSettings.saveFilePath(key, toFile.path)
            toFile.path to if (wantBitmap) ImageDecodeUtils.decodeSampledBitmap(toFile.path) else null
        }
        if (dialogType == CHANGE_BACKGROUND) {
            imageChangeCallBack.backgroundSourceChange(File(newImagePath))
        } else {
            avatar?.setImageBitmap(bitmap)
        }
    }

    private fun getImagePath(uri: Uri, selection: String?): String? {
        // Cursor was previously closed only on the success branch; the
        // `columnIndex == -1` early-return leaked the cursor. `use { }` closes
        // the cursor on every exit path including thrown exceptions.
        return activity.contentResolver.query(uri, null, selection, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val columnIndex = cursor.getColumnIndex(MediaStore.Images.Media.DATA)
            if (columnIndex == -1) return@use null
            cursor.getString(columnIndex)
        }
    }

    /**
     * 获取权限的回调
     */
    override fun agree(permissionCode: Int) {
        // Camera no longer goes through a permission request (the CAMERA
        // declaration was removed; startCamera launches the capture intent
        // directly), so only the storage/album path remains.
        if (permissionCode == REQUEST_STORAGE_PERMISSION) {
            val intent = Intent(Intent.ACTION_GET_CONTENT)
            intent.type = "image/*"
            albumLauncher.launch(intent)
        }
    }

    companion object {
        @JvmField
        val CHANGE_BACKGROUND = 0
        @JvmField
        val CHANGE_AVATAR = 1
        @JvmField
        val REQUEST_STORAGE_PERMISSION = 2

        @Throws(IOException::class)
        private fun copyFile(src: File, dst: File) {
            FileInputStream(src).use { input ->
                FileOutputStream(dst).use { output ->
                    val buf = ByteArray(8192)
                    var len: Int
                    while (input.read(buf).also { len = it } > 0) {
                        output.write(buf, 0, len)
                    }
                }
            }
        }
    }
}
