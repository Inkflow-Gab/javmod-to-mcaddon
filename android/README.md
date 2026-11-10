# ModMorpher for Android

The phone app for ModMorpher. Pick a Java mod `.jar`, the server converts it,
and the add-on comes back as a `.mcaddon` you can save or hand to Minecraft.

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

## The phone cannot convert by itself

Converting a Java mod needs a JVM: `modmorpher.py` decompiles the jar with
Vineflower (`tools/ClassDecompiler.jar`). Android has no JVM, so this app is a
**client**. It uploads the jar to `server/modmorpher_server.py` running on a
computer, polls the job, and downloads the result.

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
```

Create `android/local.properties` if Gradle cannot find your SDK:

```properties
sdk.dir=/path/to/Android/Sdk
```

### Checks that run without an SDK

```bash
python3 scripts/check_resources.py   # XML + R.* + ViewBinding + manifest refs
```

That script exists because this project is often edited without an Android SDK
available; it catches the reference-level mistakes that would otherwise only
surface as aapt errors.

## Using it

1. Start the server on a computer on the same network:
   ```bash
   python3 server/modmorpher_server.py
   ```
   It prints an address and a token.
2. In the app, open **Settings** (toolbar), enter e.g. `192.168.1.10:8765` and
   paste the token, then **Test connection**. It should report the server's
   Java version.
3. **Add .jar** → pick a Forge/NeoForge mod → wait for *SUCCEEDED*.
4. **Save** writes the `.mcaddon` wherever you choose; **Share** hands it
   straight to Minecraft or another app; **Notes** shows the conversion's
   `PORTING_NOTES.txt` — the list of things you still have to fix by hand.

## What it deliberately does not do

- **Convert on the device.** Not possible without a JVM (see above).
- **Run in the background.** Polling happens while the app is open; reopen it
  and the job list re-syncs from the server, including jobs finished while you
  were away.
- **Use TLS.** The default server is plain `http://` on your LAN, so
  `usesCleartextTraffic` is enabled. Do not point this at the public internet;
  put TLS and a real reverse proxy in front if you ever do.
- **Support texture-pack `.zip` yet.** The server accepts mod jars only, and the
  app requires a `.jar` extension.

## Layout

```
android/
├── app/src/main/java/modmorpher/app/
│   ├── MainActivity.kt          list, file picking, save/share
│   ├── MainViewModel.kt         upload, polling, persistence
│   ├── SettingsActivity.kt      server address + token, test connection
│   ├── data/                    JobRecord, JobStore (JSON), Prefs
│   ├── net/ModMorpherClient.kt  OkHttp client, upload progress
│   └── ui/JobAdapter.kt         job cards
├── keystore/                    THE keystore (git-ignored)
└── scripts/                     make_keystore.py, check_resources.py
```
