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

    // ── Player global: se detiene siempre antes de crear uno nuevo ──
    private ExoPlayer player;
    private static PlayerActivity activeInstance = null;

    // LIVE
    private PlayerView playerView;
    private LinearLayout liveTopBar, liveBottomBar, loadingOverlay;
    private TextView liveTxtName, liveTxtStatus, txtLoading;
    private ImageButton liveBtnBack, liveBtnFav;
    private Button liveBtnAudio, liveBtnSubs, liveBtnPip, liveBtnExt, liveBtnStop;
    private ProgressBar progressBar;

    // VOD
    private LinearLayout vodLayout;
    private PlayerView vodPlayerView;
    private LinearLayout vodTopBar;
    private ImageButton vodBtnBack, vodBtnFav;
    private TextView vodTxtTitleBar, vodTxtTitle, vodTxtYear, vodTxtDuration, vodTxtRating, vodTxtPlot;
    private ScrollView vodScroll;
    private Button vodBtnFullscreen, vodBtnPip, vodBtnExt, vodBtnCopy, vodBtnStop, vodBtnAudio, vodBtnSubs;

    // VOD fullscreen overlay
    private LinearLayout vodFsTop, vodFsBottom;
    private TextView vodFsTxtTitle;
    private Button vodFsBtnExit, vodFsBtnPip, vodFsBtnExt, vodFsBtnUrl, vodFsBtnSubs;

    // Datos
    private String url, name, group, type, logo, itemId;
    private List<JSONObject> channels = new ArrayList<>();
    private int channelIndex = -1;

    // Estado
    private boolean isFav = false;
    private boolean favChanged = false;
    private boolean favAdded = false;
    private boolean isVodFullscreen = false;
    private boolean liveBarsVisible = false;
    private int retryCount = 0;
    private final Handler handler = new Handler();
    private GestureDetector gestureDetector;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        // Detener instancia anterior si existe — fix audio doble
        if (activeInstance != null && activeInstance != this) {
            activeInstance.stopAndRelease();
        }
        activeInstance = this;

        setContentView(R.layout.activity_player);

        // Datos del intent
        url          = getIntent().getStringExtra("url");
        name         = getIntent().getStringExtra("name");
        group        = getIntent().getStringExtra("group");
        type         = getIntent().getStringExtra("type");
        logo         = getIntent().getStringExtra("logo");
        itemId       = getIntent().getStringExtra("id");
        channelIndex = getIntent().getIntExtra("channel_index", -1);
        parseChannels(getIntent().getStringExtra("channels_json"));

        bindViews();
        setEmojiLabels();

        if (isVodType()) setupVod();
        else setupLive();

        initPlayer();
    }

    private void parseChannels(String json) {
        if (json == null || json.isEmpty()) return;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) channels.add(arr.getJSONObject(i));
        } catch (Exception ignored) {}
    }

    private boolean isVodType() {
        return "vod".equals(type) || "series".equals(type);
    }

    private void bindViews() {
        // LIVE
        playerView    = findViewById(R.id.player_view);
        liveTopBar    = findViewById(R.id.live_top_bar);
        liveBottomBar = findViewById(R.id.live_bottom_bar);
        loadingOverlay= findViewById(R.id.loading_overlay);
        liveTxtName   = findViewById(R.id.live_txt_name);
        liveTxtStatus = findViewById(R.id.live_txt_status);
        txtLoading    = findViewById(R.id.txt_loading);
        progressBar   = findViewById(R.id.progress_bar);
        liveBtnBack   = findViewById(R.id.live_btn_back);
        liveBtnFav    = findViewById(R.id.live_btn_fav);
        liveBtnAudio  = findViewById(R.id.live_btn_audio);
        liveBtnSubs   = findViewById(R.id.live_btn_subs);
        liveBtnPip    = findViewById(R.id.live_btn_pip);
        liveBtnExt    = findViewById(R.id.live_btn_ext);
        liveBtnStop   = findViewById(R.id.live_btn_stop);

        // VOD
        vodLayout      = findViewById(R.id.vod_layout);
        vodPlayerView  = findViewById(R.id.vod_player_view);
        vodTopBar      = findViewById(R.id.vod_top_bar);
        vodBtnBack     = findViewById(R.id.vod_btn_back);
        vodBtnFav      = findViewById(R.id.vod_btn_fav);
        vodTxtTitleBar = findViewById(R.id.vod_txt_title_bar);
        vodTxtTitle    = findViewById(R.id.vod_txt_title);
        vodTxtYear     = findViewById(R.id.vod_txt_year);
        vodTxtDuration = findViewById(R.id.vod_txt_duration);
        vodTxtRating   = findViewById(R.id.vod_txt_rating);
        vodTxtPlot     = findViewById(R.id.vod_txt_plot);
        vodScroll      = findViewById(R.id.vod_scroll);
        vodBtnFullscreen = findViewById(R.id.vod_btn_fullscreen);
        vodBtnPip      = findViewById(R.id.vod_btn_pip);
        vodBtnExt      = findViewById(R.id.vod_btn_ext);
        vodBtnCopy     = findViewById(R.id.vod_btn_copy);
        vodBtnStop     = findViewById(R.id.vod_btn_stop);
        vodBtnAudio    = findViewById(R.id.vod_btn_audio);
        vodBtnSubs     = findViewById(R.id.vod_btn_subs);

        // VOD fullscreen
        vodFsTop      = findViewById(R.id.vod_fs_top);
        vodFsBottom   = findViewById(R.id.vod_fs_bottom);
        vodFsTxtTitle = findViewById(R.id.vod_fs_txt_title);
        vodFsBtnExit  = findViewById(R.id.vod_fs_btn_exit);
        vodFsBtnPip   = findViewById(R.id.vod_fs_btn_pip);
        vodFsBtnExt   = findViewById(R.id.vod_fs_btn_ext);
        vodFsBtnUrl   = findViewById(R.id.vod_fs_btn_url);
        vodFsBtnSubs  = findViewById(R.id.vod_fs_btn_subs);
    }

    // Emojis puestos desde Java para evitar corrupcion UTF-8 en XML
    private void setEmojiLabels() {
        liveBtnAudio.setText("\uD83D\uDD0A Audio");
        liveBtnSubs.setText("\uD83D\uDCAC Subs");
        liveBtnExt.setText("\uD83D\uDCF2 Externo");
        liveBtnStop.setText("\u23F9 Detener");
        vodBtnFullscreen.setText("\u26F6 Pantalla completa");
        vodBtnExt.setText("\uD83D\uDCF2 Externo");
        vodBtnCopy.setText("\uD83D\uDCCB URL");
        vodBtnStop.setText("\u23F9 Detener");
        vodBtnAudio.setText("\uD83D\uDD0A Audio");
        vodBtnSubs.setText("\uD83D\uDCAC Subtitulos");
        vodFsBtnExit.setText("\u2715 Salir");
        vodFsBtnExt.setText("\uD83D\uDCF2 Externo");
        vodFsBtnUrl.setText("\uD83D\uDCCB URL");
        vodFsBtnSubs.setText("\uD83D\uDCAC Subs");
    }

    // ══ SETUP LIVE ══
    private void setupLive() {
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL);
        vodLayout.setVisibility(View.GONE);
        liveTxtName.setText(name);
        liveTxtStatus.setText("\u25CF EN VIVO");
        liveBtnAudio.setVisibility(View.GONE);
        liveBtnSubs.setVisibility(View.GONE);

        liveBtnBack.setOnClickListener(v -> { stopAndRelease(); finish(); });
        liveBtnStop.setOnClickListener(v -> { stopAndRelease(); finish(); });
        liveBtnFav.setOnClickListener(v -> toggleFav(liveBtnFav));
        liveBtnPip.setOnClickListener(v -> enterPip());
        liveBtnExt.setOnClickListener(v -> launchExternal());
        liveBtnAudio.setOnClickListener(v -> showAudioTracks());
        liveBtnSubs.setOnClickListener(v -> showSubtitleTracks());

        // Swipe para canal siguiente/anterior + tap para barras
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vX, float vY) {
                if (e1 == null || e2 == null) return false;
                float diff = e2.getX() - e1.getX();
                if (Math.abs(diff) > 80 && Math.abs(vX) > 80) {
                    navigateChannel(diff < 0 ? 1 : -1);
                    return true;
                }
                return false;
            }
            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                toggleLiveBars();
                return true;
            }
        });
        playerView.setOnTouchListener((v, e) -> { gestureDetector.onTouchEvent(e); return true; });
    }

    // ══ SETUP VOD ══
    private void setupVod() {
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        vodLayout.setVisibility(View.VISIBLE);
        playerView.setVisibility(View.GONE);
        liveTopBar.setVisibility(View.GONE);
        liveBottomBar.setVisibility(View.GONE);
        vodFsTop.setVisibility(View.GONE);
        vodFsBottom.setVisibility(View.GONE);
        vodPlayerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);

        vodTxtTitleBar.setText(name);
        vodTxtTitle.setText(name);
        vodFsTxtTitle.setText(name);
        vodTxtPlot.setText("Cargando informacion...");
        vodBtnAudio.setVisibility(View.GONE);
        vodBtnSubs.setVisibility(View.GONE);
        vodFsBtnSubs.setVisibility(View.GONE);

        vodBtnBack.setOnClickListener(v -> { stopAndRelease(); finish(); });
        vodBtnStop.setOnClickListener(v -> { stopAndRelease(); finish(); });
        vodBtnFav.setOnClickListener(v -> toggleFav(vodBtnFav));
        vodBtnPip.setOnClickListener(v -> enterPip());
        vodBtnExt.setOnClickListener(v -> launchExternal());
        vodBtnCopy.setOnClickListener(v -> copyUrl());
        vodBtnFullscreen.setOnClickListener(v -> enterVodFullscreen());
        vodBtnAudio.setOnClickListener(v -> showAudioTracks());
        vodBtnSubs.setOnClickListener(v -> showSubtitleTracks());
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

    // ══ PLAYER ══
    private void initPlayer() {
        // Detener siempre antes de crear nuevo — fix audio doble
        stopAndRelease();
        showLoading(true);

        PlayerView pv = isVodType() ? vodPlayerView : playerView;
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
            public void onTracksChanged(Tracks tracks) {
                int audioCount = 0; boolean hasSubs = false;
                for (Tracks.Group g : tracks.getGroups()) {
                    if (g.getType() == C.TRACK_TYPE_AUDIO) audioCount += g.length;
                    if (g.getType() == C.TRACK_TYPE_TEXT && g.length > 0) hasSubs = true;
                }
                boolean fa = audioCount > 1, fs = hasSubs;
                runOnUiThread(() -> {
                    if (isVodType()) {
                        vodBtnAudio.setVisibility(fa ? View.VISIBLE : View.GONE);
                        vodBtnSubs.setVisibility(fs ? View.VISIBLE : View.GONE);
                        vodFsBtnSubs.setVisibility(fs ? View.VISIBLE : View.GONE);
                    } else {
                        liveBtnAudio.setVisibility(fa ? View.VISIBLE : View.GONE);
                        liveBtnSubs.setVisibility(fs ? View.VISIBLE : View.GONE);
                    }
                });
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    showLoading(false);
                    if (!isVodType()) scheduleLiveHideBars();
                } else if (state == Player.STATE_BUFFERING) {
                    showLoading(true);
                } else if (!isVodType() && (state == Player.STATE_IDLE || state == Player.STATE_ENDED)) {
                    retry();
                }
            }

            @Override
            public void onPlayerError(androidx.media3.common.PlaybackException e) {
                if (!isVodType()) retry();
                else { showLoading(false); toast("Error al reproducir"); }
            }
        });
    }

    private void retry() {
        if (retryCount < 3) {
            retryCount++;
            txtLoading.setText("Reconectando (" + retryCount + "/3)...");
            showLoading(true);
            handler.postDelayed(this::initPlayer, 3000);
        } else showLoading(false);
    }

    private void stopAndRelease() {
        handler.removeCallbacksAndMessages(null);
        if (player != null) {
            // Desconectar PlayerView primero — evita que el audio siga por el surface
            playerView.setPlayer(null);
            vodPlayerView.setPlayer(null);
            player.setPlayWhenReady(false);
            player.stop();
            player.clearMediaItems();
            player.release();
            player = null;
        }
    }

    // ══ CANAL SIGUIENTE/ANTERIOR ══
    private void navigateChannel(int dir) {
        if (channels.isEmpty() || channelIndex < 0) return;
        int next = channelIndex + dir;
        if (next < 0) next = channels.size() - 1;
        if (next >= channels.size()) next = 0;
        try {
            JSONObject ch = channels.get(next);
            channelIndex = next;
            url   = ch.optString("url", "");
            name  = ch.optString("name", "");
            itemId= ch.optString("id", "");
            liveTxtName.setText(name);
            retryCount = 0;
            showLiveBars();
            initPlayer();
        } catch (Exception e) { toast("Error al cambiar canal"); }
    }

    // ══ VOD FULLSCREEN ══
    private void enterVodFullscreen() {
        isVodFullscreen = true;
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        vodScroll.setVisibility(View.GONE);
        vodTopBar.setVisibility(View.GONE);
        // El FrameLayout padre del vod_player_view tiene layout_weight=4 en el LinearLayout vod_layout
        // Necesitamos cambiar el peso del FrameLayout, no del PlayerView
        View videoFrame = (View) vodPlayerView.getParent();
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) videoFrame.getLayoutParams();
        lp.weight = 10; lp.height = 0;
        videoFrame.setLayoutParams(lp);
        vodPlayerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL);
        vodFsTop.setVisibility(View.VISIBLE);
        vodFsBottom.setVisibility(View.VISIBLE);
        scheduleHideVodFs();
    }

    private void exitVodFullscreen() {
        isVodFullscreen = false;
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        vodScroll.setVisibility(View.VISIBLE);
        vodTopBar.setVisibility(View.VISIBLE);
        View videoFrame = (View) vodPlayerView.getParent();
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) videoFrame.getLayoutParams();
        lp.weight = 4; lp.height = 0;
        videoFrame.setLayoutParams(lp);
        vodPlayerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        vodFsTop.setVisibility(View.GONE);
        vodFsBottom.setVisibility(View.GONE);
    }

    private void toggleVodFsBars() {
        boolean show = vodFsTop.getVisibility() != View.VISIBLE;
        vodFsTop.setVisibility(show ? View.VISIBLE : View.GONE);
        vodFsBottom.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) scheduleHideVodFs();
    }

    private void scheduleHideVodFs() {
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(() -> {
            vodFsTop.setVisibility(View.GONE);
            vodFsBottom.setVisibility(View.GONE);
        }, 4000);
    }

    // ══ LIVE BARS ══
    private void toggleLiveBars() {
        if (liveBarsVisible) hideLiveBars(); else showLiveBars();
    }

    private void showLiveBars() {
        liveBarsVisible = true;
        liveTopBar.setVisibility(View.VISIBLE);
        liveBottomBar.setVisibility(View.VISIBLE);
        scheduleLiveHideBars();
    }

    private void hideLiveBars() {
        liveBarsVisible = false;
        liveTopBar.setVisibility(View.GONE);
        liveBottomBar.setVisibility(View.GONE);
    }

    private void scheduleLiveHideBars() {
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(this::hideLiveBars, 4000);
    }

    // ══ AUDIO / SUBS ══
    private void showAudioTracks() {
        if (player == null) return;
        List<String> labels = new ArrayList<>(), langs = new ArrayList<>();
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() == C.TRACK_TYPE_AUDIO) {
                for (int i = 0; i < g.length; i++) {
                    String lang = g.getTrackFormat(i).language;
                    labels.add(lang != null && !lang.isEmpty() ? lang.toUpperCase() : "Pista " + (labels.size()+1));
                    langs.add(lang != null ? lang : "");
                }
            }
        }
        if (labels.isEmpty()) { toast("Sin pistas de audio"); return; }
        new AlertDialog.Builder(this).setTitle("Seleccionar audio")
            .setItems(labels.toArray(new String[0]), (d, w) ->
                player.setTrackSelectionParameters(player.getTrackSelectionParameters()
                    .buildUpon().setPreferredAudioLanguage(langs.get(w)).build())).show();
    }

    private void showSubtitleTracks() {
        if (player == null) return;
        List<String> labels = new ArrayList<>(), langs = new ArrayList<>();
        labels.add("Ninguno"); langs.add("");
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() == C.TRACK_TYPE_TEXT) {
                for (int i = 0; i < g.length; i++) {
                    String lang = g.getTrackFormat(i).language;
                    labels.add(lang != null && !lang.isEmpty() ? lang.toUpperCase() : "Sub " + labels.size());
                    langs.add(lang != null ? lang : "");
                }
            }
        }
        if (labels.size() == 1) { toast("Sin subtitulos disponibles"); return; }
        new AlertDialog.Builder(this).setTitle("Subtitulos")
            .setItems(labels.toArray(new String[0]), (d, w) -> {
                if (w == 0) player.setTrackSelectionParameters(player.getTrackSelectionParameters()
                    .buildUpon().setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT).build());
                else player.setTrackSelectionParameters(player.getTrackSelectionParameters()
                    .buildUpon().setPreferredTextLanguage(langs.get(w)).build());
            }).show();
    }

    // ══ VOD INFO ══
    private void fetchVodInfo() {
        new Thread(() -> {
            try {
                String[] p = url.split("/");
                if (p.length < 6) return;
                String api = p[0] + "//" + p[2] + "/player_api.php?username=" + p[4]
                        + "&password=" + p[5] + "&action=get_vod_info&vod_id=" + itemId;
                HttpURLConnection c = (HttpURLConnection) new URL(api).openConnection();
                c.setConnectTimeout(8000); c.setReadTimeout(8000);
                BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream()));
                StringBuilder sb = new StringBuilder(); String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                JSONObject info = new JSONObject(sb.toString()).optJSONObject("info");
                if (info == null) return;
                String plot = info.optString("plot",""), year = info.optString("releasedate",info.optString("year",""));
                String dur  = info.optString("duration",""), rating = info.optString("rating","");
                runOnUiThread(() -> {
                    vodTxtPlot.setText(!plot.isEmpty() ? plot : "Sin sinopsis disponible.");
                    if (!year.isEmpty())   { vodTxtYear.setText(year.length()>=4?year.substring(0,4):year); vodTxtYear.setVisibility(View.VISIBLE); }
                    if (!dur.isEmpty())    { vodTxtDuration.setText(dur); vodTxtDuration.setVisibility(View.VISIBLE); }
                    if (!rating.isEmpty() && !rating.equals("0")) { vodTxtRating.setText("\u2B50 "+rating); vodTxtRating.setVisibility(View.VISIBLE); }
                });
            } catch (Exception e) { runOnUiThread(() -> vodTxtPlot.setText("Sin informacion disponible.")); }
        }).start();
    }

    // ══ HELPERS ══
    private void showLoading(boolean show) { loadingOverlay.setVisibility(show ? View.VISIBLE : View.GONE); }
    private void toast(String msg) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); }

    private void toggleFav(ImageButton btn) {
        isFav = !isFav; favChanged = true; favAdded = isFav;
        btn.setImageResource(isFav ? android.R.drawable.btn_star_big_on : android.R.drawable.btn_star_big_off);
        toast(isFav ? "Favorito guardado" : "Quitado de favoritos");
    }

    private boolean enteredPiP = false;

    private void enterPip() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && player != null) {
            enteredPiP = true;
            enterPictureInPictureMode(new PictureInPictureParams.Builder()
                .setAspectRatio(new Rational(16, 9)).build());
        }
    }

    private void launchExternal() {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(android.net.Uri.parse(url), "video/*");
            i.setPackage("org.videolan.vlc");
            startActivity(i);
        } catch (Exception e) { copyUrl(); }
    }

    private void copyUrl() {
        ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
            .setPrimaryClip(ClipData.newPlainText("url", url));
        toast("URL copiada");
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
            return new OkHttpClient.Builder().sslSocketFactory(sc.getSocketFactory(), tm)
                .hostnameVerifier((h, s) -> true).build();
        } catch (Exception e) { return new OkHttpClient.Builder().build(); }
    }

    // ══ LIFECYCLE ══
    @Override
    public void onPictureInPictureModeChanged(boolean inPiP) {
        super.onPictureInPictureModeChanged(inPiP);
        if (inPiP) {
            // Entrando en PiP — ocultar todo, solo video puro
            liveTopBar.setVisibility(View.GONE);
            liveBottomBar.setVisibility(View.GONE);
            vodFsTop.setVisibility(View.GONE);
            vodFsBottom.setVisibility(View.GONE);
            if (isVodType()) {
                vodTopBar.setVisibility(View.GONE);
                vodScroll.setVisibility(View.GONE);
                vodPlayerView.setUseController(false);
            } else {
                playerView.setUseController(false);
            }
        } else {
            // Saliendo de PiP — restaurar UI solamente
            // NO resetear enteredPiP aquí — onStop lo necesita para detectar cierre con X
            if (isVodType()) {
                vodTopBar.setVisibility(View.VISIBLE);
                vodScroll.setVisibility(isVodFullscreen ? View.GONE : View.VISIBLE);
                vodPlayerView.setUseController(true);
            } else {
                playerView.setUseController(true);
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Detener audio al ir al background EXCEPTO cuando entra a PiP
        if (enteredPiP) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode()) return;
        stopAndRelease();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Reiniciar solo si viene de PiP (el usuario volvió a la app)
        if (enteredPiP && player == null && url != null && !url.isEmpty()) {
            enteredPiP = false;
            initPlayer();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (enteredPiP && !isInPictureInPictureMode()) {
            stopAndRelease();
            enteredPiP = false;
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopAndRelease();
        if (activeInstance == this) activeInstance = null;
        Intent result = new Intent();
        result.putExtra("fav_added", favChanged && favAdded);
        result.putExtra("fav_removed", favChanged && !favAdded);
        result.putExtra("item_id", itemId);
        result.putExtra("item_type", type);
        setResult(RESULT_OK, result);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Detener player actual antes de reproducir nuevo contenido
        stopAndRelease();
        setIntent(intent);
        // Releer datos del nuevo intent
        url          = intent.getStringExtra("url");
        name         = intent.getStringExtra("name");
        group        = intent.getStringExtra("group");
        type         = intent.getStringExtra("type");
        logo         = intent.getStringExtra("logo");
        itemId       = intent.getStringExtra("id");
        channelIndex = intent.getIntExtra("channel_index", -1);
        channels.clear();
        parseChannels(intent.getStringExtra("channels_json"));
        retryCount = 0;
        enteredPiP = false;
        // Actualizar UI y reiniciar player
        if (isVodType()) {
            vodTxtTitleBar.setText(name);
            vodTxtTitle.setText(name);
            vodFsTxtTitle.setText(name);
            vodTxtPlot.setText("Cargando informacion...");
            vodTxtYear.setVisibility(View.GONE);
            vodTxtDuration.setVisibility(View.GONE);
            vodTxtRating.setVisibility(View.GONE);
            fetchVodInfo();
            initPlayer();
        } else {
            liveTxtName.setText(name);
            initPlayer();
        }
    }

    @Override
    public void onBackPressed() {
        if (isVodFullscreen) {
            exitVodFullscreen();
        } else {
            stopAndRelease();
            finish();
        }
    }
}
