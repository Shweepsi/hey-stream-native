# Décisions produit et techniques

Décidé le 2026-10-28 par le propriétaire du projet. Ne pas revenir dessus sans
nouvelle décision.

## Produit

- **Public** : grand public, y compris des personnes peu à l'aise avec la technique.
- **Appareils** : téléphone, tablette et TV, Android et Apple, dès la première version.
- **Sources** : Xtream Codes uniquement (pas de M3U, Stalker ni EPG externe en v1).
- **v1** : accueil en trois tuiles **Direct / Films / Séries**, puis listes et lecture.
- **Lecteur intégré** : la vidéo se lit dans l'app (zapping rapide). Pas de
  renvoi vers un lecteur externe en v1.
- **Tout en local** : pas de compte, pas de serveur, pas de synchronisation.
- **Pas de multi-écran**, ni en v1 ni plus tard sans nouvelle décision.
- **Pas de TMDB** : on affiche les données du fournisseur.
- **Gratuit, open source (GPL-3.0)**, sans publicité ni version payante.
- **Langues** : multilingue ouvert — français et anglais d'abord, traductions
  communautaires ensuite (Weblate).
- **Diffusion** : Google Play et App Store (dont Apple TV).
- **Nom** : hey-stream.

## Ce que l'on garde de Smarters Pro — et comment, légalement

Ce que les utilisateurs aimaient : **une interface évidente et la stabilité**.
On reproduit ces *qualités*, pas l'apparence :

- On reprend l'organisation (tuiles Direct / Films / Séries, trois panneaux en
  direct sur tablette et TV, tout à deux clics) — ce sont des idées et des
  conventions communes, non protégées.
- On ne reprend **ni le nom, ni le logo, ni les icônes, images, couleurs ou
  textes** de Smarters, et **aucun code** : pas de décompilation de l'APK.
  L'abandon d'une app ne lève pas son droit d'auteur.
- L'étude d'un logiciel par observation de son fonctionnement (art. L122-6-1 III
  CPI), par ex. la capture de ses requêtes réseau sur son propre abonnement,
  est permise et peut nourrir la spécification.

## Technique

- **Cœur Rust** pour toute la logique ; **interfaces natives** : Jetpack Compose
  (+ Compose for TV) sur Android, SwiftUI sur Apple. Une interface 100 % Rust
  a été écartée : ni tvOS, ni le focus télécommande Android TV ne sont mûrs.
- **Lecteurs** : Media3/ExoPlayer (décodage matériel) sur Android, AVPlayer
  (+ VLCKit pour MKV/TS si besoin) sur Apple.
- **Format du direct** : TS sur Android, HLS (`m3u8`) sur Apple (AVPlayer ne
  lit pas le TS continu) ; si le fournisseur n'autorise que l'autre format
  (`allowed_output_formats`), c'est lui qui est demandé.
- **Minimum** : voir `MATERIEL.md` — appareil de référence Xiaomi Mi Box S 2e gén.

## Panneau de référence

Un abonnement réel sert de référence pour les essais (identifiants **jamais**
dans le dépôt : variables `XTREAM_URL`, `XTREAM_USER`, `XTREAM_PASS`, voir
`core/examples/smoke.rs`). Mesures du 2026-10-28 depuis un serveur cloud :

| | Catégories | Entrées | Temps |
|---|---|---|---|
| Direct | 63 | 3 330 | ~2 s |
| Films | 50 | 29 372 | ~4 s |
| Séries | 33 | 8 637 | ~2 s |

Règle : **aucun appel automatique** au panneau (CI, cloud, scripts) ; essais
réels uniquement à la main par le propriétaire, depuis son réseau. Le risque
qui compte (propriétaire, 2026-10-30) : un **blocage de l'IP** par le
fournisseur, plus gênant que la perte du compte. D'où peu de requêtes,
espacées, jamais en rafale.

**Enregistrement des réponses** (2026-10-30) : plutôt qu'une capture qui
envoie ses propres requêtes, l'app enregistre les réponses pendant l'usage
normal du propriétaire (Xiaomi 14, Xiaomi Pad 7), sans **aucune** requête en
plus (cœur `recording.rs`). Dans l'APK normal, derrière des outils de test
cachés (sept appuis sur la version dans Réglages › À propos), désactivés par
défaut : un APK debug sur une longue période n'aurait ni rapports de
plantage, ni les performances de l'APK publié. Chaque réponse est nettoyée
avant d'être écrite (identifiants, adresse du serveur, hôtes et jetons des
URL, adresses IP), les listes sont échantillonnées (300 entrées) et le nombre
de fichiers est plafonné par type. Le propriétaire partage le fichier
(Drive) ; il devient une réponse simulée des tests dans
`core/tests/fixtures/`, relue avant d'être versionnée.

## Données des testeurs (2026-10-31)

Décision du propriétaire : beaucoup de données réelles, de testeurs qui ont
le même panneau que lui (autres appareils, autres usages), analysées par
une IA (rapport chaque lundi). Mise en route et lecture : `docs/COLLECTE.md`.

- **Deux APK** à chaque version : l'APK testeur
  (`-t.apk`, version `X.Y.Z-t`, `-Pheystream.testeur=true`) collecte dès
  l'installation ; l'APK public ne collecte rien. L'APK testeur n'est
  **jamais sur GitHub** (dépôt public : un inconnu qui le trouverait n'a rien
  accordé, et la clé d'envoi qu'il contient serait publique). Le workflow
  `release` le dépose dans le stockage R2 du service ; le propriétaire le
  télécharge (`/v1/admin/apk`) et le remet aux testeurs ; l'APK testeur se
  met ensuite à jour depuis le service (`/v1/update`, clé d'envoi), l'APK
  public depuis GitHub. Un APK `-t.apk` trouvé sur GitHub n'est jamais
  proposé.
- **Accord** recueilli par le propriétaire en remettant l'APK : rien n'est
  affiché dans l'app (choix du propriétaire).
- **Tout** : réponses entières du panneau (enregistrement complet, même
  nettoyage que les outils de test), usage, lecture, plantages, appareil.
  Numéro aléatoire par appareil, jamais d'URL ni d'identifiant.
- **Envoi** sur tout réseau, jamais pendant une lecture ; **aucune requête
  en plus** vers le panneau.
- **Cloudflare** (choix du propriétaire) : un Worker
  (`cloudflare/collecte`) range les lots dans R2. La clé d'envoi, dans
  l'APK, ne permet pas de lire ; la clé d'administration ne quitte pas les
  secrets. Conservation **un an** ; **coupure à distance** de tous ou d'un
  appareil.

## Mises à jour de l'app (2026-10-30)

Décision du propriétaire, pour réduire son implication : l'app se met à jour
elle-même depuis les **releases GitHub** du dépôt (workflow `release`).

- Le cœur (`update.rs`) lit `/repos/…/releases` (pas `/releases/latest`, qui
  ignore les pre-releases), garde la plus haute version qui a un APK,
  télécharge et vérifie l'APK (taille, `digest` SHA-256 fourni par GitHub).
- L'app le confie à l'installateur d'Android (`PackageInstaller`). La
  première fois, Android demande d'autoriser l'app à installer des
  applications (l'installation reprend d'elle-même au retour) et une
  confirmation. Ensuite, l'app ayant installé la version en place, Android
  12+ ne demande plus de confirmation (`USER_ACTION_NOT_REQUIRED`, décision
  du propriétaire) : « Installer » suffit, l'app se ferme et il faut la
  rouvrir. Si le système l'exige quand même (surcouche du fabricant), sa
  confirmation s'affiche. Même clé de signature : l'app est remplacée sans
  rien effacer.
- Vérification à chaque retour au premier plan, une fois par jour au plus
  (APK release seulement : ni les tests ni l'émulateur n'interrogent
  GitHub), plus un bouton dans Réglages › À propos. « Plus tard » ferme la
  boîte : la version est reproposée le lendemain (décision du
  propriétaire).
- Limite connue : la clé de signature de test est publique ; la mise à jour
  ne vaut que ce que vaut le dépôt. À revoir avec la clé privée de la 1.0.
- Un magasin d'applications (Play Store) interdit qu'une app se mette à jour
  elle-même : à retirer ou désactiver dans la version publiée sur un
  magasin (`TODO.md`).

Constats :
- 1 connexion simultanée maximum : l'app ne doit **jamais** ouvrir deux flux
  en même temps (aperçu + plein écran compris).
- Deux adresses (« private » et « generated ») servent le même compte : le
  cache est lié à l'identifiant, pas à l'adresse (changer d'adresse garde
  favoris, reprise et code PIN ; décision du propriétaire, 2026-10-28).
- `get_vod_categories` renvoie `[]` sur **environ 60 % des appels** (mesuré
  sur 8 appels, les autres listes sont stables) : le cache retente jusqu'à
  6 fois une liste de catégories vide et n'écrase jamais un catalogue connu
  par une réponse vide.
- L'ordre des catégories varie d'une requête à l'autre : trier côté app.

## Identité et publication (2026-10-28)

- **Nom affiché : « Hey Stream »**, conservé par choix du propriétaire.
  Risque connu : un fournisseur IPTV tiers utilise un nom de domaine très
  proche. Un examinateur de store pourrait prendre l'app pour l'app
  officielle de ce fournisseur. À réévaluer avant la
  publication. En attendant, **aucune URL de panneau n'apparaît dans l'app,
  le dépôt ni les captures d'écran**.
- **Identifiant d'app : `io.github.shweepsi.heystream`** (Android
  `applicationId` et Apple bundle ID), définitif une fois publié.
- **Nouvelle identité visuelle**, distincte de hey-stream web et de Smarters :
  logo et palette à proposer avant les premiers écrans.
- **Tests** : le propriétaire installe les APK produits par la CI sur sa
  Mi Box S 2e génération et rapporte les résultats.
- **Comptes développeur (Google Play, Apple) : pas encore.** Distribution des
  versions de test par APK uniquement.
- **Rapports de plantage : GlitchTip** (compatible avec le SDK Sentry, libre,
  auto-hébergeable). Anonymes : ni identifiants ni URL de flux. Précisé le
  2026-10-29 : offre gratuite de glitchtip.com ; activés par défaut,
  interrupteur dans Réglages ; plantages Kotlin, paniques Rust, gels (ANR),
  lectures abandonnées ; release seulement ; clé dans le secret
  `GLITCHTIP_DSN` de la CI, jamais dans le dépôt.
- **Politique de confidentialité : GitHub Pages** du dépôt. Faite le
  2026-10-29 : `site/` (accueil et politique, français et anglais), publié
  par `.github/workflows/pages.yml` sur https://shweepsi.github.io/hey-stream-native/ ;
  contact par les issues GitHub ; aucun cookie, script ni ressource externe.

## Versions (2026-10-29)

- **Une seule version** pour le cœur, les liaisons et les apps :
  `[workspace.package] version` du `Cargo.toml` racine. L'app Android la lit
  (`versionName`) et en tire `versionCode` (1.2.3 → 10203).
- **Versionnage sémantique** : correctif (0.2.**1**) pour une correction,
  mineure (0.**3**.0) pour une fonctionnalité ou un changement visible,
  majeure (**1**.0.0) à la première publication.
- **Chaque PR qui change l'app monte la version** et ajoute son entrée en
  tête de `CHANGELOG.md`. Une PR sans effet sur l'app (CI, docs) ne la monte pas.
- Le nom de l'APK de la CI porte la version (`hey-stream-0.2.0-<commit>`).
- **Publication** (2026-10-30) : workflow `release`, déclenché **de
  lui-même** par la fusion dans main d'une PR qui monte la version
  (décision du propriétaire : les appareils se mettent à jour sans qu'il
  ait à le demander). Il crée l'étiquette `vX.Y.Z` et la release GitHub avec
  l'APK. Une version déjà publiée n'est pas republiée ; lancement à la main
  encore possible.
- **CI et publication** (2026-10-31) : pour garder les PR rapides, le test
  sur émulateur ne tourne plus sur les PR, seulement sur main (ou à la main).
  La release attend donc que **toute la CI android soit verte sur main,
  émulateur compris**, avant de publier : une régression vue seulement par
  l'émulateur ne part jamais vers les appareils. Si l'émulateur échoue sur
  main, corriger puis fusionner ; la version (non publiée) part avec la
  correction. Les liaisons Kotlin et Swift ne tournent sur une PR que si
  `ffi/` change, et à chaque push sur main.

## Image publique et listes M3U (réflexion, 2026-10-29)

Constat : les lecteurs reconnus (TiviMate, OTT Navigator) se présentent comme
de simples **lecteurs** (« ne fournit aucun contenu, n'est lié à aucun
fournisseur »), l'utilisateur apportant sa source. IPTV Smarters a été retiré
de Google Play en 2019 sur plainte d'ayants droit, puis réintégré en faisant
valoir qu'il n'est qu'un lecteur. Le nom « Xtream Codes » reste associé à
l'opération antipiratage européenne de 2019. Le site présente donc Hey Stream
comme un lecteur, avec l'avertissement près du téléchargement, et ne cite
Xtream Codes qu'une fois, comme compatibilité technique.

Limite : un lecteur qui ne lit que l'API Xtream sert surtout à des
abonnements de provenance douteuse ; les sources légales (box des
opérateurs, chaînes gratuites) passent plutôt par des **listes M3U**
(hors périmètre v1, `SPEC_V1.md`). Ajouter M3U (avec guide XMLTV) serait le
signal de légitimité le plus fort, notamment pour l'examen Google Play.
**Non décidé** : à trancher avant la publication 1.0.

## Licence (2026-10-30)

- **GPL-3.0-or-later**, avec une **permission supplémentaire** (article 7 de
  la GPL-3) pour la distribution par les magasins d'applications
  (`LICENSE-EXCEPTION.md`), posée tant que le propriétaire est le seul auteur.
  Elle couvre le code de Hey Stream, pas les composants tiers (FFmpeg).
- **Contributions** : DCO (`Signed-off-by` sur chaque commit), pas de CLA ;
  chaque contributeur garde ses droits.
- **Licences tierces** affichées dans l'app (Réglages › À propos) : liste des
  bibliothèques Android générée par AboutLibraries à la compilation, liste
  des crates Rust par `cargo about` (`scripts/third_party_licenses.sh`,
  vérifiée par la CI). Lien vers les sources du décodeur FFmpeg (GPL).
