package com.jox3.iptvexo;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
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
    
    // User-Agent personalizado que simula un reproductor IPTV moderno
    private static final String CUSTOM_USER_AGENT = 
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36 " +
        "ExoPlayer/2.18.1 (Linux;Android 10) ExoPlayerLib/2.18.1";

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
        ws.setCacheMode(WebSettings.LOAD_NO_CACHE);
        
        // ========== MEJORA CRÍTICA: User-Agent personalizado ==========
        ws.setUserAgentString(CUSTOM_USER_AGENT);
        
        webView.clearCache(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.proceed();
            }
            
            // ========== MEJORA: Logging de errores HTTP ==========
            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, 
                                           WebResourceResponse errorResponse) {
                Log.e("IPTV_EXO", "Error HTTP " + errorResponse.getStatusCode() + 
                      " al cargar: " + request.getUrl());
                super.onReceivedHttpError(view, request, errorResponse);
            }
            
            // ========== MEJORA: Logging de errores de carga ==========
            @Override
            public void onReceivedError(WebView view, int errorCode, 
                                       String description, String failingUrl) {
                Log.e("IPTV_EXO", "Error de carga (" + errorCode + "): " + 
                      description + " - URL: " + failingUrl);
                super.onReceivedError(view, errorCode, description, failingUrl);
            }
        });

        webView.addJavascriptInterface(new Bridge(), "AndroidPlayer");

        // Detectar TV Box y cargar versión optimizada
        boolean isTv = getPackageManager().hasSystemFeature(
            android.content.pm.PackageManager.FEATURE_LEANBACK);
        boolean isTvUiMode = (getResources().getConfiguration().uiMode &
            android.content.res.Configuration.UI_MODE_TYPE_MASK) ==
            android.content.res.Configuration.UI_MODE_TYPE_TELEVISION;

        if(isTv || isTvUiMode){
            // TV Box — forzar landscape fullscreen
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
            getWindow().getDecorView().setSystemUiVisibility(
                android.view.View.SYSTEM_UI_FLAG_FULLSCREEN |
                android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            );
            webView.loadUrl("file:///android_asset/player_tv.html");
        } else {
            webView.loadUrl("file:///android_asset/player.html");
        }
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

    // Almacén temporal de resultados grandes — evita OOM al pasar JSON en evaluateJavascript
    private final java.util.concurrent.ConcurrentHashMap<String, String> resultStore =
        new java.util.concurrent.ConcurrentHashMap<>();

    class Bridge {

        @JavascriptInterface
        public String getResult(String callbackId) {
            return resultStore.remove(callbackId); // devuelve y elimina
        }

        // Devolver progreso guardado en SharedPreferences al WebView
        @JavascriptInterface
        public String getAllVodProgress() {
            android.content.SharedPreferences prefs =
                getSharedPreferences("vod_progress", MODE_PRIVATE);
            java.util.Map<String, ?> all = prefs.getAll();
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (java.util.Map.Entry<String, ?> entry : all.entrySet()) {
                String key = entry.getKey();
                if (!key.startsWith("pos_")) continue;
                String id = key.substring(4);
                long pos = prefs.getLong("pos_" + id, 0);
                long dur = prefs.getLong("dur_" + id, 0);
                if (pos > 0 && dur > 0) {
                    if (!first) sb.append(",");
                    sb.append("\"").append(id).append("\":{\"pos\":").append(pos)
                      .append(",\"dur\":").append(dur).append("}");
                    first = false;
                }
            }
            sb.append("}");
            return sb.toString();
        }

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
                        // Notificar al WebView el protocolo correcto
                        updateHostProtocol(altUrl);
                    } catch (Exception e2) {
                        error = e1.getMessage() != null ? e1.getMessage() : "Error de red";
                    }
                }

                final String finalResult = result;
                final String finalError  = error;

                // Guardar resultado en objeto accesible desde JS — evita OOM de evaluateJavascript
                webView.post(() -> {
                    String js;
                    if (finalResult != null) {
                        // Guardar en objeto puente en lugar de pasar como string literal
                        resultStore.put(callbackId, finalResult);
                        js = "if(window._jcb&&window._jcb['" + callbackId + "']){" +
                             "var _d=AndroidPlayer.getResult('" + callbackId + "');" +
                             "window._jcb['" + callbackId + "'](null,_d);" +
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
                .header("User-Agent", CUSTOM_USER_AGENT)
                .header("Accept", "application/json, */*")
                .build();
            Response resp = httpClient.newCall(req).execute();
            String body = resp.body() != null ? resp.body().string() : "";
            resp.close();
            if (body.isEmpty()) throw new Exception("Empty response");
            return body;
        }

        // Detectar protocolo correcto del host y notificar al WebView
        private void updateHostProtocol(final String workingUrl) {
            try {
                java.net.URL u = new java.net.URL(workingUrl);
                final String correctHost = u.getProtocol() + "://" + u.getHost() +
                    (u.getPort() > 0 ? ":" + u.getPort() : "");
                webView.post(() ->
                    webView.evaluateJavascript(
                        "if(S&&S.host&&S.host!=='" + correctHost + "'){S.host='" + correctHost + "';}", null
                    )
                );
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public void openPlayer(String url, String name, String group, String type,
                               String logo, String itemId, String channelsJson, int channelIndex,
                               boolean isSeries) {
            Intent i = new Intent(MainActivity.this, PlayerActivity.class);
            i.putExtra("url", url);
            i.putExtra("name", name);
            i.putExtra("group", group);
            i.putExtra("type", type);
            i.putExtra("logo", logo);
            i.putExtra("id", itemId);
            i.putExtra("channel_index", channelIndex);
            i.putExtra("channels_json", channelsJson != null ? channelsJson : "[]");
            i.putExtra("is_series", isSeries);
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
            long position = data.getLongExtra("vod_position", 0);
            long duration = data.getLongExtra("vod_duration", 0);
            if (id != null) {
                String key = type + "_" + id;
                if (added)   webView.evaluateJavascript("S.favs.add('"    + key + "');saveFavs();", null);
                if (removed) webView.evaluateJavascript("S.favs.delete('" + key + "');saveFavs();", null);
                // Guardar progreso VOD en localStorage del WebView
                if (position > 0 && duration > 0) {
                    webView.evaluateJavascript(
                        "saveVodProgress('" + id + "'," + position + "," + duration + ");", null
                    );
                }
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
