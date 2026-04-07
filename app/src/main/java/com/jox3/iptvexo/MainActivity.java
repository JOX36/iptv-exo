package com.jox3.iptvexo;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private static final int REQ_PLAYER   = 1001;
    private static final int REQ_M3U_FILE = 1002;
    private OkHttpClient httpClient;

    @SuppressLint({"SetJavaScriptEnabled","TrustAllX509TrustManager"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        httpClient = buildUnsafeClient();

        webView = findViewById(R.id.webview);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setAllowUniversalAccessFromFileURLs(true);
        ws.setAllowFileAccessFromFileURLs(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.proceed();
            }
        });

        webView.addJavascriptInterface(new Bridge(), "AndroidPlayer");
        webView.loadUrl("file:///android_asset/player.html");
    }

    @SuppressLint("TrustAllX509TrustManager")
    private OkHttpClient buildUnsafeClient() {
        try {
            X509TrustManager tm = new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            };
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, new TrustManager[]{tm}, new SecureRandom());
            return new OkHttpClient.Builder()
                .sslSocketFactory(sc.getSocketFactory(), tm)
                .hostnameVerifier((h, s) -> true)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
        } catch (Exception e) {
            return new OkHttpClient.Builder().build();
        }
    }

    class Bridge {

        // ── Petición API desde Java — solución definitiva Android 15 ──
        @JavascriptInterface
        public void fetchUrl(final String url, final String callbackId) {
            new Thread(() -> {
                String result = null;
                String error  = null;
                try {
                    // Intentar con la URL original
                    result = doFetch(url);
                } catch (Exception e1) {
                    // Si falla, intentar con el protocolo alternativo HTTP/HTTPS
                    try {
                        String altUrl;
                        if (url.startsWith("https://")) {
                            altUrl = "http://" + url.substring(8);
                        } else if (url.startsWith("http://")) {
                            altUrl = "https://" + url.substring(7);
                        } else {
                            throw e1;
                        }
                        result = doFetch(altUrl);
                    } catch (Exception e2) {
                        error = e1.getMessage() != null ? e1.getMessage() : "Error de red";
                    }
                }

                final String finalResult = result;
                final String finalError  = error;

                webView.post(() -> {
                    String js;
                    if (finalResult != null) {
                        String escaped = finalResult
                            .replace("\\", "\\\\")
                            .replace("'", "\\'")
                            .replace("\n", "\\n")
                            .replace("\r", "");
                        js = "if(window._jcb&&window._jcb['" + callbackId + "']){" +
                             "window._jcb['" + callbackId + "'](null,'" + escaped + "');" +
                             "delete window._jcb['" + callbackId + "'];}";
                    } else {
                        js = "if(window._jcb&&window._jcb['" + callbackId + "']){" +
                             "window._jcb['" + callbackId + "']('" + finalError + "',null);" +
                             "delete window._jcb['" + callbackId + "'];}";
                    }
                    webView.evaluateJavascript(js, null);
                });
            }).start();
        }

        private String doFetch(String url) throws Exception {
            Request req = new Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")
                .header("Accept", "application/json, */*")
                .build();
            Response resp = httpClient.newCall(req).execute();
            String body = resp.body() != null ? resp.body().string() : "";
            resp.close();
            if (body.isEmpty()) throw new Exception("Empty response");
            return body;
        }

        @JavascriptInterface
        public void openPlayer(String url, String name, String group, String type,
                               String logo, String itemId, String channelsJson, int channelIndex) {
            Intent i = new Intent(MainActivity.this, PlayerActivity.class);
            i.putExtra("url", url);
            i.putExtra("name", name);
            i.putExtra("group", group);
            i.putExtra("type", type);
            i.putExtra("logo", logo);
            i.putExtra("id", itemId);
            i.putExtra("channel_index", channelIndex);
            i.putExtra("channels_json", channelsJson != null ? channelsJson : "[]");
            startActivityForResult(i, REQ_PLAYER);
        }

        @JavascriptInterface
        public void openFilePicker() {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "text/plain", "audio/x-mpegurl",
                "application/octet-stream", "application/vnd.apple.mpegurl"
            });
            startActivityForResult(Intent.createChooser(i, "Seleccionar lista M3U"), REQ_M3U_FILE);
        }

        @JavascriptInterface public void playUrl(String url) {}
        @JavascriptInterface public void stop() {}
        @JavascriptInterface public void goFullscreen() {}
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);

        if (req == REQ_PLAYER && data != null) {
            boolean added   = data.getBooleanExtra("fav_added", false);
            boolean removed = data.getBooleanExtra("fav_removed", false);
            String id   = data.getStringExtra("item_id");
            String type = data.getStringExtra("item_type");
            if (id != null) {
                String key = type + "_" + id;
                if (added)   webView.evaluateJavascript("S.favs.add('"    + key + "');saveFavs();", null);
                if (removed) webView.evaluateJavascript("S.favs.delete('" + key + "');saveFavs();", null);
            }
        }

        if (req == REQ_M3U_FILE && res == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri == null) return;
            try {
                InputStream is = getContentResolver().openInputStream(uri);
                if (is == null) return;
                BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                    if (sb.length() > 5 * 1024 * 1024) break;
                }
                reader.close();
                String content = sb.toString()
                    .replace("\\", "\\\\")
                    .replace("`", "\\`")
                    .replace("$", "\\$");
                String fileName = uri.getLastPathSegment();
                if (fileName == null) fileName = "Lista local";
                final String jsContent = content;
                final String jsName = fileName.replace("'", "\\'");
                webView.post(() ->
                    webView.evaluateJavascript(
                        "loadLocalM3U(`" + jsContent + "`, '" + jsName + "')", null
                    )
                );
            } catch (Exception e) {
                webView.post(() ->
                    webView.evaluateJavascript("toast('❌ Error al leer el archivo')", null)
                );
            }
        }
    }

    private long backPressedTime = 0;

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            if (System.currentTimeMillis() - backPressedTime < 2000) {
                super.onBackPressed();
            } else {
                backPressedTime = System.currentTimeMillis();
                android.widget.Toast.makeText(this,
                    "Presiona atrás de nuevo para salir",
                    android.widget.Toast.LENGTH_SHORT).show();
            }
        }
    }
}
