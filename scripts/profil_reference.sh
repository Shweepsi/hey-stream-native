#!/usr/bin/env bash
# Régénère android/app/src/main/baseline-prof.txt depuis l'usage réel de l'appli
# sur l'appareil de référence (Mi Box S 2), branché en adb. L'appareil se désigne
# par ANDROID_SERIAL (adresse:port) : le script refuse de deviner, pour ne pas
# prendre le profil d'une autre box (la Shield n'a ni le même processeur ni le
# même usage).
#
# 1. Installer l'APK, puis installer le profil des bibliothèques et compiler :
#      adb shell am broadcast -a androidx.profileinstaller.action.INSTALL_PROFILE \
#        io.github.shweepsi.heystream/androidx.profileinstaller.ProfileInstallReceiver
#      adb shell am force-stop io.github.shweepsi.heystream
#      adb shell cmd package compile -m speed-profile -f io.github.shweepsi.heystream
# 2. Utiliser l'appli normalement, à la main (démarrage, Direct, zapping, plein
#    écran, film, Films, fiches, Séries, recherche, Réglages) : aucun script ne doit
#    appeler le panneau (CLAUDE.md).
# 3. Lancer ce script : il extrait le profil accumulé et le convertit.
#
# Limite : le profil vient d'un APK minifié (R8) ; les règles sont réécrites par
# R8 à la compilation, et celles qui visent une classe fusionnée ou générée par la
# compilation (lambdas synthétiques…) ne trouvent pas leur cible dans le code
# d'origine : elles sont ignorées. Le profil embarqué est donc un sous-ensemble de
# ce fichier. Le régénérer après un changement important du code.
set -euo pipefail
cd "$(dirname "$0")/.."
if [ -z "${ANDROID_SERIAL:-}" ]; then
  echo "Designer l'appareil de reference : ANDROID_SERIAL=<adresse:port> scripts/profil_reference.sh" >&2
  exit 2
fi
pkg=io.github.shweepsi.heystream
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
adb shell am force-stop "$pkg"
adb shell cmd package dump-profiles "$pkg"
if ! adb pull "/data/misc/profman/$pkg-primary.prof.txt" "$tmp/profil.txt" >/dev/null; then
  echo "Profil introuvable sur l'appareil (l'appli est-elle installée et utilisée ? Android 11 laisse le shell lire /data/misc/profman ; sinon, appareil rooté)." >&2
  exit 1
fi
node scripts/profil_reference.mjs "$tmp/profil.txt" android/app/src/main/baseline-prof.txt
