package com.hippo.ehviewer.ui.main;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentUris;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.transition.Slide;
import android.transition.TransitionSet;
import android.transition.Visibility;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.content.FileProvider;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.ui.MainActivity;
import com.hippo.ehviewer.callBack.ImageChangeCallBack;
import com.hippo.ehviewer.callBack.PermissionCallBack;
import com.hippo.util.FileUtils;
import com.hippo.util.PermissionRequester;
import com.hippo.widget.AvatarImageView;
import com.yalantis.ucrop.UCrop;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class UserImageChange implements PermissionCallBack {

    public static final int CHANGE_BACKGROUND = 0;
    public static final int CHANGE_AVATAR = 1;
    public static final int TAKE_CAMERA = 101;
    public static final int PICK_PHOTO = 102;
    public static final int CROP_PHOTO = UCrop.REQUEST_CROP;
    public static final int REQUEST_CAMERA_PERMISSION = 1;
    public static final int REQUEST_STORAGE_PERMISSION = 2;

    @NonNull
    private final Activity activity;
    @NonNull
    private final LayoutInflater layoutInflater;
    @NonNull
    private final LayoutInflater rootLayoutInflater;
    private final int dialogType;
    @NonNull
    private final String key;

    @Nullable
    private PopupWindow popupWindow;
    private final AlertDialog alertDialog;

    private Uri imageUri;
    private File outputImage;
    private File cropFile;

    private final ImageChangeCallBack imageChangeCallBack;


    public UserImageChange(@NonNull Activity activity,
                           int dialogType,
                           @NonNull LayoutInflater layoutInflater,
                           @NonNull LayoutInflater rootLayoutInflater,
                           ImageChangeCallBack imageChangeCallBack) {
        this.activity = activity;
        this.rootLayoutInflater = rootLayoutInflater;
        this.layoutInflater = layoutInflater;
        this.dialogType = dialogType;
        this.imageChangeCallBack = imageChangeCallBack;

        if (dialogType == CHANGE_AVATAR) {
            alertDialog = null;
            key = Settings.USER_AVATAR_IMAGE;
        } else {
            alertDialog = null;
            key = Settings.USER_BACKGROUND_IMAGE;
        }

    }

    public void showImageChangeDialog() {
        // Skip confirmation dialog, go directly to picker
        yes();
    }

    @SuppressLint("InflateParams")
    private void yes() {
        RelativeLayout relativeLayout = (RelativeLayout) layoutInflater.inflate(R.layout.background_change_bottom_pop, null);
        TextView startCamera = relativeLayout.findViewById(R.id.take_photo_with_camera);

        startCamera.setOnClickListener(l -> startCamera());

        TextView startAlbum = relativeLayout.findViewById(R.id.choose_from_the_album);
        startAlbum.setOnClickListener(l -> startAlbum());

        // Reset to default button
        TextView resetDefault = relativeLayout.findViewById(R.id.reset_to_default);
        if (resetDefault != null) {
            resetDefault.setVisibility(android.view.View.VISIBLE);
            resetDefault.setOnClickListener(l -> resetToDefault());
        }

        popupWindow = new PopupWindow(relativeLayout, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT);
        popupWindow.setOutsideTouchable(true);
        popupWindow.setTouchable(true);
        popupWindow.setFocusable(true);
        // Dismiss when tapping the dimmed overlay
        relativeLayout.setOnClickListener(v -> popupWindow.dismiss());

        TransitionSet enterTransitionSet = new TransitionSet();
        enterTransitionSet.setDuration(300);
        Slide enterSlide = new Slide(Gravity.BOTTOM);
        enterSlide.setMode(Visibility.MODE_IN);
        enterTransitionSet.addTransition(enterSlide);
        enterTransitionSet.setOrdering(TransitionSet.ORDERING_TOGETHER);
        popupWindow.setEnterTransition(enterTransitionSet);

        TransitionSet exitTransitionSet = new TransitionSet();
        exitTransitionSet.setDuration(300);
        Slide exitSlide = new Slide(Gravity.BOTTOM);
        exitSlide.setMode(Visibility.MODE_OUT);
        exitTransitionSet.addTransition(exitSlide);
        exitTransitionSet.setOrdering(TransitionSet.ORDERING_TOGETHER);
        popupWindow.setExitTransition(exitTransitionSet);


        popupWindow.showAtLocation(rootLayoutInflater.inflate(R.layout.activity_main, null), Gravity.BOTTOM, 0, 0);

    }

    private void cancel(DialogInterface dialog, int which) {
        if (which == DialogInterface.BUTTON_NEGATIVE) {
            dialog.dismiss();
        }
    }

    private void startCamera() {
        assert popupWindow != null;
        popupWindow.dismiss();
        if (dialogType == CHANGE_BACKGROUND) {
            outputImage = new File(activity.getExternalCacheDir(), "background_image.jpg");
        } else {
            outputImage = new File(activity.getExternalCacheDir(), "avatar_image.jpg");
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            //大于等于版本24（7.0）的场合
            String authority = activity.getApplication().getPackageName() + ".fileprovider";
            imageUri = FileProvider.getUriForFile(activity, authority, outputImage);
        } else {
            //小于android 版本7.0（24）的场合
            imageUri = Uri.fromFile(outputImage);
        }

        PermissionRequester.request(activity, Manifest.permission.CAMERA,
                activity.getString(R.string.request_camera_permission),
                REQUEST_CAMERA_PERMISSION, this);


    }

    private void startAlbum() {
        assert popupWindow != null;
        popupWindow.dismiss();
        PermissionRequester.request(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE,
                activity.getString(R.string.request_storage_permission),
                REQUEST_STORAGE_PERMISSION, this);
    }

    public void saveImageForResult(int requestCode, int resultCode, @Nullable Intent data, AvatarImageView avatar) {
        if (resultCode != Activity.RESULT_OK) {
            // Check UCrop error
            if (resultCode == UCrop.RESULT_ERROR && data != null) {
                Throwable cropError = UCrop.getError(data);
                if (cropError != null) {
                    Toast.makeText(activity, cropError.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
            return;
        }
        if (requestCode == CROP_PHOTO) {
            // UCrop completed — save the cropped image
            saveCroppedImage(data, avatar);
            return;
        }
        if (requestCode == PICK_PHOTO) {
            assert data != null;
            Uri pickedUri = data.getData();
            if (pickedUri != null) {
                startCrop(pickedUri);
                return;
            }
            saveImageFromAlbum(data, avatar);
        } else if (requestCode == TAKE_CAMERA) {
            if (imageUri != null) {
                startCrop(imageUri);
                return;
            }
            saveImageFromCamera(avatar);
        }
    }

    private void startCrop(Uri sourceUri) {
        String filename = (dialogType == CHANGE_AVATAR) ? "avatar_cropped.jpg" : "background_cropped.jpg";
        cropFile = new File(activity.getExternalCacheDir(), filename);
        Uri destUri = Uri.fromFile(cropFile);

        UCrop.Options options = new UCrop.Options();
        options.setShowCropFrame(dialogType != CHANGE_AVATAR);
        options.setShowCropGrid(dialogType != CHANGE_AVATAR);
        options.setCircleDimmedLayer(dialogType == CHANGE_AVATAR);
        options.setToolbarColor(Color.BLACK);
        options.setToolbarWidgetColor(Color.WHITE);
        options.setCompressionQuality(90);

        float aspectX, aspectY;
        int maxW, maxH;
        if (dialogType == CHANGE_AVATAR) {
            aspectX = 1;
            aspectY = 1;
            maxW = 512;
            maxH = 512;
        } else {
            // Nav header: match_parent x 160dp, roughly 2:1
            aspectX = 2;
            aspectY = 1;
            maxW = 1080;
            maxH = 540;
        }

        UCrop.of(sourceUri, destUri)
                .withAspectRatio(aspectX, aspectY)
                .withMaxResultSize(maxW, maxH)
                .withOptions(options)
                .start(activity);
    }

    private void saveCroppedImage(@Nullable Intent data, AvatarImageView avatar) {
        if (data == null) return;
        Uri resultUri = UCrop.getOutput(data);
        if (resultUri == null) return;

        String croppedPath = resultUri.getPath();
        if (croppedPath == null) return;

        // Copy cropped file to persistent filesDir to avoid cache cleanup issues
        String persistentName = (dialogType == CHANGE_AVATAR) ? "avatar_image.jpg" : "background_image.jpg";
        File persistentFile = new File(activity.getFilesDir(), persistentName);
        File croppedFile = new File(croppedPath);

        try {
            copyFile(croppedFile, persistentFile);
        } catch (IOException e) {
            Toast.makeText(activity, "Failed to save image", Toast.LENGTH_SHORT).show();
            return;
        }

        // Delete old saved image (only if it's different from the new persistent file)
        File oldFile = Settings.getUserImageFile(key);
        if (oldFile != null && !oldFile.getAbsolutePath().equals(persistentFile.getAbsolutePath())) {
            oldFile.delete();
        }

        Settings.saveFilePath(key, persistentFile.getAbsolutePath());
        if (dialogType == CHANGE_BACKGROUND) {
            imageChangeCallBack.backgroundSourceChange(persistentFile);
        } else if (avatar != null) {
            avatar.setImageBitmap(BitmapFactory.decodeFile(persistentFile.getAbsolutePath()));
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        }
    }

    private void resetToDefault() {
        if (popupWindow != null) popupWindow.dismiss();

        // Delete saved image file
        File oldFile = Settings.getUserImageFile(key);
        if (oldFile != null) {
            oldFile.delete();
        }
        // Clear saved path
        Settings.saveFilePath(key, "");

        if (dialogType == CHANGE_BACKGROUND) {
            imageChangeCallBack.backgroundSourceChange(null);
        } else {
            // Reload default avatar in MainActivity
            if (activity instanceof MainActivity) {
                ((MainActivity) activity).loadAvatar();
            }
        }
    }

    private void saveImageFromCamera(AvatarImageView avatar) {
        Settings.saveFilePath(key,
                outputImage.getPath());
        if (dialogType == CHANGE_BACKGROUND) {
            imageChangeCallBack.backgroundSourceChange(new File(outputImage.getPath()));
        } else {
            avatar.setImageBitmap(BitmapFactory.decodeFile(outputImage.getPath()));
        }
    }

    private void saveImageFromAlbum(Intent data, AvatarImageView avatar) {

        String imagePath = null;
        Uri uri = data.getData();
        if (DocumentsContract.isDocumentUri(activity, uri)) {
            // 如果是document类型的Uri，则通过document id处理
            String docId = DocumentsContract.getDocumentId(uri);
            assert uri != null;
            if ("com.android.providers.media.documents".equals(uri.getAuthority())) {
                String id = docId.split(":")[1];
                // 解析出数字格式的id
                String selection = MediaStore.Images.Media._ID + "=" + id;
                imagePath = getImagePath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, selection);
            } else if ("com.android.providers.downloads.documents".equals(uri.getAuthority())) {
                try {
                    Uri contentUri = ContentUris.withAppendedId(Uri.parse("content: //downloads/public_downloads"), Long.parseLong(docId));
                    imagePath = getImagePath(contentUri, null);
                }catch (NumberFormatException e){
                    e.printStackTrace();
                    Toast.makeText(activity,"获取图片路径出错",Toast.LENGTH_SHORT).show();
                }
            }
        } else {
            assert uri != null;
            if ("content".equalsIgnoreCase(uri.getScheme())) {
                // 如果是content类型的Uri，则使用普通方式处理
                imagePath = getImagePath(uri, null);
            } else if ("file".equalsIgnoreCase(uri.getScheme())) {
                // 如果是file类型的Uri，直接获取图片路径即可
                imagePath = uri.getPath();
            }
        }
        // 根据图片路径显示图片
        saveImage(imagePath, avatar);
    }

    private void saveImage(String imagePath, AvatarImageView avatar) {

        if (imagePath == null){
            return;
        }

        File oleFile;

        oleFile = Settings.getUserImageFile(key);
        if (oleFile != null) {
            oleFile.delete();
        }

        File newFile = new File(imagePath);
        File toFile = new File(activity.getExternalCacheDir(), newFile.getName());
        if (!toFile.exists()) {
            try {
                toFile.createNewFile();
            } catch (IOException ioException) {
                ioException.printStackTrace();
            }
        }
        FileUtils.copyFile(newFile, toFile);
        String newImagePath = toFile.getPath();
        Settings.saveFilePath(key, newImagePath);
        if (dialogType == CHANGE_BACKGROUND) {
            imageChangeCallBack.backgroundSourceChange(new File(newImagePath));
//            background.setImageBitmap(BitmapFactory.decodeFile(toFile.getPath()));
        } else {
            avatar.setImageBitmap(BitmapFactory.decodeFile(toFile.getPath()));
        }
    }

    private String getImagePath(Uri uri, String selection) {
        String path = null;
        // 通过Uri和selection来获取真实的图片路径
        Cursor cursor = activity.getContentResolver().query(uri, null, selection, null, null);
        if (cursor != null) {
            if (cursor.moveToFirst()) {
                int columnIndex = cursor.getColumnIndex(MediaStore.Images.Media.DATA);
                if (columnIndex == -1){
                    return null;
                }
                path = cursor.getString(columnIndex);
            }
            cursor.close();
        }
        return path;
    }

    /**
     * 获取权限的回调
     *
     * @param permissionCode
     */
    @Override
    public void agree(int permissionCode) {
        if (permissionCode == REQUEST_CAMERA_PERMISSION) {
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, imageUri);
            activity.startActivityForResult(intent, TAKE_CAMERA);
        }
        if (permissionCode == REQUEST_STORAGE_PERMISSION) {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("image/*");
            activity.startActivityForResult(intent, PICK_PHOTO);
        }

    }
}
