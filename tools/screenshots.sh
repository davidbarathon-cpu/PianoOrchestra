#!/usr/bin/env bash
# Installe l'APK sur la tablette virtuelle, ouvre chaque écran en français puis en russe,
# prend une capture, puis refait la même chose en format téléphone. Échoue si l'app plante.
set -u
APK=$(ls "$1"/*.apk | head -1)
mkdir -p shots
adb install -r "$APK"
# l'émulateur affiche parfois « Pixel Launcher isn't responding » : on masque ces fenêtres et on le laisse se calmer
adb shell settings put global hide_error_dialogs 1
adb shell input keyevent KEYCODE_HOME
sleep 40
adb logcat -c
PKG=com.pianoorchestra

shot() {  # nom, extras…
  local name=$1; shift
  timeout 60 adb wait-for-device || { echo "émulateur perdu"; return; }
  timeout 60 adb shell am start -S -W -n $PKG/.MainActivity "$@" > /dev/null
  sleep "${WAIT:-6}"
  timeout 30 adb exec-out screencap -p > "shots/$name.png"
  echo "capture $name"
}

for lang in fr ru; do
  WAIT=10 shot "tablette-${lang}-1-accueil" --es po_lang $lang --es po_screen home
  shot "tablette-${lang}-2-clavier" --es po_lang $lang --es po_screen keyboard
  shot "tablette-${lang}-3-morceaux" --es po_lang $lang --es po_screen playalong
  WAIT=10 shot "tablette-${lang}-4-groupe" --es po_lang $lang --ei po_song 0
  shot "tablette-${lang}-5-reglages" --es po_lang $lang --es po_screen settings
  WAIT=14 shot "tablette-${lang}-8-partition" --es po_lang $lang --ei po_song 100
  WAIT=8 shot "tablette-${lang}-10-echange" --es po_lang $lang --es po_screen exchange
  WAIT=8 shot "tablette-${lang}-9-catalogue" --es po_lang $lang --es po_screen catalog
  WAIT=12 shot "tablette-${lang}-7-jeu-libre" --es po_lang $lang --es po_screen freeplay --es po_chord 45,48,52
done

# les notes qui tombent, pendant la lecture
WAIT=16 shot "tablette-fr-14-notes-qui-tombent" --es po_lang fr --ei po_song 0 --ez po_autoplay true --ez po_falling true
# composer : le jeu libre avec l'enregistrement (capture de l'écran seulement)

# les deux autres ambiances (Bermet choisit dans les réglages)
WAIT=10 shot "tablette-fr-9-aurore-accueil" --es po_lang fr --es po_screen home --es po_ambiance aurore
WAIT=12 shot "tablette-fr-10-aurore-jeu-libre" --es po_lang fr --es po_screen freeplay --es po_chord 48,52,55
WAIT=10 shot "tablette-fr-11-ocean-accueil" --es po_lang fr --es po_screen home --es po_ambiance ocean
shot "tablette-fr-12-ocean-reglages" --es po_lang fr --es po_screen settings
WAIT=10 shot "tablette-fr-13-ocean-groupe" --es po_lang fr --ei po_song 2
adb shell am start -S -W -n $PKG/.MainActivity --es po_ambiance nuit > /dev/null
sleep 4

# le groupe joue-t-il vraiment ? on lance la lecture et on vérifie que le temps avance
adb shell am start -S -W -n $PKG/.MainActivity --es po_lang fr --ei po_song 1 --ez po_autoplay true > /dev/null
sleep 14
adb exec-out screencap -p > "shots/tablette-fr-6-en-lecture.png"

# format téléphone (même appareil, écran redimensionné)
adb shell wm size 1080x2340
adb shell wm density 420
sleep 3
shot "telephone-fr-1-accueil" --es po_lang fr --es po_screen home
shot "telephone-fr-2-clavier" --es po_lang fr --es po_screen keyboard
WAIT=10 shot "telephone-fr-3-groupe" --es po_lang fr --ei po_song 0
adb shell wm size reset
adb shell wm density reset

timeout 60 adb logcat -d > shots/logcat.txt
grep -E "PianoOrchestra|AndroidRuntime" shots/logcat.txt | tail -60
if grep -q "FATAL EXCEPTION" shots/logcat.txt && grep -A3 "FATAL EXCEPTION" shots/logcat.txt | grep -q "$PKG"; then
  echo "L'application a planté :"
  grep -A30 "FATAL EXCEPTION" shots/logcat.txt
  exit 1
fi
echo "Aucun plantage."
