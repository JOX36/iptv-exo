package com.jox3.iptvexo;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.PictureInPictureParams;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.Rational;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import okhttp3.OkHttpClient;

@UnstableApi
public class PlayerActivity extends AppCompatActivity {

    // ── SINGLETON PLAYER — una sola instancia global ──
    private static ExoPlayer globalPlayer = null;
    private static PlayerActivity currentInstance = null;

    private PlayerView playerView;
    private PlayerView vodPlayerView;

    // LIVE views
    private LinearLayout topBar, bottomBar, loadingOverlay;
    private TextView txtChannelName, txtStatus, txtEpg, txtLoading;
    private ImageButton btnBack, btnFav;
    private Button btnPip, btnExternal, btnStop, btnAudio, btnSubs;
    private ProgressBar progress;

    // VOD views
    private LinearLayout vodLayout;
    private ImageButton vodBtnBack, vodBtnFav;
    private Button vodBtnPip, vodBtnExternal, vodBtnCopy, vodBtnStop, vodBtnFullscreen;
    private Button vodBtnAudio, vodBtnSubs;
    private TextView vodTxtTitle, vodTxtFullTitle, vodTxtYear, vodTxtDuration, vodTxtRating, vodTxtPlot;
    private ScrollView vodScrollView;
    private LinearLayout vodFsTopBar, vodFsBottomBar;
    private TextView vodFsTitle;
    private Button vodFsBtnExit, vodFsBtnPip, vodFsBtnExt, vodFsBtnUrl, vodFsBtnSubs;

    private String url, name, group, type, logo, itemId;
    private List<JSONObject> channels = new ArrayList<>();
    private int channelIndex = -1;

    private boolean isFav = false;
    private boolean barsVisible = false;
    private boolean favChanged = false;
    private boolean favAdded = false;
    private boolean isVodFullscreen = false;
    private int retryCount = 0;
    private Handler hideHandler = new Handler();
    private GestureDetector gestureDetector;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ── DETENER instancia anterior antes de crear nueva ──
        if (currentInstance != null && currentInstance != this) {
            currentInstance.releasePlayer();
        }
        currentInstance = this;

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
        setContentView(R.layout.activity_player);

        url          = getIntent().getStringExtra("url");
        name         = getIntent().getStringExtra("name");
        group        = getIntent().getStringExtra("group");
        type         = getIntent().getStringExtra("type");
        logo         = getIntent().getStringExtra("logo");
        itemId       = getIntent().getStringExtra("id");
        channelIndex = getIntent().getIntExtra("channel_index", -1);

        String channelsJson = getIntent().getStringExtra("channels_json");
        if (channelsJson != null && !channelsJson.isEmpty()) {
            try {
                JSONArray arr = new JSONArray(channelsJson);
                for (int i = 0; i < arr.length(); i++) channels.add(arr.getJSONObject(i));
            } catch (Exception e) { channels = new ArrayList<>(); }
        }

        // Views
        playerView     = findViewById(R.id.player_view);
        vodLayout      = findViewById(R.id.vod_layout);
        vodPlayerView  = findViewById(R.id.vod_player_view);
        topBar         = findViewById(R.id.top_bar);
        bottomBar      = findViewById(R.id.bottom_bar);
        loadingOverlay = findViewById(R.id.loading_overlay);
        txtChannelName = findViewById(R.id.txt_channel_name);
        txtStatus      = findViewById(R.id.txt_status);
        txtEpg         = findViewById(R.id.txt_epg);
        txtLoading     = findViewById(R.id.txt_loading);
        progress       = findViewById(R.id.progress);
        btnBack        = findViewById(R.id.btn_back);
        btnFav         = findViewById(R.id.btn_fav);
        btnPip         = findViewById(R.id.btn_pip);
        btnExternal    = findViewById(R.id.btn_external);
        btnStop        = findViewById(R.id.btn_stop);
        btnAudio       = findViewById(R.id.btn_audio);
        btnSubs        = findViewById(R.id.btn_subs);
        vodBtnBack     = findViewById(R.id.vod_btn_back);
        vodBtnFav      = findViewById(R.id.vod_btn_fav);
        vodBtnPip      = findViewById(R.id.vod_btn_pip);
        vodBtnExternal = findViewById(R.id.vod_btn_external);
        vodBtnCopy     = findViewById(R.id.vod_btn_copy);
        vodBtnStop     = findViewById(R.id.vod_btn_stop);
        vodBtnFullscreen = findViewById(R.id.vod_btn_fullscreen);
        vodBtnAudio    = findViewById(R.id.vod_btn_audio);
        vodBtnSubs     = findViewById(R.id.vod_btn_subs);
        vodTxtTitle    = findViewById(R.id.vod_txt_title);
        vodTxtFullTitle= findViewById(R.id.vod_txt_full_title);
        vodTxtYear     = findViewById(R.id.vod_txt_year);
        vodTxtDuration = findViewById(R.id.vod_txt_duration);
        vodTxtRating   = findViewById(R.id.vod_txt_rating);
        vodTxtPlot     = findViewById(R.id.vod_txt_plot);
        vodScrollView  = findViewById(R.id.vod_scroll);
        vodFsTopBar    = findViewById(R.id.vod_fs_top_bar);
        vodFsBottomBar = findViewById(R.id.vod_fs_bottom_bar);
        vodFsTitle     = findViewById(R.id.vod_fs_title);
        vodFsBtnExit   = findViewById(R.id.vod_fs_btn_exit);
        vodFsBtnPip    = findViewById(R.id.vod_fs_btn_pip);
        vodFsBtnExt    = findViewById(R.id.vod_fs_btn_ext);
        vodFsBtnUrl    = findViewById(R.id.vod_fs_btn_url);
        vodFsBtnSubs   = findViewById(R.id.vod_fs_btn_subs);

        boolean isVod = "vod".equals(type) || "series".equals(type);
        if (isVod) setupVodMode();
        else setupLiveMode();

        initPlayer(isVod ? vodPlayerView : playerView);
    }

    private void setupLiveMode() {
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL);
        vodLayout.setVisibility(View.GONE);
        txtChannelName.setText(name);
        txtStatus.setText("🔴 EN VIVO");
        btnBack.setOnClickListener(v -> finish());
        btnStop.setOnClickListener(v -> finish());
        btnFav.setOnClickListener(v -> toggleFav(btnFav));
        btnPip.setOnClickListener(v -> enterPip());
        btnExternal.setOnClickListener(v -> launchExternal());
        btnAudio.setOnClickListener(v -> showAudioTracks());
        btnSubs.setOnClickListener(v -> showSubtitleTracks());
        btnAudio.setVisibility(View.GONE);
        btnSubs.setVisibility(View.GONE);

        // Swipe en PlayerView para cambiar canal
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vX, float vY) {
                if (e1 == null || e2 == null) return false;
                float diff = e2.getX() - e1.getX();
                if (Math.abs(diff) > 100 && Math.abs(vX) > 100) {
                    navigateChannel(diff < 0 ? 1 : -1);
                    return true;
                }
                return false;
            }
            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                toggleBars();
                return true;
            }
        });

        playerView.setOnTouchListener((v, event) -> {
            gestureDetector.onTouchEvent(event);
            return true;
        });
    }

    private void setupVodMode() {
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        vodLayout.setVisibility(View.VISIBLE);
        playerView.setVisibility(View.GONE);
        topBar.setVisibility(View.GONE);
        bottomBar.setVisibility(View.GONE);
        vodFsTopBar.setVisibility(View.GONE);
        vodFsBottomBar.setVisibility(View.GONE);
        vodPlayerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        vodTxtTitle.setText(name);
        vodTxtFullTitle.setText(name);
        vodTxtPlot.setText("Cargando información...");
        vodFsTitle.setText(name);
        vodBtnFav.setVisibility(View.VISIBLE);
        vodBtnAudio.setVisibility(View.GONE);
        vodBtnSubs.setVisibility(View.GONE);
        vodFsBtnSubs.setVisibility(View.GONE);

        vodBtnBack.setOnClickListener(v -> finish());
        vodBtnStop.setOnClickListener(v -> finish());
        vodBtnFav.setOnClickListener(v -> toggleFav(vodBtnFav));
        vodBtnPip.setOnClickListener(v -> enterPip());
        vodBtnExternal.setOnClickListener(v -> launchExternal());
        vodBtnCopy.setOnClickListener(v -> copyUrl());
        vodBtnFullscreen.setOnClickListener(v -> enterVodFullscreen());
        vodBtnAudio.setOnClickListener(v -> showAudioTracks());
        vodBtnSubs.setOnClickListener(v -> showSubtitleTracks());
        vodFsBtnExit.setOnClickListener(v -> exitVodFullscreen());
        vodFsBtnPip.setOnClickListener(v -> enterPip());
        vodFsBtnExt.setOnClickListener(v -> launchExternal());
        vodFsBtnUrl.setOnClickListener(v -> copyUrl());
        vodFsBtnSubs.setOnClickListener(v -> showSubtitleTracks());
        vodPlayerView.setOnClickListener(v -> { if (isVodFullscreen) toggleVodFsBars(); });
        fetchVodInfo();
    }

    private void navigateChannel(int direction) {
        if (channels.isEmpty() || channelIndex < 0) {
            Toast.makeText(this, "Sin lista de canales", Toast.LENGTH_SHORT).show();
            return;
        }
        int newIndex = channelIndex + direction;
        if (newIndex < 0) newIndex = channels.size() - 1;
        if (newIndex >= channels.size()) newIndex = 0;
        try {
            JSONObject ch = channels.get(newIndex);
            channelIndex = newIndex;
            url    = ch.optString("url", "");
            name   = ch.optString("name", "");
            itemId = ch.optString("id", "");
            logo   = ch.optString("logo", "");
            group  = ch.optString("group", "");
            txtChannelName.setText(name);
            retryCount = 0;
            // Mostrar nombre del canal brevemente
            topBar.setVisibility(View.VISIBLE);
            scheduleHideBars();
            initPlayer(playerView);
        } catch (Exception e) {
            Toast.makeText(this, "Error al cambiar canal", Toast.LENGTH_SHORT).show();
        }
    }

    private void enterVodFullscreen() {
        isVodFullscreen = true;
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        vodScrollView.setVisibility(View.GONE);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) vodPlayerView.getLayoutParams();
        lp.weight = 10;
        vodPlayerView.setLayoutParams(lp);
        vodPlayerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL);
        vodFsTopBar.setVisibility(View.VISIBLE);
        vodFsBottomBar.setVisibility(View.VISIBLE);
        scheduleHideVodFsBars();
    }

    private void exitVodFullscreen() {
        isVodFullscreen = false;
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        vodScrollView.setVisibility(View.VISIBLE);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) vodPlayerView.getLayoutParams();
        lp.weight = 4;
        vodPlayerView.setLayoutParams(lp);
        vodPlayerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        vodFsTopBar.setVisibility(View.GONE);
        vodFsBottomBar.setVisibility(View.GONE);
        hideHandler.removeCallbacksAndMessages(null);
    }

    private void toggleVodFsBars() {
        boolean visible = vodFsTopBar.getVisibility() == View.VISIBLE;
        vodFsTopBar.setVisibility(visible ? View.GONE : View.VISIBLE);
        vodFsBottomBar.setVisibility(visible ? View.GONE : View.VISIBLE);
        if (!visible) scheduleHideVodFsBars();
    }

    private void scheduleHideVodFsBars() {
        hideHandler.removeCallbacksAndMessages(null);
        hideHandler.postDelayed(() -> {
            vodFsTopBar.setVisibility(View.GONE);
            vodFsBottomBar.setVisibility(View.GONE);
        }, 4000);
    }

    private void showAudioTracks() {
        if (globalPlayer == null) return;
        Tracks tracks = globalPlayer.getCurrentTracks();
        List<String> labels = new ArrayList<>();
        List<String> langs = new ArrayList<>();
        for (Tracks.Group g : tracks.getGroups()) {
            if (g.getType() == C.TRACK_TYPE_AUDIO) {
                for (int i = 0; i < g.length; i++) {
                    String lang = g.getTrackFormat(i).language;
                    labels.add((lang != null && !lang.isEmpty()) ? lang.toUpperCase() : "Pista " + (labels.size() + 1));
                    langs.add(lang != null ? lang : "");
                }
            }
        }
        if (labels.isEmpty()) { Toast.makeText(this, "Sin pistas de audio", Toast.LENGTH_SHORT).show(); return; }
        new AlertDialog.Builder(this)
            .setTitle("🔊 Seleccionar audio")
            .setItems(labels.toArray(new String[0]), (d, which) ->
                globalPlayer.setTrackSelectionParameters(
                    globalPlayer.getTrackSelectionParameters().buildUpon()
                        .setPreferredAudioLanguage(langs.get(which)).build()
                )).show();
    }

    private void showSubtitleTracks() {
        if (globalPlayer == null) return;
        Tracks tracks = globalPlayer.getCurrentTracks();
        List<String> labels = new ArrayList<>();
        List<String> langs = new ArrayList<>();
        labels.add("Ninguno"); langs.add("");
        for (Tracks.Group g : tracks.getGroups()) {
            if (g.getType() == C.TRACK_TYPE_TEXT) {
                for (int i = 0; i < g.length; i++) {
                    String lang = g.getTrackFormat(i).language;
                    labels.add((lang != null && !lang.isEmpty()) ? lang.toUpperCase() : "Sub " + labels.size());
                    langs.add(lang != null ? lang : "");
                }
            }
        }
        if (labels.size() == 1) { Toast.makeText(this, "Sin subtítulos disponibles", Toast.LENGTH_SHORT).show(); return; }
        new AlertDialog.Builder(this)
            .setTitle("💬 Subtítulos")
            .setItems(labels.toArray(new String[0]), (d, which) -> {
                if (which == 0) {
                    globalPlayer.setTrackSelectionParameters(
                        globalPlayer.getTrackSelectionParameters().buildUpon()
                            .setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT).build());
                } else {
                    globalPlayer.setTrackSelectionParameters(
                        globalPlayer.getTrackSelectionParameters().buildUpon()
                            .setPreferredTextLanguage(langs.get(which)).build());
                }
            }).show();
    }

    private void fetchVodInfo() {
        new Thread(() -> {
            try {
                String[] parts = url.split("/");
                if (parts.length < 6) return;
                String host = parts[0] + "//" + parts[2];
                String user = parts[4];
                String pass = parts[5];
                String apiUrl = host + "/player_api.php?username=" + user +
                    "&password=" + pass + "&action=get_vod_info&vod_id=" + itemId;
                URL u = new URL(apiUrl);
                HttpURLConnection conn = (HttpURLConnection) u.openConnection();
                conn.setConnectTimeout(8000); conn.setReadTimeout(8000);
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                JSONObject json = new JSONObject(sb.toString());
                JSONObject info = json.optJSONObject("info");
                if (info == null) return;
                String plot     = info.optString("plot", "");
                String year     = info.optString("releasedate", info.optString("year", ""));
                String duration = info.optString("duration", "");
                String rating   = info.optString("rating", "");
                runOnUiThread(() -> {
                    vodTxtPlot.setText(!plot.isEmpty() ? plot : "Sin sinopsis disponible.");
                    if (!year.isEmpty()) { vodTxtYear.setText(year.length()>=4?year.substring(0,4):year); vodTxtYear.setVisibility(View.VISIBLE); }
                    if (!duration.isEmpty()) { vodTxtDuration.setText("⏱ "+duration); vodTxtDuration.setVisibility(View.VISIBLE); }
                    if (!rating.isEmpty() && !rating.equals("0")) { vodTxtRating.setText("⭐ "+rating); vodTxtRating.setVisibility(View.VISIBLE); }
                });
            } catch (Exception e) {
                runOnUiThread(() -> vodTxtPlot.setText("Sin información disponible."));
            }
        }).start();
    }

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

    private void releasePlayer() {
        if (globalPlayer != null) {
            globalPlayer.stop();
            globalPlayer.release();
            globalPlayer = null;
        }
    }

    private void initPlayer(PlayerView pv) {
        showLoading(true);
        releasePlayer();

        OkHttpDataSource.Factory dsf = new OkHttpDataSource.Factory(buildUnsafeClient());
        globalPlayer = new ExoPlayer.Builder(this)
            .setMediaSourceFactory(new DefaultMediaSourceFactory(dsf))
            .build();
        pv.setPlayer(globalPlayer);
        globalPlayer.setMediaItem(MediaItem.fromUri(url));
        globalPlayer.prepare();
        globalPlayer.play();

        globalPlayer.addListener(new Player.Listener() {
            @Override
            public void onTracksChanged(Tracks tracks) {
                boolean hasAudio = false, hasSubs = false;
                int audioCount = 0;
                for (Tracks.Group g : tracks.getGroups()) {
                    if (g.getType() == C.TRACK_TYPE_AUDIO) audioCount += g.length;
                    if (g.getType() == C.TRACK_TYPE_TEXT && g.length > 0) hasSubs = true;
                }
                hasAudio = audioCount > 1;
                boolean fa = hasAudio, fs = hasSubs;
                runOnUiThread(() -> {
                    boolean isVod = "vod".equals(type) || "series".equals(type);
                    if (isVod) {
                        vodBtnAudio.setVisibility(fa ? View.VISIBLE : View.GONE);
                        vodBtnSubs.setVisibility(fs ? View.VISIBLE : View.GONE);
                        vodFsBtnSubs.setVisibility(fs ? View.VISIBLE : View.GONE);
                    } else {
                        btnAudio.setVisibility(fa ? View.VISIBLE : View.GONE);
                        btnSubs.setVisibility(fs ? View.VISIBLE : View.GONE);
                    }
                });
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    showLoading(false);
                    if ("live".equals(type)) scheduleHideBars();
                } else if (state == Player.STATE_BUFFERING) {
                    showLoading(true);
                } else if ((state == Player.STATE_IDLE || state == Player.STATE_ENDED) && "live".equals(type)) {
                    if (retryCount < 3) {
                        retryCount++;
                        txtLoading.setText("Reconectando... (" + retryCount + "/3)");
                        showLoading(true);
                        hideHandler.postDelayed(() -> initPlayer(playerView), 3000);
                    } else showLoading(false);
                }
            }

            @Override
            public void onPlayerError(androidx.media3.common.PlaybackException error) {
                if ("live".equals(type) && retryCount < 3) {
                    retryCount++;
                    txtLoading.setText("Reconectando... (" + retryCount + "/3)");
                    showLoading(true);
                    hideHandler.postDelayed(() -> initPlayer(playerView), 3000);
                } else {
                    showLoading(false);
                    Toast.makeText(PlayerActivity.this, "❌ Error al reproducir", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void showLoading(boolean show) {
        loadingOverlay.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void toggleBars() {
        barsVisible = !barsVisible;
        topBar.setVisibility(barsVisible ? View.VISIBLE : View.GONE);
        bottomBar.setVisibility(barsVisible ? View.VISIBLE : View.GONE);
        if (barsVisible) scheduleHideBars();
    }

    private void scheduleHideBars() {
        hideHandler.removeCallbacksAndMessages(null);
        hideHandler.postDelayed(() -> {
            topBar.setVisibility(View.GONE);
            bottomBar.setVisibility(View.GONE);
            barsVisible = false;
        }, 4000);
    }

    private void toggleFav(ImageButton btn) {
        isFav = !isFav;
        favChanged = true; favAdded = isFav;
        btn.setImageResource(isFav ? android.R.drawable.btn_star_big_on : android.R.drawable.btn_star_big_off);
        Toast.makeText(this, isFav ? "⭐ Favorito guardado" : "Quitado de favoritos", Toast.LENGTH_SHORT).show();
    }

    private void enterPip() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            enterPictureInPictureMode(new PictureInPictureParams.Builder()
                .setAspectRatio(new Rational(16, 9)).build());
        }
    }

    private void launchExternal() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(android.net.Uri.parse(url), "video/*");
            intent.setPackage("org.videolan.vlc");
            startActivity(intent);
        } catch (Exception e) { copyUrl(); }
    }

    private void copyUrl() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("url", url));
        Toast.makeText(this, "📋 URL copiada", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPiP) {
        super.onPictureInPictureModeChanged(isInPiP);
        topBar.setVisibility(View.GONE);
        bottomBar.setVisibility(View.GONE);
        vodFsTopBar.setVisibility(View.GONE);
        vodFsBottomBar.setVisibility(View.GONE);
        boolean isVod = "vod".equals(type) || "series".equals(type);
        if (isVod) {
            LinearLayout vodTopBar = findViewById(R.id.vod_top_bar);
            vodTopBar.setVisibility(isInPiP ? View.GONE : View.VISIBLE);
            vodScrollView.setVisibility(isInPiP ? View.GONE : (isVodFullscreen ? View.GONE : View.VISIBLE));
            if (globalPlayer != null) vodPlayerView.setUseController(!isInPiP);
        } else {
            if (globalPlayer != null) playerView.setUseController(!isInPiP);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        hideHandler.removeCallbacksAndMessages(null);
        if (currentInstance == this) {
            releasePlayer();
            currentInstance = null;
        }
        Intent result = new Intent();
        result.putExtra("fav_added", favChanged && favAdded);
        result.putExtra("fav_removed", favChanged && !favAdded);
        result.putExtra("item_id", itemId);
        result.putExtra("item_type", type);
        setResult(RESULT_OK, result);
    }

    @Override
    public void onBackPressed() {
        if (isVodFullscreen) exitVodFullscreen();
        else super.onBackPressed();
    }
}
