# Décisions produit et techniques

Décidé le 2026-10-28 par le propriétaire du projet. Ne pas revenir dessus sans
nouvelle décision.

## Produit

- **Public** : grand public, y compris des personnes peu à l'aise avec la technique.
- **Appareils** : téléphone, tablette et TV, Android et Apple, dès la première version.
- **Sources** : Xtream Codes uniquement (pas de M3U, Stalker ni EPG externe en v1).
- **v1** : accueil en trois tuiles **Direct / Films / Séries**, puis listes et lecture.
- **Lecteur intégré** : la vidéo se lit dans l'app (zapping rapide). Pas de
  renvoi vers un lecteur externe en v1.
- **Tout en local** : pas de compte, pas de serveur, pas de synchronisation.
- **Pas de multi-écran**, ni en v1 ni plus tard sans nouvelle décision.
- **Pas de TMDB** : on affiche les données du fournisseur.
- **Gratuit, open source (GPL-3.0)**, sans publicité ni version payante.
- **Langues** : multilingue ouvert — français et anglais d'abord, traductions
  communautaires ensuite (Weblate).
- **Diffusion** : Google Play et App Store (dont Apple TV).
- **Nom** : hey-stream.

## Ce que l'on garde de Smarters Pro — et comment, légalement

Ce que les utilisateurs aimaient : **une interface évidente et la stabilité**.
On reproduit ces *qualités*, pas l'apparence :

- On reprend l'organisation (tuiles Direct / Films / Séries, trois panneaux en
  direct sur tablette et TV, tout à deux clics) — ce sont des idées et des
  conventions communes, non protégées.
- On ne reprend **ni le nom, ni le logo, ni les icônes, images, couleurs ou
  textes** de Smarters, et **aucun code** : pas de décompilation de l'APK.
  L'abandon d'une app ne lève pas son droit d'auteur.
- L'étude d'un logiciel par observation de son fonctionnement (art. L122-6-1 III
  CPI), par ex. la capture de ses requêtes réseau sur son propre abonnement,
  est permise et peut nourrir la spécification.

## Technique

- **Cœur Rust** pour toute la logique ; **interfaces natives** : Jetpack Compose
  (+ Compose for TV) sur Android, SwiftUI sur Apple. Une interface 100 % Rust
  a été écartée : ni tvOS, ni le focus télécommande Android TV ne sont mûrs.
- **Lecteurs** : Media3/ExoPlayer (décodage matériel) sur Android, AVPlayer
  (+ VLCKit pour MKV/TS si besoin) sur Apple.
- **Minimum** : voir `MATERIEL.md` — appareil de référence Xiaomi Mi Box S 2e gén.

## Panneau de référence

Un abonnement réel sert de référence pour les essais (identifiants **jamais**
dans le dépôt : variables `XTREAM_URL`, `XTREAM_USER`, `XTREAM_PASS`, voir
`core/examples/smoke.rs`). Mesures du 2026-10-28 depuis un serveur cloud :

| | Catégories | Entrées | Temps |
|---|---|---|---|
| Direct | 63 | 3 330 | ~2 s |
| Films | 50 | 29 372 | ~4 s |
| Séries | 33 | 8 637 | ~2 s |

Règle : **aucun appel automatique** au panneau (CI, cloud, scripts) ; essais
réels uniquement à la main par le propriétaire, depuis son réseau.

Constats :
- 1 connexion simultanée maximum : l'app ne doit **jamais** ouvrir deux flux
  en même temps (aperçu + plein écran compris).
- Deux adresses (« private » et « generated ») servent le même compte : le
  cache est lié à l'identifiant, pas à l'adresse (changer d'adresse garde
  favoris, reprise et code PIN ; décision du propriétaire, 2026-10-28).
- `get_vod_categories` renvoie `[]` sur **environ 60 % des appels** (mesuré
  sur 8 appels, les autres listes sont stables) : le cache retente jusqu'à
  6 fois une liste de catégories vide et n'écrase jamais un catalogue connu
  par une réponse vide.
- L'ordre des catégories varie d'une requête à l'autre : trier côté app.
