#!/usr/bin/env python3
"""Tiny transitive resolver over Maven Central POMs (compile/runtime scope).
Downloads jars (or aar -> classes.jar) into jars/. Highest requested version wins
for roots; transitive versions are ignored when the GA is already chosen."""
import os, re, sys, urllib.request, zipfile, io, xml.etree.ElementTree as ET

M = "https://repo1.maven.org/maven2"
HERE = os.path.dirname(os.path.abspath(__file__))
# Downloads live outside the repo (TYPECHECK_WORK, default /tmp/android-explorer-typecheck).
WORK = os.environ.get("TYPECHECK_WORK") or os.path.join(os.environ.get("TMPDIR", "/tmp"), "android-explorer-typecheck")
JARS = os.path.join(WORK, "jars")
CACHE = os.path.join(WORK, "pomcache")
os.makedirs(JARS, exist_ok=True); os.makedirs(CACHE, exist_ok=True)

ROOTS = [l.split() for l in open(os.path.join(HERE, "roots.txt")) if l.strip() and not l.startswith("#")]
# GA -> forced version (pin) ; skip GA entirely
PIN = {f"{g}:{a}": v for g, a, v in ROOTS}
SKIP_PREFIX = ("org.jetbrains.kotlin:kotlin-stdlib", "org.jetbrains.kotlin:kotlin-reflect",
               "org.jetbrains:annotations", "org.jetbrains.skiko:skiko-awt-runtime")

def fetch(path):
    local = os.path.join(CACHE, path.replace("/", "_"))
    if os.path.exists(local):
        return open(local, "rb").read() or None
    import urllib.error, time
    for attempt in range(6):
        # Maven Central, then its Apache mirror on every other try (repo1 may answer 429).
        base = M if attempt % 2 == 0 else "https://repo.maven.apache.org/maven2"
        try:
            data = urllib.request.urlopen(f"{base}/{path}", timeout=120).read()
            break
        except urllib.error.HTTPError as e:
            if e.code == 404:
                data = b""; break
            time.sleep(2)
        except Exception:
            time.sleep(2)
    else:
        print("NETFAIL", path, file=sys.stderr); return None
    open(local, "wb").write(data)
    return data or None

def strip_ns(root):
    for el in root.iter():
        if "}" in el.tag: el.tag = el.tag.split("}", 1)[1]
    return root

def pom(g, a, v):
    d = fetch(f"{g.replace('.', '/')}/{a}/{v}/{a}-{v}.pom")
    return strip_ns(ET.fromstring(d)) if d else None

def props_of(p, g, v):
    pr = {"project.version": v, "project.groupId": g, "pom.version": v}
    pe = p.find("properties")
    if pe is not None:
        for c in pe: pr[c.tag] = (c.text or "").strip()
    return pr

def subst(s, pr):
    return re.sub(r"\$\{([^}]+)\}", lambda m: pr.get(m.group(1), m.group(0)), s or "")

def deps(g, a, v):
    p = pom(g, a, v)
    if p is None: return None
    pr = props_of(p, g, v)
    managed = {}
    par = p.find("parent")
    if par is not None:
        pg, pa, pv = (par.findtext(x) for x in ("groupId", "artifactId", "version"))
        pp = pom(pg, pa, pv)
        if pp is not None:
            ppr = props_of(pp, pg, pv); ppr.update(pr); pr = ppr
            for d in pp.findall("dependencyManagement/dependencies/dependency"):
                managed[(d.findtext("groupId"), d.findtext("artifactId"))] = subst(d.findtext("version"), pr)
    for d in p.findall("dependencyManagement/dependencies/dependency"):
        managed[(d.findtext("groupId"), d.findtext("artifactId"))] = subst(d.findtext("version"), pr)
    out = []
    for d in p.findall("dependencies/dependency"):
        sc = d.findtext("scope") or "compile"
        if sc not in ("compile", "runtime") or (d.findtext("optional") or "") == "true": continue
        dg, da = subst(d.findtext("groupId"), pr), subst(d.findtext("artifactId"), pr)
        dv = subst(d.findtext("version"), pr) or managed.get((dg, da), "")
        dv = dv.strip("[]()").split(",")[0]
        out.append((dg, da, dv))
    return out, (p.findtext("packaging") or "jar")

chosen, missing = {}, []
queue = [tuple(r) for r in ROOTS]
while queue:
    g, a, v = queue.pop(0)
    ga = f"{g}:{a}"
    if ga.startswith(SKIP_PREFIX) or ga in chosen: continue
    # Google-Maven-only groups are unreachable; KMP "root" modules (no -desktop/-jvm/-android
    # suffix) only carry metadata and redirect via .module files, so skip their jars.
    if g.startswith("androidx.") and ga not in PIN:
        missing.append(f"{ga}:{v} (Google Maven only)"); chosen[ga] = None; continue
    kmp_root = (g.startswith(("org.jetbrains.compose", "org.jetbrains.androidx", "io.coil-kt"))
                and not a.endswith(("-desktop", "-jvm", "-android")) and ga not in PIN)
    if kmp_root: chosen[ga] = None; continue
    v = PIN.get(ga, v)
    r = deps(g, a, v)
    if r is None:
        missing.append(f"{ga}:{v}"); chosen[ga] = None; continue
    ds, packaging = r
    chosen[ga] = v
    base = f"{g.replace('.', '/')}/{a}/{v}/{a}-{v}"
    target = os.path.join(JARS, f"{a}-{v}.jar")
    if not os.path.exists(target):
        data = fetch(base + ".jar") if packaging != "aar" else None
        if data is None:
            aar = fetch(base + ".aar")
            if aar:
                z = zipfile.ZipFile(io.BytesIO(aar))
                data = z.read("classes.jar") if "classes.jar" in z.namelist() else None
        if data: open(target, "wb").write(data)
        elif packaging not in ("pom",): missing.append(f"{ga}:{v} (no jar/aar)")
    queue.extend(ds)

print("resolved:", len([c for c in chosen.values() if c]))
for m in missing: print("MISSING", m)
