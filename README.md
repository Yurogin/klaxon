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
- **Windows** : `Klaxon.exe`, installé par le hub STLKM ou pris dans les
  releases. En plus de la version web :
  - une **touche qui klaxonne depuis n'importe quel logiciel** (F9 par défaut,
    tenue = klaxon long), même en plein jeu ;
  - une **icône à côté de l'horloge** : fermer la fenêtre ne quitte pas,
    Klaxon continue d'écouter et surgit quand on te klaxonne ;
  - le **lancement avec Windows**, en fond ;
  - un bouton vers la version web.

  Pour refaire l'exe : `windows/build.bat`. Mêmes salons que la version web.

Les messages passent par trois serveurs MQTT publics à la fois
(`broker.emqx.io`, `broker.hivemq.com`, `test.mosquitto.org`) : si l'un rame
ou tombe, les deux autres suffisent. Le nom du salon est haché avant d'être
envoyé, mais quelqu'un qui le devine peut vous klaxonner : prenez un nom peu
courant.
