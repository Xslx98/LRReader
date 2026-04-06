package com.hippo.ehviewer.ui.scene.gallery.list

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.EhTagDatabase
import com.hippo.ehviewer.client.data.userTag.UserTag
import com.hippo.ehviewer.client.data.userTag.UserTagList

class SubscriptionItemAdapter(
    context: Context,
    private val userTagList: UserTagList,
    ehTags: EhTagDatabase?
) : BaseAdapter() {

    private val ehTags: EhTagDatabase = ehTags ?: EhTagDatabase.getInstance(context)!!
    private val inflater: LayoutInflater = LayoutInflater.from(context)

    override fun getCount(): Int = userTagList.userTags.size

    override fun getItem(position: Int): UserTag = userTagList.userTags[position]

    override fun getItemId(position: Int): Long =
        java.lang.Long.decode(getItem(position).userTagId!!.substring(8))

    @SuppressLint("ViewHolder", "InflateParams")
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val userTag = getItem(position)

        val view = inflater.inflate(R.layout.subscription_list_item, null)
        val imageView: ImageView = view.findViewById(R.id.subscription_state)
        if (userTag.hidden) {
            imageView.setImageResource(R.drawable.ic_baseline_visibility_off_24)
        }
        if (userTag.watched) {
            imageView.setImageResource(R.drawable.ic_baseline_visibility_24)
        }

        val textView: TextView = view.findViewById(R.id.label)
        textView.text = userTag.getName(ehTags)

        return view
    }
}
