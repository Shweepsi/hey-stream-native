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
- **Versionnage sémantique** : correctif (0.2.**1**) pour une correction
  ou une petite retouche d'interface (un bouton, un libellé, un réglage
  d'affichage — ex. l'œil du mot de passe, 2026-10-31), mineure (0.**3**.0)
  pour une vraie fonctionnalité (un écran, un parcours, un comportement
  nouveau), majeure (**1**.0.0) à la première publication.
- **Chaque PR qui change l'app monte la version** et ajoute son entrée en
  tête de `CHANGELOG.md`. Une PR sans effet sur l'app (CI, docs) ne la monte pas.
- Le nom de l'APK de la CI porte la version (`hey-stream-0.2.0-<commit>`).
- **Publication** (2026-10-30) : workflow `release`, déclenché **de
  lui-même** par la fusion dans main d'une PR qui monte la version
  (décision du propriétaire : les appareils se mettent à jour sans qu'il
  ait à le demander). Il crée l'étiquette `vX.Y.Z` et la release GitHub avec
  l'APK. Une version déjà publiée n'est pas republiée ; lancement à la main
  encore possible.
- **Numérotation** (2026-11-03) : la version 0.8.0 est sautée (c'était celle
  de la PR #46, abandonnée). Après la 0.7.5, la série continue en **0.9.x**,
  une version par PR qui change l'app.
- **CI et publication** (2026-10-31) : pour garder les PR rapides, le test
  sur émulateur ne tourne plus sur les PR, seulement sur main (ou à la main).
  La release attend donc que **toute la CI android soit verte sur main,
  émulateur compris**, avant de publier : une régression vue seulement par
  l'émulateur ne part jamais vers les appareils. Si l'émulateur échoue sur
  main, corriger puis fusionner ; la version (non publiée) part avec la
  correction. Les liaisons Kotlin et Swift ne tournent sur une PR que si
  `ffi/` change, et à chaque push sur main.

## Langue des films et dernière catégorie (2026-11-03)

- **Réglage de langue** à trois valeurs, propre à l'abonnement : Automatique
  (piste par défaut du fichier), Doublée (langue de l'app, sans sous-titres
  sauf les forcés), Version originale sous-titrée (la piste annoncée « VO »,
  sinon la première dans une autre langue ; sous-titres complets dans la
  langue de l'app). **Films et
  épisodes seulement**, pas le direct (étiquettes de langue peu fiables, rares
  pistes multiples). Appliqué une fois au lancement d'un titre (sous-titres
  annoncés après l'audio : repris à leur arrivée) ; le choix à la main
  l'emporte ensuite, et le titre suivant repart des pistes par défaut (le
  lecteur est partagé).
- **Le cœur décide** (`tracks::choose_tracks`, fonction pure) : il lit le code
  de langue ou, à défaut, le libellé ; « VFF » passe avant « VFQ » et « VFI » ;
  l'audiodescription et les commentaires passent en dernier ; une piste
  étiquetée « VO » ou « VOSTFR » est l'original, jamais une version doublée ; si rien ne
  convient (aucune piste doublée, aucune piste d'origine), la piste par défaut
  reste. L'interface passe les pistes du lecteur et applique les numéros
  renvoyés.
- **Dernière catégorie** : retenue pour le Direct, les Films et les Séries
  (clé de l'interface, gardée telle quelle par le cœur), propre à l'abonnement.
  Ouverte si elle existe encore, n'est ni masquée ni verrouillée, et si
  Favoris, Récents ou « Continuer » ne sont pas vides. Priorité : le choix de
  la visite en cours, la chaîne venue de la recherche (Direct), puis la
  dernière catégorie, puis l'ouverture habituelle. Aux Films et aux Séries,
  elle passe avant « Continuer ».

## Profil de référence tiré de l'usage (2026-11-04)

- L'APK n'embarquait que les profils des bibliothèques : après chaque
  installation ou mise à jour (fréquentes, une PR = une version), le code de
  l'appli tournait interprété ou en JIT jusqu'à ce qu'Android le compile
  d'après l'usage (démarrage ≈ 480 à 610 ms selon l'heure sur la Mi Box,
  ≈ 440 ms une fois l'appli apprise).
- **Profil tiré de l'usage réel** : `cmd package dump-profiles` sur
  l'appareil de référence après une longue séance, converti en règles
  explicites (`scripts/profil_reference.mjs`). Des règles génériques par
  classe (`HSPL<classe>;->**(**)**`) avaient été essayées d'abord : quasi sans
  effet (≈ −25 ms), les jokers ne semblant pas appliqués.
- Mesure (protocole ProfileInstaller : `INSTALL_PROFILE` puis
  `compile -m speed-profile` forcé, deux manches alternées) : démarrage à froid
  ≈ 480 → ≈ 400 ms ; premier défilement de la grille inchangé (≈ 9 %).
- **Installation simple** (sans compilation forcée, mesuré le 2026-11-04 sur la
  Mi Box, deux manches alternées) : juste après l'installation et un premier
  lancement (ProfileInstaller écrit le profil), le démarrage est le même avec
  ou sans le profil (≈ 530 ms : la compilation à l'installation ne l'utilise
  pas encore). Quand le système recompile l'appli (`cmd package compile -r
  bg-dexopt`, sans `-f`, équivalent de la maintenance de fond), l'APK avec
  le profil est recompilé et démarre en ≈ 370 ms (346 à 388, −31 %) ; l'APK
  public, lui, n'est pas recompilé (statut inchangé, ≈ 535 ms) : sans ce profil,
  le système n'améliore pas le démarrage. Le gain arrive donc à la première
  maintenance de fond après l'installation, pas à l'instant de l'installation.
- **R8** : le profil vient d'un APK minifié ; R8 réécrit les règles à la
  compilation et ignore celles dont la classe est fusionnée ou générée par la
  compilation (lambdas synthétiques, fonctions « outline »). Le profil
  embarqué est un sous-ensemble du fichier de règles (profil binaire de 5,6 à
  10,9 Ko). Une génération depuis un APK non minifié éviterait cette perte.
- **Régénérer** après un changement important de l'interface ou des
  bibliothèques : `scripts/profil_reference.sh` (usage à la main sur la Mi
  Box, aucun script n'appelle le panneau ; le script ne fait qu'extraire et
  convertir le profil).

## Zapping : décodeurs gardés (2026-11-04)

- Mesure (Mi Box, sonde de la PR d'essai #56) : sur ≈ 860 ms entre la demande
  d'ouverture et la première image, ≈ 450 ms sont du réseau du fournisseur
  (redirection et réponse du serveur de flux), ≈ 160 ms la lecture du flux
  jusqu'au format vidéo, ≈ 125 ms le décodeur jusqu'à la première image, et
  ≈ 120 ms avant même la requête (le fil du lecteur libère encore l'ancien
  flux et son décodeur).
- **Mode premier plan de Media3** (`setForegroundMode(true)`) : les décodeurs
  restent ouverts entre deux flux ; la libération et la recréation du
  décodeur matériel disparaissent du chemin du zapping (≈ −90 ms, régulier sur
  deux manches alternées). Media3 réutilise le décodeur quand le format suit
  (même codec, taille compatible), sinon en crée un autre comme avant.
- **Rendus au système** : `setForegroundMode(false)` à la mise en
  arrière-plan, et aussi quand le lecteur n'a pas servi depuis 30 s app
  visible (navigation dans le catalogue ou les réglages : un décodeur matériel
  ne reste pas tenu pour rien sur une box qui en a très peu) ; `true` dès qu'un
  flux s'ouvre ou au retour. Un seul endroit règle ce mode
  (`PlayerController.applyForegroundMode`, propriété `decodersHeld` testée).
  La règle « jamais deux flux » ne change pas : l'ancien flux est toujours
  fermé avant l'ouverture du suivant (seuls les décodeurs, et leur surface,
  survivent à l'arrêt).
- **Décodeur écarté** : une erreur de lecture réinitialise les moteurs de
  rendu dans Media3 (même en mode premier plan) : le décodeur écarté après un
  plantage n'est pas repris par réutilisation.
- Écartés après mesure : réduire le tampon de démarrage (la lecture part déjà
  ≈ 20 ms après la première image), l'ouverture du décodeur elle-même
  (≈ 45 ms).

## Allègement de l'interface (2026-11-03)

Demandé par Loïc : moins de complexité visuelle. Retenu : compteur des lignes
de catégories retiré (DESIGN.md le prévoyait pour le Direct : le chiffre reste
dans l'en-tête de la grille, Films et Séries ; le Direct n'en affiche plus et
ne les compte plus), aides toujours affichées retirées : elles ne se montrent
plus qu'au premier usage (Direct : tant qu'aucun favori ; fiche série : tant
qu'aucun épisode n'est vu ni commencé), numéro d'un épisode sans titre écrit
une fois. Reste à faire (docs/TODO.md) : aux Films et aux Séries, ne compter
que la catégorie ouverte (le chargement compte encore toutes les catégories). **Gardé** : le nom des catégories du fournisseur, avec
son préfixe (« |FR| ✪ ACTION »), comme décidé le 2026-11-02, et l'étiquette
« FILMS / SÉRIES » au-dessus des catégories. Même principe aux Réglages
(textes d'introduction d'une phrase, mise en garde de la déconnexion retirée au
profit de la fenêtre de confirmation, version du cœur seulement si elle diffère,
crédits au chevron). Non touché : « Mises à jour » de « À propos » (repris par
la PR du parcours de mise à jour). Voir `DESIGN.md`, « Allègement ».

## Erreurs du lecteur (2026-11-03)

- **Le cœur décide** (`playback_retry(status, attempt)`, fonction pure) : 509
  arrête (abonnement utilisé ailleurs : relancer ne sert à rien tant que l'autre
  flux tourne ; « Réessayer » reste là), 401 et 403 arrêtent (compte expiré ou
  refusé ; décision du 3 novembre : « sans relance »), 404 a **une**
  relance puis s'arrête (« un seul essai » du plan : un flux introuvable l'est
  rarement par accident, mais un panneau peut répondre 404 un instant), tout le
  reste suit 1, 2, 4, 8, 8, 8 s comme avant. Le cœur ne produit aucun texte :
  il renvoie la raison, l'interface la traduit.
- **L'interface lit le code** dans l'erreur du lecteur (réponse HTTP à
  l'origine) et applique la décision. Un format que l'appareil ne sait pas
  décoder (ou au-delà de ses capacités) s'arrête sans consulter le cœur : un
  second essai n'y changerait rien. Les erreurs de lecture de conteneur ne
  comptent pas comme « format non pris en charge » : un panneau qui répond une
  page d'erreur avec le code 200 passerait pour un format.
- **Retour du réseau** : l'app suit le réseau par défaut. Après une **panne de
  connexion** (aucun code HTTP, et soit aucun réseau actif, soit une connexion
  échouée ou expirée), une reconnexion en attente part aussitôt et une lecture
  abandonnée repart d'elle-même, une fois par retour. Jamais après un refus ou
  une panne du fournisseur (509, 401, 403, 404, 5xx), ni après un décodeur
  planté ou un direct qui se termine sur un réseau présent : leurs délais
  restent, et un changement de réseau ne les raccourcit pas. « Pas de
  connexion Internet » remplace « Reconnexion… » quand aucun réseau n'est
  actif.
- **Décodeur écarté** : un décodeur matériel qui plante en cours de lecture
  n'est plus proposé jusqu'à la fin de l'app (liste en mémoire, rien d'écrit) ;
  s'il ne reste aucun autre décodeur, la liste complète revient. La reprise se
  fait à la même position, par les relances habituelles. Le nom d'un décodeur
  matériel est propre à un codec : l'écarter ne touche pas les autres formats.
  Un seul plantage suffit (décision du plan : « l'écarter pour la session »).
- **Bord du direct** : une erreur « trop loin derrière le direct » (HLS)
  rouvre au bord du direct, trois fois au plus par fenêtre de 30 s, sans
  compter comme une panne ; au-delà, ce sont les relances habituelles.
- **Catalogue** : le rafraîchissement automatique (ouverture de l'app, tâche
  quotidienne) attend la fin d'une lecture, c'est-à-dire tant qu'un flux est
  ouvert ou se charge (pas à l'arrêt, ni en arrière-plan, ni fini) ; la tâche
  quotidienne saute alors son tour au lieu de se relancer en cascade ; le
  bouton manuel part toujours.
- **Télémétrie** (APK du testeur) : le code HTTP s'ajoute à l'événement
  d'erreur de lecture et au rapport d'abandon ; jamais l'adresse du flux.

## Vus, « Continuer » et code parental (2026-11-03)

- **« Vu » à la main garde la position** : masquée tant que la marque est la
  plus récente (pas de barre, pas dans « Continuer »), gardée pour
  « Reprendre » sur la fiche ; « non vu » la ramène. Seule une fin de
  lecture efface la position.
- **Épisode suivant** : à la fin d'un épisode, le cœur écrit pour le premier
  épisode suivant pas encore vu (ordre de la fiche, saison suivante comprise)
  une reprise à 0, sans durée : « Continuer » le propose, sans barre, et la
  fiche dit « Lecture ». Seulement après une fin de lecture, jamais après un
  « vu » mis à la main (marquer un vieil épisode ne doit pas remplacer la
  reprise en cours). Sans ordre connu (fiche jamais ouverte), rien. Revoir
  jusqu'au bout un épisode déjà vu propose de même le premier épisode pas
  encore vu après lui. La reprise d'un épisode qui avait déjà une position
  garde cette position, son titre et son image : seule sa date change.
- **Dernier épisode commencé marqué « vu » à la main** : la série quitte
  « Continuer » (rien à reprendre), même si un épisode plus ancien avait une
  position : celui-ci a été laissé pour un épisode plus récent.
- **Code parental** : l'ancien code est vérifié dès la première étape d'un
  changement ; il peut être supprimé avec le code actuel, et les catégories
  adultes sont alors verrouillées comme avant tout code.

## Noms affichés (2026-11-02, 2026-11-03)

- **Chaînes, films, séries** : la mention du français (« FR - », « |FR| »,
  « _fr ») et la mention de qualité en tête (« 4K », « 4KL », « UHD »,
  « FHD », « HD ») sont retirées du nom affiché, sans badge (2026-11-02). Les
  autres langues restent écrites ; les catégories gardent leur nom.
- **Films et séries seulement** (2026-11-03, `names::clean_title`) : une
  seule espace entre les mots ; un nom de fichier de publication devient un
  titre (« 15.Aout.2001.1080p.TF1.WEB-DL » donne « 15 Aout (2001) ») ; les
  mentions techniques en fin de nom (HD, FHD, UHD, 4K, HDR, HEVC, x265…) sont
  retirées. **MULTI, VOSTFR et VOST restent** : elles disent quelle version
  on lance. **Les chaînes gardent leurs mentions** (« TF1 ᵁᴴᴰ » distingue
  deux flux d'une même chaîne).
- **L'année** reste dans le titre des grilles, où elle distingue les
  homonymes (« Dune (1984) », « Dune (2021) ») ; la fiche, qui la montre déjà
  sur sa ligne d'infos, l'ôte du titre (`names::title_without_year`).
- **Tout reste cherchable** : la clé de recherche garde le nom du
  fournisseur, mentions comprises.
- Les noms déjà enregistrés sont recalculés à l'ouverture (migrations 6 et
  7), sans recharger le catalogue.

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
