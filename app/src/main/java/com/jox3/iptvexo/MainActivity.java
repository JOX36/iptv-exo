package com.jox3.iptvexo;

import android.annotation.SuppressLint;
import android.app.PictureInPictureParams;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.Rational;
import android.view.View;
import android.view.WindowManager;
import android.webkit.SslErrorHandler;
import android.widget.ImageButton;
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
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import okhttp3.OkHttpClient;
import android.widget.Button;

@UnstableApi
public class PlayerActivity extends AppCompatActivity {

    private ExoPlayer player;
    private PlayerView playerView;
    private LinearLayout topBar, bottomBar, loadingOverlay;
    private TextView txtChannelName, txtStatus, txtEpg, txtLoading;
    private ImageButton btnBack, btnFav;
    private Button btnPip, btnExternal, btnStop;
    private ProgressBar progress;

    private String url, name, group, type, logo, itemId;
    private boolean isFav = false;
    private boolean barsVisible = true;
    private Handler hideHandler = new Handler();
    private int retryCount = 0;

    // Resultado para devolver a MainActivity
    private boolean favChanged = false;
    private boolean favAdded = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Fullscreen inmersivo
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );

        setContentView(R.layout.activity_player);

        // Obtener datos del intent
        url     = getIntent().getStringExtra("url");
        name    = getIntent().getStringExtra("name");
        group   = getIntent().getStringExtra("group");
        type    = getIntent().getStringExtra("type");
        logo    = getIntent().getStringExtra("logo");
        itemId  = getIntent().getStringExtra("id");

        // Orientación según tipo
        if ("live".equals(type)) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        }

        // Vistas
        playerView      = findViewById(R.id.player_view);
        topBar          = findViewById(R.id.top_bar);
        bottomBar       = findViewById(R.id.bottom_bar);
        loadingOverlay  = findViewById(R.id.loading_overlay);
        txtChannelName  = findViewById(R.id.txt_channel_name);
        txtStatus       = findViewById(R.id.txt_status);
        txtEpg          = findViewById(R.id.txt_epg);
        txtLoading      = findViewById(R.id.txt_loading);
        progress        = findViewById(R.id.progress);
        btnBack         = findViewById(R.id.btn_back);
        btnFav          = findViewById(R.id.btn_fav);
        btnPip          = findViewById(R.id.btn_pip);
        btnExternal     = findViewById(R.id.btn_external);
        btnStop         = findViewById(R.id.btn_stop);

        // Info
        txtChannelName.setText(name);
        txtStatus.setText("live".equals(type) ? "🔴 EN VIVO" : "▶ VOD");

        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL);
        playerView.setUseController(true);

        // Botones
        btnBack.setOnClickListener(v -> finish());
        btnStop.setOnClickListener(v -> finish());
        btnFav.setOnClickListener(v -> toggleFav());
        btnPip.setOnClickListener(v -> enterPip());
        btnExternal.setOnClickListener(v -> launchExternal());

        // Tap para mostrar/ocultar barras
        playerView.setOnClickListener(v -> toggleBars());

        // Iniciar reproducción
        initPlayer();
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

    private void initPlayer() {
        showLoading(true);
        if (player != null) { player.release(); player = null; }

        OkHttpDataSource.Factory dsf = new OkHttpDataSource.Factory(buildUnsafeClient());
        player = new ExoPlayer.Builder(this)
            .setMediaSourceFactory(new DefaultMediaSourceFactory(dsf))
            .build();
        playerView.setPlayer(player);
        player.setMediaItem(MediaItem.fromUri(url));
        player.prepare();
        player.play();

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    showLoading(false);
                    scheduleHideBars();
                } else if (state == Player.STATE_BUFFERING) {
                    showLoading(true);
                } else if (state == Player.STATE_IDLE || state == Player.STATE_ENDED) {
                    if ("live".equals(type) && retryCount < 3) {
                        retryCount++;
                        txtLoading.setText("Reconectando... (" + retryCount + "/3)");
                        showLoading(true);
                        hideHandler.postDelayed(() -> initPlayer(), 3000);
                    } else {
                        showLoading(false);
                    }
                }
            }

            @Override
            public void onPlayerError(androidx.media3.common.PlaybackException error) {
                if ("live".equals(type) && retryCount < 3) {
                    retryCount++;
                    txtLoading.setText("Reconectando... (" + retryCount + "/3)");
                    showLoading(true);
                    hideHandler.postDelayed(() -> initPlayer(), 3000);
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

    private void toggleFav() {
        isFav = !isFav;
        favChanged = true;
        favAdded = isFav;
        btnFav.setImageResource(isFav ?
            android.R.drawable.btn_star_big_on :
            android.R.drawable.btn_star_big_off);
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
        } catch (Exception e) {
            // VLC no instalado — copiar URL
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("url", url));
            Toast.makeText(this, "📋 URL copiada", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPiP) {
        super.onPictureInPictureModeChanged(isInPiP);
        topBar.setVisibility(isInPiP ? View.GONE : View.VISIBLE);
        bottomBar.setVisibility(isInPiP ? View.GONE : View.VISIBLE);
        playerView.setUseController(!isInPiP);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (!isInPictureInPictureMode() && player != null) player.pause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (player != null) player.play();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        hideHandler.removeCallbacksAndMessages(null);
        if (player != null) { player.release(); player = null; }
        // Devolver resultado de favoritos a MainActivity
        Intent result = new Intent();
        result.putExtra("fav_added", favChanged && favAdded);
        result.putExtra("fav_removed", favChanged && !favAdded);
        result.putExtra("item_id", itemId);
        result.putExtra("item_type", type);
        setResult(RESULT_OK, result);
    }
}
