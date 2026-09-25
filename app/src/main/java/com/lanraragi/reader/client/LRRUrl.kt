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

package com.lanraragi.reader.client

/**
 * appurl请求设置
 */
object LRRUrl {

    const val SITE_EX = 1

    @JvmStatic
    fun getTagDefinitionUrl(tag: String?): String {
        return "https://ehwiki.org/wiki/" + (tag?.replace(' ', '_') ?: "")
    }
}
