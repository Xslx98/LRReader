"""uiautomator driver for the release smoke (scripts/ci/smoke-release.sh) and
the widget smoke.

  smoke_ui.py texts                        print the visible texts
  smoke_ui.py has  ATTR VALUE              exit 0 if a node has ATTR == VALUE
  smoke_ui.py wait ATTR VALUE [SECONDS]    poll until present (default 30 s)
  smoke_ui.py tap  ATTR VALUE [SECONDS]    wait, then tap the centre of the first match
  smoke_ui.py get  ATTR VALUE SECONDS PROP wait, then print PROP of the first match
  smoke_ui.py connect PKG URL              fill the first-run server form and wait for the list
  smoke_ui.py widgets PKG [ONLY...]        drive every user path of the framework widgets
                                           (see WIDGET_JOURNEYS); ONLY limits it to named journeys
  smoke_ui.py shots PKG DIR [THEME...]     screenshot every main screen per theme (light, dark,
                                           black) into DIR/<theme>/; run after `widgets`

ATTR is any node attribute of the dump (text, resource-id, content-desc,
class). Re-dumps before every tap. Needs adb on PATH or ANDROID_HOME.

`widgets` is the 2.4 safety net: every later framework branch attaches one
passing run as smoke evidence. It needs an emulator (the
pinch and double tap go through the emulator console), an en-US locale, a
fresh install connected to `MOCK_SEED=1 MOCK_CHAPTERS=1 MOCK_BIG_PAGES=40
scripts/ci/mock_lrr.py`, and optionally SMOKE_MOCK_LOG=<the mock's stdout
log> so a pull-to-refresh must reach the server. It restores the navigation
mode and rotation it changes.
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
# The mock's request log (scripts/ci/mock_lrr.py stdout), optional: lets
# `widgets` prove that a pull-to-refresh reloaded the list.
MOCK_LOG = os.environ.get("SMOKE_MOCK_LOG")
# connect(): how often the server URL is cleared and retyped before giving up.
CONNECT_TYPE_ATTEMPTS = 3


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
    punctuation) mangles the URL, so keyboards are disabled while typing.
    Right after a fresh install `input text` sometimes drops the tail of the
    URL ("http://127.0.0.1:" without the port), so the field is cleared and
    retyped until it reads the URL, up to CONNECT_TYPE_ATTEMPTS times."""
    field_id = f"{pkg}:id/server_url"
    field = wait("resource-id", field_id, 30)
    if not field:
        print("smoke_ui: server form not shown", file=sys.stderr)
        return 1
    imes = shell("ime", "list", "-s").stdout.split()
    for ime in imes:
        shell("ime", "disable", ime)
    try:
        for attempt in range(1, CONNECT_TYPE_ATTEMPTS + 1):
            tap_node(field, "server_url")
            typed = field.get("text", "")
            if attempt > 1 or typed:
                # Move to the end and delete everything typed so far.
                shell("input", "keyevent", "KEYCODE_MOVE_END", check=True)
                shell("input", "keyevent", *["KEYCODE_DEL"] * (len(typed) + 8), check=True)
            shell("input", "text", url, check=True)
            time.sleep(1)
            field = find("resource-id", field_id) or field
            if field.get("text") == url:
                break
            print(f"smoke_ui: URL field reads {field.get('text', '')!r}, not {url!r} "
                  f"(attempt {attempt} of {CONNECT_TYPE_ATTEMPTS})", file=sys.stderr)
        else:
            print(f"smoke_ui: URL field does not read {url}; visible: " + visible(), file=sys.stderr)
            return 1
    finally:
        for ime in imes:
            shell("ime", "enable", ime)
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


def visible():
    return " | ".join(n["text"] for n in nodes(dump()) if n.get("text"))


def centre(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node["bounds"]))
    return (x1 + x2) // 2, (y1 + y2) // 2


# ---- widgets: scripted user paths of the widgets 2.4 replaces ---------------

class SmokeFailure(Exception):
    pass


class Widgets:
    """Every user path of the framework / seven332 widgets that 2.4 retires
    or converts (drawer, pull-to-refresh, list scroll / tap / long-press
    multi-select / fast scroller, ripple press states, settings screens,
    pattern lock, reader gestures), on an app already connected to
    scripts/ci/mock_lrr.py started with MOCK_SEED=1 MOCK_CHAPTERS=1
    MOCK_BIG_PAGES=40. Each step checks a visible effect, then that the app
    has not crashed."""

    def __init__(self, pkg):
        self.pkg = pkg
        self.w, self.h = map(int, re.findall(r"(\d+)x(\d+)", shell("wm", "size").stdout)[-1])

    # -- primitives --
    def rid(self, name):
        return name if ":" in name else f"{self.pkg}:id/{name}"

    def all_id(self, name):
        return [n for n in nodes(dump()) if n.get("resource-id") == self.rid(name)]

    def need(self, name, seconds=15):
        hit = wait("resource-id", self.rid(name), seconds)
        if not hit:
            raise SmokeFailure(f"{name} not shown; visible: {visible()}")
        return hit

    def need_text(self, text, seconds=15):
        hit = wait("text", text, seconds)
        if not hit:
            raise SmokeFailure(f"text {text!r} not shown; visible: {visible()}")
        return hit

    def gone(self, name, seconds=10):
        deadline = time.monotonic() + seconds
        while find("resource-id", self.rid(name)):
            if time.monotonic() > deadline:
                raise SmokeFailure(f"{name} still shown after {seconds}s")
            time.sleep(0.5)

    def tap_id(self, name, seconds=15):
        tap_node(self.need(name, seconds), name)

    def tap(self, x, y):
        shell("input", "tap", str(x), str(y), check=True)

    def swipe(self, x1, y1, x2, y2, ms=300):
        shell("input", "swipe", *map(str, (x1, y1, x2, y2, ms)), check=True)

    def long_press(self, x, y):
        self.swipe(x, y, x, y, 900)

    def press(self, x, y):
        """A held tap (250 ms, under the 400 ms long-press timeout): draws the
        pressed / ripple state, then clicks. Separate `input motionevent`
        calls cannot do a press-and-cancel: each takes ~300 ms to start, so
        the hold always turns into a long-press."""
        self.swipe(x, y, x, y, 250)

    def console_touch(self, *events):
        """Raw multi-touch events through the emulator console: `input` has
        no multi-touch and starts too slowly for a double tap, and the shell
        user cannot write /dev/input. Emulator only; coordinates 0..32767."""
        out = subprocess.run([ADB, "emu", "event", "send", *events, "EV_SYN:0:0"],
                             capture_output=True, text=True).stdout
        if "OK" not in out:
            raise SmokeFailure(f"emulator console refused a touch event: {out.strip()}")

    def double_tap(self, x, y):
        """Two taps 120 ms apart: GestureDetector only takes a second down
        40..300 ms after the first up as a double tap, and back-to-back
        console calls (~50 ms each) can land under the 40 ms minimum."""
        rx, ry = int(x * 32767 / (self.w - 1)), int(y * 32767 / (self.h - 1))
        for i, tid in enumerate((200, 201)):
            if i:
                time.sleep(0.12)
            self.console_touch("EV_ABS:ABS_MT_SLOT:0", f"EV_ABS:ABS_MT_TRACKING_ID:{tid}",
                               "EV_ABS:ABS_MT_PRESSURE:50", f"EV_ABS:ABS_MT_POSITION_X:{rx}",
                               f"EV_ABS:ABS_MT_POSITION_Y:{ry}")
            time.sleep(0.05)
            self.console_touch("EV_ABS:ABS_MT_TRACKING_ID:-1")

    def pinch(self, cx, cy, start, end, steps=10):
        """Two fingers on a horizontal line through (cx, cy), `start` px
        apart moving to `end` px apart (multi-touch protocol B)."""
        sx, sy = 32767 / (self.w - 1), 32767 / (self.h - 1)
        send = self.console_touch

        def fingers(gap, first=False):
            events = []
            for slot, x in enumerate((cx - gap // 2, cx + gap // 2)):
                events.append(f"EV_ABS:ABS_MT_SLOT:{slot}")
                if first:
                    events += [f"EV_ABS:ABS_MT_TRACKING_ID:{100 + slot}", "EV_ABS:ABS_MT_PRESSURE:50"]
                events += [f"EV_ABS:ABS_MT_POSITION_X:{int(x * sx)}",
                           f"EV_ABS:ABS_MT_POSITION_Y:{int(cy * sy)}"]
            send(*events)

        fingers(start, first=True)
        for i in range(1, steps + 1):
            fingers(start + (end - start) * i // steps)
        send("EV_ABS:ABS_MT_SLOT:0", "EV_ABS:ABS_MT_TRACKING_ID:-1",
             "EV_ABS:ABS_MT_SLOT:1", "EV_ABS:ABS_MT_TRACKING_ID:-1")

    def frame(self):
        """Raw RGBA screenshot of the middle third of the screen (the GL
        reader has nothing for uiautomator, so its effects are checked
        by comparing frames)."""
        raw = subprocess.run([ADB, "exec-out", "screencap"], capture_output=True).stdout
        row = self.w * 4
        header = len(raw) - row * self.h
        top = header + row * (self.h // 3)
        return raw[top:top + row * (self.h // 3)]

    def back(self, settle=1.0):
        shell("input", "keyevent", "KEYCODE_BACK", check=True)
        time.sleep(settle)

    def check(self, step, settle=1.0):
        time.sleep(settle)
        crash = subprocess.run([ADB, "logcat", "-d", "-b", "crash"], capture_output=True,
                               text=True, encoding="utf-8", errors="replace").stdout
        if f"Process: {self.pkg}" in crash:
            raise SmokeFailure(f"crash after '{step}':\n{crash[-4000:]}")
        if not shell("pidof", self.pkg).stdout.strip():
            raise SmokeFailure(f"{self.pkg} is not running after '{step}'")
        print(f"OK: {step}", flush=True)

    def titles(self):
        return [n["text"] for n in nodes(dump()) if n.get("resource-id") == self.rid("title")]

    def three_button_nav(self):
        """Gesture navigation eats edge swipes: switch to three-button nav;
        returns a function that restores the previous mode."""
        overlays = shell("cmd", "overlay", "list").stdout
        if "[x] com.android.internal.systemui.navbar.gestural" not in overlays:
            return lambda: None
        shell("cmd", "overlay", "enable-exclusive", "--category",
              "com.android.internal.systemui.navbar.threebutton")
        time.sleep(3)

        def restore():
            shell("cmd", "overlay", "enable-exclusive", "--category",
                  "com.android.internal.systemui.navbar.gestural")
            time.sleep(3)
        return restore

    def relaunch(self):
        shell("monkey", "-p", self.pkg, "-c", "android.intent.category.LAUNCHER", "1")
        time.sleep(3)

    # -- navigation --
    def to_home(self):
        """Back out to the gallery list (relaunching if back left the app)."""
        for _ in range(6):
            xml = dump()
            if self.rid("thumb_new") in xml and not any(
                    self.rid(v) in xml for v in ("batch_action_bar", "nav_settings", "action_settings")):
                return
            if self.pkg not in xml:
                self.relaunch()
                continue
            self.back(1.5)
        self.need("thumb_new")

    def open_drawer(self):
        self.tap_id("search_menu")
        self.need("nav_settings", 5)

    def open_right_drawer(self):
        """Edge-drag the quick search drawer open (needs three-button nav;
        the first gesture after a nav-mode switch is sometimes dropped)."""
        for _ in range(3):
            self.swipe(self.w - 2, self.h // 2, self.w // 5, self.h // 2, 500)
            if wait("resource-id", self.rid("action_settings"), 4):
                return
        raise SmokeFailure(f"right edge drag did not open quick search; visible: {visible()}")

    def home_list(self):
        """The unfiltered gallery list (a category list also has thumb_new)."""
        self.nav("nav_homepage", "thumb_new")
        self.to_home()                      # already on it: the drawer stays open
        self.need_text("Mock Tank")
        time.sleep(1)

    def close_right_drawer(self):
        for _ in range(3):
            self.back()
            if not wait("resource-id", self.rid("action_settings"), 1):
                return
        raise SmokeFailure("back did not close quick search")

    def nav(self, item, marker):
        self.to_home()
        self.open_drawer()
        self.tap_id(item, 5)
        return self.need(marker, 20)

    # -- shared list gestures --
    def scroll_list(self, label):
        before = self.titles()
        for _ in range(2):                  # a first swipe after a transition can be dropped
            for _ in range(3):
                self.swipe(self.w // 2, self.h * 3 // 4, self.w // 2, self.h // 4)
            if not before or before != self.titles():
                break
        else:
            raise SmokeFailure(f"{label}: list did not scroll ({before})")
        for _ in range(5):
            self.swipe(self.w // 2, self.h // 4, self.w // 2, self.h * 3 // 4, 150)
        time.sleep(1)
        if before and self.titles()[:1] != before[:1]:
            raise SmokeFailure(f"{label}: list did not scroll back to {before[:1]}")
        self.check(f"{label}: scroll down and back up")

    def searches(self):
        if not MOCK_LOG:
            return None
        with open(MOCK_LOG, encoding="utf-8", errors="replace") as f:
            return f.read().count("GET /api/search")

    def pull_to_refresh(self, label, marker):
        """Pull down from the top; with SMOKE_MOCK_LOG set, the mock must then
        see a new /api/search (the list reloaded, not just bounced)."""
        before = self.searches()
        self.swipe(self.w // 2, self.h * 3 // 10, self.w // 2, self.h * 8 // 10, 700)
        time.sleep(3)
        self.need(marker, 20)
        if before is not None and self.searches() <= before:
            raise SmokeFailure(f"{label}: pull to refresh did not reload the list")
        self.check(f"{label}: pull to refresh")

    def pull_up_at_end(self, label, marker):
        """Fling to the end of the list, then drag up past it: the footer
        reloads the last page, so with SMOKE_MOCK_LOG set the mock must see a
        new /api/search (the mock serves the whole list as one page, so no
        page load happens on the way down). Flings back to the top after."""
        for _ in range(20):
            self.swipe(self.w // 2, self.h * 4 // 5, self.w // 2, self.h // 5, 100)
        time.sleep(3)
        before = self.searches()
        self.swipe(self.w // 2, self.h * 7 // 10, self.w // 2, self.h * 2 // 10, 900)
        time.sleep(3)
        self.need(marker, 20)
        if before is not None and self.searches() <= before:
            raise SmokeFailure(f"{label}: pulling up at the end did not reload the last page")
        top = None
        for i in range(20):
            self.swipe(self.w // 2, self.h // 5, self.w // 2, self.h * 4 // 5, 100)
            if i % 5 == 4:
                time.sleep(1)
                seen = self.titles()[:1]
                if seen == top:
                    break
                top = seen
        self.check(f"{label}: pull up at the end loads the footer")

    def fast_scroll(self, label):
        """Drag the fast scroller's handle to the end, prove the list is at
        its end (a further fling changes nothing), then drag it back to the
        first row. The handle's position is not in the dump: press at a few
        points along the track until a drag lands on it.

        The scroller hides 1 s after the list stops (then fades for 0.5 s)
        and ignores touches once hidden, while a uiautomator dump takes ~2 s
        on the AVD: read its bounds once from a dump taken during a slow
        scroll, then reveal it (a short scroll away from the list end it
        sits at and back, whose fling stops at that end and so leaves the
        handle where it was) and drag in one shell call, with no dump
        between."""
        first = self.titles()[:1]
        mid, top, bottom = self.w // 2, self.h * 2 // 5, self.h * 3 // 5
        forward = f"input swipe {mid} {bottom} {mid} {top}"
        backward = f"input swipe {mid} {top} {mid} {bottom}"
        slow = subprocess.Popen([ADB, "shell", f"{forward} 4000; {backward} 4000"])
        time.sleep(1)
        bar = [n for n in nodes(dump()) if n.get("resource-id") == self.rid("fast_scroller")]
        slow.wait()
        if not bar:
            raise SmokeFailure(f"{label}: no fast scroller")
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", bar[0]["bounds"]))
        x = (x1 + x2) // 2

        def drag(starts, end, done):
            reveal = f"{forward} 200; {backward} 200" if end else f"{backward} 200; {forward} 200"
            for start in starts:
                shell(f"{reveal}; input swipe {x} {y1 + (y2 - y1) * start // 100}"
                      f" {x} {y1 + (y2 - y1) * end // 100} 1200", check=True)
                time.sleep(1)
                if done():
                    return
            raise SmokeFailure(f"{label}: fast scroller drag did not move the list")

        def at_end():
            seen = self.titles()
            if seen[:1] == first:
                return False
            self.swipe(self.w // 2, self.h * 3 // 4, self.w // 2, self.h // 4, 150)
            time.sleep(1)
            return self.titles() == seen

        drag((5, 15, 25, 35), 100, at_end)
        drag((95, 85, 75, 65), 0, lambda: self.titles()[:1] == first)
        self.check(f"{label}: fast scroller drag to the end and back")

    def long_press_into(self, row_text, marker, then=None):
        """Long-press a row until `marker` shows (the first touch after a
        scene transition is sometimes dropped, so try twice)."""
        for _ in range(2):
            self.long_press(*centre(self.need_text(row_text)))
            time.sleep(1)
            if then:
                then()
            if wait(*marker, 5):
                return
        raise SmokeFailure(f"long-press on {row_text!r} did not show {marker}; visible: {visible()}")

    def tap_into(self, text, marker):
        """Tap a text until `marker` (a resource id) shows; a tap right after
        a screen transition is sometimes dropped."""
        for _ in range(3):
            self.tap(*centre(self.need_text(text)))
            if wait("resource-id", self.rid(marker), 4):
                return
        raise SmokeFailure(f"tapping {text!r} did not show {marker}; visible: {visible()}")

    def multi_select(self, label, row_text):
        """Long-press a row into multi-select (batch bar), select all, back out."""
        self.long_press_into(row_text, ("resource-id", self.rid("batch_action_bar")))
        self.need("batch_count", 5)
        self.tap_id("batch_select_all")
        self.check(f"{label}: long-press multi-select and select all")
        self.back()
        self.gone("batch_action_bar")
        self.check(f"{label}: back leaves multi-select")

    # -- journeys --
    def home(self):
        self.home_list()
        self.scroll_list("home")
        self.fast_scroll("home")
        self.pull_to_refresh("home", "thumb_new")
        self.pull_up_at_end("home", "thumb_new")
        self.press(*centre(self.need_text("Mock Archive 1")))
        self.need("read", 20)
        self.check("home: card press opens detail")
        self.to_home()
        # A tap in multi-select toggles a row.
        self.long_press_into("Mock Archive 1", ("resource-id", self.rid("batch_count")))
        first = self.need("batch_count", 5).get("text")
        self.tap(*centre(self.need_text("Mock Big Archive 1")))
        time.sleep(0.5)
        if self.need("batch_count").get("text") == first:
            raise SmokeFailure("home: tapping a second row did not change the selection")
        self.check("home: tap adds a row to the selection")
        self.back()
        self.gone("batch_action_bar")
        self.multi_select("home", "Mock Archive 1")

    def drawer(self):
        self.to_home()
        self.open_drawer()
        self.check("drawer: open from the toolbar")
        self.back()
        self.gone("nav_settings")
        self.check("drawer: back closes")
        self.open_drawer()
        self.tap(self.w - 40, self.h // 2)
        self.gone("nav_settings")
        self.check("drawer: scrim tap closes")
        # A horizontal drag anywhere on the content opens a drawer (the edges
        # belong to the system back gesture under gesture navigation).
        self.swipe(self.w * 3 // 10, self.h // 2, self.w * 9 // 10, self.h // 2, 500)
        self.need("nav_settings", 5)
        self.back()
        self.gone("nav_settings")
        self.swipe(self.w * 7 // 10, self.h // 2, self.w // 10, self.h // 2, 500)
        self.need("action_settings", 5)
        self.close_right_drawer()
        self.check("drawer: a drag on the content opens either drawer")
        restore = self.three_button_nav()
        try:
            self.swipe(2, self.h // 2, self.w * 4 // 5, self.h // 2, 500)
            self.need("nav_settings", 5)
            self.check("drawer: left edge drag opens")
            self.swipe(self.w * 4 // 5, self.h // 2, 5, self.h // 2, 400)
            self.gone("nav_settings")
            self.check("drawer: drag closes")
            self.open_right_drawer()
            self.check("drawer: right edge drag opens quick search")
            self.close_right_drawer()
        finally:
            restore()
        self.need("thumb_new")
        self.check("drawer: right drawer closed")

    def page(self):
        return self.need("progress", 5).get("text", "")

    def reader(self):
        """Tap zones, swipes, menu, pinch / double-tap zoom and in-place
        rotation on a 40-page archive that is not downloaded (its striped
        mock pages make a zoom visible in the frame)."""
        self.home_list()
        self.tap(*centre(self.need_text("Mock Big Archive 1")))
        self.tap_id("read", 20)
        self.need("progress", 20)
        time.sleep(3)
        mid = self.h // 2
        self.tap(self.w // 2, mid)            # dismisses the first-run tap-zone hint
        time.sleep(1.5)
        if find("resource-id", self.rid("seek_bar_panel")):
            self.tap(self.w // 2, mid)        # no hint: that tap opened the menu
            time.sleep(1.5)
        start = self.page()
        # Default direction is right-to-left: the left zone is "next".
        for _ in range(3):
            self.tap(self.w // 10, mid)
            time.sleep(0.8)
        after = self.page()
        if after == start:
            raise SmokeFailure(f"reader: left-zone taps did not turn the page ({start})")
        self.check(f"reader: left tap zone turns pages ({start} -> {after})")
        self.tap(self.w * 9 // 10, mid)
        time.sleep(0.8)
        if self.page() == after:
            raise SmokeFailure("reader: right-zone tap did not turn back")
        self.check("reader: right tap zone turns back")
        here = self.page()
        self.swipe(self.w // 5, mid, self.w * 4 // 5, mid, 300)
        time.sleep(1)
        swiped = self.page()
        self.swipe(self.w * 4 // 5, mid, self.w // 5, mid, 300)
        time.sleep(1)
        if swiped == here or self.page() != here:
            raise SmokeFailure(f"reader: swipes did not turn forth and back ({here}/{swiped}/{self.page()})")
        self.check("reader: swipe page turns")
        # Centre zone: menu on, menu off.
        self.tap(self.w // 2, self.h * 3 // 4)
        self.need("seek_bar_panel", 5)
        self.tap(self.w // 2, self.h * 3 // 4)
        self.gone("seek_bar_panel", 6)
        self.check("reader: centre tap zone toggles the menu")
        time.sleep(1)
        base = self.frame()
        self.pinch(self.w // 2, mid, self.w // 5, self.w * 4 // 5)
        time.sleep(1.5)
        if self.frame() == base:
            raise SmokeFailure("reader: pinch-out did not zoom")
        self.pinch(self.w // 2, mid, self.w * 4 // 5, self.w // 5)
        time.sleep(1.5)
        self.check("reader: pinch zoom in and out")
        base = self.frame()
        self.double_tap(self.w // 2, mid)
        time.sleep(1.5)
        if self.frame() == base:
            raise SmokeFailure("reader: double tap did not zoom")
        self.double_tap(self.w // 2, mid)
        time.sleep(1.5)
        self.check("reader: double-tap zoom in and out")
        here = self.page()
        auto = shell("settings", "get", "system", "accelerometer_rotation").stdout.strip()
        shell("settings", "put", "system", "accelerometer_rotation", "0")
        shell("settings", "put", "system", "user_rotation", "1")
        time.sleep(4)
        self.check("reader: rotate to landscape")
        shell("settings", "put", "system", "user_rotation", "0")
        shell("settings", "put", "system", "accelerometer_rotation", auto or "1")
        time.sleep(4)
        if self.page() != here:
            raise SmokeFailure(f"reader: rotation moved from {here} to {self.page()}")
        self.check("reader: rotate back keeps the page")
        self.back(2)
        self.need("read", 10)
        self.check("reader: back to detail")

    def downloads(self):
        """Queue every archive (multi-select, Download), then drive the list:
        scroll, fast scroller, press opens the reader (filling history),
        multi-select through the FAB."""
        self.home_list()
        self.long_press_into("Mock Archive 1", ("resource-id", self.rid("batch_action_bar")))
        self.tap_id("batch_select_all")
        self.tap_id("batch_download")
        self.check("downloads: queue every archive", 3)
        self.nav("nav_downloads", "action_start_all")
        deadline = time.monotonic() + 120
        while len(self.all_id("title")) < 5:
            if time.monotonic() > deadline:
                raise SmokeFailure(f"downloads: list never filled; visible: {visible()}")
            time.sleep(2)
        self.scroll_list("downloads")
        self.fast_scroll("downloads")
        # Open a dozen rows in the reader: exercises the row press and gives
        # History enough rows for its fast scroller.
        opened = 0
        for _ in range(3):
            for text in [n["text"] for n in self.all_id("title")[:4]]:
                hit = find("text", text)
                if not hit:
                    continue
                self.press(*centre(hit))
                if not wait("resource-id", self.rid("progress"), 15):
                    raise SmokeFailure(f"downloads: row press did not open the reader; visible: {visible()}")
                opened += 1
                self.back(2)
                self.need("action_start_all")
            self.swipe(self.w // 2, self.h * 4 // 5, self.w // 2, self.h // 5, 600)
            time.sleep(1)
        self.check(f"downloads: row press opens the reader ({opened} rows)")
        for _ in range(5):
            self.swipe(self.w // 2, self.h // 4, self.w // 2, self.h * 3 // 4, 150)
        time.sleep(1)
        self.long_press_into(self.all_id("title")[0]["text"], ("content-desc", "Select all"),
                             then=lambda: self.tap_id("fab_layout"))
        tap_node(wait("content-desc", "Select all", 5), "Select all")
        self.check("downloads: long-press multi-select and select all")
        self.back()
        deadline = time.monotonic() + 5    # the FAB menu animates closed
        while find("content-desc", "Select all"):
            if time.monotonic() > deadline:
                raise SmokeFailure("downloads: back did not leave multi-select")
            time.sleep(0.5)
        self.need("action_start_all")
        self.check("downloads: back leaves multi-select")

    def history(self):
        self.nav("nav_history", "action_clear_all")
        self.need("title", 10)
        self.scroll_list("history")
        self.fast_scroll("history")
        text = self.all_id("title")[0]["text"]
        self.press(*centre(self.need_text(text)))
        time.sleep(3)
        if find("resource-id", self.rid("action_clear_all")):
            raise SmokeFailure("history: row press opened nothing")
        self.check("history: row press opens the archive")
        self.back(2)
        self.need("action_clear_all")
        self.multi_select("history", self.all_id("title")[0]["text"])

    def favourites(self):
        """The drawer's Favourites = LANraragi categories."""
        self.nav("nav_favourite", "action_add_category")
        self.press(*centre(self.need_text("Mock Favourites")))
        self.need("refresh_layout", 15)
        self.need_text("Mock Archive 1")
        self.check("favourites: category press opens its list")
        self.pull_to_refresh("favourites: category list", "thumb_new")
        self.back(2)
        self.need("action_add_category")
        self.long_press_into("Mock Favourites", ("text", "Pin"))
        self.check("favourites: long-press opens the category menu")
        self.back()
        self.need("action_add_category")
        self.swipe(self.w // 2, self.h * 3 // 4, self.w // 2, self.h // 4)
        self.check("favourites: scroll")

    def tanks(self):
        self.nav("nav_tankoubons", "action_add_tankoubon")
        self.press(*centre(self.need_text("Mock Tank")))
        time.sleep(3)
        if find("resource-id", self.rid("action_add_tankoubon")):
            raise SmokeFailure("tanks: row press opened nothing")
        self.check("tanks: row press opens the tank reader")
        self.back(2)
        self.need("action_add_tankoubon")
        self.long_press_into("Mock Tank", ("text", "Delete tankoubon"))
        self.check("tanks: long-press opens the tank menu")
        self.back()
        self.need("action_add_tankoubon")
        self.swipe(self.w // 2, self.h * 3 // 4, self.w // 2, self.h // 4)
        self.check("tanks: scroll")

    def settings_titles(self):
        return [n["text"] for n in nodes(dump()) if n.get("resource-id") == "android:id/title"]

    def settings(self):
        """Every settings screen: open (row press), scroll to the end and back."""
        self.nav("nav_settings", "settings")
        headers = self.settings_titles()
        if len(headers) < 6:
            raise SmokeFailure(f"settings: expected 6 headers, saw {headers}")
        for header in headers:
            self.press(*centre(self.need_text(header)))
            time.sleep(1.5)
            rows = self.settings_titles()
            if rows == headers:
                raise SmokeFailure(f"settings: {header} did not open")
            for _ in range(3):
                self.swipe(self.w // 2, self.h * 3 // 4, self.w // 2, self.h // 4)
            for _ in range(3):
                self.swipe(self.w // 2, self.h // 4, self.w // 2, self.h * 3 // 4, 150)
            self.check(f"settings: {header} screen ({len(rows)} rows)")
            self.back(1.5)
            if self.settings_titles() != headers:
                raise SmokeFailure(f"settings: back from {header} did not return to the headers")
        self.to_home()

    def draw_pattern(self, cells):
        """Draw through 3x3 grid cells (0..8, row-major) on pattern_view."""
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", self.need("pattern_view", 15)["bounds"]))
        pts = [(x1 + (x2 - x1) * (2 * (c % 3) + 1) // 6, y1 + (y2 - y1) * (2 * (c // 3) + 1) // 6)
               for c in cells]
        rx, ry = 32767 / (self.w - 1), 32767 / (self.h - 1)

        def at(x, y):
            return f"EV_ABS:ABS_MT_POSITION_X:{int(x * rx)}", f"EV_ABS:ABS_MT_POSITION_Y:{int(y * ry)}"

        self.console_touch("EV_ABS:ABS_MT_SLOT:0", "EV_ABS:ABS_MT_TRACKING_ID:300",
                           "EV_ABS:ABS_MT_PRESSURE:50", *at(*pts[0]))
        for (ax, ay), (bx, by) in zip(pts, pts[1:]):
            for k in (1, 2, 3, 4):
                self.console_touch(*at(ax + (bx - ax) * k // 4, ay + (by - ay) * k // 4))
        self.console_touch("EV_ABS:ABS_MT_TRACKING_ID:-1")
        time.sleep(0.5)

    def open_pattern_settings(self):
        self.nav("nav_settings", "settings")
        self.tap(*centre(self.need_text("Privacy")))
        self.need_text("Prevent screenshots")
        self.tap_into("Pattern protection", "pattern_view")

    def background_and_return(self):
        shell("input", "keyevent", "KEYCODE_HOME")
        time.sleep(2)
        self.relaunch()

    def lock(self):
        """Pattern lock: set (draw + confirm), lock (backgrounding, else a cold
        start), reject a wrong pattern, unlock, then clear it again."""
        pattern = [0, 3, 6, 7]
        self.open_pattern_settings()
        self.draw_pattern(pattern)
        self.tap_id("set")
        time.sleep(1)
        self.draw_pattern(pattern)
        self.tap_id("set")
        self.gone("pattern_view")
        self.check("lock: pattern set")
        self.to_home()
        self.background_and_return()
        if not wait("resource-id", self.rid("pattern_view"), 8):
            # v2.3.0 behaviour on the API 35 AVD (2026-10-20): coming back
            # from the background does not re-lock; only a new process does.
            print("WARN: lock: no lock screen after backgrounding; locking via a cold start",
                  flush=True)
            shell("am", "force-stop", self.pkg)
            self.relaunch()
        self.need("pattern_view", 15)
        self.check("lock: app locked")
        self.draw_pattern([0, 1, 2, 5])
        time.sleep(1.5)
        if not find("resource-id", self.rid("pattern_view")):
            raise SmokeFailure("lock: a wrong pattern unlocked the app")
        self.draw_pattern(pattern)
        self.need("thumb_new", 15)
        self.check("lock: wrong pattern rejected, right pattern unlocks")
        self.open_pattern_settings()
        self.tap_id("set")
        self.gone("pattern_view")
        self.to_home()
        shell("am", "force-stop", self.pkg)
        self.relaunch()
        if find("resource-id", self.rid("pattern_view")):
            raise SmokeFailure("lock: still locked after clearing the pattern")
        self.need("thumb_new")
        self.check("lock: pattern cleared")

    def run(self, only):
        subprocess.run([ADB, "logcat", "-b", "crash", "-c"])
        for name in WIDGET_JOURNEYS:
            if only and name not in only:
                continue
            print(f"== {name}", flush=True)
            getattr(self, name)()


# In order: downloads opens a dozen archives, which fills History.
WIDGET_JOURNEYS = ["home", "drawer", "reader", "downloads", "history", "favourites",
                   "tanks", "settings", "lock"]


class Shots(Widgets):
    """Visual baseline: every main screen in each app theme, as PNGs named
    `<theme>/<NN>-<screen>.png`. Run after `widgets` (it needs downloads and
    history rows). The status bar is frozen with System UI demo mode so
    reruns differ only where the app does."""

    THEMES = {"light": 0, "dark": 1, "black": 2}

    def __init__(self, pkg, out):
        super().__init__(pkg)
        self.out = out
        self.n = 0

    def shot(self, name, settle=1.5):
        time.sleep(settle)
        self.n += 1
        path = os.path.join(self.out, f"{self.n:02d}-{name}.png")
        with open(path, "wb") as f:
            f.write(subprocess.run([ADB, "exec-out", "screencap", "-p"], capture_output=True).stdout)
        self.check(f"shot {os.path.relpath(path)}", 0)

    def demo_mode(self, on):
        bc = ["am", "broadcast", "-a", "com.android.systemui.demo", "-e", "command"]
        if on:
            shell("settings", "put", "global", "sysui_demo_allowed", "1")
            time.sleep(1)
            shell(*bc, "enter")
            time.sleep(1)
            shell(*bc, "clock", "-e", "hhmm", "1200")
            shell(*bc, "battery", "-e", "level", "100", "-e", "plugged", "false")
            shell(*bc, "network", "-e", "wifi", "show", "-e", "level", "4")
            shell(*bc, "notifications", "-e", "visible", "false")
        else:
            shell(*bc, "exit")
            shell("settings", "put", "global", "sysui_demo_allowed", "0")

    def set_theme(self, index):
        """Display settings -> Theme -> the index-th option (locale-free)."""
        self.nav("nav_settings", "settings")
        self.tap_into(self.settings_titles()[0], "recycler_view")
        self.tap(*centre(self.need_text("Theme")))
        options = [n for n in nodes(dump()) if n.get("resource-id") == "android:id/text1"]
        if len(options) != 3:
            raise SmokeFailure(f"theme dialog: expected 3 options, saw {len(options)}")
        self.tap(*centre(options[index]))
        time.sleep(3)
        self.to_home()

    def capture(self):
        self.home_list()
        self.shot("home")
        self.long_press_into("Mock Archive 1", ("resource-id", self.rid("batch_action_bar")))
        self.shot("home-multiselect")
        self.back()
        self.open_drawer()
        self.shot("drawer")
        self.back()
        restore = self.three_button_nav()
        try:
            self.open_right_drawer()
            self.shot("quick-search-drawer")
            self.close_right_drawer()
        finally:
            restore()
        self.demo_mode(True)                # a nav-bar overlay switch restarts System UI
        self.to_home()
        self.tap_into("Mock Archive 1", "read")
        self.shot("detail")
        self.tap_id("read")
        self.need("progress", 20)
        self.shot("reader", 3)
        # The menu auto-hides after 3 s: open the settings dialog at once,
        # then show the menu again for its own shot.
        self.tap(self.w // 2, self.h * 3 // 4)
        self.tap_id("reader_top_bar_settings", 5)
        self.need("reading_direction", 10)
        self.shot("reader-settings")
        self.back()
        self.tap(self.w // 2, self.h * 3 // 4)
        self.need("seek_bar_panel", 5)
        self.shot("reader-menu", 0.3)
        self.to_home()
        self.nav("nav_downloads", "action_start_all")
        self.shot("downloads", 3)
        self.nav("nav_history", "action_clear_all")
        self.shot("history", 2)
        self.nav("nav_favourite", "action_add_category")
        self.shot("categories")
        self.tap_into("Mock Favourites", "refresh_layout")
        self.shot("category-list", 2)
        self.nav("nav_tankoubons", "action_add_tankoubon")
        self.shot("tanks")
        self.tap(*centre(self.need_text("Mock Tank")))
        self.need("progress", 20)
        self.shot("tank-reader", 3)
        self.nav("nav_tankoubons", "action_add_tankoubon")
        self.long_press_into("Mock Tank", ("text", "Details"))
        self.tap(*centre(self.need_text("Details")))
        time.sleep(3)
        self.shot("tank-detail")
        self.nav("nav_settings", "settings")
        headers = self.settings_titles()
        self.shot("settings")
        for i, header in enumerate(headers):
            self.press(*centre(self.need_text(header)))
            self.shot(f"settings-{i + 1}-{re.sub(r'[^a-z0-9]+', '-', header.lower()).strip('-')}")
            self.back(1.5)
        self.back(1.5)
        self.open_pattern_settings()
        self.shot("set-pattern")
        self.tap_id("cancel")
        self.to_home()

    def run_themes(self, themes):
        root = self.out
        self.demo_mode(True)
        try:
            for theme in themes:
                self.set_theme(self.THEMES[theme])
                self.demo_mode(True)
                self.out = os.path.join(root, theme)
                os.makedirs(self.out, exist_ok=True)
                self.n = 0
                print(f"== {theme}", flush=True)
                self.capture()
        finally:
            self.demo_mode(False)
            self.out = root
            self.set_theme(self.THEMES["light"])  # the default theme


def shots(pkg, out, themes):
    themes = themes or list(Shots.THEMES)
    try:
        Shots(pkg, out).run_themes(themes)
    except SmokeFailure as e:
        print(f"smoke_ui shots: FAILED: {e}", file=sys.stderr)
        return 1
    print(f"smoke_ui shots: {', '.join(themes)} captured under {out}")
    return 0


def widgets(pkg, only):
    try:
        Widgets(pkg).run(only)
    except SmokeFailure as e:
        print(f"smoke_ui widgets: FAILED: {e}", file=sys.stderr)
        return 1
    print("smoke_ui widgets: all journeys passed")
    return 0


def main(argv):
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    cmd = argv[1]
    if cmd == "texts":
        print(" | ".join(n["text"] for n in nodes(dump()) if n.get("text")))
        return 0
    if cmd == "connect":
        return connect(argv[2], argv[3])
    if cmd == "widgets":
        return widgets(argv[2], argv[3:])
    if cmd == "shots":
        return shots(argv[2], argv[3], argv[4:])
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
