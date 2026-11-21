package top.niunaijun.blackbox.utils;

import android.graphics.Bitmap;
import android.net.http.SslError;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ClientCertRequest;
import android.webkit.CookieManager;
import android.webkit.HttpAuthHandler;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

/**
 * Perbaikan login (net::ERR_CACHE_MISS) untuk WebView di dalam app virtual.
 *
 * Cara kerja:
 *  1. Poller ringan di main thread mencari semua WebView yang sedang tampil
 *     (termasuk WebView di dialog / popup milik SDK login seperti Moonton).
 *  2. Mode cache LOAD_CACHE_ONLY dipaksa kembali ke LOAD_DEFAULT. Mode itu sering
 *     dipasang SDK saat dia mengira jaringan mati, dan itulah yang memunculkan
 *     ERR_CACHE_MISS pada halaman login.
 *  3. WebViewClient milik SDK dibungkus (delegasi penuh). Pembungkus ini:
 *     - otomatis kirim ulang form (onFormResubmission) supaya reload/back
 *       pada halaman POST tidak jadi ERR_CACHE_MISS,
 *     - kalau halaman utama tetap gagal dengan ERR_CACHE_MISS, muat ulang
 *       satu-dua kali dengan LOAD_NO_CACHE dan error tidak diteruskan ke SDK
 *       (jadi toast "Moonton Account Error" tidak muncul).
 */
public final class WebViewFixer {
    private static final String TAG = "WebViewFixer";
    private static final long TICK_MS = 300L;
    private static final int MAX_RETRY = 2;

    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    private static final WeakHashMap<WebView, Integer> sRetry = new WeakHashMap<>();
    private static volatile boolean sStarted;

    private WebViewFixer() {
    }

    /** Aman dipanggil berkali-kali; poller hanya dibuat sekali per proses. */
    public static void start() {
        if (sStarted) {
            return;
        }
        sStarted = true;
        HANDLER.post(new Runnable() {
            @Override
            public void run() {
                try {
                    scanAllWindows();
                } catch (Throwable t) {
                    Log.w(TAG, "scan failed: " + t);
                }
                HANDLER.postDelayed(this, TICK_MS);
            }
        });
        Log.d(TAG, "started");
    }

    // ------------------------------------------------------------------ scan

    @SuppressWarnings("unchecked")
    private static void scanAllWindows() throws Throwable {
        Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
        Object inst = wmg.getMethod("getInstance").invoke(null);
        Field f = wmg.getDeclaredField("mViews");
        f.setAccessible(true);
        Object raw = f.get(inst);
        if (!(raw instanceof List)) {
            return;
        }
        List<View> roots = new ArrayList<>((List<View>) raw);
        for (View root : roots) {
            walk(root, 0);
        }
    }

    private static void walk(View v, int depth) {
        if (v == null || depth > 40) {
            return;
        }
        if (v instanceof WebView) {
            try {
                fix((WebView) v);
            } catch (Throwable t) {
                Log.w(TAG, "fix failed: " + t);
            }
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                walk(g.getChildAt(i), depth + 1);
            }
        }
    }

    // ------------------------------------------------------------------- fix

    private static void fix(final WebView w) {
        WebSettings s = w.getSettings();
        if (s == null) {
            return;
        }

        boolean cacheFixed = false;
        if (s.getCacheMode() == WebSettings.LOAD_CACHE_ONLY) {
            s.setCacheMode(WebSettings.LOAD_DEFAULT);
            cacheFixed = true;
            Log.d(TAG, "LOAD_CACHE_ONLY -> LOAD_DEFAULT");
        }

        s.setDomStorageEnabled(true);
        s.setBlockNetworkLoads(false);
        s.setBlockNetworkImage(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        try {
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                cm.setAcceptThirdPartyCookies(w, true);
            }
        } catch (Throwable ignored) {
        }

        try {
            w.setNetworkAvailable(true);
        } catch (Throwable ignored) {
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WebViewClient cur = w.getWebViewClient();
            if (!(cur instanceof FixClient)) {
                w.setWebViewClient(new FixClient(cur));
            }
        }

        if (cacheFixed) {
            // Halaman yang sudah terlanjur gagal dimuat ulang dengan mode baru.
            w.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        w.reload();
                    } catch (Throwable ignored) {
                    }
                }
            });
        }
    }

    private static boolean isCacheMiss(CharSequence desc) {
        return desc != null && desc.toString().contains("ERR_CACHE_MISS");
    }

    private static boolean retryNoCache(final WebView v, final String url) {
        if (url == null || url.length() == 0) {
            return false;
        }
        Integer n = sRetry.get(v);
        int count = n == null ? 0 : n;
        if (count >= MAX_RETRY) {
            return false;
        }
        sRetry.put(v, count + 1);
        Log.d(TAG, "ERR_CACHE_MISS, retry #" + (count + 1) + " no-cache: " + url);
        v.post(new Runnable() {
            @Override
            public void run() {
                try {
                    v.getSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);
                    v.loadUrl(url);
                } catch (Throwable ignored) {
                }
            }
        });
        return true;
    }

    // ---------------------------------------------------------------- client

    /** WebViewClient pembungkus: semua method diteruskan ke client asli SDK. */
    private static final class FixClient extends WebViewClient {
        private final WebViewClient d;

        FixClient(WebViewClient delegate) {
            this.d = delegate != null ? delegate : new WebViewClient();
        }

        @Override
        public void onFormResubmission(WebView view, Message dontResend, Message resend) {
            // Kirim ulang form otomatis: inilah penyebab klasik ERR_CACHE_MISS saat reload/back.
            if (resend != null) {
                resend.sendToTarget();
            } else {
                d.onFormResubmission(view, dontResend, resend);
            }
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && request != null && request.isForMainFrame() && error != null
                    && isCacheMiss(error.getDescription())
                    && retryNoCache(view, request.getUrl().toString())) {
                return; // jangan teruskan ke SDK, sedang dicoba ulang
            }
            d.onReceivedError(view, request, error);
        }

        @Override
        public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
            if (isCacheMiss(description) && retryNoCache(view, failingUrl)) {
                return;
            }
            d.onReceivedError(view, errorCode, description, failingUrl);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            synchronized (sRetry) {
                sRetry.remove(view);
            }
            d.onPageFinished(view, url);
        }

        // ---- delegasi murni ----

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return d.shouldOverrideUrlLoading(view, url);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return d.shouldOverrideUrlLoading(view, request);
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            d.onPageStarted(view, url, favicon);
        }

        @Override
        public void onLoadResource(WebView view, String url) {
            d.onLoadResource(view, url);
        }

        @Override
        public void onPageCommitVisible(WebView view, String url) {
            d.onPageCommitVisible(view, url);
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
            return d.shouldInterceptRequest(view, url);
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            return d.shouldInterceptRequest(view, request);
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
            d.onReceivedHttpError(view, request, errorResponse);
        }

        @Override
        public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
            d.doUpdateVisitedHistory(view, url, isReload);
        }

        @Override
        public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler handler, String host, String realm) {
            d.onReceivedHttpAuthRequest(view, handler, host, realm);
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            d.onReceivedSslError(view, handler, error);
        }

        @Override
        public void onReceivedClientCertRequest(WebView view, ClientCertRequest request) {
            d.onReceivedClientCertRequest(view, request);
        }

        @Override
        public void onScaleChanged(WebView view, float oldScale, float newScale) {
            d.onScaleChanged(view, oldScale, newScale);
        }

        @Override
        public void onReceivedLoginRequest(WebView view, String realm, String account, String args) {
            d.onReceivedLoginRequest(view, realm, account, args);
        }

        @Override
        public boolean shouldOverrideKeyEvent(WebView view, KeyEvent event) {
            return d.shouldOverrideKeyEvent(view, event);
        }

        @Override
        public void onUnhandledKeyEvent(WebView view, KeyEvent event) {
            d.onUnhandledKeyEvent(view, event);
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            return d.onRenderProcessGone(view, detail);
        }
    }
}
