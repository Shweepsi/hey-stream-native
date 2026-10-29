# Journal des versions

Une entrée par version, la plus récente en haut. La version vient de
`[workspace.package] version` dans `Cargo.toml` (voir `docs/DECISIONS.md`,
« Versions »).

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

## 0.1.0 — 2026-10-29

Première app Android (téléphone, tablette, TV) : connexion, Direct en trois
panneaux, Films et Séries, fiches, lecteur unique, recherche, réglages,
contrôle parental. Adaptée au téléphone (densité compacte, gestes du
lecteur, avance et recul cumulables).
