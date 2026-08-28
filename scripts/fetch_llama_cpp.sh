#!/usr/bin/env bash
# Fetches the pinned llama.cpp release used by PocketAI's native inference layer.
# The sources are intentionally not vendored into git.
set -euo pipefail

LLAMA_CPP_TAG="${LLAMA_CPP_TAG:-b6100}"
REPO_URL="https://github.com/ggml-org/llama.cpp.git"
TARGET_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/app/src/main/cpp/llama.cpp"

if [ -d "$TARGET_DIR/.git" ]; then
  echo "llama.cpp already present at $TARGET_DIR"
  current="$(git -C "$TARGET_DIR" describe --tags --exact-match 2>/dev/null || echo unknown)"
  if [ "$current" = "$LLAMA_CPP_TAG" ]; then
    echo "Already at $LLAMA_CPP_TAG, nothing to do."
    exit 0
  fi
  echo "Switching from $current to $LLAMA_CPP_TAG"
  git -C "$TARGET_DIR" fetch --depth 1 origin "refs/tags/$LLAMA_CPP_TAG:refs/tags/$LLAMA_CPP_TAG"
  git -C "$TARGET_DIR" checkout -f "$LLAMA_CPP_TAG"
  exit 0
fi

echo "Cloning llama.cpp $LLAMA_CPP_TAG into $TARGET_DIR"
mkdir -p "$(dirname "$TARGET_DIR")"
git clone --depth 1 --branch "$LLAMA_CPP_TAG" "$REPO_URL" "$TARGET_DIR"
echo "Done."
