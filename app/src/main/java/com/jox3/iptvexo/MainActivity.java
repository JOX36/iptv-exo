package com.jox3.iptvexo;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.net.http.SslError;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.appcompat.app.AppCompatActivity;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    // Almacén temporal de respuestas — evita pasar JSON por evaluateJavascript
    private final ConcurrentHashMap<String, String> fetchResults = new ConcurrentHashMap<>();

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        ws.setMediaPlaybackRequiresUserGesture(false);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.proceed();
            }
        });

        webView.addJavascriptInterface(new Bridge(), "AndroidPlayer");
        webView.loadUrl("file:///android_asset/player.html");
    }

    class Bridge {

        // ── fetchUrl: bridge HTTP sin CORS ──
        // El resultado se guarda en fetchResults y JS lo lee con fetchGetResult()
        // Así evitamos pasar JSON por evaluateJavascript (que corrompe caracteres especiales)
        @JavascriptInterface
        public void fetchUrl(String urlStr, String callbackId) {
            executor.execute(() -> {
                String result = null;
                String error  = null;
                try {
                    URL url = new URL(urlStr);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(12000);
                    conn.setReadTimeout(20000);
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                    conn.setRequestProperty("Accept", "application/json");
                    int code = conn.getResponseCode();
                    if (code >= 200 && code < 300) {
                        BufferedReader br = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), "UTF-8"));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = br.readLine()) != null) sb.append(line).append('\n');
                        br.close();
                        result = sb.toString();
                    } else {
                        error = "HTTP " + code;
                    }
                } catch (Exception e) {
                    error = e.getMessage() != null ? e.getMessage() : "error";
                }

                // Guardar resultado en el mapa (el JS lo recupera con fetchGetResult)
                if (result != null) {
                    fetchResults.put(callbackId, result);
                }
                final String finalError = error;

                // Notificar al JS solo con el callbackId (sin datos en la URL del JS)
                runOnUiThread(() -> {
                    if (finalError != null) {
                        // Escapar solo lo mínimo para pasar el mensaje de error
                        String safeErr = finalError.replace("'", " ").replace("\n", " ").substring(
                            0, Math.min(finalError.length(), 100));
                        webView.evaluateJavascript(
                            "(function(){var cb=window._jcb['" + callbackId + "'];" +
                            "if(cb){cb('" + safeErr + "',null);" +
                            "delete window._jcb['" + callbackId + "'];}})();", null);
                    } else {
                        // Sin datos en JS — el callback llama fetchGetResult() para leer
                        webView.evaluateJavascript(
                            "(function(){var cb=window._jcb['" + callbackId + "'];" +
                            "if(cb){var d=AndroidPlayer.fetchGetResult('" + callbackId + "');" +
                            "cb(null,d);delete window._jcb['" + callbackId + "'];}})();", null);
                    }
                });
            });
        }

        // ── fetchGetResult: JS llama esto para obtener la respuesta guardada ──
        @JavascriptInterface
        public String fetchGetResult(String callbackId) {
            String result = fetchResults.remove(callbackId);
            return result != null ? result : "";
        }

        // ── openPlayer: único método con 10 parámetros ──
        @JavascriptInterface
        public void openPlayer(String url, String name, String group, String type,
                               String logo, String itemId,
                               String channelsJson, int channelIndex,
                               String episodesJson, int episodeIndex) {
            Intent i = new Intent(MainActivity.this, PlayerActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            i.putExtra("url",           url);
            i.putExtra("name",          name);
            i.putExtra("group",         group);
            i.putExtra("type",          type);
            i.putExtra("logo",          logo);
            i.putExtra("id",            itemId);
            i.putExtra("channels_json", channelsJson != null ? channelsJson : "[]");
            i.putExtra("channel_index", channelIndex);
            i.putExtra("episodes_json", episodesJson != null ? episodesJson : "[]");
            i.putExtra("episode_index", episodeIndex);
            startActivityForResult(i, 1001);
        }

        // ── openFilePicker: seleccionar archivo M3U local ──
        @JavascriptInterface
        public void openFilePicker() {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            try {
                startActivityForResult(Intent.createChooser(intent, "Seleccionar M3U"), 2001);
            } catch (Exception e) {
                android.widget.Toast.makeText(MainActivity.this,
                    "No se pudo abrir el selector", android.widget.Toast.LENGTH_SHORT).show();
            }
        }

        // ── getAllVodProgress: progreso VOD (guardado en localStorage del WebView) ──
        @JavascriptInterface
        public String getAllVodProgress() {
            return "{}";
        }

        @JavascriptInterface public void playUrl(String url) {}
        @JavascriptInterface public void stop() {}
        @JavascriptInterface public void goFullscreen() {}
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);

        // Resultado PlayerActivity → sincronizar favs
        if (req == 1001 && data != null) {
            boolean added   = data.getBooleanExtra("fav_added", false);
            boolean removed = data.getBooleanExtra("fav_removed", false);
            String id       = data.getStringExtra("item_id");
            String type     = data.getStringExtra("item_type");
            if (id != null) {
                String key = type + "_" + id;
                if (added)   webView.evaluateJavascript("S.favs.add('"    + key + "');saveFavs();", null);
                if (removed) webView.evaluateJavascript("S.favs.delete('" + key + "');saveFavs();", null);
            }
        }

        // Resultado selector archivo M3U
        if (req == 2001 && res == RESULT_OK && data != null && data.getData() != null) {
            android.net.Uri uri = data.getData();
            executor.execute(() -> {
                try {
                    java.io.InputStream is = getContentResolver().openInputStream(uri);
                    BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line).append('\n');
                    br.close();
                    // Guardar contenido M3U en fetchResults con clave especial
                    fetchResults.put("__m3u__", sb.toString());
                    String filename = uri.getLastPathSegment();
                    if (filename == null) filename = "lista.m3u";
                    final String fname = filename.replace("'", "");
                    runOnUiThread(() -> webView.evaluateJavascript(
                        "(function(){var c=AndroidPlayer.fetchGetResult('__m3u__');" +
                        "if(typeof receiveLocalM3U==='function')receiveLocalM3U(c,'" + fname + "');})()", null));
                } catch (Exception e) {
                    runOnUiThread(() -> android.widget.Toast.makeText(MainActivity.this,
                        "Error: " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show());
                }
            });
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
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
