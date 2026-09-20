#!/usr/bin/env python3
"""Reescribe rutas/nombres en binarios de Termux para la app io.debi.

Estrategia: sustituye strings C (terminadas en NUL) *completas*, de modo que el
sufijo posterior se conserva y solo se rellena con NUL al final (la ruta nueva es
más corta). Evita depender de patchelf.

Uso:
  patch-paths.py <binario>
"""
import sys

PAIRS = [
    # (viejo, nuevo) — el nuevo debe ser <= en longitud
    (b"/data/data/com.termux/files/usr", b"/data/data/io.debi/files/usr"),
    # los libs van también a jniLibs (nativeLibraryDir) como lib*.so
    (b"libtalloc.so.2", b"libtalloc.so"),
]


def patch(data: bytes, old: bytes, new: bytes):
    assert len(new) <= len(old), f"{new!r} más largo que {old!r}"
    out = bytearray(data)
    n = 0
    i = 0
    while True:
        i = data.find(old, i)
        if i < 0:
            break
        j = data.find(b"\x00", i)
        if j < 0:
            break
        s = data[i:j]                       # string C completa
        ns = s.replace(old, new)
        if len(ns) <= len(s):
            out[i:j] = ns + b"\x00" * (len(s) - len(ns))
            n += 1
        i = j + 1
    return bytes(out), n


def main():
    for path in sys.argv[1:]:
        with open(path, "rb") as f:
            data = f.read()
        total = 0
        for old, new in PAIRS:
            data, n = patch(data, old, new)
            total += n
            print(f"  {path}: {n:>2} ocurrencia(s) {old!r} -> {new!r}")
        if total:
            with open(path, "wb") as f:
                f.write(data)
        print(f"{path}: {total} total")


if __name__ == "__main__":
    main()
