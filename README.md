# ModMorpher - Java Mod → Bedrock Addon

         Trying to Convert Mods Since June 9th 2025

> This repository is a copy of [Indozilla1234/Modmorpher](https://github.com/Indozilla1234/Modmorpher), imported with its full commit history and tags so it can be kept in sync with upstream.

ModMorpher is an experimental conversion tool. Because Java and Bedrock use fundamentally different modding systems, manual fixes should be expected after conversion.

⚠️ this script will auto-install Python packages (`javalang`, `Pillow`, etc) the first time you run it.

ModMorpher tries to take a Minecraft Java mod and produce a Bedrock add-on (`.mcaddon`). It’s not perfect, sometimes it breaks things, but it usually gets you close enough to tweak manually.

---

## What it Does
- 1. It first calls my custom wrapper for Vineflower(ClassDecompiler.jar) to decompile the mod you provide
- 2. Extracts textures/models/sounds from the jar into the Resource Pack in the /Bedrock_Pack folder
- 3. It cycles through and attempts to convert java logic to its bedrock equivalent
- 4. It then packages everything into Bedrock_Pack.mcaddon, ready for bedrock or tweaking. And deletes /src_[mod]

---

## What Works Best
This is made for **mods made with Forge / NeoForge**.

| Minecraft | loader | how reliable it is |
|---|---|---|
| 1.20.1+ | NeoForge | decent, but expect some manual fixing |
| 1.19.2-1.20.1 | Forge | Best Shot|
| 1.12+ | Forge | Works decently, manual tweaking may be needed. |
| 1.3+ | Forge | experimental |
| 1.20.1+ | Fabric / Quilt | experimental |


---

## Requirements
- Python 3.10+ on your PATH
- OpenJDK 21+ (needed for the decompiler)

---

## How To Run
1. Put your compiled `.jar` in this folder (project root).
2. Run:
   ```bash
   python modmorpher.py
   ```
3. Use or tweak Bedrock_Pack.mcaddon

---

## Keep in Mind
- It installs dependecies automatically. If it fails the first time, try running it again.
- Treat the output as a starting point, not a finished pack.

---



## Android app

This repo also contains an Android app, `ModMorpher` (`modmorpher.app`), plus the
small server it can talk to.

The app works **with no server at all** by default: it reads the mod jar on the
phone and builds a Bedrock pack from its textures, sounds, models and language
files. What a phone cannot do is translate Java *code* — that needs the jar
decompiled by Vineflower, which needs a JVM. Point the app at a server for that
part:

```bash
# on a computer (needs Java 17+ and: pip install -r Requirements.txt)
python3 server/modmorpher_server.py        # prints an address + token

# build the app (needs JDK 17 + Android SDK)
cd android && python3 scripts/make_keystore.py && ./gradlew assembleRelease
```

- [`android/README.md`](android/README.md) — the app, and what it does not do
- [`android/KEYSTORE.md`](android/KEYSTORE.md) — the single signing keystore
- [`server/README.md`](server/README.md) — API, security, systemd unit

## Why The License Stuff is Here
This project is meant to help mod creators & players, not to pirate people’s work.
If you’re converting someone else’s ARR or non-permissive mod, make sure you have permission.


---

## Limitations

ModMorpher does NOT:
- fully convert Java mods into working Bedrock equivalents
- guarantee functional gameplay logic conversion
- support all Forge/Fabric mod features
- handle complex rendering systems perfectly



   
