# Spécification fonctionnelle — v1

Décidée le 2026-10-28 avec le propriétaire. Tout ce qui n'est pas listé ici
est **hors périmètre** : ne pas l'ajouter sans nouvelle décision.

## Principes

- Une seule mise en page, **en paysage partout** (téléphone compris), pensée
  d'abord pour la télécommande (haut / bas / gauche / droite / OK / Retour).
- **Un seul abonnement** Xtream par installation.
- **Un seul flux ouvert à la fois** (abonnements à 1 connexion) : l'aperçu et
  le plein écran sont le même lecteur.

## Premier lancement

Formulaire classique : serveur, identifiant, mot de passe. Vérification
immédiate (authentification), message clair en cas d'échec. Rien d'autre
(pas de QR code, pas de lien M3U collé).

## Accueil

- Trois grandes tuiles : **Direct**, **Films**, **Séries**.
- Rangée **Continuer** (reprise de lecture des films et épisodes).
- **Infos abonnement** : date d'expiration.
- Boutons **Recherche** et **Réglages**.

## Direct

- Trois panneaux : catégories | chaînes | **aperçu vidéo** + programme
  **en cours et suivant** (EPG court).
- Sélectionner une chaîne lance l'aperçu ; **OK** passe en plein écran ;
  **Retour** quitte le plein écran et revient à l'aperçu.
- Pas de zapping en plein écran, pas de guide en grille, pas de replay.
- Catégories spéciales en tête : **Favoris**, **Récents**.

## Films

- Catégories à gauche | **grille d'affiches** à droite.
- Fiche : affiche, **résumé, année, durée, note** (données du fournisseur).
- Lecture avec **reprise** à la dernière position.

## Séries

- Même présentation que les films ; fiche de série, saisons, épisodes.
- **Épisode suivant automatique** : compte à rebours de 10 s, annulable.

## Lecteur (films et épisodes)

- **Avance / recul rapides** (±10 s), barre de progression.
- Choix de la **piste audio** et des **sous-titres** intégrés au flux.

## Confort

- **Favoris** (chaînes, films, séries), **Récemment regardés**,
  **Reprise de lecture**, **Recherche** par nom dans les trois sections.

## Réglages

- **Masquer des catégories** (pas de réordonnancement).
- **Contrôle parental** : PIN à 4 chiffres ; les catégories **adultes sont
  détectées automatiquement** (marquage du fournisseur ou nom contenant
  « XXX », « Adult », « +18 »…) et verrouillées par défaut.
- Langue, mise à jour manuelle du catalogue, déconnexion.

## Catalogue

- Cache local (SQLite) ; l'app s'ouvre toujours sur le cache.
- **Rafraîchissement automatique une fois par jour** en arrière-plan, plus un
  bouton manuel.
- Une réponse vide ne remplace jamais un catalogue connu ; tri des catégories
  côté app.

## Stabilité

- Reconnexion automatique du flux en cas de coupure.
- **Rapports de plantage anonymes** (sans identifiants ni URL), mentionnés
  dans la politique de confidentialité.

## Hors périmètre v1

Multi-écran · plusieurs abonnements · replay · guide en grille · Chromecast /
AirPlay · lecteur externe · TMDB · casting et bande-annonce · format d'image ·
M3U / Stalker · QR code · réordonnancement des catégories · compte / synchro.
