# Publier une version

Pour moi, pas pour les potes : comment refaire les applis et les mettre en
ligne. La présentation du projet est dans [README.md](README.md).

## Avant de refaire les binaires

Monter `versionCode` et `versionName` dans `android/mobile/build.gradle.kts` et
`android/wear/build.gradle.kts`, et donner à la release le tag correspondant
(`v1.1` pour `versionName = "1.1"`). C'est là-dessus que l'appli compare : sans
ça, personne ne verra qu'une version est sortie.

## Refaire les binaires

- **Windows** : `windows/build.bat` (il faut Python) → `windows/Klaxon.exe`.
- **Android et montre** : `cd android && gradlew assembleRelease`, puis
  renommer `mobile/build/outputs/apk/release/mobile-release.apk` en
  `Klaxon-telephone.apk` et `wear/build/outputs/apk/release/wear-release.apk`
  en `Klaxon-montre.apk`.

Les deux APK sont signées avec la clé de debug (`~/.android/debug.keystore`).
**Garde cette clé** : avec une autre signature, les potes ne pourraient plus
mettre à jour par-dessus, et l'appli n'ouvrirait plus toute seule les liens
`klaxon.stlkm.fr` (`.well-known/assetlinks.json` contient l'empreinte de
celle-ci).

Les binaires ne sont pas dans le dépôt (85 Mo à chaque build) : ils vont dans
la release GitHub, et dans `dl/` pour le site.

## Mettre en ligne

1. **La release** : une [nouvelle release](https://github.com/Yurogin/klaxon/releases/new)
   avec `Klaxon-telephone.apk`, `Klaxon-montre.apk` et `Klaxon.exe` en pièces
   jointes. Les liens `…/releases/latest/download/<fichier>` suivent toujours
   la dernière. Le hub STLKM y prend `Klaxon.exe` tout seul (fiche `klaxon` de
   `stlkm-catalog`, `source = github-release`) : rien à faire de ce côté.
2. **Le site** : copier sur le serveur `index.html`, `sw.js`,
   `manifest.webmanifest`, `web.config`, `mqtt.min.js`, `icon.svg`,
   `icon-512.png`, `.well-known/assetlinks.json` et le dossier `dl/` avec les
   trois binaires. Sans `dl/`, les boutons de téléchargement de la page
   d'accueil tombent sur une 404.

Si `index.html` change, penser à monter le numéro de `CACHE` dans `sw.js` :
c'est ce qui vide le cache des applis déjà installées.
