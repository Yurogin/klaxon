# Klaxon

Klaxonne tes potes d'ordinateur à ordinateur, par Internet, à 2 comme à 6.

Tout le monde met le **même nom de salon** : ceux qui sont dedans apparaissent,
un clic sur un nom le klaxonne, le bouton jaune klaxonne tout le salon.

- **Chacun son son** : klaxon, pouet, camion, vuvuzela ou canard — les autres
  entendent le tien et savent que c'est toi.
- **Klaxon long** : tant que tu appuies, ça klaxonne (4 s maximum).
- **Riposte** : celui qui vient de te klaxonner clignote, un clic et tu ripostes.
- **Inviter** : copie le lien du salon, à coller sur Discord.
- **Amis** (bouton 👥) : chacun a un **code ami** (ex. `K7F3M-X9QP2`). Ajoute
  le code d'un ami, ou clique « + Ajouter » sur quelqu'un de ton salon : tu le
  vois en ligne, dans quel salon il est, et tu peux le **rejoindre** ou
  l'**inviter** là où tu es (il reçoit un bandeau « Rejoindre / Non »).
- **Groupes privés** : un salon au nom secret (`Soirée~k3j9x2p8qa`), créé en
  un clic. On n'y entre que par invitation ou par son lien ; les groupes
  connus restent dans la liste pour y revenir.

Tout se télécharge en bas de https://klaxon.stlkm.fr, ou dans la
[dernière release](https://github.com/Yurogin/klaxon/releases/latest).

- **Navigateur** : https://klaxon.stlkm.fr, rien à installer. Un lien du type
  `https://klaxon.stlkm.fr/#nom-du-salon` pré-remplit le salon. Chrome propose aussi de
  l'installer comme une appli.
- **Android** : `Klaxon-telephone.apk`. Android prévient que l'appli ne vient
  pas du Play Store : « Installer quand même ». Elle reste connectée en fond,
  sonne écran éteint, et ouvre elle-même les liens `klaxon.stlkm.fr` que les
  potes envoient.
  Elle demande à GitHub une fois par jour s'il existe une version plus récente,
  et propose alors un lien ; le bouton « Vérifier », en bas de l'écran Amis,
  pose la question tout de suite. C'est tout ce qui sort de ton téléphone vers
  GitHub.
- **Montre (Wear OS)** : `Klaxon-montre.apk`, à installer depuis le téléphone
  (mode développeur de la montre, `adb install`). Elle ne marche **qu'avec
  l'appli Android** : c'est le téléphone qui parle aux serveurs, la montre
  passe par lui en Bluetooth. Elle vibre et joue le son, on klaxonne du
  poignet, et une **tuile** met « Tout le monde » à un glissement du cadran.
- **Windows** : `Klaxon.exe`, installé par le hub STLKM ou pris dans les
  releases. En plus de la version web :
  - une **touche qui klaxonne depuis n'importe quel logiciel** (F9 par défaut,
    tenue = klaxon long), même en plein jeu ;
  - une **icône à côté de l'horloge** : fermer la fenêtre ne quitte pas,
    Klaxon continue d'écouter et surgit quand on te klaxonne ;
  - le **lancement avec Windows**, en fond ;
  - un bouton vers la version web.

Les messages passent par trois serveurs MQTT publics à la fois
(`broker.emqx.io`, `broker.hivemq.com`, `test.mosquitto.org`) : si l'un rame
ou tombe, les deux autres suffisent. Le nom du salon est haché avant d'être
envoyé, mais quelqu'un qui le devine peut vous klaxonner : prenez un nom peu
courant — ou un groupe privé.

Le statut envoyé aux amis (nom, salon) et les invitations sont chiffrés
(AES-GCM) avec une clé tirée du code ami : seul qui connaît ton code peut les
lire ou t'écrire. Garde-le pour tes amis. La version web et l'appli Windows ont
chacune leur code.

Fabriquer les applis et publier une nouvelle version : [PUBLIER.md](PUBLIER.md).
