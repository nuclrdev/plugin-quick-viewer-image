# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

A **Nuclr Commander** core plugin that provides quick-view (preview) functionality for image files. It implements the `QuickViewPlugin` interface from the `plugins-sdk` and renders images in a Swing `JPanel` with aspect-ratio-preserving scaling.

Supported formats: jpg, jpeg, png, gif, bmp, webp, svg.

## Build Commands

```bash
# Full build (compile, package JAR, assemble ZIP)
mvn clean package

# Build and deploy to local Nuclr Commander plugins directory
deploy.bat
```

The build produces `target/quick-view-image-1.0.0.zip` — a plugin archive containing the plugin JAR, dependencies in `lib/`, `plugin.json`, LICENSE, and README.

JAR signing runs during the `verify` phase and requires the keystore password via `-Djarsigner.storepass=<password>`.

## Architecture

**Two source files, single responsibility each:**

- **`ImageQuickViewerCorePlugin`** — Plugin entry point implementing `QuickViewPlugin`. Lazy-initializes the panel and delegates all work to `ImageViewPanel`. The `pluginClass` in `plugin.json` points here.

- **`ImageViewPanel`** — Swing `JPanel` subclass that handles image loading (`ImageIO.read`) and rendering. Contains the supported format list (`IMAGE_EXTENSIONS`), scaling logic (fit-contain, no upscaling), and quality rendering hints. Uses Lombok `@Data` and `@Slf4j`.

**Plugin lifecycle:** The Nuclr framework calls `getQuickViewPanel()` to obtain the UI component, `canQuickView(File)` to check format support, `quickView(File)` to load/display, and `destroy()` to clean up.

## Build System Details

- **Maven** project, Java 21
- Key dependencies: `plugins-sdk` (plugin interface), `imageio-webp` (WebP support via TwelveMonkeys), `commons-io` (FilenameUtils), Lombok (compile-time)
- Assembly descriptor at `src/assembly/plugin.xml` controls ZIP packaging
- No test framework is configured
