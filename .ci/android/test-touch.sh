#!/usr/bin/env bash
# SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CACHE="${TOUCH_TEST_CACHE:-$ROOT/.cache/touch-tests}"
MAVEN="${MAVEN_CENTRAL_URL:-https://repo.maven.apache.org/maven2}"
JAVA="${JAVA_HOME:+${JAVA_HOME}/bin/}java"
mkdir -p "$CACHE"

fetch() {
    local artifact="$1" target="$CACHE/$2"
    if [[ ! -s "$target" ]]; then
        curl --fail --location --silent --show-error --connect-timeout 20 --max-time 180 \
            "$MAVEN/$artifact" --output "$target.download"
        cp "$target.download" "$target"
    fi
}

fetch org/jetbrains/kotlin/kotlin-compiler-embeddable/1.9.20/kotlin-compiler-embeddable-1.9.20.jar compiler.jar
fetch org/jetbrains/kotlin/kotlin-stdlib/1.9.20/kotlin-stdlib-1.9.20.jar stdlib.jar
fetch org/jetbrains/kotlin/kotlin-script-runtime/1.9.20/kotlin-script-runtime-1.9.20.jar script.jar
fetch org/jetbrains/kotlin/kotlin-reflect/1.6.10/kotlin-reflect-1.6.10.jar reflect.jar
fetch org/jetbrains/intellij/deps/trove4j/1.0.20200330/trove4j-1.0.20200330.jar trove.jar
fetch org/jetbrains/annotations/13.0/annotations-13.0.jar annotations.jar
fetch org/jetbrains/kotlin/kotlin-serialization-compiler-plugin-embeddable/1.9.20/kotlin-serialization-compiler-plugin-embeddable-1.9.20.jar serialization-plugin.jar
fetch org/jetbrains/kotlinx/kotlinx-serialization-core-jvm/1.6.3/kotlinx-serialization-core-jvm-1.6.3.jar serialization-core.jar
fetch org/jetbrains/kotlinx/kotlinx-serialization-json-jvm/1.6.3/kotlinx-serialization-json-jvm-1.6.3.jar serialization-json.jar
fetch junit/junit/4.13.2/junit-4.13.2.jar junit.jar
fetch org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar hamcrest.jar

MAIN="$ROOT/src/android/app/src/main/java/org/yuzu/yuzu_emu/overlay/touch"
TEST="$ROOT/src/android/app/src/test/java/org/yuzu/yuzu_emu/overlay/touch"
CP="$CACHE/stdlib.jar:$CACHE/serialization-core.jar:$CACHE/serialization-json.jar:$CACHE/junit.jar:$CACHE/hamcrest.jar"
"$JAVA" -cp "$CACHE/compiler.jar:$CACHE/stdlib.jar:$CACHE/script.jar:$CACHE/reflect.jar:$CACHE/trove.jar:$CACHE/annotations.jar" \
    org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 \
    -Xplugin="$CACHE/serialization-plugin.jar" -classpath "$CP" -d "$CACHE/tests.jar" \
    "$MAIN"/model/*.kt "$MAIN"/store/*.kt "$MAIN"/runtime/*.kt \
    "$MAIN/TouchNativeSink.kt" "$MAIN/ui/TouchEditorGeometry.kt" "$MAIN/ui/TouchWorkspaceDraft.kt" "$MAIN/ui/TouchToolState.kt" \
    "$TEST"/store/*.kt "$TEST"/runtime/*.kt \
    "$TEST/ui/TouchEditorGeometryTest.kt" "$TEST/ui/TouchWorkspaceDraftTest.kt" "$TEST/ui/TouchToolStateTest.kt" \
    "$TEST/TouchNativeSinkTest.kt"
"$JAVA" -cp "$CACHE/tests.jar:$CP" org.junit.runner.JUnitCore \
    org.yuzu.yuzu_emu.overlay.touch.store.TouchStoreTest \
    org.yuzu.yuzu_emu.overlay.touch.store.TouchStoreReviewTest \
    org.yuzu.yuzu_emu.overlay.touch.runtime.TouchRuntimeTest \
    org.yuzu.yuzu_emu.overlay.touch.runtime.SwipeMotionTest \
    org.yuzu.yuzu_emu.overlay.touch.TouchNativeSinkTest \
    org.yuzu.yuzu_emu.overlay.touch.ui.TouchEditorGeometryTest \
    org.yuzu.yuzu_emu.overlay.touch.ui.TouchWorkspaceDraftTest \
    org.yuzu.yuzu_emu.overlay.touch.ui.TouchToolStateTest
