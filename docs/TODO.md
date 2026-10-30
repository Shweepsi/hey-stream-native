# À faire

Travail restant avant la version 1.0, dans l'ordre. Rayer (supprimer) une
ligne quand sa PR est fusionnée.

## Avant la 1.0

1. **Essai sur la Mi Box S 2** (propriétaire) : installer l'APK et vérifier
   les objectifs de `docs/MATERIEL.md` (démarrage < 2 s, liste < 500 ms,
   changement de chaîne < 1,5 s, < 300 Mo en lecture, 0 plantage sur 4 h).
2. **Identité** : nom « Hey Stream » et palette gardés (2026-10-29) ; logo C
   appliqué (site et app, 0.5.0) ; reste la fiche Play Store.
3. **Captures réelles** : le propriétaire lance la capture de l'APK debug et
   partage le fichier ; en faire les réponses simulées des tests
   (`core/tests/fixtures/`) et corriger ce qu'elles révèlent.
4. **1.0.0** : APK signé pour la publication ; comptes développeur à décider.

## Cœur (acceptables pour l'instant)

- Lire la réponse du panneau au fil de l'eau au lieu de la garder entière en
  mémoire (`client.rs`) : gros catalogues sur 1 Go libre.
- Recherche par index plein texte (FTS5) au lieu de `LIKE '%…%'`.
- Banc d'essai : temps d'enregistrement et de recherche sur 100 000 entrées.
- Télécharger direct, films et séries en parallèle.

## Accessibilité

- Le cœur des favoris se distingue par la couleur seule (vide, trait rouge) :
  ajouter une différence de forme ou un libellé.

## Dépôt

- Supprimer automatiquement les branches fusionnées (réglage GitHub).
- Étiquettes de version manquantes : v0.2.0 (93e4e9c), v0.3.0 (5d6f88a),
  v0.3.1 (e2ca218).
