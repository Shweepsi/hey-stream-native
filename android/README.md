# Hey Stream — Android

Un seul APK pour téléphone, tablette et TV (Android 11 et plus). Jetpack
Compose pour l'interface, Media3 pour la lecture, et le cœur Rust (`../core`,
`../ffi`) pour toute la logique.

## Installer une version de test

Chaque passage de la CI (`android` › `application`) publie l'APK dans
l'onglet **Actions** du dépôt : artefact `hey-stream-<commit>` (une archive
zip qui contient `app-release.apk`).

- **Tablette ou téléphone** : copier l'APK sur l'appareil, l'ouvrir et
  autoriser l'installation d'applications inconnues.
- **Mi Box S / Google TV** : activer les options pour les développeurs
  (Paramètres › Système › À propos › appuyer 7 fois sur « Build Android TV »),
  puis le débogage ADB, et depuis un ordinateur sur le même réseau :
  `adb connect <adresse-de-la-box>` puis `adb install -r app-release.apk`.

L'APK contient le cœur en 64 bits (`arm64-v8a`, Xiaomi Pad 7) et en 32 bits
(`armeabi-v7a`, Mi Box S).

**Clé de test** : les APK sont signés avec `app/test.keystore`, une clé
publique versionnée exprès. Ainsi chaque nouvel APK s'installe par-dessus le
précédent, sans désinstaller ni se reconnecter. Elle n'offre aucune sécurité :
une clé privée la remplacera avant toute publication sur un store.

## Construire

Prérequis : JDK 17+, SDK Android (plateforme 37), NDK `28.2.13676358`, Rust
avec les cibles `aarch64-linux-android` et `armv7-linux-androideabi`, et
`cargo install cargo-ndk`.

```sh
cd android
./gradlew :app:assembleRelease   # APK de test (R8)
./gradlew :app:testDebugUnitTest # tests JVM, dont les captures d'écran
./gradlew :app:recordRoborazziDebug  # captures dans app/build/outputs/roborazzi/
```

À chaque build, Gradle lance cargo : `cargoNdk` compile le cœur pour chaque
architecture, `uniffiBindings` génère les liaisons Kotlin. Rien de tout cela
n'est versionné. `-Pheystream.abis=x86_64` compile pour un émulateur.

## Organisation

```
app/src/main/java/io/github/shweepsi/heystream/
  data/        session du cœur, identifiants chiffrés, pagination, mise à jour quotidienne
  player/      lecteur unique (Media3), image vidéo
  ui/theme/    couleurs, police Inter, tailles TV et tactiles, icônes Lucide
  ui/components/  éléments communs (focus, boutons, affiches, code PIN…)
  ui/<écran>/  un dossier par écran : Screen → ViewModel → Content
```

- Toute la logique reste dans le cœur Rust : les écrans ne font qu'afficher.
- Le design suit `docs/DESIGN.md` : à lire avant de toucher à l'interface.
- Textes : `res/values/` (anglais, langue par défaut) et `res/values-fr/`.
- Les icônes et le logo sont générés par `tools/lucide_icons.py` et
  `tools/brand_assets.py`.

## Tests

- **JVM** (`testDebugUnitTest`) : logique et captures d'écran de chaque écran
  (Robolectric, Roborazzi), sur TV, tablette et téléphone.
- **Émulateur** (CI, job `émulateur`) : sur un vrai Android 11, l'app se
  connecte à un faux panneau local, télécharge le catalogue et ouvre le
  Direct. Aucun vrai fournisseur n'est contacté. Pour garder les PR
  rapides, il tourne après chaque fusion dans main, ou à la main (*Actions*
  › *android* › *Run workflow*, sur la branche de la PR). La release attend
  qu'il soit vert sur main.
- **Appareils** : la Mi Box S reste l'appareil de référence
  (`docs/MATERIEL.md`) ; les essais avec un vrai abonnement se font à la
  main, par le propriétaire.

## Licences tierces

Inter (SIL OFL 1.1) et Lucide (ISC) : textes dans `app/src/main/assets/licenses/`.
AndroidX, Media3, Coil, OkHttp, JNA : Apache 2.0. Décodeur audio FFmpeg de
Jellyfin : GPL.
