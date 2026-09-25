# Klaxon

Klaxonne tes potes d'ordinateur à ordinateur, par Internet, à 2 comme à 6.

Tout le monde met le **même nom de salon** : ceux qui sont dedans apparaissent,
un clic sur un nom le klaxonne, le bouton jaune klaxonne tout le salon.

- **Chacun son son** : klaxon, pouet, camion, vuvuzela ou canard — les autres
  entendent le tien et savent que c'est toi.
- **Klaxon long** : tant que tu appuies, ça klaxonne (4 s maximum).
- **Riposte** : celui qui vient de te klaxonner clignote, un clic et tu ripostes.
- **Inviter** : copie le lien du salon, à coller sur Discord.

- **Navigateur** : https://klaxon.stlkm.fr, rien à installer. Un lien du type
  `https://klaxon.stlkm.fr/#nom-du-salon` pré-remplit le salon. Chrome propose aussi de
  l'installer comme une appli.
- **Windows** : `windows/klaxon.py`, ou `windows/build.bat` pour fabriquer
  `Klaxon.exe`. Mêmes salons que la version navigateur.

Les messages passent par trois serveurs MQTT publics à la fois
(`broker.emqx.io`, `broker.hivemq.com`, `test.mosquitto.org`) : si l'un rame
ou tombe, les deux autres suffisent. Le nom du salon est haché avant d'être
envoyé, mais quelqu'un qui le devine peut vous klaxonner : prenez un nom peu
courant.
