package com.jox3.iptvexo;

import android.app.PictureInPictureParams;
import android.content.pm.ActivityInfo;
import android.os.Build;
import android.os.Bundle;
import android.util.Rational;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private ExoPlayer player;
    private PlayerView playerView;
    private boolean isPlaying = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Pantalla completa edge-to-edge
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        );

        setContentView(R.layout.activity_main);

        playerView = findViewById(R.id.player_view);
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL);
        webView = findViewById(R.id.webview);

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        ws.setMediaPlaybackRequiresUserGesture(false);

        webView.addJavascriptInterface(new PlayerBridge(), "AndroidPlayer");
        webView.setWebViewClient(new WebViewClient());
        webView.loadUrl("file:///android_asset/player.html");
    }

    private void initPlayer(String url) {
        if (player != null) {
            player.release();
            player = null;
        }

        isPlaying = true;

        // Mantener pantalla encendida
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Landscape + fullscreen
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );

        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        playerView.setVisibility(View.VISIBLE);
        webView.setVisibility(View.GONE);

        MediaItem mediaItem = MediaItem.fromUri(url);
        player.setMediaItem(mediaItem);
        player.prepare();
        player.play();

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED || state == Player.STATE_IDLE) {
                    runOnUiThread(() -> stopPlayer());
                }
            }
        });
    }

    private void stopPlayer() {
        isPlaying = false;
        if (player != null) {
            player.release();
            player = null;
        }
        // Apagar keep screen on
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Volver a portrait
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);

        playerView.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
    }

    // PiP al presionar home
    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (isPlaying && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PictureInPictureParams params = new PictureInPictureParams.Builder()
                .setAspectRatio(new Rational(16, 9))
                .build();
            enterPictureInPictureMode(params);
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPiP) {
        super.onPictureInPictureModeChanged(isInPiP);
        playerView.setUseController(!isInPiP);
    }

    class PlayerBridge {
        @JavascriptInterface
        public void playUrl(String url) {
            runOnUiThread(() -> initPlayer(url));
        }

        @JavascriptInterface
        public void stop() {
            runOnUiThread(() -> stopPlayer());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (player != null) player.release();
    }

    @Override
    public void onBackPressed() {
        if (playerView.getVisibility() == View.VISIBLE) {
            stopPlayer();
        } else if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
