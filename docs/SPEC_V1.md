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

- Trois grandes tuiles : **Direct**, **Films**, **Séries** ; heure et date.
- **Infos abonnement** : date d'expiration (celle de la dernière connexion
  réussie, affichée aussi hors ligne) ; « sans date de fin » pour un compte
  illimité, « expiré le … » une fois la date passée.
- Boutons **Recherche** et **Réglages**.
- Pas de rangée « Continuer » sur l'accueil (décision du 2026-10-28) : la
  reprise est une catégorie de Films et de Séries.

## Direct

- Trois panneaux : catégories | chaînes | **aperçu vidéo** + programme
  **en cours et suivant** (EPG court, demandé à la sélection d'une chaîne,
  mis en cache, jamais plus d'une fois toutes les 15 min par chaîne).
- Chaque ligne de chaîne : **numéro** du fournisseur, logo, nom, et le
  programme en cours **seulement s'il est déjà en cache** (jamais de requête
  pour remplir la liste).
- Sélectionner une chaîne lance l'aperçu ; **OK** passe en plein écran ;
  **Retour** quitte le plein écran et revient à l'aperçu.
- Pas de zapping en plein écran, pas de guide en grille, pas de replay.
- Catégories spéciales en tête : **Favoris**, **Récents**.

## Films

- Catégories à gauche | **grille d'affiches** à droite.
- Catégories spéciales en tête : **Continuer** (films commencés), **Favoris**.
- **Barre de progression** sur l'affiche d'un film commencé.
- Fiche : affiche, **résumé, année, durée, note** (données du fournisseur).
- Lecture avec **reprise** à la dernière position.

## Séries

- Même présentation que les films ; fiche de série, saisons, épisodes.
- **Continuer** liste les séries commencées (une par série, son dernier
  épisode) ; la barre de l'affiche montre la progression de cet épisode.
- **Épisode suivant automatique** : compte à rebours de 10 s, annulable.

## Lecteur (films et épisodes)

- **Avance / recul rapides** (±10 s), barre de progression.
- Choix de la **piste audio** et des **sous-titres** intégrés au flux.

## Confort

- **Favoris** (chaînes, films, séries), **Récemment regardés**,
  **Reprise de lecture**, **Recherche** par nom dans les trois sections.
- **Vus** : un film ou un épisode est marqué vu en fin de lecture (≥ 95 %
  ou moins de 2 min restantes) ou à la main depuis la fiche (« Marquer comme
  vu / non vu ») ; vu, il quitte « Continuer » ; le revoir garde la marque.
  Une série est vue quand tous ses épisodes connus le sont (liste des
  épisodes lue à la dernière ouverture de sa fiche). Un contenu court n'est
  pas « vu » avant d'avoir passé la moitié.

## Réglages

- **Masquer des catégories** (pas de réordonnancement).
- **Contrôle parental** : PIN à 4 chiffres ; les catégories **adultes sont
  détectées automatiquement** (marquage du fournisseur ou nom contenant
  « XXX », « Adult », « +18 »…) et verrouillées par défaut. Verrouillées,
  elles restent listées mais leur contenu n'apparaît nulle part (listes,
  recherche, favoris, récents, « Continuer »). Tant qu'aucun code n'est
  défini, ouvrir une catégorie adulte propose d'en créer un. Le
  déverrouillage vaut pour la session ; 5 codes faux bloquent 30 s, au
  déverrouillage comme au changement de code, même après un redémarrage.
  Un contenu rangé dans une catégorie inconnue reste verrouillé ; un contenu
  sans aucune catégorie reste visible, sauf s'il est marqué adulte.
- Les catégories masquées quittent la navigation et la recherche ; leurs
  favoris et récents restent visibles.
- Langue, mise à jour manuelle du catalogue, déconnexion.
- Les données (catalogue, favoris, récents, reprise, vus, code PIN,
  catégories masquées) appartiennent à l'identifiant : se connecter avec un autre
  identifiant les efface, le même identifiant sur une autre adresse du
  fournisseur les garde et recharge seulement le catalogue. Rien n'est
  effacé avant une connexion réussie (une faute de frappe n'efface rien).

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
