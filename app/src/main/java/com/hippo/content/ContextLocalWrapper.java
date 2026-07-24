/*
 * Copyright 2018 Hippo Seven
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

package com.hippo.content;

/*
 * Created by Hippo on 2018/3/27.
 */

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.LocaleList;
import java.util.Locale;

public class ContextLocalWrapper extends ContextWrapper {

  public ContextLocalWrapper(Context base) {
    super(base);
  }

  /**
   * Locale-adjusted context WITHOUT the wrapper shell, for use as the
   * Application base context. ActivityThread.handleReceiver casts
   * {@code app.getBaseContext()} straight to ContextImpl, so the Application
   * base must never be a ContextWrapper — every manifest-declared receiver
   * (e.g. an AppWidgetProvider) would otherwise crash the process with a
   * ClassCastException before onReceive. {@code createConfigurationContext}
   * returns a real ContextImpl carrying the locale override, which is all
   * the wrapper shell ever added on API &gt;= N (minSdk is 28). Activities
   * keep using {@link #wrap} — the framework does not cast their base.
   *
   * @param newLocale null = follow the system, base is returned untouched.
   */
  public static Context localeApplicationBase(Context base, Locale newLocale) {
    if (newLocale == null) {
      return base;
    }
    Configuration configuration = base.getResources().getConfiguration();
    configuration.setLocale(newLocale);
    LocaleList localeList = new LocaleList(newLocale);
    LocaleList.setDefault(localeList);
    configuration.setLocales(localeList);
    return base.createConfigurationContext(configuration);
  }

  public static ContextLocalWrapper wrap(Context context, Locale newLocale) {
    Resources res = context.getResources();
    Configuration configuration = res.getConfiguration();

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      configuration.setLocale(newLocale);

      LocaleList localeList = new LocaleList(newLocale);
      LocaleList.setDefault(localeList);
      configuration.setLocales(localeList);

      context = context.createConfigurationContext(configuration);

    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
      configuration.setLocale(newLocale);
      context = context.createConfigurationContext(configuration);

    } else {
      configuration.locale = newLocale;
      res.updateConfiguration(configuration, res.getDisplayMetrics());
    }

    return new ContextLocalWrapper(context);
  }
}
