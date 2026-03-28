package com.jox3.iptvexo;

import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.View;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private ExoPlayer player;
    private PlayerView playerView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        playerView = findViewById(R.id.player_view);
        webView = findViewById(R.id.webview);

        // Configurar WebView
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        ws.setMediaPlaybackRequiresUserGesture(false);

        // Puente JS → Java
        webView.addJavascriptInterface(new PlayerBridge(), "AndroidPlayer");
        webView.setWebViewClient(new WebViewClient());

        // Cargar el player.html local
        webView.loadUrl("file:///android_asset/player.html");
    }

    // ExoPlayer
    private void initPlayer(String url) {
        if (player != null) {
            player.release();
            player = null;
        }
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
        if (player != null) {
            player.release();
            player = null;
        }
        playerView.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
    }

    // Interfaz JavaScript
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
    }
}
