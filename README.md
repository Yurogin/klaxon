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

## Où l'installer

Tout se télécharge sur https://klaxon.stlkm.fr (bas de la page d'accueil) ou
dans la [dernière release](https://github.com/Yurogin/klaxon/releases/latest).

- **Navigateur** : https://klaxon.stlkm.fr, rien à installer. Un lien du type
  `https://klaxon.stlkm.fr/#nom-du-salon` pré-remplit le salon. Chrome propose aussi de
  l'installer comme une appli.
- **Android** : `Klaxon-telephone.apk`. Android prévient que l'appli ne vient
  pas du Play Store : « Installer quand même ». Elle reste connectée en fond
  (notification permanente), sonne écran éteint, et ouvre elle-même les liens
  `klaxon.stlkm.fr` envoyés par les potes.
- **Montre (Wear OS)** : `Klaxon-montre.apk`. Elle ne marche **qu'avec l'appli
  Android** : c'est le téléphone qui parle aux serveurs, la montre passe par lui
  en Bluetooth. Elle vibre et joue le son, on klaxonne du poignet, et une
  **tuile** met « Tout le monde » à un glissement du cadran. Pour l'installer :
  activer le mode développeur de la montre et `adb install Klaxon-montre.apk`.
- **Windows** : `Klaxon.exe`, installé par le hub STLKM ou pris dans les
  releases. En plus de la version web :
  - une **touche qui klaxonne depuis n'importe quel logiciel** (F9 par défaut,
    tenue = klaxon long), même en plein jeu ;
  - une **icône à côté de l'horloge** : fermer la fenêtre ne quitte pas,
    Klaxon continue d'écouter et surgit quand on te klaxonne ;
  - le **lancement avec Windows**, en fond ;
  - un bouton vers la version web.

  Pour refaire l'exe : `windows/build.bat`. Mêmes salons que la version web.

## Comment ça marche

Les messages passent par trois serveurs MQTT publics à la fois
(`broker.emqx.io`, `broker.hivemq.com`, `test.mosquitto.org`) : si l'un rame
ou tombe, les deux autres suffisent. Le nom du salon est haché avant d'être
envoyé, mais quelqu'un qui le devine peut vous klaxonner : prenez un nom peu
courant — ou un groupe privé.

Le statut envoyé aux amis (nom, salon) et les invitations sont chiffrés
(AES-GCM) avec une clé tirée du code ami : seul qui connaît ton code peut les
lire ou t'écrire. Garde-le pour tes amis. La version web et l'appli Windows ont
chacune leur code.

## Refaire les binaires

- Windows : `windows/build.bat` (il faut Python) → `windows/Klaxon.exe`.
- Android et montre : `cd android && gradlew assembleRelease`, puis
  `mobile/build/outputs/apk/release/mobile-release.apk` → `Klaxon-telephone.apk`
  et `wear/…/wear-release.apk` → `Klaxon-montre.apk`. Les deux sont signées avec
  la clé de debug (`~/.android/debug.keystore`) : **garde cette clé**, une autre
  signature empêcherait la mise à jour par-dessus, et casserait l'ouverture
  automatique des liens (`.well-known/assetlinks.json` contient son empreinte).

Les binaires ne sont pas dans le dépôt : ils vont dans la release GitHub, et
dans `dl/` pour le site.

## Publier

1. Nouvelle [release](https://github.com/Yurogin/klaxon/releases/new) avec
   `Klaxon-telephone.apk`, `Klaxon-montre.apk` et `Klaxon.exe` en pièces jointes.
2. Sur le serveur du site, copier `index.html`, `sw.js`, `manifest.webmanifest`,
   `web.config`, `mqtt.min.js`, les icônes, `.well-known/assetlinks.json` et le
   dossier `dl/` (les trois binaires).
