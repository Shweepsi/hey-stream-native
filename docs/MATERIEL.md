# Matériel minimum : Xiaomi Mi Box S (2e génération)

Appareil de référence le plus faible. **Si l'app est fluide et stable dessus,
elle l'est partout.** Toute fonctionnalité doit y être testée avant publication.

## Caractéristiques

| | Mi Box S 2e gén. (2023) |
|---|---|
| Puce | Amlogic S905X4, 4 × Cortex-A55 |
| GPU | Mali-G31 MP2 (entrée de gamme) |
| Mémoire | 2 Go (≈ 1 Go réellement disponible pour une app) |
| Stockage | 8 Go eMMC (≈ 4 Go libres en pratique) |
| Système | Google TV, base **Android 11**, **espace utilisateur 32 bits** |
| Vidéo matérielle | H.264, HEVC, VP9, **AV1**, HDR10, HDR10+, Dolby Vision, 4K60 |
| Sortie | HDMI 2.0, 4K60 |

## Conséquences pour le projet

### Android
- **`minSdk = 30` (Android 11)** couvre la Mi Box S 2. Choix « Récent » validé.
  À noter : les Fire TV Stick sous Fire OS 7 (Android 9) sont exclus ; les
  Fire TV sous Fire OS 8 (Android 11) restent couverts.
- **Compiler le cœur Rust en 32 *et* 64 bits.** Le processeur est 64 bits mais
  Google TV y tourne en 32 bits : la cible `armv7-linux-androideabi` est
  **obligatoire**, à côté de `aarch64-linux-android` (exigée par Google Play).
  Un APK uniquement 64 bits ne s'installe pas sur cette box.
- **TLS** : `reqwest` + rustls utilise `aws-lc-rs` ; sa compilation croisée
  passe avec le NDK r28 seul, sans CMake (validé le 2026-10-28, CI `android`).
  Sur Android, le cœur vérifie les certificats avec les racines Mozilla
  embarquées (`webpki-root-certs`) : le vérificateur du système demanderait
  une initialisation JNI par l'app.
- **Taille** : cœur optimisé (LTO, symboles retirés) ≈ 7 Mo en arm64-v8a et
  5 Mo en armeabi-v7a, avant compression dans l'APK. Segments alignés sur
  16 Ko (exigence d'Android 15+), vérifié en CI.

### Mémoire (2 Go)
- Le catalogue (souvent 20 000 à 100 000 entrées) vit **dans SQLite**, jamais
  entièrement en mémoire ; les écrans lisent **page par page**.
- Listes **virtualisées** (`LazyColumn`/`LazyVerticalGrid`) ; aucune liste
  complète dans l'état Compose.
- Cache d'images en mémoire **plafonné** (~48 Mo), images redimensionnées à la
  taille affichée (pas d'affiche 2000 px pour une vignette de 200 px).
- Budget cible : **< 300 Mo** de mémoire en lecture.

### Stockage (8 Go)
- Cache disque d'images plafonné (~150 Mo) ; base SQLite compacte.
- APK cible : **< 25 Mo** par architecture (bundle AAB).

### GPU (Mali-G31 MP2)
- Pas de flou, d'ombres lourdes ni de transparences empilées ; animations de
  focus simples (échelle + bordure).
- Cible : **60 i/s** lors des déplacements de focus dans une grille d'affiches.
- **Baseline Profiles** obligatoires pour un démarrage et un défilement fluides.

### Lecture vidéo
- **Décodage matériel exclusivement** (Media3/ExoPlayer via MediaCodec) : la
  puce décode H.264/HEVC/AV1 en 4K ; un décodage logiciel (libmpv/FFmpeg)
  saturerait les 4 cœurs A55.
- Extension FFmpeg de Media3 **pour l'audio uniquement** (MP2, AC-3 sans
  passthrough), fréquent sur les flux TS IPTV.

### Réseau
- Wi-Fi 5 seulement (pas de port Ethernet intégré) : prévoir un tampon de
  lecture généreux et la **reconnexion automatique** du flux.

## Objectifs mesurables sur la Mi Box S 2

| Mesure | Objectif |
|---|---|
| Démarrage à froid → accueil | < 2 s |
| Ouverture d'une liste de 50 000 chaînes (cache chaud) | < 500 ms |
| Changement de chaîne (zapping) | < 1,5 s |
| Mémoire en lecture | < 300 Mo |
| Plantages | 0 sur 4 h de lecture continue |

## Premier appareil d'essai : Xiaomi Pad 7

Décidé le 2026-10-28 : le premier APK s'essaie sur une **Xiaomi Pad 7**
(tablette), avant la Mi Box S. La Mi Box S reste l'appareil de référence pour
les objectifs ci-dessus.

| | Xiaomi Pad 7 |
|---|---|
| Puce | Snapdragon 7+ Gen 3 (GPU Adreno 732) |
| Mémoire | 8 ou 12 Go |
| Système | Xiaomi HyperOS 2 (Android 15) |

Conséquences :
- **Processeur 64 bits uniquement** : le cœur Rust doit être compilé pour
  `aarch64-linux-android` (`arm64-v8a`) dès le premier APK. La cible 32 bits
  (`armv7-linux-androideabi`) reste obligatoire, mais seulement pour la
  Mi Box S.
- Écran tactile : la navigation doit marcher au doigt comme à la
  télécommande, toujours en paysage.
- Appareil bien plus puissant que la Mi Box S : il valide le fonctionnement,
  pas les performances.

## Apple

Minimum « Récent » : **iOS / iPadOS / tvOS 17**. L'Apple TV HD (2015) est
l'appareil Apple de référence le plus faible encore couvert.
