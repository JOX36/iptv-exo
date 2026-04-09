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
    private LinearLayout liveEpgContainer;
    private TextView liveTxtName, liveTxtStatus, txtLoading;
    private TextView liveEpgNow, liveEpgTime, liveEpgNext;
    private ProgressBar liveEpgProgress;
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

    // Progreso VOD
    private static final String PREFS_PROGRESS = "vod_progress";
    private long savedPosition = 0; // posición a restaurar
    private final Handler progressHandler = new Handler();
    private Runnable progressSaver;

    // Siguiente episodio — reproducción continua
    private LinearLayout nextEpOverlay;
    private TextView nextEpTitle, nextEpCountdown;
    private Button nextEpBtnNow, nextEpBtnCancel;
    private final Handler countdownHandler = new Handler();
    private Runnable countdownRunnable;
    private int countdownSeconds = 5;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        // Borde a borde — eliminar barras negras laterales en notch/cutout
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

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

        // EPG data
        String epgNow  = getIntent().getStringExtra("epg_now");
        String epgNext = getIntent().getStringExtra("epg_next");
        int epgProgress = getIntent().getIntExtra("epg_progress", 0);
        String epgTime = getIntent().getStringExtra("epg_time");

        bindViews();
        setEmojiLabels();

        if (isVodType()) setupVod();
        else setupLive();

        // Mostrar EPG si hay datos (solo Live)
        if (!isVodType() && epgNow != null && !epgNow.isEmpty()) {
            showEpg(epgNow, epgTime, epgNext, epgProgress);
        }

        // VOD — verificar si hay posición guardada
        if (isVodType() && itemId != null && !itemId.isEmpty()) {
            savedPosition = getVodProgress(itemId);
            if (savedPosition > 5000) { // más de 5 segundos guardados
                askContinueOrRestart();
            } else {
                initPlayer();
            }
        } else {
            initPlayer();
        }
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
        liveEpgContainer = findViewById(R.id.live_epg_container);
        liveEpgNow    = findViewById(R.id.live_epg_now);
        liveEpgTime   = findViewById(R.id.live_epg_time);
        liveEpgNext   = findViewById(R.id.live_epg_next);
        liveEpgProgress = findViewById(R.id.live_epg_progress);

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

        // Siguiente episodio
        nextEpOverlay  = findViewById(R.id.next_ep_overlay);
        nextEpTitle    = findViewById(R.id.next_ep_title);
        nextEpCountdown= findViewById(R.id.next_ep_countdown);
        nextEpBtnNow   = findViewById(R.id.next_ep_btn_now);
        nextEpBtnCancel= findViewById(R.id.next_ep_btn_cancel);
        nextEpBtnNow.setOnClickListener(v -> playNextEpisode());
        nextEpBtnCancel.setOnClickListener(v -> hideNextEpOverlay());
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

    private void showEpg(String now, String time, String next, int progress) {
        if (liveEpgContainer == null) return;
        if (now != null && !now.isEmpty()) {
            liveEpgContainer.setVisibility(View.VISIBLE);
            liveEpgNow.setText(now);
            liveEpgTime.setText(time != null ? time : "");
            liveEpgNext.setText(next != null && !next.isEmpty() ? "▶ " + next : "");
            liveEpgProgress.setProgress(progress);
        } else {
            liveEpgContainer.setVisibility(View.GONE);
        }
    }

    private void fetchEpg() {
        if (itemId == null || itemId.isEmpty() || url == null) return;
        new Thread(() -> {
            try {
                String[] p = url.split("/");
                if (p.length < 6) return;
                String api = p[0] + "//" + p[2] + "/player_api.php?username=" + p[3]
                        + "&password=" + p[4] + "&action=get_short_epg&stream_id=" + itemId + "&limit=2";
                // Extraer user/pass correctamente
                // URL live: host/live/user/pass/id.m3u8
                String user = p.length > 4 ? p[4] : "";
                String pass = p.length > 5 ? p[5] : "";
                api = p[0] + "//" + p[2] + "/player_api.php?username=" + user
                        + "&password=" + pass + "&action=get_short_epg&stream_id=" + itemId + "&limit=2";
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(api).openConnection();
                c.setConnectTimeout(6000); c.setReadTimeout(6000);
                java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(c.getInputStream()));
                StringBuilder sb = new StringBuilder(); String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                org.json.JSONObject json = new org.json.JSONObject(sb.toString());
                org.json.JSONArray list = json.optJSONArray("epg_listings");
                if (list == null || list.length() == 0) return;
                java.util.Date now = new java.util.Date();
                String epgNow = "", epgNext = "", epgTime = "";
                int epgProgress = 0;
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault());
                for (int i = 0; i < list.length(); i++) {
                    org.json.JSONObject e = list.getJSONObject(i);
                    String title = e.optString("title", "");
                    if (!title.isEmpty()) {
                        try { title = new String(android.util.Base64.decode(title, android.util.Base64.DEFAULT)); } catch (Exception ex) {}
                    }
                    try {
                        java.util.Date start = sdf.parse(e.optString("start", ""));
                        java.util.Date end   = sdf.parse(e.optString("end", ""));
                        if (start == null || end == null) continue;
                        if (now.after(start) && now.before(end)) {
                            epgNow = title;
                            long dur = end.getTime() - start.getTime();
                            long elapsed = now.getTime() - start.getTime();
                            epgProgress = dur > 0 ? (int)((elapsed * 100) / dur) : 0;
                            java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault());
                            epgTime = fmt.format(start) + " – " + fmt.format(end);
                        } else if (now.before(start) && !epgNow.isEmpty() && epgNext.isEmpty()) {
                            epgNext = title;
                        }
                    } catch (Exception ex) {}
                }
                final String fNow = epgNow, fTime = epgTime, fNext = epgNext;
                final int fProg = epgProgress;
                runOnUiThread(() -> showEpg(fNow, fTime, fNext, fProg));
            } catch (Exception e) { /* EPG no disponible */ }
        }).start();
    }

    // ══ SETUP LIVE ══
    private void setupLive() {
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL);
        // Extender a bordes completos — elimina barras negras laterales
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
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

        // Para VOD: especificar tipo MIME según extensión — fix servidores con SSL/redirecciones
        MediaItem mediaItem;
        if (isVodType()) {
            String mimeType = "video/mp4";
            if (url.contains(".mkv")) mimeType = "video/x-matroska";
            else if (url.contains(".ts")) mimeType = "video/mp2t";
            else if (url.contains(".avi")) mimeType = "video/avi";
            mediaItem = new MediaItem.Builder()
                    .setUri(url)
                    .setMimeType(mimeType)
                    .build();
        } else {
            mediaItem = MediaItem.fromUri(url);
        }
        player.setMediaItem(mediaItem);
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
                    if (!isVodType()) {
                        scheduleLiveHideBars();
                        fetchEpg();
                    } else {
                        // Buscar posición guardada en el primer STATE_READY
                        if (savedPosition > 0) {
                            player.seekTo(savedPosition);
                            savedPosition = 0; // resetear para no buscar de nuevo
                        }
                        stopProgressSaver(); // detener cualquier timer anterior
                        startProgressSaver();
                    }
                } else if (state == Player.STATE_BUFFERING) {
                    showLoading(true);
                } else if (state == Player.STATE_ENDED) {
                    if (!isVodType()) {
                        retry();
                    } else if (isSeriesType()) {
                        // Serie terminó — mostrar overlay siguiente episodio
                        onEpisodeEnded();
                    }
                } else if (state == Player.STATE_IDLE && !isVodType()) {
                    retry();
                }
            }

            @Override
            public void onPlayerError(androidx.media3.common.PlaybackException e) {
                if (!isVodType()) retry();
                else {
                    showLoading(false);
                    // ExoPlayer falló en VOD — intentar con VLC automáticamente
                    toast("\uD83D\uDCFA Abriendo en reproductor externo...");
                    handler.postDelayed(() -> launchExternal(), 800);
                }
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
        stopProgressSaver();
        hideNextEpOverlay();
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
        // Intentar VLC primero
        try {
            Intent vlc = new Intent(Intent.ACTION_VIEW);
            vlc.setDataAndType(android.net.Uri.parse(url), "video/*");
            vlc.setPackage("org.videolan.vlc");
            startActivity(vlc);
            return;
        } catch (Exception ignored) {}
        // Si VLC no está — abrir con cualquier reproductor instalado
        try {
            Intent any = new Intent(Intent.ACTION_VIEW);
            any.setDataAndType(android.net.Uri.parse(url), "video/*");
            startActivity(Intent.createChooser(any, "Abrir con..."));
        } catch (Exception e) {
            // Ningún reproductor — copiar URL
            copyUrl();
            toast("\uD83D\uDCCB URL copiada \u2014 pega en tu reproductor");
        }
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
                .hostnameVerifier((h, s) -> true)
                .addInterceptor(chain -> chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", "VLC/3.0.18 LibVLC/3.0.18")
                        .build()
                ))
                .build();
        } catch (Exception e) { return new OkHttpClient.Builder().build(); }
    }

    // ══ PROGRESO VOD ══
    private void askContinueOrRestart() {
        long mins = savedPosition / 60000;
        long secs = (savedPosition % 60000) / 1000;
        String timeStr = mins > 0 ? mins + "m " + secs + "s" : secs + "s";
        new android.app.AlertDialog.Builder(this)
            .setTitle(name)
            .setMessage("¿Continuar desde " + timeStr + "?")
            .setPositiveButton("▶ Continuar", (d, w) -> {
                initPlayer(); // el seek se hace en STATE_READY via seekOnReady flag
            })
            .setNegativeButton("⏮ Empezar de nuevo", (d, w) -> {
                savedPosition = 0;
                clearVodProgress(itemId);
                initPlayer();
            })
            .setCancelable(false)
            .show();
    }

    private void startProgressSaver() {
        if (!isVodType() || itemId == null) return;
        progressSaver = new Runnable() {
            @Override public void run() {
                if (player != null && player.isPlaying()) {
                    long pos = player.getCurrentPosition();
                    long dur = player.getDuration();
                    if (pos > 5000 && dur > 0) {
                        // Si superó el 95% marcar como vista completa
                        if ((float) pos / dur > 0.95f) {
                            clearVodProgress(itemId);
                        } else {
                            saveVodProgress(itemId, pos, dur);
                        }
                    }
                }
                progressHandler.postDelayed(this, 10000); // cada 10 segundos
            }
        };
        progressHandler.postDelayed(progressSaver, 10000);
    }

    private void stopProgressSaver() {
        if (progressSaver != null) {
            progressHandler.removeCallbacks(progressSaver);
            progressSaver = null;
        }
    }

    private void saveVodProgress(String id, long position, long duration) {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS_PROGRESS, MODE_PRIVATE);
        prefs.edit()
            .putLong("pos_" + id, position)
            .putLong("dur_" + id, duration)
            .apply();
    }

    private long getVodProgress(String id) {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS_PROGRESS, MODE_PRIVATE);
        return prefs.getLong("pos_" + id, 0);
    }

    private long getVodDuration(String id) {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS_PROGRESS, MODE_PRIVATE);
        return prefs.getLong("dur_" + id, 0);
    }

    private void clearVodProgress(String id) {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS_PROGRESS, MODE_PRIVATE);
        prefs.edit().remove("pos_" + id).remove("dur_" + id).apply();
    }

    // ══ REPRODUCCIÓN CONTINUA SERIES ══
    private boolean isSeriesType() {
        if (channels.isEmpty()) return false;
        try {
            // Cualquier item de la lista con _isSeries=true confirma que es serie
            for (int i = 0; i < Math.min(channels.size(), 3); i++) {
                if (channels.get(i).optBoolean("_isSeries", false)) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private void onEpisodeEnded() {
        if (channelIndex < 0 || channels.isEmpty()) { finish(); return; }
        int nextIdx = channelIndex + 1;
        // Caso 3 — último episodio de la última temporada
        if (nextIdx >= channels.size()) {
            showSeriesCompleted();
            return;
        }
        // Caso 1 y 2 — hay siguiente episodio (misma o nueva temporada)
        try {
            JSONObject next = channels.get(nextIdx);
            String nextName = next.optString("name", "Siguiente episodio");
            showNextEpOverlay(nextName, nextIdx);
        } catch (Exception e) { finish(); }
    }

    private void showNextEpOverlay(String nextName, int nextIdx) {
        if (nextEpOverlay == null) return;
        nextEpTitle.setText(nextName);
        nextEpOverlay.setVisibility(View.VISIBLE);
        countdownSeconds = 5;
        nextEpCountdown.setText("En " + countdownSeconds + " segundos...");

        countdownRunnable = new Runnable() {
            @Override public void run() {
                countdownSeconds--;
                if (countdownSeconds <= 0) {
                    playNextEpisode();
                } else {
                    nextEpCountdown.setText("En " + countdownSeconds + " segundos...");
                    countdownHandler.postDelayed(this, 1000);
                }
            }
        };
        countdownHandler.postDelayed(countdownRunnable, 1000);
    }

    private void playNextEpisode() {
        hideNextEpOverlay();
        if (channelIndex + 1 >= channels.size()) { finish(); return; }
        channelIndex++;
        try {
            JSONObject next = channels.get(channelIndex);
            url    = next.optString("url", "");
            name   = next.optString("name", "");
            itemId = next.optString("id", "");
            savedPosition = getVodProgress(itemId);
            vodTxtTitleBar.setText(name);
            vodTxtTitle.setText(name);
            vodFsTxtTitle.setText(name);
            vodTxtPlot.setText("");
            retryCount = 0;
            initPlayer();
        } catch (Exception e) { finish(); }
    }

    private void hideNextEpOverlay() {
        countdownHandler.removeCallbacks(countdownRunnable);
        if (nextEpOverlay != null) nextEpOverlay.setVisibility(View.GONE);
    }

    private void showSeriesCompleted() {
        if (nextEpOverlay == null) { finish(); return; }
        nextEpTitle.setText("Serie completada \u2705");
        nextEpCountdown.setText("Has visto todos los episodios");
        nextEpBtnNow.setVisibility(View.GONE);
        nextEpBtnCancel.setText("Cerrar");
        nextEpBtnCancel.setOnClickListener(v -> finish());
        nextEpOverlay.setVisibility(View.VISIBLE);
    }

    // ══ LIFECYCLE ══
    @Override
    public void onPictureInPictureModeChanged(boolean inPiP) {
        super.onPictureInPictureModeChanged(inPiP);
        if (inPiP) {
            // Entrando en PiP — ocultar todo
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
            // Monitorear cierre de PiP en MIUI
            startPipMonitor();
        } else {
            // Saliendo de PiP — restaurar UI
            enteredPiP = false;
            stopPipMonitor();
            if (isVodType()) {
                vodTopBar.setVisibility(View.VISIBLE);
                vodScroll.setVisibility(isVodFullscreen ? View.GONE : View.VISIBLE);
                vodPlayerView.setUseController(true);
            } else {
                playerView.setUseController(true);
            }
        }
    }

    private Runnable pipMonitor = null;

    private void startPipMonitor() {
        pipMonitor = new Runnable() {
            @Override
            public void run() {
                if (!enteredPiP) return;
                // Si la ventana no es visible y no estamos en PiP activo = usuario cerró con X
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    if (!isInPictureInPictureMode() && enteredPiP) {
                        runOnUiThread(() -> {
                            stopAndRelease();
                            enteredPiP = false;
                            finish();
                        });
                        return;
                    }
                }
                handler.postDelayed(this, 500);
            }
        };
        handler.postDelayed(pipMonitor, 500);
    }

    private void stopPipMonitor() {
        if (pipMonitor != null) {
            handler.removeCallbacks(pipMonitor);
            pipMonitor = null;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Solo pausar — no destruir. La destrucción va en botones de salida y onDestroy
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode()) return;
        if (enteredPiP) return;
        if (player != null) player.setPlayWhenReady(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (enteredPiP) {
            enteredPiP = false;
            if (player == null && url != null && !url.isEmpty()) initPlayer();
            return;
        }
        if (player != null) player.setPlayWhenReady(true);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // En MIUI, isInPictureInPictureMode() puede no ser confiable
        // Si enteredPiP=true y llegamos a onStop, el usuario cerró el PiP
        if (enteredPiP) {
            stopAndRelease();
            enteredPiP = false;
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopProgressSaver();
        // Guardar posición final antes de salir
        if (isVodType() && player != null && itemId != null) {
            long pos = player.getCurrentPosition();
            long dur = player.getDuration();
            if (pos > 5000 && dur > 0 && (float)pos/dur < 0.95f) {
                saveVodProgress(itemId, pos, dur);
            }
        }
        stopAndRelease();
        if (activeInstance == this) activeInstance = null;
        // Devolver resultado al MainActivity
        long retPos = isVodType() && itemId != null ? getVodProgress(itemId) : 0;
        long retDur = isVodType() && itemId != null ? getVodDuration(itemId) : 0;
        Intent result = new Intent();
        result.putExtra("fav_added", favChanged && favAdded);
        result.putExtra("fav_removed", favChanged && !favAdded);
        result.putExtra("item_id", itemId);
        result.putExtra("item_type", type);
        result.putExtra("vod_position", retPos);
        result.putExtra("vod_duration", retDur);
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
        savedPosition = 0; // resetear posición guardada al cambiar de contenido
        // Actualizar UI y reiniciar player
        if (isVodType()) {
            // Verificar si hay posición guardada para el nuevo item
            if (itemId != null && !itemId.isEmpty()) {
                savedPosition = getVodProgress(itemId);
            }
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
