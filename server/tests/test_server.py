"""Tests for the ModMorpher job server.

Run:  python3 -m unittest discover -s server/tests -v

These use a stub converter script so they exercise the HTTP API, job
lifecycle, upload validation and artifact download without needing Java.
"""
from __future__ import annotations

import http.client
import io
import json
import os
import sys
import tempfile
import threading
import time
import unittest
import zipfile
from types import SimpleNamespace

HERE = os.path.dirname(os.path.abspath(__file__))
SERVER_DIR = os.path.dirname(HERE)
sys.path.insert(0, SERVER_DIR)

import modmorpher_server as srv  # noqa: E402

TOKEN = "test-token-123"
STUB = """\
import os, time
time.sleep(0.2)
with open('Bedrock_Pack.mcaddon', 'wb') as fh:
    fh.write(b'MCADDON-BYTES')
with open('PORTING_NOTES.txt', 'w') as fh:
    fh.write('nothing to report\\n')
print('stub conversion done')
"""

FAIL_STUB = """\
raise SystemExit(3)
"""


def make_jar(classes=("Example.class",)) -> bytes:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        for name in classes:
            zf.writestr(name, b"\xca\xfe\xba\xbe")
        zf.writestr("pack.mcmeta", "{}")
    return buf.getvalue()


class ServerTestCase(unittest.TestCase):
    script_body = STUB
    requires_java = False  # stub scripts need no JVM

    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.script = os.path.join(self.tmp.name, "stub_modmorpher.py")
        with open(self.script, "w", encoding="utf-8") as fh:
            fh.write(self.script_body)

        args = SimpleNamespace(
            host="127.0.0.1",
            port=0,
            data_dir=os.path.join(self.tmp.name, "data"),
            script=self.script,
            token=TOKEN,
            workers=1,
            job_timeout=30,
            quiet=True,
            requires_java=self.requires_java,
        )
        self.server = srv.build_server(args)
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.server.converter.stop()  # type: ignore[attr-defined]

    # -- helpers ----------------------------------------------------------
    def request(self, method, path, body=None, headers=None):
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=30)
        hdrs = {"Authorization": f"Bearer {TOKEN}"}
        if headers:
            hdrs.update(headers)
        conn.request(method, path, body=body, headers=hdrs)
        resp = conn.getresponse()
        data = resp.read()
        conn.close()
        return resp.status, data, dict(resp.getheaders())

    def json_request(self, method, path, body=None, headers=None):
        status, data, _ = self.request(method, path, body, headers)
        return status, json.loads(data) if data else {}

    def upload(self, payload=None, name="examplemod-1.0.jar"):
        payload = payload if payload is not None else make_jar()
        return self.json_request(
            "POST",
            "/api/jobs",
            body=payload,
            headers={
                "Content-Type": "application/octet-stream",
                "X-Filename": name,
                "Content-Length": str(len(payload)),
            },
        )

    def wait_for_job(self, job_id, timeout=30):
        deadline = time.time() + timeout
        while time.time() < deadline:
            status, payload = self.json_request("GET", f"/api/jobs/{job_id}")
            self.assertEqual(status, 200)
            job = payload["job"]
            if job["status"] in ("succeeded", "failed"):
                return job
            time.sleep(0.1)
        self.fail(f"job {job_id} did not settle in {timeout}s")


class HealthAndAuthTests(ServerTestCase):
    def test_health_reports_status(self):
        status, payload = self.json_request("GET", "/api/health")
        self.assertEqual(status, 200)
        self.assertEqual(payload["status"], "ok")
        self.assertTrue(payload["script_present"])
        self.assertIn("java", payload)

    def test_missing_token_is_rejected(self):
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        conn.request("GET", "/api/jobs")
        resp = conn.getresponse()
        self.assertEqual(resp.status, 401)
        resp.read()
        conn.close()

    def test_wrong_token_is_rejected(self):
        status, _, _ = self.request(
            "GET", "/api/jobs", headers={"Authorization": "Bearer nope"}
        )
        self.assertEqual(status, 401)

    def test_errors_close_the_connection(self):
        # Unread request bodies must not poison a reused keep-alive socket.
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        conn.request("GET", "/api/nope")
        resp = conn.getresponse()
        self.assertEqual(resp.getheader("Connection"), "close")
        resp.read()
        conn.close()

    def test_unknown_endpoint_404(self):
        status, _ = self.json_request("GET", "/api/nonsense")
        self.assertEqual(status, 404)


class UploadValidationTests(ServerTestCase):
    def test_rejects_non_zip(self):
        status, payload = self.json_request("POST", "/api/jobs", body=b"not a jar")
        self.assertEqual(status, 400)
        self.assertIn("not a .jar", payload["error"])

    def test_rejects_zip_without_classes(self):
        status, payload = self.upload(payload=make_jar(classes=()))
        self.assertEqual(status, 400)
        self.assertIn("not a .jar", payload["error"])

    def test_rejects_empty_body(self):
        status, _ = self.json_request("POST", "/api/jobs", body=b"")
        self.assertEqual(status, 400)

    def test_rejects_oversized_upload(self):
        original = srv.MAX_UPLOAD_BYTES
        srv.MAX_UPLOAD_BYTES = 32
        try:
            status, payload = self.json_request("POST", "/api/jobs", body=b"x" * 64)
        finally:
            srv.MAX_UPLOAD_BYTES = original
        self.assertEqual(status, 413)
        self.assertIn("limit", payload["error"])


class JobLifecycleTests(ServerTestCase):
    def test_successful_conversion_produces_downloadable_addon(self):
        status, payload = self.upload()
        self.assertEqual(status, 202)
        job = payload["job"]
        self.assertEqual(job["status"], "queued")
        self.assertEqual(job["filename"], "examplemod-1.0.jar")

        done = self.wait_for_job(job["id"])
        self.assertEqual(done["status"], "succeeded", done.get("message"))
        self.assertTrue(done["has_addon"])
        self.assertTrue(done["has_notes"])
        self.assertEqual(done["size_bytes"], len(b"MCADDON-BYTES"))
        self.assertIn("stub conversion done", done["log_tail"])

        status, body, headers = self.request("GET", f"/api/jobs/{job['id']}/download")
        self.assertEqual(status, 200)
        self.assertEqual(body, b"MCADDON-BYTES")
        self.assertEqual(headers["Content-Type"], "application/octet-stream")
        self.assertIn("ModMorpher_examplemod-1.0.mcaddon", headers["Content-Disposition"])

        status, notes, _ = self.request("GET", f"/api/jobs/{job['id']}/notes")
        self.assertEqual(status, 200)
        self.assertIn(b"nothing to report", notes)

    def test_work_dir_uses_mod_name_for_namespace(self):
        """modmorpher.py names the pack after basename(cwd); keep that stable."""
        status, payload = self.upload(name="earthmobsmod-1.19.2-9.2.0.jar")
        self.assertEqual(status, 202)
        job_id = payload["job"]["id"]
        self.wait_for_job(job_id)
        # Only artifacts should survive; the bulky work dir is cleaned up.
        entries = sorted(os.listdir(self.server.store.job_dir(job_id)))  # type: ignore[attr-defined]
        self.assertIn("Bedrock_Pack.mcaddon", entries)
        self.assertIn("meta.json", entries)
        self.assertNotIn("earthmobsmod-1.19.2-9.2.0", entries)
        self.assertNotIn("upload.jar", entries)

    def test_jobs_are_listed_newest_first(self):
        _, first = self.upload(name="a.jar")
        _, second = self.upload(name="b.jar")
        self.wait_for_job(first["job"]["id"])
        self.wait_for_job(second["job"]["id"])
        status, payload = self.json_request("GET", "/api/jobs")
        self.assertEqual(status, 200)
        names = [j["filename"] for j in payload["jobs"]]
        self.assertEqual(names[0], "b.jar")

    def test_delete_removes_job_and_files(self):
        _, payload = self.upload()
        job_id = payload["job"]["id"]
        self.wait_for_job(job_id)
        status, _ = self.json_request("DELETE", f"/api/jobs/{job_id}")
        self.assertEqual(status, 200)
        status, _ = self.json_request("GET", f"/api/jobs/{job_id}")
        self.assertEqual(status, 404)
        self.assertFalse(os.path.exists(self.server.store.job_dir(job_id)))  # type: ignore[attr-defined]

    def test_missing_job_is_404(self):
        status, _ = self.json_request("GET", "/api/jobs/deadbeef")
        self.assertEqual(status, 404)
        status, _ = self.json_request("GET", "/api/jobs/deadbeef/download")
        self.assertEqual(status, 404)


class FailurePathTests(ServerTestCase):
    script_body = FAIL_STUB

    def test_conversion_failure_is_reported_not_crashed(self):
        status, payload = self.upload()
        self.assertEqual(status, 202)
        job = self.wait_for_job(payload["job"]["id"])
        self.assertEqual(job["status"], "failed")
        self.assertEqual(job["exit_code"], 3)
        self.assertFalse(job["has_addon"])
        self.assertIn("failed", job["message"].lower())

        status, _ = self.json_request("GET", f"/api/jobs/{job['id']}/download")
        self.assertEqual(status, 404)


class JavaPreflightTests(ServerTestCase):
    """A server without a JVM should say so, not produce a vague failure."""

    requires_java = True

    def test_job_fails_fast_with_actionable_message(self):
        original = srv.java_version
        srv.java_version = lambda: None
        try:
            status, payload = self.upload()
            self.assertEqual(status, 202)
            job = self.wait_for_job(payload["job"]["id"])
        finally:
            srv.java_version = original
        self.assertEqual(job["status"], "failed")
        self.assertIn("Java runtime", job["message"])
        self.assertIn("JRE", job["message"])
        self.assertIn("preflight", job["log_tail"])


class PersistenceTests(ServerTestCase):
    def test_running_jobs_become_failed_after_restart(self):
        store = self.server.store  # type: ignore[attr-defined]
        job = store.create("interrupted.jar")
        store.update(job, status="running")

        reloaded = srv.JobStore(store.data_dir)
        recovered = reloaded.get(job.id)
        self.assertIsNotNone(recovered)
        self.assertEqual(recovered.status, "failed")
        self.assertIn("restarted", recovered.message)


class SanitizeTests(unittest.TestCase):
    def test_sanitize_component(self):
        self.assertEqual(srv.sanitize_component("earthmobsmod-1.19.2-9.2.0.jar"),
                         "earthmobsmod-1.19.2-9.2.0")
        self.assertEqual(srv.sanitize_component("../../etc/passwd.jar"), "passwd")
        self.assertEqual(srv.sanitize_component(""), "mod")
        # separators collapse and leading/trailing punctuation is trimmed
        self.assertEqual(srv.sanitize_component("my mod (1).jar"), "my_mod_1")
        self.assertLessEqual(len(srv.sanitize_component("x" * 500)), 64)

    def test_ansi_stripped_from_logs(self):
        self.assertEqual(srv.strip_ansi("\x1b[36mhello\x1b[0m"), "hello")


if __name__ == "__main__":
    unittest.main(verbosity=2)
