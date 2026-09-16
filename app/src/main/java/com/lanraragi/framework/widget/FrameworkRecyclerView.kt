package com.lanraragi.framework.widget

import android.content.Context
import android.util.AttributeSet
import com.hippo.easyrecyclerview.EasyRecyclerView

/**
 * In-tree name for the seven332 [EasyRecyclerView] so layouts never
 * reference the library class by its `com.hippo.*` FQN. A class named in
 * layout XML is kept verbatim by R8; through this subclass the library
 * parent is unkept and obfuscated like the rest of the seven332 code, which
 * keeps the upstream package name out of the shipped APK (spec
 * 2026-09-16 §3.4). Code may keep using the library type directly.
 */
class FrameworkRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : EasyRecyclerView(context, attrs, defStyle)
