# Design — Hey Stream

Choix du propriétaire (2026-10-28). Maquettes de référence :
https://claude.ai/artifact/HA1iuddTLtgRvpm6cakDqY (privées).
Ne pas s'en écarter sans nouvelle décision. En cas de désaccord, `SPEC_V1.md`
décide du contenu des écrans, ce document de leur apparence.

## Principes

- **Moderne et sobre** (esprit Tesla, ChatGPT, Claude) : gris, blanc, noir.
  La couleur ne sert qu'à l'identité et au focus.
- **Télécommande d'abord** : tout se fait avec haut / bas / gauche / droite / OK
  / Retour, tout à deux clics. Une seule mise en page, en paysage partout,
  téléphone compris ; le tactile (Xiaomi Pad 7) suit la même organisation.
- **Léger** : rien qui empêche 60 i/s sur la Mi Box S (voir `MATERIEL.md`) :
  pas de flou, pas d'ombres portées, pas de dégradés animés.
- **Pas d'apparence Smarters** : on reprend l'organisation (tuiles, 3
  panneaux), jamais ses couleurs, icônes, textes ou logo (`DECISIONS.md`).
- **N'afficher que ce que le cœur fournit** : aucune donnée inventée, aucune
  requête réseau pour décorer une liste.

## Logo

**Piste C** (2026-10-29, affinage de la piste A) : **triangle lecture + deux
ondes**. Dessins de l'app générés par `scripts/brand_assets.py`.

- Triangle blanc `#F4F4F5`, coins arrondis, sans dégradé.
- Onde intérieure bleu électrique, onde extérieure rouge vif, traits épais
  et arrondis.
- Mot « Hey Stream » en Inter Bold, approche −0,02 em, à droite du symbole.
- Le symbole seul sert d'icône d'app (fond `#121214`).

## Couleurs

| Rôle | Sombre | Clair |
|---|---|---|
| Fond | `#121214` | `#FAFAFA` |
| Surface (tuiles, panneaux) | `#1C1C1F` | `#FFFFFF` |
| Surface 2 (puces, champs) | `#2A2A2E` | `#E9E9EC` |
| Séparateurs | `#26262A` | `#E4E4E7` |
| Texte | `#F4F4F5` | `#18181B` |
| Texte secondaire | `#A1A1AA` | `#52525B` |
| Bleu électrique | `#1F6BFF` | `#1F6BFF` |
| Rouge vif | `#FF2E3B` | `#FF2E3B` |

- **Bleu et rouge viennent du logo** et ne servent d'accents qu'ici
  (décidé le 2026-10-29) : **bleu** pour le contour de focus, le champ actif
  et la sélection de texte ; **rouge** pour les alertes (icône d'erreur,
  abonnement expiré ou expirant sous 7 jours) et le cœur des favoris (trait
  rouge, jamais plein). Nulle part ailleurs.
- Contraste du texte ≥ 4,5:1 sur son fond ; tout indicateur d'état (focus,
  sélection) ≥ 3:1 avec ce qui l'entoure.
- **Sur une image ou une vidéo** (badge « EN DIRECT », barre de progression
  d'une affiche), les couleurs ne suivent pas le thème : badge fond `#F4F4F5`
  et texte `#121214` ; barre `#F4F4F5` sur un rail noir à 60 % d'opacité.
  Ainsi ils restent visibles sur une image claire comme sombre.

## Thème

- **Toujours sombre** (décidé le 2026-10-29), sur TV comme au doigt : on
  regarde des vidéos, souvent le soir, et le clair éblouit dans le noir. La
  palette claire reste décrite ci-dessus pour une éventuelle option.

## Unités et typographie

Les maquettes sont dessinées sur un écran de 1280 × 720. Dans les interfaces :

- **Android TV** (Compose for TV, écran de 960 × 540 dp) : tailles en dp / sp.
- **Apple TV** (tvOS, écran de 1920 × 1080 pt) : **deux fois** les valeurs
  Android TV, en pt.
- **Tablette et téléphone** : dp (Android) ou pt (Apple), valeurs ci-dessous.

Police **Inter** (licence OFL), embarquée dans l'app. Titres 600–700, texte
400–500, chiffres tabulaires pour heures et numéros.

| Usage | Android TV (sp) | tvOS (pt) | Tablette / téléphone |
|---|---|---|---|
| Titre d'écran | 22–36 | 44–72 | 22–28 |
| Texte courant | 14–16 | 28–32 | 15–17 |
| Petit texte (minimum) | 12 | 24 | 13 |

Exceptions : les lignes de catégorie du Direct, des Films et des Séries sont à **13 sp** à la
TV (0.9.8, listes de noms du fournisseur souvent longs) ; les heures suivent le réglage 12/24 h
de l'appareil, pas la langue de l'app.

## Formes et icônes

- Coins arrondis (tuiles, affiches, boutons, panneaux) : **12** dp / pt sur
  tablette et téléphone, **8** dp sur Android TV, **16** pt sur tvOS ; la
  moitié pour les petits badges.
- Icônes **Lucide** (licence ISC), trait fin 1,5–1,75.
- Pas de carte à bordure latérale colorée, pas d'emoji.

## Focus et sélection

Style « gris doux », décidé le 2026-10-29 (maquettes A, B, C : A retenue).

- **Télécommande** : contour **bleu `#1F6BFF`** de 2 dp tracé 2 dp autour de
  l'élément (lisible aussi sur un bouton blanc), ombre douce et
  agrandissement **×1,05** ; les lignes pleine largeur ne grossissent pas.
  Rien ne bouge autour.
- **Élément sélectionné** (catégorie, chaîne en aperçu, saison, réglage),
  partout : fond **Surface 2**, texte blanc demi-gras. Le blanc plein est
  réservé au bouton principal (« Lecture »).
- Barre du haut (au doigt) : 48 dp, sur le fond de l'écran, retour sans fond.
  Direct, Films et Séries n'en ont pas : retour et titre en tête de la
  colonne des catégories (maquette C, 2026-10-29).
- Grille commune : marges d'écran de 16 dp (téléphone), 24 dp (tablette),
  48 × 27 dp (TV) ; espacements de 8, 16, 24 ou 32 dp ; coins de 12 dp
  partout.
- Zones tactiles ≥ 48 dp sur tablette Android, ≥ 40 dp sur téléphone / 44 pt
  (Apple).
- Noms de films, séries et chaînes affichés sans la mention du français
  (« FR - », « |FR| », « _fr »), implicite dans l'app, ni la mention de
  qualité en tête (« 4K », « 4KL », « UHD », « FHD », « HD »), sans badge à la
  place (décidé le 2026-11-02) ; les autres langues restent écrites, les
  catégories gardent leur nom. Films et séries seulement (2026-11-03) :
  espaces en trop, noms de fichiers de publication et mentions techniques en
  fin de nom (« FHD », « HDR », « x265 »…) nettoyés, sauf MULTI, VOSTFR et
  VOST ; sur la fiche, l'année déjà montrée par la ligne d'infos quitte le
  titre. Les chaînes gardent leurs mentions.

## Allègement (2026-11-03)

Principe : une information s'écrit **une fois**, et une aide n'occupe pas
l'écran en permanence.

- **Catégories** : le nom du fournisseur, tel quel (inchangé), sans compteur à
  droite. Le nombre de titres de la catégorie ouverte s'écrit seulement dans
  l'en-tête de la grille (Films, Séries : « ACTION  579 films ») ; le Direct
  n'affiche aucun nombre de chaînes.
- **Pas d'aide permanente** : « Maintenez OK sur une chaîne… » (sous l'aperçu
  du Direct) ne se montre que tant qu'il n'y a aucun favori ; « Maintenez OK ou
  appuyez sur MENU… » (épisodes) que tant qu'aucun épisode n'est vu ni commencé.
  Une catégorie Favoris vide n'est pas listée (rien ne l'explique là).
- **Épisode sans titre** : « Épisode 2 » sans numéro à côté quand aucun épisode
  de la liste n'a de titre ; sinon le numéro reste en tête de toutes les lignes
  (alignement).
- **Options qui se comprennent seules** : « Doublée, sans sous-titres » plutôt
  qu'un paragraphe d'explication.
- **Réglages** : une phrase d'introduction par volet au plus, sans exemples ni
  rappels que la fenêtre de confirmation ou le message d'erreur donnent au
  bon moment (mise en garde de la déconnexion, blocage après 5 codes faux). La
  version du cœur n'est écrite que si elle diffère de celle de l'app. Les
  lignes qui s'ouvrent (crédits) montrent un chevron, pas un libellé répété.

## Animations

Niveau moyen :

- Focus : agrandissement et contour en 120–150 ms, courbe « ease-out ».
- Changement d'écran : fondu court 150–200 ms.
- **Aperçu → plein écran** n'est pas un changement d'écran : le **même
  lecteur** s'agrandit (aucun fondu entre deux lecteurs, jamais deux flux
  ouverts, même un instant ; voir `SPEC_V1.md`).
- Rien d'autre (pas de parallaxe, pas de glissement d'affiches, pas de flou).
  Toute animation qui fait tomber la Mi Box sous 60 i/s est retirée.

## Écrans

### Accueil

- En haut : logo à gauche, heure et date à droite.
- Trois grandes tuiles **Direct / Films / Séries** (icône, titre, sous-titre).
- En bas : **Rechercher** et **Réglages** ; à droite, l'abonnement (connu hors
  ligne) :
  - « Abonnement valable jusqu’au 14 mars 2027 » ;
  - « Abonnement sans date de fin » (compte illimité) ;
  - « Abonnement expiré le 14 mars 2027 », en Texte gras, une fois la date
    passée.
- Pas de rangée « Continuer » ni de bouton Favoris sur l'accueil.

### Direct — 3 panneaux

1. Catégories, sans compteur ; **Favoris** et **Récents** en tête.
2. Chaînes : logo, nom, et le programme en cours **seulement s'il est déjà en
   cache** ; sinon la ligne n'a pas de deuxième ligne de texte. Jamais de
   requête pour remplir la liste. Pas de numéro de chaîne dans les listes
   (retiré en 0.7.x) ; le bandeau du plein écran garde celui du fournisseur,
   s'il en donne un.
3. Aperçu 16:9 avec badge « EN DIRECT », puis le programme **en cours** (avec
   sa progression), le **suivant** et les deux d'après, chacun avec « début –
   fin », demandés à la sélection de la chaîne. Faute de place (téléphone),
   les dernières lignes disparaissent plutôt que d'être coupées.

OK sur une chaîne déjà en aperçu → plein écran. Mêmes trois panneaux sur
téléphone, plus étroits.

### Films / Séries

- Catégories à gauche : **Continuer** (titres commencés ; pour les séries, une
  entrée par série), **Favoris**, puis celles du fournisseur.
- Grille d'affiches 2:3 **avec le titre dessous, sur deux lignes** (6
  colonnes sur TV ; deux lignes aussi à la TV depuis le 2026-11-02).
- **Barre de progression** sur toute affiche commencée, dans toutes les
  catégories (série : son dernier épisode commencé).
- **Coche « vu »** sur l'affiche d'un film vu ou d'une série entièrement vue :
  pastille ronde en haut à droite, icône Lucide `check` en `#F4F4F5` sur fond
  noir à 60 % (hors thème, comme la barre). Pas de barre sur un titre vu
  sauf s'il est en cours de revisionnage.
- OK ouvre la **fiche** (jamais la lecture directe).

### Fiche film / série

- Affiche à gauche ; titre, **année**, **durée** (films), **note**, résumé
  (4 lignes au plus). Seulement les champs fournis : un champ absent
  disparaît, sans tiret ni « inconnu ».
- « Vu jusqu’à … » et la barre de progression si le titre est commencé.
- Boutons : **Reprendre** (ou **Lecture**), **Depuis le début**, Favori,
  **Marquer comme vu / non vu** (icône `check`, libellé lu par
  l'accessibilité). Le premier bouton a le focus à l'ouverture.
- Série : saisons puis épisodes sous les boutons.

## Textes

L'app est multilingue (français et anglais d'abord, voir `DECISIONS.md`) :
aucun texte en dur, tout passe par les ressources de traduction de chaque
plateforme. En français : vouvoiement, apostrophe typographique ’ et
guillemets « ». Les interfaces traduisent les types d'erreur du cœur (le cœur
ne produit aucun texte affiché).
