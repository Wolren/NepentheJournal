# Google Play Publishing Guide

Nepenthe Journal can ship to Google Play via the same release build that
feeds F-Droid, but the decision to actually publish there is separate:
the Play review policy contains an "Unapproved Substances" clause that is
the main approval risk for this app category. The pipeline below targets
the **internal testing track** first, which keeps the app out of the public
store until you decide the framing passes review.

## Pipeline overview

```
tag v* / manual trigger
  -> .github/workflows/play.yml
  -> restore secrets (keystore + service account)
  -> ./gradlew :composeApp:bundleRelease   (signed AAB)
  -> ./gradlew :composeApp:publishBundle   (GPP -> internal track)
  -> promote to closed/production manually in Play Console
```

F-Droid compatibility is preserved: the release signing config is only
active when `keystore.properties` exists. F-Droid builds from source
without secrets and signs with its own key.

## One-time setup (you do this, in Play Console)

1. **Create the app** in Play Console with package name `app.journal.nepenthe`.
2. **Generate the upload keystore** (keep it safe; it is the key to your
   app identity for uploads — Play App Signing then manages the actual
   signing key):

```bash
keytool -genkeypair -v -keystore nepenthe-upload.keystore \
  -alias nepenthe -keyalg RSA -keysize 4096 -validity 10000
```

3. **Create `keystore.properties`** from `keystore.properties.example`
   (gitignored).
4. **Create a service account**:
   - Play Console -> Setup -> API access -> Create service account
   - Grant the account "Release to production, testing tracks, and
     app bundles" permission (or narrower: "Release to testing tracks")
   - Download the JSON key -> base64 it
5. **Configure GitHub secrets** (repo Settings -> Secrets and variables):

| Secret | Value |
|--------|-------|
| `PLAY_SERVICE_ACCOUNT_JSON` | base64 of the service account JSON |
| `KEYSTORE_BASE64` | base64 of `nepenthe-upload.keystore` |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | `nepenthe` (or your alias) |
| `KEY_PASSWORD` | key password |

## Local verification (needs Android SDK + keystore)

```bash
./gradlew :composeApp:bundleRelease
./gradlew :composeApp:publishBundle --track internal
# metadata upload (listings, screenshots) rides along from fastlane/metadata/android
```

`./gradlew help --task publishBundle` lists all CLI options
(e.g. `--track closed`, `--release-name`).

## Track strategy

| Track | Use |
|-------|-----|
| `internal` | CI default; instant distribution to up to 100 internal testers, no review wait |
| `closed` | opt-in testers; reviewed |
| `open` | public beta; reviewed |
| `production` | public release; reviewed |

The internal track is the safe sandbox: iterate on the AAB + metadata
there until the store listing is complete, then attempt a review-bound
track as the real compliance test.

## Review-risk framing (the "promote vs journal" line)

Google's Unapproved Substances policy distinguishes apps that *promote*
substances from apps that provide a *journal with harm reduction context*.
Arguments that support approval:

- App explicitly disclaims medical purpose (built into the Safer screen)
- Content is user-generated journaling, not a purchase/referral mechanism
- Harm reduction framing throughout (testing guides, dosing protocol,
  recovery position, external resources)
- No ads, no in-app purchases, no affiliate links

If Play rejects the app, F-Droid remains the distribution channel and the
rejection is a data point, not a blocker: the pipeline costs nothing to keep.

## Versioning

- Bump `versionCode` (monotonic) + `versionName` in `composeApp/build.gradle.kts`
- Tag `v<versionName>` — the workflow runs on tag push
- Keep the same tag for F-Droid (`docs/FDROID.md` AutoUpdateMode: Version)

## Decision log

- 2026-07-31: Play pipeline added (GPP 4.0.0, conditional signing, internal
  track default). F-Droid remains primary; Play is opportunistic pending
  review outcome.
