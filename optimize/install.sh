#!/usr/bin/env bash
# Download the latest APKs from GitHub and install them on the TV.
#   optimize/install.sh saver      شاشة تلفزيونك: install + make it the screensaver
#   optimize/install.sh launcher   the home screen: install + make it the default Home
#   optimize/install.sh all        both
#   optimize/install.sh extras     two free apps from their own GitHub releases: TV Bro (a TV web browser,
#                                  github.com/truefedex/tv-bro) and SmartTube (YouTube without ads,
#                                  github.com/yuliskov/SmartTube), the build that fits the TV's processor
# The first install of the screensaver replaces an APK signed with another key: uninstall the old one first
# (pm uninstall sa.yourtv.v2.screensaver) — this script does that only if a plain install fails.
set -euo pipefail
cd "$(dirname "$0")/.."
PY="${PY:-optimize/venv/bin/python}"
REL=https://github.com/jolyan20/YourTV-V2/releases/download/${APK_TAG:-apk-latest}   # APK_TAG=v3.N: that numbered build
what="${1:?usage: optimize/install.sh saver|launcher|all|extras}"

push_install() {   # $1 apk name, $2 package
  curl -fsSL -o "optimize/$1" "$REL/$1"
  "$PY" -I optimize/tv.py --push-b64 "optimize/$1" "/data/local/tmp/$1"
  local out
  out=$("$PY" -I optimize/tv.py "pm install -r /data/local/tmp/$1")
  if ! grep -q Success <<< "$out"; then
    # only a signing-key clash justifies removing the installed app (and its settings); anything else stops here
    if grep -qE 'UPDATE_INCOMPATIBLE|signatures do not match' <<< "$out"; then
      echo "An older $2 signed with another key is installed: removing it, then installing again."
      # removing the home app while the stock one is off would leave the TV with no home at all
      [ "$2" = sa.yourtv.v2.home ] && "$PY" -I optimize/tv.py "pm enable ${STOCK%%/*}" > /dev/null || true
      "$PY" -I optimize/tv.py "pm uninstall $2" || true
      "$PY" -I optimize/tv.py "pm install -r /data/local/tmp/$1" | grep Success
    else
      echo "!! Install of $1 failed: $out"; exit 1
    fi
  fi
  "$PY" -I optimize/tv.py "rm -f /data/local/tmp/$1"
}

from_github() {   # $1 owner/repo, $2 package, $3 asset pattern ({abi} = the TV's processor types, best first)
  local abis url name out
  abis=$("$PY" -I optimize/tv.py "getprop ro.product.cpu.abilist" | tr -d '\r')
  url=$(curl -fsSL "https://api.github.com/repos/$1/releases/latest" | "$PY" -I -c '
import json, re, sys
assets = {a["name"]: a["browser_download_url"] for a in json.load(sys.stdin)["assets"]}
for abi in [a for a in sys.argv[2].split(",") if a] + ["universal", ""]:
    pat = re.compile(sys.argv[1].replace("{abi}", re.escape(abi)) + "$")
    hit = sorted(n for n in assets if pat.search(n))
    if hit:
        print(assets[hit[0]]); break
' "$3" "$abis") || true
  if [ -z "$url" ]; then echo "!! No build of $1 for this TV ($abis): skipped"; return 0; fi
  name=$(basename "$url")
  curl -fsSL -o "optimize/$name" "$url"
  "$PY" -I optimize/tv.py --push-b64 "optimize/$name" "/data/local/tmp/$name"
  out=$("$PY" -I optimize/tv.py "pm install -r /data/local/tmp/$name")
  "$PY" -I optimize/tv.py "rm -f /data/local/tmp/$name" > /dev/null
  rm -f "optimize/$name"
  if grep -q Success <<< "$out"; then echo "Installed $name"
  elif grep -qE 'UPDATE_INCOMPATIBLE|signatures do not match' <<< "$out"; then
    echo "$2 is already installed from somewhere else: left as it is (with its settings)."   # never removed
  else echo "!! Install of $name failed: $out"; fi
}

if [ "$what" = extras ]; then
  from_github truefedex/tv-bro com.phlox.tvwebbrowser 'tvbro-[0-9.]+-generic-geckoExcluded\.apk'   # light: the TV's WebView
  from_github yuliskov/SmartTube org.smarttube.stable 'SmartTube_stable_[0-9.]+_{abi}\.apk'
fi

if [ "$what" = saver ] || [ "$what" = all ]; then
  push_install urtv-screensaver.apk sa.yourtv.v2.screensaver
  printf '%s\n' \
    "settings put secure screensaver_enabled 1" \
    "settings put secure screensaver_components sa.yourtv.v2.screensaver/.ScreenDream" \
    "settings put secure screensaver_default_component sa.yourtv.v2.screensaver/.ScreenDream" \
    "appops set sa.yourtv.v2.screensaver SYSTEM_ALERT_WINDOW allow" \
    "appops set sa.yourtv.v2.screensaver REQUEST_INSTALL_PACKAGES allow" \
    "am start -n sa.yourtv.v2.screensaver/.SettingsActivity" \
    | "$PY" -I optimize/tv.py --batch
  echo "Screensaver installed and selected. Its settings screen is open on the TV."
fi

if [ "$what" = launcher ] || [ "$what" = all ]; then
  # Which stock home does this TV have? Google TV sets: launcherx. Older Android TV: tvlauncher.
  if "$PY" -I optimize/tv.py "pm list packages com.google.android.apps.tv.launcherx" | grep -q launcherx; then
    STOCK=com.google.android.apps.tv.launcherx/com.google.android.apps.tv.launcherx.home.HomeActivity
  else
    STOCK=com.google.android.tvlauncher/.MainActivity
  fi
  echo "Stock home: $STOCK"

  # 1. The new home must be installed and working BEFORE anything is disabled.
  push_install urtv-home.apk sa.yourtv.v2.home
  "$PY" -I optimize/tv.py "pm grant sa.yourtv.v2.home android.permission.ACCESS_FINE_LOCATION" || true

  # 2. Arm the safety switch on the TV: it restores the stock home in 300 s unless we confirm (slow ADB needs time).
  "$PY" -I optimize/tv.py --push-b64 optimize/deadman.sh /data/local/tmp/deadman.sh
  printf '%s\n' \
    "rm -f /data/local/tmp/home_ok" \
    "nohup sh /data/local/tmp/deadman.sh $STOCK 300 > /dev/null 2>&1 &" \
    | "$PY" -I optimize/tv.py --batch
  echo "Safety switch armed: the stock home comes back by itself in 5 minutes unless the new home works."

  # 3. Switch. Setup app first, then the stock home (this order avoided the black screen).
  printf '%s\n' \
    "cmd package set-home-activity --user 0 sa.yourtv.v2.home/.MainActivity" \
    "pm disable-user --user 0 com.google.android.tungsten.setupwraith" \
    "pm disable-user --user 0 ${STOCK%%/*}" \
    "cmd package set-home-activity --user 0 sa.yourtv.v2.home/.MainActivity" \
    "am force-stop com.android.tv.settings" \
    "input keyevent KEYCODE_HOME" \
    | "$PY" -I optimize/tv.py --batch
  # 4. Verify Home really opens the new launcher (a few tries: a slow TV), then disarm the switch.
  ours=no
  for try in 1 2 3; do
    sleep 6
    if "$PY" -I optimize/tv.py "dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity'" \
         | grep -q sa.yourtv.v2.home; then ours=yes; break; fi
    "$PY" -I optimize/tv.py "input keyevent KEYCODE_HOME" > /dev/null
  done
  if [ "$ours" = yes ]; then
    "$PY" -I optimize/tv.py "touch /data/local/tmp/home_ok"
    # the switch may have fired already if ADB was very slow: then the stock home is back, say so and stop
    if ! "$PY" -I optimize/tv.py "cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN" \
         | grep -q sa.yourtv.v2.home; then
      echo "!! The safety switch put the stock home back (ADB was too slow). Nothing is broken: run the same command again."
      exit 1
    fi
    # 5. Only now: the stock home's helpers are dead weight (restore.sh brings them back).
    grep -vE '^\s*(#|$)' optimize/lists/home-replaced.txt | awk '{print "pm disable-user --user 0 " $1}' \
      | "$PY" -I optimize/tv.py --batch > /dev/null || true
    echo "Launcher is home. Press Home from inside an app to double-check."
    echo "Undo everything: optimize/reset.sh"
  else
    echo "!! Home did not open the new launcher. Leave the TV alone: the stock home returns within 5 minutes."
    echo "   Some TCL firmware pins Home to the stock launcher; on those sets keep the stock home."
    exit 1
  fi
fi
