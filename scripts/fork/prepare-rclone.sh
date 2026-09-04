#!/usr/bin/env bash
# [FORK] Make `rclone.aar` available to the Gradle build.
#
# `src/android/app/build.gradle.kts` declares
#     implementation(group = "", name = "rclone", ext = "aar")
# resolved from the flatDir repo `app/libs`. Upstream treats that .aar as a
# BUILD ARTIFACT (it is gitignored), produced by `deps/build_rclone_android.sh`
# via gomobile. Without it the build fails at configuration time — before a
# single line of Kotlin is compiled — so CI must produce something.
#
# Two modes:
#
#   real  (default) Build it for real with gomobile. Needs Go + the NDK and a
#         few minutes on a cold module cache. Full remote-backup support.
#   stub  Synthesise a minimal .aar exposing the exact gomobile-generated API
#         surface (com.openminis.rclone.gomobile.Gomobile / RcloneRPCResult)
#         with every RPC answering HTTP 501. The app compiles and runs; ONLY
#         remote backup destinations (SMB/WebDAV/SFTP/S3/FTP) are unavailable,
#         and they fail with a clear message instead of crashing.
#
# `real` automatically falls back to `stub` so a Go/gomobile hiccup degrades one
# feature instead of failing the whole APK build. The chosen mode is written to
# $GITHUB_STEP_SUMMARY when running in Actions.
#
# Usage: prepare-rclone.sh [real|stub]
set -uo pipefail

MODE="${1:-real}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
LIBS_DIR="$ROOT/src/android/app/libs"
OUT_AAR="$LIBS_DIR/rclone.aar"
WORK="$ROOT/deps/build/rclone-stub"

mkdir -p "$LIBS_DIR"

note() {
  echo "[prepare-rclone] $*"
  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    echo "- rclone: $*" >> "$GITHUB_STEP_SUMMARY"
  fi
}

build_real() {
  command -v go >/dev/null 2>&1 || { echo "[prepare-rclone] no go toolchain"; return 1; }

  if ! command -v gomobile >/dev/null 2>&1; then
    echo "[prepare-rclone] installing gomobile/gobind…"
    go install golang.org/x/mobile/cmd/gomobile@latest || return 1
    go install golang.org/x/mobile/cmd/gobind@latest || return 1
    export PATH="$PATH:$(go env GOPATH)/bin"
  fi

  bash "$ROOT/deps/build_rclone_android.sh" || return 1

  local built="$ROOT/deps/build/rclone/rclone.aar"
  [ -f "$built" ] || return 1
  cp -f "$built" "$OUT_AAR"
  return 0
}

build_stub() {
  command -v javac >/dev/null 2>&1 || {
    echo "[prepare-rclone] FATAL: javac not found; cannot even build the stub" >&2
    return 1
  }
  rm -rf "$WORK"
  mkdir -p "$WORK/src/com/openminis/rclone/gomobile" "$WORK/classes" "$WORK/aar"

  # The API shape is dictated by gomobile's binding of
  # deps/rclone-mobile/gomobile/gomobile.go:
  #   func RcloneInitialize()            -> static void rcloneInitialize()
  #   func RcloneFinalize()              -> static void rcloneFinalize()
  #   type RcloneRPCResult{Output string; Status int}
  #                                      -> getOutput():String / getStatus():long
  #                                         (gobind maps Go int to Java long)
  #   func RcloneRPC(m, in string) *RcloneRPCResult
  # Keep these signatures byte-compatible or RcloneBridge.kt stops compiling.
  cat > "$WORK/src/com/openminis/rclone/gomobile/RcloneRPCResult.java" <<'JAVA'
package com.openminis.rclone.gomobile;

/** Stub mirror of the gomobile-generated result type. */
public final class RcloneRPCResult {
    private String output;
    private long status;

    public RcloneRPCResult() {
    }

    public RcloneRPCResult(String output, long status) {
        this.output = output;
        this.status = status;
    }

    public String getOutput() {
        return output;
    }

    public void setOutput(String value) {
        this.output = value;
    }

    public long getStatus() {
        return status;
    }

    public void setStatus(long value) {
        this.status = value;
    }
}
JAVA

  cat > "$WORK/src/com/openminis/rclone/gomobile/Gomobile.java" <<'JAVA'
package com.openminis.rclone.gomobile;

/**
 * Stub of the gomobile rclone binding.
 *
 * This build was produced WITHOUT the real rclone library, so every RPC answers
 * HTTP 501 with a JSON error. RcloneBridge turns a non-200 status into
 * RPCException carrying the `error` field, which the backup UI already surfaces
 * — so remote destinations report "unavailable in this build" instead of
 * crashing, and every other feature is unaffected.
 */
public final class Gomobile {
    private Gomobile() {
    }

    private static final String ERROR_JSON =
        "{\"error\":\"rclone is not bundled in this build; remote backup destinations are unavailable\"}";

    public static void rcloneInitialize() {
    }

    public static void rcloneFinalize() {
    }

    public static RcloneRPCResult rcloneRPC(String method, String input) {
        return new RcloneRPCResult(ERROR_JSON, 501L);
    }
}
JAVA

  javac -source 17 -target 17 -nowarn \
        -d "$WORK/classes" \
        "$WORK/src/com/openminis/rclone/gomobile/RcloneRPCResult.java" \
        "$WORK/src/com/openminis/rclone/gomobile/Gomobile.java" || return 1

  ( cd "$WORK/classes" && jar cf "$WORK/aar/classes.jar" . ) || return 1

  cat > "$WORK/aar/AndroidManifest.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.openminis.rclone">
    <uses-sdk android:minSdkVersion="26" />
</manifest>
XML

  mkdir -p "$WORK/aar/res"
  # An .aar is just a zip: manifest + classes.jar + (empty) res/ + R.txt.
  : > "$WORK/aar/R.txt"
  ( cd "$WORK/aar" && rm -f "$OUT_AAR" && jar cfM "$OUT_AAR" AndroidManifest.xml classes.jar R.txt res ) || return 1
  return 0
}

if [ -f "$OUT_AAR" ] && [ "${FORK_RCLONE_FORCE:-0}" != "1" ]; then
  note "reusing existing $(basename "$OUT_AAR") ($(du -h "$OUT_AAR" | cut -f1))"
  exit 0
fi

case "$MODE" in
  stub)
    build_stub || exit 1
    note "**stub** — remote backup destinations disabled in this APK"
    ;;
  real)
    if build_real; then
      note "real gomobile build ($(du -h "$OUT_AAR" | cut -f1)) — full remote backup support"
    else
      echo "[prepare-rclone] real build failed; falling back to stub" >&2
      build_stub || exit 1
      note "**stub (fallback)** — the gomobile build failed, so remote backup destinations are disabled in this APK. Everything else is unaffected."
    fi
    ;;
  *)
    echo "usage: $0 [real|stub]" >&2
    exit 2
    ;;
esac

ls -lh "$OUT_AAR"
