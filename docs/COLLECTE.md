# Données des testeurs — mise en place et lecture

Décision : `docs/DECISIONS.md`, « Données des testeurs ». Ce document dit
comment le mettre en route (une fois) et comment lire les données.

## Ce qui est collecté

Seulement par l'**APK testeur** (`hey-stream-X.Y.Z-testeur.apk`), que le
propriétaire remet aux testeurs qui lui ont donné leur accord. Il n'est
jamais sur GitHub : il est servi par le service de collecte, au
propriétaire (clé d'administration) et aux APK testeur pour leurs mises à
jour (clé d'envoi). L'APK public (`hey-stream-X.Y.Z.apk`, releases GitHub)
n'envoie rien. Chacun se met à jour vers le sien.

| Sorte | Contenu |
|---|---|
| `responses` | Réponses entières du panneau, nettoyées sur l'appareil (identifiant, mot de passe, adresse du serveur, hôtes et jetons des URL, adresses IP), une ligne par réponse. |
| `events` | Une ligne par événement : `app_start` (appareil : modèle, Android, écran, mémoire, langue), `foreground`/`background`, `screen` (route et numéros), `search` (texte cherché, nombre de résultats), `search_open`, `play_start`/`play_ready`/`rebuffer`/`play_error`/`play_failed`/`play_end` (sorte et numéro du flux, démarrage, coupures, durée vue, images perdues, débit), `play_format`, `play_decoder`, `catalog_refresh(_failed)`, `sign_in`, `memory`, `crash`, `rust_panic`. |

Jamais d'URL de flux ni d'identifiant. L'appareil est un numéro aléatoire
(`X-Device`). Les envois partent par lots compressés, sur tout réseau, mais
jamais pendant une lecture : toutes les heures et une minute après le
passage en arrière-plan. Aucune requête en plus vers le panneau.

## Mise en route (propriétaire, une fois)

1. **Jeton Cloudflare** : tableau de bord Cloudflare › *My Profile* › *API
   Tokens* › *Create Token* › *Custom token*, avec les droits du compte
   *Workers Scripts: Edit* et *Workers R2 Storage: Edit*. Noter aussi
   l'*Account ID* (page d'accueil du compte, colonne de droite).
2. **Sous-domaine workers.dev** : *Workers & Pages* › choisir un
   sous-domaine s'il n'y en a pas encore.
3. **Deux clés au hasard**, par exemple `openssl rand -hex 32`, deux fois :
   la clé d'envoi (dans l'APK testeur, ne permet que de déposer) et la clé
   d'administration (lecture et coupure, jamais dans l'app).
4. **Secrets du dépôt** (GitHub › *Settings* › *Secrets and variables* ›
   *Actions*) : `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ACCOUNT_ID`,
   `TELEMETRY_KEY` (clé d'envoi), `TELEMETRY_ADMIN_KEY`.
5. **Déployer** : *Actions* › *collecte* › *Run workflow* (branche `main`).
   Le stockage R2 `hey-stream-donnees-eu` est créé s'il n'existe pas, en Europe de
   l'Ouest (WEUR). L'emplacement d'un bucket ne change plus après coup.
6. **Adresse** : ajouter le secret `TELEMETRY_URL` =
   `https://hey-stream-donnees.<sous-domaine>.workers.dev`.
7. La release suivante dépose l'APK testeur dans le stockage du service
   (et l'APK public sur GitHub, comme avant). Sans ces secrets, seul l'APK
   public est publié.
8. **Récupérer l'APK testeur**, à remettre aux testeurs : ouvrir dans un
   navigateur
   `https://hey-stream-donnees.<sous-domaine>.workers.dev/v1/admin/apk?key=<clé d'administration>`.
   Ne partager que le fichier, jamais ce lien (il contient la clé).

Pour les rapports automatiques (session Claude), ajouter dans
l'environnement de la session la variable `TELEMETRY_ADMIN_KEY`, et
autoriser l'hôte `*.workers.dev` dans son accès réseau.

## Lecture (clé d'administration)

```sh
H="Authorization: Bearer $TELEMETRY_ADMIN_KEY"
U=https://hey-stream-donnees.<sous-domaine>.workers.dev
curl -sH "$H" "$U/v1/admin/devices"                       # appareils, dernière visite
curl -sH "$H" "$U/v1/admin/list?prefix=events/2026-11-02/" # lots d'un jour
curl -sH "$H" "$U/v1/admin/object?key=<clé>&decompress=1"  # un lot, en lignes JSON
```

Rangement : `<sorte>/<AAAA-MM-JJ>/<appareil>/<heure>-<aléa>.ndjson.gz`.
Conservation : un an, puis effacement automatique chaque nuit.

## Coupure à distance

```sh
# Tout couper :
curl -sH "$H" -X PUT "$U/v1/admin/control" -d '{"paused": true}'
# Un seul appareil :
curl -sH "$H" -X PUT "$U/v1/admin/control" -d '{"paused_devices": ["<numéro>"]}'
# Reprendre :
curl -sH "$H" -X PUT "$U/v1/admin/control" -d '{"paused": false}'
```

Coupé, l'appareil efface ce qui attendait et n'écrit plus rien (les
réponses du panneau, jusqu'au prochain lancement de l'app). Il redemande
toutes les heures et reprend dès que la coupure est levée.
