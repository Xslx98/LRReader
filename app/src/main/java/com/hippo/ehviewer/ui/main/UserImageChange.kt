package com.hippo.ehviewer.ui.main

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentUris
import android.content.DialogInterface
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
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
import com.hippo.content.FileProvider
import com.hippo.ehviewer.R
import com.hippo.ehviewer.callBack.ImageChangeCallBack
import com.hippo.ehviewer.settings.AppearanceSettings
import com.hippo.ehviewer.callBack.PermissionCallBack
import com.hippo.ehviewer.ui.MainActivity
import com.hippo.util.FileUtils
import com.hippo.util.PermissionRequester
import com.hippo.widget.AvatarImageView
import com.yalantis.ucrop.UCrop
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

class UserImageChange(
    private val activity: Activity,
    private val dialogType: Int,
    private val layoutInflater: LayoutInflater,
    private val rootLayoutInflater: LayoutInflater,
    private val imageChangeCallBack: ImageChangeCallBack
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

        popupWindow?.showAtLocation(
            rootLayoutInflater.inflate(R.layout.activity_main, null),
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
        checkNotNull(popupWindow)
        popupWindow!!.dismiss()
        val cameraDir = File(activity.externalCacheDir, "camera")
        cameraDir.mkdirs()
        outputImage = if (dialogType == CHANGE_BACKGROUND) {
            File(cameraDir, "background_image.jpg")
        } else {
            File(cameraDir, "avatar_image.jpg")
        }
        imageUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // 大于等于版本24（7.0）的场合
            val authority = activity.application.packageName + ".fileprovider"
            FileProvider.getUriForFile(activity, authority, outputImage!!)
        } else {
            // 小于android 版本7.0（24）的场合
            Uri.fromFile(outputImage)
        }

        PermissionRequester.request(
            activity, Manifest.permission.CAMERA,
            activity.getString(R.string.request_camera_permission),
            REQUEST_CAMERA_PERMISSION, this
        )
    }

    private fun startAlbum() {
        checkNotNull(popupWindow)
        popupWindow!!.dismiss()
        PermissionRequester.request(
            activity, Manifest.permission.WRITE_EXTERNAL_STORAGE,
            activity.getString(R.string.request_storage_permission),
            REQUEST_STORAGE_PERMISSION, this
        )
    }

    fun saveImageForResult(requestCode: Int, resultCode: Int, data: Intent?, avatar: AvatarImageView?) {
        if (resultCode != Activity.RESULT_OK) {
            // Check UCrop error
            if (resultCode == UCrop.RESULT_ERROR && data != null) {
                val cropError = UCrop.getError(data)
                if (cropError != null) {
                    Toast.makeText(activity, cropError.message, Toast.LENGTH_SHORT).show()
                }
            }
            return
        }
        if (requestCode == CROP_PHOTO) {
            // UCrop completed — save the cropped image
            saveCroppedImage(data, avatar)
            return
        }
        if (requestCode == PICK_PHOTO) {
            checkNotNull(data)
            val pickedUri = data.data
            if (pickedUri != null) {
                startCrop(pickedUri)
                return
            }
            saveImageFromAlbum(data, avatar)
        } else if (requestCode == TAKE_CAMERA) {
            if (imageUri != null) {
                startCrop(imageUri!!)
                return
            }
            saveImageFromCamera(avatar)
        }
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
            .start(activity)
    }

    private fun saveCroppedImage(data: Intent?, avatar: AvatarImageView?) {
        if (data == null) return
        val resultUri = UCrop.getOutput(data) ?: return
        val croppedPath = resultUri.path ?: return

        // Copy cropped file to persistent filesDir to avoid cache cleanup issues
        val persistentName = if (dialogType == CHANGE_AVATAR) "avatar_image.jpg" else "background_image.jpg"
        val persistentFile = File(activity.filesDir, persistentName)
        val croppedFile = File(croppedPath)

        try {
            copyFile(croppedFile, persistentFile)
        } catch (e: IOException) {
            Toast.makeText(activity, activity.getString(R.string.error_save_image), Toast.LENGTH_SHORT).show()
            return
        }

        // Delete old saved image (only if it's different from the new persistent file)
        val oldFile = AppearanceSettings.getUserImageFile(key)
        if (oldFile != null && oldFile.absolutePath != persistentFile.absolutePath) {
            oldFile.delete()
        }

        AppearanceSettings.saveFilePath(key, persistentFile.absolutePath)
        if (dialogType == CHANGE_BACKGROUND) {
            imageChangeCallBack.backgroundSourceChange(persistentFile)
        } else if (avatar != null) {
            avatar.setImageBitmap(BitmapFactory.decodeFile(persistentFile.absolutePath))
        }
    }

    private fun resetToDefault() {
        popupWindow?.dismiss()

        // Delete saved image file
        val oldFile = AppearanceSettings.getUserImageFile(key)
        oldFile?.delete()

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
        AppearanceSettings.saveFilePath(key, outputImage!!.path)
        if (dialogType == CHANGE_BACKGROUND) {
            imageChangeCallBack.backgroundSourceChange(File(outputImage!!.path))
        } else {
            avatar?.setImageBitmap(BitmapFactory.decodeFile(outputImage!!.path))
        }
    }

    private fun saveImageFromAlbum(data: Intent, avatar: AvatarImageView?) {
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
                try {
                    val contentUri = ContentUris.withAppendedId(
                        Uri.parse("content: //downloads/public_downloads"),
                        docId.toLong()
                    )
                    imagePath = getImagePath(contentUri, null)
                } catch (e: NumberFormatException) {
                    e.printStackTrace()
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.error_cant_get_image),
                        Toast.LENGTH_SHORT
                    ).show()
                }
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
        // 根据图片路径显示图片
        saveImage(imagePath, avatar)
    }

    private fun saveImage(imagePath: String?, avatar: AvatarImageView?) {
        if (imagePath == null) {
            return
        }

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
        val newImagePath = toFile.path
        AppearanceSettings.saveFilePath(key, newImagePath)
        if (dialogType == CHANGE_BACKGROUND) {
            imageChangeCallBack.backgroundSourceChange(File(newImagePath))
        } else {
            avatar?.setImageBitmap(BitmapFactory.decodeFile(toFile.path))
        }
    }

    private fun getImagePath(uri: Uri, selection: String?): String? {
        var path: String? = null
        // 通过Uri和selection来获取真实的图片路径
        val cursor = activity.contentResolver.query(uri, null, selection, null, null)
        if (cursor != null) {
            if (cursor.moveToFirst()) {
                val columnIndex = cursor.getColumnIndex(MediaStore.Images.Media.DATA)
                if (columnIndex == -1) {
                    return null
                }
                path = cursor.getString(columnIndex)
            }
            cursor.close()
        }
        return path
    }

    /**
     * 获取权限的回调
     */
    override fun agree(permissionCode: Int) {
        if (permissionCode == REQUEST_CAMERA_PERMISSION) {
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            intent.putExtra(MediaStore.EXTRA_OUTPUT, imageUri)
            @Suppress("DEPRECATION")
            activity.startActivityForResult(intent, TAKE_CAMERA)
        }
        if (permissionCode == REQUEST_STORAGE_PERMISSION) {
            val intent = Intent(Intent.ACTION_GET_CONTENT)
            intent.type = "image/*"
            @Suppress("DEPRECATION")
            activity.startActivityForResult(intent, PICK_PHOTO)
        }
    }

    companion object {
        @JvmField
        val CHANGE_BACKGROUND = 0
        @JvmField
        val CHANGE_AVATAR = 1
        @JvmField
        val TAKE_CAMERA = 101
        @JvmField
        val PICK_PHOTO = 102
        @JvmField
        val CROP_PHOTO = UCrop.REQUEST_CROP
        @JvmField
        val REQUEST_CAMERA_PERMISSION = 1
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
