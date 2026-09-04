#!/usr/bin/env bash
# Runs the full Stage 1 + Stage 2 verification with nothing but kotlinc + a JRE.
#
# This is the sandbox path (no Gradle, no Maven, no Android SDK). CI runs the
# identical assertions through Gradle via :core-protocol:goldenTest and
# :core-vision:visionTest — same main() entry points, same golden vectors.
#
# Usage:  ./android/verify-local.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

if [ -f /home/user/toolchain/env.sh ]; then
  # shellcheck disable=SC1091
  . /home/user/toolchain/env.sh
fi

command -v kotlinc >/dev/null || { echo "kotlinc not on PATH"; exit 127; }
command -v java >/dev/null    || { echo "java not on PATH"; exit 127; }

OUT="$REPO_ROOT/build/kt"
mkdir -p "$OUT"

echo "==> Java:   $(java -version 2>&1 | head -1)"
echo "==> Kotlin: $(kotlinc -version 2>&1 | tail -1)"

echo
echo "==> Compiling core-protocol (+ golden-vector tests)"
kotlinc \
  android/core-protocol/src/main/kotlin/app/candela/protocol/*.kt \
  android/core-protocol/src/test/kotlin/app/candela/protocol/*.kt \
  -include-runtime -d "$OUT/protocol-tests.jar" 2>&1 | grep -v '^warning: ' || true

echo "==> Compiling core-vision (+ gate tests)"
kotlinc \
  android/core-vision/src/main/kotlin/app/candela/vision/*.kt \
  android/core-vision/src/test/kotlin/app/candela/vision/*.kt \
  -include-runtime -d "$OUT/vision-tests.jar" 2>&1 | grep -v '^warning: ' || true

# Stage 4 + Stage 6 pure logic. The Camera2/SurfaceView call layers under
# src/main/kotlin need the Android SDK and are built in CI; everything they
# DECIDE lives under src/pure/kotlin and is verified right here.
echo "==> Compiling optical-camera + optical-render pure logic (+ Stage 4/6 tests)"
kotlinc \
  android/optical-camera/src/pure/kotlin/app/candela/camera/*.kt \
  android/optical-render/src/pure/kotlin/app/candela/render/*.kt \
  android/optical-camera/src/pure/test/kotlin/app/candela/camera/*.kt \
  android/optical-render/src/pure/test/kotlin/app/candela/render/*.kt \
  -include-runtime -d "$OUT/stage46-tests.jar" 2>&1 | grep -v '^warning: ' || true

echo
java -cp "$OUT/protocol-tests.jar" app.candela.protocol.GoldenTestsKt \
  "$REPO_ROOT/android/core-protocol/src/test/resources/golden"
PROTO=$?

echo
java -cp "$OUT/vision-tests.jar" app.candela.vision.VisionTestsKt
VISION=$?

echo
java -cp "$OUT/stage46-tests.jar" app.candela.camera.CameraLogicTestsKt
STAGE46=$?

echo
java -cp "$OUT/protocol-tests.jar" app.candela.protocol.Stage8TestsKt
STAGE8=$?

echo
java -cp "$OUT/stage46-tests.jar" app.candela.render.ThermalTestsKt
STAGE7=$?

echo
if [ $PROTO -eq 0 ] && [ $VISION -eq 0 ] && [ $STAGE46 -eq 0 ] && [ $STAGE8 -eq 0 ] \
   && [ $STAGE7 -eq 0 ]; then
  echo "VERIFICATION PASSED — protocol wire-compatible; vision gates, camera/render,"
  echo "                     thermal governor and SAS/export logic behave per audit."
  exit 0
fi
echo "VERIFICATION FAILED (protocol=$PROTO vision=$VISION stage4/6=$STAGE46 \
stage8=$STAGE8 stage7=$STAGE7)"
exit 1
