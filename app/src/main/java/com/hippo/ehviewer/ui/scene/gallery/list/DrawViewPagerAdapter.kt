package com.hippo.ehviewer.ui.scene.gallery.list

import android.view.View
import android.view.ViewGroup
import androidx.viewpager.widget.PagerAdapter

class DrawViewPagerAdapter(
    private val listView: List<View>
) : PagerAdapter() {

    override fun instantiateItem(container: ViewGroup, position: Int): Any {
        container.addView(listView[position], 0)
        return listView[position]
    }

    override fun getCount(): Int = listView.size

    override fun isViewFromObject(view: View, obj: Any): Boolean = view === obj

    override fun destroyItem(container: ViewGroup, position: Int, obj: Any) {
        container.removeView(listView[position])
    }
}
