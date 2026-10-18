package com.jox3.iptvexo.tv.ui

import android.annotation.SuppressLint
import android.graphics.Color as AColor
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp

/** Lo que la interfaz puede pedirle al tráiler (pausa, adelantar…). */
class TrailerControl {
    internal var web: WebView? = null
    private fun js(code: String) { web?.evaluateJavascript("try{$code}catch(e){}", null) }
    fun toggle() = js("if(p.getPlayerState()==1){p.pauseVideo()}else{p.playVideo()}")
    fun seekBy(sec: Int) = js("p.seekTo(Math.max(0,p.getCurrentTime()+($sec)),true)")
    fun unmute() = js("p.unMute();p.setVolume(100)")
}

/**
 * Tráiler de YouTube dentro de la app, con el reproductor oficial de YouTube
 * (IFrame API en un WebView): así se permite en otras apps.
 *
 * - [muted] = true: de fondo, sin sonido, sin controles y en bucle (estilo Netflix).
 * - [muted] = false: pantalla completa con sonido; los controles los pone la interfaz.
 *
 * Avisa con [onPlaying] cuando de verdad empieza (para mostrarlo con un fundido) y con
 * [onError] si el video no se deja poner fuera de YouTube (entonces se usa la imagen o
 * la app de YouTube).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TrailerPlayer(
    videoKey: String,
    muted: Boolean,
    modifier: Modifier = Modifier,
    control: TrailerControl? = null,
    touchControls: Boolean = false,
    onPlaying: () -> Unit = {},
    onEnded: () -> Unit = {},
    onError: (Int) -> Unit = {},
) {
    val main = remember { Handler(Looper.getMainLooper()) }
    val ctl = control ?: remember { TrailerControl() }
    val bridge = remember {
        object {
            var playing: () -> Unit = {}
            var ended: () -> Unit = {}
            var error: (Int) -> Unit = {}
            @JavascriptInterface fun state(s: Int) {
                main.post { if (s == 1) playing() else if (s == 0) ended() }
            }
            @JavascriptInterface fun fail(code: Int) { main.post { error(code) } }
        }
    }
    bridge.playing = onPlaying; bridge.ended = onEnded; bridge.error = onError

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundColor(AColor.BLACK)
                // Con el control remoto el foco lo maneja la app, nunca la página
                isFocusable = touchControls
                isFocusableInTouchMode = touchControls
                if (!touchControls) {
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    setOnTouchListener { _, _ -> true }   // de fondo: los toques no hacen nada
                }
                overScrollMode = View.OVER_SCROLL_NEVER
                isVerticalScrollBarEnabled = false; isHorizontalScrollBarEnabled = false
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                webChromeClient = WebChromeClient()
                webViewClient = WebViewClient()
                addJavascriptInterface(bridge, "Android")
                // YouTube pide un origen válido para sus reproductores fuera de youtube.com
                val origin = "https://" + ctx.packageName
                loadDataWithBaseURL(origin, html(videoKey, muted, touchControls, origin), "text/html", "utf-8", null)
                ctl.web = this
            }
        },
        modifier = modifier,
    )
    DisposableEffect(videoKey) {
        onDispose {
            ctl.web?.let { w ->
                runCatching { w.stopLoading(); w.loadUrl("about:blank"); w.removeJavascriptInterface("Android"); w.destroy() }
            }
            ctl.web = null
        }
    }
}

private fun html(key: String, muted: Boolean, controls: Boolean, origin: String): String {
    val k = key.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(20)
    val m = if (muted) 1 else 0
    val c = if (controls) 1 else 0
    val loop = if (muted) ",loop:1,playlist:'$k'" else ""
    return """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}#p{position:absolute;inset:0;width:100%;height:100%}</style></head>
<body><div id="p"></div>
<script src="https://www.youtube.com/iframe_api"></script>
<script>
var p;
function onYouTubeIframeAPIReady(){
  p=new YT.Player('p',{videoId:'$k',width:'100%',height:'100%',
    playerVars:{autoplay:1,mute:$m,controls:$c,playsinline:1,rel:0,modestbranding:1,iv_load_policy:3,fs:0,disablekb:1,cc_load_policy:0,origin:'$origin',widget_referrer:'$origin'$loop},
    events:{
      onReady:function(e){ if($m){e.target.mute();} e.target.playVideo(); },
      onStateChange:function(e){ Android.state(e.data); },
      onError:function(e){ Android.fail(e.data); }
    }});
}
setTimeout(function(){ if(!p||!p.getPlayerState){ Android.fail(-1); } }, 12000);
</script></body></html>"""
}

/**
 * Tráiler a pantalla completa en la TV, con sonido. Con el control: OK o ⏯ pausa,
 * ◀▶ 10 segundos, Atrás cierra. Si el video no se deja poner aquí, se abre YouTube.
 */
@Composable
fun TvTrailerFullscreen(videoKey: String, title: String, onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val ctl = remember { TrailerControl() }
    val req = remember { androidx.compose.ui.focus.FocusRequester() }
    var hint by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }
    var started by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.activity.compose.BackHandler(onBack = onClose)
    androidx.compose.runtime.LaunchedEffect(hint) { if (hint) { kotlinx.coroutines.delay(4000); hint = false } }
    androidx.compose.foundation.layout.Box(
        Modifier.fillMaxSize()
            .then(Modifier.background(androidx.compose.ui.graphics.Color.Black))
            .focusRequester(req)
            .onKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (ev.nativeKeyEvent.keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER,
                    android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, android.view.KeyEvent.KEYCODE_MEDIA_PLAY,
                    android.view.KeyEvent.KEYCODE_MEDIA_PAUSE -> { ctl.toggle(); hint = true; true }
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_MEDIA_REWIND -> { ctl.seekBy(-10); hint = true; true }
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT, android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { ctl.seekBy(10); hint = true; true }
                    android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN -> { hint = true; true }
                    else -> false
                }
            }
            .focusable()
    ) {
        TrailerPlayer(videoKey, muted = false, modifier = Modifier.fillMaxSize(), control = ctl,
            onPlaying = { started = true },
            onEnded = onClose,
            onError = {
                // No se deja reproducir fuera de YouTube: se abre la app de YouTube
                onClose()
                com.jox3.iptvexo.tv.data.Tmdb.openTrailer(ctx, videoKey)
            })
        if (!started) androidx.tv.material3.Text("Cargando tráiler…", style = Type.Label, color = Jx.TextSoft,
            modifier = Modifier.align(androidx.compose.ui.Alignment.Center))
        if (hint) androidx.compose.foundation.layout.Column(
            Modifier.align(androidx.compose.ui.Alignment.BottomStart)
                .then(Modifier.background(androidx.compose.ui.graphics.Color(0xB3000000)))
                .then(Modifier.padding(horizontal = Safe.H, vertical = 18.dp)),
        ) {
            androidx.tv.material3.Text("Tráiler · $title", style = Type.H2, color = Jx.Text, maxLines = 1)
            androidx.tv.material3.Text("OK: pausa   ◀▶: 10 segundos   Atrás: cerrar", style = Type.Small, color = Jx.Muted)
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { kotlinx.coroutines.delay(80); runCatching { req.requestFocus() } }
}
