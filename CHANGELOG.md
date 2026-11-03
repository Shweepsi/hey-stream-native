# Journal des versions

Une entrée par version, la plus récente en haut. La version vient de
`[workspace.package] version` dans `Cargo.toml` (voir `docs/DECISIONS.md`,
« Versions »).

## 0.9.3 — 2026-11-03

- Erreurs du lecteur : le cœur décide de la suite selon le code HTTP du
  fournisseur (`playback_retry`). 509 : arrêt, « abonnement utilisé ailleurs » ;
  401 et 403 : arrêt, « compte expiré ou refusé » ; 404 : une seule relance,
  puis « flux introuvable » ; les autres codes et les coupures sans réponse
  gardent les relances après 1, 2, 4, 8, 8 et 8 s. Un format que l'appareil ne
  sait pas lire s'arrête aussitôt avec son message.
- Retour du réseau : après une panne de connexion, une reconnexion qui attend
  son délai part tout de suite, et une lecture abandonnée repart d'elle-même
  (jamais après un refus ou une panne du fournisseur, ni après un décodeur
  planté). Sans réseau, « Pas de connexion Internet » remplace
  « Reconnexion… ».
- Un décodeur matériel qui plante en cours de lecture est écarté jusqu'à la fin
  de l'app : la reprise, à la même position, en prend un autre.
- Direct trop en retard sur son bord (HLS) : retour au bord du direct (trois
  fois par fenêtre de 30 s), sans compter comme une panne.
- Le rafraîchissement automatique du catalogue (ouverture de l'app, tâche
  quotidienne) attend qu'aucun flux ne soit ouvert ; « Mettre à jour
  maintenant » part toujours.
- Télémétrie du testeur et rapports d'abandon : le code HTTP est joint à
  l'erreur de lecture, et un décodeur écarté est noté.

## 0.9.2 — 2026-11-03

- Langue des films et des épisodes : un réglage à trois valeurs dans
  Réglages › Langue. « Automatique » (piste par défaut du fichier, comme
  avant), « Doublée » (piste dans la langue de l'app, sans sous-titres, sauf
  les sous-titres forcés de cette langue) ou « Version originale sous-titrée »
  (la piste annoncée « VO », sinon la première dans une autre langue, avec les
  sous-titres complets dans la langue de l'app). Appliqué une fois au lancement
  (les sous-titres annoncés plus tard que l'audio, comme dans certains flux
  HLS, sont repris à leur arrivée) ; un choix fait ensuite dans le lecteur
  l'emporte, et chaque titre repart des pistes par défaut. Le cœur lit la
  langue d'une piste par son code (« fr », « fra », « fr-CA »…) ou son libellé
  (« VFF », « Français », « TRUEFRENCH »…), met « VFF » avant « VFQ » et
  l'audiodescription en dernier ; une piste « VOSTFR » est l'original, pas une
  version doublée. Pas pour le direct. Propre à l'abonnement.
- Dernière catégorie : le Direct, les Films et les Séries s'ouvrent sur la
  dernière catégorie ouverte, si elle s'ouvre encore (elle existe, n'est ni
  masquée ni verrouillée, et Favoris, Récents ou « Continuer » ne sont pas
  vides) ; sinon, comme avant. Aux Films et aux Séries, elle passe avant
  « Continuer ». Une chaîne venue de la recherche garde la priorité.

## 0.9.1 — 2026-11-03

- Code parental : l'ancien code est vérifié dès la première étape d'un
  changement (plus de nouveau code à saisir pour rien, et la vérification
  compte dans les 5 essais). Un bouton « Supprimer le code » apparaît dans
  les réglages, avec le code actuel ; ensuite, les catégories adultes sont
  verrouillées comme avant tout code (en ouvrir une propose d'en créer un).
- Vus : un film ou un épisode marqué « vu » à la main garde sa position. Il
  quitte « Continuer » et perd sa barre (la coche reste), mais la fiche
  propose toujours « Reprendre » ; « Marquer comme non vu » le ramène dans
  « Continuer ». Vu jusqu'au bout, la position est toujours effacée.
- Séries : quand un épisode est vu jusqu'au bout, « Continuer » propose le
  premier épisode suivant pas encore vu (saison suivante comprise), sans
  barre ; la fiche affiche « Lecture S1 · E6 ». À la fin de la série, elle
  quitte « Continuer ». Le cœur retient l'ordre des épisodes à l'ouverture
  de la fiche (migration 8 du catalogue).

## 0.9.0 — 2026-11-03

- Noms des films et des séries (les chaînes ne changent pas) : une seule
  espace entre les mots, aucune après « ( » ni avant « ) » (« 9 Bullets
  ( 2022) » devient « 9 Bullets (2022) ») ; un nom de fichier de publication
  devient un titre (« 15.Aout.2001.1080p.TF1.WEB-DL » donne « 15 Aout
  (2001) ») ; les mentions techniques en fin de nom disparaissent (« Dune
  (2021) FHD » donne « Dune (2021) » : HD, FHD, UHD, 4K, HDR, HEVC, x265…).
  MULTI, VOSTFR et VOST restent. Tout reste cherchable. Les noms qui peuvent
  changer sont recalculés à l'ouverture (migration 7 du catalogue).
- Fiches : le titre n'affiche plus l'année quand la ligne d'infos la montre
  déjà (« 22 Jump Street (2014) » au-dessus de « 2014 »). Les grilles la
  gardent : elle distingue « Dune (1984) » de « Dune (2021) ».
- Numérotation : la version 0.8.0 est sautée, la série continue en 0.9.x
  (`docs/DECISIONS.md`, « Versions »).

## 0.7.5 — 2026-11-02

- Noms : la mention de qualité en tête (« 4K », « 4KL », « UHD », « FHD »,
  « HD », aussi « |4K| » ou empilée avec « FR - ») est retirée du nom
  affiché, comme celle du français. « 4KL Black Box (2021) » devient
  « Black Box (2021) ». Elle reste cherchable : « 4k gravity » trouve la
  version 4K. Les noms qui peuvent en contenir une sont recalculés à
  l'ouverture (migration 6 du catalogue).
- TV : le titre sous les affiches tient sur deux lignes au lieu d'une, comme
  au doigt ; il ne défile plus au focus.

## 0.7.4 — 2026-11-02

- Lecture : tampon plafonné selon l'appareil, à la moitié du tas Java de
  l'app (entre 32 et 128 Mo, 64 Mo au plus sur une box de 2 Go). Sur une
  NVIDIA Shield, un film 4K occupait 161 Mo du tas (limite 192 Mo), au bord
  du manque de mémoire ; le plafond y est de 96 Mo, soit ~13 s d'une 4K à
  60 Mbit/s, et les 50 s maximales d'un flux HD.
- Recherche : les noms qui commencent par la requête passent en premier
  (« dune » : « Dune (1984) » avant « 4K Dune.Part.Two… »).

## 0.7.3 — 2026-10-31

- Connexion : un œil à droite du mot de passe l'affiche ou le masque, pour
  vérifier la saisie.
- Direct : sous l'aperçu, les 4 programmes connus (en cours, suivant et les
  deux d'après) avec leurs heures de début et de fin. Aucune requête en plus :
  ce sont les 4 déjà demandés au fournisseur.

## 0.7.2 — 2026-10-31

- APK testeur : numéro de version `X.Y.Z-t` (Réglages › À propos, données
  d'usage, rapports de plantage) et fichier `hey-stream-X.Y.Z-t.apk`, pour le
  distinguer de l'APK public d'un coup d'œil.

## 0.7.1 — 2026-10-31

- Données des testeurs stockées en Europe de l'Ouest : le stockage du
  service de collecte devient `hey-stream-donnees-eu` (WEUR). Premier APK
  testeur.

## 0.7.0 — 2026-10-31

- APK testeur (`hey-stream-X.Y.Z-testeur.apk`), remis par le propriétaire
  et jamais publié sur GitHub : il envoie ses données d'usage au service de collecte du projet
  (Cloudflare). Réponses entières du panneau, nettoyées sur l'appareil ;
  écrans, recherches, lectures (démarrage, coupures, formats, décodeurs,
  débit), erreurs, plantages, mémoire et appareil. Numéro aléatoire par
  appareil, jamais d'URL ni d'identifiant. Envoi par lots, jamais pendant
  une lecture ; aucune requête en plus vers le fournisseur. L'APK public
  n'envoie rien. L'APK testeur se met à jour depuis le service de
  collecte, l'APK public depuis GitHub.
- Service de collecte (`cloudflare/collecte`) : conservation un an, coupure
  à distance.

## 0.6.0 — 2026-10-30

- Mise à jour intégrée : à l'ouverture (une fois par jour au plus), l'app
  cherche une nouvelle version parmi les releases GitHub du projet et la
  propose (« Installer » ou « Plus tard », reproposée le lendemain) ; aussi
  dans Réglages › À propos › Rechercher une mise à jour. L'APK est vérifié
  (taille, empreinte SHA-256) puis confié à l'installateur d'Android ;
  favoris, reprise et abonnement sont gardés. La première fois, Android
  demande l'autorisation d'installer des applications et une confirmation ;
  ensuite (Android 12+), « Installer » suffit.
- Publication automatique : fusionner une PR qui monte la version publie la
  release.
- Outils de test cachés (sept appuis sur la version, dans Réglages › À
  propos) : « Enregistrer les réponses du fournisseur » pendant l'usage
  normal, sans requête en plus. Réponses nettoyées avant d'être écrites
  (identifiant, mot de passe, adresse du serveur, hôtes et jetons des URL,
  adresses IP), listes réduites à 300 entrées réparties, nombre de réponses
  plafonné par type. « Partager l'enregistrement » réunit tout en un fichier.
- Politique de confidentialité : vérification des mises à jour auprès de
  GitHub et outils de test.

## 0.5.1 — 2026-10-30

- Licence précisée : GPL-3.0 ou ultérieure, avec une exception pour les
  magasins d'applications.
- Réglages › À propos : listes complètes des bibliothèques Android et Rust
  avec le texte de leurs licences ; lien vers les sources du décodeur FFmpeg.

## 0.5.0 — 2026-10-29

- Nouveau logo (piste C) : triangle blanc aux coins arrondis, ondes bleu
  électrique et rouge vif, sans dégradé. Icône d'app, icône thématique,
  bannière Google TV et logo de l'accueil.
- Le nom s'écrit « Hey Stream » partout, logo compris.

## 0.4.0 — 2026-10-29

- Rapports de plantage anonymes (GlitchTip) : plantages, gels (ANR),
  paniques du cœur Rust (fichier et ligne) et lectures abandonnées après
  toutes les reconnexions (code d'erreur seul). Ni compte, ni identifiant
  d'installation ; URL, noms de serveur et adresses IP retirés de tout texte.
- Interrupteur « Envoyer des rapports de plantage anonymes » dans
  Réglages › À propos, activé par défaut ; coupé, plus rien ne part.
- Seulement dans l'APK release construit par la CI (clé `GLITCHTIP_DSN`) ;
  piles d'appel lisibles (code réduit, noms conservés).

## 0.3.1 — 2026-10-29

- Le cœur retire la mention du français des noms (films, séries, chaînes)
  à l'enregistrement du catalogue, et garde le nom d'origine. Une seule
  règle pour Android et la future app Apple ; les catalogues déjà
  enregistrés sont nettoyés à l'ouverture.
- Recherche sur le nom nettoyé : « fr » ne renvoie plus tout le catalogue.

## 0.3.0 — 2026-10-29

Polissage de l'interface, même style sur TV, tablette et téléphone.

- Élément sélectionné sur fond gris doux partout (plus de pilule blanche
  au doigt).
- Focus à la télécommande : contour bleu électrique autour de l'élément,
  ombre douce, agrandissement de 5 %. Champ de saisie actif bordé de bleu.
- Rouge vif pour les alertes (icônes d'erreur, abonnement expiré ou
  expirant sous 7 jours) et le cœur des favoris (trait rouge).
- Barre du haut fondue dans le fond, plus basse (48 dp).
- Marges et espacements sur une grille commune ; coins de 12 dp partout ;
  lignes de chaînes plus compactes au doigt.
- Le français, implicite, n'est plus écrit dans les noms de films, séries et
  chaînes (« FR - », « |FR| », « FR -4K », « (2016) FR », « _fr ») ; les
  autres langues et la qualité restent (« EN - Dune », « 4K Gravity ») et
  les catégories gardent leur nom.
- Direct, Films, Séries au doigt : plus de barre du haut ni d'en-tête de
  grille. Retour et titre en tête des catégories (la catégorie ouverte et
  son nombre y restent), heure sur l'aperçu vidéo du direct.
- Fiche série : onglets de saisons sur tous les appareils ; épisodes sur
  toute la largeur.

## 0.2.0 — 2026-10-29

Retours du Xiaomi Pad 7.

- Fiche film : durée juste quand le fournisseur envoie « 01:49 » ou 109
  secondes pour 1 h 49.
- Note arrondie à une décimale, virgule française (« 7,3 »).
- Épisodes : vrai titre (sans le nom de la série ni « S01E01 »), résumé
  court ; bouton « Lecture S1 · E2 ».
- Titres des affiches sur 2 lignes au doigt ; icône film ou série quand
  l'affiche manque.
- Accueil sur tablette : tuiles plus basses, centrées.
- Lecteur : commandes sur un dégradé posé sur l'image.
- Consignes d'appui long uniformisées.
- Direct et recherche : numéro de chaîne retiré (le logo et le nom suffisent).
- Réglages › Catalogue : date de la dernière mise à jour (« aujourd’hui à
  14:02 », « hier à … », « le 27 octobre 2026 à … »).

## 0.1.0 — 2026-10-29

Première app Android (téléphone, tablette, TV) : connexion, Direct en trois
panneaux, Films et Séries, fiches, lecteur unique, recherche, réglages,
contrôle parental. Adaptée au téléphone (densité compacte, gestes du
lecteur, avance et recul cumulables).
