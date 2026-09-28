package fr.stlkm.klaxon

import android.app.Application
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class KlaxonApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Core.init(this)
        Notif.channels(this)
        WearSync.watchWatches(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                Core.visible = true
                NotificationManagerCompat.from(this@KlaxonApp).cancel(Notif.HONK_ID)
                Update.check()
            }

            override fun onStop(owner: LifecycleOwner) {
                Core.visible = false
                Core.release()
            }
        })
    }
}

/** Comme l'icône près de l'horloge sur Windows : Klaxon reste connecté et écoute, écran éteint compris. */
class KlaxonService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // relancé tout seul par Android (intent null) alors qu'on avait quitté : on reste arrêté
        if (intent?.action == ACTION_QUIT || !Core.hasRoom || (intent == null && !Core.listening)) {
            if (intent?.action == ACTION_QUIT) Core.stopListening()
            Core.disconnect()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(this, Notif.SERVICE_ID, Notif.service(this, "Connexion…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        Core.connect()
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        WearSync.start(scope, applicationContext)
        scope.launch {
            combine(Core.online, Core.peers, Core.room, Core.statusTick) { on, peers, room, _ ->
                val who = when {
                    !on -> "Connexion…"
                    peers.isEmpty() -> "personne d'autre"
                    else -> "${peers.size + 1} dans le salon"
                }
                val friends = Core.onlineFriends()
                roomLabel(room) + " · " + who + if (friends > 0) " · $friends ami${if (friends > 1) "s" else ""} en ligne" else ""
            }.collect { text ->
                try {
                    NotificationManagerCompat.from(this@KlaxonService).notify(Notif.SERVICE_ID, Notif.service(this@KlaxonService, text))
                } catch (_: SecurityException) {
                }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_QUIT = "fr.stlkm.klaxon.QUIT"

        /** Seulement sur une action de l'utilisateur : ouvrir l'appli, changer de salon, « Démarrer » sur la montre. */
        fun start(context: Context) {
            if (!Core.hasRoom) return
            Core.listening = true
            try {
                ContextCompat.startForegroundService(context, Intent(context, KlaxonService::class.java))
            } catch (_: Exception) {
                // démarrage en fond refusé par Android : l'écran le relancera à la prochaine ouverture
            }
        }

        fun quit(context: Context) {
            context.startService(Intent(context, KlaxonService::class.java).setAction(ACTION_QUIT))
        }
    }
}

/** Boutons des notifications qui n'ouvrent pas l'appli. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val nm = NotificationManagerCompat.from(context)
        when (intent.action) {
            Notif.ACTION_RIPOSTE -> {
                Core.quickHonk(intent.getStringExtra("pid"))
                nm.cancel(Notif.HONK_ID)
            }
            Notif.ACTION_DECLINE -> {
                Core.dropNotice("inv/" + intent.getStringExtra("from"))
                nm.cancel(Notif.INVITE_ID)
            }
        }
    }
}
