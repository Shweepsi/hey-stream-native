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
  **en cours, suivant et les deux d'après**, chacun avec ses heures de début
  et de fin (EPG court de 4 programmes, demandé à la sélection d'une chaîne,
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
  Quand un épisode est vu jusqu'au bout, **Continuer** propose le premier
  épisode suivant pas encore vu (saison suivante comprise), sans barre ; la
  fiche affiche « Lecture S1 · E6 ». À la fin de la série, elle en sort.
- **Épisode suivant automatique** : compte à rebours de 10 s, annulable.

## Lecteur (films et épisodes)

- **Avance / recul rapides** (+30 s / −10 s), appuis rapprochés cumulés en un
  seul saut ; barre de progression. Au doigt (décidé le 2026-10-29) : double
  tap sur un côté de la vidéo pour avancer ou reculer, glissement vertical à
  droite pour le volume et à gauche pour la luminosité (rendue au système en
  quittant le lecteur).
- Choix de la **piste audio** et des **sous-titres** intégrés au flux. Au
  lancement, ils suivent le réglage « Langue des films et épisodes »
  (Automatique, Doublée, Version originale sous-titrée) ; un choix fait ensuite
  dans le lecteur l'emporte. Pas pour le direct.
- **Infos du flux** (appui long sur OK à la télécommande, 0.9.11) : codec,
  résolution, images par seconde et plage dynamique (SDR, HDR10, HLG, Dolby
  Vision), débit vidéo, décodeur vidéo, audio (codec, voies, fréquence) et son
  décodeur, débit réseau mesuré, images perdues. Aucune adresse ni
  identifiant ; Retour ou un nouvel appui long le ferme. Un appui court sur OK
  agit au relâchement.

## Confort

- **Favoris** (chaînes, films, séries), **Récemment regardés**,
  **Reprise de lecture**, **Recherche** par nom dans les trois sections.
- **Vus** : un film ou un épisode est marqué vu en fin de lecture (≥ 95 %
  ou moins de 2 min restantes) ou à la main depuis la fiche (« Marquer comme
  vu / non vu ») ; vu, il quitte « Continuer » ; le revoir garde la marque.
  Marqué vu à la main, il garde sa position : sans barre sur l'affiche, mais
  la fiche propose toujours « Reprendre » ; « non vu » le ramène dans
  « Continuer ».
  Une série est vue quand tous ses épisodes connus le sont (liste des
  épisodes lue à la dernière ouverture de sa fiche). Un contenu court n'est
  pas « vu » avant d'avoir passé la moitié.

- **Dernière catégorie** : le Direct, les Films et les Séries s'ouvrent sur la
  dernière catégorie ouverte si elle s'ouvre encore (elle existe, n'est ni
  masquée ni verrouillée, et Favoris, Récents ou « Continuer » ne sont pas
  vides) ; sinon, l'ouverture habituelle. Aux Films et aux Séries, elle passe
  avant « Continuer ».

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
  Le code se change ou se supprime dans les réglages, en donnant le code
  actuel dès la première étape ; sans code, les catégories adultes sont
  verrouillées comme au premier jour.
  Un contenu rangé dans une catégorie inconnue reste verrouillé ; un contenu
  sans aucune catégorie reste visible, sauf s'il est marqué adulte.
- Les catégories masquées quittent la navigation et la recherche ; leurs
  favoris et récents restent visibles.
- Langue de l'app et langue des films et épisodes, mise à jour manuelle du
  catalogue, déconnexion.
- Les données (catalogue, favoris, récents, reprise, vus, code PIN,
  catégories masquées, langue des films, dernière catégorie) appartiennent à l'identifiant : se connecter avec un autre
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

- Reconnexion automatique du flux en cas de coupure (1, 2, 4, 8 s…, six
  essais), relancée aussitôt au retour du réseau. Un refus du fournisseur ne
  se relance pas : abonnement utilisé ailleurs (509), compte refusé (401,
  403), flux introuvable (404, après un seul essai), format non pris en charge
  par l'appareil. Chaque cas a son message ; sans réseau, « Pas de connexion
  Internet ».
- Pas de rafraîchissement automatique du catalogue pendant une lecture.
- **Rapports de plantage anonymes** (sans identifiants ni URL), mentionnés
  dans la politique de confidentialité.

## Mises à jour (2026-10-30)

- L'app cherche une nouvelle version parmi les releases GitHub du projet à
  l'ouverture (une fois par jour au plus) et la propose, de nouveau le
  lendemain après « Plus tard » ; aussi à la demande dans Réglages › À
  propos. Un interrupteur « Rechercher les mises à jour automatiquement »
  (Réglages › À propos, activé par défaut) coupe la recherche à l'ouverture ;
  la recherche à la demande reste toujours possible. Données gardées ;
  confirmation d'Android la première fois seulement (Android 12+).
- Parcours d'installation : le bouton dit l'étape (« Télécharger et
  installer », « Téléchargement… 42 % », « Confirmez dans la fenêtre
  d'Android ») ; pendant la confirmation, un appui rouvre la même fenêtre ; une
  confirmation annulée se reprend avec l'APK déjà téléchargé. L'autorisation « applis
  inconnues » ferme souvent l'app : la version voulue est retenue et
  l'installation reprend à la réouverture, sans appui (« Reprise de la mise à
  jour… ») ; si l'autorisation reste sans effet (box à profil professionnel),
  l'aide renvoie vers le fichier APK de la page GitHub.
- Outils de test cachés (sept appuis sur la version) : enregistrement des
  réponses du fournisseur pendant l'usage, pour les tests automatiques
  (`DECISIONS.md`, « Panneau de référence »).

## Hors périmètre v1

Multi-écran · plusieurs abonnements · replay · guide en grille · Chromecast /
AirPlay · lecteur externe · TMDB · casting et bande-annonce · format d'image ·
M3U / Stalker · QR code · réordonnancement des catégories · compte / synchro.
