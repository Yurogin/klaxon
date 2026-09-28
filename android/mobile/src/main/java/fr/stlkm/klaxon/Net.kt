package fr.stlkm.klaxon

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

data class Peer(val name: String, val sound: String, val u: String)
data class FriendStatus(val name: String, val room: String, val t: Long)

/** Ce que le réseau signale à l'appli. Appelé depuis les fils réseau. */
interface NetEvents {
    fun changed()                                   // connexion ou gens du salon
    fun honk(pid: String, name: String, everyone: Boolean, key: String, sound: String, hold: Boolean)
    fun honkEnd(key: String)
    fun friendStatus(code: String, name: String)    // statut d'un ami reçu (ou effacé)
    fun invite(from: String, name: String, room: String)
    fun friendAddedMe(from: String, name: String, asked: Boolean)
    fun friendAsked(from: String, name: String)     // demande faite depuis le salon
}

/** Parle aux trois serveurs à la fois : on envoie partout, on fusionne ce qu'on reçoit. */
class Net(
    val myId: String,
    val code: String,
    @Volatile var name: String,
    @Volatile var sound: String,
    room: String,
    friends: List<String>,
    private val events: NetEvents,
) {
    @Volatile var room: String = room.trim(); private set
    @Volatile var base: String = roomBase(room); private set
    val tid = ident(code).tid
    private val lock = Any()
    private var friends: Map<String, String> = friends.associateBy { ident(it).tid }  // id public -> code
    private val fstatus = HashMap<String, FriendStatus>()
    val pending: MutableSet<String> = ConcurrentHashMap.newKeySet()  // gens du salon à qui on a demandé d'être amis
    private val seen = HashMap<String, Long>()  // un message arrive une fois par serveur
    private var seq = 0
    @Volatile private var closed = false
    private val links = BROKERS.map { Link(it) }

    fun presence() = JSONObject().put("name", name).put("sound", sound).put("u", tid).toString().toByteArray()
    val onlineCount get() = links.count { it.online }

    fun peers(): List<Pair<String, Peer>> {
        val merged = HashMap<String, Peer>()
        for (l in links) merged.putAll(l.peers)
        return merged.toList().sortedBy { it.second.name.lowercase() }
    }

    // ---------- klaxons ----------
    fun nextN() = synchronized(lock) { "$START-${++seq}" }

    /** Début d'un klaxon (to = null : tout le monde). Renvoie son numéro. */
    fun press(to: String?): String {
        val n = nextN()
        val msg = JSONObject().put("id", myId).put("name", name).put("to", to ?: JSONObject.NULL)
            .put("n", n).put("sound", sound).put("hold", true)
        for (l in links) l.publish(base + "h", msg.toString().toByteArray(), 0)
        return n
    }

    fun release(n: String) {
        val msg = JSONObject().put("id", myId).put("n", n).toString().toByteArray()
        for (l in links) l.publish(base + "he", msg, 0)
    }

    fun setProfile(name: String, sound: String) {
        this.name = name
        this.sound = sound
        for (l in links) l.announce()
        publishStatus()
    }

    fun setRoom(room: String) {
        val new = roomBase(room)
        this.room = room.trim()
        if (new == base) return
        val old = base
        base = new
        for (l in links) l.switchRoom(old)
        events.changed()
    }

    fun close() {
        closed = true
        val ts = links.map { thread { it.close() } }
        ts.forEach { it.join(2500) }
    }

    // ---------- amis ----------
    fun topicsWanted(): Set<String> = synchronized(lock) { setOf("$U$tid/i") + friends.keys.map { "$U$it/s" } }

    fun setFriends(codes: List<String>) {
        synchronized(lock) {
            friends = codes.associateBy { ident(it).tid }
            fstatus.keys.retainAll(friends.keys)
            pending.removeAll(friends.keys)
        }
        val want = topicsWanted()
        for (l in links) l.syncSubs(want)
    }

    /** Le statut d'un ami s'il est en ligne, sinon null. */
    fun friendStatus(code: String): FriendStatus? {
        val s = synchronized(lock) { fstatus[ident(code).tid] } ?: return null
        return s.takeIf { System.currentTimeMillis() - it.t < FRESH }
    }

    fun publishStatus(only: Link? = null) {
        val payload = seal(code, JSONObject().put("name", name).put("room", room).put("t", System.currentTimeMillis()))
        for (l in if (only != null) listOf(only) else links) l.publish("$U$tid/s", payload, 1, retain = true)
    }

    fun sendInbox(to: String, msg: JSONObject) {
        msg.put("from", code).put("name", name).put("n", nextN())
        val payload = seal(to, msg)
        for (l in links) l.publish(U + ident(to).tid + "/i", payload, 1)
    }

    fun askFriend(pid: String, u: String) {
        pending.add(u)
        val msg = JSONObject().put("id", myId).put("to", pid).put("name", name).put("code", code).put("n", nextN())
        for (l in links) l.publish(base + "f", msg.toString().toByteArray(), 1)
    }

    private fun fresh(key: String): Boolean = synchronized(lock) {
        val now = System.currentTimeMillis()
        if (key in seen) return false
        seen[key] = now
        if (seen.size > 500) seen.values.removeAll { now - it > 60_000 }
        true
    }

    // ---------- réception ----------
    private fun userMessage(rest: String, payload: ByteArray) {
        val parts = rest.split("/")
        if (parts.size != 2) return
        val (t, kind) = parts
        if (kind == "s") {
            val fcode = synchronized(lock) { friends[t] } ?: return
            if (payload.isEmpty()) {
                synchronized(lock) { fstatus.remove(t) }
                events.friendStatus(fcode, "")
                return
            }
            val s = unseal(fcode, payload) ?: return
            val time = (s.opt("t") as? Number)?.toLong() ?: return
            val name = s.str("name")
            synchronized(lock) {
                val old = fstatus[t]
                if (old != null && old.t > time) return
                fstatus[t] = FriendStatus(name, (s.opt("room") as? String ?: "").take(80), time)
            }
            events.friendStatus(fcode, name)
        } else if (kind == "i" && t == tid) {
            val m = unseal(code, payload) ?: return
            val from = normCode(m.opt("from"))
            val name = m.str("name")
            if (!validCode(from) || from == code || !fresh("i/$from/${m.opt("n")}")) return
            val room = (m.opt("room") as? String ?: "").trim()
            when (m.opt("type")) {
                "invite" -> if (room.isNotEmpty()) events.invite(from, name, room.take(80))
                "ami" -> events.friendAddedMe(from, name, ident(from).tid in pending)
            }
        }
    }

    private fun roomMessage(link: Link, sub: String, payload: ByteArray) {
        if (sub.startsWith("p/")) {
            val pid = sub.substring(2)
            if (pid == myId) return
            if (payload.isEmpty()) link.peers.remove(pid)
            else {
                val p = try { JSONObject(String(payload)) } catch (e: Exception) { return }
                val u = p.opt("u")
                link.peers[pid] = Peer(p.str("name"), soundOf(p.opt("sound")).key, if (validTid(u)) u as String else "")
            }
            events.changed()
            return
        }
        val m = try { JSONObject(String(payload)) } catch (e: Exception) { return }
        val pid = m.opt("id") as? String ?: return
        if (pid == myId) return
        when (sub) {
            "h" -> {
                val to = m.opt("to")
                if (!(to == null || to == JSONObject.NULL || to == myId)) return
                val n = m.opt("n")
                val key = if (n == null || n == JSONObject.NULL) "$pid/${Math.random()}" else "$pid/$n"  // sans numéro : ancienne version
                if (!fresh(key)) return
                events.honk(pid, m.str("name"), to == null || to == JSONObject.NULL, key,
                    soundOf(m.opt("sound")).key, m.optBoolean("hold"))
            }
            "he" -> events.honkEnd("$pid/${m.opt("n")}")
            "f" -> {
                if (m.opt("to") != myId || !fresh("f/$pid/${m.opt("n")}")) return
                val from = normCode(m.opt("code"))
                if (validCode(from) && from != code) events.friendAsked(from, m.str("name"))
            }
        }
    }

    /** Une connexion à un serveur. Présence : message retenu sur <salon>/p/<id>, que le serveur
     *  efface tout seul (testament MQTT) si le client disparaît. */
    inner class Link(private val broker: Broker) {
        @Volatile var online = false
        val peers = ConcurrentHashMap<String, Peer>()
        @Volatile private var client: MqttAsyncClient? = null
        @Volatile private var subs = setOf<String>()
        @Volatile private var reconnect = false
        private val wake = Object()

        init {
            thread(isDaemon = true, name = "klaxon-" + broker.host) { loop() }
        }

        private fun loop() {
            var i = 0
            while (!closed) {
                val uri = broker.routes[i % broker.routes.size]
                val myBase = base
                val c = try {
                    MqttAsyncClient(uri, "klaxon-$myId", MemoryPersistence())
                } catch (e: Exception) {
                    i++; Thread.sleep(3000); continue
                }
                c.setCallback(object : MqttCallback {
                    override fun connectionLost(cause: Throwable?) = synchronized(wake) { wake.notifyAll() }
                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                    override fun messageArrived(topic: String, message: MqttMessage) {
                        try {
                            when {
                                topic.startsWith(U) -> userMessage(topic.substring(U.length), message.payload)
                                topic.startsWith(base) -> roomMessage(this@Link, topic.substring(base.length), message.payload)
                            }
                        } catch (_: Exception) {
                        }
                    }
                })
                val opts = MqttConnectOptions().apply {
                    isCleanSession = true
                    keepAliveInterval = KEEPALIVE
                    connectionTimeout = 10
                    mqttVersion = MqttConnectOptions.MQTT_VERSION_3_1_1
                    setWill(myBase + "p/" + myId, ByteArray(0), 1, true)
                }
                try {
                    c.connect(opts).waitForCompletion(15_000)
                } catch (e: Exception) {
                    try { c.close(true) } catch (_: Exception) {}
                    i++
                    Thread.sleep(if (i % broker.routes.size != 0) 1000 else 3000)
                    continue
                }
                client = c
                try {
                    subs = topicsWanted()
                    val topics = (listOf(myBase + "#") + subs).toTypedArray()
                    c.subscribe(topics, IntArray(topics.size) { 1 })
                    online = true
                    announce()
                    publishStatus(this)
                    events.changed()
                    synchronized(wake) { while (c.isConnected && !reconnect && !closed) wake.wait(5000) }
                } catch (_: Exception) {
                }
                online = false
                peers.clear()
                events.changed()
                if (c.isConnected) {  // changement de salon ou fermeture : on s'en va proprement
                    try { c.disconnect(1000).waitForCompletion(1500) } catch (_: Exception) {}
                }
                reconnect = false
                client = null
                try { c.close(true) } catch (_: Exception) {}
                if (!closed) Thread.sleep(500)
            }
        }

        fun publish(topic: String, payload: ByteArray, qos: Int, retain: Boolean = false) {
            val c = client ?: return
            if (!online) return
            try {
                c.publish(topic, payload, qos, retain)
            } catch (_: Exception) {
            }
        }

        fun announce() = publish(base + "p/" + myId, presence(), 1, retain = true)

        fun syncSubs(want: Set<String>) {
            val c = client ?: return
            if (!online) return
            try {
                val add = (want - subs).toTypedArray()
                val drop = (subs - want).toTypedArray()
                if (add.isNotEmpty()) c.subscribe(add, IntArray(add.size) { 1 })
                if (drop.isNotEmpty()) c.unsubscribe(drop)
                subs = want
            } catch (_: Exception) {
            }
        }

        /** Le testament n'est transmis qu'à la connexion : on se reconnecte avec le nouveau salon. */
        fun switchRoom(old: String) {
            peers.clear()
            publish(old + "p/" + myId, ByteArray(0), 1, retain = true)
            reconnect = true
            synchronized(wake) { wake.notifyAll() }
        }

        fun close() {
            val c = client
            if (c != null && online) {
                try {
                    c.publish(base + "p/" + myId, ByteArray(0), 1, true).waitForCompletion(1500)
                    c.publish("$U$tid/s", ByteArray(0), 1, true).waitForCompletion(1500)
                } catch (_: Exception) {
                }
            }
            synchronized(wake) { wake.notifyAll() }
        }
    }
}
