package com.hippo.ehviewer.ui.scene.gallery.detail;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.CountDownTimer;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.client.lrr.LRRArchiveApi;
import com.hippo.ehviewer.client.lrr.LRRClientProvider;
import com.hippo.ehviewer.client.lrr.LRRCoroutineHelper;
import com.hippo.ehviewer.ui.scene.BaseScene;
import com.hippo.util.IoThreadPoolExecutor;

/**
 * Two-stage confirmation dialog for deleting an archive from the LANraragi server.
 * Stage 1: AlertDialog with warning text.
 * Stage 2: Confirm button has a 3-second countdown before it becomes clickable.
 */
public class DeleteArchiveHelper {

    public interface Callback {
        void onDeleteSuccess(String title);
    }

    public static void show(Activity activity, GalleryInfo galleryInfo, Callback callback) {
        if (activity == null || galleryInfo == null) return;

        String title = galleryInfo.title != null ? galleryInfo.title : "Unknown";
        String arcid = galleryInfo.token;

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.lrr_delete_confirm_title)
                .setMessage(activity.getString(R.string.lrr_delete_confirm_message, title))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.lrr_delete_confirm_button, null)
                .create();

        dialog.setOnShowListener(d -> {
            Button positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            positiveButton.setTextColor(Color.parseColor("#F44336"));
            positiveButton.setEnabled(false);

            new CountDownTimer(3000, 1000) {
                @Override
                public void onTick(long millisUntilFinished) {
                    positiveButton.setText(activity.getString(R.string.lrr_delete_countdown,
                            (int) (millisUntilFinished / 1000) + 1));
                }

                @Override
                public void onFinish() {
                    positiveButton.setText(R.string.lrr_delete_confirm_button);
                    positiveButton.setEnabled(true);
                }
            }.start();

            positiveButton.setOnClickListener(v -> {
                dialog.dismiss();
                performDelete(activity, arcid, title, callback);
            });
        });

        dialog.show();
    }

    private static void performDelete(Activity activity, String arcid, String title, Callback callback) {
        if (arcid == null || arcid.isEmpty()) return;

        IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
            try {
                LRRCoroutineHelper.runSuspend(
                        (scope, cont) -> LRRArchiveApi.deleteArchive(
                                LRRClientProvider.getClient(),
                                LRRClientProvider.getBaseUrl(),
                                arcid, cont)
                );

                activity.runOnUiThread(() -> {
                    if (callback != null) {
                        callback.onDeleteSuccess(title);
                    }
                });
            } catch (Exception e) {
                android.util.Log.e("DeleteArchiveHelper", "Delete archive failed", e);
                activity.runOnUiThread(() ->
                        Toast.makeText(activity,
                                activity.getString(R.string.lrr_delete_failed, e.getMessage()),
                                Toast.LENGTH_LONG).show());
            }
        });
    }
}
