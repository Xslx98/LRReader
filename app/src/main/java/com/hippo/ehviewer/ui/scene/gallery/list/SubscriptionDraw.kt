package com.hippo.ehviewer.ui.scene.gallery.list

import android.annotation.SuppressLint
import android.content.Context
import android.content.DialogInterface
import android.view.LayoutInflater
import android.view.View
import android.widget.AbsListView
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.viewpager.widget.ViewPager
import com.hippo.app.EditTextDialogBuilder
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.callBack.SubscriptionCallback
import com.hippo.ehviewer.client.EhClient
import com.hippo.ehviewer.client.EhRequest
import com.hippo.ehviewer.client.EhTagDatabase
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.data.userTag.TagPushParam
import com.hippo.ehviewer.client.data.userTag.UserTag
import com.hippo.ehviewer.client.data.userTag.UserTagList
import com.hippo.ehviewer.client.exception.EhException
import com.hippo.ehviewer.ui.MainActivity
import com.hippo.ehviewer.ui.scene.EhCallback
import com.hippo.lib.yorozuya.AssertUtils
import com.hippo.lib.yorozuya.ViewUtils
import com.hippo.scene.Announcer
import com.hippo.scene.SceneFragment
import com.hippo.widget.ProgressView

class SubscriptionDraw(
    private val context: Context,
    private val inflater: LayoutInflater,
    private val ehClient: EhClient,
    private val mTag: String,
    ehTags: EhTagDatabase?
) {

    private val ehTags: EhTagDatabase? = ehTags ?: EhTagDatabase.getInstance(context)
    private val ehApplication: EhApplication = context.applicationContext as EhApplication

    private lateinit var listView: ListView
    private lateinit var progressView: ProgressView
    private lateinit var frameLayout: FrameLayout
    private lateinit var textView: TextView
    protected var activity: MainActivity? = null
    private var callback: SubscriptionCallback? = null

    @JvmField
    var needLoad = true

    private var userTagList: UserTagList? = null

    private var tagName: String? = null

    @SuppressLint("NonConstantResourceId")
    fun onCreate(drawPager: ViewPager, activity: MainActivity, callback: SubscriptionCallback): View {
        this.activity = activity
        this.callback = callback
        @SuppressLint("InflateParams")
        val subscriptionView = inflater.inflate(R.layout.subscription_draw, null, false)

        progressView = ViewUtils.`$$`(subscriptionView, R.id.tag_list_view_progress) as ProgressView
        frameLayout = ViewUtils.`$$`(subscriptionView, R.id.tag_list_parent) as FrameLayout
        textView = ViewUtils.`$$`(subscriptionView, R.id.not_login_text) as TextView
        frameLayout.visibility = View.GONE

        val toolbar = ViewUtils.`$$`(subscriptionView, R.id.toolbar) as Toolbar
        val tip = ViewUtils.`$$`(subscriptionView, R.id.tip) as TextView
        listView = ViewUtils.`$$`(subscriptionView, R.id.list_view) as ListView
        AssertUtils.assertNotNull(context)

        tip.setText(R.string.subscription_tip)
        toolbar.setLogo(R.drawable.ic_baseline_subscriptions_24)
        toolbar.setTitle(R.string.subscription)
        toolbar.inflateMenu(R.menu.drawer_gallery_list)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_add -> addNewTag()
                R.id.action_settings -> seeDetailPage()
            }
            true
        }

        toolbar.setOnClickListener { drawPager.currentItem = 0 }

        if (needLoad) {
            try {
                loadData()
            } catch (e: EhException) {
                e.printStackTrace()
            }
        }

        return subscriptionView
    }

    fun setUserTagList(tagList: UserTagList) {
        if (userTagList == null) {
            userTagList = tagList
        } else {
            userTagList!!.userTags = tagList.userTags
        }
    }

    private fun seeDetailPage() {
        val tagList = userTagList
        if (tagList == null) {
            Toast.makeText(context, R.string.empty_subscription, Toast.LENGTH_SHORT).show()
            return
        }
        tagList.stageId = activity!!.stageId
        ServiceRegistry.dataModule.saveUserTagList(tagList)
        activity!!.startScene(Announcer(SubscriptionsScene::class.java))
    }

    private fun bindViewSecond() {
        progressView.visibility = View.GONE
        frameLayout.visibility = View.VISIBLE
        if (userTagList!!.userTags.isEmpty()) {
            return
        }

        val adapter = SubscriptionItemAdapter(context, userTagList, ehTags)

        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            val tag = userTagList!!.userTags[position]
            callback!!.onSubscriptionItemClick(tag.tagName)
        }
        listView.setOnScrollListener(ScrollListener())
        if (userTagList!!.size() > 0) {
            resume()
        }
    }

    private fun addNewTag() {
        tagName = callback!!.getAddTagName(userTagList)
        if (tagName == null) {
            Toast.makeText(context, R.string.can_not_use_this_tag, Toast.LENGTH_SHORT).show()
            return
        }

        val builder = EditTextDialogBuilder(
            context,
            tagName, context.getString(R.string.tag_title)
        )
        builder.setTitle(R.string.add_tag_dialog_title)
        builder.setPositiveButton(R.string.subscription_watched) { dialog, _ ->
            onDialogPositiveButtonClick(dialog)
        }
        builder.setNegativeButton(R.string.subscription_hidden) { dialog, _ ->
            onDialogNegativeButtonClick(dialog)
        }
        builder.show()
    }

    private fun onDialogNegativeButtonClick(dialog: DialogInterface) {
        dialog.dismiss()
        requestTag(tagName!!, false)
    }

    private fun onDialogPositiveButtonClick(dialog: DialogInterface) {
        dialog.dismiss()
        requestTag(tagName!!, true)
    }

    @Throws(EhException::class)
    private fun loadData() {
        val requested = request()
        if (!requested) {
            throw EhException("请求数据失败请更换IP地址或检查网络设置是否正确~")
        }
    }

    private fun requestTag(tagName: String, tagState: Boolean) {
        val url = EhUrl.getMyTag()

        if (activity == null) {
            return
        }

        progressView.visibility = View.VISIBLE
        frameLayout.visibility = View.GONE

        val callback: EhClient.Callback<UserTagList> =
            SubscriptionDetailListener(context, activity!!.stageId, mTag)

        val param = TagPushParam()
        param.tagNameNew = tagName
        if (tagState) {
            param.tagWatchNew = "on"
        } else {
            param.tagHiddenNew = "on"
        }

        val mRequest = EhRequest()
            .setMethod(EhClient.METHOD_ADD_TAG)
            .setArgs(url, param).setCallback(callback)

        ehClient.execute(mRequest)
    }

    private fun request(): Boolean {
        val url = EhUrl.getMyTag()

        if (activity == null) {
            return false
        }

        val callback: EhClient.Callback<UserTagList> =
            SubscriptionDetailListener(context, activity!!.stageId, mTag)

        val mRequest = EhRequest()
            .setMethod(EhClient.METHOD_GET_WATCHED)
            .setArgs(url).setCallback(callback)

        ehClient.execute(mRequest)

        return true
    }

    fun resume() {
        val scrollY = ehApplication.getTempCache(SUBSCRIPTION_DRAW_SCROLL_Y)
        val pos = ehApplication.getTempCache(SUBSCRIPTION_DRAW_POS)
        if (scrollY != null && pos != null) {
            listView.setSelection(pos as Int)
        }
    }

    private inner class SubscriptionDetailListener(
        context: Context,
        stageId: Int,
        sceneTag: String
    ) : EhCallback<GalleryListScene, UserTagList>(context, stageId, sceneTag) {

        override fun isInstance(scene: SceneFragment?): Boolean = false

        override fun onSuccess(result: UserTagList) {
            userTagList = result
            ServiceRegistry.dataModule.saveUserTagList(result)
            bindViewSecond()
            needLoad = false
        }

        override fun onFailure(e: Exception) {}

        override fun onCancel() {}
    }

    private inner class ScrollListener : AbsListView.OnScrollListener {
        override fun onScrollStateChanged(view: AbsListView, scrollState: Int) {
            val item = view.getChildAt(0) ?: return
            val firstPos = view.firstVisiblePosition
            val top = item.top
            val scrollY = firstPos * item.height - top
            ehApplication.putTempCache(SUBSCRIPTION_DRAW_SCROLL_Y, scrollY)
            ehApplication.putTempCache(SUBSCRIPTION_DRAW_POS, firstPos)
        }

        override fun onScroll(view: AbsListView, firstVisibleItem: Int, visibleItemCount: Int, totalItemCount: Int) {}
    }

    companion object {
        private const val SUBSCRIPTION_DRAW_SCROLL_Y = "SubscriptionDrawScrollY"
        private const val SUBSCRIPTION_DRAW_POS = "SubscriptionDrawPos"
    }
}
