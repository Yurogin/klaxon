package fr.stlkm.klaxon

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationManagerCompat
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** La montre passe par le téléphone (Bluetooth) : le téléphone lui envoie l'état du salon,
 *  elle lui envoie ses klaxons. Chemins partagés avec le module wear. */
object WearSync {
    const val STATE = "/klaxon/state"

    /** Montres proches qui ont l'appli Klaxon : elles jouent elles-mêmes le vrai son des klaxons. */
    @Volatile var watches: Set<String> = emptySet()
        private set

    fun watchWatches(context: Context) {
        val cc = Wearable.getCapabilityClient(context)
        try {
            cc.getCapability("klaxon_watch", CapabilityClient.FILTER_REACHABLE)
                .addOnSuccessListener { info -> watches = info.nodes.map { it.id }.toSet() }
            cc.addListener({ info -> watches = info.nodes.filter { it.isNearby }.map { it.id }.toSet() }, "klaxon_watch")
        } catch (_: Exception) {
        }
    }

    fun toWatches(context: Context, path: String, data: String) {
        for (node in watches) {
            try {
                Wearable.getMessageClient(context).sendMessage(node, path, data.toByteArray()).addOnFailureListener { }
            } catch (_: Exception) {
            }
        }
    }

    fun honk(context: Context, pid: String, text: String, key: String, sound: String, hold: Boolean) =
        toWatches(context, "/klaxon/honk", JSONObject().put("pid", pid).put("text", text).put("key", key)
            .put("sound", sound).put("hold", hold).toString())

    fun honkEnd(context: Context, key: String) = toWatches(context, "/klaxon/hend", key)  // sans montre : ne fait rien

    fun invite(context: Context, from: String, text: String, room: String) =
        toWatches(context, "/klaxon/invitation", JSONObject().put("from", from).put("text", text).put("room", room).toString())

    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope, context: Context) = scope.launch {
        combine(listOf(Core.online, Core.peers, Core.room, Core.riposte, Core.friends, Core.groups, Core.statusTick,
            Core.running)) { }
            .debounce(150)
            .collect { push(context) }
    }

    fun state(): String {
        val room = Core.room.value
        val peers = JSONArray()
        for ((pid, p) in Core.peers.value) peers.put(JSONObject().put("id", pid).put("name", p.name).put("e", soundOf(p.sound).emoji))
        val friends = JSONArray()
        for (f in Core.friends.value) {
            val s = Core.friendOnline(f) ?: continue
            val here = s.room.isNotEmpty() && sameRoom(s.room, room)
            friends.put(JSONObject().put("code", f.code).put("name", f.name).put("room", s.room).put("here", here)
                .put("where", if (here) "avec toi" else if (s.room.isNotEmpty()) roomLabel(s.room) else "en ligne"))
        }
        val groups = JSONArray()
        for (g in Core.groups.value) groups.put(JSONObject().put("room", g).put("label", roomLabel(g)).put("here", sameRoom(g, room)))
        val r = Core.riposte.value
        return JSONObject()
            .put("room", roomLabel(room))
            .put("running", Core.running.value)
            .put("online", Core.online.value)
            .put("peers", peers)
            .put("riposte", if (r != null) JSONObject().put("id", r.pid).put("name", r.name) else JSONObject.NULL)
            .put("friends", friends)
            .put("groups", groups)
            .toString()
    }

    fun push(context: Context) {
        val req = PutDataMapRequest.create(STATE).apply {
            dataMap.putString("json", state())
            dataMap.putLong("t", System.currentTimeMillis())  // force l'envoi même si rien n'a changé
        }.asPutDataRequest().setUrgent()
        try {
            Wearable.getDataClient(context).putDataItem(req).addOnFailureListener { }  // pas de montre : tant pis
        } catch (_: Exception) {
        }
    }
}

/** Ce que la montre demande au téléphone. Réveille l'appli si besoin. */
class PhoneListener : WearableListenerService() {
    private val main = Handler(Looper.getMainLooper())

    override fun onMessageReceived(event: MessageEvent) {
        val data = String(event.data)
        main.post {
            if (event.path == "/klaxon/start") KlaxonService.start(this@PhoneListener)
            else if (event.path == "/klaxon/decline") {  // « Non » à une invitation, sur la montre
                Core.dropNotice("inv/$data")
                NotificationManagerCompat.from(this@PhoneListener).cancel(Notif.INVITE_ID)
            }
            else if (Core.net != null) when (event.path) {  // Klaxon arrêté : la montre ne le relance pas en douce
                "/klaxon/press" -> Core.press(data.takeIf { it != "*" })
                "/klaxon/release" -> Core.release()
                "/klaxon/quick" -> Core.quickHonk(data.takeIf { it != "*" })
                "/klaxon/join" -> {
                    Core.setRoom(data)
                    NotificationManagerCompat.from(this@PhoneListener).cancel(Notif.INVITE_ID)
                }
                "/klaxon/invite" -> Core.friends.value.firstOrNull { it.code == data }?.let(Core::inviteFriend)
            }
            WearSync.push(applicationContext)
        }
    }
}
