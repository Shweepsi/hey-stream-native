#!/usr/bin/env bash
# Régénère android/app/src/main/baseline-prof.txt depuis l'usage réel de l'appli
# sur l'appareil de référence (Mi Box S 2), branché en adb.
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
set -euo pipefail
cd "$(dirname "$0")/.."
pkg=io.github.shweepsi.heystream
tmp=$(mktemp -d)
adb shell am force-stop "$pkg"
adb shell cmd package dump-profiles "$pkg"
adb pull "/data/misc/profman/$pkg-primary.prof.txt" "$tmp/profil.txt" >/dev/null
node scripts/profil_reference.mjs "$tmp/profil.txt" android/app/src/main/baseline-prof.txt
rm -rf "$tmp"
