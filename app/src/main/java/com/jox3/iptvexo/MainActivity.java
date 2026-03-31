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

        webView.addJavascriptInterface(new Bridge(), "AndroidPlayer");
        webView.loadUrl("file:///android_asset/player.html");
    }

    class Bridge {
        @JavascriptInterface
        public void openPlayer(String url, String name, String group, String type,
                               String logo, String itemId, String channelsJson, int channelIndex) {
            Intent i = new Intent(MainActivity.this, PlayerActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            i.putExtra("url", url);
            i.putExtra("name", name);
            i.putExtra("group", group);
            i.putExtra("type", type);
            i.putExtra("logo", logo);
            i.putExtra("id", itemId);
            i.putExtra("channel_index", channelIndex);
            i.putExtra("channels_json", channelsJson != null ? channelsJson : "[]");
            startActivityForResult(i, 1001);
        }

        @JavascriptInterface public void playUrl(String url) {}
        @JavascriptInterface public void stop() {}
        @JavascriptInterface public void goFullscreen() {}
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == 1001 && data != null) {
            boolean added   = data.getBooleanExtra("fav_added", false);
            boolean removed = data.getBooleanExtra("fav_removed", false);
            String id   = data.getStringExtra("item_id");
            String type = data.getStringExtra("item_type");
            if (id != null) {
                String key = type + "_" + id;
                if (added)   webView.evaluateJavascript("S.favs.add('"   + key + "');saveFavs();", null);
                if (removed) webView.evaluateJavascript("S.favs.delete('"+ key + "');saveFavs();", null);
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
