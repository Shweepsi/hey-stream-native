# À faire

Travail restant avant la version 1.0, dans l'ordre. Rayer (supprimer) une
ligne quand sa PR est fusionnée.

## Avant la 1.0

1. **Essai sur la Mi Box S 2** (propriétaire) : installer l'APK et vérifier
   les objectifs de `docs/MATERIEL.md` (démarrage < 2 s, liste < 500 ms,
   changement de chaîne < 1,5 s, < 300 Mo en lecture, 0 plantage sur 4 h).
2. **Rapports de plantage anonymes** (GlitchTip, voir `docs/DECISIONS.md`) :
   ni identifiants ni URL de flux.
3. **Politique de confidentialité** sur GitHub Pages, mentionnant les
   rapports de plantage.
4. **Identité** : logo et palette définitifs (maquette d'abord) ; réévaluer
   le nom « Hey Stream » avant publication.
5. **1.0.0** : APK signé pour la publication ; comptes développeur à décider.

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
