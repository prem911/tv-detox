#!/bin/sh
# Runs ci/MarketHoursCheck.java against the app's MarketBoard on this computer (after sh build.sh).
set -e
cd "$(dirname "$0")/.."
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}}
JAR=$SDK/platforms/android-34/android.jar
d=$(mktemp -d)
javac -nowarn -d "$d" -cp "out/classes:$JAR" ci/MarketHoursCheck.java
java -cp "$d:out/classes:$JAR" com.ansub.tvhome.MarketHoursCheck
