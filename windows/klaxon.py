"""Klaxon — klaxonner d'ordinateur à ordinateur par Internet (2 à 6 personnes, ou plus).

Tout le monde met le même nom de salon : ceux qui sont dans le salon apparaissent tout seuls.
Passe par trois serveurs MQTT publics à la fois (si l'un rame, les autres suffisent),
rien à héberger, aucun compte. Même protocole que la version navigateur (index.html).
"""
import hashlib
import json
import math
import os
import queue
import socket
import struct
import tempfile
import threading
import time
import tkinter as tk
import urllib.parse
import uuid
import wave

import paho.mqtt.client as mqtt

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

CONFIG = os.path.join(os.environ.get("APPDATA") or os.path.expanduser("~"), "Klaxon", "config.json")

BG = "#15171c"
FG = "#f2f2f2"
MUTED = "#7c8190"
FIELD = "#23262e"
YELLOW = "#ffc629"
BLUE = "#2f6fed"
RED = "#e8412c"

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
except ImportError:
    winsound = None


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


class Link:
    """Une connexion à un serveur. Présence : message retenu sur <salon>/p/<id>, que le serveur
    efface tout seul (testament MQTT) si le client disparaît. Klaxons : <salon>/h (début)
    et <salon>/he (fin d'un klaxon long)."""

    def __init__(self, net, host, routes):
        self.net, self.host, self.routes = net, host, routes
        self.client = None
        self.online = False
        self.peers = {}  # id -> {"name", "sound"}, vus sur ce serveur
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
        client.subscribe(self.net.base + "#", qos=1)
        self.announce()
        self.net.changed()

    def _on_disconnect(self, client, userdata, flags, rc, props=None):
        self.online = False
        with self.net.lock:
            self.peers.clear()
        self.net.changed()

    def _on_message(self, client, userdata, msg):
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
                        self.peers[pid] = {"name": str(p.get("name", "?"))[:24], "sound": sound_of(p.get("sound"))}
                    except (ValueError, AttributeError):
                        return
            self.net.changed()
        elif sub in ("h", "he"):
            try:
                data = json.loads(msg.payload)
                if isinstance(data, dict):
                    self.net.received(sub, data)
            except ValueError:
                pass

    def publish(self, sub, payload, qos=0, retain=False):
        if self.client and self.online:
            return self.client.publish(self.net.base + sub, payload, qos=qos, retain=retain)

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
        info = self.publish("p/" + self.net.my_id, b"", qos=1, retain=True)
        if info:
            info.wait_for_publish(1.5)
        if self.client:
            self.client.disconnect()


class Net:
    """Parle aux trois serveurs à la fois : on envoie partout, on fusionne ce qu'on reçoit."""

    def __init__(self, my_id, events, name, sound, room):
        self.my_id = my_id
        self.events = events
        self.name = name
        self.sound = sound
        self.base = room_base(room)
        self.lock = threading.Lock()
        self.seq = 0
        self.seen_msgs = {}  # (id, n) -> heure : un klaxon arrive une fois par serveur
        self.links = [Link(self, host, routes) for host, routes in BROKERS]

    def presence(self):
        return json.dumps({"name": self.name, "sound": self.sound})

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

    def set_room(self, room):
        new = room_base(room)
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


class App:
    def __init__(self):
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
        self.root.geometry("560x720")
        self.root.minsize(460, 580)
        self.player = Player(self.root)
        self.name = tk.StringVar(value=self.cfg.get("name") or default_name[:24])
        self.room = tk.StringVar(value=self.cfg.get("room") or "")
        self.sound = sound_of(self.cfg.get("sound"))

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
            e.grid(row=row, column=1, columnspan=2 - row, sticky="ew", padx=(12, 0), pady=4, ipady=4)
            e.bind("<Return>", lambda ev, a=apply: (a(), self.root.focus()))
            e.bind("<FocusOut>", lambda ev, a=apply: a())
            self.entries.append(e)
        self.invite_btn = tk.Button(self.fields, text="🔗 Inviter", command=self.invite, font=("Segoe UI", 12, "bold"),
                                    bg=FIELD, fg=FG, activebackground="#2f333d", activeforeground=FG,
                                    relief="flat", cursor="hand2", padx=10)
        self.invite_btn.grid(row=1, column=2, sticky="ns", padx=(8, 0), pady=4)

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
        self.root.protocol("WM_DELETE_WINDOW", self.quit)

        self.net = None
        self.shown = None
        self.peer_btns = {}
        if self.room.get().strip():
            self.start_net()
        else:
            self.entries[1].focus()
        self.render_peers()
        self.poll()
        self.tick_blink()

    def start_net(self):
        self.net = Net(self.my_id, self.events, self.my_name(), self.sound, self.room.get())

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
        self.riposte = None
        if self.net:
            self.net.set_room(room)
        else:
            self.start_net()
        self.render_peers()

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

    # --- rendu -----------------------------------------------------------
    def set_bg(self, color):
        for w in (self.root, self.grid, self.status, self.fields, self.sound_row):
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
            if self.net:
                self.net.close()
        finally:
            self.root.destroy()

    def run(self):
        self.root.mainloop()


if __name__ == "__main__":
    App().run()
