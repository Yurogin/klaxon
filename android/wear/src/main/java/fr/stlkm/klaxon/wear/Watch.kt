package fr.stlkm.klaxon.wear

import android.content.Context
import androidx.wear.tiles.TileService
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

// La montre ne se connecte à rien elle-même : elle passe par Klaxon sur le téléphone (Bluetooth).
// Chemins partagés avec mobile/WearSync.kt.
const val STATE = "/klaxon/state"

data class WPeer(val id: String, val name: String, val emoji: String)
data class WFriend(val code: String, val name: String, val where: String, val room: String, val here: Boolean)
data class WGroup(val room: String, val label: String, val here: Boolean)
data class WState(
    val room: String = "",
    val running: Boolean = false,
    val online: Boolean = false,
    val peers: List<WPeer> = emptyList(),
    val riposte: WPeer? = null,
    val friends: List<WFriend> = emptyList(),
    val groups: List<WGroup> = emptyList(),
)

private inline fun <T> JSONArray?.list(f: (JSONObject) -> T): List<T> =
    if (this == null) emptyList() else List(length()) { f(getJSONObject(it)) }

fun parseState(json: String): WState? = try {
    val o = JSONObject(json)
    WState(
        room = o.optString("room"),
        running = o.optBoolean("running"),
        online = o.optBoolean("online"),
        peers = o.optJSONArray("peers").list { WPeer(it.getString("id"), it.getString("name"), it.optString("e")) },
        riposte = o.optJSONObject("riposte")?.let { WPeer(it.getString("id"), it.getString("name"), "") },
        friends = o.optJSONArray("friends").list {
            WFriend(it.getString("code"), it.getString("name"), it.optString("where"), it.optString("room"), it.optBoolean("here"))
        },
        groups = o.optJSONArray("groups").list { WGroup(it.getString("room"), it.getString("label"), it.optBoolean("here")) },
    )
} catch (e: Exception) {
    null
}

object Watch {
    val state = MutableStateFlow<WState?>(null)   // null : pas encore de nouvelles du téléphone
    val phoneFound = MutableStateFlow(true)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var phone: String? = null

    /** Le dernier état reçu, gardé pour la tuile et le prochain lancement. */
    fun load(context: Context) {
        if (state.value == null) state.value = context.prefs().getString("state", null)?.let(::parseState)
    }

    fun update(context: Context, json: String) {
        val s = parseState(json) ?: return
        context.prefs().edit().putString("state", json).apply()
        state.value = s
        TileService.getUpdater(context).requestUpdate(KlaxonTile::class.java)
    }

    private fun Context.prefs() = getSharedPreferences("montre", Context.MODE_PRIVATE)

    /** Relit l'état directement dans la couche de données (au lancement de l'appli). */
    fun refresh(context: Context) = scope.launch {
        try {
            val items = Wearable.getDataClient(context).dataItems.await()
            for (item in items) {
                if (item.uri.path == STATE) DataMapItem.fromDataItem(item).dataMap.getString("json")?.let { update(context, it) }
            }
            items.release()
        } catch (_: Exception) {
        }
        send(context, "/klaxon/sync")  // et demande au téléphone un état tout frais
    }

    fun send(context: Context, path: String, data: String = "") = scope.launch {
        try {
            val node = phone ?: Wearable.getCapabilityClient(context)
                .getCapability("klaxon_phone", CapabilityClient.FILTER_REACHABLE).await()
                .nodes.let { nodes -> nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull() }?.id
            if (node == null) {
                phoneFound.value = false
                return@launch
            }
            phone = node
            phoneFound.value = true
            Wearable.getMessageClient(context).sendMessage(node, path, data.toByteArray()).await()
        } catch (e: Exception) {
            phone = null  // téléphone parti : on le recherchera au prochain envoi
            phoneFound.value = false
        }
    }
}

/** Réveillé par Android quand le téléphone envoie un nouvel état ou un klaxon, même appli fermée. */
class WatchListener : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        val data = String(event.data)
        when (event.path) {
            "/klaxon/honk" -> {
                val m = try { JSONObject(data) } catch (e: Exception) { return }
                Alert.honk(applicationContext, m.optString("pid"), m.optString("text"), m.optString("key"),
                    m.optString("sound"), m.optBoolean("hold"))
            }
            "/klaxon/hend" -> Alert.player(applicationContext).endNow(data)
            "/klaxon/invitation" -> {
                val m = try { JSONObject(data) } catch (e: Exception) { return }
                Alert.invite(applicationContext, m.optString("from"), m.optString("text"), m.optString("room"))
            }
        }
    }

    override fun onDataChanged(events: DataEventBuffer) {
        Alert.player(applicationContext)  // prépare les sons avant le premier klaxon
        for (e in events) {
            if (e.dataItem.uri.path == STATE) {
                DataMapItem.fromDataItem(e.dataItem).dataMap.getString("json")?.let { Watch.update(applicationContext, it) }
            }
        }
    }
}
