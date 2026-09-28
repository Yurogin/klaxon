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

Les deux APK sont signées par `~/.android/debug.keystore`. **Cette clé est
irremplaçable** : avec une autre signature, les potes ne pourraient plus mettre
à jour par-dessus, et l'appli n'ouvrirait plus toute seule les liens
`klaxon.stlkm.fr`. Une sauvegarde est sur le Drive ; si un jour tu la restaures,
vérifie que c'est la bonne avant de bâtir :

```
keytool -list -v -keystore %USERPROFILE%\.android\debug.keystore -storepass android -alias androiddebugkey
```

L'empreinte SHA-256 doit être `5F:86:94:C2:D3:79:B0:C8:52:3C:0D:AD:5B:27:8A:91:
D0:0A:9D:23:81:86:61:2D:86:18:44:A5:4D:2A:38:DC`, la même que dans
`.well-known/assetlinks.json`. Si elle diffère, n'assemble rien : Android Studio
en a recréé une au hasard, et il faut remettre la vraie.

Les binaires ne sont pas dans le dépôt (trop lourds à chaque build) : ils vont
dans la release GitHub, d'où le site les fait télécharger. `dl/` n'est qu'un
dossier de préparation local, le temps de les déposer sur la release.

## Mettre en ligne

1. **La release** : une [nouvelle release](https://github.com/Yurogin/klaxon/releases/new)
   avec `Klaxon-telephone.apk`, `Klaxon-montre.apk` et `Klaxon.exe` en pièces
   jointes. Les liens `…/releases/latest/download/<fichier>` suivent toujours
   la dernière. Le hub STLKM y prend `Klaxon.exe` tout seul (fiche `klaxon` de
   `stlkm-catalog`, `source = github-release`) : rien à faire de ce côté.
2. **Le site** : rien à faire pour une nouvelle version. Les boutons de la page
   d'accueil pointent sur `releases/latest/download/<fichier>`, qui suit tout
   seul la dernière release. On ne copie sur le serveur (`index.html`, `sw.js`,
   `manifest.webmanifest`, `web.config`, `mqtt.min.js`, les icônes,
   `.well-known/assetlinks.json`) que lorsqu'on a modifié ces fichiers-là.

   Attention si tu remets un jour des fichiers dans `dl/` : Cloudflare les garde
   quatre heures, donc le site servirait encore les anciens. Il faudrait vider
   son cache après chaque copie.

Si `index.html` change, penser à monter le numéro de `CACHE` dans `sw.js` :
c'est ce qui vide le cache des applis déjà installées.
