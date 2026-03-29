# DrawView

DrawView is a small Android drawing stack built around **Jetpack Compose** and **AndroidX Ink** (`androidx.ink`). The repository contains:

- `:draw-view` — the reusable drawing/document module (Compose UI + Ink rendering + persistence)
- `:app` — a sample app that wires navigation and hosts the module

If you’re building anything that needs pen/highlighter/eraser input with a “document + pages” model behind it, the `:draw-view` module is the part you’ll want.

## What you get

- **Ink-based drawing** using AndroidX Ink (low-latency strokes)
- **Tools**: pen, highlighter, eraser, lasso selection (UI and logic live in the module)
- **Undo / redo** (history stack)
- **Documents & pages** (create, rename, delete; multi-page support in the module)
- **Persistence**:
    - local database via **Room**
    - JSON serialization via **kotlinx.serialization**
- **Pan & zoom** gestures inside the drawing surface
- A **ready-to-run sample app** showing how to host the module

## Requirements

- Android Studio (recent version recommended)
- **minSdk 29**
- **compileSdk / targetSdk 36** (as configured in this repo)
- Kotlin toolchain: the module uses a **JVM toolchain 21** configuration

## Run the sample

1. Clone:
   ```bash
   git clone https://github.com/valerioisufi/DrawView.git
   ```
2. Open the project in Android Studio.
3. Run the `app` configuration on a device/emulator (Android 10+).

The launcher activity is `MainActivity`, which shows the document list. Opening a document starts `DrawActivity`.

## Using the module in your own app

This repo is currently set up as a multi-module project, so the simplest way to integrate is to **include the module** (or copy it into your project) and depend on it:

```kotlin
dependencies {
    implementation(project(":draw-view"))
}
```

### Entry points (Compose)

The module exposes two main Composables meant to be hosted by the parent app:

#### Document list

Use this when you want the module to handle document management UI, and your app to decide how navigation works.

```kotlin
DocumentListRoute(
    onNavigateToDocument = { documentId ->
        // navigate to your drawing screen / activity, passing documentId
        // documentId == -1 means "create new"
    }
)
```

#### Drawing screen

This is the drawing UI. You pass a document id and handle “back” navigation.

```kotlin
DrawRoute(
    documentId = documentId,
    onNavigateBack = { /* close screen */ }
)
```

### Navigation model

DrawView intentionally doesn’t own app navigation. The parent app decides:

- how to open a document (Activity, NavHost, etc.)
- what “back” means (finish activity, pop back stack, etc.)

The sample app demonstrates this with:
- `MainActivity` → hosts `DocumentListRoute`
- `DrawActivity` → hosts `DrawRoute`

## Project structure (high level)

- `draw-view/src/main/java/com/studiomath/drawview/`
    - `DocumentListRoute.kt`, `DrawRoute.kt` — module entry points
    - `document/` — drawing engine, view-model, tools, selection, rendering, history, IO
    - `data/` — Room + repository layer
    - `ui/` — Compose components and theming
- `app/` — sample app that depends on `:draw-view`

## Notes

- The module uses `androidx.ink` artifacts (currently configured as `1.1.0-alpha01` in the version catalog).
- This repository doesn’t currently publish `:draw-view` to Maven Central / GitHub Packages — integration is by module inclusion.

## License

MIT — see `LICENSE`.