#!/usr/bin/env python3
"""Reescribe rutas/nombres en binarios de Termux para la app Linux-TNube Pro (io.tnube).

Estrategia: sustituye strings C (terminadas en NUL) *completas*, de modo que el
sufijo posterior se conserva y solo se rellena con NUL al final (la ruta nueva es
<= en longitud que la original). Evita depender de patchelf.

Uso:
  patch-paths.py <binario>

NOTA: `/data/data/com.termux/files/usr` (31) -> `/data/data/io.tnube/files/usr`
(29): cabe de sobra (quedan 2 NUL de relleno). Si cambias el applicationId, debe medir
<= 10 caracteres para que la ruta no pase de 31 bytes, y hay que re-parchear el binario
proot (las otras dos libs y los loader NO llevan rutas de paquete).
"""
import sys

PAIRS = [
    # (viejo, nuevo) — el nuevo debe ser <= en longitud
    (b"/data/data/com.termux/files/usr", b"/data/data/io.tnube/files/usr"),
    # los libs van también a jniLibs (nativeLibraryDir) como lib*.so
    (b"libtalloc.so.2", b"libtalloc.so"),
]


def patch(data: bytes, old: bytes, new: bytes):
    """Sustituye strings C enteras SIN desplazar el fichero.

    La regla de oro: el resultado debe ocupar EXACTAMENTE lo mismo que el original
    (si crece/encoge, se desplazan las secciones ELF → el linker falla con
    "empty/missing DT_HASH"). Aquí se permite que `new` sea más largo que el
    trozo `old` mientras quede relleno NUL de sobra tras la string (se consume
    ese relleno). NUNCA se cambia la longitud total del fichero.
    """
    out = bytearray(data)
    n = 0
    i = 0
    while True:
        i = data.find(old, i)
        if i < 0:
            break
        j = data.find(b"\x00", i)          # fin de la string C
        if j < 0:
            break
        s = data[i:j]                       # string C completa (p.ej. .../io.debi/files/usr/lib)
        ns = s.replace(old, new)
        # relleno NUL disponible justo después de la string (se puede consumir)
        pad = 0
        while data[j + pad:j + pad + 1] == b"\x00":
            pad += 1
        total = len(s) + pad
        if len(ns) <= total:
            out[i:i + total] = ns + b"\x00" * (total - len(ns))
            n += 1
        else:
            print(f"  [aviso] no cabe: {ns!r} ({len(ns)}) > {total} bytes disponibles")
        i = j + 1
    assert len(out) == len(data), "el fichero cambió de tamaño (¡peligro!)"
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
