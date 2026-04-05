package com.hippo.ehviewer.ui.scene.download

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import com.hippo.ehviewer.R

class DownloadLabelAdapter(
    context: Context,
    resource: Int,
    objects: List<DownloadLabelItem>
) : ArrayAdapter<DownloadLabelItem>(context, resource, objects) {

    @SuppressLint("ViewHolder")
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view: View
        val viewHolder: ViewHolder
        val item = getItem(position)
        if (convertView == null) {
            view = LayoutInflater.from(context)
                .inflate(R.layout.item_download_label_list, parent, false)
            viewHolder = ViewHolder()
            viewHolder.textView1 = view.findViewById(R.id.text1)
            viewHolder.textView2 = view.findViewById(R.id.text2)
            view.tag = viewHolder
        } else {
            view = convertView
            viewHolder = view.tag as ViewHolder
        }

        viewHolder.textView1?.setText(item?.label)
        viewHolder.textView2?.setText(item?.count())

        return view
    }

    private class ViewHolder {
        var textView1: TextView? = null
        var textView2: TextView? = null
    }
}
