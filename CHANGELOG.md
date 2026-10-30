# Journal des versions

Une entrée par version, la plus récente en haut. La version vient de
`[workspace.package] version` dans `Cargo.toml` (voir `docs/DECISIONS.md`,
« Versions »).

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
