package com.jox3.iptvexo;

import android.annotation.SuppressLint;
import android.app.PictureInPictureParams;
import android.content.pm.ActivityInfo;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.util.Rational;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.graphics.Color;
import android.view.Gravity;
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

@UnstableApi
public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private ExoPlayer player;
    private PlayerView playerView;
    private TextView epgOverlay;
    private boolean isPlaying = false;
    private boolean isFullscreen = false;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        );
        setContentView(R.layout.activity_main);

        playerView = findViewById(R.id.player_view);
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        playerView.setVisibility(View.GONE);

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
        webView.addJavascriptInterface(new PlayerBridge(), "AndroidPlayer");
        webView.loadUrl("file:///android_asset/player.html");

        epgOverlay = new TextView(this);
        epgOverlay.setTextColor(Color.WHITE);
        epgOverlay.setTextSize(14);
        epgOverlay.setPadding(24, 12, 24, 12);
        epgOverlay.setBackgroundColor(Color.argb(160, 0, 0, 0));
        epgOverlay.setVisibility(View.GONE);
        FrameLayout.LayoutParams epgParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        epgParams.gravity = Gravity.BOTTOM;
        addContentView(epgOverlay, epgParams);
    }

    @SuppressLint("TrustAllX509TrustManager")
    private OkHttpClient buildUnsafeOkHttpClient() {
        try {
            X509TrustManager trustAll = new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) throws CertificateException {}
                public void checkServerTrusted(X509Certificate[] c, String a) throws CertificateException {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            };
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{trustAll}, new java.security.SecureRandom());
            return new OkHttpClient.Builder()
                .sslSocketFactory(sslContext.getSocketFactory(), trustAll)
                .hostnameVerifier((h, s) -> true)
                .build();
        } catch (Exception e) {
            return new OkHttpClient.Builder().build();
        }
    }

    private void initPlayer(String url) {
        if (player != null) { player.release(); player = null; }
        isPlaying = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // PlayerView encima del WebView — tamaño fijo inline
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        playerView.setBackgroundColor(Color.BLACK);
        RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(280)
        );
        params.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        playerView.setLayoutParams(params);
        playerView.setVisibility(View.VISIBLE);

        OkHttpDataSource.Factory dsf = new OkHttpDataSource.Factory(buildUnsafeOkHttpClient());
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
                if (state == Player.STATE_ENDED || state == Player.STATE_IDLE)
                    runOnUiThread(() -> stopPlayer());
            }
        });

        // Desplazar WebView para que empiece debajo del player
        webView.post(() -> {
            ViewGroup.MarginLayoutParams wvp = (ViewGroup.MarginLayoutParams) webView.getLayoutParams();
            wvp.topMargin = dpToPx(280);
            webView.setLayoutParams(wvp);
        });
    }

    private void enterFullscreen() {
        if (!isPlaying) return;
        isFullscreen = true;
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        // PlayerView ocupa toda la pantalla
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        );
        playerView.setLayoutParams(params);
        webView.setVisibility(View.GONE);
        epgOverlay.setVisibility(View.VISIBLE);
    }

    private void exitFullscreen() {
        isFullscreen = false;
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        // Volver a tamaño inline
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(280)
        );
        params.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        playerView.setLayoutParams(params);
        webView.setVisibility(View.VISIBLE);
        epgOverlay.setVisibility(View.GONE);
    }

    private void stopPlayer() {
        isPlaying = false;
        isFullscreen = false;
        if (player != null) { player.release(); player = null; }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        playerView.setVisibility(View.GONE);
        epgOverlay.setVisibility(View.GONE);
        // Resetear margen del WebView
        webView.post(() -> {
            ViewGroup.MarginLayoutParams wvp = (ViewGroup.MarginLayoutParams) webView.getLayoutParams();
            wvp.topMargin = 0;
            webView.setLayoutParams(wvp);
        });
        webView.setVisibility(View.VISIBLE);
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (isPlaying && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            enterPictureInPictureMode(new PictureInPictureParams.Builder()
                .setAspectRatio(new Rational(16, 9)).build());
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPiP) {
        super.onPictureInPictureModeChanged(isInPiP);
        playerView.setUseController(!isInPiP);
        epgOverlay.setVisibility(isInPiP ? View.GONE : (isFullscreen ? View.VISIBLE : View.GONE));
        if (!isInPiP && isFullscreen) exitFullscreen();
    }

    class PlayerBridge {
        @JavascriptInterface
        public void playUrl(String url) { runOnUiThread(() -> initPlayer(url)); }

        @JavascriptInterface
        public void stop() { runOnUiThread(() -> stopPlayer()); }

        @JavascriptInterface
        public void goFullscreen() {
            runOnUiThread(() -> { if (isFullscreen) exitFullscreen(); else enterFullscreen(); });
        }

        @JavascriptInterface
        public void updateEpg(String text) {
            runOnUiThread(() -> {
                if (isFullscreen && text != null && !text.isEmpty()) {
                    epgOverlay.setText(text);
                    epgOverlay.setVisibility(View.VISIBLE);
                }
            });
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (player != null) player.release();
    }

    @Override
    public void onBackPressed() {
        if (isFullscreen) exitFullscreen();
        else if (isPlaying) { stopPlayer(); webView.evaluateJavascript("stopPlayer()", null); }
        else if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
