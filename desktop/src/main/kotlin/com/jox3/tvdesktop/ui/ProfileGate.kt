package com.jox3.tvdesktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jox3.tvdesktop.data.CloudSync
import com.jox3.tvdesktop.data.Profile
import com.jox3.tvdesktop.data.ProfileStore
import com.jox3.tvdesktop.data.Profiles

private sealed interface GateMode {
    data object Pick : GateMode
    data object Manage : GateMode
    data class Edit(val profile: Profile?) : GateMode
    data class Pin(val profile: Profile, val thenEdit: Boolean) : GateMode
    data class Forgot(val profile: Profile) : GateMode
    data class VaultSetup(val profile: Profile) : GateMode
}

/**
 * "¿Quién está viendo?": se muestra siempre al abrir el programa (como en el TV Box).
 * Perfiles con emoji, color y PIN opcional de 4 números. Hasta 5. [onCancel] (si hay) vuelve
 * al perfil que estaba. Desde "Editar perfil" se guardan sus servidores con un código JOX3.
 */
@Composable
fun ProfileGate(
    store: ProfileStore,
    accounts: com.jox3.tvdesktop.data.AccountStore,
    onPick: (Profile) -> Unit,
    onCancel: (() -> Unit)? = null,
) {
    var mode by remember { mutableStateOf<GateMode>(GateMode.Pick) }

    // Perfiles creados en otro equipo: se bajan al mostrar esta pantalla
    LaunchedEffect(Unit) { CloudSync.syncProfiles(store) }

    fun choose(p: Profile) {
        val chosen = store.byId(p.id) ?: p
        Profiles.current = chosen
        onPick(chosen)
    }

    // Esc: atrás (o volver al perfil que estaba)
    DisposableEffect(mode) {
        val prev = KeyRouter.handler
        KeyRouter.handler = { ev ->
            if (ev.type == KeyEventType.KeyDown && ev.key == Key.Escape && !(mode is GateMode.Pin)) {
                when {
                    mode != GateMode.Pick -> { mode = if (mode is GateMode.Edit || mode is GateMode.VaultSetup) GateMode.Manage else GateMode.Pick; true }
                    onCancel != null -> { onCancel(); true }
                    else -> false
                }
            } else prev?.invoke(ev) ?: false
        }
        onDispose { KeyRouter.handler = prev }
    }

    Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Jx.Accent.copy(alpha = 0.08f), Jx.Bg), radius = 1600f))) {
        when (val m = mode) {
            GateMode.Pick, GateMode.Manage -> PickScreen(
                store, manage = m == GateMode.Manage,
                onProfile = { p ->
                    when {
                        m == GateMode.Manage -> mode = if (p.hasPin) GateMode.Pin(p, thenEdit = true) else GateMode.Edit(p)
                        p.hasPin -> mode = GateMode.Pin(p, thenEdit = false)
                        else -> choose(p)
                    }
                },
                onAdd = { mode = GateMode.Edit(null) },
                onManage = { mode = if (m == GateMode.Manage) GateMode.Pick else GateMode.Manage },
                onCancel = onCancel,
            )
            is GateMode.Edit -> EditScreen(store, m.profile, onDone = { mode = GateMode.Manage }, onVault = { p -> mode = GateMode.VaultSetup(p) })
            is GateMode.VaultSetup -> VaultScreen(store, accounts, m.profile, onDone = { mode = GateMode.Manage })
            is GateMode.Pin -> {
                var error by remember(m) { mutableStateOf("") }
                PinPad(
                    title = "PIN de ${m.profile.name}", subtitle = "Escribe los 4 números (también con el teclado)", error = error,
                    onComplete = { pin ->
                        if (store.checkPin(m.profile, pin)) {
                            if (m.thenEdit) mode = GateMode.Edit(store.byId(m.profile.id) ?: m.profile) else choose(m.profile)
                        } else error = "PIN incorrecto, intenta de nuevo"
                    },
                    onCancel = { mode = GateMode.Pick },
                    extraLabel = "¿Olvidaste el PIN?", onExtra = { mode = GateMode.Forgot(m.profile) },
                )
            }
            is GateMode.Forgot -> ForgotScreen(store, m.profile, onDone = { mode = GateMode.Pick })
        }
    }
}

// ───────────────────────── Elegir ─────────────────────────

@Composable
private fun PickScreen(
    store: ProfileStore, manage: Boolean, onProfile: (Profile) -> Unit, onAdd: () -> Unit, onManage: () -> Unit,
    onCancel: (() -> Unit)?,
) {
    Column(
        Modifier.fillMaxSize().padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Brand(iconSize = 40.dp, textSize = 26)
        Spacer(Modifier.height(30.dp))
        Text(if (manage) "Administrar perfiles" else "¿Quién está viendo?", color = Jx.Text, fontFamily = Exo2,
            fontWeight = FontWeight.SemiBold, fontSize = 36.sp)
        Spacer(Modifier.height(6.dp))
        Text(if (manage) "Elige un perfil para cambiar su nombre, emoji, color o PIN"
            else "Cada perfil tiene su propio Seguir viendo y sus favoritos", color = Jx.Muted, fontFamily = Exo2, fontSize = 15.sp)
        Spacer(Modifier.height(40.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            store.list.forEach { p -> ProfileTile(p, 130.dp, manage) { onProfile(p) } }
            if (store.canAdd) AddTile(130.dp, onAdd)
        }
        Spacer(Modifier.height(40.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (manage) PillButton("Listo", onManage) else GhostButton("Administrar perfiles", onManage)
            if (onCancel != null && !manage) GhostButton("Volver", onCancel)
        }
    }
}

@Composable
private fun ProfileTile(p: Profile, size: Dp, manage: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Column(
        Modifier.hoverable(source).clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand).padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Avatar(p.emoji, p.color, size, ring = hovered, lock = p.hasPin, edit = manage)
        Spacer(Modifier.height(12.dp))
        Text(p.name, color = if (hovered) Jx.Text else Jx.TextSoft, fontFamily = Exo2, fontSize = 16.sp,
            fontWeight = if (hovered) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = size + 20.dp))
    }
}

@Composable
private fun AddTile(size: Dp, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Column(
        Modifier.hoverable(source).clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand).padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(size).border(if (hovered) 3.dp else 2.dp, if (hovered) Jx.Accent else Jx.Line, RoundedCornerShape(size * 0.22f)),
            contentAlignment = Alignment.Center,
        ) { Text("+", color = if (hovered) Jx.Accent else Jx.Muted, fontSize = (size.value * 0.42f).sp, fontFamily = Exo2) }
        Spacer(Modifier.height(12.dp))
        Text("Agregar", color = if (hovered) Jx.Text else Jx.Muted, fontFamily = Exo2, fontSize = 16.sp)
    }
}

/** Avatar: emoji sobre un fondo de color, con candado si tiene PIN. */
@Composable
fun Avatar(emoji: String, color: Long, size: Dp, ring: Boolean = false, lock: Boolean = false, edit: Boolean = false) {
    val shape = RoundedCornerShape(size * 0.22f)
    Box(
        Modifier.size(size)
            .then(if (ring) Modifier.border(3.dp, Jx.Text, shape) else Modifier)
            .padding(if (ring) 5.dp else 0.dp)
            .clip(shape).background(Color(color)),
        contentAlignment = Alignment.Center,
    ) {
        Text(emoji, fontSize = (size.value * 0.46f).sp, textAlign = TextAlign.Center)
        if (edit) Box(Modifier.fillMaxSize().background(Color(0x88070A0F)), contentAlignment = Alignment.Center) {
            Text("✏️", fontSize = (size.value * 0.26f).sp)
        }
        if (lock) Box(
            Modifier.align(Alignment.BottomEnd).padding(6.dp).size(size * 0.26f).background(Color(0xCC070A0F), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text("🔒", fontSize = (size.value * 0.13f).sp) }
    }
}

// ───────────────────────── Crear / editar ─────────────────────────

@Composable
private fun EditScreen(store: ProfileStore, profile: Profile?, onDone: () -> Unit, onVault: (Profile) -> Unit) {
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
        PinPad(
            title = if (pinStep == 1) "Nuevo PIN" else "Repite el PIN",
            subtitle = if (pinStep == 1) "4 números que solo sepa esta persona" else "Para confirmar", error = pinError,
            onComplete = { pin ->
                if (pinStep == 1) { firstPin = pin; pinStep = 2; pinError = "" }
                else if (pin == firstPin) { newPin = pin; removePin = false; pinStep = 0; pinError = "" }
                else { pinStep = 1; pinError = "No coinciden, escríbelo otra vez" }
            },
            onCancel = { pinStep = 0; pinError = "" },
        )
        return
    }

    fun save() {
        if (profile == null) store.add(name, emoji, color, newPin)
        else store.update(profile, name, emoji, color, when {
            newPin != null -> Profiles.hashPin(profile.id, newPin!!)
            removePin -> ""
            else -> profile.pinHash
        })
        onDone()
    }

    Row(
        Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 36.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(56.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Avatar(emoji, color, 170.dp, lock = hasPin)
            Spacer(Modifier.height(14.dp))
            Text(name.ifBlank { "Nuevo perfil" }, color = Jx.Text, fontFamily = Exo2, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, maxLines = 1)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(if (profile == null) "Agregar perfil" else "Editar perfil", color = Jx.Text, fontFamily = Exo2,
                fontWeight = FontWeight.SemiBold, fontSize = 30.sp)
            GateField("Nombre", name, { name = it.take(20) }, "Ej.: María")
            Text("Emoji", color = Jx.TextSoft, fontFamily = Exo2, fontSize = 13.sp)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Profiles.EMOJIS.chunked(8).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { e ->
                            Box(
                                Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                                    .background(if (e == emoji) Color(color) else Jx.Surface)
                                    .clickable { emoji = e }.pointerHoverIcon(PointerIcon.Hand),
                                contentAlignment = Alignment.Center,
                            ) { Text(e, fontSize = 21.sp) }
                        }
                    }
                }
            }
            Text("Color", color = Jx.TextSoft, fontFamily = Exo2, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Profiles.COLORS.forEach { c ->
                    Box(
                        Modifier.size(38.dp).clip(CircleShape)
                            .then(if (c == color) Modifier.border(3.dp, Jx.Text, CircleShape) else Modifier)
                            .clickable { color = c }.pointerHoverIcon(PointerIcon.Hand)
                            .padding(5.dp).background(Color(c), CircleShape)
                    )
                }
            }
            Text(if (hasPin) "🔒 Este perfil pide PIN para entrar" else "Sin PIN: cualquiera puede entrar",
                color = Jx.TextSoft, fontFamily = Exo2, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton(if (hasPin) "Cambiar PIN" else "Poner PIN") { pinStep = 1; pinError = "" }
                if (hasPin) GhostButton("Quitar PIN") { newPin = null; removePin = true }
            }
            if (profile != null) {
                val withVault = (store.byId(profile.id) ?: profile).vaultId.isNotEmpty()
                Text(if (withVault) "☁️ Sus servidores se guardan con un código JOX3" else "☁️ Lleva tus servidores a cualquier equipo con un código",
                    color = Jx.TextSoft, fontFamily = Exo2, fontSize = 13.sp)
                GhostButton(if (withVault) "Mis servidores en la nube" else "Guardar mis servidores en la nube") { onVault(store.byId(profile.id) ?: profile) }
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                PillButton("Guardar") { save() }
                GhostButton("Cancelar", onDone)
                if (profile != null && !profile.isMain) {
                    DangerButton(if (confirmDelete) "¿Seguro? Haz clic otra vez" else "Eliminar perfil") {
                        if (confirmDelete) { store.delete(profile); onDone() } else confirmDelete = true
                    }
                }
            }
            if (profile?.isMain == true) Text("El perfil principal no se puede eliminar", color = Jx.Faint, fontFamily = Exo2, fontSize = 12.sp)
        }
    }
}

// ───────────────────────── PIN ─────────────────────────

/** Teclado de 4 números (con mouse o con el teclado del PC). */
@Composable
fun PinPad(
    title: String, subtitle: String, error: String,
    onComplete: (String) -> Unit, onCancel: () -> Unit,
    extraLabel: String? = null, onExtra: () -> Unit = {},
) {
    var entered by remember(title) { mutableStateOf("") }
    fun add(d: Char) {
        if (entered.length >= 4) return
        entered += d
        if (entered.length == 4) { val pin = entered; entered = ""; onComplete(pin) }
    }
    // Números del teclado (fila de arriba y teclado numérico), Retroceso y Esc
    DisposableEffect(title) {
        val prev = KeyRouter.handler
        KeyRouter.handler = { ev ->
            if (ev.type != KeyEventType.KeyDown) false
            else when (ev.key) {
                Key.Backspace -> { entered = entered.dropLast(1); true }
                Key.Escape -> { onCancel(); true }
                else -> digitKey(ev.key)?.let { add('0' + it); true } ?: false
            }
        }
        onDispose { KeyRouter.handler = prev }
    }
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(title, color = Jx.Text, fontFamily = Exo2, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(subtitle, color = Jx.Muted, fontFamily = Exo2, fontSize = 14.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(4) { i -> Box(Modifier.size(18.dp).clip(CircleShape).background(if (i < entered.length) Jx.Accent else Jx.Line)) }
        }
        Spacer(Modifier.height(10.dp))
        Text(error, color = Jx.Live, fontFamily = Exo2, fontSize = 13.sp)
        Spacer(Modifier.height(10.dp))
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "✕")
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            keys.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    row.forEach { k ->
                        val source = remember { MutableInteractionSource() }
                        val hovered by source.collectIsHoveredAsState()
                        Box(
                            Modifier.size(64.dp).clip(CircleShape).background(if (hovered) Jx.Text else Jx.Surface)
                                .hoverable(source).clickable {
                                    when (k) { "⌫" -> entered = entered.dropLast(1); "✕" -> onCancel(); else -> add(k[0]) }
                                }.pointerHoverIcon(PointerIcon.Hand),
                            contentAlignment = Alignment.Center,
                        ) { Text(k, color = if (hovered) Jx.Bg else Jx.Text, fontFamily = Exo2, fontWeight = FontWeight.SemiBold, fontSize = 22.sp) }
                    }
                }
            }
        }
        if (extraLabel != null) {
            Spacer(Modifier.height(16.dp))
            GhostButton(extraLabel, onExtra)
        }
    }
}

/** Sin el PIN: con la contraseña de la cuenta IPTV se quita el PIN del perfil. */
@Composable
private fun ForgotScreen(store: ProfileStore, p: Profile, onDone: () -> Unit) {
    var pass by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    fun check() {
        if (pass == store.account.pass) { store.update(p, p.name, p.emoji, p.color, ""); onDone() }
        else error = "La contraseña no coincide"
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 120.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("¿Olvidaste el PIN de ${p.name}?", color = Jx.Text, fontFamily = Exo2, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text("Escribe la contraseña de la cuenta IPTV y el PIN se quitará.", color = Jx.Muted, fontFamily = Exo2, fontSize = 14.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Box(Modifier.widthIn(max = 420.dp)) { GateField("Contraseña de la cuenta", pass, { pass = it }, "", password = true, onEnter = { check() }) }
        Spacer(Modifier.height(8.dp))
        Text(error, color = Jx.Live, fontFamily = Exo2, fontSize = 13.sp)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("Quitar PIN") { check() }
            GhostButton("Cancelar", onDone)
        }
    }
}

// ───────────────────────── Piezas ─────────────────────────

/** Campo de texto con etiqueta. Mientras se escribe, los atajos de teclado no actúan. */
@Composable
fun GateField(label: String, value: String, onChange: (String) -> Unit, hint: String, password: Boolean = false, onEnter: () -> Unit = {}) {
    DisposableEffect(Unit) { onDispose { Typing.active = false } }
    Column(Modifier.widthIn(max = 460.dp)) {
        Text(label, color = Jx.TextSoft, fontFamily = Exo2, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(12.dp)).background(Jx.Surface)
                .border(1.dp, Jx.Line, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty() && hint.isNotEmpty()) Text(hint, color = Jx.Faint, fontFamily = Exo2, fontSize = 15.sp)
            BasicTextField(
                value, onChange, singleLine = true,
                textStyle = TextStyle(color = Jx.Text, fontFamily = Exo2, fontSize = 15.sp),
                cursorBrush = SolidColor(Jx.Accent),
                visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                modifier = Modifier.fillMaxWidth().onFocusChanged { Typing.active = it.isFocused }
                    .onPreviewKeyEvent { ev ->
                        if (ev.type == KeyEventType.KeyDown && (ev.key == Key.Enter || ev.key == Key.NumPadEnter)) { onEnter(); true } else false
                    },
            )
        }
    }
}

/** Botón rojo (acciones que borran). */
@Composable
fun DangerButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(Jx.Live.copy(alpha = 0.15f)).border(1.dp, Jx.Live.copy(alpha = 0.6f), RoundedCornerShape(50))
            .clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 18.dp, vertical = 8.dp),
    ) { Text(text, color = Jx.Live, fontFamily = Exo2, fontWeight = FontWeight.Medium, fontSize = 14.sp) }
}

/** Número de una tecla (fila de arriba o teclado numérico). */
fun digitKey(k: Key): Int? = when (k) {
    Key.Zero, Key.NumPad0 -> 0; Key.One, Key.NumPad1 -> 1; Key.Two, Key.NumPad2 -> 2
    Key.Three, Key.NumPad3 -> 3; Key.Four, Key.NumPad4 -> 4; Key.Five, Key.NumPad5 -> 5
    Key.Six, Key.NumPad6 -> 6; Key.Seven, Key.NumPad7 -> 7; Key.Eight, Key.NumPad8 -> 8
    Key.Nine, Key.NumPad9 -> 9; else -> null
}
