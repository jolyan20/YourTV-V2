#!/usr/bin/env bash
# Screenshots without navigating by hand. Saves full-size PNGs in optimize/run/shots/<NAME>.png.
#   optimize/shot.sh home [NAME]          the home screen (after Home, 8 s)
#   optimize/shot.sh screensaver [NAME]   the screensaver (started now, 40 s for its data), then Back
#   optimize/shot.sh favourites [NAME]    «ترتيب المفضلة»
#   optimize/shot.sh settings [NAME]      شاشة تلفزيونك's settings
#   optimize/shot.sh now NAME             whatever is on screen now
set -euo pipefail
cd "$(dirname "$0")/.."
PY="${PY:-optimize/venv/bin/python}"
mkdir -p optimize/run/shots
tv() { "$PY" -I optimize/tv.py "$@" > /dev/null; }
case "${1:-}" in
  home)        tv "input keyevent KEYCODE_HOME"; sleep 8; name=home ;;
  screensaver) tv "am start -n com.android.systemui/.Somnambulator"; sleep 40; name=screensaver ;;
  favourites)  tv "am start -n sa.yourtv.v2.home/.DockActivity"; sleep 4; name=favourites ;;
  settings)    tv "am start -n sa.yourtv.v2.screensaver/.SettingsActivity"; sleep 4; name=settings ;;
  now)         name="${2:?usage: optimize/shot.sh now NAME}" ;;
  *) sed -n '2,8p' "$0"; exit 1 ;;
esac
file="optimize/run/shots/${2:-$name}.png"
"$PY" -I optimize/tv.py --shot "$file"
[ "$1" = screensaver ] && tv "input keyevent KEYCODE_BACK"
echo "$file"
