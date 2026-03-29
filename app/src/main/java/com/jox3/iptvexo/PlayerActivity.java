package com.jox3.iptvexo;

import android.annotation.SuppressLint;
import android.app.PictureInPictureParams;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.Rational;
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
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import okhttp3.OkHttpClient;

@UnstableApi
public class PlayerActivity extends AppCompatActivity {

    private ExoPlayer player;

    // LIVE views
    private PlayerView playerView;
    private LinearLayout topBar, bottomBar, loadingOverlay;
    private TextView txtChannelName, txtStatus, txtEpg, txtLoading;
    private ImageButton btnBack, btnFav;
    private Button btnPip, btnExternal, btnStop;
    private ProgressBar progress;

    // VOD views
    private LinearLayout vodLayout;
    private PlayerView vodPlayerView;
    private ImageButton vodBtnBack, vodBtnFav;
    private Button vodBtnPip, vodBtnExternal, vodBtnCopy, vodBtnStop, vodBtnFullscreen;
    private TextView vodTxtTitle, vodTxtFullTitle, vodTxtYear, vodTxtDuration, vodTxtRating, vodTxtPlot;
    private ScrollView vodScrollView;

    // VOD fullscreen overlay views
    private LinearLayout vodFsTopBar, vodFsBottomBar;
    private TextView vodFsTitle;
    private Button vodFsBtnExit, vodFsBtnPip, vodFsBtnExt;

    private String url, name, group, type, logo, itemId;
    private boolean isFav = false;
    private boolean barsVisible = true;
    private boolean favChanged = false;
    private boolean favAdded = false;
    private boolean isVodFullscreen = false;
    private int retryCount = 0;
    private Handler hideHandler = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
        setContentView(R.layout.activity_player);

        url    = getIntent().getStringExtra("url");
        name   = getIntent().getStringExtra("name");
        group  = getIntent().getStringExtra("group");
        type   = getIntent().getStringExtra("type");
        logo   = getIntent().getStringExtra("logo");
        itemId = getIntent().getStringExtra("id");

        // LIVE views
        playerView     = findViewById(R.id.player_view);
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

        // VOD views
        vodLayout        = findViewById(R.id.vod_layout);
        vodPlayerView    = findViewById(R.id.vod_player_view);
        vodBtnBack       = findViewById(R.id.vod_btn_back);
        vodBtnFav        = findViewById(R.id.vod_btn_fav);
        vodBtnPip        = findViewById(R.id.vod_btn_pip);
        vodBtnExternal   = findViewById(R.id.vod_btn_external);
        vodBtnCopy       = findViewById(R.id.vod_btn_copy);
        vodBtnStop       = findViewById(R.id.vod_btn_stop);
        vodBtnFullscreen = findViewById(R.id.vod_btn_fullscreen);
        vodTxtTitle      = findViewById(R.id.vod_txt_title);
        vodTxtFullTitle  = findViewById(R.id.vod_txt_full_title);
        vodTxtYear       = findViewById(R.id.vod_txt_year);
        vodTxtDuration   = findViewById(R.id.vod_txt_duration);
        vodTxtRating     = findViewById(R.id.vod_txt_rating);
        vodTxtPlot       = findViewById(R.id.vod_txt_plot);
        vodScrollView    = findViewById(R.id.vod_scroll);

        // VOD fullscreen overlay
        vodFsTopBar    = findViewById(R.id.vod_fs_top_bar);
        vodFsBottomBar = findViewById(R.id.vod_fs_bottom_bar);
        vodFsTitle     = findViewById(R.id.vod_fs_title);
        vodFsBtnExit   = findViewById(R.id.vod_fs_btn_exit);
        vodFsBtnPip    = findViewById(R.id.vod_fs_btn_pip);
        vodFsBtnExt    = findViewById(R.id.vod_fs_btn_ext);

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
        playerView.setOnClickListener(v -> toggleBars());
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

        vodBtnBack.setOnClickListener(v -> finish());
        vodBtnStop.setOnClickListener(v -> finish());
        vodBtnFav.setOnClickListener(v -> toggleFav(vodBtnFav));
        vodBtnPip.setOnClickListener(v -> enterPip());
        vodBtnExternal.setOnClickListener(v -> launchExternal());
        vodBtnCopy.setOnClickListener(v -> copyUrl());
        vodBtnFullscreen.setOnClickListener(v -> enterVodFullscreen());

        // Fullscreen overlay buttons
        vodFsBtnExit.setOnClickListener(v -> exitVodFullscreen());
        vodFsBtnPip.setOnClickListener(v -> enterPip());
        vodFsBtnExt.setOnClickListener(v -> launchExternal());

        // Tap video to show/hide fullscreen controls
        vodPlayerView.setOnClickListener(v -> {
            if (isVodFullscreen) toggleVodFsBars();
        });

        fetchVodInfo();
    }

    private void enterVodFullscreen() {
        isVodFullscreen = true;
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        vodScrollView.setVisibility(View.GONE);
        vodBtnBack.setVisibility(View.GONE);
        vodBtnFav.setVisibility(View.GONE);

        // Expandir PlayerView a toda la pantalla
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) vodPlayerView.getLayoutParams();
        lp.weight = 10;
        vodPlayerView.setLayoutParams(lp);
        vodPlayerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL);

        // Mostrar overlay de fullscreen
        vodFsTopBar.setVisibility(View.VISIBLE);
        vodFsBottomBar.setVisibility(View.VISIBLE);
        scheduleHideVodFsBars();
    }

    private void exitVodFullscreen() {
        isVodFullscreen = false;
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        vodScrollView.setVisibility(View.VISIBLE);
        vodBtnBack.setVisibility(View.VISIBLE);
        vodBtnFav.setVisibility(View.VISIBLE);

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
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
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
        } catch (Exception e) {
            return new OkHttpClient.Builder().build();
        }
    }

    private void initPlayer(PlayerView pv) {
        showLoading(true);
        if (player != null) { player.release(); player = null; }
        OkHttpDataSource.Factory dsf = new OkHttpDataSource.Factory(buildUnsafeClient());
        player = new ExoPlayer.Builder(this)
            .setMediaSourceFactory(new DefaultMediaSourceFactory(dsf))
            .build();
        pv.setPlayer(player);
        player.setMediaItem(MediaItem.fromUri(url));
        player.prepare();
        player.play();
        player.addListener(new Player.Listener() {
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
        favChanged = true;
        favAdded = isFav;
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
        // En PiP: ocultar TODO — solo video puro
        int vis = isInPiP ? View.GONE : View.VISIBLE;
        topBar.setVisibility(View.GONE);
        bottomBar.setVisibility(View.GONE);
        vodFsTopBar.setVisibility(View.GONE);
        vodFsBottomBar.setVisibility(View.GONE);
        if ("vod".equals(type) || "series".equals(type)) {
            LinearLayout vodTopBar = findViewById(R.id.vod_top_bar);
            vodTopBar.setVisibility(isInPiP ? View.GONE : View.VISIBLE);
            vodScrollView.setVisibility(isInPiP ? View.GONE : (isVodFullscreen ? View.GONE : View.VISIBLE));
        }
        PlayerView pv = "live".equals(type) ? playerView : vodPlayerView;
        if (player != null) pv.setUseController(!isInPiP);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Solo liberar si NO es PiP y la activity se está terminando
        if (!isInPictureInPictureMode() && isFinishing()) {
            if (player != null) { player.stop(); player.release(); player = null; }
        } else if (!isInPictureInPictureMode()) {
            // Pausar cuando va a background (ej: home button sin PiP)
            if (player != null) player.pause();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Reanudar si vuelve de background
        if (player != null) player.play();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        hideHandler.removeCallbacksAndMessages(null);
        if (player != null) { player.stop(); player.release(); player = null; }
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

