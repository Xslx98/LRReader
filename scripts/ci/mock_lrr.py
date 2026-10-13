"""Minimal mock LANraragi for AVD release smoke (AVD reaches host via 10.0.2.2:3939).

Endpoints: /api/info, /api/search, /api/categories, /api/archives/{id}/categories, /api/tankoubons,
/api/archives/:id/{metadata,thumbnail,files,page}, progress PUT.
Pages are generated PNGs (800x1200, distinct colors) — big enough for the
reader's tiny-image rejection. Logs every request line to stdout.
"""
import hashlib
import json
import struct
import sys
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

ARCHIVES = [
    {"arcid": f"{i:04d}" + "a" * 36, "title": f"Mock Archive {i}",
     "tags": f"artist:mock{i},language:english,parody:original,full color,content_rating:safe",
     "lastreadtime": 0, "progress": 0, "pagecount": 3, "isnew": "false",
     "extension": "zip", "filename": f"mock{i}.zip", "size": 1000 + i,
     "summary": ""}
    for i in range(1, 4)
]

# Optional slow "big" archive for download-vs-read linkage smokes:
#   MOCK_BIG_PAGES=40  -> adds "Mock Big Archive" (arcid 0004aaaa…) with N pages
#   MOCK_PAGE_DELAY=1.5 -> every /page response sleeps this many seconds
import os, time
BIG_PAGES = int(os.environ.get("MOCK_BIG_PAGES", "0") or 0)
PAGE_DELAY = float(os.environ.get("MOCK_PAGE_DELAY", "0") or 0)
#   MOCK_BIG_COUNT=3   -> that many big archives (0004…, 0005…, …) for repeat smokes
BIG_COUNT = int(os.environ.get("MOCK_BIG_COUNT", "1") or 1)
#   MOCK_ANIMATED_PAGE=path.gif -> page 1 of every archive is that file (animated-page smokes)
ANIMATED_PAGE = open(os.environ["MOCK_ANIMATED_PAGE"], "rb").read() if os.environ.get("MOCK_ANIMATED_PAGE") else None
if BIG_PAGES > 0:
    for k in range(BIG_COUNT):
        ARCHIVES.append({
            "arcid": f"{4 + k:04d}" + "a" * 36, "title": f"Mock Big Archive {k + 1}",
            "tags": "artist:mockbig,language:english", "lastreadtime": 0,
            "progress": 0, "pagecount": BIG_PAGES, "isnew": "false",
            "extension": "zip", "filename": f"mockbig{k + 1}.zip", "size": 9999, "summary": ""})

#   MOCK_CHAPTERS=1    -> adds 35 small "chapter" archives (arcids 0100…, 0101…, …)
#                         whose titles cover the member-sorting families (spec 2026-09-21 §2)
CHAPTER_TITLES = [
    "小说 第一回", "小说 第2话", "小说 第三章", "小说 第4集", "小说 第五期",
    "连载 第1话", "连载 第11话", "连载 第111话", "连载 第2话", "连载 第21话",
    "合集 1-5话", "合集 6~10话", "合集 11至15话", "合集 16-20话",
    "长篇 第3话 上", "长篇 第3话 下", "长篇 第3话（中）", "长篇 第4话 前篇", "长篇 第4话 后篇",
    "番外 第1话", "番外篇 2", "番外 第3话",
    "Series Vol.1", "Series Vol.2", "Series Vol.10", "Series Ch 3", "Series #4", "Series EP5",
    "Extra 01", "Extra 2", "Extra 10",
    "Alpha", "Gamma", "beta", "第１２话",
]
if os.environ.get("MOCK_CHAPTERS"):
    for k, title in enumerate(CHAPTER_TITLES):
        ARCHIVES.append({
            "arcid": f"{100 + k:04d}" + "c" * 36, "title": title,
            "tags": "artist:mockchapter", "lastreadtime": 0,
            "progress": 0, "pagecount": 3, "isnew": "false",
            "extension": "zip", "filename": f"chapter{k}.zip", "size": 9, "summary": ""})

# Stateful categories (real-server semantics: create/update use `param || old`,
# so an empty/omitted `search` NEVER clears a dynamic predicate; `pinned`
# omitted -> 0).
CATEGORIES = {}


def cat_json(cid):
    c = CATEGORIES[cid]
    return {"id": cid, "name": c["name"], "archives": c["archives"],
            "pinned": "1" if c["pinned"] else "0", "search": c["search"]}


def form_body(handler):
    ln = int(handler.headers.get("Content-Length", 0) or 0)
    body = handler.rfile.read(ln).decode() if ln else ""
    return {k: v[0] for k, v in parse_qs(body).items()}


def png(width, height, rgb):
    def chunk(tag, data):
        c = tag + data
        return struct.pack(">I", len(data)) + c + struct.pack(">I", zlib.crc32(c) & 0xFFFFFFFF)

    row = b"\x00" + bytes(rgb) * width
    raw = row * height
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
            + chunk(b"IDAT", zlib.compress(raw, 6)) + chunk(b"IEND", b""))


COLORS = [(200, 80, 80), (80, 200, 80), (80, 80, 200), (200, 200, 80)]
PAGES = {i: png(800, 1200, COLORS[i % len(COLORS)]) for i in range(4)}
THUMB = png(250, 350, (120, 120, 220))
TANK_THUMB = png(250, 350, (220, 140, 60))
NO_THUMB = png(250, 350, (90, 70, 60))  # stands in for the server's noThumb.png

# Stateful in-memory tankoubons:
# id -> {name, archives, summary, tags, progress, has_cover}
# has_cover models whether a generated TA/{id}.jpg exists server-side.
# Deliberately NOT set by archive-list updates (emulates a broken/lagging
# Minion worker — the user-reported scenario); only PUT thumbnail sets it.
TANKS = {}


def tank_json(tid):
    t = TANKS[tid]
    return {"id": tid, "name": t["name"], "archives": t["archives"],
            "summary": t["summary"], "tags": t["tags"], "progress": t["progress"]}


def fold_tanks(archives):
    """groupby_tanks=true: members collapse into one TANK_ pseudo-entry placed
    where the first member would have been (mirrors the 0.9.8 search fold)."""
    member_to_tank = {}
    for tid, t in TANKS.items():
        for a in t["archives"]:
            member_to_tank.setdefault(a, tid)
    by_id = {a["arcid"]: a for a in archives}
    out, seen = [], set()
    for a in archives:
        tid = member_to_tank.get(a["arcid"])
        if tid is None:
            out.append(a)
            continue
        if tid in seen:
            continue
        seen.add(tid)
        t = TANKS[tid]
        pages = sum(by_id[m]["pagecount"] for m in t["archives"] if m in by_id)
        out.append({"arcid": tid, "title": t["name"], "tags": t["tags"] or "",
                    "lastreadtime": 0, "progress": t["progress"], "pagecount": pages,
                    "isnew": "false", "extension": "", "filename": "", "size": 0,
                    "summary": t["summary"] or ""})
    return out


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _json(self, obj, code=200):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _img(self, data, ctype="image/png"):
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, fmt, *args):
        # MOCK_LOG_AUTH=1 appends a short digest of the Authorization header
        # (proves a key is still sent, e.g. after a credential-store migration).
        auth = ""
        if os.environ.get("MOCK_LOG_AUTH") == "1":
            h = self.headers.get("Authorization") if hasattr(self, "headers") else None
            auth = " auth=" + (hashlib.sha1(h.encode()).hexdigest()[:8] if h else "none")
        sys.stdout.write("%s%s\n" % (fmt % args, auth))
        sys.stdout.flush()

    def do_PUT(self):
        u = urlparse(self.path)
        parts = [p for p in u.path.split("/") if p]
        if parts[:2] == ["api", "categories"]:
            if len(parts) == 2:
                f = form_body(self)
                name = f.get("name", "")
                if not name:
                    self._json({"operation": "create_category", "success": 0,
                                "error": "Category name not specified."}, 400)
                    return
                cid = "SET_%d" % (1700000000 + len(CATEGORIES))
                CATEGORIES[cid] = {"name": name, "search": f.get("search", ""),
                                   "pinned": f.get("pinned", "") not in ("", "false"),
                                   "archives": []}
                self._json({"operation": "create_category", "success": 1,
                            "category_id": cid})
            elif len(parts) == 3 and parts[2] in CATEGORIES:
                f = form_body(self)
                c = CATEGORIES[parts[2]]
                c["name"] = f.get("name") or c["name"]
                c["search"] = f.get("search") or c["search"]
                c["pinned"] = f.get("pinned", "") not in ("", "false")
                self._json({"operation": "update_category", "success": 1,
                            "category_id": parts[2]})
            elif len(parts) == 4 and parts[2] in CATEGORIES:
                c = CATEGORIES[parts[2]]
                if parts[3] not in c["archives"]:
                    c["archives"].append(parts[3])
                self._json({"operation": "add_to_category", "success": 1})
            else:
                self._json({"operation": "update_category", "success": 0,
                            "error": "The given category does not exist."}, 400)
        elif len(parts) == 4 and parts[:2] == ["api", "archives"] and parts[3] == "metadata":
            # PUT /api/archives/{id}/metadata (query or form: title / tags / summary)
            f = {k: v[0] for k, v in parse_qs(u.query).items()}
            f.update(form_body(self))
            arc = next((a for a in ARCHIVES if a["arcid"] == parts[2]), None)
            if arc is None:
                self._json({"error": "no such archive"}, 400)
            else:
                for k in ("title", "tags", "summary"):
                    if k in f:
                        arc[k] = f[k]
                print("METADATA PUT %s tags=%r" % (parts[2][:8], f.get("tags")), flush=True)
                self._json({"operation": "update_metadata", "success": 1})
        elif len(parts) == 4 and parts[:2] == ["api", "archives"] and parts[3] == "thumbnail":
            # PUT /api/archives/{id}/thumbnail?page=N (archive cover from any page)
            q = parse_qs(u.query)
            page = int(q.get("page", ["1"])[0])
            arc = next((a for a in ARCHIVES if a["arcid"] == parts[2]), None)
            if arc is None:
                self._json({"error": "no such archive"}, 400)
            elif page < 1 or page > arc["pagecount"]:
                self._json({"operation": "update_thumbnail", "success": 0,
                            "error": "page out of range"}, 400)
            else:
                self._json({"operation": "update_thumbnail", "success": 1,
                            "new_thumbnail": f"/thumb/{parts[2]}.jpg"})
        elif parts[:2] == ["api", "tankoubons"]:
            if len(parts) == 2:
                # create (form body: name=...)
                ln = int(self.headers.get("Content-Length", 0) or 0)
                body = self.rfile.read(ln).decode() if ln else ""
                name = parse_qs(body).get("name", ["unnamed"])[0]
                tid = "TANK_%010d" % (1688000000 + len(TANKS))
                TANKS[tid] = {"name": name, "archives": [],
                              "summary": "", "tags": "", "progress": 0}
                self._json({"operation": "create_tankoubon", "success": 1,
                            "tankoubon_id": tid})
            elif len(parts) == 3:
                # whole-tank JSON update (archives and/or metadata)
                ln = int(self.headers.get("Content-Length", 0) or 0)
                body = json.loads(self.rfile.read(ln).decode() or "{}")
                t = TANKS.setdefault(parts[2], {"name": "?", "archives": [],
                                                "summary": "", "tags": "", "progress": 0})
                if "archives" in body:
                    t["archives"] = body["archives"]
                for k in ("name", "summary", "tags"):
                    if k in body.get("metadata", {}):
                        t[k] = body["metadata"][k]
                self._json({"operation": "update_tankoubon", "success": 1})
            elif len(parts) == 4 and parts[3] == "thumbnail":
                if parts[2] in TANKS:
                    TANKS[parts[2]]["has_cover"] = True
                self._json({"operation": "update_thumbnail", "success": 1})
            elif len(parts) == 5 and parts[3] == "progress":
                TANKS.setdefault(parts[2], {"name": "?", "archives": [],
                                            "summary": "", "tags": "",
                                            "progress": 0})["progress"] = int(parts[4])
                self._json({"operation": "update_progress", "success": 1})
            elif len(parts) == 4:
                # append archive to tank
                #   MOCK_TANK_FAIL_PREFIX=0105 -> appends of arcids with that prefix fail (423)
                fail_prefix = os.environ.get("MOCK_TANK_FAIL_PREFIX", "")
                if fail_prefix and parts[3].startswith(fail_prefix):
                    self._json({"operation": "add_to_tankoubon", "success": 0,
                                "error": "injected failure"}, 423)
                    return
                t = TANKS.setdefault(parts[2], {"name": "?", "archives": [],
                                                "summary": "", "tags": "", "progress": 0})
                if parts[3] not in t["archives"]:
                    t["archives"].append(parts[3])
                self._json({"operation": "add_to_tankoubon", "success": 1})
            else:
                self._json({"error": "unhandled"}, 400)
        else:
            self._json({"operation": "update_progress", "success": 1})

    def do_DELETE(self):
        u = urlparse(self.path)
        parts = [p for p in u.path.split("/") if p]
        if parts[:2] == ["api", "categories"]:
            if len(parts) == 3:
                CATEGORIES.pop(parts[2], None)
            elif len(parts) == 4 and parts[2] in CATEGORIES:
                c = CATEGORIES[parts[2]]
                c["archives"] = [a for a in c["archives"] if a != parts[3]]
            self._json({"operation": "delete_category", "success": 1})
        elif parts[:2] == ["api", "tankoubons"]:
            if len(parts) == 3:
                TANKS.pop(parts[2], None)
            elif len(parts) == 4 and parts[2] in TANKS:
                t = TANKS[parts[2]]
                t["archives"] = [a for a in t["archives"] if a != parts[3]]
            self._json({"operation": "tankoubon_delete", "success": 1})
        else:
            self._json({"operation": "clear_new", "success": 1})

    def do_HEAD(self):
        # Same status and headers as GET, no body (like Mojolicious).
        real_write = self.wfile.write
        headers_done = []

        def write_headers_only(data):
            if not headers_done:
                real_write(data)
                headers_done.append(True)
        self.wfile.write = write_headers_only
        try:
            self.do_GET()
        finally:
            self.wfile.write = real_write

    def do_GET(self):
        u = urlparse(self.path)
        q = parse_qs(u.query)
        parts = [p for p in u.path.split("/") if p]
        if u.path == "/api/info":
            self._json({
                "name": "MockLRR", "motd": "", "version": "0.9.8",
                "version_name": "mock", "version_desc": "", "has_password": "0",
                "debug_mode": "0", "nofun_mode": "0", "archives_per_page": "50",
                "server_resizes_images": "0", "server_tracks_progress": "1",
                "total_tags_stored": "3", "total_pages_read": "0",
                "total_archives": str(len(ARCHIVES)), "cache_last_cleared": "0",
            })
        elif u.path == "/api/search":
            start = int(q.get("start", ["0"])[0])
            groupby = q.get("groupby_tanks", ["false"])[0] == "true"
            rows = fold_tanks(ARCHIVES) if groupby else ARCHIVES
            # category=SET_x (static): mirrors Search.pm — the category's id list
            # intersected with the filtered rows, so a TANK_ id in a category
            # shows the tank (grouping on) and never a member that is tank-grouped.
            cat_id = q.get("category", [""])[0]
            if cat_id and cat_id in CATEGORIES and not CATEGORIES[cat_id]["search"]:
                allowed = set(CATEGORIES[cat_id]["archives"])
                rows = [r for r in rows if r["arcid"] in allowed]
            data = rows[start:start + 50]
            self._json({"data": data, "draw": 0,
                        "recordsFiltered": len(rows),
                        "recordsTotal": len(ARCHIVES)})
        elif u.path == "/api/categories":
            self._json([cat_json(c) for c in CATEGORIES])
        elif len(parts) == 3 and parts[:2] == ["api", "categories"]:
            if parts[2] in CATEGORIES:
                self._json(cat_json(parts[2]))
            else:
                self._json({"error": "The given category does not exist."}, 400)
        elif u.path == "/api/tankoubons":
            result = [tank_json(tid) for tid in TANKS]
            self._json({"draw": 0, "filtered": len(result),
                        "total": len(result), "result": result})
        elif len(parts) == 4 and parts[:2] == ["api", "tankoubons"] and parts[3] == "full":
            tid = parts[2]
            if tid not in TANKS:
                self._json({"error": "no such tankoubon"}, 400)
            else:
                full = tank_json(tid)
                by_id = {a["arcid"]: a for a in ARCHIVES}
                full["full_data"] = [by_id[i] for i in TANKS[tid]["archives"] if i in by_id]
                self._json({"filtered": 1, "total": 1, "result": full})
        elif len(parts) == 4 and parts[:2] == ["api", "tankoubons"] and parts[3] == "thumbnail":
            # Mirrors the real serve_tankoubon_thumbnail: without a generated
            # cover, ?no_fallback=true answers 202 (and queues generation),
            # the plain route serves the noThumb placeholder. With a cover,
            # the image follows the FIRST member so reorders flip it.
            tank = TANKS.get(parts[2])
            no_fallback = q.get("no_fallback", ["false"])[0] == "true"
            if tank is None or not tank.get("has_cover"):
                if no_fallback:
                    self._json({"operation": "serve_tankoubon_thumbnail",
                                "success": 1, "job": 1}, 202)
                else:
                    self._img(NO_THUMB)
            else:
                members = tank.get("archives", [])
                color = COLORS[int(members[0][:4]) % len(COLORS)] if members else (220, 140, 60)
                self._img(png(250, 350, color))
        elif len(parts) == 4 and parts[:2] == ["api", "archives"] and parts[3] == "categories":
            # Per-archive (or, 0.9.81+, per-tank) membership: static categories only.
            self._json({"operation": "find_arc_categories", "success": 1,
                        "categories": [cat_json(c) for c, v in CATEGORIES.items()
                                       if not v["search"] and parts[2] in v["archives"]]})
        elif len(parts) == 4 and parts[:2] == ["api", "archives"]:
            arcid, leaf = parts[2], parts[3]
            arc = next((a for a in ARCHIVES if a["arcid"] == arcid), None)
            if arc is None:
                self._json({"error": "no such archive"}, 400)
            elif leaf == "tankoubons":
                self._json({"tankoubons": [tid for tid, t in TANKS.items()
                                           if arcid in t["archives"]]})
            elif leaf == "metadata":
                self._json(arc)
            elif leaf == "thumbnail":
                self._img(THUMB)
            elif leaf == "files":
                base = f"/api/archives/{arcid}/page?path="
                self._json({"job": -1,
                            "pages": [base + f"mock/{n:03d}.png" for n in range(1, arc["pagecount"] + 1)]})
            elif leaf == "page":
                path = q.get("path", ["mock/001.png"])[0]
                n = int("".join(c for c in path.split("/")[-1] if c.isdigit()) or 1)
                if PAGE_DELAY > 0:
                    time.sleep(PAGE_DELAY)
                if n == 1 and ANIMATED_PAGE:
                    self._img(ANIMATED_PAGE, "image/gif")
                    return
                self._img(PAGES[n % 4])
            else:
                self._json({"error": "unhandled"}, 400)
        else:
            self._json({"error": "unhandled " + u.path}, 400)


if __name__ == "__main__":
    port = int(os.environ.get("MOCK_PORT", "3939"))
    srv = ThreadingHTTPServer(("0.0.0.0", port), H)
    print("mock LRR on :%d" % port)
    srv.serve_forever()
