# CI workflow — one manual step required

`android.yml` in this directory is the GitHub Actions workflow for Option B
(CI-based Android builds, see `ANDROID_NATIVE_PLAN.md` section 1.3).

It is parked here rather than at `.github/workflows/android.yml` because the
GitHub App used to push this branch does not hold the `workflows` permission, so
any push that creates or edits a file under `.github/workflows/` is rejected
outright by the server.

## Activating it

Either move it in a local clone and push with your own credentials:

```sh
mkdir -p .github/workflows
git mv android/ci/android.yml .github/workflows/android.yml
git commit -m "ci: enable Candela workflow"
git push
```

…or create the file through the GitHub web UI (Actions → New workflow → paste
the contents of `android.yml`). Either way it becomes active immediately; nothing
else in the repository needs to change.

## What it does

| Job | Runs on | Purpose |
|---|---|---|
| `golden-vectors` | every push/PR | `tsc --noEmit`, regenerates the golden vectors and **fails if a single byte changed** — catches both TS protocol drift and non-deterministic generation. Also asserts `/src` is unmodified. |
| `kotlin-verify` | after the above | JDK 17 + Gradle, runs `gradle verify` = `:core-protocol:test` + `:core-vision:test`. This is the byte-parity gate. |
| `apk` | after the above | Probes for `android/app/build.gradle.kts`; **skips cleanly** while Stage 3+ is unbuilt, then builds and uploads a debug APK once the app module lands. |

The `apk` job is deliberately a no-op today so CI is green on real status rather
than red on work that has not started yet.

## Running the same checks locally

`./android/verify-local.sh` runs the identical assertions with nothing but
`kotlinc` and a JRE — no Gradle, no Maven, no Android SDK. CI and the sandbox
invoke the same `main()` entry points against the same golden vectors, so there
is no second copy of the test logic to drift.
