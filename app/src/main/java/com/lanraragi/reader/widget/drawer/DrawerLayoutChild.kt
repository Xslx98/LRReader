/*
 * Copyright 2015 Hippo Seven
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

package com.lanraragi.reader.widget.drawer

/**
 * A direct child of [DrawerLayout] that takes part in its window insets:
 * the layout hands every child the status and navigation bar heights and
 * lays it out with the margins it asks for. (Ported from seven332's
 * DrawerLayout library, commit ea2bb388f0.)
 */
interface DrawerLayoutChild {

    /** [top] is the status bar height, [bottom] the navigation bar height. */
    fun onGetWindowPadding(top: Int, bottom: Int)

    fun getAdditionalTopMargin(): Int

    fun getAdditionalBottomMargin(): Int
}
