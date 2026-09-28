package fr.stlkm.klaxon

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Friend(val code: String, val name: String)
data class Notice(val key: String, val text: String, val yes: String, val action: () -> Unit)
data class Riposte(val pid: String, val name: String, val until: Long)
data class Flash(val text: String, val until: Long)

/** L'appli elle-même, sans l'écran : l'écran et le service de fond en lisent l'état.
 *  Tout ce qui change l'état passe par le fil principal. */
@SuppressLint("StaticFieldLeak")
object Core : NetEvents {
    lateinit var app: Context
        private set
    private lateinit var prefs: SharedPreferences
    val player by lazy { Player() }
    private val main = Handler(Looper.getMainLooper())
    private val myId = UUID.randomUUID().toString().replace("-", "").take(12)

    lateinit var code: String
        private set
    val name = MutableStateFlow("")
    val room = MutableStateFlow("")
    val sound = MutableStateFlow("klaxon")
    val friends = MutableStateFlow(listOf<Friend>())
    val groups = MutableStateFlow(listOf<String>())
    val online = MutableStateFlow(false)
    val peers = MutableStateFlow(listOf<Pair<String, Peer>>())
    val statusTick = MutableStateFlow(0)      // change quand un statut d'ami arrive ou expire
    val notices = MutableStateFlow(listOf<Notice>())
    val riposte = MutableStateFlow<Riposte?>(null)
    val flash = MutableStateFlow<Flash?>(null)
    val holding = MutableStateFlow<String?>(null)   // destinataire du klaxon en cours ("*" = tout le monde)
    val invitedAt = MutableStateFlow(mapOf<String, Long>())
    val running = MutableStateFlow(false)
    val quit = MutableStateFlow(0)                   // change quand on quitte : l'écran se ferme
    @Volatile var visible = false                    // l'écran est-il affiché ?
    var net: Net? = null
        private set

    private var lastPress = 0L
    private var holdN: String? = null

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        prefs = app.getSharedPreferences("klaxon", Context.MODE_PRIVATE)
        var c = normCode(prefs.getString("code", ""))
        if (!validCode(c)) {
            c = randomText(10, ALPHA)
            prefs.edit().putString("code", c).apply()
        }
        code = c
        name.value = prefs.getString("name", "") ?: ""
        room.value = prefs.getString("room", "") ?: ""
        sound.value = soundOf(prefs.getString("sound", "")).key
        friends.value = readList("friends").mapNotNull { o ->
            (o as? JSONObject)?.let { Friend(normCode(it.opt("code")), it.str("name")) }
        }.filter { validCode(it.code) && it.code != code }.distinctBy { it.code }
        groups.value = readList("groups").mapNotNull { it as? String }.filter(::isPrivate)
        loud.value = prefs.getBoolean("loud", false)
        player.loud = loud.value
        heartbeat()
    }

    private fun readList(k: String): List<Any?> = try {
        val a = JSONArray(prefs.getString(k, "[]"))
        List(a.length()) { a.opt(it) }
    } catch (e: Exception) {
        emptyList()
    }

    private fun ui(f: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) f() else main.post(f)
    }

    val hasRoom get() = room.value.isNotBlank() && name.value.isNotBlank()
    fun myName() = name.value.trim().take(24).ifEmpty { "Anonyme" }

    /** Lance la connexion (appelé par le service de fond). */
    fun connect() {
        if (net != null || !hasRoom) return
        net = Net(myId, code, myName(), sound.value, room.value, friends.value.map { it.code }, this)
        running.value = true
    }

    /** Écoute voulue par l'utilisateur. Faux après « Quitter », jusqu'à ce qu'il rouvre l'appli. */
    var listening: Boolean
        get() = prefs.getBoolean("listening", true)
        set(v) = prefs.edit().putBoolean("listening", v).apply()

    /** Le klaxon passe par le canal « alarme » : il sonne même téléphone en silencieux. */
    val loud = MutableStateFlow(false)

    fun setLoud(v: Boolean) {
        loud.value = v
        prefs.edit().putBoolean("loud", v).apply()
        player.loud = v
        player.beep(sound.value)
    }

    var linkHintDismissed: Boolean
        get() = prefs.getBoolean("hint_liens", false)
        set(v) = prefs.edit().putBoolean("hint_liens", v).apply()

    /** Quand Klaxon a demandé à GitHub, pour la dernière fois, s'il y a du nouveau. */
    var lastCheck: Long
        get() = prefs.getLong("maj", 0)
        set(v) = prefs.edit().putLong("maj", v).apply()

    fun stopListening() {
        listening = false
        quit.value++
    }

    /** Bouton Quitter : coupe tout et ferme l'écran. */
    fun quitApp() {
        stopListening()
        KlaxonService.quit(app)
    }

    fun disconnect() {
        release()
        val n = net ?: return
        net = null
        running.value = false
        online.value = false
        peers.value = emptyList()
        WearSync.push(app)  // la montre voit tout de suite que Klaxon a quitté
        Thread { n.close() }.start()
    }

    // ---------- champs ----------
    fun enter(newName: String, newRoom: String) {
        setName(newName)
        setRoom(newRoom)
    }

    fun setName(v: String) {
        val n = v.trim().take(24)
        if (n.isEmpty() || n == name.value) return
        name.value = n
        prefs.edit().putString("name", n).apply()
        net?.setProfile(myName(), sound.value)
    }

    fun setRoom(v: String) {
        val r = roomFromText(v).take(80)
        if (r.isEmpty() || r == room.value) return
        room.value = r
        prefs.edit().putString("room", r).apply()
        addGroup(r)
        riposte.value = null
        net?.setRoom(r)
        notices.value = notices.value.filterNot { it.key.startsWith("inv/") && it.text.contains("« ${roomLabel(r)} »") }
        KlaxonService.start(app)
    }

    fun pickSound(k: String) {
        sound.value = k
        prefs.edit().putString("sound", k).apply()
        player.beep(k)
        net?.setProfile(myName(), k)
    }

    // ---------- klaxonner : appuyer = ça klaxonne, relâcher = ça s'arrête ----------
    /** to : id d'un pair, ou null pour tout le monde. */
    fun press(to: String?) {
        val n = net ?: return
        if (holdN != null || !online.value || peers.value.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        if (now - lastPress < COOLDOWN) return
        lastPress = now
        val num = n.press(to)
        holdN = num
        holding.value = to ?: "*"
        player.start("moi/$num", sound.value)  // on s'entend klaxonner aussi
        main.postDelayed({ if (holdN == num) release() }, MAX_HOLD)
        if (riposte.value?.pid == to) riposte.value = null
    }

    fun release() {
        val num = holdN ?: return
        holdN = null
        holding.value = null
        net?.release(num)
        player.end("moi/$num")
    }

    /** Depuis la notification : un coup bref. */
    fun quickHonk(to: String?) {
        press(to)
        main.postDelayed({ release() }, 700)
    }

    /** Un seul appareil sonne : le téléphone si l'appli est à l'écran, sinon la montre si elle est là. */
    private fun watchAlerts() = !visible && WearSync.watches.isNotEmpty()

    override fun honk(pid: String, name: String, everyone: Boolean, key: String, sound: String, hold: Boolean) {
        val toWatch = watchAlerts()
        if (!toWatch) {
            player.start(key, sound)
            if (!hold) player.end(key)  // ancienne version : un coup simple
        }
        ui {
            val text = "📯 $name " + if (everyone) "klaxonne tout le monde !" else "te klaxonne !"
            val now = SystemClock.uptimeMillis()
            flash.value = Flash(text, now + 1200)
            riposte.value = Riposte(pid, name, now + RIPOSTE)
            main.postDelayed({ if ((flash.value?.until ?: 0) <= SystemClock.uptimeMillis()) flash.value = null }, 1250)
            main.postDelayed({ if ((riposte.value?.until ?: 0) <= SystemClock.uptimeMillis()) riposte.value = null }, RIPOSTE + 50)
            if (visible) vibrate(longArrayOf(0, 300))
            else {
                Notif.honk(app, pid, text, quiet = toWatch)  // discrète si c'est la montre qui sonne
                if (toWatch) WearSync.honk(app, pid, text, key, sound, hold)
            }
        }
    }

    override fun honkEnd(key: String) {
        player.end(key)
        WearSync.honkEnd(app, key)  // la montre ignore les klaxons qu'elle ne joue pas
    }

    override fun changed() = ui {
        val n = net ?: return@ui
        online.value = n.onlineCount > 0
        peers.value = n.peers()
        if (riposte.value != null && peers.value.none { it.first == riposte.value?.pid }) riposte.value = null
    }

    private fun vibrate(pattern: LongArray) {
        val v = if (Build.VERSION.SDK_INT >= 31) {
            (app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION") app.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        v.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    // ---------- amis ----------
    fun friendOnline(f: Friend) = net?.friendStatus(f.code)
    fun onlineFriends() = friends.value.count { friendOnline(it) != null }
    fun isFriend(c: String) = friends.value.any { it.code == c }

    private fun saveFriends(list: List<Friend>) {
        friends.value = list
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("code", it.code).put("name", it.name)) }
        prefs.edit().putString("friends", a.toString()).apply()
        net?.setFriends(list.map { it.code })
    }

    /** Ajoute un ami et le lui dit : il voit une demande, ou nous ajoute tout seul s'il l'avait demandé. */
    fun addFriend(c: String, n: String) {
        if (c == code || isFriend(c)) return
        saveFriends(friends.value + Friend(c, n.take(24).ifEmpty { "?" }))
        net?.sendInbox(c, JSONObject().put("type", "ami"))
    }

    fun removeFriend(c: String) = saveFriends(friends.value.filter { it.code != c })

    fun inviteFriend(f: Friend) {
        net?.sendInbox(f.code, JSONObject().put("type", "invite").put("room", room.value))
        invitedAt.value = invitedAt.value + (f.code to SystemClock.uptimeMillis())
        main.postDelayed({ statusTick.value++ }, 3050)
    }

    fun askFriend(pid: String, p: Peer) {
        net?.askFriend(pid, p.u)
        statusTick.value++
    }

    fun asked(u: String) = net?.pending?.contains(u) == true

    override fun friendStatus(code: String, name: String) = ui {
        if (name.isNotEmpty()) {
            val list = friends.value
            if (list.any { it.code == code && it.name != name }) {
                saveFriends(list.map { if (it.code == code) it.copy(name = name) else it })
            }
        }
        statusTick.value++
    }

    override fun invite(from: String, name: String, room: String) = ui {
        if (sameRoom(room, this.room.value)) return@ui
        val text = "📨 $name t'invite dans « ${roomLabel(room)} »"
        notice(Notice("inv/$from", text, "Rejoindre") { setRoom(room) })
        val toWatch = watchAlerts()
        if (!toWatch) player.beep("canard")
        if (visible) vibrate(longArrayOf(0, 150, 100, 150))
        else {
            Notif.invite(app, from, text, room, quiet = toWatch)
            if (toWatch) WearSync.invite(app, from, text, room)  // la montre sonne et propose Rejoindre / Non
        }
    }

    override fun friendAddedMe(from: String, name: String, asked: Boolean) = ui {
        if (isFriend(from)) return@ui
        if (asked) addFriend(from, name)
        else notice(Notice("ami/$from", "👋 $name t'a ajouté en ami", "Ajouter") { addFriend(from, name) })
    }

    override fun friendAsked(from: String, name: String) = ui {
        if (!isFriend(from)) notice(Notice("ami/$from", "👋 $name veut être ton ami", "Accepter") { addFriend(from, name) })
    }

    /** Une version plus récente est sortie : le bandeau mène à la page des releases. */
    fun newVersion(version: String) = ui {
        notice(Notice("maj", "⬆️ Klaxon $version est sorti", "Voir") {
            val page = Intent(Intent.ACTION_VIEW, Uri.parse(Update.PAGE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                app.startActivity(page)
            } catch (e: Exception) {
                // Pas de navigateur : le bandeau a fait ce qu'il pouvait.
            }
        })
    }

    private fun notice(n: Notice) {
        notices.value = notices.value.filter { it.key != n.key }.takeLast(4) + n
    }

    fun answerNotice(yes: Boolean) {
        val n = notices.value.firstOrNull() ?: return
        notices.value = notices.value.drop(1)
        if (yes) n.action()
    }

    fun dropNotice(key: String) {
        notices.value = notices.value.filter { it.key != key }
    }

    // ---------- groupes privés ----------
    private fun saveGroups(list: List<String>) {
        groups.value = list
        prefs.edit().putString("groups", JSONArray(list).toString()).apply()
    }

    fun addGroup(r: String) {
        if (isPrivate(r) && groups.value.none { sameRoom(it, r) }) saveGroups(groups.value + r)
    }

    fun forgetGroup(r: String) = saveGroups(groups.value.filter { it != r })
    fun createGroup(n: String) {
        if (n.isNotBlank()) setRoom(newGroup(n))
    }

    private fun heartbeat() {
        main.postDelayed({
            net?.publishStatus()
            statusTick.value++
            heartbeat()
        }, HEARTBEAT)
    }
}
