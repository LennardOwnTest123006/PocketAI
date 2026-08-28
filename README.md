# PocketAI

A complete, privacy-first local AI assistant for Android. PocketAI runs a
quantised language model directly on the phone through `llama.cpp`, so chat,
summarisation, formatting and document Q&A all work with no account, no server
and no internet connection.

Optimised for modern ARM64 handsets, with the **Samsung Galaxy Z Flip6** as the
reference device.

## What it does

- **On-device inference** — GGUF models via llama.cpp with a JNI streaming
  bridge, KV-cache prefix reuse across turns, and immediate cancellation.
- **Runtime CPU dispatch** — ggml is built with `GGML_CPU_ALL_VARIANTS`, so a
  Snapdragon 8 Gen 3 gets the `armv8.6 + i8mm` kernels while older arm64 devices
  fall back to a compatible variant instead of crashing.
- **Model manager** — curated catalogue with size/RAM/storage warnings before
  any download, resumable downloads with pause and cancel, `.gguf` import from
  device storage, custom URLs, and real GGUF header parsing for parameter count
  and quantisation.
- **Real reasoning display** — `<think>` blocks the model actually emits are
  split into a collapsible Thinking panel. Nothing is ever fabricated; a model
  that emits no reasoning shows no panel.
- **Rich rendering** — headings, lists, checklists, quotes, code with syntax
  highlighting, links, and horizontally scrollable tables with measured column
  widths, copy and share.
- **Optional web search** — off by default. When on, results from a keyless
  provider are handed to the local model and the sources are shown.
- **Deep customisation** — 8 themes, per-element text colours with a custom
  colour picker and contrast check, six independent text sizes, spacing, corner
  radius, code themes, table styles, animation level and emoji style.
- **Export/import** — TXT, Markdown, JSON and real paginated PDF.

## Building

The Android SDK, NDK and Google's Maven repository are required.

```bash
./scripts/fetch_llama_cpp.sh      # pinned llama.cpp checkout (not vendored)
./gradlew assembleRelease
```

Release signing is read from the environment and never committed:

| Variable | Purpose |
| --- | --- |
| `POCKETAI_KEYSTORE` | path to the keystore |
| `POCKETAI_KEYSTORE_PASSWORD` | store password |
| `POCKETAI_KEY_ALIAS` | key alias |
| `POCKETAI_KEY_PASSWORD` | key password |

Without them the release build still completes, just unsigned.

Add `-Ppocketai.vulkan=true` to also build the ggml Vulkan backend. PocketAI
detects available backends at runtime and falls back to CPU when a GPU driver
is missing, so a Vulkan-less device never crashes.

CI (`.github/workflows/build-apk.yml`) runs the unit tests, builds the release
APK, verifies its signature, ABI and native libraries, and uploads
`PocketAI.apk` as a build artifact.

## Architecture

```
llm/        JNI bindings, inference engine, session control, prompts, reasoning parser
data/db     Room storage for conversations and messages
data/model  GGUF parsing, catalogue, repository, resumable downloader
data/repo   DataStore-backed settings and chat domain models
web/        optional keyless search
doc/        local document text extraction
export/     TXT / Markdown / JSON / PDF
ui/         Compose screens, theme system, Markdown and table renderers
cpp/        pocketai_llm.cpp - the streaming llama.cpp bridge
```

## Privacy

No account, no analytics SDK, no advertising SDK, no tracking. Conversations
and models live in app-private storage. The only outbound traffic is a model
download you start, or a web search you explicitly enable — both surfaced in
the in-app Privacy Center.

## Licence

PocketAI's own source is provided as-is. Bundled open-source components keep
their own licences (llama.cpp/ggml — MIT; AndroidX, Compose, Kotlin, OkHttp —
Apache 2.0; jsoup — MIT). Model weights carry the licence shown in the model
manager.
