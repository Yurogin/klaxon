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
START = int(time.time() * 1000)  # distingue les klaxons de deux lancements successifs
COOLDOWN = 0.35        # anti-spam, par destinataire

CONFIG = os.path.join(os.environ.get("APPDATA") or os.path.expanduser("~"), "Klaxon", "config.json")

BG = "#15171c"
FG = "#f2f2f2"
MUTED = "#7c8190"
FIELD = "#23262e"
YELLOW = "#ffc629"
BLUE = "#2f6fed"
RED = "#e8412c"

try:
    import winsound
except ImportError:
    winsound = None


def make_horn_wav(path):
    """Synthétise un klaxon de voiture (deux notes saturées, ~0,6 s)."""
    rate, dur = 22050, 0.6
    n = int(rate * dur)
    frames = bytearray()
    for i in range(n):
        t = i / rate
        s = 0.0
        for f in (415.0, 523.0):
            s += math.tanh(4 * math.sin(2 * math.pi * f * t))
            s += 0.3 * math.sin(2 * math.pi * 2 * f * t)
        env = min(1.0, t / 0.02) * min(1.0, (dur - t) / 0.05)
        frames += struct.pack("<h", int(max(-1, min(1, s / 2.8 * env)) * 30000))
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(bytes(frames))


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
    efface tout seul (testament MQTT) si le client disparaît. Klaxons : messages sur <salon>/h."""

    def __init__(self, net, host, routes):
        self.net, self.host, self.routes = net, host, routes
        self.client = None
        self.online = False
        self.peers = {}  # id -> nom, vus sur ce serveur
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
                        self.peers[pid] = str(json.loads(msg.payload).get("name", "?"))[:24]
                    except (ValueError, AttributeError):
                        return
            self.net.changed()
        elif sub == "h":
            try:
                self.net.received(json.loads(msg.payload))
            except (ValueError, AttributeError):
                pass

    def publish(self, sub, payload, qos=0, retain=False):
        if self.client and self.online:
            return self.client.publish(self.net.base + sub, payload, qos=qos, retain=retain)

    def announce(self):
        self.publish("p/" + self.net.my_id, json.dumps({"name": self.net.name}), qos=1, retain=True)

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

    def __init__(self, my_id, events, name, room):
        self.my_id = my_id
        self.events = events
        self.name = name
        self.base = room_base(room)
        self.lock = threading.Lock()
        self.seq = 0
        self.seen_msgs = {}  # (id, n) -> heure : un klaxon arrive une fois par serveur
        self.links = [Link(self, host, routes) for host, routes in BROKERS]

    def changed(self):
        self.events.put(("conn", self.online_count()))
        self.events.put(("peers",))

    def online_count(self):
        return sum(l.online for l in self.links)

    def received(self, data):
        pid = data.get("id")
        if not pid or pid == self.my_id or data.get("to") not in (None, self.my_id):
            return
        key, now = (pid, data.get("n")), time.time()
        with self.lock:
            if key[1] is not None:  # sans numéro : ancienne version, un seul serveur, pas de doublon
                if key in self.seen_msgs:
                    return
                self.seen_msgs[key] = now
                if len(self.seen_msgs) > 500:
                    self.seen_msgs = {k: v for k, v in self.seen_msgs.items() if now - v < 60}
        self.events.put(("honk", str(data.get("name", "?"))[:24], data.get("to") is None))

    def honk(self, to=None):
        """to=None : tout le monde, sinon l'id d'un pair."""
        self.seq += 1
        # n rend chaque klaxon unique, même d'un lancement à l'autre
        payload = json.dumps({"id": self.my_id, "name": self.name, "to": to, "n": "%d-%d" % (START, self.seq)})
        for l in self.links:
            l.publish("h", payload)

    def set_name(self, name):
        self.name = name
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
        return sorted(merged.items(), key=lambda x: x[1].lower())


class App:
    def __init__(self):
        self.my_id = uuid.uuid4().hex[:12]
        self.events = queue.Queue()
        self.last_honk = {}
        self.flash_until = 0
        self.online = False

        self.horn = os.path.join(tempfile.gettempdir(), "klaxon_horn.wav")
        if not os.path.exists(self.horn):
            make_horn_wav(self.horn)

        self.cfg = load_config()
        default_name = os.environ.get("USERNAME") or socket.gethostname()

        self.root = tk.Tk()
        self.root.title("Klaxon")
        self.root.configure(bg=BG)
        self.root.geometry("520x660")
        self.root.minsize(420, 520)
        self.name = tk.StringVar(value=self.cfg.get("name") or default_name[:24])
        self.room = tk.StringVar(value=self.cfg.get("room") or "")

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

        self.status = tk.Label(self.root, text="", bg=BG, fg=MUTED, font=("Segoe UI", 20, "bold"))
        self.status.pack(fill="x", pady=(8, 4))

        self.all_btn = tk.Button(self.root, text="📯  TOUT LE MONDE", command=lambda: self.honk(None),
                                 font=("Segoe UI", 26, "bold"), bg=YELLOW, fg="#1a1a1a",
                                 activebackground="#ffdb70", relief="flat", cursor="hand2")
        self.all_btn.pack(fill="x", padx=20, pady=(4, 12), ipady=18)

        self.grid = tk.Frame(self.root, bg=BG)
        self.grid.pack(fill="both", expand=True, padx=20, pady=(0, 18))

        self.root.bind("<space>", lambda e: None if e.widget in self.entries else self.honk(None))
        self.root.protocol("WM_DELETE_WINDOW", self.quit)

        self.net = None
        self.shown = None
        if self.room.get().strip():
            self.start_net()
        else:
            self.entries[1].focus()
        self.render_peers(force=True)
        self.poll()

    def start_net(self):
        self.net = Net(self.my_id, self.events, self.my_name(), self.room.get())

    # --- champs ----------------------------------------------------------
    def my_name(self):
        return self.name.get().strip()[:24] or "Anonyme"

    def apply_name(self):
        name = self.my_name()
        if self.cfg.get("name") != name:
            self.cfg["name"] = name
            save_config(self.cfg)
            if self.net:
                self.net.set_name(name)

    def apply_room(self):
        room = self.room.get().strip()
        if not room or self.cfg.get("room") == room and self.net:
            return
        self.cfg["room"] = room
        save_config(self.cfg)
        if self.net:
            self.net.set_room(room)
        else:
            self.start_net()
        self.render_peers(force=True)

    # --- réseau -> interface ---------------------------------------------
    def poll(self):
        try:
            while True:
                ev = self.events.get_nowait()
                if ev[0] == "peers":
                    self.render_peers()
                elif ev[0] == "conn":
                    self.online = ev[1] > 0
                    self.render_peers(force=True)
                elif ev[0] == "honk":
                    self.honked_by(ev[1], ev[2])
        except queue.Empty:
            pass
        if self.flash_until and time.time() > self.flash_until:
            self.flash_until = 0
            self.set_bg(BG)
            self.render_status()
        self.root.after(40, self.poll)

    # --- actions ---------------------------------------------------------
    def honk(self, to):
        if not self.net or not self.net.snapshot():
            return
        now = time.time()
        if now - self.last_honk.get(to, 0) < COOLDOWN:
            return
        self.last_honk[to] = now
        self.net.honk(to)
        self.play()  # on s'entend klaxonner aussi

    def honked_by(self, who, everyone):
        self.play()
        self.set_bg(RED)
        self.status.configure(text="📯 %s %s" % (who, "klaxonne tout le monde !" if everyone else "te klaxonne !"),
                              fg=FG)
        self.flash_until = time.time() + 1.2
        try:
            self.root.deiconify()
            self.root.attributes("-topmost", True)
            self.root.after(300, lambda: self.root.attributes("-topmost", False))
        except tk.TclError:
            pass

    def play(self):
        if winsound:
            winsound.PlaySound(self.horn, winsound.SND_FILENAME | winsound.SND_ASYNC | winsound.SND_NODEFAULT)
        else:
            self.root.bell()

    # --- rendu -----------------------------------------------------------
    def set_bg(self, color):
        for w in (self.root, self.grid, self.status, self.fields):
            w.configure(bg=color)
        for c in self.fields.winfo_children():
            if isinstance(c, tk.Label):
                c.configure(bg=color)

    def render_status(self):
        if not self.net:
            text = "Choisis un salon ↑"
        elif not self.online:
            text = "Connexion…"
        elif not self.net.snapshot():
            text = "Personne d'autre dans le salon"
        else:
            text = "%d dans le salon" % (len(self.net.snapshot()) + 1)
        self.status.configure(text=text, fg=MUTED)

    def render_peers(self, force=False):
        peers = self.net.snapshot() if self.net else []
        if peers == self.shown and not force:
            return
        self.shown = peers
        for w in self.grid.winfo_children():
            w.destroy()
        if not self.flash_until:
            self.render_status()
        self.all_btn.configure(state="normal" if peers else "disabled")
        if not peers:
            return
        cols = 1 if len(peers) == 1 else 2
        for i, (pid, pname) in enumerate(peers):
            b = tk.Button(self.grid, text=pname, command=lambda p=pid: self.honk(p),
                          font=("Segoe UI", 20 if len(peers) <= 4 else 16, "bold"), bg=BLUE, fg="white",
                          activebackground="#5a8cf5", activeforeground="white", relief="flat",
                          cursor="hand2", wraplength=200)
            b.grid(row=i // cols, column=i % cols, sticky="nsew", padx=5, pady=5)
        rows = (len(peers) + cols - 1) // cols
        for c in range(2):
            self.grid.columnconfigure(c, weight=1 if c < cols else 0, uniform="c" if c < cols else "")
        for r in range(max(rows, 4)):
            self.grid.rowconfigure(r, weight=1 if r < rows else 0)

    def quit(self):
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
