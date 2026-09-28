package fr.stlkm.klaxon

import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// Même protocole que index.html et windows/klaxon.py : mêmes salons, mêmes amis, tout le monde se voit.

/** Un serveur MQTT public et ses portes d'entrée : TLS direct, puis WebSocket sécurisé (passe mieux les pare-feux). */
class Broker(val host: String, val routes: List<String>)

val BROKERS = listOf(
    Broker("broker.emqx.io", listOf("ssl://broker.emqx.io:8883", "wss://broker.emqx.io:8084/mqtt")),
    Broker("broker.hivemq.com", listOf("ssl://broker.hivemq.com:8883", "wss://broker.hivemq.com:8884/mqtt")),
    Broker("test.mosquitto.org", listOf("ssl://test.mosquitto.org:8886", "wss://test.mosquitto.org:8081/")),
)
const val APP_PREFIX = "klaxon-stlkm/v1/"
const val SITE = "https://klaxon.stlkm.fr/"
const val U = APP_PREFIX + "u/"   // u/<id ami>/s : statut chiffré (retenu), u/<id ami>/i : boîte aux lettres
const val KEEPALIVE = 60          // s : plus long que sur PC, pour ménager la batterie
const val COOLDOWN = 350L         // ms entre deux klaxons
const val RIPOSTE = 4000L         // ms pendant lesquels on peut riposter d'un clic
const val HEARTBEAT = 60_000L     // ms entre deux statuts envoyés aux amis
const val FRESH = 150_000L        // ms : un ami sans nouvelles depuis plus longtemps est hors ligne
val START = System.currentTimeMillis()  // distingue les klaxons de deux lancements successifs

private val random = SecureRandom()

fun sha(s: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

/** Le nom du salon n'apparaît jamais en clair sur le serveur. */
fun roomBase(room: String) = APP_PREFIX + hex(sha(room.trim().lowercase())).take(20) + "/"

// ---------- amis ----------
// Du code ami on tire un identifiant public (sa boîte sur les serveurs) et une clé AES :
// seul qui connaît le code lit son statut et peut lui écrire.
const val ALPHA = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"  // ni 0/O ni 1/I/L : se dicte sans erreur
private val TID = Regex("[0-9a-f]{20}")
private val PRIVATE = Regex("~[a-z0-9]{8,}$", RegexOption.IGNORE_CASE)

fun randomText(n: Int, alpha: String) = String(CharArray(n) { alpha[random.nextInt(alpha.length)] })
fun normCode(s: Any?) = (s as? String ?: "").uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }
fun validCode(c: String) = c.length == 10 && c.all { it in ALPHA }
fun showCode(c: String) = c.take(5) + "-" + c.drop(5)
fun validTid(u: Any?) = u is String && TID.matches(u)

class Ident(val tid: String, val key: SecretKeySpec)

private val idents = HashMap<String, Ident>()
fun ident(code: String): Ident = synchronized(idents) {
    idents.getOrPut(code) {
        Ident(hex(sha("klaxon-ami/$code")).take(20), SecretKeySpec(sha("klaxon-cle/$code"), "AES"))
    }
}

/** 12 octets d'IV puis le chiffré AES-GCM (tag compris), comme WebCrypto. */
fun seal(code: String, obj: JSONObject): ByteArray {
    val iv = ByteArray(12).also(random::nextBytes)
    val c = Cipher.getInstance("AES/GCM/NoPadding")
    c.init(Cipher.ENCRYPT_MODE, ident(code).key, GCMParameterSpec(128, iv))
    return iv + c.doFinal(obj.toString().toByteArray())
}

fun unseal(code: String, data: ByteArray): JSONObject? = try {
    val c = Cipher.getInstance("AES/GCM/NoPadding")
    c.init(Cipher.DECRYPT_MODE, ident(code).key, GCMParameterSpec(128, data, 0, 12))
    JSONObject(String(c.doFinal(data, 12, data.size - 12)))
} catch (e: Exception) {
    null
}

// Un groupe privé est un salon dont le nom finit par ~ et un secret : impossible à deviner.
fun isPrivate(room: String) = PRIVATE.containsMatchIn(room)
fun roomLabel(room: String) = if (isPrivate(room)) "🔒 " + room.substringBeforeLast("~") else room
fun sameRoom(a: String, b: String) = a.trim().lowercase() == b.trim().lowercase()
fun newGroup(name: String) = name.trim().replace("~", "-").take(40) + "~" + randomText(10, ALPHA.lowercase())

/** Un lien d'invitation collé dans le champ Salon donne le salon. */
fun roomFromText(text: String): String {
    val t = text.trim()
    if (!t.startsWith(SITE) && !t.startsWith(SITE.removeSuffix("/"))) return t
    return java.net.URLDecoder.decode(t.substringAfter("#", ""), "UTF-8").trim()
}

fun JSONObject.str(key: String, max: Int = 24) = (opt(key)?.takeIf { it != JSONObject.NULL }?.toString() ?: "?").take(max)
