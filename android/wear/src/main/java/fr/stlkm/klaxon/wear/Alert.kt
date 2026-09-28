package fr.stlkm.klaxon.wear

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import fr.stlkm.klaxon.Player

/** Klaxon reçu : la montre joue le vrai son de l'autre, vibre, et propose de riposter. */
object Alert {
    private var p: Player? = null

    /** Sons à 22 kHz, gardés en cache : le calcul est lent sur une montre. */
    fun player(context: Context): Player = p ?: synchronized(this) {
        p ?: Player(22050, context.applicationContext.cacheDir).also {
            it.usage = AudioAttributes.USAGE_ALARM  // Wear OS coupe les sons de notification de toutes les applis
            p = it
        }
    }

    /** Pas de son en Ne pas déranger, Coucher ou Cinéma : le canal alarme passerait outre, on vérifie nous-mêmes. */
    private fun quiet(context: Context) =
        context.getSystemService(NotificationManager::class.java).currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
    private const val CHANNEL = "klaxons"
    private const val ID = 1
    const val INVITE_ID = 2

    private fun buzz(context: Context, pattern: LongArray) {
        (context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            ?: @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java))
            ?.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    private fun channel(context: Context) = context.getSystemService(NotificationManager::class.java)
        .createNotificationChannel(NotificationChannel(CHANNEL, "Klaxons et invitations", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)       // le son, c'est le klaxon joué juste avant
            enableVibration(false)     // déjà fait à la main
        })

    /** Invitation : coin-coin, vibration, et Rejoindre / Non au poignet. */
    fun invite(context: Context, from: String, text: String, room: String) {
        if (!quiet(context)) player(context).beepNow("canard")
        buzz(context, longArrayOf(0, 150, 100, 150))
        channel(context)
        fun action(request: Int, join: Boolean) = PendingIntent.getBroadcast(context, request,
            Intent(context, InviteReceiver::class.java).putExtra("from", from).putExtra("room", room).putExtra("join", join),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_horn)
            .setContentTitle(text)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .addAction(0, "Rejoindre", action(3, true))
            .addAction(0, "Non", action(4, false))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(INVITE_ID, n)
        } catch (_: SecurityException) {
        }
    }

    fun honk(context: Context, pid: String, text: String, key: String, sound: String, hold: Boolean) {
        if (!quiet(context)) {
            player(context).startNow(key, sound)
            if (!hold) player(context).endNow(key)
        }
        buzz(context, longArrayOf(0, 300, 120, 300))
        notify(context, pid, text)
    }

    private fun notify(context: Context, pid: String, text: String) {
        channel(context)
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        val riposte = PendingIntent.getBroadcast(context, 1, Intent(context, RiposteReceiver::class.java).putExtra("pid", pid),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_horn)
            .setContentTitle(text)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .setContentIntent(open)
            .addAction(0, "↩ Riposter", riposte)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(ID, n)
        } catch (_: SecurityException) {  // notifications refusées : le son et la vibration suffisent
        }
    }

    fun clear(context: Context) = NotificationManagerCompat.from(context).cancel(ID)
}

class InviteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getBooleanExtra("join", false)) Watch.send(context, "/klaxon/join", intent.getStringExtra("room") ?: return)
        else Watch.send(context, "/klaxon/decline", intent.getStringExtra("from") ?: return)
        NotificationManagerCompat.from(context).cancel(Alert.INVITE_ID)
    }
}

class RiposteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Watch.send(context, "/klaxon/quick", intent.getStringExtra("pid") ?: return)
        Alert.clear(context)
    }
}
