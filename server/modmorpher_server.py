#!/usr/bin/env python3
"""
ModMorpher job server.

The Android app is a thin client: it uploads a mod .jar, the server runs the
real, unmodified modmorpher.py against it, and the app downloads the resulting
Bedrock_Pack.mcaddon.

Why a server at all? Converting needs a JVM (Vineflower decompiles the jar) and
CPython with Pillow/javalang. Neither exists on Android, so the phone cannot do
the work itself.

    pip install -r Requirements.txt      # pillow, javalang, tqdm
    java -version                        # a JRE 17+ must be on PATH
    python3 server/modmorpher_server.py --port 8765

Endpoints (all JSON unless noted; Bearer token required if a token is set):
    GET    /api/health                     server + toolchain status
    GET    /api/jobs                       list jobs, newest first
    POST   /api/jobs                       raw .jar body -> new job
    GET    /api/jobs/<id>                  one job, incl. log tail
    GET    /api/jobs/<id>/download         the .mcaddon (octet-stream)
    GET    /api/jobs/<id>/notes            PORTING_NOTES.txt (text/plain)
    DELETE /api/jobs/<id>                  remove job + its files

Security: this runs a decompiler on files you upload. Run it on a trusted
network, keep the token secret, and do not expose it to the public internet
without TLS and authentication in front of it. Uploads are size-capped and no
code from the jar is ever executed -- Vineflower only reads class files.
"""
from __future__ import annotations

import argparse
import json
import os
import queue
import re
import secrets
import shutil
import signal
import subprocess
import sys
import threading
import time
import traceback
import uuid
import zipfile
from dataclasses import dataclass, field, asdict
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Optional
from urllib.parse import urlparse, unquote

SERVER_VERSION = "1.0.0"
HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.dirname(HERE)
DEFAULT_SCRIPT = os.path.join(REPO_ROOT, "modmorpher.py")

MAX_UPLOAD_BYTES = 200 * 1024 * 1024
LOG_TAIL_BYTES = 6000
ACTIVE_STATES = ("queued", "running")
ANSI_RE = re.compile(r"\x1b\[[0-9;?]*[a-zA-Z]")

# Runs modmorpher.py with cwd set to the job's work dir, while keeping
# __file__ pointing at the real script so it still finds tools/ next to it.
_BOOTSTRAP = (
    "import os, runpy, sys\n"
    "script, work = sys.argv[1], sys.argv[2]\n"
    "os.chdir(work)\n"
    "sys.argv = [script]\n"
    "runpy.run_path(script, run_name='__main__')\n"
)


def sanitize_component(name: str) -> str:
    """Filesystem-safe identifier, used for both dirs and pack namespaces."""
    base = os.path.basename(name or "").strip()
    base = re.sub(r"\.jar$", "", base, flags=re.I)
    base = re.sub(r"[^A-Za-z0-9_.-]+", "_", base).strip("._-")
    return base[:64] or "mod"


def strip_ansi(text: str) -> str:
    return ANSI_RE.sub("", text)


def tail_text(path: str, limit: int = LOG_TAIL_BYTES) -> str:
    try:
        size = os.path.getsize(path)
        with open(path, "rb") as fh:
            if size > limit:
                fh.seek(size - limit)
            return strip_ansi(fh.read().decode("utf-8", "replace"))
    except OSError:
        return ""


def java_version() -> Optional[str]:
    exe = shutil.which("java")
    if not exe:
        return None
    try:
        out = subprocess.run(
            [exe, "-version"], capture_output=True, text=True, timeout=20
        )
        text = (out.stderr or out.stdout or "").strip().splitlines()
        return text[0].strip() if text else "unknown"
    except Exception:
        return "unknown"


@dataclass
class Job:
    id: str
    filename: str = "mod.jar"
    status: str = "queued"       # queued | running | succeeded | failed
    message: str = ""
    created_at: float = field(default_factory=time.time)
    updated_at: float = field(default_factory=time.time)
    started_at: Optional[float] = None
    finished_at: Optional[float] = None
    exit_code: Optional[int] = None
    has_addon: bool = False
    has_notes: bool = False
    size_bytes: int = 0

    def public(self, log_tail: str = "") -> dict:
        data = asdict(self)
        data["log_tail"] = log_tail
        return data


class JobStore:
    """Jobs live in memory and are mirrored to <data>/jobs/<id>/meta.json."""

    def __init__(self, data_dir: str):
        self.data_dir = data_dir
        self.jobs_dir = os.path.join(data_dir, "jobs")
        os.makedirs(self.jobs_dir, exist_ok=True)
        self._lock = threading.RLock()
        self._jobs: dict[str, Job] = {}
        self._load()

    def _load(self) -> None:
        for entry in sorted(os.listdir(self.jobs_dir)):
            meta = os.path.join(self.jobs_dir, entry, "meta.json")
            if not os.path.isfile(meta):
                continue
            try:
                with open(meta, encoding="utf-8") as fh:
                    job = Job(**json.load(fh))
            except Exception:
                continue
            # A job that was mid-flight when the server stopped can never
            # finish, so surface it as failed instead of a zombie "running".
            if job.status in ACTIVE_STATES:
                job.status = "failed"
                job.message = "Server restarted while this job was running."
                job.finished_at = time.time()
                self._save(job)
            self._jobs[job.id] = job

    def job_dir(self, job_id: str) -> str:
        return os.path.join(self.jobs_dir, job_id)

    def addon_path(self, job: Job) -> str:
        return os.path.join(self.job_dir(job.id), "Bedrock_Pack.mcaddon")

    def notes_path(self, job: Job) -> str:
        return os.path.join(self.job_dir(job.id), "PORTING_NOTES.txt")

    def log_path(self, job: Job) -> str:
        return os.path.join(self.job_dir(job.id), "conversion.log")

    def _save(self, job: Job) -> None:
        directory = self.job_dir(job.id)
        os.makedirs(directory, exist_ok=True)
        tmp = os.path.join(directory, "meta.json.tmp")
        with open(tmp, "w", encoding="utf-8") as fh:
            json.dump(asdict(job), fh)
        os.replace(tmp, os.path.join(directory, "meta.json"))

    def create(self, filename: str) -> Job:
        job = Job(id=uuid.uuid4().hex, filename=filename)
        with self._lock:
            self._jobs[job.id] = job
            self._save(job)
        return job

    def get(self, job_id: str) -> Optional[Job]:
        with self._lock:
            return self._jobs.get(job_id)

    def all(self) -> list[Job]:
        with self._lock:
            return sorted(self._jobs.values(), key=lambda j: j.created_at, reverse=True)

    def update(self, job: Job, **fields) -> Job:
        with self._lock:
            for key, value in fields.items():
                setattr(job, key, value)
            job.updated_at = time.time()
            self._save(job)
            return job

    def delete(self, job_id: str) -> bool:
        with self._lock:
            job = self._jobs.pop(job_id, None)
            if job is None:
                return False
        shutil.rmtree(self.job_dir(job_id), ignore_errors=True)
        return True

    def active_count(self) -> int:
        with self._lock:
            return sum(1 for j in self._jobs.values() if j.status in ACTIVE_STATES)


class Converter:
    """Owns the worker threads that actually run modmorpher.py."""

    def __init__(
        self,
        store: JobStore,
        script: str,
        workers: int = 1,
        job_timeout: int = 1800,
        requires_java: bool = True,
    ):
        self.store = store
        self.script = script
        self.job_timeout = job_timeout
        self.requires_java = requires_java
        self.queue: "queue.Queue[str]" = queue.Queue()
        self._stop = threading.Event()
        self._threads = [
            threading.Thread(target=self._worker, daemon=True, name=f"convert-{i}")
            for i in range(max(1, workers))
        ]

    def start(self) -> None:
        for thread in self._threads:
            thread.start()

    def stop(self) -> None:
        self._stop.set()
        for _ in self._threads:
            self.queue.put("")  # poison pill

    def submit(self, job: Job) -> None:
        self.queue.put(job.id)

    def _worker(self) -> None:
        while not self._stop.is_set():
            job_id = self.queue.get()
            if not job_id:
                return
            job = self.store.get(job_id)
            if job is None:
                continue
            try:
                self._run(job)
            except Exception:
                self.store.update(
                    job,
                    status="failed",
                    message="Internal server error.",
                    finished_at=time.time(),
                )
                try:
                    with open(self.store.log_path(job), "a", encoding="utf-8") as fh:
                        fh.write(traceback.format_exc())
                except OSError:
                    pass

    def _run(self, job: Job) -> None:
        job_dir = self.store.job_dir(job.id)
        # modmorpher.py derives the addon's namespace from basename(cwd), so the
        # work dir is named after the mod, not after the job id.
        work_dir = os.path.join(job_dir, sanitize_component(job.filename))
        os.makedirs(work_dir, exist_ok=True)
        upload = os.path.join(job_dir, "upload.jar")
        # Keep the original mod name on the copy: modmorpher.py uses the jar
        # filename for the pack's display name, so "input.jar" would ship a
        # pack literally called "input".
        target = os.path.join(work_dir, os.path.basename(work_dir) + ".jar")

        # Fail fast with a useful message. modmorpher.py hides this reason in
        # PORTING_NOTES, which it never writes when the run aborts this early,
        # so the app would otherwise only show "no addon produced".
        if self.requires_java and java_version() is None:
            self.store.update(
                job,
                status="failed",
                message=(
                    "The server has no Java runtime, so the decompiler cannot run. "
                    "Install a JRE 17+ on the server and restart it."
                ),
                started_at=time.time(),
                finished_at=time.time(),
            )
            with open(self.store.log_path(job), "a", encoding="utf-8") as fh:
                fh.write("\npreflight: no 'java' on PATH; job aborted before conversion.\n")
            shutil.rmtree(work_dir, ignore_errors=True)
            return

        self.store.update(
            job, status="running", started_at=time.time(), message="Converting…"
        )
        shutil.copyfile(upload, target)

        env = os.environ.copy()
        env["MODMORPHER_DEBUG"] = "1"   # modmorpher.py silences stdout otherwise
        env["PYTHONUNBUFFERED"] = "1"
        cmd = [sys.executable, "-c", _BOOTSTRAP, self.script, work_dir]

        log_path = self.store.log_path(job)
        exit_code: Optional[int] = None
        timed_out = False
        with open(log_path, "ab") as log:
            log.write(
                f"\n=== job {job.id} :: {job.filename} :: {time.strftime('%Y-%m-%d %H:%M:%S')} ===\n".encode()
            )
            log.flush()
            proc = subprocess.Popen(
                cmd,
                cwd=work_dir,
                env=env,
                stdout=log,
                stderr=subprocess.STDOUT,
                start_new_session=True,
            )
            try:
                exit_code = proc.wait(timeout=self.job_timeout)
            except subprocess.TimeoutExpired:
                timed_out = True
                self._kill(proc)

        # Outputs are produced inside work_dir, then promoted next to meta.json.
        addon_src = os.path.join(work_dir, "Bedrock_Pack.mcaddon")
        notes_src = os.path.join(work_dir, "PORTING_NOTES.txt")
        addon_dst = self.store.addon_path(job)
        notes_dst = self.store.notes_path(job)
        has_addon = os.path.isfile(addon_src)
        has_notes = os.path.isfile(notes_src)
        if has_addon:
            shutil.move(addon_src, addon_dst)
        if has_notes:
            shutil.move(notes_src, notes_dst)

        size = os.path.getsize(addon_dst) if has_addon else 0

        if timed_out:
            status, message = (
                "failed",
                f"Timed out after {self.job_timeout}s and was stopped.",
            )
        elif has_addon:
            status, message = "succeeded", f"Addon ready ({size / 1024 / 1024:.1f} MB)."
        else:
            status, message = (
                "failed",
                self._failure_message(exit_code, log_path),
            )

        self.store.update(
            job,
            status=status,
            message=message,
            exit_code=exit_code,
            finished_at=time.time(),
            has_addon=has_addon,
            has_notes=has_notes,
            size_bytes=size,
        )
        # The work dir holds a full extracted pack; keep only the artifacts.
        shutil.rmtree(work_dir, ignore_errors=True)
        try:
            os.remove(upload)
        except OSError:
            pass

    @staticmethod
    def _kill(proc: subprocess.Popen) -> None:
        try:
            os.killpg(os.getpgid(proc.pid), signal.SIGTERM)
            proc.wait(timeout=15)
        except Exception:
            try:
                os.killpg(os.getpgid(proc.pid), signal.SIGKILL)
            except Exception:
                pass

    @staticmethod
    def _failure_message(exit_code: Optional[int], log_path: str) -> str:
        tail = tail_text(log_path).lower()
        if (
            "no 'java' executable" in tail
            or "java: command not found" in tail
            or "decompilation failed" in tail
        ):
            return (
                "Decompilation failed: the server's Java runtime or "
                "tools/ClassDecompiler.jar is missing/broken. See the log."
            )
        if "classdecompiler.jar not found" in tail:
            return "Conversion failed: tools/ClassDecompiler.jar is missing."
        if "no .jar file found" in tail:
            return "Conversion failed: the uploaded file was not usable as a jar."
        if exit_code not in (None, 0):
            return f"Conversion failed (exit code {exit_code}). See the log."
        return "Conversion finished but produced no addon."


class Handler(BaseHTTPRequestHandler):
    server_version = f"ModMorpherServer/{SERVER_VERSION}"
    protocol_version = "HTTP/1.1"

    # -- plumbing ---------------------------------------------------------
    @property
    def store(self) -> JobStore:
        return self.server.store  # type: ignore[attr-defined]

    @property
    def converter(self) -> Converter:
        return self.server.converter  # type: ignore[attr-defined]

    @property
    def token(self) -> Optional[str]:
        return self.server.token  # type: ignore[attr-defined]

    def log_message(self, fmt: str, *args) -> None:
        if getattr(self.server, "quiet", False):
            return
        sys.stderr.write(
            f"[{time.strftime('%H:%M:%S')}] {self.address_string()} {fmt % args}\n"
        )

    def _send_json(self, payload: dict, status: int = 200) -> None:
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _error(self, status: int, message: str) -> None:
        # A rejected request may still have an unread body (oversized upload,
        # bad token on a POST). Reusing that socket would desync the next
        # request, so these responses always close the connection.
        self.close_connection = True
        body = json.dumps({"error": message}).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)

    def _authorized(self) -> bool:
        if not self.token:
            return True
        header = self.headers.get("Authorization", "")
        presented = header[7:] if header.startswith("Bearer ") else ""
        if presented and secrets.compare_digest(presented, self.token):
            return True
        self._error(HTTPStatus.UNAUTHORIZED, "Missing or invalid bearer token.")
        return False

    def _send_file(self, path: str, content_type: str, download_name: str) -> None:
        if not os.path.isfile(path):
            self._error(HTTPStatus.NOT_FOUND, "File not available.")
            return
        size = os.path.getsize(path)
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(size))
        self.send_header(
            "Content-Disposition", f'attachment; filename="{download_name}"'
        )
        self.end_headers()
        with open(path, "rb") as fh:
            shutil.copyfileobj(fh, self.wfile)

    # -- routing ----------------------------------------------------------
    def do_GET(self) -> None:
        self._route("GET")

    def do_POST(self) -> None:
        self._route("POST")

    def do_DELETE(self) -> None:
        self._route("DELETE")

    def _route(self, method: str) -> None:
        try:
            parsed = urlparse(self.path)
            parts = [unquote(p) for p in parsed.path.strip("/").split("/") if p]
            if not parts or parts[0] != "api":
                self._error(HTTPStatus.NOT_FOUND, "Unknown endpoint.")
                return
            if not self._authorized():
                return
            parts = parts[1:]
            if method == "GET" and parts == ["health"]:
                self._health()
            elif method == "GET" and parts == ["jobs"]:
                self._list_jobs()
            elif method == "POST" and parts == ["jobs"]:
                self._create_job()
            elif len(parts) == 2 and parts[0] == "jobs":
                job_id = parts[1]
                if method == "GET":
                    self._job_detail(job_id)
                elif method == "DELETE":
                    self._delete_job(job_id)
                else:
                    self._error(HTTPStatus.METHOD_NOT_ALLOWED, "Method not allowed.")
            elif len(parts) == 3 and parts[0] == "jobs" and method == "GET":
                job_id, action = parts[1], parts[2]
                if action == "download":
                    self._download(job_id)
                elif action == "notes":
                    self._notes(job_id)
                else:
                    self._error(HTTPStatus.NOT_FOUND, "Unknown endpoint.")
            else:
                self._error(HTTPStatus.NOT_FOUND, "Unknown endpoint.")
        except Exception:
            traceback.print_exc()
            try:
                self._error(HTTPStatus.INTERNAL_SERVER_ERROR, "Internal server error.")
            except Exception:
                pass

    # -- handlers ---------------------------------------------------------
    def _health(self) -> None:
        self._send_json(
            {
                "status": "ok",
                "server_version": SERVER_VERSION,
                "java": java_version(),
                "script_present": os.path.isfile(self.converter.script),
                "active_jobs": self.store.active_count(),
            }
        )

    def _list_jobs(self) -> None:
        jobs = [
            job.public(tail_text(self.store.log_path(job)))
            for job in self.store.all()
        ]
        self._send_json({"jobs": jobs})

    def _create_job(self) -> None:
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            self._error(HTTPStatus.BAD_REQUEST, "Invalid Content-Length.")
            return
        if length <= 0:
            self._error(HTTPStatus.BAD_REQUEST, "Empty upload.")
            return
        if length > MAX_UPLOAD_BYTES:
            # Drain nothing; the client is told why before we allocate.
            self._error(
                HTTPStatus.REQUEST_ENTITY_TOO_LARGE,
                f"Upload exceeds the {MAX_UPLOAD_BYTES // (1024 * 1024)} MB limit.",
            )
            return

        filename = self.headers.get("X-Filename") or "mod.jar"
        filename = os.path.basename(filename)[:128] or "mod.jar"

        job = self.store.create(filename)
        upload = os.path.join(self.store.job_dir(job.id), "upload.jar")
        os.makedirs(os.path.dirname(upload), exist_ok=True)
        remaining = length
        try:
            with open(upload, "wb") as fh:
                while remaining > 0:
                    chunk = self.rfile.read(min(65536, remaining))
                    if not chunk:
                        break
                    fh.write(chunk)
                    remaining -= len(chunk)
        except Exception:
            self.store.delete(job.id)
            self._error(HTTPStatus.BAD_REQUEST, "Upload interrupted.")
            return

        if not self._looks_like_jar(upload):
            self.store.delete(job.id)
            self._error(
                HTTPStatus.BAD_REQUEST,
                "That file is not a .jar (a zip with class files).",
            )
            return

        self.converter.submit(job)
        self._send_json({"job": job.public()}, HTTPStatus.ACCEPTED)

    @staticmethod
    def _looks_like_jar(path: str) -> bool:
        try:
            with open(path, "rb") as fh:
                if fh.read(4) != b"PK\x03\x04":
                    return False
            with zipfile.ZipFile(path) as zf:
                return any(n.endswith(".class") for n in zf.namelist())
        except (OSError, zipfile.BadZipFile):
            return False

    def _job_detail(self, job_id: str) -> None:
        job = self.store.get(job_id)
        if job is None:
            self._error(HTTPStatus.NOT_FOUND, "No such job.")
            return
        self._send_json(
            {"job": job.public(tail_text(self.store.log_path(job)))}
        )

    def _delete_job(self, job_id: str) -> None:
        if not self.store.delete(job_id):
            self._error(HTTPStatus.NOT_FOUND, "No such job.")
            return
        self._send_json({"deleted": job_id})

    def _download(self, job_id: str) -> None:
        job = self.store.get(job_id)
        if job is None:
            self._error(HTTPStatus.NOT_FOUND, "No such job.")
            return
        name = f"ModMorpher_{sanitize_component(job.filename)}.mcaddon"
        self._send_file(
            self.store.addon_path(job), "application/octet-stream", name
        )

    def _notes(self, job_id: str) -> None:
        job = self.store.get(job_id)
        if job is None:
            self._error(HTTPStatus.NOT_FOUND, "No such job.")
            return
        self._send_file(
            self.store.notes_path(job),
            "text/plain; charset=utf-8",
            f"PORTING_NOTES_{sanitize_component(job.filename)}.txt",
        )


def load_or_make_token(data_dir: str, explicit: Optional[str]) -> Optional[str]:
    """Token precedence: --token, env, then a generated one persisted locally."""
    if explicit:
        return explicit
    env_token = os.environ.get("MODMORPHER_TOKEN")
    if env_token:
        return env_token
    path = os.path.join(data_dir, "token.txt")
    if os.path.isfile(path):
        with open(path, encoding="utf-8") as fh:
            existing = fh.read().strip()
        if existing:
            return existing
    token = secrets.token_urlsafe(32)
    os.makedirs(data_dir, exist_ok=True)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as fh:
        fh.write(token)
    return token


def build_server(args) -> ThreadingHTTPServer:
    store = JobStore(args.data_dir)
    converter = Converter(
        store,
        args.script,
        workers=args.workers,
        job_timeout=args.job_timeout,
        requires_java=getattr(args, "requires_java", True),
    )
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    server.store = store          # type: ignore[attr-defined]
    server.converter = converter  # type: ignore[attr-defined]
    server.token = load_or_make_token(args.data_dir, args.token)  # type: ignore[attr-defined]
    server.quiet = args.quiet     # type: ignore[attr-defined]
    converter.start()
    return server


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="ModMorpher job server for the Android app.")
    parser.add_argument("--host", default=os.environ.get("MODMORPHER_HOST", "0.0.0.0"))
    parser.add_argument("--port", type=int, default=int(os.environ.get("MODMORPHER_PORT", "8765")))
    parser.add_argument("--data-dir", default=os.environ.get("MODMORPHER_DATA", os.path.join(HERE, "data")))
    parser.add_argument("--script", default=os.environ.get("MODMORPHER_SCRIPT", DEFAULT_SCRIPT))
    parser.add_argument("--token", default=None, help="Bearer token; default generated and saved")
    parser.add_argument("--workers", type=int, default=int(os.environ.get("MODMORPHER_WORKERS", "1")))
    parser.add_argument("--job-timeout", type=int, default=int(os.environ.get("MODMORPHER_JOB_TIMEOUT", "1800")))
    parser.add_argument("--quiet", action="store_true")
    parser.add_argument("--no-auth", action="store_true", help="accept unauthenticated requests (trusted LAN only)")

    args = parser.parse_args(argv)
    if args.no_auth:
        args.token = ""
    if not os.path.isfile(args.script):
        parser.error(f"cannot find modmorpher.py at {args.script} (use --script)")

    os.makedirs(args.data_dir, exist_ok=True)
    server = build_server(args)

    java = java_version()
    print(f"ModMorpher server {SERVER_VERSION}")
    print(f"  listening : http://{args.host}:{args.port}")
    print(f"  data      : {os.path.abspath(args.data_dir)}")
    print(f"  script    : {os.path.abspath(args.script)}")
    print(f"  java      : {java or 'NOT FOUND -- conversions will fail, install a JRE'}")
    if server.token:  # type: ignore[attr-defined]
        print(f"  token     : {server.token}")  # type: ignore[attr-defined]
        print("            (paste this into the app's Settings screen)")
    else:
        print("  token     : disabled (--no-auth)")
    print("  Ctrl+C to stop.\n")

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nshutting down…")
    finally:
        server.converter.stop()  # type: ignore[attr-defined]
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
