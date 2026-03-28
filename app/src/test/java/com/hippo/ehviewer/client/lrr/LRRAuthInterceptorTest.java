package com.hippo.ehviewer.client.lrr;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Base64;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link LRRAuthInterceptor} — verifies Bearer token injection
 * only for requests targeting the configured LANraragi server.
 *
 * Uses Robolectric for Context, but bypasses EncryptedSharedPreferences
 * by injecting a plain SharedPreferences into LRRAuthManager via reflection.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = android.app.Application.class)
public class LRRAuthInterceptorTest {

    private MockWebServer server;
    private OkHttpClient client;
    private String baseUrl;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        baseUrl = server.url("").toString();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        client = new OkHttpClient.Builder()
                .addInterceptor(new LRRAuthInterceptor())
                .build();

        // Inject plain SharedPreferences into LRRAuthManager via reflection
        // (avoids EncryptedSharedPreferences which requires Android KeyStore)
        Context ctx = ApplicationProvider.getApplicationContext();
        SharedPreferences prefs = ctx.getSharedPreferences("test_lrr_auth", Context.MODE_PRIVATE);
        prefs.edit().clear().apply();
        Field sPrefsField = LRRAuthManager.class.getDeclaredField("sPrefs");
        sPrefsField.setAccessible(true);
        sPrefsField.set(null, prefs);
    }

    @After
    public void tearDown() throws IOException {
        server.shutdown();
        // Clear the test SharedPreferences directly
        try {
            Field sPrefsField = LRRAuthManager.class.getDeclaredField("sPrefs");
            sPrefsField.setAccessible(true);
            SharedPreferences prefs = (SharedPreferences) sPrefsField.get(null);
            if (prefs != null) prefs.edit().clear().apply();
        } catch (Exception ignored) {}
    }

    @Test
    public void noApiKey_noHeader() throws Exception {
        // API key is null by default after clearing prefs
        LRRAuthManager.setServerUrl(baseUrl);

        server.enqueue(new MockResponse().setBody("ok"));
        Request request = new Request.Builder().url(server.url("/api/info")).build();
        client.newCall(request).execute().close();

        RecordedRequest recorded = server.takeRequest();
        assertNull(recorded.getHeader("Authorization"));
    }

    @Test
    public void emptyApiKey_noHeader() throws Exception {
        LRRAuthManager.setApiKey("");
        LRRAuthManager.setServerUrl(baseUrl);

        server.enqueue(new MockResponse().setBody("ok"));
        Request request = new Request.Builder().url(server.url("/api/info")).build();
        client.newCall(request).execute().close();

        RecordedRequest recorded = server.takeRequest();
        assertNull(recorded.getHeader("Authorization"));
    }

    @Test
    public void withApiKey_addsBearer() throws Exception {
        LRRAuthManager.setApiKey("test-key");
        LRRAuthManager.setServerUrl(baseUrl);

        server.enqueue(new MockResponse().setBody("ok"));
        Request request = new Request.Builder().url(server.url("/api/info")).build();
        client.newCall(request).execute().close();

        RecordedRequest recorded = server.takeRequest();
        String authHeader = recorded.getHeader("Authorization");
        assertNotNull("Authorization header should be present", authHeader);
        assertTrue(authHeader.startsWith("Bearer "));
        String token = authHeader.substring("Bearer ".length());
        byte[] decoded = Base64.getDecoder().decode(token);
        assertEquals("test-key", new String(decoded, "UTF-8"));
    }

    @Test
    public void requestToOtherHost_noHeader() throws Exception {
        LRRAuthManager.setApiKey("secret");
        LRRAuthManager.setServerUrl("https://different-host.example.com");

        server.enqueue(new MockResponse().setBody("ok"));
        Request request = new Request.Builder().url(server.url("/api/info")).build();
        client.newCall(request).execute().close();

        RecordedRequest recorded = server.takeRequest();
        assertNull(recorded.getHeader("Authorization"));
    }

    @Test
    public void noServerUrl_noHeader() throws Exception {
        LRRAuthManager.setApiKey("secret");
        // Server URL not set — getServerUrl() returns null

        server.enqueue(new MockResponse().setBody("ok"));
        Request request = new Request.Builder().url(server.url("/api/info")).build();
        client.newCall(request).execute().close();

        RecordedRequest recorded = server.takeRequest();
        assertNull(recorded.getHeader("Authorization"));
    }
}
