# The one keystore

ModMorpher the app is signed with **exactly one** signing identity, used for
both `debug` and `release`. There is no second keystore, and no fallback to
Gradle's auto-generated `~/.android/debug.keystore`.

| | |
|---|---|
| File | `android/keystore/modmorpher.keystore` (PKCS#12) |
| Config | `android/keystore/keystore.properties` |
| Alias | `modmorpher` |
| Key | RSA 4096, SHA-256, valid 100 years |
| In git? | **No** — both files are git-ignored |

## Why it is enforced

Android identifies an app by `applicationId` **plus** signing certificate. If a
different keystore ever signs `modmorpher.app`, the device treats it as a
different app: installing over the old one fails with *"App not installed"*, and
a published build can never be updated again — only republished as a new app
with a new package name, losing every install.

So `app/build.gradle.kts` **fails the build** if the keystore is missing rather
than quietly signing with something else. If you see

> Missing the ModMorpher signing keystore.

that is the guard working as designed.

## Generating it (first time only)

```bash
cd android
python3 scripts/make_keystore.py        # needs: pip install cryptography
```

This writes the keystore plus `keystore.properties` with a random 28-character
password, both mode `0600`. No JDK is required — it writes PKCS#12 directly.

The equivalent with a JDK, if you prefer a familiar tool:

```bash
keytool -genkeypair -v -keystore keystore/modmorpher.keystore \
        -storetype PKCS12 -alias modmorpher -keyalg RSA -keysize 4096 \
        -validity 36500 -dname "CN=ModMorpher, O=ModMorpher, C=PH"
```

If you use `keytool`, also write `keystore.properties` yourself:

```properties
storeFile=modmorpher.keystore
storePassword=<the password you chose>
keyAlias=modmorpher
keyPassword=<same password>
storeType=PKCS12
```

## Back it up

**Losing these two files is unrecoverable.** No reset, no support, no
re-download. Back them up somewhere that is not this repository: a password
manager's file storage or an encrypted archive.

Do **not** re-run the generator with `--force` once you have shipped a build.
`make_keystore.py` refuses to overwrite without that flag for this reason.

## Building on another machine or CI

The CI workflow uses these exact secret names:
`MODMORPHER_KEYSTORE_B64` and `MODMORPHER_STORE_PASSWORD`. See
`.github/workflows/android.yml`. Until they exist, CI produces an *unsigned*
APK rather than inventing a second key.

Copy `modmorpher.keystore` and `keystore.properties` into `android/keystore/`
before building. In CI, store the keystore as a base64 secret and reconstruct
it at build time:

```bash
echo "$MODMORPHER_KEYSTORE_B64" | base64 -d > android/keystore/modmorpher.keystore
printf 'storeFile=modmorpher.keystore\nstorePassword=%s\nkeyAlias=modmorpher\nkeyPassword=%s\nstoreType=PKCS12\n' \
  "$MODMORPHER_STORE_PASSWORD" "$MODMORPHER_KEY_PASSWORD" \
  > android/keystore/keystore.properties
chmod 600 android/keystore/*
```

Never echo the secrets, and never commit the reconstructed files — the ignore
rules already cover them.

## Verifying a build really used it

```bash
# SHA-256 of the signing certificate, for the APK and for the keystore
apksigner verify --print-certs android/app/build/outputs/apk/release/app-release.apk
keytool -list -v -keystore android/keystore/modmorpher.keystore -alias modmorpher
```

The two fingerprints must match. Debug and release APKs of this project should
report the **same** certificate — that is what "one keystore" means in practice.
