#!/usr/bin/env python3
"""
Sign an APK with APK Signature Scheme v2, in pure Python.

Why this exists: `apksigner` ships in the Android build-tools, which are not
installable in every environment (no route to dl.google.com). Scheme v2 is
however just SHA-256, RSA PKCS#1 v1.5 and careful byte layout, so it can be done
with `cryptography` alone.

v2 (plus v1) is required by Android 11+ when targetSdkVersion >= 30, so this
produces a genuinely installable APK. It does not add v1 (JAR) signing, which
modern Android ignores, nor v3 (key rotation), which a single long-lived key
does not need.

Usage:
    python3 sign_apk_v2.py --apk app-release-unsigned.apk \
        --keystore ../keystore/modmorpher.keystore \
        --keystore-properties ../keystore/keystore.properties \
        --out app-release-signed.apk

    python3 sign_apk_v2.py --verify app-release-signed.apk \
        --keystore ../keystore/modmorpher.keystore \
        --keystore-properties ../keystore/keystore.properties

Format: APK Signature Scheme v2 (platform/tools/apksig in AOSP).
"""
from __future__ import annotations

import argparse
import hashlib
import struct
import sys

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding
from cryptography.hazmat.primitives.serialization import (
    load_der_public_key,
    pkcs12,
)

ZIP_EOCD = b"PK\x05\x06"
ZIP_CENTRAL = b"PK\x01\x02"
SIG_BLOCK_MAGIC = b"APK Sig Block 42"
V2_BLOCK_ID = 0x7109871A
SIG_ALG_RSA_PKCS1_SHA256 = 0x0103
CHUNK_SIZE = 1024 * 1024
EOCD_MIN = 22
EOCD_MAX_SCAN = EOCD_MIN + 0xFFFF


def u32(value: int) -> bytes:
    return struct.pack("<I", value)


def u64(value: int) -> bytes:
    return struct.pack("<Q", value)


def lp(payload: bytes) -> bytes:
    """Length-prefixed with a uint32 length."""
    return u32(len(payload)) + payload


def read_u32(data: bytes, offset: int):
    return struct.unpack_from("<I", data, offset)[0], offset + 4


def read_lp(data: bytes, offset: int):
    length, offset = read_u32(data, offset)
    if offset + length > len(data):
        raise ApkError("truncated length-prefixed field")
    return data[offset:offset + length], offset + length


class ApkError(Exception):
    pass


class Layout:
    """The zip regions the v2 digest is computed over."""

    def __init__(self, data: bytes):
        if len(data) < EOCD_MIN:
            raise ApkError("file is too small to be a zip")
        self.data = data

        eocd_off = data.rfind(ZIP_EOCD, max(0, len(data) - EOCD_MAX_SCAN))
        if eocd_off < 0:
            raise ApkError("no end-of-central-directory record found")

        cd_size = struct.unpack_from("<I", data, eocd_off + 12)[0]
        cd_off = struct.unpack_from("<I", data, eocd_off + 16)[0]
        entries = struct.unpack_from("<H", data, eocd_off + 10)[0]
        if cd_off == 0xFFFFFFFF or cd_size == 0xFFFFFFFF or entries == 0xFFFF:
            raise ApkError("ZIP64 APKs are not supported by this signer")
        if cd_off + cd_size > eocd_off:
            raise ApkError("central directory overlaps the EOCD")
        if data[cd_off:cd_off + 4] != ZIP_CENTRAL:
            raise ApkError("central directory does not start where the EOCD says")

        # An existing APK Signing Block sits immediately before the directory.
        self.has_block = False
        self.block_start = cd_off
        if cd_off > 24 and data[cd_off - 16:cd_off] == SIG_BLOCK_MAGIC:
            size = struct.unpack_from("<Q", data, cd_off - 24)[0]
            start = cd_off - (size + 8)
            if start < 8 or data[start:start + 8] != u64(size):
                raise ApkError("existing signing block is malformed")
            self.has_block = True
            self.block_start = start

        self.cd_off = cd_off
        self.cd_size = cd_size
        self.entries = data[:self.block_start]
        self.central_dir = data[cd_off:cd_off + cd_size]
        self.eocd = data[eocd_off:]
        self.eocd_off = eocd_off


def chunked_digest(*sections: bytes) -> bytes:
    """SHA-256 over the per-chunk digests of every section."""
    overall = hashlib.sha256()
    for section in sections:
        for offset in range(0, len(section), CHUNK_SIZE):
            chunk = section[offset:offset + CHUNK_SIZE]
            inner = hashlib.sha256()
            inner.update(b"\xa5")
            inner.update(u32(len(chunk)))
            inner.update(chunk)
            overall.update(inner.digest())
    return overall.digest()


def content_digest(layout: Layout, block_start: int) -> bytes:
    eocd = bytearray(layout.eocd)
    struct.pack_into("<I", eocd, 16, block_start)  # central directory offset
    return chunked_digest(layout.entries, layout.central_dir, bytes(eocd))


def load_signer(keystore_path: str, properties_path: str | None, password: str | None):
    store_password = password
    if properties_path:
        props = {}
        with open(properties_path, encoding="utf-8") as handle:
            for line in handle:
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    name, value = line.split("=", 1)
                    props[name.strip()] = value.strip()
        store_password = store_password or props.get("storePassword")
        if props.get("keyPassword") and props["keyPassword"] != props.get("storePassword"):
            raise ApkError("PKCS#12 uses one password; keystore.properties disagrees")
    if not store_password:
        raise ApkError("no keystore password (--password or --keystore-properties)")

    with open(keystore_path, "rb") as handle:
        blob = handle.read()
    key, cert, _extra = pkcs12.load_key_and_certificates(blob, store_password.encode())
    if key is None or cert is None:
        raise ApkError("keystore holds no key/certificate pair")
    return key, cert


def build_v2_block(key, cert, digest: bytes) -> bytes:
    signed_data = (
        lp(lp(u32(SIG_ALG_RSA_PKCS1_SHA256) + lp(digest)))       # digests
        + lp(lp(cert.public_bytes(serialization.Encoding.DER)))   # certificates
        + lp(b"")                                                # attributes
    )
    signature = key.sign(signed_data, padding.PKCS1v15(), hashes.SHA256())
    public_key = key.public_key().public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    signer = lp(signed_data) + lp(lp(u32(SIG_ALG_RSA_PKCS1_SHA256) + lp(signature))) + lp(public_key)

    pair = u64(4 + len(signer)) + u32(V2_BLOCK_ID) + signer
    size = len(pair) + 8 + 16
    return u64(size) + pair + u64(size) + SIG_BLOCK_MAGIC


def sign_apk(apk_in: str, apk_out: str, key, cert) -> int:
    with open(apk_in, "rb") as handle:
        data = handle.read()
    layout = Layout(data)

    block = build_v2_block(key, cert, content_digest(layout, len(layout.entries)))
    eocd = bytearray(layout.eocd)
    struct.pack_into("<I", eocd, 16, len(layout.entries) + len(block))

    out = layout.entries + block + layout.central_dir + bytes(eocd)
    with open(apk_out, "wb") as handle:
        handle.write(out)
    return len(out)


def verify_apk(apk_path: str, expected_cert_der: bytes | None = None) -> str:
    """Re-read the APK the way Android would, and check the v2 signature."""
    with open(apk_path, "rb") as handle:
        data = handle.read()
    layout = Layout(data)
    if not layout.has_block:
        raise ApkError("no APK Signing Block found")

    # Walk the block's ID-value pairs.
    pos = layout.block_start + 8
    end = layout.cd_off - 24
    v2_value = None
    while pos < end:
        pair_len = struct.unpack_from("<Q", data, pos)[0]
        pair_id = struct.unpack_from("<I", data, pos + 8)[0]
        value = data[pos + 12:pos + 8 + pair_len]
        if pair_id == V2_BLOCK_ID:
            v2_value = value
        pos += 8 + pair_len
    if v2_value is None:
        raise ApkError("no v2 signature block")

    signers_seq, _ = read_lp(v2_value, 0)
    signer, _ = read_lp(signers_seq, 0)
    signed_data, rest = read_lp(signer, 0)
    signatures_seq, rest = read_lp(rest, 0)
    public_key, _ = read_lp(rest, 0)

    alg, after_alg = read_u32(signatures_seq, 0)
    signature, _ = read_lp(signatures_seq, after_alg)
    if alg != SIG_ALG_RSA_PKCS1_SHA256:
        raise ApkError(f"unexpected signature algorithm 0x{alg:04x}")

    # The digest inside the signed data must match the file on disk.
    digests_seq, _ = read_lp(signed_data, 0)
    digest_alg, after = read_u32(digests_seq, 0)
    stored_digest, _ = read_lp(digests_seq, after)
    if digest_alg != SIG_ALG_RSA_PKCS1_SHA256:
        raise ApkError(f"unexpected digest algorithm 0x{digest_alg:04x}")
    if stored_digest != content_digest(layout, layout.block_start):
        raise ApkError("content digest mismatch: the file changed after signing")

    certs_seq, _ = read_lp(signed_data, len(lp(digests_seq)))
    cert_der, _ = read_lp(certs_seq, 0)
    if expected_cert_der is not None and cert_der != expected_cert_der:
        raise ApkError("signing certificate does not match the keystore")

    load_der_public_key(public_key).verify(
        signature, signed_data, padding.PKCS1v15(), hashes.SHA256()
    )
    cert = x509.load_der_x509_certificate(cert_der)
    return cert.fingerprint(hashes.SHA256()).hex()


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="Sign an APK with APK Signature Scheme v2.")
    parser.add_argument("--apk", help="unsigned APK to sign")
    parser.add_argument("--out", help="where to write the signed APK")
    parser.add_argument("--verify", metavar="APK", help="only verify an already-signed APK")
    parser.add_argument("--keystore", required=True)
    parser.add_argument("--keystore-properties")
    parser.add_argument("--password")
    args = parser.parse_args(argv)

    try:
        key, cert = load_signer(args.keystore, args.keystore_properties, args.password)
        cert_der = cert.public_bytes(serialization.Encoding.DER)

        if args.verify:
            fingerprint = verify_apk(args.verify, cert_der)
            print(f"verify OK  {args.verify}")
            print(f"  certificate SHA-256: {fingerprint}")
            print(f"  subject: {cert.subject.rfc4514_string()}")
            return 0

        if not args.apk or not args.out:
            parser.error("--apk and --out are required unless --verify is used")

        size = sign_apk(args.apk, args.out, key, cert)
        print(f"signed     {args.out} ({size} bytes)")
        # Never hand back an APK that has not been re-read and checked.
        fingerprint = verify_apk(args.out, cert_der)
        print(f"verify OK  {args.out}")
        print(f"  certificate SHA-256: {fingerprint}")
        print(f"  subject: {cert.subject.rfc4514_string()}")
        return 0
    except ApkError as error:
        print(f"error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
