#!/usr/bin/env bash
# Builds worldgend-wrap.jar: the quick-launch jar plus the Wrap front end. Needs JDK 25.
set -euo pipefail
cd "$(dirname "$0")"
JDK="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")}"
BASE=../../quick-launch-jar/worldgend.jar
[[ -f $BASE ]] || ../../quick-launch-jar/build.sh

rm -rf build && mkdir -p build/stage build/classes
(cd build/stage && "$JDK/bin/jar" xf "../../$BASE")
"$JDK/bin/javac" --release 8 -Xlint:-options -cp build/stage -d build/classes src/*.java
cp -r build/classes/. build/stage/
rm -f build/stage/META-INF/MANIFEST.MF

cat > build/manifest.txt <<'M'
Main-Class: Wrap
Launcher-Agent-Class: SelfAgent
Can-Retransform-Classes: true
Add-Opens: java.base/java.lang
M
"$JDK/bin/jar" cfm worldgend-wrap.jar build/manifest.txt -C build/stage .
rm -rf build
