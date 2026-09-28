package fr.stlkm.klaxon

import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** Hors du Play Store, personne ne saurait qu'une version est sortie : Klaxon demande
 *  lui-même à GitHub, une fois par jour au plus, et un bandeau propose la nouvelle.
 *  Rien n'est envoyé : on lit la dernière release, c'est tout. */
object Update {
    private const val API = "https://api.github.com/repos/Yurogin/klaxon/releases/latest"
    const val PAGE = "https://github.com/Yurogin/klaxon/releases/latest"
    private const val EVERY = 24 * 60 * 60 * 1000L   // ms entre deux vérifications

    /** Ce que le bouton « Vérifier » affiche sous la version : vide tant qu'on n'a rien demandé. */
    val status = MutableStateFlow("")

    /** `force` : l'utilisateur a appuyé sur « Vérifier », on ne lui oppose pas le délai d'un jour
     *  et on lui répond, même quand il n'y a rien de neuf. */
    fun check(force: Boolean = false) {
        val now = System.currentTimeMillis()
        // Une horloge remise en arrière ne doit pas bloquer la vérification pour toujours.
        val last = Core.lastCheck
        if (!force && last in (now - EVERY + 1)..now) return
        Core.lastCheck = now
        if (force) status.value = "Recherche…"
        thread(isDaemon = true) {
            val tag = latestTag()?.trimStart('v', 'V')
            if (tag != null && newer(tag, BuildConfig.VERSION_NAME)) {
                Core.newVersion(tag)
                if (force) status.value = "Klaxon $tag est sorti"
            } else if (force) {
                status.value = if (tag == null) "GitHub n'a pas répondu" else "À jour"
            }
        }
    }

    /** Le nom du tag de la dernière release (`v1.1`), ou rien si GitHub ne répond pas. */
    private fun latestTag(): String? = try {
        val c = (URL(API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Klaxon")
        }
        try {
            if (c.responseCode != 200) null
            else JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                .optString("tag_name").takeIf { it.isNotBlank() }
        } finally {
            c.disconnect()
        }
    } catch (e: Exception) {
        null   // pas de réseau, GitHub en panne : on réessaiera demain
    }

    /** Compare `v1.10` à `1.9` par nombres, pas par lettres : 10 vient après 9. */
    private fun newer(remote: String, local: String): Boolean {
        val a = numbers(remote)
        val b = numbers(local)
        if (a.isEmpty()) return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun numbers(v: String) = Regex("\\d+").findAll(v).map { it.value.toIntOrNull() ?: 0 }.toList()
}
