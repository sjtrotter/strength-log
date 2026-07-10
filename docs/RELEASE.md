# Release build & signing

Steps for cutting a signed release build of `:app`. This is a public repo
(CLAUDE.md data principles) — the signing key never enters it, in any form.
These steps are user-run; an agent has no signing key and shouldn't.

## 1. One-time: create the release keystore

Do this once, on your own machine, and keep the resulting file somewhere
that isn't this repo (a password manager's attachment, an encrypted volume,
whatever you'd trust with a house key):

```
keytool -genkeypair -v \
  -keystore ~/keystores/strength-log-release.jks \
  -alias strength-log \
  -keyalg RSA -keysize 2048 -validity 10000
```

`keytool` will prompt for a store password and a key password (they can be
the same value). Write both down somewhere durable — Play has no "reset
signing key" button; losing this keystore means you can never update the
app under the same listing again.

## 2. One-time per machine: point Gradle at it

`app/build.gradle.kts`'s `release` signing config reads four Gradle
properties and does nothing if they're absent (the build stays unsigned,
which is fine for local `assembleRelease`/CI verification — R8 still runs,
you just can't install or upload the result). Add them to
**`~/.gradle/gradle.properties`** — your per-user Gradle config, outside any
repo, never committed by definition:

```
STRENGTHLOG_RELEASE_STORE_FILE=/home/you/keystores/strength-log-release.jks
STRENGTHLOG_RELEASE_STORE_PASSWORD=...
STRENGTHLOG_RELEASE_KEY_ALIAS=strength-log
STRENGTHLOG_RELEASE_KEY_PASSWORD=...
```

Do **not** put these in the repo's `gradle.properties` (that file is
committed) or in `local.properties` (that's already spoken for — SDK path).
`~/.gradle/gradle.properties` is the standard place Android tooling expects
per-machine secrets like this to live.

## 3. Build

```
JAVA_HOME=/usr/lib/jvm/java-17-temurin-jdk ./gradlew :app:bundleRelease
```

This produces a signed `.aab` at `app/build/outputs/bundle/release/` — the
format Play wants. `:app:assembleRelease` also works if you need a signed
`.apk` (e.g. to sideload the actual release build for the device smoke test
in step 4, instead of the debug-keystore build you've been using for day-to-
day testing).

Both tasks run R8 (`isMinifyEnabled = true`, `isShrinkResources = true`,
`app/proguard-rules.pro`). That's new as of M6 (#23) — earlier milestones
shipped release builds unminified. R8's runtime behavior (as opposed to
"did it build") can only be verified on a device, which is why step 4 below
exists.

## 4. Device smoke test (R8 is on — do this before shipping)

R8 strips/renames anything it can prove is unreferenced, and reflection is
exactly the kind of reference it can't see. `proguard-rules.pro` has keep
rules for our `@Serializable` classes, and Room/Hilt/Compose/Health Connect
each bundle their own consumer rules, but "should be covered" isn't the same
as "verified on-device." Install the signed release build (not a debug
build — the whole point is exercising R8's output) and walk every
reflection-sensitive path once:

- **Wizard finish** — completes onboarding, writes the generated program to
  Room. Exercises kotlinx.serialization (`CardioSerialization`/
  `SetSerialization` TypeConverters) and Room/Hilt DI end to end.
- **Day logging** — log a set, mark it done, add/remove a set. Exercises
  the live Room-backed ViewModel wiring.
- **Backup export → restore** — export a full JSON backup, then restore it.
  Exercises `BackupDocument`'s kotlinx.serialization tree directly — this is
  the path most likely to silently break under R8 if a keep rule is missing
  (it'll fail loudly: either the export throws, or the restore reads back
  wrong/empty data).
- **CSV import** — import a Strong-style CSV, confirm an unmatched-exercise
  pattern, commit the import.
- **Health Connect publish** — connect Health Connect from Setup or Log,
  grant permissions, complete a day, confirm the session shows up in Health
  Connect (or another HC-reading app). Exercises the Health Connect client's
  AIDL-backed calls under R8.

If any of these behave differently than the debug build (crash, silently
empty data, a field that reads back as its default instead of what you
entered), that's a missing keep rule — file it before shipping, don't ship
around it.

## What's out of scope here

- `:wear`'s release build type is still unminified (`isMinifyEnabled =
  false` in `wear/build.gradle.kts`) — not signed or covered by this
  document. It isn't bundled into the phone app's release artifact yet
  either (no `wearApp` dependency wiring); that's a separate task from #23.
- Play listing copy, store screenshots, and the actual Play Console upload
  flow aren't covered here — this document stops at "you have a signed
  `.aab` on disk."
