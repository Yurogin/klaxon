package fr.stlkm.klaxon

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** Salon d'un lien d'invitation ouvert avant d'avoir choisi son nom. */
    private val linkRoom = mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        handle(intent)
        setContent { KlaxonUi(linkRoom.value) }
        lifecycleScope.launch { Core.quit.drop(1).collect { finishAndRemoveTask() } }
    }

    override fun onStart() {
        super.onStart()
        KlaxonService.start(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(Notif.EXTRA_JOIN)?.let { room ->  // « Rejoindre » dans une notification
            NotificationManagerCompat.from(this).cancel(Notif.INVITE_ID)
            Core.notices.value.firstOrNull { it.key.startsWith("inv/") && it.text.contains(roomLabel(room)) }?.let { Core.dropNotice(it.key) }
            Core.setRoom(room)
            intent.removeExtra(Notif.EXTRA_JOIN)
        }
        val data = intent.data
        if (intent.action == Intent.ACTION_VIEW && data != null) {  // lien https://klaxon.stlkm.fr/#salon
            val room = data.getQueryParameter("salon")?.trim() ?: roomFromText(data.toString())
            if (room.isNotEmpty()) {
                if (Core.name.value.isBlank()) linkRoom.value = room else Core.setRoom(room)
            }
            intent.data = null
        }
    }
}
