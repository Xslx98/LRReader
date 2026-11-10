"""Deterministic archives for the contract seed (stdlib only).

Every page is a solid colour unique within its archive, so a test can tell
which page a server-made thumbnail came from.
"""
import colorsys
import random
import struct
import zipfile
import zlib

PAGE_WIDTH = 64
PAGE_HEIGHT = 90
# The server's arcid is the SHA-1 of the first 512 000 bytes. Padding pushes the
# large archive past that, so comparing ids proves the cut-off and not just "SHA-1".
LARGE_PAGE_PADDING = 16 * 1024


def page_rgb(index, total):
    r, g, b = colorsys.hsv_to_rgb(index / total, 1.0, 1.0)
    return [round(r * 255), round(g * 255), round(b * 255)]


def _png_chunk(kind, payload):
    crc = zlib.crc32(kind + payload) & 0xFFFFFFFF
    return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", crc)


def solid_png(rgb, padding=b""):
    header = struct.pack(">IIBBBBB", PAGE_WIDTH, PAGE_HEIGHT, 8, 2, 0, 0, 0)
    row = b"\x00" + bytes(rgb) * PAGE_WIDTH
    chunks = [_png_chunk(b"IHDR", header)]
    if padding:
        # An ancillary private chunk: decoders skip it, and random bytes do not compress.
        chunks.append(_png_chunk(b"raNd", padding))
    chunks.append(_png_chunk(b"IDAT", zlib.compress(row * PAGE_HEIGHT, 9)))
    chunks.append(_png_chunk(b"IEND", b""))
    return b"\x89PNG\r\n\x1a\n" + b"".join(chunks)


def write_archive(path, name, pages, padded=False):
    """Writes a stored zip of `pages` PNGs and returns each page's colour in order."""
    rng = random.Random(name)
    colours = [page_rgb(i, pages) for i in range(pages)]
    with zipfile.ZipFile(path, "w", zipfile.ZIP_STORED) as archive:
        for index, rgb in enumerate(colours):
            padding = rng.randbytes(LARGE_PAGE_PADDING) if padded else b""
            info = zipfile.ZipInfo(f"page_{index + 1:03d}.png", date_time=(2020, 1, 1, 0, 0, 0))
            archive.writestr(info, solid_png(rgb, padding))
    return colours
