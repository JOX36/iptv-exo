package com.jox3.iptvexo;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import androidx.media.session.MediaSessionCompat;
import androidx.media.session.PlaybackStateCompat;
import androidx.media.MediaMetadataCompat;


import android.util.Rational;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;

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
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.OkHttpClient;

@UnstableApi
public class PlayerActivity extends AppCompatActivity {

    // ── Constantes ──
    private static final String NOTIF_CHANNEL_ID = "jox3_player";
    private static final int    NOTIF_ID         = 1001;
    private static final String USER_AGENT       = "VLC/3.0.0 LibVLC/3.0.0";

    // ── Player global ──
    private ExoPlayer player;
    private static PlayerActivity activeInstance = null;

    // ── MediaSession ──
    private MediaSessionCompat mediaSession;

    // ── OkHttpClient compartido (se crea una sola vez) ──
    private OkHttpClient httpClient;

    // ── LIVE views ──
    private PlayerView playerView;
    private LinearLayout liveTopBar, liveBottomBar, loadingOverlay;
    private TextView liveTxtName, liveTxtStatus, txtLoading;
    private ImageButton liveBtnBack, liveBtnFav;
    private Button liveBtnAudio, liveBtnSubs, liveBtnPip, liveBtnExt, liveBtnStop;
    private ProgressBar progressBar;

    // ── VOD views ──
    private LinearLayout vodLayout;
    private PlayerView vodPlayerView;
    private LinearLayout vodTopBar;
    private ImageButton vodBtnBack, vodBtnFav;
    private TextView vodTxtTitleBar, vodTxtTitle, vodTxtYear, vodTxtDuration,
            vodTxtRating, vodTxtPlot;
    private ScrollView vodScroll;
    private Button vodBtnFullscreen, vodBtnPip, vodBtnExt, vodBtnCopy,
            vodBtnStop, vodBtnAudio, vodBtnSubs;

    // ── VOD fullscreen overlay ──
    private LinearLayout vodFsTop, vodFsBottom;
    private TextView vodFsTxtTitle;
    private Button vodFsBtnExit, vodFsBtnPip, vodFsBtnExt, vodFsBtnUrl, vodFsBtnSubs;

    // ── Datos del intent ──
    private String url, name, group, type, logo, itemId;
    private List<JSONObject> channels = new ArrayList<>();
    private int channelIndex = -1;

    // ── Estado ──
    private boolean isFav          = false;
    private boolean favChanged      = false;
    private boolean favAdded        = false;
    private boolean isVodFullscreen = false;
    private boolean liveBarsVisible = false;
    private boolean enteredPiP      = false;
    private int     retryCount      = 0;

    private final Handler handler = new Handler();
    private GestureDetector gestureDetector;

    // ── Gestos: brillo y volumen ──
    private AudioManager audioManager;
    private float        initialBrightness = -1f;
    private int          initialVolume     = -1;
    private float        gestureStartY     = -1f;
    private float        gestureStartX     = -1f;
    private boolean      gestureActive     = false;
    private enum GestureMode { NONE, BRIGHTNESS, VOLUME, SEEK }
    private GestureMode  gestureMode = GestureMode.NONE;
    private long         seekStartPos = 0;
    private TextView     gestureOverlay; // indicador central de gesto

    // ─────────────────────────────────────────────
    //  LIFECYCLE
    // ─────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Pantalla siempre encendida y fullscreen
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        // Detener instancia anterior — evita audio doble
        if (activeInstance != null && activeInstance != this) {
            activeInstance.stopAndRelease();
        }
        activeInstance = this;

        setContentView(R.layout.activity_player);

        // Leer intent
        url          = getIntent().getStringExtra("url");
        name         = getIntent().getStringExtra("name");
        group        = getIntent().getStringExtra("group");
        type         = getIntent().getStringExtra("type");
        logo         = getIntent().getStringExtra("logo");
        itemId       = getIntent().getStringExtra("id");
        channelIndex = getIntent().getIntExtra("channel_index", -1);
        parseChannels(getIntent().getStringExtra("channels_json"));

        // Servicios del sistema
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);

        // Cliente HTTP reutilizable con User-Agent VLC
        httpClient = buildHttpClient();

        // Crear canal de notificación (Android 8+)
        createNotificationChannel();

        // MediaSession
        initMediaSession();

        bindViews();
        setEmojiLabels();

        if (isVodType()) setupVod();
        else setupLive();

        initPlayer();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopAndRelease();
        cancelNotification();
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
        }
        if (activeInstance == this) activeInstance = null;

        Intent result = new Intent();
        result.putExtra("fav_added",   favChanged && favAdded);
        result.putExtra("fav_removed", favChanged && !favAdded);
        result.putExtra("item_id",   itemId);
        result.putExtra("item_type", type);
        setResult(RESULT_OK, result);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Cerrar PiP con el botón X → stopAndRelease
        if (enteredPiP && !isInPictureInPictureMode()) {
            stopAndRelease();
            enteredPiP = false;
            finish();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        // Handle notification control actions
        String action = intent.getAction();
        if (action != null) {
            switch (action) {
                case "jox3.PLAY":
                    if (player != null) {
                        if (player.isPlaying()) player.pause(); else player.play();
                    }
                    return;
                case "jox3.PREV": navigateChannel(-1); return;
                case "jox3.NEXT": navigateChannel(1);  return;
                case "jox3.STOP": stopAndRelease(); finish(); return;
            }
        }

        stopAndRelease();
        setIntent(intent);

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

        if (isVodType()) {
            vodTxtTitleBar.setText(name);
            vodTxtTitle.setText(name);
            vodFsTxtTitle.setText(name);
            vodTxtPlot.setText("Cargando información...");
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

    @Override
    public void onPictureInPictureModeChanged(boolean inPiP) {
        super.onPictureInPictureModeChanged(inPiP);
        if (inPiP) {
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
            if (isVodType()) {
                vodTopBar.setVisibility(View.VISIBLE);
                vodScroll.setVisibility(isVodFullscreen ? View.GONE : View.VISIBLE);
                vodPlayerView.setUseController(true);
            } else {
                playerView.setUseController(true);
            }
        }
    }

    // ─────────────────────────────────────────────
    //  MEDIASESSION + NOTIFICACIÓN
    // ─────────────────────────────────────────────

    private void initMediaSession() {
        mediaSession = new MediaSessionCompat(this, "JOX3TV");
        mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);

        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay()  { if (player != null) player.play(); }
            @Override public void onPause() { if (player != null) player.pause(); }
            @Override public void onStop()  { stopAndRelease(); finish(); }
            @Override public void onSkipToNext()     { navigateChannel(1);  }
            @Override public void onSkipToPrevious() { navigateChannel(-1); }
            @Override public void onSeekTo(long pos) { if (player != null) player.seekTo(pos); }
        });
        mediaSession.setActive(true);
    }

    private void updateMediaSession(boolean playing) {
        if (mediaSession == null) return;

        PlaybackStateCompat.Builder stateBuilder = new PlaybackStateCompat.Builder()
                .setActions(
                        PlaybackStateCompat.ACTION_PLAY |
                        PlaybackStateCompat.ACTION_PAUSE |
                        PlaybackStateCompat.ACTION_STOP |
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
                        PlaybackStateCompat.ACTION_SEEK_TO
                )
                .setState(
                        playing ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED,
                        player != null ? player.getCurrentPosition() : 0,
                        playing ? 1f : 0f
                );
        mediaSession.setPlaybackState(stateBuilder.build());

        MediaMetadataCompat.Builder metaBuilder = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, name != null ? name : "")
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, group != null ? group : "JOX3 TV")
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "JOX3 TV");

        if (player != null && player.getDuration() > 0) {
            metaBuilder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, player.getDuration());
        }
        mediaSession.setMetadata(metaBuilder.build());

        showNotification(playing);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    NOTIF_CHANNEL_ID, "JOX3 TV Reproductor",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Controles de reproducción IPTV");
            ch.setShowBadge(false);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
    }

    private void showNotification(boolean playing) {
        if (mediaSession == null) return;

        Intent openIntent = new Intent(this, PlayerActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Intents para acciones
        PendingIntent prevPi  = buildActionPi("jox3.PREV",  0);
        PendingIntent playPi  = buildActionPi("jox3.PLAY",  1);
        PendingIntent nextPi  = buildActionPi("jox3.NEXT",  2);
        PendingIntent stopPi  = buildActionPi("jox3.STOP",  3);

        String subtitle = (group != null && !group.isEmpty()) ? group : "JOX3 TV";

        NotificationCompat.Builder nb = new NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(name != null ? name : "Reproduciendo")
                .setContentText(subtitle)
                .setContentIntent(openPi)
                .setOngoing(playing)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setSilent(true)
                .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2))
                .addAction(android.R.drawable.ic_media_previous, "Anterior", prevPi)
                .addAction(playing
                        ? android.R.drawable.ic_media_pause
                        : android.R.drawable.ic_media_play,
                        playing ? "Pausar" : "Reproducir", playPi)
                .addAction(android.R.drawable.ic_media_next, "Siguiente", nextPi)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Detener", stopPi);

        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                .notify(NOTIF_ID, nb.build());
    }

    private PendingIntent buildActionPi(String action, int reqCode) {
        // Use activity intent with extras - simpler than BroadcastReceiver
        Intent i = new Intent(this, PlayerActivity.class);
        i.setAction(action);
        i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, reqCode, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void cancelNotification() {
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(NOTIF_ID);
    }

    // ─────────────────────────────────────────────
    //  SETUP VISTAS
    // ─────────────────────────────────────────────

    private void bindViews() {
        playerView      = findViewById(R.id.player_view);
        liveTopBar      = findViewById(R.id.live_top_bar);
        liveBottomBar   = findViewById(R.id.live_bottom_bar);
        loadingOverlay  = findViewById(R.id.loading_overlay);
        liveTxtName     = findViewById(R.id.live_txt_name);
        liveTxtStatus   = findViewById(R.id.live_txt_status);
        txtLoading      = findViewById(R.id.txt_loading);
        progressBar     = findViewById(R.id.progress_bar);
        liveBtnBack     = findViewById(R.id.live_btn_back);
        liveBtnFav      = findViewById(R.id.live_btn_fav);
        liveBtnAudio    = findViewById(R.id.live_btn_audio);
        liveBtnSubs     = findViewById(R.id.live_btn_subs);
        liveBtnPip      = findViewById(R.id.live_btn_pip);
        liveBtnExt      = findViewById(R.id.live_btn_ext);
        liveBtnStop     = findViewById(R.id.live_btn_stop);
        gestureOverlay  = findViewById(R.id.gesture_overlay);

        vodLayout       = findViewById(R.id.vod_layout);
        vodPlayerView   = findViewById(R.id.vod_player_view);
        vodTopBar       = findViewById(R.id.vod_top_bar);
        vodBtnBack      = findViewById(R.id.vod_btn_back);
        vodBtnFav       = findViewById(R.id.vod_btn_fav);
        vodTxtTitleBar  = findViewById(R.id.vod_txt_title_bar);
        vodTxtTitle     = findViewById(R.id.vod_txt_title);
        vodTxtYear      = findViewById(R.id.vod_txt_year);
        vodTxtDuration  = findViewById(R.id.vod_txt_duration);
        vodTxtRating    = findViewById(R.id.vod_txt_rating);
        vodTxtPlot      = findViewById(R.id.vod_txt_plot);
        vodScroll       = findViewById(R.id.vod_scroll);
        vodBtnFullscreen= findViewById(R.id.vod_btn_fullscreen);
        vodBtnPip       = findViewById(R.id.vod_btn_pip);
        vodBtnExt       = findViewById(R.id.vod_btn_ext);
        vodBtnCopy      = findViewById(R.id.vod_btn_copy);
        vodBtnStop      = findViewById(R.id.vod_btn_stop);
        vodBtnAudio     = findViewById(R.id.vod_btn_audio);
        vodBtnSubs      = findViewById(R.id.vod_btn_subs);

        vodFsTop        = findViewById(R.id.vod_fs_top);
        vodFsBottom     = findViewById(R.id.vod_fs_bottom);
        vodFsTxtTitle   = findViewById(R.id.vod_fs_txt_title);
        vodFsBtnExit    = findViewById(R.id.vod_fs_btn_exit);
        vodFsBtnPip     = findViewById(R.id.vod_fs_btn_pip);
        vodFsBtnExt     = findViewById(R.id.vod_fs_btn_ext);
        vodFsBtnUrl     = findViewById(R.id.vod_fs_btn_url);
        vodFsBtnSubs    = findViewById(R.id.vod_fs_btn_subs);
    }

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

    // ─────────────────────────────────────────────
    //  SETUP LIVE
    // ─────────────────────────────────────────────

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

        setupLiveGestures();
    }

    // ─────────────────────────────────────────────
    //  GESTOS TÁCTILES (brillo / volumen / seek)
    // ─────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private void setupLiveGestures() {
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vX, float vY) {
                if (e1 == null || e2 == null) return false;
                float diff = e2.getX() - e1.getX();
                if (Math.abs(diff) > 120 && Math.abs(vX) > 100) {
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

        playerView.setOnTouchListener((v, event) -> {
            gestureDetector.onTouchEvent(event);
            handleSlideGesture(event, playerView.getWidth(), playerView.getHeight());
            return true;
        });
    }

    private void handleSlideGesture(MotionEvent event, int width, int height) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                gestureStartX   = event.getX();
                gestureStartY   = event.getY();
                gestureActive   = false;
                gestureMode     = GestureMode.NONE;
                initialVolume   = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                initialBrightness = getScreenBrightness();
                if (player != null) seekStartPos = player.getCurrentPosition();
                break;

            case MotionEvent.ACTION_MOVE:
                float dx = event.getX() - gestureStartX;
                float dy = event.getY() - gestureStartY;

                // Determinar modo de gesto al superar umbral
                if (!gestureActive && (Math.abs(dx) > 20 || Math.abs(dy) > 20)) {
                    gestureActive = true;
                    if (Math.abs(dx) > Math.abs(dy) * 1.5f) {
                        // Horizontal → seek (solo VOD)
                        gestureMode = isVodType() ? GestureMode.SEEK : GestureMode.NONE;
                    } else {
                        // Vertical → brillo (izquierda) o volumen (derecha)
                        gestureMode = gestureStartX < width / 2f
                                ? GestureMode.BRIGHTNESS : GestureMode.VOLUME;
                    }
                }

                if (!gestureActive) break;

                float delta = -(dy / height); // subir = positivo

                if (gestureMode == GestureMode.VOLUME) {
                    int maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                    int newVol = (int) Math.max(0, Math.min(maxVol,
                            initialVolume + delta * maxVol));
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0);
                    showGestureOverlay("\uD83D\uDD0A " + Math.round(newVol * 100f / maxVol) + "%");

                } else if (gestureMode == GestureMode.BRIGHTNESS) {
                    float newBr = Math.max(0.01f, Math.min(1f, initialBrightness + delta));
                    setScreenBrightness(newBr);
                    showGestureOverlay("\u2600 " + Math.round(newBr * 100) + "%");

                } else if (gestureMode == GestureMode.SEEK && player != null) {
                    long duration = player.getDuration();
                    if (duration > 0) {
                        long seekDelta = (long) (dx / width * duration);
                        long newPos = Math.max(0, Math.min(duration, seekStartPos + seekDelta));
                        long secs = newPos / 1000;
                        showGestureOverlay(String.format("%d:%02d", secs / 60, secs % 60));
                    }
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (gestureMode == GestureMode.SEEK && player != null) {
                    long duration = player.getDuration();
                    if (duration > 0) {
                        float dxFinal = event.getX() - gestureStartX;
                        long seekDelta = (long) (dxFinal / width * duration);
                        player.seekTo(Math.max(0, Math.min(duration, seekStartPos + seekDelta)));
                    }
                }
                hideGestureOverlay();
                gestureActive = false;
                gestureMode   = GestureMode.NONE;
                break;
        }
    }

    private float getScreenBrightness() {
        Window w = getWindow();
        float br = w.getAttributes().screenBrightness;
        if (br < 0) {
            try {
                br = Settings.System.getInt(getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS) / 255f;
            } catch (Exception e) { br = 0.5f; }
        }
        return br;
    }

    private void setScreenBrightness(float brightness) {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = brightness;
        getWindow().setAttributes(lp);
    }

    private void showGestureOverlay(String text) {
        if (gestureOverlay == null) return;
        gestureOverlay.setText(text);
        gestureOverlay.setVisibility(View.VISIBLE);
    }

    private void hideGestureOverlay() {
        handler.postDelayed(() -> {
            if (gestureOverlay != null) gestureOverlay.setVisibility(View.GONE);
        }, 600);
    }

    // ─────────────────────────────────────────────
    //  SETUP VOD
    // ─────────────────────────────────────────────

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
        vodTxtPlot.setText("Cargando información...");
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
        vodFsBtnExit.setOnClickListener(v -> exitVodFullscreen());
        vodFsBtnPip.setOnClickListener(v -> enterPip());
        vodFsBtnExt.setOnClickListener(v -> launchExternal());
        vodFsBtnUrl.setOnClickListener(v -> copyUrl());
        vodFsBtnSubs.setOnClickListener(v -> showSubtitleTracks());
        vodPlayerView.setOnClickListener(v -> { if (isVodFullscreen) toggleVodFsBars(); });

        fetchVodInfo();
    }

    // ─────────────────────────────────────────────
    //  PLAYER
    // ─────────────────────────────────────────────

    private void initPlayer() {
        stopAndRelease();
        showLoading(true);
        retryCount = 0;

        PlayerView pv = isVodType() ? vodPlayerView : playerView;

        OkHttpDataSource.Factory dsf = new OkHttpDataSource.Factory(httpClient);
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
                int audioCount = 0;
                boolean hasSubs = false;
                for (Tracks.Group g : tracks.getGroups()) {
                    if (g.getType() == C.TRACK_TYPE_AUDIO) audioCount += g.length;
                    if (g.getType() == C.TRACK_TYPE_TEXT && g.length > 0) hasSubs = true;
                }
                final boolean fa = audioCount > 1, fs = hasSubs;
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
            public void onIsPlayingChanged(boolean isPlaying) {
                updateMediaSession(isPlaying);
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    showLoading(false);
                    if (!isVodType()) scheduleLiveHideBars();
                } else if (state == Player.STATE_BUFFERING) {
                    showLoading(true);
                } else if (!isVodType() &&
                        (state == Player.STATE_IDLE || state == Player.STATE_ENDED)) {
                    retryLive();
                }
            }

            @Override
            public void onPlayerError(androidx.media3.common.PlaybackException e) {
                if (!isVodType()) retryLive();
                else { showLoading(false); toast("Error al reproducir"); }
            }
        });
    }

    private void retryLive() {
        if (retryCount < 3) {
            retryCount++;
            runOnUiThread(() ->
                    txtLoading.setText("Reconectando (" + retryCount + "/3)..."));
            showLoading(true);
            // Retry sin recrear todo el player — solo seek al inicio
            handler.postDelayed(() -> {
                if (player != null) {
                    player.stop();
                    player.prepare();
                    player.play();
                } else {
                    initPlayer();
                }
            }, 3000);
        } else {
            showLoading(false);
            toast("No se pudo conectar con el canal");
        }
    }

    private void stopAndRelease() {
        handler.removeCallbacksAndMessages(null);
        if (player != null) {
            player.stop();
            player.release();
            player = null;
        }
    }

    // ─────────────────────────────────────────────
    //  CANAL SIGUIENTE / ANTERIOR
    // ─────────────────────────────────────────────

    private void navigateChannel(int dir) {
        if (channels.isEmpty() || channelIndex < 0) return;
        int next = channelIndex + dir;
        if (next < 0) next = channels.size() - 1;
        if (next >= channels.size()) next = 0;
        try {
            JSONObject ch = channels.get(next);
            channelIndex = next;
            url    = ch.optString("url", "");
            name   = ch.optString("name", "");
            itemId = ch.optString("id", "");
            liveTxtName.setText(name);
            retryCount = 0;
            showLiveBars();
            initPlayer();
        } catch (Exception e) {
            toast("Error al cambiar canal");
        }
    }

    // ─────────────────────────────────────────────
    //  VOD FULLSCREEN
    // ─────────────────────────────────────────────

    private void enterVodFullscreen() {
        isVodFullscreen = true;
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        vodScroll.setVisibility(View.GONE);
        vodTopBar.setVisibility(View.GONE);
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

    // ─────────────────────────────────────────────
    //  LIVE BARS
    // ─────────────────────────────────────────────

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

    // ─────────────────────────────────────────────
    //  AUDIO / SUBTÍTULOS
    // ─────────────────────────────────────────────

    private void showAudioTracks() {
        if (player == null) return;
        List<String> labels = new ArrayList<>(), langs = new ArrayList<>();
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() == C.TRACK_TYPE_AUDIO) {
                for (int i = 0; i < g.length; i++) {
                    String lang = g.getTrackFormat(i).language;
                    labels.add(lang != null && !lang.isEmpty()
                            ? lang.toUpperCase() : "Pista " + (labels.size() + 1));
                    langs.add(lang != null ? lang : "");
                }
            }
        }
        if (labels.isEmpty()) { toast("Sin pistas de audio"); return; }
        new AlertDialog.Builder(this).setTitle("Seleccionar audio")
                .setItems(labels.toArray(new String[0]), (d, w) ->
                        player.setTrackSelectionParameters(player.getTrackSelectionParameters()
                                .buildUpon().setPreferredAudioLanguage(langs.get(w)).build()))
                .show();
    }

    private void showSubtitleTracks() {
        if (player == null) return;
        List<String> labels = new ArrayList<>(), langs = new ArrayList<>();
        labels.add("Ninguno"); langs.add("");
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() == C.TRACK_TYPE_TEXT) {
                for (int i = 0; i < g.length; i++) {
                    String lang = g.getTrackFormat(i).language;
                    labels.add(lang != null && !lang.isEmpty()
                            ? lang.toUpperCase() : "Sub " + labels.size());
                    langs.add(lang != null ? lang : "");
                }
            }
        }
        if (labels.size() == 1) { toast("Sin subtítulos disponibles"); return; }
        new AlertDialog.Builder(this).setTitle("Subtítulos")
                .setItems(labels.toArray(new String[0]), (d, w) -> {
                    if (w == 0)
                        player.setTrackSelectionParameters(player.getTrackSelectionParameters()
                                .buildUpon()
                                .setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT).build());
                    else
                        player.setTrackSelectionParameters(player.getTrackSelectionParameters()
                                .buildUpon().setPreferredTextLanguage(langs.get(w)).build());
                }).show();
    }

    // ─────────────────────────────────────────────
    //  VOD INFO — URL parsing robusto
    // ─────────────────────────────────────────────

    private void fetchVodInfo() {
        new Thread(() -> {
            try {
                // Parsing robusto: funciona con cualquier formato de URL Xtream
                String apiUrl = buildVodInfoUrl(url, itemId);
                if (apiUrl == null) return;

                HttpURLConnection c = (HttpURLConnection) new URL(apiUrl).openConnection();
                c.setRequestProperty("User-Agent", USER_AGENT);
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);

                BufferedReader br = new BufferedReader(
                        new InputStreamReader(c.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();

                JSONObject root = new JSONObject(sb.toString());
                JSONObject info = root.optJSONObject("info");
                if (info == null) return;

                String plot   = info.optString("plot", "");
                String year   = info.optString("releasedate", info.optString("year", ""));
                String dur    = info.optString("duration", "");
                String rating = info.optString("rating", "");

                runOnUiThread(() -> {
                    vodTxtPlot.setText(!plot.isEmpty() ? plot : "Sin sinopsis disponible.");
                    if (!year.isEmpty()) {
                        vodTxtYear.setText(year.length() >= 4 ? year.substring(0, 4) : year);
                        vodTxtYear.setVisibility(View.VISIBLE);
                    }
                    if (!dur.isEmpty()) {
                        vodTxtDuration.setText(dur);
                        vodTxtDuration.setVisibility(View.VISIBLE);
                    }
                    if (!rating.isEmpty() && !rating.equals("0")) {
                        vodTxtRating.setText("\u2B50 " + rating);
                        vodTxtRating.setVisibility(View.VISIBLE);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() ->
                        vodTxtPlot.setText("Sin información disponible."));
            }
        }).start();
    }

    /**
     * Construye la URL de la API VOD de forma robusta.
     * Soporta formatos:
     *   http://host:port/movie/user/pass/ID.ext
     *   http://host:port/series/user/pass/ID.ext
     *   http://host:port/get.php?username=u&password=p&...
     */
    private String buildVodInfoUrl(String streamUrl, String vodId) {
        if (streamUrl == null || streamUrl.isEmpty()) return null;
        try {
            URL u = new URL(streamUrl);
            String host   = u.getProtocol() + "://" + u.getHost()
                    + (u.getPort() != -1 ? ":" + u.getPort() : "");
            String path   = u.getPath();   // /movie/user/pass/ID.ext
            String query  = u.getQuery();  // username=u&password=p&...

            String username = null, password = null;

            // Caso 1: /movie/user/pass/ID.ext  o  /series/user/pass/...
            if (path != null && (path.contains("/movie/") || path.contains("/series/"))) {
                String[] parts = path.split("/");
                // parts[0]="" parts[1]="movie" parts[2]=user parts[3]=pass parts[4]=ID
                if (parts.length >= 4) {
                    username = parts[2];
                    password = parts[3];
                }
            }

            // Caso 2: get.php?username=...&password=...
            if (username == null && query != null) {
                for (String param : query.split("&")) {
                    String[] kv = param.split("=", 2);
                    if (kv.length == 2) {
                        if (kv[0].equals("username")) username = kv[1];
                        if (kv[0].equals("password")) password = kv[1];
                    }
                }
            }

            if (username == null || password == null) return null;

            // Usar itemId si está disponible, si no extraerlo de la URL
            String id = (vodId != null && !vodId.isEmpty()) ? vodId : extractIdFromPath(path);
            if (id == null) return null;

            String action = (type != null && type.equals("series"))
                    ? "get_series_info&series_id=" : "get_vod_info&vod_id=";

            return host + "/player_api.php?username=" + username
                    + "&password=" + password + "&action=" + action + id;

        } catch (Exception e) {
            return null;
        }
    }

    private String extractIdFromPath(String path) {
        if (path == null) return null;
        // Último segmento sin extensión: /movie/user/pass/12345.mp4 → "12345"
        int slash = path.lastIndexOf('/');
        int dot   = path.lastIndexOf('.');
        if (slash >= 0 && dot > slash) {
            return path.substring(slash + 1, dot);
        }
        return null;
    }

    // ─────────────────────────────────────────────
    //  HELPERS
    // ─────────────────────────────────────────────

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

    private void showLoading(boolean show) {
        runOnUiThread(() ->
                loadingOverlay.setVisibility(show ? View.VISIBLE : View.GONE));
    }

    private void toast(String msg) {
        runOnUiThread(() ->
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    private void toggleFav(ImageButton btn) {
        isFav = !isFav; favChanged = true; favAdded = isFav;
        btn.setImageResource(isFav
                ? android.R.drawable.btn_star_big_on
                : android.R.drawable.btn_star_big_off);
        toast(isFav ? "Favorito guardado" : "Quitado de favoritos");
    }

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
        } catch (Exception e) {
            copyUrl();
        }
    }

    private void copyUrl() {
        ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                .setPrimaryClip(ClipData.newPlainText("url", url));
        toast("URL copiada");
    }

    // ─────────────────────────────────────────────
    //  HTTP CLIENT — User-Agent VLC + SSL permisivo
    // ─────────────────────────────────────────────

    @SuppressLint("TrustAllX509TrustManager")
    private OkHttpClient buildHttpClient() {
        try {
            X509TrustManager tm = new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a)
                        throws CertificateException {}
                public void checkServerTrusted(X509Certificate[] c, String a)
                        throws CertificateException {}
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            };
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, new TrustManager[]{tm}, new java.security.SecureRandom());

            return new OkHttpClient.Builder()
                    .sslSocketFactory(sc.getSocketFactory(), tm)
                    .hostnameVerifier((h, s) -> true)
                    .addInterceptor(chain -> chain.proceed(
                            chain.request().newBuilder()
                                    .header("User-Agent", USER_AGENT)
                                    .build()))
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build();
        } catch (Exception e) {
            return new OkHttpClient.Builder()
                    .addInterceptor(chain -> chain.proceed(
                            chain.request().newBuilder()
                                    .header("User-Agent", USER_AGENT)
                                    .build()))
                    .build();
        }
    }
}
