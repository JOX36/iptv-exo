package com.jox3.iptvexo.tv.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jox3.iptvexo.tv.data.CloudSync
import com.jox3.iptvexo.tv.data.Profile
import com.jox3.iptvexo.tv.data.ProfileStore
import com.jox3.iptvexo.tv.data.Profiles
import com.jox3.iptvexo.tv.ui.FocusSurface
import com.jox3.iptvexo.tv.ui.Jx
import com.jox3.iptvexo.tv.ui.TvTextField
import kotlinx.coroutines.delay

private sealed interface Mode {
    data object Pick : Mode
    data object Manage : Mode
    data class Edit(val profile: Profile?) : Mode
    data class Pin(val profile: Profile, val thenEdit: Boolean) : Mode
    data class Forgot(val profile: Profile) : Mode
}

private val PgDisplay = com.jox3.iptvexo.tv.ui.Display
private val PgBody = com.jox3.iptvexo.tv.ui.Body

/**
 * "¿Quién está viendo?": elegir, crear y editar perfiles (nombre, emoji, color y PIN opcional).
 * La misma pantalla sirve para la TV ([tv] = true, con el control) y para el celular (táctil).
 * [onCancel]: si se abrió desde el menú, Atrás vuelve al perfil que estaba.
 */
@Composable
fun ProfileGate(store: ProfileStore, tv: Boolean, onPick: (Profile) -> Unit, onCancel: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    var mode by remember { mutableStateOf<Mode>(Mode.Pick) }
    var rememberHere by remember { mutableStateOf(store.remembered != null) }

    // Perfiles creados en otro equipo: se bajan al mostrar esta pantalla
    LaunchedEffect(Unit) { CloudSync.syncProfiles(ctx, store) }

    fun choose(p: Profile) {
        Profiles.chosenThisLaunch[store.account.key] = p.id
        if (!tv) store.remembered = if (rememberHere) p.id else null
        onPick(p)
    }

    BackHandler(enabled = mode != Mode.Pick || onCancel != null) {
        if (mode == Mode.Pick) onCancel?.invoke() else mode = Mode.Pick
    }

    Box(Modifier.fillMaxSize().background(Jx.Bg)) {
        when (val m = mode) {
            Mode.Pick, Mode.Manage -> PickScreen(
                store, tv, manage = m == Mode.Manage, rememberHere = rememberHere,
                onRemember = { rememberHere = it },
                onProfile = { p ->
                    when {
                        m == Mode.Manage -> mode = if (p.hasPin) Mode.Pin(p, thenEdit = true) else Mode.Edit(p)
                        p.hasPin -> mode = Mode.Pin(p, thenEdit = false)
                        else -> choose(p)
                    }
                },
                onAdd = { mode = Mode.Edit(null) },
                onManage = { mode = if (m == Mode.Manage) Mode.Pick else Mode.Manage },
            )
            is Mode.Edit -> EditScreen(store, tv, m.profile, onDone = { mode = Mode.Manage })
            is Mode.Pin -> {
                var error by remember(m) { mutableStateOf("") }
                PinPad(tv, title = "PIN de ${m.profile.name}", subtitle = "Escribe los 4 números", error = error,
                    onComplete = { pin ->
                        if (store.checkPin(m.profile, pin)) {
                            if (m.thenEdit) mode = Mode.Edit(m.profile) else choose(m.profile)
                        } else error = "PIN incorrecto, intenta de nuevo"
                    },
                    onCancel = { mode = Mode.Pick },
                    extraLabel = "¿Olvidaste el PIN?", onExtra = { mode = Mode.Forgot(m.profile) })
            }
            is Mode.Forgot -> ForgotScreen(store, tv, m.profile, onDone = { mode = Mode.Pick })
        }
    }
}

// ───────────────────────── Elegir ─────────────────────────

@Composable
private fun PickScreen(
    store: ProfileStore, tv: Boolean, manage: Boolean, rememberHere: Boolean,
    onRemember: (Boolean) -> Unit, onProfile: (Profile) -> Unit, onAdd: () -> Unit, onManage: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(manage) { if (tv) { delay(120); runCatching { first.requestFocus() } } }
    val size = if (tv) 112.dp else 104.dp
    val tiles: List<@Composable () -> Unit> = buildList {
        store.list.forEachIndexed { i, p ->
            add {
                ProfileTile(tv, p, size, manage, if (i == 0) Modifier.focusRequester(first) else Modifier) { onProfile(p) }
            }
        }
        if (store.canAdd) add { AddTile(tv, size, onAdd) }
    }

    Column(
        Modifier.fillMaxSize().then(if (tv) Modifier else Modifier.verticalScroll(rememberScrollState()))
            .padding(horizontal = if (tv) 48.dp else 20.dp, vertical = if (tv) 36.dp else 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = if (tv) Arrangement.Center else Arrangement.Top,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt("JOX3", 22, Jx.Accent, display = true)
            Spacer(Modifier.width(6.dp))
            Txt("TV", 22, Jx.Text, display = true)
        }
        Spacer(Modifier.height(if (tv) 24.dp else 36.dp))
        Txt(if (manage) "Administrar perfiles" else "¿Quién está viendo?", if (tv) 34 else 30, Jx.Text, display = true)
        Spacer(Modifier.height(6.dp))
        Txt(if (manage) "Elige un perfil para cambiar su nombre, emoji, color o PIN"
            else "Cada perfil tiene su propio Seguir viendo y sus favoritos", if (tv) 14 else 14, Jx.Muted, center = true)
        Spacer(Modifier.height(if (tv) 32.dp else 32.dp))

        if (tv) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) { tiles.forEach { it() } }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
                tiles.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) { row.forEach { it() } }
                }
            }
        }

        Spacer(Modifier.height(if (tv) 32.dp else 36.dp))
        if (!tv && !manage) {
            Toggle(tv, "Recordar en este celular", "Entra directo a tu perfil", rememberHere) { onRemember(!rememberHere) }
            Spacer(Modifier.height(12.dp))
        }
        Btn(tv, if (manage) "Listo" else "Administrar perfiles", primary = manage, onClick = onManage)
    }
}

@Composable
private fun ProfileTile(tv: Boolean, p: Profile, size: Dp, manage: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Press(tv, onClick, modifier, scale = 1.08f) { f ->
        Column(Modifier.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Avatar(p.emoji, p.color, size, ring = f, lock = p.hasPin, edit = manage)
            Spacer(Modifier.height(10.dp))
            Txt(p.name, if (tv) 15 else 16, if (f || !tv) Jx.Text else Jx.Muted, bold = f, maxLines = 1)
        }
    }
}

@Composable
private fun AddTile(tv: Boolean, size: Dp, onClick: () -> Unit) {
    Press(tv, onClick, scale = 1.08f) { f ->
        Column(Modifier.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(size).border(if (f) 3.dp else 2.dp, if (f) Jx.Accent else Jx.Line, RoundedCornerShape(size * 0.22f)),
                contentAlignment = Alignment.Center,
            ) { Txt("+", (size.value * 0.42f).toInt(), if (f) Jx.Accent else Jx.Muted) }
            Spacer(Modifier.height(10.dp))
            Txt("Agregar", if (tv) 15 else 16, if (f) Jx.Text else Jx.Muted, maxLines = 1)
        }
    }
}

@Composable
private fun Avatar(emoji: String, color: Long, size: Dp, ring: Boolean = false, lock: Boolean = false, edit: Boolean = false) {
    val shape = RoundedCornerShape(size * 0.22f)
    Box(
        Modifier.size(size)
            .then(if (ring) Modifier.border(3.dp, Jx.Text, shape) else Modifier)
            .padding(if (ring) 5.dp else 0.dp)
            .clip(shape).background(Color(color)),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(emoji, style = TextStyle(fontSize = (size.value * 0.46f).sp, textAlign = TextAlign.Center))
        if (edit) Box(Modifier.fillMaxSize().background(Color(0x88070A0F)), contentAlignment = Alignment.Center) {
            BasicText("✏️", style = TextStyle(fontSize = (size.value * 0.26f).sp))
        }
        if (lock) Box(
            Modifier.align(Alignment.BottomEnd).padding(6.dp).size(size * 0.26f).background(Color(0xCC070A0F), CircleShape),
            contentAlignment = Alignment.Center,
        ) { BasicText("🔒", style = TextStyle(fontSize = (size.value * 0.13f).sp)) }
    }
}

// ───────────────────────── Crear / editar ─────────────────────────

@Composable
private fun EditScreen(store: ProfileStore, tv: Boolean, profile: Profile?, onDone: () -> Unit) {
    var name by remember { mutableStateOf(profile?.name.orEmpty()) }
    var emoji by remember { mutableStateOf(profile?.emoji ?: Profiles.EMOJIS.shuffled().first()) }
    var color by remember { mutableStateOf(profile?.color ?: Profiles.COLORS.shuffled().first()) }
    var newPin by remember { mutableStateOf<String?>(null) }
    var removePin by remember { mutableStateOf(false) }
    var pinStep by remember { mutableStateOf(0) }       // 0 nada · 1 escribir · 2 repetir
    var firstPin by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    val hasPin = newPin != null || (profile?.hasPin == true && !removePin)

    if (pinStep > 0) {
        BackHandler { pinStep = 0 }
        PinPad(tv, title = if (pinStep == 1) "Nuevo PIN" else "Repite el PIN",
            subtitle = if (pinStep == 1) "4 números que solo sepa esta persona" else "Para confirmar", error = pinError,
            onComplete = { pin ->
                if (pinStep == 1) { firstPin = pin; pinStep = 2; pinError = "" }
                else if (pin == firstPin) { newPin = pin; removePin = false; pinStep = 0; pinError = "" }
                else { pinStep = 1; pinError = "No coinciden, escríbelo otra vez" }
            },
            onCancel = { pinStep = 0; pinError = "" })
        return
    }

    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (tv) { delay(120); runCatching { first.requestFocus() } } }

    fun save() {
        if (profile == null) store.add(name, emoji, color, newPin)
        else store.update(profile, name, emoji, color, when {
            newPin != null -> store.hashPin(profile.id, newPin!!)
            removePin -> ""
            else -> profile.pinHash
        })
        onDone()
    }

    val form: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(if (tv) 14.dp else 16.dp)) {
            if (tv) TvTextField("Nombre", name, { name = it.take(20) }, Modifier.widthIn(max = 420.dp).focusRequester(first),
                placeholder = "Ej.: María")
            else PhoneField("Nombre", name, { name = it.take(20) }, placeholder = "Ej.: María")

            Txt("Emoji", 13, Jx.TextSoft)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Profiles.EMOJIS.chunked(8).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { e ->
                            Press(tv, { emoji = e }, shape = RoundedCornerShape(12.dp)) { f ->
                                Box(
                                    Modifier.size(if (tv) 40.dp else 36.dp).clip(RoundedCornerShape(12.dp))
                                        .background(if (e == emoji) Color(color) else Jx.Surface)
                                        .then(if (f) Modifier.border(2.dp, Jx.Accent, RoundedCornerShape(12.dp)) else Modifier),
                                    contentAlignment = Alignment.Center,
                                ) { BasicText(e, style = TextStyle(fontSize = if (tv) 20.sp else 19.sp)) }
                            }
                        }
                    }
                }
            }

            Txt("Color", 13, Jx.TextSoft)
            Row(horizontalArrangement = Arrangement.spacedBy(if (tv) 12.dp else 8.dp)) {
                Profiles.COLORS.forEach { c ->
                    Press(tv, { color = c }, shape = CircleShape) { f ->
                        Box(
                            Modifier.size(if (tv) 36.dp else 34.dp)
                                .then(if (c == color || f) Modifier.border(3.dp, if (f) Jx.Accent else Jx.Text, CircleShape) else Modifier)
                                .padding(5.dp).background(Color(c), CircleShape)
                        )
                    }
                }
            }

            Txt(if (hasPin) "🔒 Este perfil pide PIN para entrar" else "Sin PIN: cualquiera puede entrar", 13, Jx.TextSoft)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Btn(tv, if (hasPin) "Cambiar PIN" else "Poner PIN") { pinStep = 1; pinError = "" }
                if (hasPin) Btn(tv, "Quitar PIN") { newPin = null; removePin = true }
            }

            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Btn(tv, "Guardar", primary = true) { save() }
                Btn(tv, "Cancelar", onClick = onDone)
                if (profile != null && !profile.isMain) {
                    Btn(tv, if (confirmDelete) "¿Seguro? Oprime otra vez" else "Eliminar perfil", danger = true) {
                        if (confirmDelete) { store.delete(profile); onDone() } else confirmDelete = true
                    }
                }
            }
            if (profile?.isMain == true) Txt("El perfil principal no se puede eliminar", 12, Jx.Faint)
        }
    }

    if (tv) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 28.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(48.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Avatar(emoji, color, 150.dp, lock = hasPin)
                Spacer(Modifier.height(12.dp))
                Txt(name.ifBlank { "Nuevo perfil" }, 18, Jx.Text, bold = true, maxLines = 1)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Txt(if (profile == null) "Agregar perfil" else "Editar perfil", 30, Jx.Text, display = true)
                Spacer(Modifier.height(14.dp))
                form()
            }
        }
    } else {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Txt(if (profile == null) "Agregar perfil" else "Editar perfil", 26, Jx.Text, display = true)
            Spacer(Modifier.height(18.dp))
            Avatar(emoji, color, 112.dp, lock = hasPin)
            Spacer(Modifier.height(20.dp))
            Box(Modifier.fillMaxWidth()) { form() }
        }
    }
}

// ───────────────────────── PIN ─────────────────────────

@Composable
private fun PinPad(
    tv: Boolean, title: String, subtitle: String, error: String,
    onComplete: (String) -> Unit, onCancel: () -> Unit,
    extraLabel: String? = null, onExtra: () -> Unit = {},
) {
    var entered by remember(title) { mutableStateOf("") }
    fun add(d: Char) {
        if (entered.length >= 4) return
        entered += d
        if (entered.length == 4) { val pin = entered; entered = ""; onComplete(pin) }
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(title) { if (tv) { delay(120); runCatching { first.requestFocus() } } }

    Column(
        Modifier.fillMaxSize()
            .onPreviewKeyEvent { ev ->
                // Los números del control remoto también sirven
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val kc = ev.nativeKeyEvent.keyCode
                when (kc) {
                    in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_9 -> { add('0' + (kc - android.view.KeyEvent.KEYCODE_0)); true }
                    android.view.KeyEvent.KEYCODE_DEL -> { entered = entered.dropLast(1); true }
                    else -> false
                }
            }
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Txt(title, if (tv) 28 else 26, Jx.Text, display = true, center = true)
        Spacer(Modifier.height(6.dp))
        Txt(subtitle, 14, Jx.Muted, center = true)
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(4) { i ->
                Box(Modifier.size(18.dp).clip(CircleShape)
                    .background(if (i < entered.length) Jx.Accent else Jx.Line))
            }
        }
        Spacer(Modifier.height(10.dp))
        Txt(error, 13, Jx.Live, center = true)
        Spacer(Modifier.height(10.dp))
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "✕")
        Column(verticalArrangement = Arrangement.spacedBy(if (tv) 8.dp else 10.dp)) {
            keys.chunked(3).forEachIndexed { r, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(if (tv) 10.dp else 14.dp)) {
                    row.forEachIndexed { c, k ->
                        val mod = if (r == 0 && c == 0) Modifier.focusRequester(first) else Modifier
                        Press(tv, {
                            when (k) {
                                "⌫" -> entered = entered.dropLast(1)
                                "✕" -> onCancel()
                                else -> add(k[0])
                            }
                        }, mod, shape = CircleShape, scale = 1.1f) { f ->
                            Box(
                                Modifier.size(if (tv) 52.dp else 68.dp).clip(CircleShape)
                                    .background(if (f) Jx.Text else Jx.Surface),
                                contentAlignment = Alignment.Center,
                            ) { Txt(k, if (tv) 20 else 24, if (f) Jx.Bg else Jx.Text, display = true) }
                        }
                    }
                }
            }
        }
        if (extraLabel != null) {
            Spacer(Modifier.height(14.dp))
            Btn(tv, extraLabel, onClick = onExtra)
        }
    }
}

/** Sin el PIN: con la contraseña de la cuenta IPTV se quita el PIN del perfil. */
@Composable
private fun ForgotScreen(store: ProfileStore, tv: Boolean, p: Profile, onDone: () -> Unit) {
    var pass by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (tv) { delay(120); runCatching { first.requestFocus() } } }
    fun check() {
        if (pass == store.account.pass) { store.update(p, p.name, p.emoji, p.color, ""); onDone() }
        else error = "La contraseña no coincide"
    }
    Column(
        Modifier.fillMaxSize().padding(horizontal = if (tv) 120.dp else 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Txt("¿Olvidaste el PIN de ${p.name}?", if (tv) 28 else 24, Jx.Text, display = true, center = true)
        Spacer(Modifier.height(8.dp))
        Txt("Escribe la contraseña de la cuenta IPTV y el PIN se quitará.", 14, Jx.Muted, center = true)
        Spacer(Modifier.height(20.dp))
        Box(Modifier.widthIn(max = 420.dp)) {
            if (tv) TvTextField("Contraseña de la cuenta", pass, { pass = it }, Modifier.focusRequester(first),
                password = true, onDone = { check() })
            else PhoneField("Contraseña de la cuenta", pass, { pass = it }, password = true)
        }
        Spacer(Modifier.height(8.dp))
        Txt(error, 13, Jx.Live, center = true)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn(tv, "Quitar PIN", primary = true) { check() }
            Btn(tv, "Cancelar", onClick = onDone)
        }
    }
}

// ───────────────────────── Piezas ─────────────────────────

/** Elemento que se elige con el control (TV) o con el dedo (celular). */
@Composable
private fun Press(
    tv: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp), scale: Float = 1.04f,
    content: @Composable (focused: Boolean) -> Unit,
) {
    if (tv) {
        var focused by remember { mutableStateOf(false) }
        FocusSurface(
            onClick = onClick, modifier = modifier.onFocusChanged { focused = it.isFocused }, shape = shape,
            focusedScale = scale, container = Color.Transparent, focusedContainer = Color.Transparent, ring = false,
        ) { content(focused) }
    } else {
        Box(modifier.clip(shape).clickable(onClick = onClick)) { content(false) }
    }
}

@Composable
private fun Btn(tv: Boolean, label: String, primary: Boolean = false, danger: Boolean = false, onClick: () -> Unit) {
    Press(tv, onClick, shape = RoundedCornerShape(12.dp)) { f ->
        val bg = when {
            f -> Jx.Text
            primary -> Jx.Accent
            else -> Jx.Surface
        }
        val fg = when {
            f || primary -> Jx.Bg
            danger -> Jx.Live
            else -> Jx.TextSoft
        }
        Box(Modifier.clip(RoundedCornerShape(12.dp)).background(bg)
            .padding(horizontal = if (tv) 18.dp else 18.dp, vertical = if (tv) 10.dp else 12.dp)) {
            Txt(label, if (tv) 14 else 15, fg, bold = true, maxLines = 1)
        }
    }
}

@Composable
private fun Toggle(tv: Boolean, title: String, sub: String, on: Boolean, onClick: () -> Unit) {
    Press(tv, onClick, Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { f ->
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (f) Jx.SurfaceHi else Jx.Surface)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Txt(title, 15, Jx.Text, bold = true)
                Txt(sub, 12, Jx.Muted)
            }
            Box(Modifier.size(width = 46.dp, height = 26.dp).clip(RoundedCornerShape(13.dp))
                .background(if (on) Jx.Accent else Jx.Line).padding(3.dp)) {
                Box(Modifier.align(if (on) Alignment.CenterEnd else Alignment.CenterStart).size(20.dp)
                    .background(if (on) Jx.OnAccent else Jx.Faint, CircleShape))
            }
        }
    }
}

@Composable
private fun PhoneField(label: String, value: String, onChange: (String) -> Unit, placeholder: String = "", password: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Txt(label, 13, Jx.TextSoft)
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = TextStyle(color = Jx.Text, fontSize = 16.sp, fontFamily = PgBody),
            cursorBrush = SolidColor(Jx.Accent),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else KeyboardType.Text),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Jx.Surface)
                .border(1.dp, Jx.Line, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 14.dp),
            decorationBox = { inner ->
                Box { if (value.isEmpty()) Txt(placeholder, 16, Jx.Faint); inner() }
            },
        )
    }
}

@Composable
private fun Txt(
    text: String, size: Int, color: Color, display: Boolean = false, bold: Boolean = false,
    center: Boolean = false, maxLines: Int = Int.MAX_VALUE,
) {
    BasicText(
        text, maxLines = maxLines,
        style = TextStyle(
            color = color, fontSize = size.sp, fontFamily = if (display) PgDisplay else PgBody,
            fontWeight = if (display) FontWeight.Bold else if (bold) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = if (center) TextAlign.Center else TextAlign.Start,
        ),
    )
}
