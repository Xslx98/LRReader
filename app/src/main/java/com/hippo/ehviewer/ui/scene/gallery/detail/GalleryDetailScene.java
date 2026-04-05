/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer.ui.scene.gallery.detail;

import static com.hippo.ehviewer.client.EhConfig.TORRENT_PATH;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.TextUtils;
import android.util.Pair;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RatingBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import androidx.transition.TransitionInflater;

import com.hippo.android.resource.AttrResources;
import com.hippo.drawable.RoundSideRectDrawable;
import com.hippo.drawerlayout.DrawerLayout;
import com.hippo.ehviewer.Analytics;
import com.hippo.ehviewer.AppConfig;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.ServiceRegistry;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.settings.AppearanceSettings;
import com.hippo.ehviewer.UrlOpener;
import com.hippo.ehviewer.client.EhCacheKeyFactory;
import com.hippo.ehviewer.client.EhClient;
import com.hippo.ehviewer.client.EhFilter;
import com.hippo.ehviewer.client.EhRequest;
import com.hippo.ehviewer.client.EhTagDatabase;
import com.hippo.ehviewer.client.EhUrl;
import com.hippo.ehviewer.client.EhUtils;
import com.hippo.ehviewer.client.data.GalleryDetail;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.client.data.GalleryTagGroup;
import com.hippo.ehviewer.client.data.ListUrlBuilder;
import com.hippo.ehviewer.client.data.PreviewSet;
import com.hippo.ehviewer.client.data.userTag.UserTagList;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.dao.Filter;
import com.hippo.ehviewer.spider.SpiderQueen;
import com.hippo.ehviewer.ui.CommonOperations;
import com.hippo.ehviewer.ui.GalleryActivity;
import com.hippo.ehviewer.ui.GalleryOpenHelper;
import com.hippo.ehviewer.ui.MainActivity;
import com.hippo.ehviewer.ui.annotation.WholeLifeCircle;
import com.hippo.ehviewer.ui.dialog.ArchiverDownloadDialog;
import com.hippo.ehviewer.ui.scene.BaseScene;
import com.hippo.ehviewer.ui.scene.EhCallback;
import com.hippo.ehviewer.ui.scene.GalleryPreviewsScene;
import com.hippo.ehviewer.ui.scene.TransitionNameFactory;
import com.hippo.ehviewer.ui.scene.download.DownloadsScene;
import com.hippo.ehviewer.ui.scene.gallery.list.FavoritesScene;
import com.hippo.ehviewer.ui.scene.gallery.list.GalleryListScene;
import com.hippo.ehviewer.ui.scene.gallery.list.GalleryListSceneDialog;
import com.hippo.ehviewer.ui.scene.history.HistoryScene;
import com.hippo.ehviewer.util.ClipboardUtil;
import com.hippo.ehviewer.widget.ArchiverDownloadProgress;
import com.hippo.ehviewer.widget.GalleryRatingBar;
import com.hippo.lib.yorozuya.AssertUtils;
import com.hippo.lib.yorozuya.IOUtils;
import com.hippo.lib.yorozuya.IntIdGenerator;
import com.hippo.lib.yorozuya.SimpleHandler;
import com.hippo.lib.yorozuya.ViewUtils;
import com.hippo.reveal.ViewAnimationUtils;
import com.hippo.ripple.Ripple;
import com.hippo.scene.Announcer;
import com.hippo.scene.SceneFragment;
import com.hippo.scene.TransitionHelper;
import android.text.Html;
import com.hippo.text.URLImageGetter;
import com.hippo.util.AppHelper;
import com.hippo.util.DrawableManager;
import com.hippo.util.ExceptionUtils;
import com.hippo.util.IoThreadPoolExecutor;
import com.hippo.util.FileUtils;
import com.hippo.util.ReadableTime;
import com.hippo.view.ViewTransition;
import com.hippo.widget.AutoWrapLayout;
import com.hippo.widget.LoadImageView;
import com.hippo.widget.ObservedTextView;
import com.hippo.widget.ProgressView;
import com.hippo.widget.SimpleGridAutoSpanLayout;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import okhttp3.OkHttpClient;

public class GalleryDetailScene extends BaseScene implements View.OnClickListener,
        com.hippo.ehviewer.download.DownloadInfoListener,
        View.OnLongClickListener {

    @IntDef({STATE_INIT, STATE_NORMAL, STATE_REFRESH, STATE_REFRESH_HEADER, STATE_FAILED})
    @Retention(RetentionPolicy.SOURCE)
    private @interface State {
    }

    private static final int REQUEST_CODE_COMMENT_GALLERY = 0;

    private static final int STATE_INIT = -1;
    private static final int STATE_NORMAL = 0;
    private static final int STATE_REFRESH = 1;
    private static final int STATE_REFRESH_HEADER = 2;
    private static final int STATE_FAILED = 3;

    public final static String KEY_ACTION = "action";
    public static final String ACTION_GALLERY_INFO = "action_gallery_info";
    public static final String ACTION_DOWNLOAD_GALLERY_INFO = "action_download_gallery_info";
    public static final String ACTION_GID_TOKEN = "action_gid_token";

    public static final String KEY_GALLERY_INFO = "gallery_info";
    public static final String KEY_GID = "gid";
    public static final String KEY_TOKEN = "token";
    public static final String KEY_PAGE = "page";

    private static final String KEY_GALLERY_DETAIL = "gallery_detail";
    private static final String KEY_REQUEST_ID = "request_id";

    private static final boolean TRANSITION_ANIMATION_DISABLED = true;

    /*---------------
     View life cycle
     ---------------*/
    @Nullable
    private TextView mTip;
    @Nullable
    private ViewTransition mViewTransition;
    // Header
    @Nullable
    private View mHeader;
    @Nullable
    private View mColorBg;
    @Nullable
    private LoadImageView mThumb;
    @Nullable
    private TextView mTitle;
    @Nullable
    private TextView mUploader;
    @Nullable
    private ImageView mOtherActions;
    @Nullable
    private ViewGroup mActionGroup;
    @Nullable
    private TextView mDownload;

    @Nullable
    private TextView mHaveNewVersion;
    @Nullable
    private View mRead;
    // Below header
    @Nullable
    private View mBelowHeader;
    // Info
    @Nullable
    private TextView mPages;
    @Nullable
    private TextView mSize;
    // Actions
    @Nullable
    private View mActions;
    @Nullable
    private TextView mRatingText;
    @Nullable
    private RatingBar mRating;
    @Nullable
    private View mHeartGroup;
    @Nullable
    private TextView mHeart;
    @Nullable
    private TextView mHeartOutline;
    // Tags
    @Nullable
    private LinearLayout mTags;
    @Nullable
    private TextView mNoTags;
    // Progress
    @Nullable
    private View mProgress;
    @Nullable
    private ArchiverDownloadProgress mArchiverDownloadProgress;
    @Nullable
    private ViewTransition mViewTransition2;
    @Nullable
    private PopupMenu mPopupMenu;

    @WholeLifeCircle
    private int mDownloadState;

    @Nullable
    private String mAction;
    @Nullable
    private GalleryInfo mGalleryInfo;
    private DownloadInfo mDownloadInfo;
    private long mGid;
    private String mToken;

    @Nullable
    private GalleryDetail mGalleryDetail;
    private int mRequestId = IntIdGenerator.INVALID_ID;

    @Nullable
    private Map<String, String> properties;

    @State
    private int mState = STATE_INIT;
    private GalleryUpdateDialog myUpdateDialog;
    private GalleryListSceneDialog tagDialog;

    private boolean useNetWorkLoadThumb = false;

    private Context mContext;
    private MainActivity activity;

    private ExecutorService executorService;
    private EhTagDatabase ehTags;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private void handleArgs(Bundle args) {
        if (args == null) {
            return;
        }

        String action = args.getString(KEY_ACTION);
        mAction = action;
        if (ACTION_GALLERY_INFO.equals(action)) {
            mGalleryInfo = args.getParcelable(KEY_GALLERY_INFO);
            // Add history
            if (null != mGalleryInfo) {
                com.hippo.util.IoThreadPoolExecutor.Companion.getInstance().execute(() ->
                    EhDB.putHistoryInfo(mGalleryInfo));
            }
        } else if (ACTION_GID_TOKEN.equals(action)) {
            mGid = args.getLong(KEY_GID);
            mToken = args.getString(KEY_TOKEN);
        } else if (ACTION_DOWNLOAD_GALLERY_INFO.equals(action)) {
            try {
                mDownloadInfo = args.getParcelable(KEY_GALLERY_INFO);
                mGalleryInfo = mDownloadInfo;
                if (null != mGalleryInfo) {
                    com.hippo.util.IoThreadPoolExecutor.Companion.getInstance().execute(() ->
                    EhDB.putHistoryInfo(mGalleryInfo));
                }
            } catch (ClassCastException e) {
                mGalleryInfo = args.getParcelable(KEY_GALLERY_INFO);
                if (null != mGalleryInfo) {
                    com.hippo.util.IoThreadPoolExecutor.Companion.getInstance().execute(() ->
                    EhDB.putHistoryInfo(mGalleryInfo));
                }
            }
            // Add history

        }
    }

    @Nullable
    private String getGalleryDetailUrl() {
        long gid;
        String token;
        if (mGalleryDetail != null) {
            gid = mGalleryDetail.gid;
            token = mGalleryDetail.token;
        } else if (mGalleryInfo != null) {
            gid = mGalleryInfo.gid;
            token = mGalleryInfo.token;
        } else if (ACTION_GID_TOKEN.equals(mAction)) {
            gid = mGid;
            token = mToken;
        } else {
            return null;
        }
        return EhUrl.getGalleryDetailUrl(gid, token, 0, false);
    }

    // -1 for error
    private long getGid() {
        if (mGalleryDetail != null) {
            return mGalleryDetail.gid;
        } else if (mGalleryInfo != null) {
            return mGalleryInfo.gid;
        } else if (ACTION_GID_TOKEN.equals(mAction)) {
            return mGid;
        } else {
            return -1;
        }
    }

    private String getToken() {
        if (mGalleryDetail != null) {
            return mGalleryDetail.token;
        } else if (mGalleryInfo != null) {
            return mGalleryInfo.token;
        } else if (ACTION_GID_TOKEN.equals(mAction)) {
            return mToken;
        } else {
            return null;
        }
    }

    private String getUploader() {
        if (mGalleryDetail != null) {
            return mGalleryDetail.uploader;
        } else if (mGalleryInfo != null) {
            return mGalleryInfo.uploader;
        } else {
            return null;
        }
    }

    // -1 for error
    private int getCategory() {
        if (mGalleryDetail != null) {
            return mGalleryDetail.category;
        } else if (mGalleryInfo != null) {
            return mGalleryInfo.category;
        } else {
            return -1;
        }
    }

    private GalleryInfo getGalleryInfo() {
        if (null != mGalleryDetail) {
            return mGalleryDetail;
        } else if (null != mGalleryInfo) {
            return mGalleryInfo;
        } else {
            return null;
        }
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (savedInstanceState == null) {
            onInit();
        } else {
            onRestore(savedInstanceState);
        }

        if (null == properties && mGalleryInfo != null) {

            Date date = new Date();
            @SuppressLint("SimpleDateFormat") DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            properties = new HashMap<>();
            properties.put("Title", mGalleryInfo.title);
            properties.put("Time", dateFormat.format(date));
        }
    }

    private void onInit() {
        handleArgs(getArguments());
    }

    private void onRestore(Bundle savedInstanceState) {
        mAction = savedInstanceState.getString(KEY_ACTION);
        mGalleryInfo = savedInstanceState.getParcelable(KEY_GALLERY_INFO);
        mGid = savedInstanceState.getLong(KEY_GID);
        mToken = savedInstanceState.getString(KEY_TOKEN);
        mGalleryDetail = savedInstanceState.getParcelable(KEY_GALLERY_DETAIL);
        mRequestId = savedInstanceState.getInt(KEY_REQUEST_ID);
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);

        if (mAction != null) {
            outState.putString(KEY_ACTION, mAction);
        }
        if (mGalleryInfo != null) {
            outState.putParcelable(KEY_GALLERY_INFO, mGalleryInfo);
        }
        outState.putLong(KEY_GID, mGid);
        if (mToken != null) {
            outState.putString(KEY_TOKEN, mToken);
        }
        if (mGalleryDetail != null) {
            outState.putParcelable(KEY_GALLERY_DETAIL, mGalleryDetail);
        }
        outState.putInt(KEY_REQUEST_ID, mRequestId);
    }

    @Nullable
    @Override
    public View onCreateView2(LayoutInflater inflater, @Nullable ViewGroup container,
                              @Nullable Bundle savedInstanceState) {
        Context context = getEHContext();
        // Get download state
        long gid = getGid();
        if (gid != -1) {
            AssertUtils.assertNotNull(context);
            mDownloadState = ServiceRegistry.INSTANCE.getDataModule().getDownloadManager().getDownloadState(gid);
        } else {
            mDownloadState = DownloadInfo.STATE_INVALID;
        }

        View view = inflater.inflate(R.layout.scene_gallery_detail, container, false);

        ViewGroup main = (ViewGroup) ViewUtils.$$(view, R.id.main);
        View mainView = ViewUtils.$$(main, R.id.scroll_view);
        View progressView = ViewUtils.$$(main, R.id.progress_view);
        mTip = (TextView) ViewUtils.$$(main, R.id.tip);
        mViewTransition = new ViewTransition(mainView, progressView, mTip);

        assert context != null;
        AssertUtils.assertNotNull(context);

        View actionsScrollView = ViewUtils.$$(view, R.id.actions_scroll_view);
        setDrawerGestureBlocker(new DrawerLayout.GestureBlocker() {
            private void transformPointToViewLocal(int[] point, View child) {
                ViewParent viewParent = child.getParent();

                while (viewParent instanceof View view) {
                    point[0] += view.getScrollX() - child.getLeft();
                    point[1] += view.getScrollY() - child.getTop();

                    if (view instanceof DrawerLayout) {
                        break;
                    }

                    child = view;
                    viewParent = child.getParent();
                }
            }

            @Override
            public boolean shouldBlockGesture(MotionEvent ev) {
                int[] point = new int[]{(int) ev.getX(), (int) ev.getY()};
                transformPointToViewLocal(point, actionsScrollView);
                return !isDrawersVisible()
                        && point[0] > 0 && point[0] < actionsScrollView.getWidth()
                        && point[1] > 0 && point[1] < actionsScrollView.getHeight();
            }
        });

        Drawable drawable = DrawableManager.getVectorDrawable(context, R.drawable.big_sad_pandroid);
        drawable.setBounds(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
        mTip.setCompoundDrawables(null, drawable, null, null);
        mTip.setOnClickListener(this);

        mBelowHeader = mainView.findViewById(R.id.below_header);
        View belowHeader = mBelowHeader;

        boolean isDarkTheme = !AttrResources.getAttrBoolean(context, androidx.appcompat.R.attr.isLightTheme);
        mHeader = ViewUtils.$$(belowHeader, R.id.header);
        mColorBg = ViewUtils.$$(mHeader, R.id.color_bg);
        mThumb = (LoadImageView) ViewUtils.$$(mHeader, R.id.thumb);
        mTitle = (TextView) ViewUtils.$$(mHeader, R.id.title);
        mUploader = (TextView) ViewUtils.$$(mHeader, R.id.uploader);
        mOtherActions = (ImageView) ViewUtils.$$(mHeader, R.id.other_actions);
        mActionGroup = (ViewGroup) ViewUtils.$$(mHeader, R.id.action_card);
        mDownload = (TextView) ViewUtils.$$(mActionGroup, R.id.download);
        mHaveNewVersion = (TextView) ViewUtils.$$(mHeader, R.id.new_version);
        mArchiverDownloadProgress = (ArchiverDownloadProgress) ViewUtils.$$(mHeader, R.id.archiver_download_progress);
        mRead = ViewUtils.$$(mActionGroup, R.id.read);
        Ripple.addRipple(mOtherActions, isDarkTheme);
        Ripple.addRipple(mDownload, isDarkTheme);
        Ripple.addRipple(mRead, isDarkTheme);
        mUploader.setOnClickListener(this);
        mOtherActions.setOnClickListener(this);
        mDownload.setOnClickListener(this);
        mDownload.setOnLongClickListener(this);
        mHaveNewVersion.setOnClickListener(this);
        mRead.setOnClickListener(this);
        mTitle.setOnClickListener(this);

        mUploader.setOnLongClickListener(this);

        View infoView = ViewUtils.$$(belowHeader, R.id.info);
        mPages = (TextView) ViewUtils.$$(infoView, R.id.pages);
        mSize = (TextView) ViewUtils.$$(infoView, R.id.size);

        mActions = ViewUtils.$$(belowHeader, R.id.actions);
        mRatingText = (TextView) ViewUtils.$$(mActions, R.id.rating_text);
        mRating = (RatingBar) ViewUtils.$$(mActions, R.id.rating);
        mHeartGroup = ViewUtils.$$(mActions, R.id.heart_group);
        mHeart = (TextView) ViewUtils.$$(mHeartGroup, R.id.heart);
        mHeartOutline = (TextView) ViewUtils.$$(mHeartGroup, R.id.heart_outline);
        Ripple.addRipple(mHeartGroup, isDarkTheme);
        mHeartGroup.setOnClickListener(this);
        mHeartGroup.setOnLongClickListener(this);
        ensureActionDrawable(context);

        // Make rating bar interactive: touch/drag to rate, release to confirm
        mRating.setIsIndicator(false);
        mRating.setOnRatingBarChangeListener((ratingBar, rating, fromUser) -> {
            if (!fromUser || mGalleryDetail == null) return;
            final float newRating = rating;
            final String arcid = mGalleryDetail.token;
            final GalleryDetail gd = mGalleryDetail;

            // Update local UI immediately
            gd.rating = newRating;
            gd.rated = true;
            if (mRatingText != null) {
                mRatingText.setText(com.hippo.ehviewer.client.lrr.data.LRRArchive.buildRatingEmoji(Math.round(newRating)));
            }

            // Write to LANraragi server
            RatingHelper.saveRatingToServer(arcid, newRating, null);
        });

        mTags = (LinearLayout) ViewUtils.$$(belowHeader, R.id.tags);
        mNoTags = (TextView) ViewUtils.$$(mTags, R.id.no_tags);

        mProgress = ViewUtils.$$(mainView, R.id.progress);

        mViewTransition2 = new ViewTransition(mBelowHeader, mProgress);

        if (prepareData()) {
            if (mGalleryDetail != null) {
                bindViewSecond();
                setTransitionName();
                adjustViewVisibility(STATE_NORMAL, false);
            } else if (mGalleryInfo != null) {
                bindViewFirst();
                setTransitionName();
                adjustViewVisibility(STATE_REFRESH_HEADER, false);
            } else {
                adjustViewVisibility(STATE_REFRESH, false);
            }
        } else {
            mTip.setText(R.string.error_cannot_find_gallery);
            adjustViewVisibility(STATE_FAILED, false);
        }

        ServiceRegistry.INSTANCE.getDataModule().getDownloadManager().addDownloadInfoListener(this);
        if (myUpdateDialog == null) {
            myUpdateDialog = new GalleryUpdateDialog(this, context);
        }
        return view;
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        Context context = getEHContext();
        AssertUtils.assertNotNull(context);
        ServiceRegistry.INSTANCE.getDataModule().getDownloadManager().removeDownloadInfoListener(this);

        setDrawerGestureBlocker(null);

        mTip = null;
        mViewTransition = null;

        mHeader = null;
        mColorBg = null;
        mThumb = null;
        mTitle = null;
        mUploader = null;
        mOtherActions = null;
        mActionGroup = null;
        mDownload = null;

        mHaveNewVersion = null;
        mRead = null;
        mBelowHeader = null;
        mArchiverDownloadProgress = null;

        mPages = null;
        mSize = null;

        mActions = null;
        mRatingText = null;
        mRating = null;
        mHeartGroup = null;
        mHeart = null;
        mHeartOutline = null;

        mTags = null;
        mNoTags = null;

        mProgress = null;

        mViewTransition2 = null;

        mPopupMenu = null;

        properties = null;
    }

    private boolean prepareData() {
        Context context = getEHContext();
        AssertUtils.assertNotNull(context);

        if (mGalleryDetail != null) {
            return true;
        }

        long gid = getGid();
        if (gid == -1) {
            return false;
        }

        // Get from cache
        mGalleryDetail = ServiceRegistry.INSTANCE.getDataModule().getGalleryDetailCache().get(gid);
        if (mGalleryDetail != null) {
            return true;
        }

        EhApplication application = (EhApplication) context.getApplicationContext();
        if (application.containGlobalStuff(mRequestId)) {
            // request exist
            return true;
        }

        // Do request
        return request();
    }

    private boolean request(String url, int resultMode) {
        // LANraragi: ignore E-Hentai URL, fetch archive metadata via LRRArchiveApi
        return request();
    }

    private boolean request() {
        Context context = getEHContext();
        if (context == null) return false;

        // Get arcid from mGalleryInfo.token (set by LRRArchive.toGalleryInfo())
        String arcid = getToken();
        String serverUrl = com.hippo.ehviewer.client.lrr.LRRAuthManager.getServerUrl();
        if (arcid == null || arcid.isEmpty() || serverUrl == null || serverUrl.isEmpty()) {
            return false;
        }

        OkHttpClient client = ServiceRegistry.INSTANCE.getNetworkModule().getOkHttpClient();

        com.hippo.util.IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
            try {
                com.hippo.ehviewer.client.lrr.data.LRRArchive archive =
                        (com.hippo.ehviewer.client.lrr.data.LRRArchive) com.hippo.ehviewer.client.lrr.LRRCoroutineHelper.runSuspend(
                                (scope, cont) -> com.hippo.ehviewer.client.lrr.LRRArchiveApi.getArchiveMetadata(
                                        client, serverUrl, arcid, cont)
                        );
                GalleryDetail gd = archive.toGalleryDetail();

                // Query LANraragi categories to determine favorite status
                try {
                    @SuppressWarnings("unchecked")
                    java.util.List<com.hippo.ehviewer.client.lrr.data.LRRCategory> categories =
                            (java.util.List<com.hippo.ehviewer.client.lrr.data.LRRCategory>) com.hippo.ehviewer.client.lrr.LRRCoroutineHelper.runSuspend(
                                    (scope, cont) -> com.hippo.ehviewer.client.lrr.LRRCategoryApi.getCategories(client, serverUrl, cont)
                            );
                    java.util.List<String> matchedNames = new java.util.ArrayList<>();
                    for (com.hippo.ehviewer.client.lrr.data.LRRCategory cat : categories) {
                        // Only check static categories (dynamic ones have empty archives list)
                        if (!cat.isDynamic() && cat.archives != null && cat.archives.contains(arcid)) {
                            matchedNames.add(cat.name);
                        }
                    }
                    if (!matchedNames.isEmpty()) {
                        gd.isFavorited = true;
                        if (matchedNames.size() == 1) {
                            gd.favoriteName = matchedNames.get(0);
                        } else {
                            gd.favoriteName = matchedNames.get(0) + getString(R.string.lrr_category_info_suffix) + matchedNames.size() + getString(R.string.lrr_category_count_suffix);
                        }
                    }
                } catch (Exception catEx) {
                    android.util.Log.w("GalleryDetailScene", "Failed to query categories for favorite status", catEx);
                    // Non-fatal: favorite status just won't show
                }

                // Cache the detail
                ServiceRegistry.INSTANCE.getDataModule().getGalleryDetailCache().put(gd.gid, gd);

                Activity activity = getActivity();
                if (activity != null) {
                    activity.runOnUiThread(() -> onGetGalleryDetailSuccess(gd));
                }
            } catch (Exception e) {
                android.util.Log.e("GalleryDetailScene", "LRR metadata fetch failed", e);
                Activity activity = getActivity();
                if (activity != null) {
                    activity.runOnUiThread(() -> onGetGalleryDetailFailure(e));
                }
            }
        });
        return true;
    }

    private void setActionDrawable(TextView text, Drawable drawable) {
        drawable.setBounds(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
        text.setCompoundDrawables(null, drawable, null, null);
    }

    private void ensureActionDrawable(Context context) {
        Drawable heart = DrawableManager.getVectorDrawable(context, R.drawable.v_heart_primary_x48);
        if (mHeart != null) {
            setActionDrawable(mHeart, heart);
        }
        Drawable heartOutline = DrawableManager.getVectorDrawable(context, R.drawable.v_heart_outline_primary_x48);
        if (mHeartOutline != null) {
            setActionDrawable(mHeartOutline, heartOutline);
        }
    }

    private boolean createCircularReveal() {
        if (mColorBg == null) {
            return false;
        }

        int w = mColorBg.getWidth();
        int h = mColorBg.getHeight();
        if (ViewCompat.isAttachedToWindow(mColorBg) && w != 0 && h != 0) {
            Context context = getEHContext();
            if (context == null) {
                return false;
            }
            Resources resources = context.getResources();
            int keylineMargin = resources.getDimensionPixelSize(R.dimen.keyline_margin);
            int thumbWidth = resources.getDimensionPixelSize(R.dimen.gallery_detail_thumb_width);
            int thumbHeight = resources.getDimensionPixelSize(R.dimen.gallery_detail_thumb_height);

            int x = thumbWidth / 2 + keylineMargin;
            int y = thumbHeight / 2 + keylineMargin;

            int radiusX = Math.max(Math.abs(x), Math.abs(w - x));
            int radiusY = Math.max(Math.abs(y), Math.abs(h - y));
            float radius = (float) Math.hypot(radiusX, radiusY);

            ViewAnimationUtils.createCircularReveal(mColorBg, x, y, 0, radius).setDuration(300).start();
            return true;
        } else {
            return false;
        }
    }

    private void adjustViewVisibility(int state, boolean animation) {
        if (state == mState) {
            return;
        }
        if (mViewTransition == null || mViewTransition2 == null) {
            return;
        }

        int oldState = mState;
        mState = state;

        animation = !TRANSITION_ANIMATION_DISABLED && animation;

        switch (state) {
            case STATE_NORMAL:
                // Show mMainView
                mViewTransition.showView(0, animation);
                // Show mBelowHeader
                mViewTransition2.showView(0, animation);
                break;
            case STATE_REFRESH:
                // Show mProgressView
                mViewTransition.showView(1, animation);
                break;
            case STATE_REFRESH_HEADER:
                // Show mMainView
                mViewTransition.showView(0, animation);
                // Show mProgress
                mViewTransition2.showView(1, animation);
                break;
            default:
            case STATE_INIT:
            case STATE_FAILED:
                // Show mFailedView
                mViewTransition.showView(2, animation);
                break;
        }
        Context context = getEHContext();
        if (context == null) {
            return;
        }
        if ((oldState == STATE_INIT || oldState == STATE_FAILED || oldState == STATE_REFRESH) &&
                (state == STATE_NORMAL || state == STATE_REFRESH_HEADER) && AttrResources.getAttrBoolean(context, androidx.appcompat.R.attr.isLightTheme)) {
            if (!createCircularReveal()) {
                SimpleHandler.getInstance().post(this::createCircularReveal);
            }
        }
    }

    private void bindViewFirst() {
        if (mGalleryDetail != null) {
            return;
        }
        if (mThumb == null || mTitle == null || mUploader == null) {
            return;
        }

        if ((ACTION_GALLERY_INFO.equals(mAction) || ACTION_DOWNLOAD_GALLERY_INFO.equals(mAction)) && mGalleryInfo != null) {
            GalleryInfo gi = mGalleryInfo;
            mThumb.load(EhCacheKeyFactory.getThumbKey(gi.gid), gi.thumb);
            mTitle.setText(EhUtils.getSuitableTitle(gi));
            mUploader.setText(gi.uploader);
            updateDownloadText();
        }
    }

    private void updateFavoriteDrawable() {
        GalleryDetail gd = mGalleryDetail;
        if (gd == null) {
            return;
        }
        if (mHeart == null || mHeartOutline == null) {
            return;
        }

        IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
            boolean isFav = gd.isFavorited || EhDB.containLocalFavorites(gd.gid);
            android.app.Activity a = getActivity();
            if (a != null) {
                a.runOnUiThread(() -> {
                    if (mHeart == null || mHeartOutline == null) return;
                    if (isFav) {
                        mHeart.setVisibility(View.VISIBLE);
                        if (gd.favoriteName == null) {
                            mHeart.setText(R.string.local_favorites);
                        } else {
                            mHeart.setText(gd.favoriteName);
                        }
                        mHeartOutline.setVisibility(View.GONE);
                    } else {
                        mHeart.setVisibility(View.GONE);
                        mHeartOutline.setVisibility(View.VISIBLE);
                    }
                });
            }
        });
    }

    private void bindViewSecond() {
        try {
            bindViewSecondInternal();
        } catch (Exception e) {
            android.util.Log.e("GalleryDetailScene", "bindViewSecond crashed", e);
        }
    }

    private void bindViewSecondInternal() {
        GalleryDetail gd = mGalleryDetail;
        if (gd == null) {
            return;
        }
        if (mThumb == null || mTitle == null || mUploader == null ||
                mPages == null || mSize == null ||
                mRatingText == null || mRating == null) {
            return;
        }
        Resources resources = getResources2();
        if (gd.newVersions != null && mHaveNewVersion != null && resources != null) {
            mHaveNewVersion.setVisibility(View.VISIBLE);
            mHaveNewVersion.setBackground(ResourcesCompat.getDrawable(resources, R.drawable.new_version_style, null));
        } else {
            if (mHaveNewVersion != null) {
                mHaveNewVersion.setVisibility(View.GONE);
            }
        }
        if (null == mGalleryInfo) {
            mThumb.load(EhCacheKeyFactory.getThumbKey(gd.gid), gd.thumb);
        } else {
            if (useNetWorkLoadThumb) {
                mThumb.load(EhCacheKeyFactory.getThumbKey(gd.gid), gd.thumb);
                useNetWorkLoadThumb = false;
            } else {
                mThumb.load(EhCacheKeyFactory.getThumbKey(gd.gid), gd.thumb, false);
            }
        }

        mTitle.setText(EhUtils.getSuitableTitle(gd));
        mUploader.setText(gd.uploader);
        updateDownloadText();

        GalleryInfo galleryInfo = getGalleryInfo();
        bindReadProgress(galleryInfo);

        mSize.setText(gd.size);

        // LANraragi rating display
        if (gd.rating > 0) {
            mRatingText.setText(String.format("%.0f★", gd.rating));
            mRating.setRating(gd.rating);
        } else {
            mRatingText.setText("Not rated");
            mRating.setRating(0);
        }

        updateFavoriteDrawable();
        bindArchiverProgress(gd);
        bindTags(gd.tags);
    }

    public void bindArchiverProgress(GalleryDetail gd) {
        if (mArchiverDownloadProgress != null) {
            mArchiverDownloadProgress.initThread(gd);
        }
    }

    private void bindReadProgress(GalleryInfo info) {
        if (mContext == null) {
            mContext = getEHContext();
            if (mContext == null) {
                return;
            }
        }
        if (executorService == null) {
            executorService = ServiceRegistry.INSTANCE.getAppModule().getExecutorService();
        }

        executorService.submit(() -> {
            int startPage = SpiderQueen.findStartPage(this.mContext, info);
            int pages = info.pages;
            String text;
            if (startPage > 0) {
                text = startPage + 1 + "/" + pages + "P";
            } else {
                text = "0/" + pages + "P";
            }
            handler.post(() -> {
                if (mPages == null) {
                    return;
                }
                mPages.setText(text);
            });
        });
    }

    private void bindTags(GalleryTagGroup[] tagGroups) {
        Context context = getEHContext();
        LayoutInflater inflater = getLayoutInflater2();
        Resources resources = getResources2();
        if (null == context || null == resources || null == mTags || null == mNoTags) {
            return;
        }

        mTags.removeViews(1, mTags.getChildCount() - 1);
        if (tagGroups == null || tagGroups.length == 0) {
            mNoTags.setVisibility(View.VISIBLE);
            return;
        } else {
            mNoTags.setVisibility(View.GONE);
        }

        ehTags = AppearanceSettings.getShowTagTranslations() ? EhTagDatabase.getInstance(context) : null;

        int colorTag = AttrResources.getAttrColor(context, R.attr.tagBackgroundColor);
        int colorName = AttrResources.getAttrColor(context, R.attr.tagGroupBackgroundColor);
        for (GalleryTagGroup tg : tagGroups) {
            LinearLayout ll = (LinearLayout) inflater.inflate(R.layout.gallery_tag_group, mTags, false);
            ll.setOrientation(LinearLayout.HORIZONTAL);
            mTags.addView(ll);

            String readableTagName = null;
            if (ehTags != null) {
                readableTagName = ehTags.getTranslation("n:" + tg.groupName);
            }

            TextView tgName = (TextView) inflater.inflate(R.layout.item_gallery_tag, ll, false);
            ll.addView(tgName);
            tgName.setText(readableTagName != null ? readableTagName : tg.groupName);
            tgName.setBackground(new RoundSideRectDrawable(colorName));

            String prefix = EhTagDatabase.namespaceToPrefix(tg.groupName);
            if (prefix == null) {
                prefix = "";
            }

            AutoWrapLayout awl = new AutoWrapLayout(context);
            ll.addView(awl, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            for (int j = 0, z = tg.size(); j < z; j++) {
                TextView tag = (TextView) inflater.inflate(R.layout.item_gallery_tag, awl, false);
                awl.addView(tag);
                String tagStr = tg.getTagAt(j);

                String readableTag = null;
                if (ehTags != null) {
                    readableTag = ehTags.getTranslation(prefix + tagStr);
                }

                tag.setText(readableTag != null ? readableTag : tagStr);
                tag.setBackground(new RoundSideRectDrawable(colorTag));
                tag.setTag(R.id.tag, tg.groupName + ":" + tagStr);
                tag.setOnClickListener(this);
                tag.setOnLongClickListener(this);
            }
        }
    }

    private void setTransitionName() {
        long gid = getGid();

        if (gid != -1 && mThumb != null &&
                mTitle != null && mUploader != null) {
            ViewCompat.setTransitionName(mThumb, TransitionNameFactory.getThumbTransitionName(gid));
            ViewCompat.setTransitionName(mTitle, TransitionNameFactory.getTitleTransitionName(gid));
            ViewCompat.setTransitionName(mUploader, TransitionNameFactory.getUploaderTransitionName(gid));
        }
    }

    @SuppressLint("NonConstantResourceId")
    private void ensurePopMenu() {
        if (mPopupMenu != null || mOtherActions == null) {
            return;
        }

        Context context = getEHContext();
        AssertUtils.assertNotNull(context);
        PopupMenu popup = new PopupMenu(context, mOtherActions, Gravity.TOP);
        mPopupMenu = popup;
        popup.getMenuInflater().inflate(R.menu.scene_gallery_detail, popup.getMenu());
        // Show delete menu item only when connected to LANraragi
        MenuItem deleteItem = popup.getMenu().findItem(R.id.action_lrr_delete);
        if (deleteItem != null) {
            deleteItem.setVisible(com.hippo.ehviewer.client.lrr.LRRAuthManager.getServerUrl() != null);
        }

        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case R.id.action_open_in_other_app:
                    String url = getGalleryDetailUrl();
                    Activity activity = getActivity2();
                    if (null != url && null != activity) {
                        UrlOpener.openUrl(activity, url, false);
                    }
                    break;
                case R.id.action_refresh:
                    if (mState != STATE_REFRESH && mState != STATE_REFRESH_HEADER) {
                        adjustViewVisibility(STATE_REFRESH, true);
                        request();
                    }
                    break;
                case R.id.action_lrr_delete:
                    DeleteArchiveHelper.show(getActivity2(), mGalleryInfo, title -> {
                        showTip(getString(R.string.lrr_delete_success, title), LENGTH_LONG);
                        onBackPressed();
                    });
                    break;
            }
            return true;
        });
    }


    @Override
    public void onClick(View v) {
        mContext = getEHContext();
        activity = getActivity2();
        if (null == mContext || null == activity) {
            return;
        }

        if (mTip == v) {
            if (request()) {
                adjustViewVisibility(STATE_REFRESH, true);
            }
        } else if (mOtherActions == v) {
            ensurePopMenu();
            if (mPopupMenu != null) {
                mPopupMenu.show();
            }
        } else if (mUploader == v) {
            String uploader = getUploader();
            if (TextUtils.isEmpty(uploader)) {
                return;
            }
            ListUrlBuilder lub = new ListUrlBuilder();
            lub.setMode(ListUrlBuilder.MODE_UPLOADER);
            lub.setKeyword(uploader);
            GalleryListScene.startScene(this, lub);
        } else if (mDownload == v) {
            onDownload();

        } else if (mHaveNewVersion == v) {
            if (mGalleryDetail == null) {
                return;
            }
            myUpdateDialog.showSelectDialog(mGalleryDetail);
        } else if (mRead == v) {
            GalleryInfo galleryInfo = null;
            if (mGalleryInfo != null) {
                galleryInfo = mGalleryInfo;
            } else if (mGalleryDetail != null) {
                galleryInfo = mGalleryDetail;
            }
            if (galleryInfo != null) {
                Intent intent = GalleryOpenHelper.buildReadIntent(activity, galleryInfo);
                startActivity(intent);
            }
        } else if (mHeartGroup == v) {
            // LANraragi: Show category selection dialog
            if (mGalleryDetail != null) {
                CategoryDialogHelper.showCategoryDialog(activity, mGalleryDetail,
                        (isFavorited, favoriteName) -> {
                            if (mGalleryDetail != null) {
                                mGalleryDetail.isFavorited = isFavorited;
                                mGalleryDetail.favoriteName = favoriteName;
                                updateFavoriteDrawable();
                            }
                        });
            }
        } else if (mTitle == v) {
            if (mGalleryDetail != null && mGalleryDetail.title != null) {
                ClipboardUtil.copyText(mGalleryDetail.title);
                Toast.makeText(getContext(), R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show();
            }
        } else {
            Object o = v.getTag(R.id.tag);
            if (o instanceof String tag) {
                ListUrlBuilder lub = new ListUrlBuilder();
                lub.setMode(ListUrlBuilder.MODE_TAG);
                lub.setKeyword(tag);
                GalleryListScene.startScene(this, lub);
                return;
            }

            GalleryInfo galleryInfo = getGalleryInfo();
            o = v.getTag(R.id.index);
            if (null != galleryInfo && o instanceof Integer) {
                int index = (Integer) o;
                Intent intent = new Intent(mContext, GalleryActivity.class);
                intent.setAction(GalleryActivity.ACTION_EH);
                intent.putExtra(GalleryActivity.KEY_GALLERY_INFO, galleryInfo);
                intent.putExtra(GalleryActivity.KEY_PAGE, index);
                startActivity(intent);
            }
        }
    }

    private void showFilterUploaderDialog() {
        Context context = getEHContext();
        String uploader = getUploader();
        if (context == null || uploader == null) {
            return;
        }

        new AlertDialog.Builder(context)
                .setMessage(getString(R.string.filter_the_uploader, uploader))
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    if (which != DialogInterface.BUTTON_POSITIVE) {
                        return;
                    }

                    Filter filter = new Filter();
                    filter.mode = EhFilter.MODE_UPLOADER;
                    filter.text = uploader;
                    EhFilter.getInstance().addFilter(filter);

                    showTip(R.string.filter_added, LENGTH_SHORT);
                }).show();
    }

    private void showFilterTagDialog(String tag) {
        Context context = getEHContext();
        if (context == null) {
            return;
        }

        new AlertDialog.Builder(context)
                .setMessage(getString(R.string.filter_the_tag, tag))
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    if (which != DialogInterface.BUTTON_POSITIVE) {
                        return;
                    }

                    Filter filter = new Filter();
                    filter.mode = EhFilter.MODE_TAG;
                    filter.text = tag;
                    EhFilter.getInstance().addFilter(filter);

                    showTip(R.string.filter_added, LENGTH_SHORT);
                }).show();
    }

    private void showTagDialog(final String tag) {
        if (tagDialog == null) {
            tagDialog = new GalleryListSceneDialog(this);
        }
        if (ehTags == null) {
            ehTags = EhTagDatabase.getInstance(mContext);
        }
        tagDialog.setTagName(tag);
        tagDialog.showTagLongPressDialog(ehTags);
    }

    @Override
    public void setTagList(UserTagList result) {
        super.setTagList(result);
    }

    @Override
    public boolean onLongClick(View v) {
        mContext = getEHContext();
        activity = getActivity2();
        if (null == activity) {
            return false;
        }

        if (mUploader == v) {
            showFilterUploaderDialog();
        } else if (mDownload == v) {
//            GalleryInfo galleryInfo = getGalleryInfo();
//            if (galleryInfo != null) {
//                CommonOperations.startDownload(activity, galleryInfo, true);
//            }
            onDownload();
            return true;
        } else if (v == mHeartGroup) {
            // Long press also shows category dialog (same as click)
            if (mGalleryDetail != null) {
                CategoryDialogHelper.showCategoryDialog(activity, mGalleryDetail,
                        (isFavorited, favoriteName) -> {
                            if (mGalleryDetail != null) {
                                mGalleryDetail.isFavorited = isFavorited;
                                mGalleryDetail.favoriteName = favoriteName;
                                updateFavoriteDrawable();
                            }
                        });
            }
        } else {
            String tag = (String) v.getTag(R.id.tag);
            if (null != tag) {
                showTagDialog(tag);
                return true;
            }
        }

        return false;
    }

    private void onDownload() {
        GalleryInfo galleryInfo = getGalleryInfo();
        if (galleryInfo != null) {
            if (ServiceRegistry.INSTANCE.getDataModule().getDownloadManager().getDownloadState(galleryInfo.gid) == DownloadInfo.STATE_INVALID) {
                CommonOperations.startDownload(activity, galleryInfo, false);
            } else {
                new AlertDialog.Builder(mContext)
                        .setTitle(R.string.download_remove_dialog_title)
                        .setMessage(getString(R.string.download_remove_dialog_message, galleryInfo.title))
                        .setPositiveButton(android.R.string.ok, (dialog1, which1) -> ServiceRegistry.INSTANCE.getDataModule().getDownloadManager().deleteDownload(galleryInfo.gid))
                        .show();
            }
        }
    }

    public void startUpdateDownload(String updateUrl) {
        if (mGalleryDetail == null || mGalleryDetail.newVersions == null) {
            return;
        }
        adjustViewVisibility(STATE_REFRESH, false);
        request(updateUrl, GetGalleryDetailListener.RESULT_UPDATE);
    }

    public void startDownloadAsNew(String updateUrl) {
        if (mGalleryDetail == null || mGalleryDetail.newVersions == null) {
            return;
        }
        adjustViewVisibility(STATE_REFRESH, false);
        request(updateUrl, GetGalleryDetailListener.RESULT_DETAIL);
    }

    @Override
    public void onBackPressed() {
        finish();
    }

    private void updateDownloadText() {
        if (null == mDownload) {
            return;
        }
        switch (mDownloadState) {
            default:
            case DownloadInfo.STATE_INVALID:
                mDownload.setText(R.string.download);
                break;
            case DownloadInfo.STATE_NONE:
                mDownload.setText(R.string.download_state_none);
                break;
            case DownloadInfo.STATE_WAIT:
                mDownload.setText(R.string.download_state_wait);
                break;
            case DownloadInfo.STATE_DOWNLOAD:
                mDownload.setText(R.string.download_state_downloading);
                break;
            case DownloadInfo.STATE_FINISH:
                mDownload.setText(R.string.download_state_downloaded);
                break;
            case DownloadInfo.STATE_FAILED:
                mDownload.setText(R.string.download_state_failed);
                break;
//            case DownloadInfo.STATE_UPDATE:
//                mDownload.setText(R.string.update);
//                break;
//            case DownloadInfo.GOTO_NEW:
//                mDownload.setText(R.string.new_version);
//                break;
        }
    }

    private void updateDownloadState() {
        Context context = getEHContext();
        long gid = getGid();
        if (null == context || -1L == gid) {
            return;
        }

        int downloadState = ServiceRegistry.INSTANCE.getDataModule().getDownloadManager().getDownloadState(gid);
        if (downloadState == mDownloadState) {
            return;
        }
        mDownloadState = downloadState;
        updateDownloadText();
    }

    @Override
    public void onAdd(@NonNull DownloadInfo info, @NonNull List<DownloadInfo> list, int position) {
        updateDownloadState();
    }

    @Override
    public void onReplace(@NonNull DownloadInfo newInfo, @NonNull DownloadInfo oldInfo) {

    }

    @Override
    public void onUpdate(@NonNull DownloadInfo info, @NonNull List<DownloadInfo> list, LinkedList<DownloadInfo> mWaitList) {
        updateDownloadState();
    }

    @Override
    public void onUpdateAll() {
        updateDownloadState();
    }

    @Override
    public void onReload() {
        updateDownloadState();
    }

    @Override
    public void onChange() {
        updateDownloadState();
    }

    @Override
    public void onRemove(@NonNull DownloadInfo info, @NonNull List<DownloadInfo> list, int position) {
        updateDownloadState();
    }

    @Override
    public void onRenameLabel(String from, String to) {
    }

    @Override
    public void onUpdateLabels() {
    }

    protected void onGetGalleryDetailSuccess(GalleryDetail result) {
        try {
            onGetGalleryDetailSuccessInternal(result);
        } catch (Exception e) {
            android.util.Log.e("GalleryDetailScene", "onGetGalleryDetailSuccess crashed", e);
        }
    }

    private void onGetGalleryDetailSuccessInternal(GalleryDetail result) {
        mGalleryDetail = result;
        updateDownloadState();
        if (mDownloadState != DownloadInfo.STATE_INVALID) {
            if (mDownloadInfo != null && mDownloadInfo.thumb != null && !mDownloadInfo.thumb.equals(result.thumb) && mDownloadInfo.gid == result.gid) {
                useNetWorkLoadThumb = true;
                mDownloadInfo.updateInfo(result);
                mDownloadInfo.state = mDownloadState;
                IoThreadPoolExecutor.Companion.getInstance().execute(() ->
                    EhDB.putDownloadInfo(mDownloadInfo));
            }
        }
        adjustViewVisibility(STATE_NORMAL, true);
        bindViewSecond();
        if (myUpdateDialog != null && myUpdateDialog.autoDownload) {
            myUpdateDialog.autoDownload = false;
            mDownloadState = DownloadInfo.STATE_INVALID;
            onDownload();
        }
    }

    protected void onGetGalleryDetailFailure(Exception e) {
        e.printStackTrace();
        Context context = getEHContext();
        if (null != context && null != mTip) {
            String error = ExceptionUtils.getReadableString(e);
            mTip.setText(error);
            adjustViewVisibility(STATE_FAILED, true);
        }
    }

    protected void onGetGalleryDetailUpdateFailure(Exception e) {
        Analytics.recordException(e);
        adjustViewVisibility(STATE_NORMAL, true);
    }

}
