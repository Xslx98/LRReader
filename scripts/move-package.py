"""Move a Kotlin package, or one file's top-level declarations, to another package.

Usage (from the repo root, through scripts/move-package.sh):
  move-package.sh com.lanraragi.reader.ui.widget com.lanraragi.reader.widget
      Package mode: every file of the package (and its sub-packages, main and test)
      moves; every reference to the old package prefix is rewritten.
  move-package.sh com.lanraragi.reader.download.DownloadState com.lanraragi.reader.dao.DownloadState
      File mode: the file DownloadState.kt and all its top-level declarations move
      (its <Name>Test.kt follows). Same-package users in the old package get imports,
      the moved file gets imports for what it used from its old package.
  move-package.sh com.lanraragi.reader.spider.SpiderDen com.lanraragi.reader.download.DownloadDirs
      File mode with a rename: as above, plus the simple name (and <Name>Test) is
      renamed as a whole word everywhere it is referenced.

Besides the sources it rewrites, in the same pass, everything that names classes by
package or path: layout/manifest/prefs XML, app/proguard-rules.pro,
app/src/main/baseline-prof.txt descriptors, app/lint-baseline.xml paths, the detekt
baselines, scripts/ci/coverage-floor.txt and config/architecture/*.txt.

It does not commit and does not build. Afterwards run
  ./gradlew :app:assembleAppReleaseDebug app:testAppReleaseDebugUnitTest detekt
  bash scripts/ci/check-profile-classes.sh
and review `git diff` (word renames in particular).
"""

import os
import re
import subprocess
import sys

ROOT = os.getcwd()
SRC_SETS = ["app/src/main/java", "app/src/test/java"]
EXTRA_FILES = [
    "app/proguard-rules.pro",
    "app/lint-baseline.xml",
    "app/src/main/baseline-prof.txt",
    "scripts/ci/coverage-floor.txt",
    "config/detekt/baseline.xml",
    "config/detekt/baseline-appReleaseDebug.xml",
    "config/detekt/baseline-appReleaseRelease.xml",
]
EXTRA_DIRS = ["config/architecture"]
TEXT_EXT = (".kt", ".java", ".xml", ".pro", ".txt")

TOP_DECL = re.compile(
    r"^(?:@[\w.]+(?:\([^)]*\))?\s+)*"
    r"((?:public|internal|private|protected|data|sealed|enum|abstract|open|inline|value|annotation"
    r"|const|lateinit|operator|infix|suspend|tailrec|fun\s+interface|external)\s+)*"
    r"(class|interface|object|fun|val|var|typealias)\s+"
    r"(?:<[^>]*>\s*)?"
    r"(?:[\w.]+(?:<[^>]*>)?\??\.)?"
    r"`?(\w+)`?",
    re.M,
)


def read(path):
    with open(path, encoding="utf-8", newline="") as f:
        return f.read()


def write(path, text):
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write(text)


def git(*args):
    subprocess.run(["git", *args], check=True)


def pkg_dir(src_set, pkg):
    return os.path.join(src_set, *pkg.split("."))


def all_text_files():
    out = []
    for base in ["app/src", "benchmark/src"]:
        for dp, _, fs in os.walk(base):
            for f in fs:
                if f.endswith(TEXT_EXT):
                    out.append(os.path.join(dp, f))
    for f in EXTRA_FILES:
        if os.path.exists(f) and f not in out:
            out.append(f)
    for d in EXTRA_DIRS:
        if os.path.isdir(d):
            for f in os.listdir(d):
                out.append(os.path.join(d, f))
    return sorted(set(os.path.normpath(p) for p in out))


def boundary(prefix_regex):
    # dotted name: not preceded by a word char or dot; not followed by a word char
    return re.compile(r"(?<![\w.])" + prefix_regex + r"(?!\w)")


def slash_boundary(prefix_regex, after=r"(?!\w)"):
    # path / descriptor form: preceded by a non-word char, a slash (java/com/...)
    # or the descriptor's L (HSPLcom/...)
    return re.compile(r"(?:(?<![\w.])|(?<=L))" + prefix_regex + after)


def file_package(text):
    m = re.search(r"^package\s+([\w.]+)", text, re.M)
    return m.group(1) if m else None


def top_level_names(text, include_private=False):
    names = set()
    for m in TOP_DECL.finditer(text):
        mods = m.group(1) or ""
        if "private" in mods and not include_private:
            continue
        names.add(m.group(3))
    return names


def add_imports(text, imports):
    imports = sorted(
        i for i in imports if not re.search(r"^import\s+" + re.escape(i) + r"[ \t]*\r?$", text, re.M)
    )
    if not imports:
        return text
    nl = "\r\n" if "\r\n" in text else "\n"
    block = "".join("import " + i + nl for i in imports)
    last = None
    for m in re.finditer(r"^import[ \t]+[\w.*]+", text, re.M):
        last = m
    if last:
        # insert after the newline that ends the last import line
        end = text.index("\n", last.start()) + 1
        return text[:end] + block + text[end:]
    m = re.search(r"^package[ \t]+[\w.]+", text, re.M)
    end = text.index("\n", m.start()) + 1
    return text[:end] + nl + block + text[end:]


def rewrite_all(subs, only=None):
    """subs: list of (compiled regex, replacement). Applies to every text file."""
    changed = 0
    for path in only or all_text_files():
        text = read(path)
        new = text
        for rx, rep in subs:
            new = rx.sub(rep, new)
        if new != text:
            write(path, new)
            changed += 1
    return changed


def move_package(old, new):
    moved = 0
    for src in SRC_SETS:
        od, nd = pkg_dir(src, old), pkg_dir(src, new)
        if not os.path.isdir(od):
            continue
        for dp, _, fs in os.walk(od):
            for f in fs:
                rel = os.path.relpath(os.path.join(dp, f), od)
                dst = os.path.join(nd, rel)
                if os.path.exists(dst):
                    sys.exit(f"refusing to overwrite {dst}")
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                git("mv", os.path.join(dp, f), dst)
                moved += 1
        for dp, ds, fs in os.walk(od, topdown=False):
            if not os.listdir(dp):
                os.rmdir(dp)
    if not moved:
        sys.exit(f"package {old} has no files")
    o_dot, n_dot = re.escape(old), new
    o_sl, n_sl = re.escape(old.replace(".", "/")), new.replace(".", "/")
    o_bs = re.escape(old.replace(".", "\\"))
    n = rewrite_all([
        (boundary(o_dot), n_dot),
        (slash_boundary(o_sl), n_sl),
        (boundary(o_bs), new.replace(".", "\\").replace("\\", "\\\\")),
    ])
    # A file now in `new` that imports from `new` has a redundant self-import: drop it.
    for src in SRC_SETS:
        nd = pkg_dir(src, new)
        if not os.path.isdir(nd):
            continue
        for f in os.listdir(nd):
            p = os.path.join(nd, f)
            if not f.endswith(".kt"):
                continue
            t = read(p)
            t2 = re.sub(r"^import[ \t]+" + re.escape(new) + r"\.[A-Za-z_]\w*[ \t]*\r?\n", "", t, flags=re.M)
            if t2 != t:
                write(p, t2)
    print(f"moved {moved} files, rewrote {n} files")


def move_file(old_fqn, new_fqn):
    old_pkg, old_name = old_fqn.rsplit(".", 1)
    new_pkg, new_name = new_fqn.rsplit(".", 1)
    rename = old_name != new_name
    src_main = "app/src/main/java"
    old_path = os.path.join(pkg_dir(src_main, old_pkg), old_name + ".kt")
    if not os.path.isfile(old_path):
        sys.exit(f"{old_path} not found")
    text = read(old_path)
    moved_names = top_level_names(text)
    if old_name not in moved_names:
        moved_names.add(old_name)
    stem_kt = old_name + "Kt"  # facade class of top-level functions
    # names the moved file may use from its old package
    old_pkg_names = set()
    old_dir = pkg_dir(src_main, old_pkg)
    for f in os.listdir(old_dir):
        if f.endswith(".kt") and f != old_name + ".kt":
            old_pkg_names |= top_level_names(read(os.path.join(old_dir, f)))

    moves = [(old_path, os.path.join(pkg_dir(src_main, new_pkg), new_name + ".kt"))]
    test_old = os.path.join(pkg_dir("app/src/test/java", old_pkg), old_name + "Test.kt")
    if os.path.isfile(test_old):
        moves.append((test_old, os.path.join(pkg_dir("app/src/test/java", new_pkg), new_name + "Test.kt")))
    for a, b in moves:
        if os.path.exists(b):
            sys.exit(f"refusing to overwrite {b}")
        os.makedirs(os.path.dirname(b), exist_ok=True)
        git("mv", a, b)

    # 1. package line + imports of the moved files
    for _, b in moves:
        t = read(b)
        t = re.sub(r"^package[ \t]+" + re.escape(old_pkg) + r"(?=[ \t]*\r?$)", "package " + new_pkg, t, count=1, flags=re.M)
        used = {n for n in old_pkg_names if re.search(r"(?<![\w.])" + re.escape(n) + r"\b", t)}
        t = add_imports(t, {old_pkg + "." + n for n in used})
        write(b, t)

    # 2. qualified references everywhere (imports, FQNs, descriptors, paths)
    subs = []
    for n in sorted(moved_names | {stem_kt}):
        nn = n if not rename else (new_name if n == old_name else (new_name + "Kt" if n == stem_kt else n))
        subs.append((boundary(re.escape(old_pkg + "." + n)), new_pkg + "." + nn))
        o_sl = old_pkg.replace(".", "/") + "/" + n
        subs.append((slash_boundary(re.escape(o_sl)), new_pkg.replace(".", "/") + "/" + nn))
    old_file_sl = old_pkg.replace(".", "/") + "/" + old_name
    subs.append((re.compile(re.escape(old_file_sl) + r"(?=(Test)?\.kt)"), new_pkg.replace(".", "/") + "/" + new_name))
    rewrite_all(subs)

    # 3. same-package users left in the old package need imports now
    for src in SRC_SETS:
        d = pkg_dir(src, old_pkg)
        if not os.path.isdir(d):
            continue
        for f in os.listdir(d):
            if not f.endswith(".kt"):
                continue
            p = os.path.join(d, f)
            t = read(p)
            if file_package(t) != old_pkg:
                continue
            need = {n for n in moved_names if re.search(r"(?<![\w.])" + re.escape(n) + r"\b", t)}
            if need:
                write(p, add_imports(t, {new_pkg + "." + (new_name if rename and n == old_name else n) for n in need}))
    # wildcard importers of the old package
    for path in all_text_files():
        if not path.endswith(".kt"):
            continue
        t = read(path)
        if not re.search(r"^import\s+" + re.escape(old_pkg) + r"\.\*", t, re.M):
            continue
        need = {n for n in moved_names if re.search(r"(?<![\w.])" + re.escape(n) + r"\b", t)}
        if need:
            write(path, add_imports(t, {new_pkg + "." + n for n in need}))
    # redundant self-imports in the new package
    for src in SRC_SETS:
        d = pkg_dir(src, new_pkg)
        if not os.path.isdir(d):
            continue
        for f in os.listdir(d):
            if f.endswith(".kt"):
                p = os.path.join(d, f)
                t = read(p)
                if file_package(t) == new_pkg:
                    t2 = re.sub(r"^import[ \t]+" + re.escape(new_pkg) + r"\.[A-Za-z_]\w*[ \t]*\r?\n", "", t, flags=re.M)
                    if t2 != t:
                        write(p, t2)

    # 4. rename: whole-word simple name (and <Name>Test) everywhere
    if rename:
        rx = re.compile(r"(?<!\w)" + re.escape(old_name) + r"(?=Test\b|\b)")
        n = rewrite_all([(rx, new_name)])
        print(f"renamed {old_name} -> {new_name} in {n} files (review the diff)")
    print(f"moved {len(moves)} file(s): {', '.join(b for _, b in moves)}; top-level names: {', '.join(sorted(moved_names))}")


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    old, new = sys.argv[1], sys.argv[2]
    if not os.path.isdir("app/src/main/java"):
        sys.exit("run from the repo root")
    if os.path.isdir(pkg_dir("app/src/main/java", old)):
        move_package(old, new)
    else:
        move_file(old, new)


if __name__ == "__main__":
    main()
