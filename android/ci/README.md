# CI workflow — one manual step required

`android.yml` in this directory is the GitHub Actions workflow for Option B
(CI-based Android builds, see `ANDROID_NATIVE_PLAN.md` section 1.3).

It is parked here rather than at `.github/workflows/android.yml` because the
GitHub App used to push this branch does not hold the `workflows` permission, so
any push that creates or edits a file under `.github/workflows/` is rejected
outright by the server — the whole push fails, not just that file.

## Activating it

Move it in a local clone and push with your own credentials:

```sh
git fetch origin
git checkout arena/01a06b40-audix
mkdir -p .github/workflows
git mv android/ci/android.yml .github/workflows/android.yml
git commit -m "ci: enable Candela workflow"
git push origin arena/01a06b40-audix
```

…or create the file through the GitHub web UI (Actions → New workflow → paste
the contents of `android.yml`). Either way it becomes active immediately;
nothing else in the repository needs to change, and no path inside the workflow
refers to its own location.

## What it does

| Job | Runs on | Purpose |
|---|---|---|
| `golden-vectors` | every push/PR | `tsc --noEmit`, regenerates the golden vectors and **fails if a single byte changed** — catches both TS protocol drift and non-deterministic generation. Also asserts `/src` is unmodified. |
| `kotlin-verify` | after the above | JDK 17 + Gradle for `gradle verify` (`:core-protocol:test` + `:core-vision:test`), then `kotlinc` for the four pure suites: Stage 4/6 camera+render, Stage 7 thermal, Stage 8 SAS/export. **469 assertions total.** |
| `apk` | after the above | Builds and uploads a debug APK. Retains a probe for `android/app/build.gradle.kts`; that file now exists, so the full APK path is live. |

## First-run expectations

`kotlin-verify`'s pure-Kotlin steps are the same `main()` entry points that pass
in the sandbox, so those should be green immediately.

The **`apk` job has never been executed anywhere** — the development sandbox has
no Android SDK and cannot reach `dl.google.com`, Maven Central or
`services.gradle.org`. Its first run is therefore the first time any
`android.*`-importing file meets a compiler:

- `:optical-camera` `src/main` — Camera2/ImageReader layer
- `:optical-render` `src/main` — SurfaceView layer
- `:platform` — `MediaStoreExporter`, `ThermalMonitor`, `SessionWakeLock`
- `:app` — Compose shell, `MainActivity`

Ordinary compile errors there (import paths, API-level guards, Compose compiler
version alignment) are expected and are not a design problem. Everything those
files *decide* is already verified on a bare JVM.

## Running the same checks locally

`./android/verify-local.sh` runs the identical assertions with nothing but
`kotlinc` and a JRE — no Gradle, no Maven, no Android SDK. CI and the sandbox
invoke the same `main()` entry points against the same golden vectors, so there
is no second copy of the test logic to drift.
