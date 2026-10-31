# Journal des versions

Une entrée par version, la plus récente en haut. La version vient de
`[workspace.package] version` dans `Cargo.toml` (voir `docs/DECISIONS.md`,
« Versions »).

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
