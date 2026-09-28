package fr.stlkm.klaxon

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

val Bg = Color(0xFF15171C)
val FieldC = Color(0xFF23262E)
val BtnC = Color(0xFF2F333D)
val Fg = Color(0xFFF2F2F2)
val Muted = Color(0xFF8A8F9E)
val Yellow = Color(0xFFFFC629)
val Blue = Color(0xFF2F6FED)
val Red = Color(0xFFE8412C)
val Green = Color(0xFF3CCF6E)
val Ink = Color(0xFF1A1A1A)

@Composable
fun KlaxonUi(linkRoom: String) {
    val name by Core.name.collectAsState()
    val room by Core.room.collectAsState()
    var screen by rememberSaveable { mutableStateOf("room") }
    Box(Modifier.fillMaxSize().background(Bg)) {
        when {
            name.isBlank() || room.isBlank() -> Gate(linkRoom)
            screen == "friends" -> FriendsScreen { screen = "room" }
            else -> RoomScreen { screen = "friends" }
        }
    }
}

private val pageModifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)

// ---------- porte d'entrée ----------
@Composable
fun Gate(linkRoom: String) {
    var name by rememberSaveable { mutableStateOf(Core.name.value) }
    var room by rememberSaveable(linkRoom) { mutableStateOf(linkRoom.ifEmpty { Core.room.value }) }
    val askNotif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val go = {
        if (name.isNotBlank() && room.isNotBlank()) {
            if (Build.VERSION.SDK_INT >= 33) askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
            Core.enter(name, room)
        }
    }
    Column(pageModifier.imePadding(), verticalArrangement = Arrangement.Center) {
        Text("KLAXON", color = Yellow, fontSize = 64.sp, fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp))
        LabeledField("Moi", name, { name = it.take(24) }, big = true)
        Spacer(Modifier.height(10.dp))
        LabeledField("Salon", room, { room = it.take(200) }, big = true, done = go)
        Spacer(Modifier.height(24.dp))
        Btn("ENTRER", Yellow, Ink, Modifier.fillMaxWidth(), size = 34, pad = 14, onClick = go)
    }
}

// ---------- salon ----------
@Composable
fun RoomScreen(openFriends: () -> Unit) {
    val context = LocalContext.current
    val name by Core.name.collectAsState()
    val room by Core.room.collectAsState()
    val sound by Core.sound.collectAsState()
    val online by Core.online.collectAsState()
    val peers by Core.peers.collectAsState()
    val riposte by Core.riposte.collectAsState()
    val flash by Core.flash.collectAsState()
    val holding by Core.holding.collectAsState()
    Core.statusTick.collectAsState().value  // recompose quand un ami change de statut
    Core.friends.collectAsState().value
    val bg by animateColorAsState(if (flash != null) Red else Bg, tween(150), label = "flash")
    val focus = LocalFocusManager.current

    Column(Modifier.fillMaxSize().background(bg).then(pageModifier), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EditField("Moi", name, Core::setName, Modifier.weight(1f))
            val n = Core.onlineFriends()
            Btn("👥 Amis" + if (n > 0) " · $n" else "", FieldC, Fg, Modifier.padding(start = 8.dp), onClick = openFriends)
            QuitBtn(Modifier.padding(start = 8.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            EditField("Salon", room, Core::setRoom, Modifier.weight(1f))
            Btn("🔗 Inviter", FieldC, Fg, Modifier.padding(start = 8.dp)) { shareRoom(context, room) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (s in SOUNDS) {
                val on = s.key == sound
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (on) Color(0xFF2A2D36) else FieldC)
                        .border(2.dp, if (on) Yellow else Color.Transparent, RoundedCornerShape(8.dp))
                        .clickable { focus.clearFocus(); Core.pickSound(s.key) }.padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(s.emoji, fontSize = 24.sp)
                    Text(s.label, color = if (on) Fg else Muted, fontSize = 11.sp, maxLines = 1)
                }
            }
        }
        val loud by Core.loud.collectAsState()
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(FieldC).clickable { Core.setLoud(!loud) }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (loud) "🔔 Sonne même en mode silencieux" else "🔕 Muet quand le téléphone est en silencieux",
                color = Fg, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Box(Modifier.size(width = 40.dp, height = 22.dp).clip(CircleShape).background(if (loud) Yellow else BtnC).padding(3.dp),
                contentAlignment = if (loud) Alignment.CenterEnd else Alignment.CenterStart) {
                Box(Modifier.size(16.dp).clip(CircleShape).background(if (loud) Ink else Muted))
            }
        }
        BackgroundHint()
        LinkHint()
        NoticeBanner()

        val running by Core.running.collectAsState()
        val status = flash?.text ?: when {
            !running -> "Klaxon est arrêté"
            !online -> "Connexion…"
            riposte != null -> "↩ Riposter à " + riposte!!.name
            peers.isEmpty() -> "Personne d'autre dans le salon"
            else -> "${peers.size + 1} dans le salon"
        }
        Text(status, color = if (flash != null) Fg else Muted, fontSize = 22.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().hold(riposte?.pid ?: "") { riposte?.let { Core.press(it.pid) } })

        val canHonk = online && peers.isNotEmpty()
        Box(
            Modifier.fillMaxWidth().scale(if (holding == "*") 0.96f else 1f).alpha(if (canHonk) 1f else 0.35f)
                .clip(RoundedCornerShape(10.dp)).background(Yellow).hold("*") { Core.press(null) }.padding(vertical = 22.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("📯 TOUT LE MONDE", color = Ink, fontSize = 30.sp, fontWeight = FontWeight.Black)
        }
        PeerGrid(peers, riposte?.pid, holding, Modifier.weight(1f))
    }
}

@Composable
private fun PeerGrid(peers: List<Pair<String, Peer>>, riposteId: String?, holding: String?, modifier: Modifier) {
    val cols = if (peers.size <= 1) 1 else if (peers.size <= 6) 2 else 3
    val blink = rememberInfiniteTransition(label = "riposte").animateFloat(1f, 0f,
        infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "blink")
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (row in peers.chunked(cols)) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for ((pid, p) in row) {
                    val border = if (pid == riposteId) Yellow.copy(alpha = blink.value) else Color.Transparent
                    Column(
                        Modifier.weight(1f).fillMaxHeight().scale(if (holding == pid) 0.96f else 1f)
                            .clip(RoundedCornerShape(10.dp)).background(Blue).border(4.dp, border, RoundedCornerShape(10.dp))
                            .hold(pid) { Core.press(pid) }.padding(6.dp),
                        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(p.name, color = Color.White, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center,
                            fontSize = if (peers.size <= 2) 32.sp else if (peers.size <= 6) 24.sp else 18.sp, maxLines = 2,
                            overflow = TextOverflow.Ellipsis)
                        Text(soundOf(p.sound).emoji, fontSize = 18.sp)
                    }
                }
                repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Appuyer = ça klaxonne, relâcher = ça s'arrête. */
private fun Modifier.hold(key: Any, onDown: () -> Unit): Modifier = this.then(Modifier.pointerInput(key) {
    detectTapGestures(onPress = {
        onDown()
        tryAwaitRelease()
        Core.release()
    })
})

@Composable
private fun NoticeBanner() {
    val notices by Core.notices.collectAsState()
    val n = notices.firstOrNull() ?: return
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(FieldC)
            .border(2.dp, Yellow, RoundedCornerShape(8.dp)).padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(n.text, color = Fg, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Btn(n.yes, Yellow, Ink, Modifier.padding(start = 6.dp)) { Core.answerNotice(true) }
        Btn("Non", BtnC, Fg, Modifier.padding(start = 6.dp)) { Core.answerNotice(false) }
    }
}

/** Sans ces réglages, Android (et surtout Xiaomi) coupe Klaxon écran éteint. */
@Composable
private fun BackgroundHint() {
    val context = LocalContext.current
    var ok by remember { mutableStateOf(backgroundOk(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { ok = backgroundOk(context) }
    val askNotif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok = backgroundOk(context) }
    if (ok) return
    val notifOk = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val xiaomi = Build.MANUFACTURER.equals("xiaomi", true) || Build.MANUFACTURER.equals("redmi", true)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(FieldC).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            if (!notifOk) "Autorise les notifications pour voir qui te klaxonne quand l'appli est fermée."
            else "Pour entendre les klaxons écran éteint, laisse Klaxon tourner en fond." +
                if (xiaomi) " Sur Xiaomi, active aussi « Démarrage automatique » dans les réglages de l'appli." else "",
            color = Fg, fontSize = 14.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!notifOk) Btn("Autoriser", Yellow, Ink) { askNotif.launch(Manifest.permission.POST_NOTIFICATIONS) }
            else {
                Btn("Autoriser", Yellow, Ink) { askBattery(context) }
                if (xiaomi) Btn("Réglages de l'appli", BtnC, Fg) { openAppSettings(context) }
            }
        }
    }
}

/** Les liens d'invitation https://klaxon.stlkm.fr/… s'ouvrent dans l'appli seulement si Android l'y autorise. */
@Composable
private fun LinkHint() {
    if (Build.VERSION.SDK_INT < 31) return
    val context = LocalContext.current
    var ok by remember { mutableStateOf(linksOk(context)) }
    var hidden by remember { mutableStateOf(Core.linkHintDismissed) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { ok = linksOk(context) }
    if (ok || hidden) return
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(FieldC).padding(start = 10.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Pour que les liens d'invitation s'ouvrent dans Klaxon plutôt que sur le site :", color = Fg, fontSize = 14.sp,
            modifier = Modifier.weight(1f))
        Btn("Activer", Yellow, Ink) {
            try {
                context.startActivity(Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, Uri.parse("package:" + context.packageName)))
            } catch (e: Exception) {
                openAppSettings(context)
            }
        }
        XBtn { hidden = true; Core.linkHintDismissed = true }
    }
}

private fun linksOk(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < 31) return true
    val state = context.getSystemService(android.content.pm.verify.domain.DomainVerificationManager::class.java)
        ?.getDomainVerificationUserState(context.packageName) ?: return true
    return state.hostToStateMap["klaxon.stlkm.fr"] != android.content.pm.verify.domain.DomainVerificationUserState.DOMAIN_STATE_NONE
}

private fun backgroundOk(context: Context): Boolean {
    val pm = context.getSystemService(PowerManager::class.java)
    val notif = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    return notif && pm.isIgnoringBatteryOptimizations(context.packageName)
}

@SuppressLint("BatteryLife")  // appli installée à la main, qui doit rester connectée : c'est le cas prévu
private fun askBattery(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.packageName)))
    } catch (e: ActivityNotFoundException) {
        openAppSettings(context)
    }
}

private fun openAppSettings(context: Context) {
    val autostart = Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
    val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
    try {
        context.startActivity(if (Build.MANUFACTURER.equals("xiaomi", true)) autostart else details)
    } catch (e: Exception) {
        context.startActivity(details)
    }
}

private fun shareRoom(context: Context, room: String) {
    val link = SITE + "#" + Uri.encode(room)
    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, "Viens klaxonner avec moi dans « ${roomLabel(room)} » : $link")
    context.startActivity(Intent.createChooser(send, "Inviter dans le salon"))
}

// ---------- amis et groupes ----------
@Composable
fun FriendsScreen(back: () -> Unit) {
    BackHandler(onBack = back)
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val friends by Core.friends.collectAsState()
    val groups by Core.groups.collectAsState()
    val peers by Core.peers.collectAsState()
    val room by Core.room.collectAsState()
    val invited by Core.invitedAt.collectAsState()
    Core.statusTick.collectAsState().value
    var addCode by rememberSaveable { mutableStateOf("") }
    var addMsg by rememberSaveable { mutableStateOf("") }
    var groupName by rememberSaveable { mutableStateOf("") }
    var copied by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    LaunchedEffect(copied) { if (copied) { delay(1600); copied = false } }

    val rows = friends.map { it to Core.friendOnline(it) }.sortedWith(compareBy({ it.second == null }, { it.first.name.lowercase() }))
    val tids = friends.map { ident(it.code).tid }.toSet()
    val strangers = peers.filter { it.second.u.isNotEmpty() && it.second.u != ident(Core.code).tid && it.second.u !in tids }

    LazyColumn(pageModifier.imePadding(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("←", color = Fg, fontSize = 28.sp, modifier = Modifier.clip(CircleShape).clickable(onClick = back).padding(8.dp))
                Text("Amis", color = Yellow, fontSize = 30.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(start = 4.dp))
            }
        }
        item { Section("Mon code ami") }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(showCode(Core.code), color = Yellow, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                Btn(if (copied) "✓ Copié" else "Copier", if (copied) Yellow else BtnC, if (copied) Ink else Fg) {
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Code ami Klaxon", showCode(Core.code)))) }
                    copied = true
                }
                Btn("Partager", BtnC, Fg, Modifier.padding(start = 6.dp)) {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, "Mon code ami Klaxon : ${showCode(Core.code)}\n$SITE")
                    context.startActivity(Intent.createChooser(send, "Partager mon code ami"))
                }
            }
            Hint("Donne-le à tes amis : avec, ils te voient en ligne et peuvent t'inviter.")
        }

        item { Section("Mes amis") }
        item {
            val add = {
                val c = normCode(addCode)
                addMsg = when {
                    !validCode(c) -> "Un code ami fait 10 caractères, comme ${showCode(Core.code)}."
                    c == Core.code -> "C'est ton propre code 🙂"
                    Core.isFriend(c) -> "Déjà dans tes amis."
                    else -> { Core.addFriend(c, "?"); addCode = ""; "Ajouté ! Son nom apparaît dès qu'il est en ligne." }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Field(addCode, { addCode = it.take(16) }, "Code d'un ami", Modifier.weight(1f), done = add, caps = true)
                Btn("Ajouter", Yellow, Ink, Modifier.padding(start = 8.dp), onClick = add)
            }
            if (addMsg.isNotEmpty()) Hint(addMsg)
        }
        for ((f, s) in rows) item(key = "f" + f.code) {
            val here = s != null && s.room.isNotEmpty() && sameRoom(s.room, room)
            val where = when {
                s == null -> "hors ligne"
                here -> "avec toi"
                s.room.isNotEmpty() -> "dans « ${roomLabel(s.room)} »"
                else -> "en ligne"
            }
            ListRow(f.name, where, dot = s != null) {
                if (s != null && s.room.isNotEmpty() && !here) Btn("Rejoindre", Yellow, Ink) { Core.setRoom(s.room); back() }
                if (s != null && !here) {
                    val recent = android.os.SystemClock.uptimeMillis() - (invited[f.code] ?: 0) < 3000
                    Btn(if (recent) "✓ Invité" else "Inviter", BtnC, Fg, enabled = !recent) { Core.inviteFriend(f) }
                }
                XBtn { confirm = "Retirer ${f.name} de tes amis ?" to { Core.removeFriend(f.code) } }
            }
        }
        if (friends.isEmpty()) item { ListRow("Pas encore d'amis", "Ajoute un code, ou quelqu'un de ton salon.") {} }

        if (strangers.isNotEmpty()) {
            item { Section("Dans ce salon") }
            for ((pid, p) in strangers) item(key = "p$pid") {
                ListRow(p.name, "") {
                    if (Core.asked(p.u)) Btn("Demande envoyée", BtnC, Fg, enabled = false) {}
                    else Btn("+ Ajouter", Yellow, Ink) { Core.askFriend(pid, p) }
                }
            }
        }

        item { Section("Groupes privés") }
        for (g in groups) item(key = "g$g") {
            val here = sameRoom(g, room)
            ListRow(roomLabel(g), if (here) "tu y es" else "") {
                if (here) Btn("🔗 Inviter", BtnC, Fg) { shareRoom(context, g) }
                else Btn("Entrer", Yellow, Ink) { Core.setRoom(g); back() }
                XBtn { confirm = "Oublier le groupe « ${roomLabel(g).drop(3)} » ? Il faudra une invitation pour y revenir." to { Core.forgetGroup(g) } }
            }
        }
        if (groups.isEmpty()) item { ListRow("Aucun groupe privé pour l'instant", "") {} }
        item {
            val create = { if (groupName.isNotBlank()) { Core.createGroup(groupName); groupName = ""; back() } }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Field(groupName, { groupName = it.take(40) }, "Nom du nouveau groupe", Modifier.weight(1f), done = create)
                Btn("Créer", Yellow, Ink, Modifier.padding(start = 8.dp), onClick = create)
            }
            Hint("Un groupe privé a un nom secret : on n'y entre que par invitation ou par son lien.")
            Spacer(Modifier.height(24.dp))
        }
    }

    confirm?.let { (text, action) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            text = { Text(text) },
            confirmButton = { TextButton({ action(); confirm = null }) { Text("Oui") } },
            dismissButton = { TextButton({ confirm = null }) { Text("Non") } },
        )
    }
}

// ---------- petites briques ----------
@Composable
private fun Section(text: String) = Text(text.uppercase(), color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold,
    letterSpacing = 0.6.sp, modifier = Modifier.padding(top = 18.dp, bottom = 4.dp))

@Composable
private fun Hint(text: String) = Text(text, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))

@Composable
private fun ListRow(name: String, sub: String, dot: Boolean? = null, buttons: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(FieldC).padding(start = 10.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (dot != null) Box(Modifier.size(10.dp).clip(CircleShape).background(if (dot) Green else Color(0xFF4A4F5C)))
        Column(Modifier.weight(1f).padding(start = 2.dp)) {
            Text(name, color = Fg, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            if (sub.isNotEmpty()) Text(sub, color = Muted, fontSize = 13.sp)
        }
        buttons()
    }
}

@Composable
private fun XBtn(onClick: () -> Unit) = Text("✕", color = Muted, fontSize = 18.sp,
    modifier = Modifier.clip(CircleShape).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp))

/** Quitter : icône « marche/arrêt » dessinée (le caractère ⏻ manque dans beaucoup de polices). */
@Composable
private fun QuitBtn(modifier: Modifier) {
    Box(
        modifier.size(40.dp).clip(RoundedCornerShape(6.dp)).background(FieldC).clickable { Core.quitApp() }
            .semantics { contentDescription = "Quitter Klaxon" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(20.dp)) {
            val w = 2.2.dp.toPx()
            drawArc(Fg, startAngle = -60f, sweepAngle = 300f, useCenter = false,
                topLeft = Offset(w / 2, w / 2 + 1.dp.toPx()), size = Size(size.width - w, size.height - w - 1.dp.toPx()),
                style = Stroke(w, cap = StrokeCap.Round))
            drawLine(Fg, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height * 0.45f), w, StrokeCap.Round)
        }
    }
}

@Composable
fun Btn(
    text: String, bg: Color, fg: Color, modifier: Modifier = Modifier, size: Int = 15, pad: Int = 8,
    enabled: Boolean = true, onClick: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    Box(
        modifier.clip(RoundedCornerShape(if (size > 20) 10.dp else 6.dp)).background(bg).alpha(if (enabled) 1f else 0.5f)
            .clickable(enabled = enabled) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() }
            .padding(horizontal = 12.dp, vertical = pad.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = fg, fontSize = size.sp, fontWeight = if (size > 20) FontWeight.Black else FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun Field(
    value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier,
    big: Boolean = false, done: () -> Unit = {}, caps: Boolean = false,
) {
    val style = TextStyle(color = Fg, fontSize = if (big) 24.sp else 17.sp, fontWeight = FontWeight.Bold)
    BasicTextField(
        value, onChange, modifier, singleLine = true, textStyle = style, cursorBrush = SolidColor(Fg),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done,
            capitalization = if (caps) KeyboardCapitalization.Characters else KeyboardCapitalization.Sentences),
        keyboardActions = KeyboardActions(onDone = { done() }),
        decorationBox = { inner ->
            Box(Modifier.clip(RoundedCornerShape(6.dp)).background(FieldC).padding(horizontal = 10.dp, vertical = 8.dp)) {
                if (value.isEmpty()) Text(placeholder, style = style.copy(color = Muted, fontWeight = FontWeight.Normal))
                inner()
            }
        },
    )
}

@Composable
private fun LabeledField(label: String, value: String, onChange: (String) -> Unit, big: Boolean = false, done: () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Muted, fontSize = if (big) 20.sp else 16.sp, modifier = Modifier.padding(end = 12.dp).size(width = 64.dp, height = 28.dp))
        Field(value, onChange, "", Modifier.weight(1f), big, done)
    }
}

/** Champ appliqué quand on valide ou qu'on le quitte, comme sur le web. */
@Composable
private fun EditField(label: String, current: String, apply: (String) -> Unit, modifier: Modifier) {
    var text by remember(current) { mutableStateOf(current) }
    val focus = LocalFocusManager.current
    val latest by rememberUpdatedState(text)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Muted, fontSize = 15.sp, modifier = Modifier.padding(end = 10.dp).size(width = 44.dp, height = 22.dp))
        Field(text, { text = it.take(200) }, "", Modifier.weight(1f).onFocusChanged {
            if (!it.isFocused && latest != current) { if (latest.isBlank()) text = current else apply(latest) }
        }, done = { focus.clearFocus() })
    }
}
