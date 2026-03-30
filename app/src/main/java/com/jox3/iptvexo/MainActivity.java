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

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    // Lista de canales actuales para navegación en PlayerActivity
    private String currentChannelsJson = "[]";
    private int currentChannelIndex = -1;

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

        webView.addJavascriptInterface(new CatalogBridge(), "AndroidPlayer");
        webView.loadUrl("file:///android_asset/player.html");
    }

    class CatalogBridge {

        @JavascriptInterface
        public void playUrl(String url) {}

        @JavascriptInterface
        public void openPlayer(String url, String name, String group, String type,
                               String logo, String itemId, String channelsJson, int channelIndex) {
            currentChannelsJson = channelsJson != null ? channelsJson : "[]";
            currentChannelIndex = channelIndex;

            Intent intent = new Intent(MainActivity.this, PlayerActivity.class);
            // FLAG_ACTIVITY_SINGLE_TOP evita apilar múltiples PlayerActivity
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            intent.putExtra("url", url);
            intent.putExtra("name", name);
            intent.putExtra("group", group);
            intent.putExtra("type", type);
            intent.putExtra("logo", logo);
            intent.putExtra("id", itemId);
            intent.putExtra("channel_index", channelIndex);
            intent.putExtra("channels_json", currentChannelsJson);
            startActivityForResult(intent, 1001);
        }

        // Compatibilidad con llamadas sin lista de canales
        @JavascriptInterface
        public void openPlayerSimple(String url, String name, String group, String type, String logo, String itemId) {
            openPlayer(url, name, group, type, logo, itemId, "[]", -1);
        }

        @JavascriptInterface
        public void stop() {}

        @JavascriptInterface
        public void goFullscreen() {}
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1001 && data != null) {
            boolean favAdded   = data.getBooleanExtra("fav_added", false);
            boolean favRemoved = data.getBooleanExtra("fav_removed", false);
            String itemId      = data.getStringExtra("item_id");
            String itemType    = data.getStringExtra("item_type");
            if (favAdded && itemId != null) {
                webView.evaluateJavascript(
                    "S.favs.add('" + itemType + "_" + itemId + "');saveFavs();renderItems(S.channels,'')", null
                );
            } else if (favRemoved && itemId != null) {
                webView.evaluateJavascript(
                    "S.favs.delete('" + itemType + "_" + itemId + "');saveFavs();renderItems(S.channels,'')", null
                );
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
