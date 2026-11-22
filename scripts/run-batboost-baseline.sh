#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ -n "${GRADLE_USER_HOME:-}" ]]; then
    gradle_user_home="$GRADLE_USER_HOME"
else
    user_home="$(getent passwd "${USER:-$(id -un)}" | cut -d: -f6)"
    gradle_user_home="$user_home/.gradle"
fi

sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$sdk_root" ]]; then
    sdk_root="$(sed -n 's/^sdk\.dir=//p' "$project_dir/local.properties" | head -n 1)"
fi
android_jar="$sdk_root/platforms/android-35/android.jar"
cache_root="$gradle_user_home/caches/modules-2/files-2.1"

find_cached() {
    local artifact="$1"
    local result
    result="$(find "$cache_root" -type f -name "$artifact" -print -quit)"
    if [[ -z "$result" ]]; then
        printf 'Missing cached dependency: %s\n' "$artifact" >&2
        exit 2
    fi
    printf '%s' "$result"
}

if [[ ! -f "$android_jar" ]]; then
    printf 'Missing Android platform jar: %s\n' "$android_jar" >&2
    exit 2
fi

compiler="$(find_cached kotlin-compiler-embeddable-2.0.21.jar)"
stdlib="$(find_cached kotlin-stdlib-2.0.21.jar)"
script_runtime="$(find_cached kotlin-script-runtime-2.0.21.jar)"
reflect="$(find_cached kotlin-reflect-1.6.10.jar)"
daemon="$(find_cached kotlin-daemon-embeddable-2.0.21.jar)"
trove="$(find_cached trove4j-1.0.20200330.jar)"
coroutines="$(find_cached kotlinx-coroutines-core-jvm-1.6.4.jar)"
annotations="$(find_cached annotations-23.0.0.jar)"
junit="$(find_cached junit-4.13.2.jar)"
hamcrest="$(find_cached hamcrest-core-1.3.jar)"
guava="$(find_cached guava-33.3.1-android.jar)"
media3_common="$(find_cached media3-common-1.5.1.aar)"

out_dir="${TMPDIR:-/tmp}/batboost-baseline-run-$$"
mkdir "$out_dir"
trap 'rm -rf -- "$out_dir"' EXIT
mkdir "$out_dir/classes"
unzip -p "$media3_common" classes.jar > "$out_dir/media3-common.jar"

compiler_cp="$compiler:$stdlib:$script_runtime:$reflect:$daemon:$trove:$coroutines:$annotations"
compile_cp="$android_jar:$out_dir/media3-common.jar:$stdlib:$annotations:$junit"
runtime_cp="$out_dir/classes:$android_jar:$out_dir/media3-common.jar:$stdlib:$annotations:$guava:$junit:$hamcrest"

cd "$project_dir"
java -cp "$compiler_cp" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -no-reflect -jvm-target 17 -classpath "$compile_cp" \
    -d "$out_dir/classes" \
    app/src/main/java/com/batmusic/app/audio/LookaheadTruePeakLimiter.kt \
    app/src/main/java/com/batmusic/app/audio/BatAudioProcessor.kt \
    app/src/test/java/com/batmusic/app/audio/BatAudioBaselineHarness.kt

if [[ "${1:-}" == "--distortion" ]]; then
    java -cp "$runtime_cp" com.batmusic.app.audio.BatAudioBaselineHarness distortion
else
    java -cp "$runtime_cp" org.junit.runner.JUnitCore com.batmusic.app.audio.BatAudioBaselineTest
fi
