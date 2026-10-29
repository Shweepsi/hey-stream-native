# Journal des versions

Une entrée par version, la plus récente en haut. La version vient de
`[workspace.package] version` dans `Cargo.toml` (voir `docs/DECISIONS.md`,
« Versions »).

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
- Noms sans « FR - » ni « |FR| » (films, séries, chaînes) ; les catégories
  gardent le leur.
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
