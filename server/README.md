# ModMorpher job server

A small HTTP service that runs the real, unmodified `modmorpher.py` for the
Android app: upload a mod `.jar`, poll the job, download the `.mcaddon`.

It exists because the conversion needs a JVM and CPython with Pillow/javalang —
neither of which exists on Android.

## Requirements

- Python 3.9+ with the repo's dependencies: `pip install -r Requirements.txt`
- A **JRE 17+** on `PATH` (`java -version` must work) — Vineflower needs it
- `tools/ClassDecompiler.jar`, which ships with this repo

## Run

```bash
pip install -r Requirements.txt
python3 server/modmorpher_server.py
```

```
ModMorpher server 1.0.0
  listening : http://0.0.0.0:8765
  data      : /…/server/data
  script    : /…/modmorpher.py
  java      : openjdk version "21.0.1" 2023-10-17
  token     : 9f3c…   (paste this into the app's Settings screen)
```

Enter `http://<computer-ip>:8765` and that token in the app. The token is
generated once and reused from `server/data/token.txt` on later starts.

### Options

| Flag | Env | Default | Meaning |
|---|---|---|---|
| `--host` | `MODMORPHER_HOST` | `0.0.0.0` | bind address |
| `--port` | `MODMORPHER_PORT` | `8765` | bind port |
| `--data-dir` | `MODMORPHER_DATA` | `server/data` | jobs, uploads, token |
| `--script` | `MODMORPHER_SCRIPT` | `modmorpher.py` | converter to run |
| `--workers` | `MODMORPHER_WORKERS` | `1` | parallel conversions |
| `--job-timeout` | `MODMORPHER_JOB_TIMEOUT` | `1800` | seconds before a job is killed |
| `--token` | `MODMORPHER_TOKEN` | generated | bearer token |
| `--no-auth` | — | off | accept unauthenticated requests |

## API

Every endpoint except none requires `Authorization: Bearer <token>`.

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/health` | server version, Java version, active jobs |
| `GET` | `/api/jobs` | all jobs, newest first, each with `log_tail` |
| `POST` | `/api/jobs` | raw `.jar` body; optional `X-Filename` header |
| `GET` | `/api/jobs/<id>` | one job |
| `GET` | `/api/jobs/<id>/download` | the `.mcaddon` |
| `GET` | `/api/jobs/<id>/notes` | `PORTING_NOTES.txt` |
| `DELETE` | `/api/jobs/<id>` | delete job and its files |

```bash
curl -H "Authorization: Bearer $TOKEN" http://localhost:8765/api/health
curl -X POST -H "Authorization: Bearer $TOKEN" -H "X-Filename: mymod.jar" \
     --data-binary @mymod.jar http://localhost:8765/api/jobs
curl -H "Authorization: Bearer $TOKEN" \
     -o addon.mcaddon http://localhost:8765/api/jobs/<id>/download
```

A job moves `queued → running → succeeded | failed`. Uploads are capped at
200 MB and must be a zip containing `.class` files.

## How it wraps the converter

`modmorpher.py` has no CLI; it scans the current directory for a jar and writes
`Bedrock_Pack.mcaddon` there. And it names the pack after `basename(cwd)`. So
each job gets a work directory named after the mod, and the converter is invoked
with `cwd` set to it via `runpy`, while `__file__` still points at the real
script so it can find `tools/`:

```python
os.chdir(work); sys.argv = [script]; runpy.run_path(script, run_name="__main__")
```

Artifacts are then moved next to `meta.json` and the bulky work tree is deleted.

Two deliberate deviations from the script's own behaviour:

- `MODMORPHER_DEBUG=1` is set, because otherwise it silences `print()`, and the
  server would have no log to show you.
- If `java` is missing the job fails immediately with a clear message.
  `modmorpher.py` buries that reason in `PORTING_NOTES.txt`, which it does not
  write when it aborts this early.

## Security

- The server decompiles the jars you upload. It never *executes* mod code.
- Keep it on a trusted network. Behind a router, forward nothing.
- For remote access, put HTTPS (nginx/Caddy) and real authentication in front;
  the built-in token is a shared secret, not user auth.
- Job files live under `--data-dir`; delete jobs you no longer need, and note
  that uploads and outputs can be large.

## Run it as a service

```ini
# /etc/systemd/system/modmorpher.service
[Unit]
Description=ModMorpher job server
After=network.target

[Service]
User=modmorpher
WorkingDirectory=/opt/javmod-to-mcaddon
ExecStart=/usr/bin/python3 server/modmorpher_server.py --data-dir /var/lib/modmorpher
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

## Tests

```bash
python3 -m unittest discover -s server/tests -v
```

19 tests cover the HTTP API, auth, upload validation, the job lifecycle,
artifact download, failure reporting and restart recovery. They use a stub
converter, so **no Java is needed** to run them.
