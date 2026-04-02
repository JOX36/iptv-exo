package com.jox3.iptvexo;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.pm.ActivityInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import okhttp3.OkHttpClient;

@UnstableApi
public class MultiviewActivity extends AppCompatActivity {

    private static final int MAX_CELLS = 4;

    // Players y vistas por celda
    private final ExoPlayer[]  players   = new ExoPlayer[MAX_CELLS];
    private final PlayerView[] views     = new PlayerView[MAX_CELLS];
    private final FrameLayout[]cells     = new FrameLayout[MAX_CELLS];
    private final LinearLayout[]overlays = new LinearLayout[MAX_CELLS];
    private final TextView[]   labels    = new TextView[MAX_CELLS];
    private final ProgressBar[]loadings  = new ProgressBar[MAX_CELLS];
    private final View[]       rings     = new View[MAX_CELLS];
    private final String[]     urls      = new String[MAX_CELLS];
    private final String[]     names     = new String[MAX_CELLS];

    // Canales disponibles para seleccionar
    private List<JSONObject> channels = new ArrayList<>();

    // Celda con audio activo (-1 = todas silenciadas)
    private int activeAudio = -1;

    // Barra de controles
    private LinearLayout bottomBar;
    private Button btnClose, btn2x2, btn2x1, btnMuteAll;

    private final Handler handler = new Handler();
    private boolean barsVisible = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        setContentView(R.layout.activity_multiview);

        // Leer canales del intent
        String channelsJson = getIntent().getStringExtra("channels_json");
        String firstUrl     = getIntent().getStringExtra("url");
        String firstName    = getIntent().getStringExtra("name");
        parseChannels(channelsJson);

        bindViews();
        setupControls();

        // Cargar primer canal en celda 0 automáticamente
        if (firstUrl != null && !firstUrl.isEmpty()) {
            loadChannel(0, firstUrl, firstName != null ? firstName : "Canal 1");
        }
    }

    private void parseChannels(String json) {
        if (json == null || json.isEmpty()) return;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) channels.add(arr.getJSONObject(i));
        } catch (Exception ignored) {}
    }

    @SuppressLint("ClickableViewAccessibility")
    private void bindViews() {
        int[] cellIds    = {R.id.cell_0, R.id.cell_1, R.id.cell_2, R.id.cell_3};
        int[] viewIds    = {R.id.player_view_0, R.id.player_view_1, R.id.player_view_2, R.id.player_view_3};
        int[] overlayIds = {R.id.overlay_0, R.id.overlay_1, R.id.overlay_2, R.id.overlay_3};
        int[] labelIds   = {R.id.label_0, R.id.label_1, R.id.label_2, R.id.label_3};
        int[] loadingIds = {R.id.loading_0, R.id.loading_1, R.id.loading_2, R.id.loading_3};
        int[] ringIds    = {R.id.focus_ring_0, R.id.focus_ring_1, R.id.focus_ring_2, R.id.focus_ring_3};

        bottomBar  = findViewById(R.id.mv_bottom_bar);
        btnClose   = findViewById(R.id.mv_btn_close);
        btn2x2     = findViewById(R.id.mv_btn_2x2);
        btn2x1     = findViewById(R.id.mv_btn_2x1);
        btnMuteAll = findViewById(R.id.mv_btn_mute_all);

        for (int i = 0; i < MAX_CELLS; i++) {
            cells[i]    = findViewById(cellIds[i]);
            views[i]    = findViewById(viewIds[i]);
            overlays[i] = findViewById(overlayIds[i]);
            labels[i]   = findViewById(labelIds[i]);
            loadings[i] = findViewById(loadingIds[i]);
            rings[i]    = findViewById(ringIds[i]);

            views[i].setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL);
            views[i].setUseController(false);

            final int idx = i;
            cells[i].setOnClickListener(v -> onCellClick(idx));
            cells[i].setOnLongClickListener(v -> { onCellLongClick(idx); return true; });
        }
    }

    private void setupControls() {
        btnClose.setOnClickListener(v -> finish());
        btnMuteAll.setOnClickListener(v -> {
            activeAudio = -1;
            for (int i = 0; i < MAX_CELLS; i++) {
                if (players[i] != null) players[i].setVolume(0f);
                updateRing(i);
            }
            toast("🔇 Todo silenciado");
        });
        btn2x2.setOnClickListener(v -> toast("Modo 2x2 activo"));
        btn2x1.setOnClickListener(v -> toast("Modo 2x1 próximamente"));
    }

    // ── CLICK: activar audio de esa celda o elegir canal ──
    private void onCellClick(int idx) {
        if (urls[idx] == null) {
            // Celda vacía → elegir canal
            showChannelPicker(idx);
        } else {
            // Celda con canal → activar audio
            activateAudio(idx);
        }
        toggleBars();
    }

    // ── LONG CLICK: cambiar canal de esa celda ──
    private void onCellLongClick(int idx) {
        showChannelPicker(idx);
    }

    private void activateAudio(int idx) {
        activeAudio = idx;
        for (int i = 0; i < MAX_CELLS; i++) {
            if (players[i] != null) {
                players[i].setVolume(i == idx ? 1f : 0f);
            }
            updateRing(i);
        }
        toast("🔊 Audio: " + (names[idx] != null ? names[idx] : "Canal " + (idx + 1)));
    }

    private void updateRing(int idx) {
        if (rings[idx] == null) return;
        if (idx == activeAudio) {
            rings[idx].setBackgroundResource(android.R.color.transparent);
            rings[idx].setVisibility(View.VISIBLE);
            cells[idx].setForeground(getDrawable(android.R.drawable.list_selector_background));
        } else {
            rings[idx].setVisibility(View.GONE);
            cells[idx].setForeground(null);
        }
    }

    // ── SELECTOR DE CANAL ──
    private void showChannelPicker(int cellIdx) {
        if (channels.isEmpty()) {
            toast("Sin canales disponibles");
            return;
        }
        String[] names = new String[channels.size()];
        for (int i = 0; i < channels.size(); i++) {
            names[i] = channels.get(i).optString("name", "Canal " + i);
        }
        new AlertDialog.Builder(this)
            .setTitle("Elegir canal para celda " + (cellIdx + 1))
            .setItems(names, (d, which) -> {
                JSONObject ch = channels.get(which);
                String url  = ch.optString("url", "");
                String name = ch.optString("name", "Canal");
                if (!url.isEmpty()) loadChannel(cellIdx, url, name);
            })
            .setNegativeButton("Cancelar", null)
            .show();
    }

    // ── CARGAR CANAL EN CELDA ──
    private void loadChannel(int idx, String url, String name) {
        // Liberar player anterior si existe
        if (players[idx] != null) {
            views[idx].setPlayer(null);
            players[idx].release();
            players[idx] = null;
        }

        urls[idx]   = url;
        names[idx]  = name;

        // Mostrar loading, ocultar overlay
        overlays[idx].setVisibility(View.GONE);
        loadings[idx].setVisibility(View.VISIBLE);
        labels[idx].setText(name);
        labels[idx].setVisibility(View.VISIBLE);

        // Crear player
        OkHttpDataSource.Factory dsf = new OkHttpDataSource.Factory(buildUnsafeClient());
        ExoPlayer p = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(dsf))
                .build();
        p.setVolume(0f); // Silenciado por defecto
        p.setMediaItem(MediaItem.fromUri(url));
        p.prepare();
        p.play();
        views[idx].setPlayer(p);
        players[idx] = p;

        final int i = idx;
        p.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    runOnUiThread(() -> {
                        loadings[i].setVisibility(View.GONE);
                        // Si es la primera celda cargada, activar su audio
                        if (activeAudio == -1) activateAudio(i);
                    });
                } else if (state == Player.STATE_BUFFERING) {
                    runOnUiThread(() -> loadings[i].setVisibility(View.VISIBLE));
                }
            }
            @Override
            public void onPlayerError(androidx.media3.common.PlaybackException e) {
                runOnUiThread(() -> {
                    loadings[i].setVisibility(View.GONE);
                    toast("Error en canal " + (i + 1));
                });
            }
        });
    }

    // ── BARRA DE CONTROLES ──
    private void toggleBars() {
        barsVisible = !barsVisible;
        bottomBar.setVisibility(barsVisible ? View.VISIBLE : View.GONE);
        if (barsVisible) {
            handler.removeCallbacksAndMessages(null);
            handler.postDelayed(() -> {
                bottomBar.setVisibility(View.GONE);
                barsVisible = false;
            }, 4000);
        }
    }

    // ── SSL BYPASS ──
    @SuppressLint("TrustAllX509TrustManager")
    private OkHttpClient buildUnsafeClient() {
        try {
            X509TrustManager tm = new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) throws CertificateException {}
                public void checkServerTrusted(X509Certificate[] c, String a) throws CertificateException {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            };
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, new TrustManager[]{tm}, new java.security.SecureRandom());
            return new OkHttpClient.Builder()
                .sslSocketFactory(sc.getSocketFactory(), tm)
                .hostnameVerifier((h, s) -> true).build();
        } catch (Exception e) { return new OkHttpClient.Builder().build(); }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    // ── LIFECYCLE ──
    @Override
    protected void onPause() {
        super.onPause();
        for (ExoPlayer p : players) if (p != null) p.setPlayWhenReady(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        for (ExoPlayer p : players) if (p != null) p.setPlayWhenReady(true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        for (int i = 0; i < MAX_CELLS; i++) {
            if (players[i] != null) {
                views[i].setPlayer(null);
                players[i].release();
                players[i] = null;
            }
        }
    }

    @Override
    public void onBackPressed() {
        finish();
    }
}
