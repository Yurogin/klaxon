# Klaxon

Klaxonne tes potes d'ordinateur à ordinateur, par Internet, à 2 comme à 6.

Tout le monde met le **même nom de salon** : ceux qui sont dedans apparaissent,
un clic sur un nom le klaxonne, le bouton jaune klaxonne tout le salon.

- **Navigateur** : ouvre la page, rien à installer. Un lien du type
  `…/klaxon/#nom-du-salon` pré-remplit le salon. Chrome propose aussi de
  l'installer comme une appli.
- **Windows** : `windows/klaxon.py`, ou `windows/build.bat` pour fabriquer
  `Klaxon.exe`. Mêmes salons que la version navigateur.

Les messages passent par le serveur MQTT public `broker.emqx.io`. Le nom du
salon est haché avant d'être envoyé, mais quelqu'un qui le devine peut vous
klaxonner : prenez un nom peu courant.
