"""Klaxon — klaxonner d'ordinateur à ordinateur par Internet (2 à 6 personnes, ou plus).

Tout le monde met le même nom de salon : ceux qui sont dans le salon apparaissent tout seuls.
En plus de la version web : une touche qui klaxonne depuis n'importe quel logiciel, une icône
à côté de l'horloge (Klaxon tourne en fond) et le lancement avec Windows.
Passe par trois serveurs MQTT publics à la fois (si l'un rame, les autres suffisent),
rien à héberger, aucun compte. Même protocole que la version navigateur (index.html).

Amis : chaque installation a un code ami secret. Qui le connaît voit si tu es en ligne et dans
quel salon (statut chiffré), et peut t'inviter. Groupes privés : salons au nom secret (« nom~secret »).
"""
import ctypes
import hashlib
import json
import math
import os
import queue
import re
import socket
import struct
import sys
import tempfile
import threading
import time
import tkinter as tk
import tkinter.messagebox
import urllib.parse
import uuid
import wave
import webbrowser
from ctypes import wintypes

import paho.mqtt.client as mqtt
import pystray
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from PIL import Image, ImageDraw, ImageTk

# chaque serveur : deux portes d'entrée, TLS direct puis WebSocket sécurisé (passe mieux les pare-feux)
BROKERS = [
    ("broker.emqx.io", [(8883, "tcp", None), (8084, "websockets", "/mqtt")]),
    ("broker.hivemq.com", [(8883, "tcp", None), (8884, "websockets", "/mqtt")]),
    ("test.mosquitto.org", [(8886, "tcp", None), (8081, "websockets", "/")]),
]
APP_PREFIX = "klaxon-stlkm/v1/"
SITE = "https://klaxon.stlkm.fr/"
START = int(time.time() * 1000)  # distingue les klaxons de deux lancements successifs
COOLDOWN = 0.35        # secondes entre deux klaxons
MAX_HOLD = 4.0         # un klaxon long s'arrête tout seul au bout de ce temps
RIPOSTE = 4.0          # secondes pendant lesquelles on peut riposter d'un clic
HEARTBEAT = 60.0       # secondes entre deux statuts envoyés aux amis
FRESH = 150.0          # un ami sans nouvelles depuis plus longtemps est hors ligne
U = APP_PREFIX + "u/"  # u/<id ami>/s : statut chiffré (retenu), u/<id ami>/i : boîte aux lettres

CONFIG = os.path.join(os.environ.get("APPDATA") or os.path.expanduser("~"), "Klaxon", "config.json")

BG = "#15171c"
FG = "#f2f2f2"
MUTED = "#7c8190"
FIELD = "#23262e"
YELLOW = "#ffc629"
BLUE = "#2f6fed"
RED = "#e8412c"
GREEN = "#3ccf6e"

# nom -> (libellé, emoji, durée minimale d'un simple clic)
SOUNDS = {
    "klaxon": ("Klaxon", "🚗", 0.6),
    "pouet": ("Pouet", "🤡", 0.35),
    "camion": ("Camion", "🚛", 0.8),
    "vuvuzela": ("Vuvuzela", "🎺", 0.7),
    "canard": ("Canard", "🦆", 0.2),
}

try:
    import winsound
    import winreg
except ImportError:
    winsound = winreg = None

SINGLE_PORT = 47475    # une seule instance : la deuxième réveille la première puis s'en va
RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"

# touches acceptées pour le raccourci global (code de touche virtuelle Windows -> nom affiché) :
# rien qui serve à écrire, sinon la touche serait volée à tous les autres logiciels
HOTKEYS = {0x70 + i: "F%d" % (i + 1) for i in range(24)}
HOTKEYS.update({0x13: "Pause", 0x91: "Arrêt défil", 0x2D: "Inser", 0x24: "Début", 0x23: "Fin",
                0x21: "Page ↑", 0x22: "Page ↓", 0x6A: "Pavé *", 0x6B: "Pavé +", 0x6D: "Pavé -",
                0x6F: "Pavé /"})
HOTKEYS.update({0x60 + i: "Pavé %d" % i for i in range(10)})
DEFAULT_HOTKEY = 0x78  # F9


def sound_of(name):
    return name if name in SOUNDS else "klaxon"


def synth(kind, rate):
    """Une boucle d'exactement 1 s (fréquences entières) : un klaxon long boucle sans clic.
    Mêmes formules que dans index.html."""
    tau2 = 2 * math.pi
    out = []
    phase = 0.0
    for i in range(rate):
        t = i / rate
        s = 0.0
        if kind == "klaxon":
            for f in (415, 523):
                s += math.tanh(4 * math.sin(tau2 * f * t)) + 0.3 * math.sin(tau2 * 2 * f * t)
        elif kind == "camion":
            for f in (185, 233, 277):
                s += math.tanh(2.5 * math.sin(tau2 * f * t)) + 0.5 * math.sin(tau2 * 2 * f * t)
            s += 0.6 * math.sin(tau2 * 92 * t)
        elif kind == "vuvuzela":
            ph = tau2 * 235 * t + 0.6 * math.sin(tau2 * 5 * t)
            for k in range(1, 11):
                s += math.sin(k * ph) / k ** 0.8
        elif kind == "pouet":
            tau, length = t % 0.5, 0.32
            if tau < 1 / rate:
                phase = 0.0
            if tau < length:
                phase += tau2 * (360 + 120 * math.sin(math.pi * tau / length)) / rate
                s = (math.tanh(3 * math.sin(phase)) + 0.4 * math.sin(2 * phase)) * \
                    math.sin(math.pi * tau / length) ** 0.6
        elif kind == "canard":
            tau, length = t % (1 / 3), 0.17
            if tau < 1 / rate:
                phase = 0.0
            if tau < length:
                phase += tau2 * (210 - 300 * tau) / rate
                s = math.tanh(8 * math.sin(phase)) * (0.6 + 0.4 * math.sin(tau2 * 1100 * t)) * \
                    min(1.0, tau / 0.01) * math.sqrt(1 - tau / length)
        out.append(s)
    peak = max(abs(v) for v in out) or 1.0
    return [v / peak * 0.9 for v in out]


def sound_file(kind):
    path = os.path.join(tempfile.gettempdir(), "klaxon_v2_%s.wav" % kind)
    if not os.path.exists(path):
        rate = 22050
        frames = b"".join(struct.pack("<h", int(v * 32000)) for v in synth(kind, rate))
        with wave.open(path, "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(rate)
            w.writeframes(frames)
    return path


def load_config():
    try:
        with open(CONFIG, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def save_config(cfg):
    try:
        os.makedirs(os.path.dirname(CONFIG), exist_ok=True)
        with open(CONFIG, "w", encoding="utf-8") as f:
            json.dump(cfg, f, ensure_ascii=False)
    except OSError:
        pass


def room_base(room):
    # le nom du salon n'apparaît jamais en clair sur le serveur
    return APP_PREFIX + hashlib.sha256(room.strip().lower().encode()).hexdigest()[:20] + "/"


# --- amis : mêmes calculs que dans index.html ---------------------------------
ALPHA = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"  # ni 0/O ni 1/I/L : se dicte sans erreur
PRIVATE = re.compile(r"~[a-z0-9]{8,}$", re.I)
TID = re.compile(r"[0-9a-f]{20}")


def random_text(n, alpha):
    return "".join(alpha[b % len(alpha)] for b in os.urandom(n))


def norm_code(s):
    return "".join(c for c in str(s or "").upper() if c.isascii() and c.isalnum())


def valid_code(c):
    return len(c) == 10 and all(ch in ALPHA for ch in c)


def show_code(c):
    return c[:5] + "-" + c[5:]


_ids = {}


def ident(code):
    """Du code ami : un identifiant public (sa boîte sur les serveurs) et une clé AES."""
    if code not in _ids:
        tid = hashlib.sha256(("klaxon-ami/" + code).encode()).hexdigest()[:20]
        _ids[code] = (tid, AESGCM(hashlib.sha256(("klaxon-cle/" + code).encode()).digest()))
    return _ids[code]


def seal(code, obj):
    iv = os.urandom(12)  # 12 octets d'IV puis le chiffré AES-GCM (tag compris)
    return iv + ident(code)[1].encrypt(iv, json.dumps(obj).encode(), None)


def unseal(code, data):
    try:
        obj = json.loads(ident(code)[1].decrypt(bytes(data[:12]), bytes(data[12:]), None))
    except Exception:
        return None
    return obj if isinstance(obj, dict) else None


def is_private(room):
    return bool(PRIVATE.search(room))


def room_label(room):
    return "🔒 " + room.rsplit("~", 1)[0] if is_private(room) else room


def same_room(a, b):
    return a.strip().lower() == b.strip().lower()


class Link:
    """Une connexion à un serveur. Présence : message retenu sur <salon>/p/<id>, que le serveur
    efface tout seul (testament MQTT) si le client disparaît. Klaxons : <salon>/h (début)
    et <salon>/he (fin d'un klaxon long)."""

    def __init__(self, net, host, routes):
        self.net, self.host, self.routes = net, host, routes
        self.client = None
        self.online = False
        self.peers = {}  # id -> {"name", "sound", "u"}, vus sur ce serveur
        self.subs = set()
        threading.Thread(target=self._connect_loop, daemon=True).start()

    def _make_client(self, transport, path):
        c = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id="klaxon-" + self.net.my_id, transport=transport)
        if path:
            c.ws_set_options(path=path)
        c.tls_set()
        c.will_set(self.net.base + "p/" + self.net.my_id, b"", qos=1, retain=True)
        c.reconnect_delay_set(1, 10)
        c.on_connect = self._on_connect
        c.on_disconnect = self._on_disconnect
        c.on_message = self._on_message
        return c

    def _connect_loop(self):
        i = 0
        while True:
            port, transport, path = self.routes[i % len(self.routes)]
            c = self._make_client(transport, path)
            try:
                c.connect(self.host, port, keepalive=15)
            except (OSError, ValueError):
                i += 1
                time.sleep(1 if i % len(self.routes) else 3)
                continue
            self.client = c
            c.loop_forever(retry_first_connection=False)  # gère les reconnexions ensuite
            return

    def _on_connect(self, client, userdata, flags, rc, props=None):
        if rc.is_failure:
            return
        self.online = True
        self.subs = set(self.net.topics_wanted())
        client.subscribe([(self.net.base + "#", 1)] + [(t, 1) for t in self.subs])
        self.announce()
        self.net.publish_status(self)
        self.net.changed()

    def _on_disconnect(self, client, userdata, flags, rc, props=None):
        self.online = False
        with self.net.lock:
            self.peers.clear()
        self.net.changed()

    def _on_message(self, client, userdata, msg):
        if msg.topic.startswith(U):
            self.net.user_message(msg.topic[len(U):], msg.payload)
            return
        base = self.net.base
        if not msg.topic.startswith(base):
            return  # reste d'un ancien salon
        sub = msg.topic[len(base):]
        if sub.startswith("p/"):
            pid = sub[2:]
            if pid == self.net.my_id:
                return
            with self.net.lock:
                if not msg.payload:
                    self.peers.pop(pid, None)
                else:
                    try:
                        p = json.loads(msg.payload)
                        u = p.get("u")
                        self.peers[pid] = {"name": str(p.get("name", "?"))[:24], "sound": sound_of(p.get("sound")),
                                           "u": u if isinstance(u, str) and TID.fullmatch(u) else ""}
                    except (ValueError, AttributeError):
                        return
            self.net.changed()
        elif sub in ("h", "he", "f"):
            try:
                data = json.loads(msg.payload)
                if isinstance(data, dict):
                    if sub == "f":
                        self.net.friend_request(data)
                    else:
                        self.net.received(sub, data)
            except ValueError:
                pass

    def publish(self, sub, payload, qos=0, retain=False):
        return self.publish_raw(self.net.base + sub, payload, qos, retain)

    def publish_raw(self, topic, payload, qos=0, retain=False):
        if self.client and self.online:
            return self.client.publish(topic, payload, qos=qos, retain=retain)

    def sync_subs(self, want):
        c = self.client
        if not (c and self.online):
            return
        add, drop = want - self.subs, self.subs - want
        if add:
            c.subscribe([(t, 1) for t in add])
        if drop:
            c.unsubscribe(list(drop))
        self.subs = set(want)

    def announce(self):
        self.publish("p/" + self.net.my_id, self.net.presence(), qos=1, retain=True)

    def switch_room(self, old):
        with self.net.lock:
            self.peers.clear()
        c = self.client
        if c:
            # le testament n'est transmis qu'à la connexion : on se reconnecte, on_connect fait le reste
            if self.online:
                c.publish(old + "p/" + self.net.my_id, b"", qos=1, retain=True)
            c.will_set(self.net.base + "p/" + self.net.my_id, b"", qos=1, retain=True)
            try:
                c.reconnect()
            except (OSError, ValueError):
                pass

    def close(self):
        infos = [self.publish("p/" + self.net.my_id, b"", qos=1, retain=True),
                 self.publish_raw(U + self.net.tid + "/s", b"", qos=1, retain=True)]
        for info in infos:
            if info:
                info.wait_for_publish(1.5)
        if self.client:
            self.client.disconnect()


class Net:
    """Parle aux trois serveurs à la fois : on envoie partout, on fusionne ce qu'on reçoit."""

    def __init__(self, my_id, events, name, sound, room, code, friends):
        self.my_id = my_id
        self.events = events
        self.name = name
        self.sound = sound
        self.room = room.strip()
        self.base = room_base(room)
        self.code = code
        self.tid = ident(code)[0]
        self.friends = {ident(f["code"])[0]: f["code"] for f in friends}  # id public -> code
        self.fstatus = {}      # id public -> {"name", "room", "t"}, le plus récent des trois serveurs
        self.pending = set()   # ids des gens du salon à qui on a demandé d'être amis
        self.lock = threading.Lock()
        self.seq = 0
        self.seen_msgs = {}  # (id, n) -> heure : un klaxon arrive une fois par serveur
        self.links = [Link(self, host, routes) for host, routes in BROKERS]

    def presence(self):
        return json.dumps({"name": self.name, "sound": self.sound, "u": self.tid})

    # --- amis ------------------------------------------------------------
    def topics_wanted(self):
        with self.lock:
            return {U + self.tid + "/i"} | {U + t + "/s" for t in self.friends}

    def set_friends(self, friends):
        with self.lock:
            self.friends = {ident(f["code"])[0]: f["code"] for f in friends}
            self.fstatus = {t: s for t, s in self.fstatus.items() if t in self.friends}
            self.pending -= set(self.friends)
        want = self.topics_wanted()
        for l in self.links:
            l.sync_subs(want)

    def friend_status(self, code):
        """Le statut d'un ami s'il est en ligne, sinon None."""
        with self.lock:
            s = self.fstatus.get(ident(code)[0])
        return s if s and time.time() * 1000 - s["t"] < FRESH * 1000 else None

    def publish_status(self, only=None):
        payload = seal(self.code, {"name": self.name, "room": self.room, "t": int(time.time() * 1000)})
        for l in [only] if only else self.links:
            l.publish_raw(U + self.tid + "/s", payload, qos=1, retain=True)

    def next_n(self):
        self.seq += 1
        return "%d-%d" % (START, self.seq)

    def send_inbox(self, code, msg):
        payload = seal(code, dict(msg, **{"from": self.code, "name": self.name, "n": self.next_n()}))
        for l in self.links:
            l.publish_raw(U + ident(code)[0] + "/i", payload, qos=1)

    def ask_friend(self, pid, u):
        self.pending.add(u)
        payload = json.dumps({"id": self.my_id, "to": pid, "name": self.name, "code": self.code, "n": self.next_n()})
        for l in self.links:
            l.publish("f", payload, qos=1)

    def fresh(self, key):
        """Un message arrive une fois par serveur : on n'en traite qu'un."""
        now = time.time()
        with self.lock:
            if key in self.seen_msgs:
                return False
            self.seen_msgs[key] = now
            if len(self.seen_msgs) > 500:
                self.seen_msgs = {k: v for k, v in self.seen_msgs.items() if now - v < 60}
        return True

    def user_message(self, rest, payload):
        parts = rest.split("/")
        if len(parts) != 2:
            return
        tid, kind = parts
        if kind == "s":
            with self.lock:
                code = self.friends.get(tid)
            if not code:
                return
            if not payload:
                with self.lock:
                    self.fstatus.pop(tid, None)
                self.events.put(("friends",))
                return
            s = unseal(code, payload)
            t = s and s.get("t")
            if not isinstance(t, (int, float)) or isinstance(t, bool):
                return
            name = str(s.get("name", "?"))[:24]
            room = s.get("room") if isinstance(s.get("room"), str) else ""
            with self.lock:
                old = self.fstatus.get(tid)
                if old and old["t"] > t:
                    return
                self.fstatus[tid] = {"name": name, "room": room[:80], "t": t}
            self.events.put(("fname", code, name))
        elif kind == "i" and tid == self.tid:
            m = unseal(self.code, payload)
            if not m:
                return
            frm, name = norm_code(m.get("from")), str(m.get("name", "?"))[:24]
            if not valid_code(frm) or frm == self.code or not self.fresh("i/%s/%s" % (frm, m.get("n"))):
                return
            room = m.get("room")
            if m.get("type") == "invite" and isinstance(room, str) and room.strip():
                self.events.put(("invite", frm, name, room.strip()[:80]))
            elif m.get("type") == "ami":
                self.events.put(("ami", frm, name, ident(frm)[0] in self.pending))

    def friend_request(self, data):
        """Demande faite depuis le salon, avec le code de celui qui demande."""
        if data.get("to") != self.my_id or not self.fresh("f/%s/%s" % (data.get("id"), data.get("n"))):
            return
        frm = norm_code(data.get("code"))
        if valid_code(frm) and frm != self.code:
            self.events.put(("freq", frm, str(data.get("name", "?"))[:24]))

    def changed(self):
        self.events.put(("conn", self.online_count()))
        self.events.put(("peers",))

    def online_count(self):
        return sum(l.online for l in self.links)

    def received(self, kind, data):
        pid = data.get("id")
        if not pid or pid == self.my_id:
            return
        key = "%s/%s" % (pid, data.get("n"))
        if kind == "he":
            self.events.put(("hend", key))
            return
        if data.get("to") not in (None, self.my_id):
            return
        now = time.time()
        with self.lock:
            if data.get("n") is None:  # sans numéro : ancienne version, un seul serveur, pas de doublon
                key += "/%f" % now
            elif key in self.seen_msgs:
                return
            self.seen_msgs[key] = now
            if len(self.seen_msgs) > 500:
                self.seen_msgs = {k: v for k, v in self.seen_msgs.items() if now - v < 60}
        self.events.put(("honk", pid, str(data.get("name", "?"))[:24], data.get("to") is None,
                         key, sound_of(data.get("sound")), bool(data.get("hold"))))

    def press(self, to=None):
        """Début d'un klaxon (to=None : tout le monde, sinon l'id d'un pair). Renvoie son numéro."""
        self.seq += 1
        # n rend chaque klaxon unique, même d'un lancement à l'autre
        n = "%d-%d" % (START, self.seq)
        payload = json.dumps({"id": self.my_id, "name": self.name, "to": to, "n": n,
                              "sound": self.sound, "hold": True})
        for l in self.links:
            l.publish("h", payload)
        return n

    def release(self, n):
        payload = json.dumps({"id": self.my_id, "n": n})
        for l in self.links:
            l.publish("he", payload)

    def set_profile(self, name, sound):
        self.name, self.sound = name, sound
        for l in self.links:
            l.announce()
        self.publish_status()

    def set_room(self, room):
        new = room_base(room)
        self.room = room.strip()
        if new == self.base:
            return
        old, self.base = self.base, new
        for l in self.links:
            l.switch_room(old)
        self.changed()

    def close(self):
        threads = [threading.Thread(target=l.close) for l in self.links]
        for t in threads:
            t.start()
        for t in threads:
            t.join(2)

    def snapshot(self):
        with self.lock:
            merged = {}
            for l in self.links:
                merged.update(l.peers)
        return sorted(merged.items(), key=lambda x: x[1]["name"].lower())


class Player:
    """winsound ne joue qu'un son à la fois : le dernier klaxon arrivé prend la place."""

    def __init__(self, root):
        self.root = root
        self.files = {k: sound_file(k) for k in SOUNDS}
        self.key = None
        self.t0 = 0.0
        self.min = 0.0

    def start(self, key, sound):
        sound = sound_of(sound)
        self.key, self.t0, self.min = key, time.time(), SOUNDS[sound][2]
        if winsound:
            winsound.PlaySound(self.files[sound], winsound.SND_FILENAME | winsound.SND_ASYNC |
                               winsound.SND_LOOP | winsound.SND_NODEFAULT)
        else:
            self.root.bell()
        self.root.after(int(MAX_HOLD * 1000), lambda: self._stop(key))

    def end(self, key):
        if key != self.key:
            return
        wait = max(0.0, self.t0 + self.min - time.time())  # un clic joue au moins le son en entier
        self.root.after(int(wait * 1000), lambda: self._stop(key))

    def _stop(self, key):
        if key == self.key:
            self.key = None
            if winsound:
                winsound.PlaySound(None, 0)


class HotKey:
    """Raccourci global par RegisterHotKey : Windows prévient Klaxon quand la touche est enfoncée,
    même si un autre logiciel est au premier plan. Aucun espion du clavier."""

    WM_HOTKEY, WM_SET, WM_QUIT = 0x0312, 0x8001, 0x8002
    MOD_NOREPEAT = 0x4000

    def __init__(self, events, vk):
        self.events, self.vk, self.tid = events, vk, None
        self.ready = threading.Event()
        self.user32 = ctypes.windll.user32
        threading.Thread(target=self._run, daemon=True).start()

    def _run(self):
        u = self.user32
        self.tid = ctypes.windll.kernel32.GetCurrentThreadId()
        msg = wintypes.MSG()
        u.PeekMessageW(ctypes.byref(msg), None, 0, 0, 0)  # crée la file de messages du fil
        self.ready.set()
        self._register(self.vk)
        while u.GetMessageW(ctypes.byref(msg), None, 0, 0) > 0:
            if msg.message == self.WM_HOTKEY:
                self.events.put(("hk_down",))
            elif msg.message == self.WM_SET:
                self._register(msg.wParam)
            elif msg.message == self.WM_QUIT:
                break
        u.UnregisterHotKey(None, 1)

    def _register(self, vk):
        self.user32.UnregisterHotKey(None, 1)
        ok = bool(self.user32.RegisterHotKey(None, 1, self.MOD_NOREPEAT, vk))
        if ok:
            self.vk = vk
        else:  # déjà prise par un autre logiciel : on garde l'ancienne si possible
            self.user32.RegisterHotKey(None, 1, self.MOD_NOREPEAT, self.vk)
        self.events.put(("hk_status", vk, ok))

    def _post(self, message, wparam=0):
        if self.ready.wait(1):
            self.user32.PostThreadMessageW(self.tid, message, wparam, 0)

    def set(self, vk):
        self._post(self.WM_SET, vk)

    def stop(self):
        self._post(self.WM_QUIT)

    def is_down(self):
        return bool(self.user32.GetAsyncKeyState(self.vk) & 0x8000)


def autostart_command():
    if getattr(sys, "frozen", False):
        return '"%s" --fond' % sys.executable
    pythonw = os.path.join(os.path.dirname(sys.executable), "pythonw.exe")
    return '"%s" "%s" --fond' % (pythonw, os.path.abspath(__file__))


def autostart_get():
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY) as k:
            return winreg.QueryValueEx(k, "Klaxon")[0]
    except (OSError, AttributeError):
        return None


def autostart_set(on):
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_SET_VALUE) as k:
            if on:
                winreg.SetValueEx(k, "Klaxon", 0, winreg.REG_SZ, autostart_command())
            else:
                winreg.DeleteValue(k, "Klaxon")
    except (OSError, AttributeError):
        pass


def icon_image(size=64):
    """Le pavillon jaune de icon.svg, redessiné pour la barre des tâches."""
    k = size / 512
    im = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rounded_rectangle((0, 0, size - 1, size - 1), int(96 * k), fill=BG)
    d.polygon([(120 * k, 206 * k), (180 * k, 206 * k), (330 * k, 120 * k), (330 * k, 392 * k),
               (180 * k, 306 * k), (120 * k, 306 * k)], fill=YELLOW)
    w = max(2, int(26 * k))
    d.arc((306 * k, 196 * k, 434 * k, 316 * k), -62, 62, fill=YELLOW, width=w)
    d.arc((282 * k, 160 * k, 522 * k, 352 * k), -62, 62, fill=YELLOW, width=w)
    return im


def wake_other_instance():
    """True si un Klaxon tourne déjà : on lui demande de se montrer."""
    try:
        with socket.create_connection(("127.0.0.1", SINGLE_PORT), timeout=0.5) as c:
            c.sendall(b"show")
            return c.recv(16) == b"klaxon"
    except OSError:
        return False


class App:
    def __init__(self, start_hidden=False):
        self.my_id = uuid.uuid4().hex[:12]
        self.events = queue.Queue()
        self.last_press = 0.0
        self.flash_until = 0
        self.online = False
        self.holding = None   # (numéro, bouton)
        self.riposte = None   # (id, nom, jusqu'à)
        self.blink = False

        self.cfg = load_config()
        default_name = os.environ.get("USERNAME") or socket.gethostname()

        self.root = tk.Tk()
        self.root.title("Klaxon")
        self.root.configure(bg=BG)
        self.root.geometry("560x760")
        self.root.minsize(480, 620)
        self.player = Player(self.root)
        self.name = tk.StringVar(value=self.cfg.get("name") or default_name[:24])
        self.room = tk.StringVar(value=self.cfg.get("room") or "")
        self.sound = sound_of(self.cfg.get("sound"))

        self.code = norm_code(self.cfg.get("code"))
        if not valid_code(self.code):
            self.code = self.cfg["code"] = random_text(10, ALPHA)
            save_config(self.cfg)
        self.friends = []  # {"code", "name"}
        for f in self.cfg.get("friends") or []:
            c = norm_code(f.get("code") if isinstance(f, dict) else None)
            if valid_code(c) and c != self.code and all(x["code"] != c for x in self.friends):
                self.friends.append({"code": c, "name": str(f.get("name", "?"))[:24]})
        self.groups = [g for g in self.cfg.get("groups") or [] if isinstance(g, str) and is_private(g)]
        self.notices = []      # (clé, texte, bouton, action) : invitations et demandes à traiter
        self.invited_at = {}   # code -> heure de la dernière invitation envoyée
        self.fwin = None       # fenêtre des amis

        self.fields = tk.Frame(self.root, bg=BG)
        self.fields.pack(fill="x", padx=20, pady=(18, 6))
        self.fields.columnconfigure(1, weight=1)
        self.entries = []
        for row, (label, var, apply) in enumerate((("Moi", self.name, self.apply_name),
                                                   ("Salon", self.room, self.apply_room))):
            tk.Label(self.fields, text=label, bg=BG, fg=MUTED, font=("Segoe UI", 14)).grid(
                row=row, column=0, sticky="w", pady=4)
            e = tk.Entry(self.fields, textvariable=var, font=("Segoe UI", 16, "bold"), bg=FIELD,
                         fg=FG, insertbackground=FG, relief="flat")
            e.grid(row=row, column=1, sticky="ew", padx=(12, 0), pady=4, ipady=4)
            e.bind("<Return>", lambda ev, a=apply: (a(), self.root.focus()))
            e.bind("<FocusOut>", lambda ev, a=apply: a())
            self.entries.append(e)
        self.invite_btn = tk.Button(self.fields, text="🔗 Inviter", command=self.invite, font=("Segoe UI", 12, "bold"),
                                    bg=FIELD, fg=FG, activebackground="#2f333d", activeforeground=FG,
                                    relief="flat", cursor="hand2", padx=10)
        self.invite_btn.grid(row=1, column=2, sticky="nsew", padx=(8, 0), pady=4)
        self.friends_btn = tk.Button(self.fields, text="👥 Amis", command=self.open_friends,
                                     font=("Segoe UI", 12, "bold"), bg=FIELD, fg=FG, activebackground="#2f333d",
                                     activeforeground=FG, relief="flat", cursor="hand2", padx=10)
        self.friends_btn.grid(row=0, column=2, sticky="nsew", padx=(8, 0), pady=4)

        self.sound_row = tk.Frame(self.root, bg=BG)
        self.sound_row.pack(fill="x", padx=20, pady=(4, 0))
        self.sound_btns = {}
        for i, (k, (label, emoji, _)) in enumerate(SOUNDS.items()):
            b = tk.Button(self.sound_row, text="%s\n%s" % (emoji, label), command=lambda k=k: self.pick_sound(k),
                          font=("Segoe UI", 11, "bold"), relief="flat", cursor="hand2", pady=4)
            b.grid(row=0, column=i, sticky="ew", padx=3)
            self.sound_row.columnconfigure(i, weight=1, uniform="s")
            self.sound_btns[k] = b
        self.paint_sounds()

        self.opt_row = tk.Frame(self.root, bg=BG)
        self.opt_row.pack(fill="x", padx=20, pady=(6, 0))
        self.hotkey_vk = self.cfg.get("hotkey") if self.cfg.get("hotkey") in HOTKEYS else DEFAULT_HOTKEY
        self.capturing = False
        self.hotkey_btn = self.option_button(0, self.capture_hotkey)
        self.autostart_btn = self.option_button(1, self.toggle_autostart)
        self.option_button(2, self.open_web).configure(text="🌐 Version web")
        self.paint_options()

        # invitation ou demande d'ami reçue
        self.notice_row = tk.Frame(self.root, bg=FIELD, highlightthickness=2, highlightbackground=YELLOW)
        self.notice_text = tk.Label(self.notice_row, bg=FIELD, fg=FG, font=("Segoe UI", 12, "bold"),
                                    anchor="w", justify="left", wraplength=320)
        self.notice_text.pack(side="left", fill="x", expand=True, padx=(10, 4), pady=6)
        tk.Button(self.notice_row, text="Non", command=lambda: self.answer_notice(False), font=("Segoe UI", 11, "bold"),
                  bg="#2f333d", fg=FG, activebackground="#3a3f4b", activeforeground=FG, relief="flat",
                  cursor="hand2", padx=8).pack(side="right", padx=(0, 6), pady=6)
        self.notice_yes = tk.Button(self.notice_row, command=lambda: self.answer_notice(True),
                                    font=("Segoe UI", 11, "bold"), bg=YELLOW, fg="#1a1a1a",
                                    activebackground="#ffdb70", relief="flat", cursor="hand2", padx=8)
        self.notice_yes.pack(side="right", padx=(0, 6), pady=6)

        self.status = tk.Label(self.root, text="", bg=BG, fg=MUTED, font=("Segoe UI", 20, "bold"))
        self.status.pack(fill="x", pady=(8, 4))
        self.bind_hold(self.status, lambda: self.riposte[0] if self.riposte else False)

        self.all_btn = tk.Button(self.root, text="📯  TOUT LE MONDE", font=("Segoe UI", 26, "bold"), bg=YELLOW,
                                 fg="#1a1a1a", activebackground="#ffdb70", relief="flat", cursor="hand2")
        self.all_btn.pack(fill="x", padx=20, pady=(4, 12), ipady=16)
        self.bind_hold(self.all_btn, lambda: None)

        self.grid = tk.Frame(self.root, bg=BG)
        self.grid.pack(fill="both", expand=True, padx=20, pady=(0, 18))

        self.root.bind("<KeyPress-space>", lambda e: None if e.widget in self.entries else self.press(None, self.all_btn))
        self.root.bind("<KeyRelease-space>", lambda e: None if e.widget in self.entries else self.release())
        self.root.bind("<FocusOut>", lambda e: self.release() if e.widget is self.root else None)
        self.root.bind("<KeyPress>", self.on_capture_key)
        self.root.protocol("WM_DELETE_WINDOW", self.hide)
        try:
            self._icon_photo = ImageTk.PhotoImage(icon_image(64))
            self.root.iconphoto(True, self._icon_photo)
        except tk.TclError:
            pass

        self.listen_single()
        self.hotkey = HotKey(self.events, self.hotkey_vk) if winsound else None
        if autostart_get() not in (None, autostart_command()):
            autostart_set(True)  # l'exe a changé de place (mise à jour du hub) : on suit
        self.tray = pystray.Icon("klaxon", icon_image(64), "Klaxon", pystray.Menu(
            pystray.MenuItem("Afficher Klaxon", lambda: self.events.put(("show",)), default=True),
            pystray.MenuItem("Ouvrir la version web", lambda: self.events.put(("web",))),
            pystray.MenuItem("Lancer avec Windows", lambda: self.events.put(("autostart",)),
                             checked=lambda item: autostart_get() is not None),
            pystray.Menu.SEPARATOR,
            pystray.MenuItem("Quitter Klaxon", lambda: self.events.put(("quit",))),
        ))
        self.tray.run_detached()
        if start_hidden:
            self.root.withdraw()

        self.net = None
        self.shown = None
        self.peer_btns = {}
        if self.room.get().strip():
            self.add_group(self.room.get().strip())
            self.start_net()
        else:
            self.entries[1].focus()
        self.render_peers()
        self.poll()
        self.tick_blink()
        self.root.after(int(HEARTBEAT * 1000), self.heartbeat)

    # --- fond : icône, instance unique, raccourci, démarrage -----------------
    def listen_single(self):
        srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        try:
            srv.bind(("127.0.0.1", SINGLE_PORT))
            srv.listen(4)
        except OSError:
            return

        def loop():
            while True:
                try:
                    c, _ = srv.accept()
                    with c:
                        c.settimeout(1)
                        if c.recv(16) == b"show":
                            c.sendall(b"klaxon")
                            self.events.put(("show",))
                except OSError:
                    pass
        threading.Thread(target=loop, daemon=True).start()

    def show(self):
        self.root.deiconify()
        self.root.lift()
        self.root.attributes("-topmost", True)
        self.root.after(300, lambda: self.root.attributes("-topmost", False))
        self.root.focus_force()

    def hide(self):
        self.release()
        self.root.withdraw()
        if not self.cfg.get("hint_fond"):
            self.cfg["hint_fond"] = True
            save_config(self.cfg)
            try:
                self.tray.notify("Klaxon reste à côté de l'horloge et continue d'écouter. "
                                 "Clic droit sur l'icône pour le quitter.", "Klaxon")
            except Exception:
                pass

    def option_button(self, col, command):
        b = tk.Button(self.opt_row, command=command, font=("Segoe UI", 11, "bold"), relief="flat",
                      cursor="hand2", bg=FIELD, fg=FG, activebackground="#2f333d", activeforeground=FG, pady=4)
        b.grid(row=0, column=col, sticky="ew", padx=3)
        self.opt_row.columnconfigure(col, weight=1, uniform="o")
        return b

    def paint_options(self, hotkey_msg=None):
        if self.capturing:
            self.hotkey_btn.configure(text="Appuie sur une touche…", bg=YELLOW, fg="#1a1a1a")
        else:
            self.hotkey_btn.configure(text=hotkey_msg or "⌨ Touche partout : %s" % HOTKEYS[self.hotkey_vk],
                                      bg=FIELD, fg=FG)
        on = autostart_get() is not None
        self.autostart_btn.configure(text="🚀 Avec Windows : %s" % ("oui" if on else "non"),
                                     bg=YELLOW if on else FIELD, fg="#1a1a1a" if on else FG)

    def capture_hotkey(self):
        if not self.hotkey:
            return
        self.capturing = not self.capturing
        self.paint_options()

    def on_capture_key(self, e):
        if not self.capturing:
            return None
        self.capturing = False
        if e.keycode in HOTKEYS:
            self.hotkey.set(e.keycode)
        elif e.keysym != "Escape":
            self.paint_options("Pas celle-là : F1 à F12, Pause…")
            self.root.after(2000, self.paint_options)
            return "break"
        self.paint_options()
        return "break"

    def hotkey_status(self, vk, ok):
        if ok:
            self.hotkey_vk = vk
            self.cfg["hotkey"] = vk
            save_config(self.cfg)
            self.paint_options()
        else:
            self.paint_options("%s est déjà prise ailleurs" % HOTKEYS.get(vk, "?"))
            self.root.after(2500, self.paint_options)

    def hotkey_down(self):
        if self.capturing:  # c'est la touche actuelle : on la garde
            self.capturing = False
            self.paint_options()
            return
        self.press(None, None)
        self.root.after(50, self.watch_hotkey)

    def watch_hotkey(self):
        # la touche reste enfoncée = klaxon long, comme le bouton
        if self.holding and self.holding[1] is None:
            if self.hotkey.is_down():
                self.root.after(50, self.watch_hotkey)
            else:
                self.release()

    def toggle_autostart(self):
        autostart_set(autostart_get() is None)
        self.paint_options()
        self.tray.update_menu()

    def open_web(self):
        room = self.room.get().strip()
        webbrowser.open(SITE + ("#" + urllib.parse.quote(room) if room else ""))

    def start_net(self):
        self.net = Net(self.my_id, self.events, self.my_name(), self.sound, self.room.get(), self.code, self.friends)

    # --- champs ----------------------------------------------------------
    def my_name(self):
        return self.name.get().strip()[:24] or "Anonyme"

    def apply_name(self):
        name = self.my_name()
        if self.cfg.get("name") != name:
            self.cfg["name"] = name
            save_config(self.cfg)
            if self.net:
                self.net.set_profile(name, self.sound)

    def apply_room(self):
        room = self.room.get().strip()
        if not room or self.cfg.get("room") == room and self.net:
            return
        self.cfg["room"] = room
        save_config(self.cfg)
        self.add_group(room)
        self.riposte = None
        if self.net:
            self.net.set_room(room)
        else:
            self.start_net()
        self.render_peers()

    def go_room(self, room):
        self.room.set(room)
        self.apply_room()

    def pick_sound(self, k):
        self.sound = k
        self.cfg["sound"] = k
        save_config(self.cfg)
        self.paint_sounds()
        key = "essai/%f" % time.time()
        self.player.start(key, k)
        self.player.end(key)
        if self.net:
            self.net.set_profile(self.my_name(), k)

    def paint_sounds(self):
        for k, b in self.sound_btns.items():
            on = k == self.sound
            b.configure(bg=YELLOW if on else FIELD, fg="#1a1a1a" if on else MUTED,
                        activebackground=YELLOW if on else "#2f333d", activeforeground="#1a1a1a" if on else FG)

    def invite(self):
        link = SITE + "#" + urllib.parse.quote(self.room.get().strip())
        self.root.clipboard_clear()
        self.root.clipboard_append(link)
        self.invite_btn.configure(text="✓ Lien copié", bg=YELLOW, fg="#1a1a1a")
        self.root.after(1600, lambda: self.invite_btn.configure(text="🔗 Inviter", bg=FIELD, fg=FG))

    # --- réseau -> interface ---------------------------------------------
    def poll(self):
        try:
            while True:
                ev = self.events.get_nowait()
                if ev[0] == "peers":
                    self.render_peers()
                elif ev[0] == "conn":
                    self.online = ev[1] > 0
                    self.render_peers()
                elif ev[0] == "honk":
                    self.honked_by(*ev[1:])
                elif ev[0] == "hend":
                    self.player.end(ev[1])
                elif ev[0] == "show":
                    self.show()
                elif ev[0] == "web":
                    self.open_web()
                elif ev[0] == "autostart":
                    self.toggle_autostart()
                elif ev[0] == "quit":
                    self.quit()
                    return
                elif ev[0] == "hk_down":
                    self.hotkey_down()
                elif ev[0] == "hk_status":
                    self.hotkey_status(ev[1], ev[2])
                elif ev[0] == "friends":
                    self.render_friends()
                elif ev[0] == "fname":
                    self.friend_named(ev[1], ev[2])
                elif ev[0] == "invite":
                    self.invited(*ev[1:])
                elif ev[0] == "ami":
                    self.friend_added_me(*ev[1:])
                elif ev[0] == "freq":
                    self.friend_asked(*ev[1:])
        except queue.Empty:
            pass
        if self.flash_until and time.time() > self.flash_until:
            self.flash_until = 0
            self.set_bg(BG)
            self.render_status()
        if self.riposte and time.time() > self.riposte[2]:
            self.riposte = None
            self.render_peers()
        self.root.after(40, self.poll)

    # --- klaxonner : appuyer = ça klaxonne, relâcher = ça s'arrête ---------
    def bind_hold(self, widget, target):
        """target() donne le destinataire : None = tout le monde, False = personne."""
        widget.bind("<ButtonPress-1>", lambda e: self.press(target(), widget))
        widget.bind("<ButtonRelease-1>", lambda e: self.release())

    def press(self, to, widget=None):
        if to is False or self.holding or not self.net or not self.online or not self.net.snapshot():
            return
        now = time.time()
        if now - self.last_press < COOLDOWN:
            return
        self.last_press = now
        n = self.net.press(to)
        self.player.start("moi/" + n, self.sound)  # on s'entend klaxonner aussi
        self.holding = (n, widget)
        self.root.after(int(MAX_HOLD * 1000), lambda: self.release(n))
        if self.riposte and to == self.riposte[0]:
            self.riposte = None
            self.render_peers()

    def release(self, only=None):
        if not self.holding or (only and self.holding[0] != only):
            return
        n, _ = self.holding
        self.holding = None
        self.net.release(n)
        self.player.end("moi/" + n)

    def honked_by(self, pid, who, everyone, key, sound, hold):
        self.player.start(key, sound)
        if not hold:
            self.player.end(key)  # ancienne version : un coup simple
        self.set_bg(RED)
        self.status.configure(text="📯 %s %s" % (who, "klaxonne tout le monde !" if everyone else "te klaxonne !"),
                              fg=FG)
        self.flash_until = time.time() + 1.2
        self.riposte = (pid, who, time.time() + RIPOSTE)
        self.render_peers()
        try:
            self.root.deiconify()
            self.root.attributes("-topmost", True)
            self.root.after(300, lambda: self.root.attributes("-topmost", False))
        except tk.TclError:
            pass

    # --- amis et groupes ---------------------------------------------------
    def save_friends(self):
        self.cfg["friends"] = self.friends
        save_config(self.cfg)
        if self.net:
            self.net.set_friends(self.friends)
        self.render_friends()

    def is_friend(self, code):
        return any(f["code"] == code for f in self.friends)

    def add_friend(self, code, name):
        if code == self.code or self.is_friend(code):
            return
        self.friends.append({"code": code, "name": (name or "?")[:24]})
        self.save_friends()
        if self.net:  # il voit une demande, ou nous ajoute tout seul s'il l'avait demandé
            self.net.send_inbox(code, {"type": "ami"})

    def remove_friend(self, code):
        self.friends = [f for f in self.friends if f["code"] != code]
        self.save_friends()

    def friend_named(self, code, name):
        for f in self.friends:
            if f["code"] == code and f["name"] != name:
                f["name"] = name
                self.cfg["friends"] = self.friends
                save_config(self.cfg)
        self.render_friends()

    def add_group(self, room):
        if is_private(room) and not any(same_room(g, room) for g in self.groups):
            self.groups.append(room)
            self.cfg["groups"] = self.groups
            save_config(self.cfg)

    def forget_group(self, room):
        if tkinter.messagebox.askyesno("Klaxon", "Oublier le groupe « %s » ? Il faudra une invitation pour y revenir."
                                       % room_label(room)[2:], parent=self.fwin):
            self.groups = [g for g in self.groups if g != room]
            self.cfg["groups"] = self.groups
            save_config(self.cfg)
            self.render_friends()

    def create_group(self):
        name = self.group_entry.get().strip().replace("~", "-")[:40]
        if name:
            self.group_entry.delete(0, "end")
            self.go_room(name + "~" + random_text(10, ALPHA.lower()))  # l'ajoute aux groupes

    def invite_friend(self, code):
        if not self.net:
            return
        self.net.send_inbox(code, {"type": "invite", "room": self.room.get().strip()})
        self.invited_at[code] = time.time()
        self.root.after(3050, self.render_friends)
        self.render_friends()

    def ask_friend(self, pid, u):
        if self.net:
            self.net.ask_friend(pid, u)
            self.render_friends()

    def heartbeat(self):
        if self.net:
            self.net.publish_status()
        self.render_friends()
        self.root.after(int(HEARTBEAT * 1000), self.heartbeat)

    # --- invitations et demandes reçues -------------------------------------
    def notice(self, key, text, yes, action, loud=False):
        self.notices = [n for n in self.notices if n[0] != key][-4:] + [(key, text, yes, action)]
        self.render_notice()
        if loud:
            k = "invit/%f" % time.time()
            self.player.start(k, "canard")
            self.player.end(k)
        if self.root.state() == "withdrawn":
            try:
                self.tray.notify(text[2:], "Klaxon")
            except Exception:
                pass

    def render_notice(self):
        if not self.notices:
            self.notice_row.pack_forget()
            return
        _, text, yes, _ = self.notices[0]
        self.notice_text.configure(text=text)
        self.notice_yes.configure(text=yes)
        self.notice_row.pack(fill="x", padx=20, pady=(8, 0), before=self.status)

    def answer_notice(self, yes):
        if self.notices:
            action = self.notices.pop(0)[3]
            self.render_notice()
            if yes:
                action()

    def invited(self, frm, name, room):
        if not same_room(room, self.room.get()):
            self.notice("inv/" + frm, "📨 %s t'invite dans « %s »" % (name, room_label(room)), "Rejoindre",
                        lambda: self.go_room(room), loud=True)

    def friend_added_me(self, frm, name, asked):
        if self.is_friend(frm):
            return
        if asked:
            self.add_friend(frm, name)
        else:
            self.notice("ami/" + frm, "👋 %s t'a ajouté en ami" % name, "Ajouter", lambda: self.add_friend(frm, name))

    def friend_asked(self, frm, name):
        if not self.is_friend(frm):
            self.notice("ami/" + frm, "👋 %s veut être ton ami" % name, "Accepter", lambda: self.add_friend(frm, name))

    # --- fenêtre des amis ---------------------------------------------------
    def open_friends(self):
        if self.fwin:
            self.fwin.deiconify()
            self.fwin.lift()
            self.fwin.focus_force()
            return
        w = self.fwin = tk.Toplevel(self.root, bg=BG)
        w.title("Klaxon — Amis")
        w.geometry("480x640")
        w.minsize(420, 360)
        w.protocol("WM_DELETE_WINDOW", self.close_friends)

        canvas = tk.Canvas(w, bg=BG, highlightthickness=0)
        bar = tk.Scrollbar(w, orient="vertical", command=canvas.yview)
        canvas.configure(yscrollcommand=bar.set)
        bar.pack(side="right", fill="y")
        canvas.pack(side="left", fill="both", expand=True)
        body = tk.Frame(canvas, bg=BG)
        win_id = canvas.create_window((0, 0), window=body, anchor="nw")
        body.bind("<Configure>", lambda e: canvas.configure(scrollregion=canvas.bbox("all")))
        canvas.bind("<Configure>", lambda e: canvas.itemconfigure(win_id, width=e.width))
        w.bind("<MouseWheel>", lambda e: canvas.yview_scroll(-e.delta // 120, "units"))

        def title(text):
            tk.Label(body, text=text, bg=BG, fg=MUTED, font=("Segoe UI", 10, "bold"), anchor="w").pack(
                fill="x", padx=16, pady=(16, 4))

        def hint(text):
            lab = tk.Label(body, text=text, bg=BG, fg=MUTED, font=("Segoe UI", 10), anchor="w", justify="left",
                           wraplength=420)
            lab.pack(fill="x", padx=16, pady=(4, 0))
            return lab

        def entry_row(label, command):
            row = tk.Frame(body, bg=BG)
            row.pack(fill="x", padx=16)
            e = tk.Entry(row, font=("Segoe UI", 13, "bold"), bg=FIELD, fg=FG, insertbackground=FG, relief="flat")
            e.pack(side="left", fill="x", expand=True, ipady=4)
            e.bind("<Return>", lambda ev: command())
            self.small_button(row, label, command, yes=True).pack(side="right", padx=(8, 0))
            return e

        title("MON CODE AMI")
        row = tk.Frame(body, bg=BG)
        row.pack(fill="x", padx=16)
        tk.Label(row, text=show_code(self.code), bg=BG, fg=YELLOW, font=("Consolas", 20, "bold")).pack(side="left")
        self.copy_code_btn = self.small_button(row, "Copier", self.copy_code)
        self.copy_code_btn.pack(side="right")
        hint("Donne-le à tes amis : avec, ils te voient en ligne et peuvent t'inviter.")

        title("MES AMIS")
        self.code_entry = entry_row("Ajouter", self.add_friend_code)
        self.add_msg = hint("")
        self.friend_box = tk.Frame(body, bg=BG)
        self.friend_box.pack(fill="x", padx=16, pady=(4, 0))

        self.here_title = tk.Label(body, text="DANS CE SALON", bg=BG, fg=MUTED, font=("Segoe UI", 10, "bold"), anchor="w")
        self.here_box = tk.Frame(body, bg=BG)
        self.groups_title = tk.Label(body, text="GROUPES PRIVÉS", bg=BG, fg=MUTED, font=("Segoe UI", 10, "bold"),
                                     anchor="w")
        self.groups_title.pack(fill="x", padx=16, pady=(16, 4))
        self.group_box = tk.Frame(body, bg=BG)
        self.group_box.pack(fill="x", padx=16, pady=(0, 6))
        self.group_entry = entry_row("Créer", self.create_group)
        hint("Un groupe privé a un nom secret : on n'y entre que par invitation ou par son lien.")
        tk.Frame(body, bg=BG, height=16).pack()
        self.render_friends()
        self.code_entry.focus()

    def close_friends(self):
        self.fwin.destroy()
        self.fwin = None

    def small_button(self, parent, text, command, yes=False, state="normal"):
        return tk.Button(parent, text=text, command=command, font=("Segoe UI", 11, "bold"), relief="flat",
                         cursor="hand2", padx=8, state=state, bg=YELLOW if yes else "#2f333d",
                         fg="#1a1a1a" if yes else FG, activebackground="#ffdb70" if yes else "#3a3f4b",
                         activeforeground="#1a1a1a" if yes else FG, disabledforeground=MUTED)

    def copy_code(self):
        self.root.clipboard_clear()
        self.root.clipboard_append(show_code(self.code))
        self.copy_code_btn.configure(text="✓ Copié")
        self.root.after(1600, lambda: self.fwin and self.copy_code_btn.configure(text="Copier"))

    def add_friend_code(self):
        code = norm_code(self.code_entry.get())
        if not valid_code(code):
            msg = "Un code ami fait 10 caractères, comme %s." % show_code(self.code)
        elif code == self.code:
            msg = "C'est ton propre code 🙂"
        elif self.is_friend(code):
            msg = "Déjà dans tes amis."
        else:
            self.add_friend(code, "?")
            self.code_entry.delete(0, "end")
            msg = "Ajouté ! Son nom apparaît dès qu'il est en ligne."
        self.add_msg.configure(text=msg)

    def list_row(self, box, name, sub, dot=None):
        row = tk.Frame(box, bg=FIELD)
        row.pack(fill="x", pady=3)
        if dot is not None:
            tk.Label(row, text="●", bg=FIELD, fg=GREEN if dot else "#4a4f5c", font=("Segoe UI", 12)).pack(
                side="left", padx=(10, 0))
        who = tk.Frame(row, bg=FIELD)
        who.pack(side="left", fill="x", expand=True, padx=10, pady=5)
        tk.Label(who, text=name, bg=FIELD, fg=FG, font=("Segoe UI", 12, "bold"), anchor="w").pack(fill="x")
        if sub:
            tk.Label(who, text=sub, bg=FIELD, fg=MUTED, font=("Segoe UI", 10), anchor="w").pack(fill="x")
        return row

    def x_button(self, row, command):
        tk.Button(row, text="✕", command=command, font=("Segoe UI", 12), bg=FIELD, fg=MUTED, activebackground=FIELD,
                  activeforeground=FG, relief="flat", cursor="hand2", bd=0).pack(side="right", padx=(0, 6))

    def render_friends(self):
        online = [f for f in self.friends if self.net and self.net.friend_status(f["code"])]
        self.friends_btn.configure(text="👥 Amis" + (" · %d" % len(online) if online else ""))
        if not self.fwin:
            return
        room = self.room.get().strip()
        for box in (self.friend_box, self.here_box, self.group_box):
            for c in box.winfo_children():
                c.destroy()

        rows = sorted(((f, self.net.friend_status(f["code"]) if self.net else None) for f in self.friends),
                      key=lambda x: (x[1] is None, x[0]["name"].lower()))
        for f, s in rows:
            here = bool(s and s["room"] and same_room(s["room"], room))
            where = ("hors ligne" if not s else "avec toi" if here else
                     "dans « %s »" % room_label(s["room"]) if s["room"] else "en ligne")
            row = self.list_row(self.friend_box, f["name"], where, dot=bool(s))
            code = f["code"]
            self.x_button(row, lambda c=code, n=f["name"]: tkinter.messagebox.askyesno(
                "Klaxon", "Retirer %s de tes amis ?" % n, parent=self.fwin) and self.remove_friend(c))
            if s and not here:
                recent = time.time() - self.invited_at.get(code, 0) < 3
                self.small_button(row, "✓ Invité" if recent else "Inviter", lambda c=code: self.invite_friend(c),
                                  state="disabled" if recent or not room else "normal").pack(side="right", padx=(0, 6))
            if s and s["room"] and not here:
                self.small_button(row, "Rejoindre", lambda r=s["room"]: self.go_room(r), yes=True).pack(
                    side="right", padx=(0, 6))
        if not self.friends:
            self.list_row(self.friend_box, "Pas encore d'amis", "Ajoute un code, ou quelqu'un de ton salon.")

        tids = {ident(f["code"])[0] for f in self.friends}
        strangers = [(pid, p) for pid, p in (self.net.snapshot() if self.net else [])
                     if p.get("u") and p["u"] != ident(self.code)[0] and p["u"] not in tids]
        if strangers:
            self.here_title.pack(fill="x", padx=16, pady=(16, 4), before=self.groups_title)
            self.here_box.pack(fill="x", padx=16, before=self.groups_title)
            for pid, p in strangers:
                row = self.list_row(self.here_box, p["name"], "")
                if p["u"] in self.net.pending:
                    self.small_button(row, "Demande envoyée", None, state="disabled").pack(side="right", padx=6)
                else:
                    self.small_button(row, "+ Ajouter", lambda i=pid, u=p["u"]: self.ask_friend(i, u), yes=True).pack(
                        side="right", padx=6)
        else:
            self.here_title.pack_forget()
            self.here_box.pack_forget()

        for g in self.groups:
            here = same_room(g, room)
            row = self.list_row(self.group_box, room_label(g), "tu y es" if here else "")
            self.x_button(row, lambda r=g: self.forget_group(r))
            if here:
                self.small_button(row, "🔗 Copier le lien", self.invite).pack(side="right", padx=(0, 6))
            else:
                self.small_button(row, "Entrer", lambda r=g: self.go_room(r), yes=True).pack(side="right", padx=(0, 6))
        if not self.groups:
            self.list_row(self.group_box, "Aucun groupe privé pour l'instant", "")

    # --- rendu -----------------------------------------------------------
    def set_bg(self, color):
        for w in (self.root, self.grid, self.status, self.fields, self.sound_row, self.opt_row):
            w.configure(bg=color)
        for c in self.fields.winfo_children():
            if isinstance(c, tk.Label):
                c.configure(bg=color)

    def render_status(self):
        if not self.net:
            text = "Choisis un salon ↑"
        elif not self.online:
            text = "Connexion…"
        elif self.riposte:
            text = "↩ Riposter à " + self.riposte[1]
        elif not self.net.snapshot():
            text = "Personne d'autre dans le salon"
        else:
            text = "%d dans le salon" % (len(self.net.snapshot()) + 1)
        self.status.configure(text=text, fg=MUTED, cursor="hand2" if self.riposte else "")
        tip = "Klaxon — " + (self.room.get().strip() or "pas de salon") + " : " + text
        if getattr(self, "tray", None) and self.tray.title != tip[:120]:
            self.tray.title = tip[:120]

    def tick_blink(self):
        self.blink = not self.blink
        if self.riposte and self.riposte[0] in self.peer_btns:
            self.peer_btns[self.riposte[0]].configure(bg=YELLOW if self.blink else BLUE,
                                                      fg="#1a1a1a" if self.blink else "white")
        self.root.after(250, self.tick_blink)

    def render_peers(self):
        # les boutons ne sont recréés que si quelqu'un arrive ou part : un klaxon long n'est pas coupé
        peers = self.net.snapshot() if self.net else []
        if self.riposte and self.riposte[0] not in dict(peers):
            self.riposte = None
        if not self.flash_until:
            self.render_status()
        self.all_btn.configure(state="normal" if peers else "disabled")
        order = [pid for pid, _ in peers]
        if order != self.shown:
            self.shown = order
            if self.holding and self.holding[1] in self.peer_btns.values():
                self.release()
            self.render_friends()  # « Dans ce salon »
            for w in self.grid.winfo_children():
                w.destroy()
            self.peer_btns = {}
            cols = 1 if len(peers) == 1 else 2
            for i, (pid, _) in enumerate(peers):
                b = tk.Button(self.grid, font=("Segoe UI", 20 if len(peers) <= 4 else 16, "bold"),
                              activebackground="#5a8cf5", activeforeground="white", relief="flat",
                              cursor="hand2", wraplength=220)
                b.grid(row=i // cols, column=i % cols, sticky="nsew", padx=5, pady=5)
                self.bind_hold(b, lambda p=pid: p)
                self.peer_btns[pid] = b
            rows = (len(peers) + cols - 1) // cols
            for c in range(2):
                self.grid.columnconfigure(c, weight=1 if c < cols else 0, uniform="c" if c < cols else "")
            for r in range(max(rows, 4)):
                self.grid.rowconfigure(r, weight=1 if r < rows else 0)
        for pid, p in peers:
            b = self.peer_btns[pid]
            b.configure(text="%s\n%s" % (p["name"], SOUNDS[p["sound"]][1]))
            if not (self.riposte and self.riposte[0] == pid):
                b.configure(bg=BLUE, fg="white")

    def quit(self):
        self.release()
        self.apply_name()
        try:
            self.tray.stop()
            if self.hotkey:
                self.hotkey.stop()
            if self.net:
                self.net.close()
        finally:
            self.root.destroy()

    def run(self):
        self.root.mainloop()


if __name__ == "__main__":
    if not wake_other_instance():
        App(start_hidden="--fond" in sys.argv).run()
