# À faire

Travail restant avant la version 1.0, dans l'ordre. Rayer (supprimer) une
ligne quand sa PR est fusionnée.

## Avant la 1.0

1. **Essai sur la Mi Box S 2** (propriétaire) : installer l'APK et vérifier
   les objectifs de `docs/MATERIEL.md` (démarrage < 2 s, liste < 500 ms,
   changement de chaîne < 1,5 s, < 300 Mo en lecture, 0 plantage sur 4 h).
2. **Identité** : nom « Hey Stream » et palette gardés (2026-10-29) ; logo C
   appliqué (site et app, 0.5.0) ; reste la fiche Play Store.
3. **Réponses réelles** : le propriétaire active l'enregistrement (outils de
   test cachés, sept appuis sur la version), utilise l'app, puis partage le
   fichier ; en faire les réponses simulées des tests
   (`core/tests/fixtures/`) et corriger ce qu'elles révèlent.
4. **1.0.0** : APK signé pour la publication ; comptes développeur à
   décider ; sort des outils de test et de la mise à jour intégrée
   (interdite par le Play Store) dans la version des magasins.

## Cœur (acceptables pour l'instant)

- Lire la réponse du panneau au fil de l'eau au lieu de la garder entière en
  mémoire (`client.rs`) : gros catalogues sur 1 Go libre. Aujourd'hui, le pic
  est la réponse entière plus toutes les entrées décodées. Trois points :
  - enregistrer dans SQLite par lots pendant la lecture : `parse_list`
    (`lenient.rs`) renvoie la liste complète avant tout enregistrement, donc
    lire en flux sans enregistrer au fil de l'eau ne baisserait pas le pic ;
  - deux chemins de lecture : liste (`SeqAccess`) et objet indexé
    `{"0": {...}}` (`MapAccess`), aujourd'hui lu dans une `BTreeMap` entière ;
    garder la détection de `user_info` (identifiants refusés) ;
  - corriger le Latin-1 morceau par morceau (`text`, `lenient.rs`), sans
    couper un caractère UTF-8 entre deux morceaux.
- Compter les entrées illisibles ignorées (`lenient.rs`, `epg.rs`) et
  renvoyer ce nombre avec chaque liste, jusqu'à l'app par UniFFI : un
  catalogue amputé d'une partie de ses entrées doit laisser une trace dans les
  rapports anonymes (un nombre, jamais le contenu des entrées).
- Recherche par index plein texte (FTS5) au lieu de `LIKE '%…%'`. Le découpage
  par défaut ne trouve que des mots entiers, alors que `LIKE` trouve n'importe
  quel morceau (« venge » → « Avengers », noms collés aux décorations comme
  `FRRetour`). Deux options à comparer :
  - index de préfixes : petit et rapide, mais seulement en début de mot ;
  - découpage en trigrammes : garde le comportement actuel, index plus gros,
    et une requête de moins de 3 caractères reste un parcours complet.
- Banc d'essai : temps d'enregistrement et de recherche sur 100 000 entrées,
  plus la taille de l'index pour chaque option FTS5 ci-dessus (box de 2 Go).
- Télécharger direct, films et séries en parallèle.
- Films et Séries : ne compter que la catégorie ouverte (en-tête de la grille).
  Le chargement compte encore chaque catégorie du fournisseur (une requête
  `COUNT` chacune) alors que seul le nombre de la catégorie ouverte s'affiche ;
  le lire à part, ou prendre le total de la pagination.

## Accessibilité

- Le cœur des favoris se distingue par la couleur seule (vide, trait rouge) :
  ajouter une différence de forme ou un libellé.

## Dépôt

- Supprimer automatiquement les branches fusionnées (réglage GitHub).
- Étiquettes de version manquantes : v0.2.0 (93e4e9c), v0.3.0 (5d6f88a),
  v0.3.1 (e2ca218).
