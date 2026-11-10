# ModMorpher for Android

Convert a Minecraft Java mod into a Bedrock add-on on your phone.

| | |
|---|---|
| App name | ModMorpher |
| Application ID | `modmorpher.app` |
| `namespace` | `modmorpher.app` |
| Min / target SDK | 26 / 34 |
| Language | Kotlin, Material 3, ViewBinding |
| Signing | one keystore for debug **and** release — see [KEYSTORE.md](KEYSTORE.md) |

> **Why `modmorpher.app` and not `modmorpher`?** Android requires an
> `applicationId` with at least two dot-separated segments, so a bare
> `modmorpher` is rejected by the build tools. `modmorpher.app` keeps the name
> you asked for as the leading segment and stays a valid, store-ready ID.

## Two modes — it works with no server at all

Set the mode in **Settings**. It defaults to on-device, so a fresh install is
useful immediately.

### On this device (default, no setup)

Reads the jar and assembles a Bedrock pack right on the phone:

- every texture, grouped into `textures/blocks` and `textures/items`, plus
  generated `terrain_texture.json` / `item_texture.json` atlases
- every sound, plus a generated `sound_definitions.json`
- models and language files (Java `en_us` → Bedrock `en_US`)
- `manifest.json` for a resource pack **and** a behaviour pack, with UUIDs that
  are derived from the mod id — so re-converting updates the pack instead of
  adding a duplicate to the player's list

It also writes a report of exactly what it could not do, shown under **Notes**.

**What it genuinely cannot do on a phone:** translate the mod's Java code.
That needs the jar decompiled with Vineflower, which needs a JVM, which Android
does not have. So entity behaviour, custom blocks/items, scripts and Java model
geometry stay unfinished — and are listed as such rather than silently dropped.

### A server (full conversion)

Runs the real `modmorpher.py` on a computer you control, which also translates
Java logic into Bedrock scripts. Needs Java 17+ on that machine, and it must be
reachable from the phone.

```
 phone (this app)                       computer
 ┌──────────────────┐   POST .jar   ┌─────────────────────────────┐
 │ pick .jar        │ ────────────► │ server/modmorpher_server.py │
 │ poll status      │ ◄──────────── │   └─ runs modmorpher.py     │
 │ download .mcaddon│               │        └─ java -jar Vineflower│
 └──────────────────┘               └─────────────────────────────┘
```

## Build

Requirements: **JDK 17+** and the Android SDK (compileSdk 34). Android Studio
can do this for you; from a terminal:

```bash
cd android
python3 scripts/make_keystore.py    # once, if you have no keystore yet
./gradlew assembleDebug             # app/build/outputs/apk/debug/
./gradlew assembleRelease           # signed with the same keystore
./gradlew test                      # on-device converter tests, no emulator
```

Create `android/local.properties` if Gradle cannot find your SDK:

```properties
sdk.dir=/path/to/Android/Sdk
```

### Build in CI (no local SDK needed)

`.github/workflows/android.yml` builds the APK on GitHub's runners, which come
with a JDK 17 and the Android SDK. Every push to the working branch runs it;
the APK lands under the run's **Artifacts** (kept 30 days).

Without the signing secrets the workflow still builds, but the result is
**unsigned and therefore not installable** — that is deliberate, so CI can never
become a second signing identity. To get an installable APK, add the one
keystore as repository secrets:

```bash
base64 -w0 android/keystore/modmorpher.keystore | gh secret set MODMORPHER_KEYSTORE_B64
gh secret set MODMORPHER_STORE_PASSWORD    # storePassword from keystore.properties
```

then re-run the workflow. It will report `Signed: yes` and `apksigner` will
print the certificate fingerprint.

The escape hatch that permits the unsigned build is
`MODMORPHER_ALLOW_UNSIGNED_CI=true`, set only by the workflow. Nothing about it
weakens the guarantee for normal builds: they still fail without the keystore.

### Checks that run without an SDK

```bash
python3 scripts/check_resources.py   # XML + R.* + ViewBinding + manifest refs
```

### Signing an APK without the Android SDK

If you have a keystore and an APK but no build-tools (`apksigner` lives there),
`scripts/sign_apk_v2.py` applies APK Signature Scheme v2 in pure Python:

```bash
python3 scripts/sign_apk_v2.py --apk app-release-unsigned.apk \
    --keystore ../keystore/modmorpher.keystore \
    --keystore-properties ../keystore/keystore.properties \
    --out app-release-signed.apk
```

It re-reads and verifies its own output before reporting success.

## Using it

1. **Add .jar** → pick a Forge/NeoForge/Fabric mod.
2. On-device mode finishes in seconds. Server mode uploads and polls.
3. **Save** writes the `.mcaddon` wherever you choose; **Share** hands it
   straight to Minecraft; **Notes** shows what still needs doing.

## What it deliberately does not do

- **Translate Java logic on-device.** Needs a JVM (see above).
- **Run in the background.** Server polling happens while the app is open; the
  job list re-syncs on resume.
- **Use TLS by default.** The server is plain `http://` on your LAN, so
  `usesCleartextTraffic` is enabled. Do not expose it to the public internet.
- **Accept texture-pack `.zip` inputs.** Mod jars only.

## Layout

```
android/
├── app/src/main/java/modmorpher/app/
│   ├── MainActivity.kt             list, file picking, save/share
│   ├── MainViewModel.kt            on-device + server conversion, polling
│   ├── SettingsActivity.kt         mode, server address + token
│   ├── data/                       JobRecord, JobStore (JSON), Prefs
│   ├── net/ModMorpherClient.kt     OkHttp client, upload progress
│   ├── offline/OfflineConverter.kt on-device pack builder (pure JVM)
│   └── ui/JobAdapter.kt            job cards
├── app/src/test/                   JUnit tests for the offline converter
├── keystore/                       THE keystore (git-ignored)
└── scripts/                        make_keystore, sign_apk_v2, check_resources
```
