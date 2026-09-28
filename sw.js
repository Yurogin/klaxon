// Réseau d'abord, cache en secours : l'appli s'installe et s'ouvre même hors ligne (sans klaxonner).
const CACHE = "klaxon-v4";
const FILES = ["./", "index.html", "mqtt.min.js", "manifest.webmanifest", "icon.svg", "icon-512.png"];
self.addEventListener("install", (e) => { e.waitUntil(caches.open(CACHE).then((c) => c.addAll(FILES))); self.skipWaiting(); });
self.addEventListener("activate", (e) => { e.waitUntil(self.clients.claim()); });
self.addEventListener("fetch", (e) => {
  const url = new URL(e.request.url);
  if (e.request.method !== "GET" || url.origin !== location.origin) return;
  if (url.pathname.includes("/dl/")) return;   // les applis à télécharger : inutile de les garder en cache
  e.respondWith(
    fetch(e.request)
      .then((r) => { const copy = r.clone(); caches.open(CACHE).then((c) => c.put(e.request, copy)); return r; })
      .catch(() => caches.match(e.request))
  );
});
