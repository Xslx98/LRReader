package com.lanraragi.reader.util

import android.content.Context
import android.content.ContextWrapper
import android.os.LocaleList
import java.util.Locale

/** Contexts carrying the in-app language (Settings → Appearance → Language). */
object LocaleContext {

    /**
     * Locale-adjusted context WITHOUT a wrapper shell, for use as the
     * Application base context. ActivityThread.handleReceiver casts
     * `app.getBaseContext()` straight to ContextImpl, so the Application
     * base must never be a ContextWrapper — every manifest-declared receiver
     * (e.g. an AppWidgetProvider) would otherwise crash the process with a
     * ClassCastException before onReceive. `createConfigurationContext`
     * returns a real ContextImpl carrying the locale override. Activities
     * keep using [wrap] — the framework does not cast their base.
     *
     * @param locale null = follow the system, [base] is returned untouched.
     */
    fun applicationBase(base: Context, locale: Locale?): Context {
        if (locale == null) return base
        return withLocale(base, locale)
    }

    /** Activity base context: [context] with [locale], in a ContextWrapper. */
    fun wrap(context: Context, locale: Locale): ContextWrapper =
        ContextWrapper(withLocale(context, locale))

    // Sets the locale on the context's own Configuration object and the
    // process-wide default LocaleList, as the framework helper this replaced did.
    private fun withLocale(context: Context, locale: Locale): Context {
        val configuration = context.resources.configuration
        val localeList = LocaleList(locale)
        configuration.setLocale(locale)
        LocaleList.setDefault(localeList)
        configuration.setLocales(localeList)
        return context.createConfigurationContext(configuration)
    }
}
