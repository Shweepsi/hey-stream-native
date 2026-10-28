# Design — Hey Stream

Choix du propriétaire (2026-10-28). Maquettes de référence :
https://claude.ai/artifact/HA1iuddTLtgRvpm6cakDqY (privées).
Ne pas s'en écarter sans nouvelle décision.

## Principes

- **Moderne et sobre** (esprit Tesla, ChatGPT, Claude) : gris, blanc, noir.
  La couleur ne sert qu'à l'identité et au focus.
- **Télécommande d'abord** : tout se fait avec haut / bas / gauche / droite / OK
  / Retour, tout à deux clics. Le tactile (Xiaomi Pad 7) suit la même
  organisation.
- **Léger** : rien qui empêche 60 i/s sur la Mi Box S (voir `MATERIEL.md`) :
  pas de flou, pas d'ombres portées, pas de dégradés animés.
- **Pas d'apparence Smarters** : on reprend l'organisation (tuiles, 3
  panneaux), jamais ses couleurs, icônes, textes ou logo (`DECISIONS.md`).

## Logo

Piste A retenue : **triangle lecture + deux ondes**.

- Triangle : dégradé linéaire bleu électrique → rouge vif (haut gauche → bas
  droite).
- Onde intérieure bleu électrique, onde extérieure rouge vif, traits arrondis.
- Mot « hey stream » en Inter Bold, minuscules, approche −0,02 em, à droite
  du symbole.
- Le symbole seul sert d'icône d'app (fond `#121214`).

## Couleurs

| Rôle | Sombre | Clair |
|---|---|---|
| Fond | `#121214` | `#FAFAFA` |
| Surface (tuiles, panneaux) | `#1C1C1F` | `#FFFFFF` |
| Surface 2 (sélection, puces) | `#2A2A2E` | `#E9E9EC` |
| Séparateurs | `#26262A` | `#E4E4E7` |
| Texte | `#F4F4F5` | `#18181B` |
| Texte secondaire | `#A1A1AA` | `#52525B` |
| Bleu électrique | `#1F6BFF` | `#1F6BFF` |
| Rouge vif | `#FF2E3B` | `#FF2E3B` |

- **Bleu et rouge sont réservés au logo.** Seule exception : le **bleu** du
  contour de focus.
- Badge « EN DIRECT », barres de progression : blanc (sombre) ou noir
  (clair), jamais rouge.
- Contraste du texte ≥ 4,5:1 sur son fond.

## Thème

- Suit le réglage du système (clair / sombre) sur tablette et téléphone.
- **Toujours sombre sur TV** (Android TV, Apple TV) : le clair éblouit dans le
  noir.

## Typographie

- **Inter** (licence OFL), embarquée dans l'app.
- Titres 600–700, texte 400–500, chiffres tabulaires pour heures et numéros.
- Tailles TV (vues à 3 m) : titre d'écran 28–48 px, texte 16–19 px, jamais
  moins de 14 px. Tablette : 14–22 px.

## Formes et icônes

- Coins arrondis **12 px** (tuiles, affiches, boutons, panneaux) ; 6 px pour
  les petits badges.
- Icônes **Lucide** (licence ISC), trait fin 1,5–1,75.
- Pas de carte à bordure latérale colorée, pas d'emoji.

## Focus (télécommande)

- Contour **bleu `#1F6BFF` de 3 px** + agrandissement **×1,04** (tuiles,
  boutons) ou **×1,06** (affiches).
- La place du contour est réservée (bordure transparente au repos) : rien ne
  bouge autour.
- Au doigt (tablette, téléphone), pas de contour : la sélection est un fond
  Surface 2.
- Zones tactiles ≥ 44 px.

## Animations

Niveau moyen :

- Focus : agrandissement et contour en 120–150 ms, courbe « ease-out ».
- Changement d'écran : fondu court 150–200 ms.
- Rien d'autre (pas de parallaxe, pas de glissement d'affiches, pas de flou).
  Toute animation qui fait tomber la Mi Box sous 60 i/s est retirée.

## Écrans

### Accueil

- En haut : logo à gauche, heure et date à droite.
- Trois grandes tuiles **Direct / Films / Séries** (icône, titre, sous-titre).
- En bas : Favoris, Rechercher, Réglages ; à droite, « Abonnement valable
  jusqu’au … » (connu hors ligne).

### Direct — 3 panneaux (TV et tablette)

1. Catégories (avec nombre de chaînes), Favoris en tête.
2. Chaînes : numéro, logo, nom, programme en cours.
3. Aperçu 16:9 avec badge « EN DIRECT », programme en cours avec progression,
   puis les 3 suivants.

OK sur une chaîne déjà en aperçu → plein écran. Téléphone : un panneau à la
fois.

### Films / Séries

- Catégories à gauche (Continuer, Favoris, puis celles du fournisseur).
- Grille d'affiches 2:3 **avec le titre dessous** (6 colonnes sur TV).
- Barre de progression sur l'affiche d'un titre commencé.
- OK ouvre la **fiche** (jamais la lecture directe).

### Fiche film / série

- Affiche à gauche ; titre, année, durée, genre, âge, résumé (4 lignes max).
- Progression si commencé.
- Boutons : **Reprendre** (ou Lecture), **Depuis le début**, Favori. Le premier
  bouton a le focus à l'ouverture.
- Série : saisons puis épisodes sous les boutons.

## Textes

Français, vouvoiement, apostrophe typographique ’ et guillemets « ». Les
interfaces traduisent les types d'erreur du cœur (le cœur ne produit aucun
texte affiché).
