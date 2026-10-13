"""uiautomator driver for the release smoke (scripts/ci/smoke-release.sh).

  smoke_ui.py texts                        print the visible texts
  smoke_ui.py has  ATTR VALUE              exit 0 if a node has ATTR == VALUE
  smoke_ui.py wait ATTR VALUE [SECONDS]    poll until present (default 30 s)
  smoke_ui.py tap  ATTR VALUE [SECONDS]    wait, then tap the centre of the first match
  smoke_ui.py get  ATTR VALUE SECONDS PROP wait, then print PROP of the first match
  smoke_ui.py connect PKG URL              fill the first-run server form and wait for the list

ATTR is any node attribute of the dump (text, resource-id, content-desc,
class). Re-dumps before every tap. Needs adb on PATH or ANDROID_HOME.
"""
import os
import re
import subprocess
import sys
import time


def adb_path():
    for root in (os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT")):
        if root:
            for name in ("adb", "adb.exe"):
                candidate = os.path.join(root, "platform-tools", name)
                if os.path.exists(candidate):
                    return candidate
    return "adb"


ADB = adb_path()


def dump():
    out = subprocess.run([ADB, "exec-out", "uiautomator", "dump", "/dev/tty"],
                         capture_output=True).stdout
    return out.decode("utf-8", "replace")


def nodes(xml):
    for m in re.finditer(r"<node ([^>]*)>", xml):
        yield dict(re.findall(r'([\w-]+)="([^"]*)"', m.group(1)))


def find(attr, value):
    return next((n for n in nodes(dump()) if n.get(attr) == value), None)


def wait(attr, value, seconds):
    deadline = time.monotonic() + seconds
    while True:
        hit = find(attr, value)
        if hit or time.monotonic() > deadline:
            return hit
        time.sleep(1)


def shell(*args, **kw):
    return subprocess.run([ADB, "shell", *args], capture_output=True, text=True, **kw)


def tap_node(hit, label):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", hit["bounds"]))
    x, y = (x1 + x2) // 2, (y1 + y2) // 2
    shell("input", "tap", str(x), str(y), check=True)
    print(f"smoke_ui: tapped {label} at {x},{y}")


def connect(pkg, url):
    """Fill the first-run server form: URL, cleartext opt-in, Connect; wait
    for the gallery list. `input text` goes through the active keyboard and a
    composing one (Gboard on a zh-CN emulator turns "://" into full-width
    punctuation) mangles the URL, so keyboards are disabled while typing."""
    field = wait("resource-id", f"{pkg}:id/server_url", 30)
    if not field:
        print("smoke_ui: server form not shown", file=sys.stderr)
        return 1
    tap_node(field, "server_url")
    imes = shell("ime", "list", "-s").stdout.split()
    for ime in imes:
        shell("ime", "disable", ime)
    try:
        shell("input", "text", url, check=True)
    finally:
        for ime in imes:
            shell("ime", "enable", ime)
    time.sleep(1)
    if not find("text", url):
        print(f"smoke_ui: URL field does not read {url}; visible: "
              + " | ".join(n["text"] for n in nodes(dump()) if n.get("text")), file=sys.stderr)
        return 1
    # The cleartext row only appears for an http:// URL.
    box = wait("resource-id", f"{pkg}:id/checkbox_allow_cleartext", 10)
    if box and box.get("checked") != "true":
        tap_node(box, "checkbox_allow_cleartext")
    tap_node(wait("resource-id", f"{pkg}:id/connect", 10), "connect")
    if not wait("resource-id", f"{pkg}:id/thumb_new", 60):
        print("smoke_ui: gallery list did not appear after Connect; visible: "
              + " | ".join(n["text"] for n in nodes(dump()) if n.get("text")), file=sys.stderr)
        return 1
    print("smoke_ui: connected, list shown")
    return 0


def main(argv):
    sys.stdout.reconfigure(encoding="utf-8")
    cmd = argv[1]
    if cmd == "texts":
        print(" | ".join(n["text"] for n in nodes(dump()) if n.get("text")))
        return 0
    if cmd == "connect":
        return connect(argv[2], argv[3])
    attr, value = argv[2], argv[3]
    if cmd == "has":
        return 0 if find(attr, value) else 1
    seconds = float(argv[4]) if len(argv) > 4 else 30.0
    hit = wait(attr, value, seconds)
    if not hit:
        print(f"smoke_ui: {attr}={value!r} not found within {seconds:.0f}s; visible: "
              + " | ".join(n["text"] for n in nodes(dump()) if n.get("text")), file=sys.stderr)
        return 1
    if cmd == "wait":
        print(f"smoke_ui: found {attr}={value!r}")
        return 0
    if cmd == "get":
        # get ATTR VALUE PROP: print PROP of the matched node (e.g. checked).
        print(hit.get(argv[5], ""))
        return 0
    if cmd == "tap":
        tap_node(hit, f"{attr}={value!r}")
        return 0
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
