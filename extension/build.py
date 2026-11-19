#!/usr/bin/env python3
"""Builds the GroveVault extension: GroveVault.zip (load unpacked on a computer) and a signed GroveVault.crx
(Vivaldi for Android: drag it from Files onto the Extensions page). The signing key keeps the extension ID the same
across updates; pass its path as the first argument (it is NOT stored in the repository)."""
import hashlib, io, os, struct, sys, zipfile
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa

here = os.path.dirname(os.path.abspath(__file__))
src = os.path.join(here, "src")
key_path = sys.argv[1] if len(sys.argv) > 1 else os.path.join(here, "grovevault-extension.pem")
out_dir = sys.argv[2] if len(sys.argv) > 2 else here

buf = io.BytesIO()
with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
    for n in sorted(os.listdir(src)):
        zi = zipfile.ZipInfo(n, (2026, 1, 1, 0, 0, 0)); zi.compress_type = zipfile.ZIP_DEFLATED
        z.writestr(zi, open(os.path.join(src, n), "rb").read())
zbytes = buf.getvalue()
open(os.path.join(out_dir, "GroveVault.zip"), "wb").write(zbytes)

if not os.path.exists(key_path):
    k = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    open(key_path, "wb").write(k.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
key = serialization.load_pem_private_key(open(key_path, "rb").read(), None)
pub = key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)

def varint(n):
    b = b""
    while True:
        t = n & 0x7F; n >>= 7
        b += bytes([t | (0x80 if n else 0)])
        if not n: return b
def field(num, data): return varint(num << 3 | 2) + varint(len(data)) + data

crx_id = hashlib.sha256(pub).digest()[:16]
signed_data = field(1, crx_id)
msg = b"CRX3 SignedData\x00" + struct.pack("<I", len(signed_data)) + signed_data + zbytes
sig = key.sign(msg, padding.PKCS1v15(), hashes.SHA256())
header = field(2, field(1, pub) + field(2, sig)) + field(10000, signed_data)
crx = b"Cr24" + struct.pack("<II", 3, len(header)) + header + zbytes
open(os.path.join(out_dir, "GroveVault.crx"), "wb").write(crx)
ext_id = "".join("abcdefghijklmnop"[b >> 4] + "abcdefghijklmnop"[b & 15] for b in crx_id)
print("extension id", ext_id)
