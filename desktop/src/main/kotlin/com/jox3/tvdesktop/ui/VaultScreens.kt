package com.jox3.tvdesktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jox3.tvdesktop.data.Account
import com.jox3.tvdesktop.data.AccountStore
import com.jox3.tvdesktop.data.CloudSync
import com.jox3.tvdesktop.data.Profile
import com.jox3.tvdesktop.data.ProfileStore
import com.jox3.tvdesktop.data.ServerAccess
import com.jox3.tvdesktop.data.Vault
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

private const val CODE_HINT = "El código tiene 10 letras y números (JX-XXXXX-XXXXX)"

/** Activar, ver y cambiar los servidores que viajan con el perfil (igual que Android). */
@Composable
fun VaultScreen(store: ProfileStore, accounts: AccountStore, profile: Profile, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val p = store.byId(profile.id) ?: profile
    var code by remember { mutableStateOf(Vault.localCode(p.vaultId)) }
    var device by remember { mutableStateOf(ServerAccess.candidates(accounts.all(), p)) }
    var inVault by remember { mutableStateOf<List<Account>>(emptyList()) }
    var checked by remember { mutableStateOf(device.map { it.key }.toSet()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var info by remember { mutableStateOf("") }
    var typed by remember { mutableStateOf("") }
    var confirmOff by remember { mutableStateOf(false) }

    // Con el código en este equipo: traer lo que hay en la nube
    LaunchedEffect(code) {
        val c = code ?: return@LaunchedEffect
        busy = true; error = ""
        runCatching { Vault.fetch(c) }
            .onSuccess { r ->
                val before = accounts.all().map { it.key }.toSet()
                accounts.addAll(r.servers)
                ServerAccess.claimFromVault(p, r.servers, r.servers.map { it.key }.toSet() - before, accounts)
                device = ServerAccess.candidates(accounts.all(), p)
                inVault = r.servers; checked = r.servers.map { it.key }.toSet()
            }
            .onFailure { e -> if (e !is Vault.NotFound) error = e.message ?: "No se pudo conectar" }
        busy = false
    }
    val all = (inVault + device).distinctBy { it.key }

    fun upload(c: String, isNew: Boolean) {
        val chosen = all.filter { it.key in checked }
        if (chosen.isEmpty()) { error = "Elige al menos un servidor"; return }
        busy = true; error = ""; info = ""
        scope.launch {
            val ok = Vault.upload(c, chosen, p.id)
            if (ok) {
                val id = Vault.derive(c).id
                Vault.saveLocal(id, c)
                store.setVault(p, id, chosen.size)
                ServerAccess.setVaultKeys(p.id, chosen.map { it.key }.toSet())
                inVault = chosen
                if (isNew) code = c
                info = "Guardado ✓ (${chosen.size} servidor${if (chosen.size == 1) "" else "es"})"
                // Que el aviso del código llegue a la nube de todos los servidores de este equipo
                CloudSync.syncProfiles(store)
                CloudSync.syncAllProfiles(accounts.all(), except = store.account)
            } else error = "No se pudo guardar. Revisa el internet"
            busy = false
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 80.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("☁️ Mis servidores en la nube", color = Jx.Text, fontFamily = Exo2, fontWeight = FontWeight.SemiBold, fontSize = 28.sp)
        val c = code
        when {
            // 1. Aún no tiene código
            p.vaultId.isEmpty() && c == null -> {
                Muted("Tus servidores se guardan cifrados con un código que solo tú conoces. En otro equipo (PC, TV Box o celular) " +
                    "escribes el código y se cargan solos. Nadie más puede verlos, ni siquiera desde la nube.")
                Strong("Elige qué servidores guardar:")
                ServerChecklist(all, checked) { k -> checked = if (k in checked) checked - k else checked + k }
                PillButton(if (busy) "Guardando…" else "Crear mi código y guardar") { if (!busy) upload(Vault.newCode(), isNew = true) }
            }
            // 2. Ya tiene código, creado en otro equipo
            c == null -> {
                Muted("Este perfil ya guarda ${p.vaultCount} servidor${if (p.vaultCount == 1) "" else "es"} con un código JOX3. " +
                    "Escríbelo para verlos y cambiarlos desde aquí.")
                CodeField(typed, { typed = it }) {}
                PillButton(if (busy) "Revisando…" else "Continuar") {
                    val n = Vault.normalize(typed)
                    if (n == null) { error = CODE_HINT; return@PillButton }
                    busy = true; error = ""
                    scope.launch {
                        if (Vault.derive(n).id != p.vaultId) error = "Ese no es el código de este perfil"
                        else { Vault.saveLocal(p.vaultId, n); code = n }
                        busy = false
                    }
                }
            }
            // 3. Código en este equipo: verlo y elegir servidores
            else -> {
                Text("Tu código JOX3", color = Jx.TextSoft, fontFamily = Exo2, fontSize = 14.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.clip(RoundedCornerShape(14.dp)).background(Jx.Surface).border(2.dp, Jx.Accent, RoundedCornerShape(14.dp))
                        .padding(horizontal = 22.dp, vertical = 14.dp)) {
                        Text(Vault.pretty(c), color = Jx.Accent, fontFamily = Exo2, fontWeight = FontWeight.Bold, fontSize = 30.sp)
                    }
                    Spacer(Modifier.width(14.dp))
                    GhostButton("Copiar") {
                        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(Vault.pretty(c)), null) }
                        info = "Código copiado"
                    }
                }
                Muted("Anótalo o guárdalo en un lugar seguro. Si se pierde no se puede recuperar; desde un equipo donde ya " +
                    "estés adentro lo puedes volver a ver aquí.")
                Strong("Servidores guardados:")
                ServerChecklist(all, checked) { k -> checked = if (k in checked) checked - k else checked + k }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton(if (busy) "Guardando…" else "Guardar cambios") { if (!busy) upload(c, isNew = false) }
                    DangerButton(if (confirmOff) "¿Seguro? Haz clic otra vez" else "Dejar de guardar en la nube") {
                        if (!confirmOff) { confirmOff = true; return@DangerButton }
                        busy = true
                        scope.launch {
                            if (Vault.delete(c)) {
                                Vault.forgetLocal(p.vaultId); store.setVault(p, "", 0)
                                ServerAccess.release(p); onDone()
                            } else error = "No se pudo. Revisa el internet"
                            busy = false
                        }
                    }
                }
            }
        }
        if (error.isNotEmpty()) Text(error, color = Jx.Live, fontFamily = Exo2, fontSize = 13.sp)
        if (info.isNotEmpty()) Text(info, color = Jx.Ok, fontFamily = Exo2, fontSize = 13.sp)
        GhostButton("Volver", onDone)
    }
}

@Composable
private fun ServerChecklist(servers: List<Account>, checked: Set<String>, onToggle: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (servers.isEmpty()) Text("No hay servidores en este equipo", color = Jx.Faint, fontFamily = Exo2, fontSize = 13.sp)
        servers.forEach { a ->
            val on = a.key in checked
            Row(
                Modifier.widthIn(min = 340.dp).clip(RoundedCornerShape(12.dp)).background(Jx.Surface)
                    .clickable { onToggle(a.key) }.pointerHoverIcon(PointerIcon.Hand)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(if (on) Jx.Accent else Jx.Line),
                    contentAlignment = Alignment.Center) { if (on) Text("✓", color = Jx.OnAccent, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(a.label, color = Jx.Text, fontFamily = Exo2, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1)
                    Text(a.user, color = Jx.Muted, fontFamily = Exo2, fontSize = 12.sp, maxLines = 1)
                }
            }
        }
    }
}

/**
 * Al entrar a un perfil: si este servidor no es de los que ve el perfil, pasa a uno de los suyos;
 * si el perfil guarda servidores con un código JOX3 y este equipo aún no los tiene, pide el código.
 * Muestra [content] mientras tanto (o cuando no hace falta).
 */
@Composable
fun VaultUnlockGate(
    store: ProfileStore, accounts: AccountStore, profileId: String, onSwitch: (Account) -> Unit,
    content: @Composable () -> Unit,
) {
    var ask by remember(profileId) { mutableStateOf<Profile?>(null) }
    fun checkVisible() {
        val p = store.byId(profileId) ?: return
        val vis = ServerAccess.visible(accounts.all(), p)
        if (vis.isNotEmpty() && vis.none { it.key == store.account.key }) onSwitch(vis.first())
    }
    LaunchedEffect(profileId) {
        fun needs(p: Profile?) = p != null && Vault.needsUnlock(p)
        checkVisible()
        if (needs(store.byId(profileId))) { ask = store.byId(profileId); return@LaunchedEffect }
        // Puede que el código se haya creado en otro equipo hace poco: traer los perfiles y revisar otra vez
        CloudSync.syncProfiles(store)
        if (needs(store.byId(profileId))) { ask = store.byId(profileId); return@LaunchedEffect }
        // Código en este equipo: traer servidores agregados en otros equipos y subir contraseñas cambiadas
        delay(4000)
        store.byId(profileId)?.takeIf { it.vaultId.isNotEmpty() }?.let { Vault.refresh(it, accounts); checkVisible() }
    }
    val a = ask
    if (a != null) Box(Modifier.fillMaxSize().background(Jx.Bg)) {
        UnlockPrompt(accounts, a, onFinish = { ask = null; checkVisible() })
    } else content()
}

/** Al elegir un perfil con código JOX3 en un equipo que aún no tiene sus servidores. */
@Composable
private fun UnlockPrompt(accounts: AccountStore, p: Profile, onFinish: () -> Unit) {
    val scope = rememberCoroutineScope()
    var typed by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    fun go() {
        val n = Vault.normalize(typed) ?: run { error = CODE_HINT; return }
        busy = true; error = ""
        scope.launch {
            try {
                if (Vault.derive(n).id != p.vaultId) error = "Ese no es el código de ${p.name}"
                else {
                    val r = Vault.fetch(n)
                    val before = accounts.all().map { it.key }.toSet()
                    accounts.addAll(r.servers)
                    // Los que llegaron con el código son de esta persona: no se mezclan con los de la casa
                    ServerAccess.claimFromVault(p, r.servers, r.servers.map { it.key }.toSet() - before, accounts)
                    Vault.saveLocal(p.vaultId, n)
                    onFinish()
                }
            } catch (e: Exception) { error = e.message ?: "No se pudo conectar" }
            busy = false
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 120.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Avatar(p.emoji, p.color, 100.dp)
        Spacer(Modifier.height(14.dp))
        Text("Hola, ${p.name}", color = Jx.Text, fontFamily = Exo2, fontWeight = FontWeight.SemiBold, fontSize = 28.sp)
        Spacer(Modifier.height(6.dp))
        Text("Tu perfil tiene ${p.vaultCount} servidor${if (p.vaultCount == 1) "" else "es"} guardado${if (p.vaultCount == 1) "" else "s"}. " +
            "Escribe tu código JOX3 para agregarlos a este equipo.", color = Jx.Muted, fontFamily = Exo2, fontSize = 14.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(18.dp))
        Box(Modifier.widthIn(max = 420.dp)) { CodeField(typed, { typed = it }) { go() } }
        Spacer(Modifier.height(8.dp))
        Text(error, color = Jx.Live, fontFamily = Exo2, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton(if (busy) "Revisando…" else "Agregar servidores") { if (!busy) go() }
            GhostButton("Ahora no") { Vault.skippedThisLaunch.add(p.vaultId); onFinish() }
        }
        Spacer(Modifier.height(10.dp))
        GhostButton("No volver a preguntar en este equipo") { Vault.dismiss(p.vaultId); onFinish() }
    }
}

/**
 * Entrar en un equipo nuevo con el código JOX3: se cargan todos los servidores guardados.
 * Se usa desde la pantalla de inicio de sesión ("Tengo un código JOX3").
 */
@Composable
fun VaultCodeLogin(accounts: AccountStore, onLogin: (Account) -> Unit, onCancel: () -> Unit) {
    val scope = rememberCoroutineScope()
    var typed by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    fun go() {
        val n = Vault.normalize(typed) ?: run { error = CODE_HINT; return }
        busy = true; error = ""
        scope.launch {
            try {
                val r = Vault.fetch(n)
                if (r.servers.isEmpty()) error = "Ese código no tiene servidores guardados"
                else {
                    val before = accounts.all().map { it.key }.toSet()
                    accounts.addAll(r.servers)
                    if (r.profileId.isNotEmpty()) {
                        ServerAccess.setVaultKeys(r.profileId, r.servers.map { it.key }.toSet())
                        r.servers.filter { it.key !in before }.forEach { ServerAccess.setOwner(it.key, r.profileId) }
                    }
                    Vault.saveLocal(Vault.derive(n).id, n)
                    onLogin(r.servers.first())
                }
            } catch (e: Exception) { error = e.message ?: "No se pudo conectar" }
            busy = false
        }
    }
    Column(
        Modifier.fillMaxSize().background(Jx.Bg).padding(horizontal = 120.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Brand(iconSize = 44.dp, textSize = 28)
        Spacer(Modifier.height(24.dp))
        Text("Entrar con tu código JOX3", color = Jx.Text, fontFamily = Exo2, fontWeight = FontWeight.SemiBold, fontSize = 30.sp)
        Spacer(Modifier.height(6.dp))
        Text("Se cargan los servidores que guardaste desde tu perfil en otro equipo.", color = Jx.Muted, fontFamily = Exo2, fontSize = 14.sp)
        Spacer(Modifier.height(20.dp))
        Box(Modifier.widthIn(max = 420.dp)) { CodeField(typed, { typed = it }) { go() } }
        Spacer(Modifier.height(8.dp))
        Text(error, color = Jx.Live, fontFamily = Exo2, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton(if (busy) "Buscando…" else "Entrar") { if (!busy) go() }
            GhostButton("Volver", onCancel)
        }
    }
}

@Composable
private fun CodeField(value: String, onChange: (String) -> Unit, onDone: () -> Unit) {
    GateField("Código JOX3", value, { onChange(it.uppercase().take(16)) }, "JX-XXXXX-XXXXX", onEnter = onDone)
}

@Composable private fun Muted(t: String) = Text(t, color = Jx.Muted, fontFamily = Exo2, fontSize = 14.sp)
@Composable private fun Strong(t: String) = Text(t, color = Jx.TextSoft, fontFamily = Exo2, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
