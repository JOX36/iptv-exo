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
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private static final int PROXY_PORT   = 7788;
    private ServerSocket proxyServer;
    private ExecutorService proxyPool;
    private OkHttpClient httpClient;

    @SuppressLint({"SetJavaScriptEnabled","TrustAllX509TrustManager"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Construir cliente HTTP con SSL bypass
        httpClient = buildUnsafeClient();

        // Iniciar proxy local
        startProxy();

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

    // ── PROXY LOCAL ──────────────────────────────────────────────
    private void startProxy() {
        proxyPool = Executors.newCachedThreadPool();
        proxyPool.execute(() -> {
            try {
                proxyServer = new ServerSocket(PROXY_PORT);
                while (!proxyServer.isClosed()) {
                    try {
                        Socket client = proxyServer.accept();
                        proxyPool.execute(() -> handleProxyRequest(client));
                    } catch (IOException ignored) {}
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    private void handleProxyRequest(Socket client) {
        try {
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(client.getInputStream())
            );
            // Leer primera línea: GET /http://servidor/... HTTP/1.1
            String requestLine = reader.readLine();
            if (requestLine == null) { client.close(); return; }

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) { client.close(); return; }

            // La URL real viene después de /proxy?url=
            String path = parts[1];
            String targetUrl = null;
            if (path.startsWith("/proxy?url=")) {
                targetUrl = java.net.URLDecoder.decode(
                    path.substring("/proxy?url=".length()), "UTF-8"
                );
            }

            if (targetUrl == null) {
                sendProxyError(client, 400, "Bad Request");
                return;
            }

            // Hacer la petición al servidor real
            Request req = new Request.Builder()
                .url(targetUrl)
                .header("User-Agent", "VLC/3.0.18 LibVLC/3.0.18")
                .build();

            Response resp = httpClient.newCall(req).execute();
            byte[] body = resp.body() != null ? resp.body().bytes() : new byte[0];

            // Responder al WebView con CORS headers
            OutputStream out = client.getOutputStream();
            String headers = "HTTP/1.1 " + resp.code() + " OK\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                "Content-Length: " + body.length + "\r\n" +
                "Connection: close\r\n\r\n";
            out.write(headers.getBytes("UTF-8"));
            out.write(body);
            out.flush();
            resp.close();
        } catch (Exception e) {
            try { sendProxyError(client, 500, e.getMessage()); } catch (Exception ignored) {}
        } finally {
            try { client.close(); } catch (IOException ignored) {}
        }
    }

    private void sendProxyError(Socket client, int code, String msg) throws IOException {
        String resp = "HTTP/1.1 " + code + " Error\r\n" +
            "Content-Type: text/plain\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Content-Length: " + (msg != null ? msg.length() : 0) + "\r\n\r\n" +
            (msg != null ? msg : "");
        client.getOutputStream().write(resp.getBytes("UTF-8"));
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
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build();
        } catch (Exception e) {
            return new OkHttpClient.Builder().build();
        }
    }

    // ── BRIDGE ───────────────────────────────────────────────────
    class Bridge {

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

        @JavascriptInterface
        public int getProxyPort() { return PROXY_PORT; }

        @JavascriptInterface public void playUrl(String url) {}
        @JavascriptInterface public void stop() {}
        @JavascriptInterface public void goFullscreen() {}
    }

    // ── ACTIVITY RESULTS ─────────────────────────────────────────
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

    // ── LIFECYCLE ────────────────────────────────────────────────
    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { if (proxyServer != null) proxyServer.close(); } catch (IOException ignored) {}
        if (proxyPool != null) proxyPool.shutdownNow();
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
