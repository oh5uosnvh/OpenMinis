#!/usr/bin/env bash
# [FORK] Fetch the brand icons the model list needs and convert them to Android
# VectorDrawables.
#
# Source: lobehub/lobe-icons (MIT) — the same icon set kelivo bundles, taken
# from upstream rather than copied out of kelivo, so the provenance is a
# permissive licence and not an AGPL project's asset folder.
#
# Output: src/android/app/src/main/res/drawable/fork_brand_<name>.xml
#
# The icons ARE COMMITTED. They are tiny (a few KB of path data each), they must
# exist for a release build to render correctly, and re-fetching at build time
# would make the APK depend on a third-party CDN being up. Regenerate with this
# script when adding a vendor.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/src/android/app/src/main/res/drawable"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

BASE="https://raw.githubusercontent.com/lobehub/lobe-icons/master/packages/static-svg/icons"

# name:source — `name` becomes fork_brand_<name>.xml and is what
# ForkBrandIcons.kt matches against. Colour variants are preferred where the
# vendor has one, since these render on both light and dark surfaces.
ICONS="
openai:openai
claude:claude-color
anthropic:anthropic
gemini:gemini-color
google:google-color
grok:grok
xai:xai
deepseek:deepseek-color
qwen:qwen-color
kimi:kimi-color
zhipu:zhipu-color
doubao:doubao-color
minimax:minimax-color
mistral:mistral-color
meta:meta-color
gemma:gemma-color
hunyuan:hunyuan-color
stepfun:stepfun
baichuan:baichuan-color
yi:zeroone-color
internlm:internlm-color
cohere:cohere-color
perplexity:perplexity-color
ollama:ollama
openrouter:openrouter
alibabacloud:alibabacloud-color
bytedance:bytedance-color
moonshot:moonshot
spark:spark-color
wenxin:wenxin-color
nvidia:nvidia-color
microsoft:microsoft-color
flux:flux
stability:stability-color
midjourney:midjourney
sora:sora-color
codex:codex
"

mkdir -p "$OUT"
ok=0; skip=0

for entry in $ICONS; do
  name="${entry%%:*}"
  src="${entry##*:}"
  url="$BASE/${src}.svg"
  if ! curl -fsSL "$url" -o "$TMP/$src.svg" 2>/dev/null; then
    echo "  MISS $src (no such icon upstream)" >&2
    skip=$((skip + 1))
    continue
  fi
  if python3 "$ROOT/scripts/fork/svg2vector.py" \
       "$TMP/$src.svg" "$OUT/fork_brand_${name}.xml"; then
    ok=$((ok + 1))
  else
    skip=$((skip + 1))
  fi
done

echo "converted $ok icon(s), skipped $skip"
ls -1 "$OUT" | grep '^fork_brand_' | sed 's/^/  /'
