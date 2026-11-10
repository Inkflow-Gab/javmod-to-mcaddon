#!/usr/bin/env python3
"""
Generate the ONE signing keystore used by the ModMorpher Android app.

ModMorpher policy: a single keystore signs BOTH debug and release builds.
Never create a second one -- if the signing identity changes, Android
refuses to install an update over an existing copy ("App not installed"),
and you can never update the listing again.

This script does not need a JDK: it writes a PKCS#12 container directly,
which apksigner/AGP read natively (storeType = PKCS12).

Usage:
    python3 scripts/make_keystore.py                # generate if missing
    python3 scripts/make_keystore.py --force        # overwrite (BREAKS updates!)
    python3 scripts/make_keystore.py --out-dir DIR

Requires: pip install cryptography

Prefer keytool? Equivalent JDK command:
    keytool -genkeypair -v -keystore modmorpher.keystore -storetype PKCS12 \
            -alias modmorpher -keyalg RSA -keysize 4096 -validity 36500 \
            -dname "CN=ModMorpher, O=ModMorpher, C=PH"
"""
from __future__ import annotations

import argparse
import datetime as _dt
import os
import secrets
import string
import sys

ALIAS = "modmorpher"
STOREFILE = "modmorpher.keystore"
# 36500 days ~= 100 years. Google Play independently requires a validity that
# extends past 2033; a century clears that with room to spare.
VALIDITY_DAYS = 36500
PASSWORD_ALPHABET = string.ascii_letters + string.digits  # properties-safe


def _password(length: int = 28) -> str:
    return "".join(secrets.choice(PASSWORD_ALPHABET) for _ in range(length))


def build_keystore(out_dir: str, force: bool) -> int:
    try:
        from cryptography import x509
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import rsa
        from cryptography.hazmat.primitives.serialization import pkcs12
        from cryptography.x509.oid import NameOID
    except ImportError:
        sys.exit("error: the 'cryptography' package is required: pip install cryptography")

    store_path = os.path.join(out_dir, STOREFILE)
    props_path = os.path.join(out_dir, "keystore.properties")

    if os.path.exists(store_path) and not force:
        print(f"refusing to overwrite existing keystore: {store_path}")
        print("It already signs your app. Overwriting it makes every future")
        print("update impossible to install. Re-run with --force only if you")
        print("are certain (e.g. the file is corrupt and nothing shipped yet).")
        return 1

    key = rsa.generate_private_key(public_exponent=65537, key_size=4096)
    subject = issuer = x509.Name(
        [
            x509.NameAttribute(NameOID.COMMON_NAME, "ModMorpher"),
            x509.NameAttribute(NameOID.ORGANIZATION_NAME, "ModMorpher"),
            x509.NameAttribute(NameOID.COUNTRY_NAME, "PH"),
        ]
    )
    now = _dt.datetime.now(_dt.timezone.utc)
    cert = (
        x509.CertificateBuilder()
        .subject_name(subject)
        .issuer_name(issuer)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - _dt.timedelta(days=1))
        .not_valid_after(now + _dt.timedelta(days=VALIDITY_DAYS))
        .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
        .sign(key, hashes.SHA256())
    )

    password = _password()
    p12 = pkcs12.serialize_key_and_certificates(
        name=ALIAS.encode(),
        key=key,
        cert=cert,
        cas=None,
        encryption_algorithm=serialization.BestAvailableEncryption(password.encode()),
    )

    os.makedirs(out_dir, exist_ok=True)
    # 0600: the keystore is an identity credential, not a normal asset.
    fd = os.open(store_path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "wb") as fh:
        fh.write(p12)

    with open(props_path, "w", encoding="utf-8") as fh:
        fh.write(
            "# ModMorpher signing config -- THE ONE KEYSTORE. Keep private.\n"
            "# This file and the .keystore are git-ignored on purpose.\n"
            "# Back both up somewhere safe: losing them means you can never\n"
            "# publish an update to the same app again.\n"
            f"storeFile={STOREFILE}\n"
            f"storePassword={password}\n"
            f"keyAlias={ALIAS}\n"
            f"keyPassword={password}\n"
            "storeType=PKCS12\n"
        )
    os.chmod(props_path, 0o600)

    print(f"keystore : {store_path}")
    print(f"config   : {props_path}")
    print(f"alias    : {ALIAS}")
    print(f"valid    : {VALIDITY_DAYS} days")
    print("password : written to keystore.properties (not echoed here)")
    print("\nBACK THIS UP. Without it you cannot ship updates.")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description="Create the single ModMorpher signing keystore.")
    here = os.path.dirname(os.path.abspath(__file__))
    ap.add_argument(
        "--out-dir",
        default=os.path.join(os.path.dirname(here), "keystore"),
        help="default: android/keystore/ (git-ignored)",
    )
    ap.add_argument("--force", action="store_true", help="overwrite an existing keystore")
    args = ap.parse_args()
    return build_keystore(args.out_dir, args.force)


if __name__ == "__main__":
    raise SystemExit(main())
