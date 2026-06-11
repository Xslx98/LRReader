/*
 * Copyright 2026 The LRReader Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.hippo.ehviewer.dao

import kotlinx.serialization.json.Json

/**
 * Process-wide JSON encoder/decoder for the `ARCHIVE_JSON` payload
 * column on [ArchiveLocalState].
 *
 * - `encodeDefaults = false`: don't pollute the on-disk JSON with
 *   default values; the [com.lanraragi.reader.domain.Archive] schema
 *   uses defaults for `summary` etc., and we want them omitted unless
 *   set.
 * - `ignoreUnknownKeys = true`: forward-compat. Older builds reading
 *   rows that newer builds wrote will silently drop unknown keys
 *   instead of throwing.
 * - `coerceInputValues = true`: a null/invalid value for a property that
 *   has a default is coerced to that default instead of throwing, so a
 *   slightly-off row degrades gracefully rather than failing to decode.
 */
internal val ArchiveLocalStateJson: Json = Json {
    encodeDefaults = false
    ignoreUnknownKeys = true
    coerceInputValues = true
}
