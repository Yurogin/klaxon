package fr.stlkm.klaxon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object Notif {
    const val SERVICE_ID = 1
    const val HONK_ID = 2
    const val INVITE_ID = 3
    const val ACTION_RIPOSTE = "fr.stlkm.klaxon.RIPOSTE"
    const val ACTION_DECLINE = "fr.stlkm.klaxon.DECLINE"
    const val EXTRA_JOIN = "join"
    private const val CH_SERVICE = "ecoute"
    private const val CH_HONK = "klaxons2"
    private const val CH_INVITE = "invitations2"
    private const val CH_QUIET = "discret"  // la montre sonne à la place du téléphone
    private val VIBRATION = longArrayOf(0, 300, 120, 300)
    private const val YELLOW = 0xFFFFC629.toInt()

    fun channels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "Klaxon écoute", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Reste affichée tant que Klaxon écoute en fond"
            setShowBadge(false)
        })
        // le son est joué par l'appli elle-même (celui choisi par l'autre) : pas de son de notification,
        // mais une vibration, que la montre recopie
        nm.deleteNotificationChannel("klaxons")
        nm.deleteNotificationChannel("invitations")
        nm.createNotificationChannel(NotificationChannel(CH_HONK, "Klaxons reçus", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)
            enableVibration(true)
            vibrationPattern = VIBRATION
        })
        nm.createNotificationChannel(NotificationChannel(CH_INVITE, "Invitations et amis", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)
            enableVibration(true)
            vibrationPattern = VIBRATION
        })
        nm.createNotificationChannel(NotificationChannel(CH_QUIET, "Quand la montre sonne", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Klaxons et invitations déjà joués sur la montre : le téléphone reste silencieux"
            setSound(null, null)
            enableVibration(false)
        })
    }

    private fun openApp(context: Context, request: Int, join: String? = null): PendingIntent {
        val i = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (join != null) i.putExtra(EXTRA_JOIN, join)
        return PendingIntent.getActivity(context, request, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun action(context: Context, request: Int, action: String, extras: Intent.() -> Unit) =
        PendingIntent.getBroadcast(context, request, Intent(context, ActionReceiver::class.java).setAction(action).apply(extras),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun service(context: Context, text: String): Notification {
        val quit = PendingIntent.getService(context, 10,
            Intent(context, KlaxonService::class.java).setAction(KlaxonService.ACTION_QUIT), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_notif)
            .setColor(YELLOW)
            .setContentTitle("Klaxon écoute")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(openApp(context, 11))
            .addAction(0, "Quitter", quit)
            .build()
    }

    private fun post(context: Context, id: Int, n: Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (_: SecurityException) {  // notifications refusées
        }
    }

    fun honk(context: Context, pid: String, text: String, quiet: Boolean) = post(context, HONK_ID,
        NotificationCompat.Builder(context, if (quiet) CH_QUIET else CH_HONK)
            .setSmallIcon(R.drawable.ic_notif)
            .setColor(YELLOW)
            .setContentTitle(text)
            .setContentText("Touche « Riposter » pour lui répondre")
            .setVibrate(VIBRATION)
            .setLocalOnly(WearSync.watches.isNotEmpty())  // la montre a Klaxon : elle affiche sa propre alerte
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .setContentIntent(openApp(context, 20))
            .addAction(0, "↩ Riposter", action(context, 21, ACTION_RIPOSTE) { putExtra("pid", pid) })
            .build())

    fun invite(context: Context, from: String, text: String, room: String, quiet: Boolean) = post(context, INVITE_ID,
        NotificationCompat.Builder(context, if (quiet) CH_QUIET else CH_INVITE)
            .setSmallIcon(R.drawable.ic_notif)
            .setColor(YELLOW)
            .setContentTitle(text)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setVibrate(VIBRATION)
            .setLocalOnly(WearSync.watches.isNotEmpty())  // la montre a Klaxon : elle affiche sa propre alerte
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp(context, 30))
            .addAction(0, "Rejoindre", openApp(context, 31, room))
            .addAction(0, "Non", action(context, 32, ACTION_DECLINE) { putExtra("from", from) })
            .build())
}
