package com.hippo.widget

/**
 * Same-key short-circuit decision for [LoadImageView] / [LoadImageViewNew].
 *
 * Conaco.load cancels the unikery's in-flight task before starting a new one,
 * and a cancelled network fetch throws away its partially downloaded bytes.
 * Rebinds therefore must not restart an identical request that is already in
 * flight. Kept as a pure function: the widgets cannot be exercised under
 * Robolectric (Conaco wiring), the policy can.
 */
object LoadImageSameKeyPolicy {

    /**
     * @param currentKey the widget's current key (null when nothing loaded)
     * @param newKey the requested key (caller guarantees non-null)
     * @param failed true when the last load failed (a retry must proceed)
     * @param useNetworkMatches false when the new request allows network but
     *   the in-flight one does not (or vice versa) — must proceed
     * @param inFlight true when Conaco still has an active task for this widget
     */
    @JvmStatic
    fun shouldSkipLoad(
        currentKey: String?,
        newKey: String,
        failed: Boolean,
        useNetworkMatches: Boolean,
        inFlight: Boolean,
    ): Boolean {
        return !failed && inFlight && useNetworkMatches && newKey == currentKey
    }
}
