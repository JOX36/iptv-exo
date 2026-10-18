package com.jox3.iptvexo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Actualización automática desde GitHub Releases (igual que JOX3 Verifier).
 *
 * Cada cambio que llega a main se publica en GitHub como versión "vX.Y.N" con el APK.
 * Al abrir la app (como mucho cada 12 h) se consulta la última versión; si es más nueva,
 * se muestra un aviso nativo con las novedades. "Actualizar ahora" descarga el APK con
 * barra de progreso y abre el instalador de Android, que lo instala encima sin perder
 * cuentas, favoritos ni progreso.
 *
 * El aviso es un diálogo nativo (no HTML) para que funcione igual en la interfaz del
 * celular y en la del TV, y se maneje con el control remoto sin código extra.
 */
public final class Updater {

    private static final String REPO = "JOX36/iptv-exo";
    private static final String LATEST_URL = "https://api.github.com/repos/" + REPO + "/releases/latest";
    /** Archivo que publica cada versión: se descarga directo (sin el límite de la API de GitHub). */
    private static final String LATEST_JSON = "https://github.com/" + REPO + "/releases/latest/download/latest.json";
    private static final long CHECK_EVERY_MS = 12L * 60 * 60 * 1000;
    private static final String PREFS = "updater";

    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private File pendingApk;
    private AlertDialog progressDialog;
    private ProgressBar progressBar;
    private TextView progressText;
    private boolean busy;

    public Updater(Activity activity) {
        this.activity = activity;
        this.prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Revisión automática al abrir la app (respeta el intervalo y "Más tarde"). */
    public void checkOnStartup() {
        long last = prefs.getLong("last_check", 0);
        if (System.currentTimeMillis() - last < CHECK_EVERY_MS) return;
        main.postDelayed(() -> check(false), 4000);
    }

    /** Revisión manual (desde el botón "Buscar actualizaciones"). */
    public void checkNow() {
        main.post(() -> toast("Buscando actualizaciones…"));
        check(true);
    }

    /** Versión instalada, p. ej. "1.1.57". */
    public String installedVersion() {
        try {
            PackageInfo pi = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
            return pi.versionName != null ? pi.versionName : "";
        } catch (Exception e) {
            return "";
        }
    }

    /** Volviendo de "Permitir instalar apps": continuar la instalación pendiente. */
    public void onResume() {
        if (pendingApk != null && canInstall()) install(pendingApk);
    }

    // ── Consulta ─────────────────────────────────────────────────────────────

    private void check(boolean manual) {
        if (busy) return;
        busy = true;
        NetClient.IO.execute(() -> {
            try {
                String version = null, apkUrl = null, rawNotes = "";
                long size = 0;
                // 1) latest.json de la última versión (descarga directa, sin límite de consultas)
                try {
                    Request req = new Request.Builder().url(LATEST_JSON)
                            .header("User-Agent", "JOX3-TV-Updater").header("Cache-Control", "no-cache").build();
                    try (Response resp = NetClient.api().newCall(req).execute()) {
                        if (resp.isSuccessful() && resp.body() != null) {
                            JSONObject j = new JSONObject(resp.body().string());
                            version = j.optString("version", "").replaceFirst("^[vV]", "");
                            apkUrl = j.optString("apk", null);
                            size = j.optLong("size", 0);
                            rawNotes = j.optString("notes", "");
                        }
                    }
                } catch (Exception ignored) { }
                // 2) Si aún no existe (versiones viejas), la API de GitHub como antes
                if (version == null || version.isEmpty() || apkUrl == null || apkUrl.isEmpty()) {
                    Request req = new Request.Builder()
                            .url(LATEST_URL)
                            .header("Accept", "application/vnd.github+json")
                            .header("User-Agent", "JOX3-TV-Updater")
                            .build();
                    JSONObject rel;
                    try (Response resp = NetClient.api().newCall(req).execute()) {
                        if (resp.code() == 404) throw new IllegalStateException("Aún no hay versiones publicadas");
                        if (resp.code() == 403 || resp.code() == 429)
                            throw new IllegalStateException("GitHub está limitando las consultas, intenta en un rato");
                        if (!resp.isSuccessful() || resp.body() == null) throw new IllegalStateException("HTTP " + resp.code());
                        rel = new JSONObject(resp.body().string());
                    }
                    version = rel.optString("tag_name", "").replaceFirst("^[vV]", "");
                    apkUrl = null;
                    JSONArray assets = rel.optJSONArray("assets");
                    if (assets != null) {
                        for (int i = 0; i < assets.length(); i++) {
                            JSONObject a = assets.getJSONObject(i);
                            if (a.optString("name", "").toLowerCase().endsWith(".apk")) {
                                apkUrl = a.optString("browser_download_url", null);
                                size = a.optLong("size", 0);
                                break;
                            }
                        }
                    }
                    rawNotes = rel.optString("body", "");
                }
                prefs.edit().putLong("last_check", System.currentTimeMillis()).apply();
                String installed = installedVersion();
                boolean newer = apkUrl != null && buildNumber(version) > buildNumber(installed);
                if (!newer) {
                    if (manual) main.post(() -> toast("Tienes la última versión (" + installed + ")"));
                    return;
                }
                if (!manual && version.equals(prefs.getString("skip_version", ""))) return;

                final String fUrl = apkUrl;
                final String fVersion = version;
                final long fSize = size;
                final String notes = cleanNotes(rawNotes);
                main.post(() -> showUpdateDialog(fVersion, installed, fUrl, fSize, notes));
            } catch (Exception e) {
                if (manual) main.post(() -> toast("No se pudo revisar: " + e.getMessage()));
            } finally {
                busy = false;
            }
        });
    }

    /** Número de compilación: el tercer número de "1.1.57" (crece con cada versión). */
    static int buildNumber(String v) {
        Matcher m = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)").matcher(v == null ? "" : v);
        return m.find() ? Integer.parseInt(m.group(3)) : 0;
    }

    /** Novedades en texto simple: una por línea, sin marcas de formato ni enlaces. */
    static String cleanNotes(String body) {
        StringBuilder out = new StringBuilder();
        int lines = 0;
        for (String raw : body.replace("\r", "").split("\n")) {
            String l = raw.replaceFirst("^\\s*[-*•]\\s*", "")
                    .replace("**", "").replace("__", "").replace("`", "")
                    .replaceAll("\\[([^\\]]+)\\]\\([^)]*\\)", "$1").trim();
            if (l.isEmpty() || l.startsWith("#") || l.matches("(?i).*(generated with|claude\\.ai|co-authored-by|claude-session|merge pull request).*")
                    || l.matches("(?i)^https?://.*")) continue;
            out.append("•  ").append(l).append('\n');
            if (++lines >= 10) break;
        }
        return out.toString().trim();
    }

    // ── Aviso ────────────────────────────────────────────────────────────────

    private void showUpdateDialog(String version, String installed, String url, long size, String notes) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        StringBuilder msg = new StringBuilder();
        msg.append("Tienes la ").append(installed.isEmpty() ? "versión anterior" : installed);
        if (size > 0) msg.append(String.format(java.util.Locale.ROOT, " · descarga de %.1f MB", size / 1048576f));
        msg.append(".\nSe instala encima, sin perder tus cuentas ni favoritos.");
        if (!notes.isEmpty()) msg.append("\n\nQué trae esta versión:\n").append(notes);

        AlertDialog d = new AlertDialog.Builder(activity, R.style.AppDialog)
                .setTitle("Nueva versión " + version)
                .setMessage(msg.toString())
                .setPositiveButton("Actualizar ahora", (dlg, w) -> download(url, version))
                .setNegativeButton("Más tarde", (dlg, w) ->
                        prefs.edit().putString("skip_version", version).apply())
                .create();
        d.show();
        // Con el control remoto, el foco empieza en "Actualizar ahora"
        View pos = d.getButton(AlertDialog.BUTTON_POSITIVE);
        if (pos != null) pos.requestFocus();
    }

    // ── Descarga ─────────────────────────────────────────────────────────────

    private void download(String url, String version) {
        showProgress();
        NetClient.IO.execute(() -> {
            File dir = new File(activity.getCacheDir(), "updates");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File[] old = dir.listFiles();
            if (old != null) for (File f : old) //noinspection ResultOfMethodCallIgnored
                f.delete();
            File apk = new File(dir, "jox3tv-" + version + ".apk");
            // Cliente propio con lectura larga; OkHttp sigue las redirecciones de GitHub
            OkHttpClient client = NetClient.api().newBuilder()
                    .readTimeout(60, TimeUnit.SECONDS)
                    .followRedirects(true).followSslRedirects(true)
                    .build();
            try (Response resp = client.newCall(new Request.Builder().url(url)
                    .header("User-Agent", "JOX3-TV-Updater").build()).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) throw new IllegalStateException("HTTP " + resp.code());
                long total = resp.body().contentLength();
                long done = 0;
                int lastPct = -1;
                try (InputStream in = resp.body().byteStream(); OutputStream out = new FileOutputStream(apk)) {
                    byte[] buf = new byte[64 * 1024];
                    int r;
                    while ((r = in.read(buf)) > 0) {
                        out.write(buf, 0, r);
                        done += r;
                        int pct = total > 0 ? (int) (done * 100 / total) : -1;
                        if (pct != lastPct) {
                            lastPct = pct;
                            final int p = pct;
                            main.post(() -> updateProgress(p));
                        }
                    }
                }
                main.post(() -> {
                    dismissProgress();
                    install(apk);
                });
            } catch (Exception e) {
                //noinspection ResultOfMethodCallIgnored
                apk.delete();
                main.post(() -> {
                    dismissProgress();
                    toast("No se pudo descargar la actualización: " + e.getMessage());
                });
            }
        });
    }

    private void showProgress() {
        int pad = (int) (22 * activity.getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);
        progressBar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setIndeterminate(true);
        progressText = new TextView(activity);
        progressText.setTextColor(0xFF7AB8CC);
        progressText.setPadding(0, pad / 2, 0, 0);
        progressText.setText("Descargando…");
        box.addView(progressBar);
        box.addView(progressText);
        progressDialog = new AlertDialog.Builder(activity, R.style.AppDialog)
                .setTitle("Descargando actualización")
                .setView(box)
                .setCancelable(false)
                .create();
        progressDialog.show();
    }

    private void updateProgress(int pct) {
        if (progressBar == null) return;
        if (pct >= 0) {
            progressBar.setIndeterminate(false);
            progressBar.setProgress(pct);
            progressText.setText(pct >= 100 ? "Abriendo el instalador…" : "Descargando… " + pct + "%");
        }
    }

    private void dismissProgress() {
        if (progressDialog != null && progressDialog.isShowing()) {
            try { progressDialog.dismiss(); } catch (Exception ignored) {}
        }
        progressDialog = null;
        progressBar = null;
        progressText = null;
    }

    // ── Instalación ──────────────────────────────────────────────────────────

    private boolean canInstall() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || activity.getPackageManager().canRequestPackageInstalls();
    }

    /** Abre el instalador de Android (la primera vez pide "Permitir instalar apps"). */
    private void install(File apk) {
        if (!canInstall()) {
            pendingApk = apk;
            toast("Permite instalar apps de JOX3 TV y vuelve atrás para continuar");
            try {
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName())));
            } catch (Exception e) {
                toast("Activa 'Instalar apps desconocidas' para JOX3 TV en los ajustes");
            }
            return;
        }
        pendingApk = null;
        try {
            Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".files", apk);
            Intent i = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(i);
        } catch (Exception e) {
            toast("No se pudo abrir el instalador");
        }
    }

    private void toast(String msg) {
        Toast.makeText(activity, msg, Toast.LENGTH_LONG).show();
    }
}
