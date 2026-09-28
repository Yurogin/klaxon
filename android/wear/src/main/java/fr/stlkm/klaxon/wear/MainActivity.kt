package fr.stlkm.klaxon.wear

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.withTimeoutOrNull

val Bg = Color(0xFF15171C)
val FieldC = Color(0xFF23262E)
val Fg = Color(0xFFF2F2F2)
val Muted = Color(0xFF8A8F9E)
val Yellow = Color(0xFFFFC629)
val Blue = Color(0xFF2F6FED)
val Green = Color(0xFF3CCF6E)
val Ink = Color(0xFF1A1A1A)

class MainActivity : ComponentActivity() {
    private val askNotif = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)  // pour « Riposter » depuis la notification
        Watch.load(this)
        Alert.player(this)  // prépare les sons avant le premier klaxon
        setContent { WatchApp() }
    }
}

@Composable
fun WatchApp() {
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { Watch.refresh(context) }
    val state by Watch.state.collectAsState()
    val phoneFound by Watch.phoneFound.collectAsState()
    var friend by remember { mutableStateOf<WFriend?>(null) }
    Box(Modifier.fillMaxSize().background(Bg)) {
        val s = state
        val f = friend
        when {
            s == null || !s.running || !phoneFound -> Waiting(phoneFound, stopped = s != null && !s.running)
            f != null -> FriendScreen(s.friends.firstOrNull { it.code == f.code } ?: f) { friend = null }
            else -> Room(s) { friend = it }
        }
    }
}

@Composable
private fun Waiting(phoneFound: Boolean, stopped: Boolean) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("KLAXON", color = Yellow, fontSize = 26.sp, fontWeight = FontWeight.Black)
        Text(
            when {
                !phoneFound -> "Téléphone introuvable : il doit être proche, avec Klaxon installé."
                stopped -> "Klaxon est arrêté sur le téléphone."
                else -> "Ouvre Klaxon sur ton téléphone et choisis un salon."
            },
            color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 8.dp),
        )
        if (phoneFound && stopped) Pill("Démarrer", Yellow, Ink) { Watch.send(context, "/klaxon/start") }
        else Pill("Réessayer", FieldC, Fg) { Watch.refresh(context) }
    }
}

@Composable
private fun Room(s: WState, openFriend: (WFriend) -> Unit) {
    val context = LocalContext.current
    val list = rememberScalingLazyListState(initialCenterItemIndex = 1)
    val canHonk = s.online && s.peers.isNotEmpty()
    ScalingLazyColumn(
        Modifier.fillMaxSize(), state = list,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(s.room, color = Yellow, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        !s.online -> "Connexion…"
                        s.peers.isEmpty() -> "Personne d'autre"
                        else -> "${s.peers.size + 1} dans le salon"
                    }, color = Muted, fontSize = 12.sp,
                )
            }
        }
        s.riposte?.let { r -> item { HoldPill("↩ Riposter à ${r.name}", Yellow, Ink, r.id) } }
        item { HoldPill("📯 TOUT LE MONDE", if (canHonk) Yellow else Color(0xFF4A4535), Ink, "*", big = true) }
        items(s.peers, key = { it.id }) { p -> HoldPill("${p.name}  ${p.emoji}", Blue, Color.White, p.id) }

        if (s.friends.isNotEmpty()) {
            item { Header("Amis en ligne") }
            items(s.friends, key = { "f" + it.code }) { f ->
                Row2(f.name, f.where, dot = true) { openFriend(f) }
            }
        }
        val groups = s.groups.filter { !it.here }
        if (groups.isNotEmpty()) {
            item { Header("Groupes privés") }
            items(groups, key = { "g" + it.room }) { g -> Row2(g.label, "Entrer") { Watch.send(context, "/klaxon/join", g.room) } }
        }
    }
}

@Composable
private fun FriendScreen(f: WFriend, back: () -> Unit) {
    BackHandler(onBack = back)
    val context = LocalContext.current
    var invited by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(f.name, color = Fg, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1)
        Text(f.where, color = Muted, fontSize = 13.sp, maxLines = 2, textAlign = TextAlign.Center)
        if (f.room.isNotEmpty() && !f.here) Pill("Rejoindre", Yellow, Ink) { Watch.send(context, "/klaxon/join", f.room); back() }
        if (!f.here) Pill(if (invited) "✓ Invité" else "Inviter", FieldC, Fg) {
            if (!invited) Watch.send(context, "/klaxon/invite", f.code)
            invited = true
        }
    }
}

@Composable
private fun Header(text: String) = Text(text.uppercase(), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold,
    modifier = Modifier.padding(top = 8.dp))

@Composable
private fun Row2(title: String, sub: String, dot: Boolean = false, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(FieldC).clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text((if (dot) "● " else "") + title, color = Fg, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
        if (sub.isNotEmpty()) Text(sub, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Pill(text: String, bg: Color, fg: Color, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(bg).clickable(onClick = onClick).padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = fg, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
}

/** Appuyer = ça klaxonne, relâcher = ça s'arrête. Un court délai évite de klaxonner en faisant défiler. */
@Composable
private fun HoldPill(text: String, bg: Color, fg: Color, target: String, big: Boolean = false) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var down by remember { mutableStateOf(false) }
    Box(
        Modifier.fillMaxWidth().scale(if (down) 0.95f else 1f).clip(RoundedCornerShape(28.dp)).background(bg)
            .pointerInput(target) { holdGesture(context, target, { down = it }) { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } }
            .padding(vertical = if (big) 16.dp else 12.dp, horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = fg, fontWeight = if (big) FontWeight.Black else FontWeight.Bold, fontSize = if (big) 16.sp else 15.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.holdGesture(
    context: Context, target: String, setDown: (Boolean) -> Unit, buzz: () -> Unit,
) = detectTapGestures(onPress = {
    val early = withTimeoutOrNull(120) { tryAwaitRelease() }
    if (early == false) return@detectTapGestures  // c'était un défilement
    setDown(true)
    buzz()
    Watch.send(context, "/klaxon/press", target)
    if (early == null) tryAwaitRelease()
    Watch.send(context, "/klaxon/release")
    setDown(false)
})
