#!/usr/bin/env bash
# [FORK] Minimal `shasum` stand-in for Linux CI.
#
# deps/build_proot.sh verifies the vendored Termux loaders with
# `shasum -a 256 <file>`. That command is standard on macOS (where upstream
# develops) but is not guaranteed on a Linux runner image. Map it onto
# coreutils' sha256sum, keeping the "<hash>  <path>" output shape the script's
# `awk '{print $1}'` expects.
set -euo pipefail

algo=256
args=()
while [ $# -gt 0 ]; do
  case "$1" in
    -a) algo="$2"; shift 2 ;;
    -a*) algo="${1#-a}"; shift ;;
    *) args+=("$1"); shift ;;
  esac
done

case "$algo" in
  1)   exec sha1sum "${args[@]}" ;;
  224) exec sha224sum "${args[@]}" ;;
  256) exec sha256sum "${args[@]}" ;;
  384) exec sha384sum "${args[@]}" ;;
  512) exec sha512sum "${args[@]}" ;;
  *)   echo "shasum-shim: unsupported algorithm $algo" >&2; exit 2 ;;
esac
