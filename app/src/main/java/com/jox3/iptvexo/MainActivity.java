package com.jox3.iptvexo;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.splashscreen.SplashScreen;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

import okhttp3.Request;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "IPTV_EXO";
    /** Preferencia: usar la interfaz web anterior en TV en lugar de la nativa. */
    public static final String PREFS_UI = "ui_prefs";
    public static final String KEY_CLASSIC_TV = "classic_tv";
    private static final String UI_ORIGIN = "file:///android_asset/";

    private WebView webView;
    /** La pantalla de inicio se mantiene hasta que la interfaz termina de cargar. */
    private volatile boolean uiReady = false;
    /** Actualización automática desde GitHub Releases. */
    private Updater updater;

    // Activity Result API (reemplaza a startActivityForResult/onActivityResult, obsoletos)
    private final ActivityResultLauncher<Intent> playerLauncher =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
            result -> onPlayerResult(result.getData()));
    private final ActivityResultLauncher<String[]> m3uPicker =
        registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onM3uPicked);
    /** Límite de lectura de un M3U local (ya no pasa por evaluateJavascript, así que puede ser mayor). */
    private static final int MAX_M3U_CHARS = 20 * 1024 * 1024;

    // Almacén temporal de resultados grandes — se recogen desde JS con AndroidPlayer.getResult(id)
    // en lugar de inyectarlos como texto en evaluateJavascript (evita falta de memoria).
    private final ConcurrentHashMap<String, String> resultStore = new ConcurrentHashMap<>();

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Pantalla de inicio oficial de Android 12+ (compatible con versiones anteriores)
        SplashScreen splash = SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
        splash.setKeepOnScreenCondition(() -> !uiReady);

        // En TV se usa la interfaz nativa nueva (Compose), salvo que el usuario
        // haya elegido la anterior desde Ajustes.
        if (isTvDevice() && !getSharedPreferences(PREFS_UI, MODE_PRIVATE).getBoolean(KEY_CLASSIC_TV, false)) {
            uiReady = true;
            startActivity(new Intent(this, com.jox3.iptvexo.tv.TvActivity.class));
            finish();
            return;
        }
        // Seguridad: nunca más de 4 s de splash aunque la página tarde
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> uiReady = true, 4000);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setAllowUniversalAccessFromFileURLs(true);
        ws.setAllowFileAccessFromFileURLs(true);
        // Caché normal: los logos de canales se guardan y la lista abre mucho más rápido.
        // (Antes se borraba la caché en cada inicio y se forzaba LOAD_NO_CACHE.)
        // La API no se ve afectada: pasa por el puente Java (fetchUrl), no por el WebView.
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        ws.setUserAgentString(NetClient.API_USER_AGENT);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                // Logos/imágenes de paneles IPTV con certificados vencidos o autofirmados
                handler.proceed();
            }

            // El puente AndroidPlayer solo debe estar disponible para la interfaz local.
            // Cualquier navegación fuera de ella se abre en otra app, nunca dentro del WebView.
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleNavigation(request.getUrl().toString());
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleNavigation(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                uiReady = true;
                // En TV las teclas del control solo llegan a la interfaz si el WebView tiene el foco
                view.requestFocus();
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                            WebResourceResponse errorResponse) {
                Log.w(TAG, "HTTP " + errorResponse.getStatusCode() + " al cargar: " + request.getUrl());
                super.onReceivedHttpError(view, request, errorResponse);
            }
        });

        webView.addJavascriptInterface(new Bridge(), "AndroidPlayer");

        // Detectar TV Box y cargar versión optimizada
        if (isTvDevice()) {
            // TV Box — forzar landscape fullscreen
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            WindowInsetsControllerCompat c =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
            c.hide(WindowInsetsCompat.Type.systemBars());
            c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            webView.loadUrl(UI_ORIGIN + "player_tv.html");
        } else {
            webView.loadUrl(UI_ORIGIN + "player.html");
        }

        // Buscar versión nueva al abrir (como mucho cada 12 h)
        updater = new Updater(this);
        updater.checkOnStartup();

        // Atrás con la API moderna (OnBackPressedDispatcher)
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { handleBack(); }
        });
    }

    private boolean isTvDevice() {
        boolean leanback = getPackageManager().hasSystemFeature(
            android.content.pm.PackageManager.FEATURE_LEANBACK);
        boolean tvMode = (getResources().getConfiguration().uiMode &
            android.content.res.Configuration.UI_MODE_TYPE_MASK) ==
            android.content.res.Configuration.UI_MODE_TYPE_TELEVISION;
        return leanback || tvMode;
    }

    /** true = navegación bloqueada en el WebView (se abre fuera si es posible). */
    private boolean handleNavigation(String url) {
        if (url == null) return true;
        if (url.startsWith(UI_ORIGIN)) return false;
        try {
            Intent i = url.startsWith("intent:")
                ? Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                : new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addCategory(Intent.CATEGORY_BROWSABLE);
            i.setComponent(null);
            startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "No se pudo abrir: " + url);
        }
        return true;
    }

    /** Ejecuta JS en el hilo principal, ignorando llamadas si la Activity ya se cerró. */
    private void runJs(String js) {
        webView.post(() -> {
            if (!isFinishing() && !isDestroyed()) webView.evaluateJavascript(js, null);
        });
    }

    /** Literal de string JS seguro (comillas, saltos de línea y barras escapados). */
    private static String js(String s) {
        return JSONObject.quote(s == null ? "" : s);
    }

    class Bridge {

        /** Versión instalada, para mostrarla en la interfaz. */
        @JavascriptInterface
        public String appVersion() {
            return updater != null ? updater.installedVersion() : "";
        }

        /** Desde la interfaz anterior de TV: volver a la interfaz nativa nueva. */
        @JavascriptInterface
        public void useNewTvUi() {
            getSharedPreferences(PREFS_UI, MODE_PRIVATE).edit().putBoolean(KEY_CLASSIC_TV, false).apply();
            runOnUiThread(() -> {
                startActivity(new Intent(MainActivity.this, com.jox3.iptvexo.tv.TvActivity.class));
                finish();
            });
        }

        /** Botón "Buscar actualizaciones". */
        @JavascriptInterface
        public void checkUpdates() {
            if (updater != null) updater.checkNow();
        }

        @JavascriptInterface
        public String getResult(String callbackId) {
            return resultStore.remove(callbackId); // devuelve y elimina
        }

        // Descarga nativa con DownloadManager: notificación con progreso,
        // archivo en Descargas/JOX3. Llamado desde dlFromVodModal() en el HTML.
        @JavascriptInterface
        public void downloadFile(String url, String name) {
            try {
                // Extensión real desde el path de la URL (mkv/mp4/avi/ts)
                String ext = "mp4";
                String path = Uri.parse(url).getPath();
                if (path != null) {
                    int dot = path.lastIndexOf('.');
                    if (dot > 0 && dot > path.lastIndexOf('/')) {
                        String cand = path.substring(dot + 1);
                        if (cand.length() <= 5 && cand.matches("[A-Za-z0-9]+")) ext = cand;
                    }
                }
                // Nombre de archivo seguro
                String safe = (name == null || name.trim().isEmpty() ? "video" : name)
                        .replaceAll("[^\\p{L}\\p{N} ._()\\-]", "").trim();
                if (safe.isEmpty()) safe = "video";
                if (safe.length() > 80) safe = safe.substring(0, 80);

                android.app.DownloadManager.Request req =
                    new android.app.DownloadManager.Request(Uri.parse(url));
                req.setTitle(safe);
                req.setDescription("Descargando desde JOX3 TV");
                req.setMimeType("video/*");
                req.addRequestHeader("User-Agent", NetClient.PLAYER_USER_AGENT);
                req.setNotificationVisibility(
                    android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                req.setDestinationInExternalPublicDir(
                    android.os.Environment.DIRECTORY_DOWNLOADS, "JOX3/" + safe + "." + ext);
                req.setAllowedOverMetered(true);
                req.setAllowedOverRoaming(true);

                android.app.DownloadManager dm =
                    (android.app.DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                dm.enqueue(req);
            } catch (Exception e) {
                runOnUiThread(() -> android.widget.Toast.makeText(MainActivity.this,
                    "Error al iniciar descarga: " + e.getMessage(),
                    android.widget.Toast.LENGTH_LONG).show());
            }
        }

        // Devolver progreso guardado en SharedPreferences al WebView
        @JavascriptInterface
        public String getAllVodProgress() {
            android.content.SharedPreferences prefs =
                getSharedPreferences("vod_progress", MODE_PRIVATE);
            java.util.Map<String, ?> all = prefs.getAll();
            JSONObject out = new JSONObject();
            for (java.util.Map.Entry<String, ?> entry : all.entrySet()) {
                String key = entry.getKey();
                if (!key.startsWith("pos_")) continue;
                String id = key.substring(4);
                long pos = prefs.getLong("pos_" + id, 0);
                long dur = prefs.getLong("dur_" + id, 0);
                if (pos > 0 && dur > 0) {
                    try {
                        out.put(id, new JSONObject().put("pos", pos).put("dur", dur));
                    } catch (Exception ignored) {}
                }
            }
            return out.toString();
        }

        // ── Petición API desde Java — evita el bloqueo CORS de Android 15 ──
        @JavascriptInterface
        public void fetchUrl(final String url, final String callbackId) {
            // El id se concatena en JS: solo se aceptan ids alfanuméricos
            if (callbackId == null || !callbackId.matches("[A-Za-z0-9_]{1,64}")) return;
            NetClient.IO.execute(() -> {
                String result = null;
                String error  = null;
                try {
                    result = doFetch(url);
                } catch (IOException e1) {
                    // Solo ante fallo de CONEXIÓN se prueba el otro protocolo (http ↔ https).
                    // Un error HTTP (403, 404, 500) es una respuesta real del servidor y se informa tal cual.
                    String altUrl = null;
                    if (url.startsWith("https://")) altUrl = "http://" + url.substring(8);
                    else if (url.startsWith("http://")) altUrl = "https://" + url.substring(7);
                    if (altUrl != null && !(e1 instanceof HttpStatusException)) {
                        try {
                            result = doFetch(altUrl);
                            updateHostProtocol(altUrl);
                        } catch (Exception e2) {
                            error = describe(e1);
                        }
                    } else {
                        error = describe(e1);
                    }
                } catch (Exception e) {
                    error = describe(e);
                }

                String cb = js(callbackId);
                if (result != null) {
                    resultStore.put(callbackId, result);
                    runJs("if(window._jcb&&window._jcb[" + cb + "]){" +
                          "var _d=AndroidPlayer.getResult(" + cb + ");" +
                          "window._jcb[" + cb + "](null,_d);" +
                          "delete window._jcb[" + cb + "];}");
                } else {
                    runJs("if(window._jcb&&window._jcb[" + cb + "]){" +
                          "window._jcb[" + cb + "](" + js(error) + ",null);" +
                          "delete window._jcb[" + cb + "];}");
                }
            });
        }

        private String describe(Exception e) {
            if (e instanceof HttpStatusException) return e.getMessage();
            if (e instanceof java.net.SocketTimeoutException) return "Tiempo de espera agotado";
            if (e instanceof java.net.UnknownHostException) return "Servidor no encontrado";
            return e.getMessage() != null ? e.getMessage() : "Error de red";
        }

        private String doFetch(String url) throws IOException {
            Request req = new Request.Builder()
                .url(url)
                .header("User-Agent", NetClient.API_USER_AGENT)
                .header("Accept", "application/json, */*")
                .build();
            // try-with-resources: la respuesta se cierra siempre, también si hay error
            try (Response resp = NetClient.api().newCall(req).execute()) {
                if (!resp.isSuccessful()) throw new HttpStatusException(resp.code());
                String body = resp.body() != null ? resp.body().string() : "";
                if (body.isEmpty()) throw new IOException("Respuesta vacía del servidor");
                return body;
            }
        }

        // Detectar protocolo correcto del host y notificar al WebView
        private void updateHostProtocol(final String workingUrl) {
            try {
                java.net.URL u = new java.net.URL(workingUrl);
                String correctHost = u.getProtocol() + "://" + u.getHost() +
                    (u.getPort() > 0 ? ":" + u.getPort() : "");
                String h = js(correctHost);
                runJs("if(typeof S!=='undefined'&&S.host&&S.host!==" + h + "){S.host=" + h + ";}");
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public void openPlayer(String url, String name, String group, String type,
                               String logo, String itemId, String channelsJson, int channelIndex,
                               boolean isSeries) {
            // La lista va por memoria, no por el Intent (límite de ~1 MB de Android)
            PlayerHandoff.put(channelsJson != null ? channelsJson : "[]");
            Intent i = new Intent(MainActivity.this, PlayerActivity.class);
            i.putExtra("url", url);
            i.putExtra("name", name);
            i.putExtra("group", group);
            i.putExtra("type", type);
            i.putExtra("logo", logo);
            i.putExtra("id", itemId);
            i.putExtra("channel_index", channelIndex);
            i.putExtra("channels_in_handoff", true);
            i.putExtra("is_series", isSeries);
            runOnUiThread(() -> playerLauncher.launch(i));
        }

        @JavascriptInterface
        public void openFilePicker() {
            runOnUiThread(() -> {
                try {
                    m3uPicker.launch(new String[]{
                        "text/plain", "audio/x-mpegurl", "audio/mpegurl",
                        "application/octet-stream", "application/vnd.apple.mpegurl",
                        "application/x-mpegurl"
                    });
                } catch (android.content.ActivityNotFoundException e) {
                    // Muchos TV Box no traen selector de archivos
                    runJs("toast('\u274C Este dispositivo no tiene selector de archivos')");
                }
            });
        }

        @JavascriptInterface public void playUrl(String url) {}
        @JavascriptInterface public void stop() {}
        @JavascriptInterface public void goFullscreen() {}
    }

    /** Error HTTP real del servidor (no se reintenta cambiando de protocolo). */
    static class HttpStatusException extends IOException {
        HttpStatusException(int code) { super("HTTP " + code); }
    }

    /** Resultado del reproductor: favoritos y progreso VOD. */
    private void onPlayerResult(Intent data) {
        if (data == null) return;
        boolean added   = data.getBooleanExtra("fav_added", false);
        boolean removed = data.getBooleanExtra("fav_removed", false);
        String id   = data.getStringExtra("item_id");
        String type = data.getStringExtra("item_type");
        long position = data.getLongExtra("vod_position", 0);
        long duration = data.getLongExtra("vod_duration", 0);
        if (id == null) return;
        String key = js(type + "_" + id);
        if (added)   runJs("S.favs.add(" + key + ");saveFavs();");
        if (removed) runJs("S.favs.delete(" + key + ");saveFavs();");
        // Guardar progreso VOD en localStorage del WebView
        if (position > 0 && duration > 0) {
            runJs("saveVodProgress(" + js(id) + "," + position + "," + duration + ");");
        }
    }

    /** Lista M3U elegida en el selector de archivos. */
    private void onM3uPicked(Uri uri) {
        if (uri == null) return;
        String fileName = displayName(uri);
        // Leer fuera del hilo principal: un M3U grande congelaba la app
        NetClient.IO.execute(() -> {
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                if (is == null) throw new IOException("Archivo no disponible");
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                boolean truncated = false;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                    if (sb.length() > MAX_M3U_CHARS) { truncated = true; break; }
                }
                String key = "m3u_" + System.currentTimeMillis();
                resultStore.put(key, sb.toString());
                runJs("loadLocalM3U(AndroidPlayer.getResult(" + js(key) + ")," + js(fileName) + ");"
                    + (truncated ? "toast('\u26A0\uFE0F Lista muy grande: se carg\u00f3 solo una parte');" : ""));
            } catch (Exception e) {
                runJs("toast('\u274C Error al leer el archivo')");
            }
        });
    }

    /** Nombre real del archivo elegido (antes salía algo como "primary:Download/lista.m3u"). */
    private String displayName(Uri uri) {
        String name = null;
        try (Cursor c = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (Exception ignored) {}
        if (name == null || name.isEmpty()) {
            name = uri.getLastPathSegment();
            if (name != null && name.contains("/")) name = name.substring(name.lastIndexOf('/') + 1);
        }
        return (name == null || name.isEmpty()) ? "Lista local" : name;
    }

    private long backPressedTime = 0;

    private void handleBack() {
        // Preguntar primero al HTML: cierra modales, sale de categorías, vuelve al home...
        webView.evaluateJavascript(
            "(function(){try{return (typeof appBack==='function')?appBack():'exit'}catch(e){return 'exit'}})()",
            value -> {
                if (value != null && value.contains("handled")) {
                    return; // el HTML manejó el atrás (cerró algo o navegó)
                }
                // El HTML dice 'exit' — aplicar doble-atrás para salir
                runOnUiThread(() -> {
                    if (System.currentTimeMillis() - backPressedTime < 2000) {
                        finish();
                    } else {
                        backPressedTime = System.currentTimeMillis();
                        android.widget.Toast.makeText(MainActivity.this,
                            "Presiona atrás de nuevo para salir",
                            android.widget.Toast.LENGTH_SHORT).show();
                    }
                });
            });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Volviendo de "Permitir instalar apps": continuar con la actualización
        if (updater != null) updater.onResume();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.removeJavascriptInterface("AndroidPlayer");
            webView.destroy();
        }
        super.onDestroy();
    }
}
