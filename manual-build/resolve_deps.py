#!/usr/bin/env python3
"""Resolve the compile-time dependency closure for the TYTV Android app
without Gradle, by walking Maven POM files (Google Maven, then Central).

Usage: resolve_deps.py <out-dir>
Writes <out-dir>/artifacts.json and downloads every AAR/JAR into <out-dir>/.

Rules:
- Scopes compile/runtime only (test/provided skipped).
- Optional dependencies are INCLUDED (safer for runtime linking).
- Exclusions are honoured along each dependency path.
- On a version conflict, the highest version wins.
- Properties (${...}) resolved from the POM, its parents, and
  project.version / project.groupId / project.artifactId.
"""
import json
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

GOOGLE = "https://dl.google.com/dl/android/maven2"
CENTRAL = "https://repo1.maven.org/maven2"

ROOTS = [
    ("androidx.appcompat", "appcompat", "1.7.0"),
    ("androidx.core", "core-ktx", "1.13.1"),
    ("androidx.leanback", "leanback", "1.0.0"),
    ("androidx.lifecycle", "lifecycle-runtime-ktx", "2.8.3"),
    ("androidx.media3", "media3-exoplayer", "1.3.1"),
    ("androidx.media3", "media3-ui", "1.3.1"),
    ("org.jetbrains.kotlinx", "kotlinx-coroutines-android", "1.8.1"),
    ("org.jetbrains.kotlin", "kotlin-stdlib", "1.9.24"),
]

SKIP_SCOPES = {"test", "provided", "system"}


def curl(url, out=None):
    cmd = ["curl", "-sL", "--fail", "--max-time", "90", url]
    if out:
        cmd += ["-o", out]
        r = subprocess.run(cmd, capture_output=True)
        return r.returncode == 0
    r = subprocess.run(cmd, capture_output=True)
    return r.stdout if r.returncode == 0 else None


def group_path(group):
    return group.replace(".", "/")


def pom_url(group, artifact, version):
    return f"{GOOGLE}/{group_path(group)}/{artifact}/{version}/{artifact}-{version}.pom"


def pom_url_central(group, artifact, version):
    return f"{CENTRAL}/{group_path(group)}/{artifact}/{version}/{artifact}-{version}.pom"


def fetch_pom(group, artifact, version):
    for url in (pom_url(group, artifact, version), pom_url_central(group, artifact, version)):
        data = curl(url)
        if data:
            return data
    return None


def local(tag):
    return tag.rsplit("}", 1)[-1]


def children(elem, name):
    return [c for c in list(elem) if local(c.tag) == name]


def child(elem, name):
    for c in list(elem):
        if local(c.tag) == name:
            return c
    return None


def text(elem):
    return (elem.text or "").strip() if elem is not None else ""


def parse_pom(data):
    root = ET.fromstring(data)
    props = {}
    for p in children(child(root, "properties") or ET.Element("x"), "*"):
        props[local(p.tag)] = text(p)
    props["project.version"] = text(child(root, "version"))
    props["project.groupId"] = text(child(root, "groupId"))
    props["project.artifactId"] = text(child(root, "artifactId"))
    mgmt = {}
    dm = child(child(root, "dependencyManagement") or ET.Element("x"), "dependencies")
    if dm is not None:
        for d in children(dm, "dependency"):
            g = text(child(d, "groupId"))
            a = text(child(d, "artifactId"))
            v = text(child(d, "version"))
            if g and a and v:
                mgmt[(g, a)] = v
    deps = []
    ds = child(root, "dependencies")
    if ds is not None:
        for d in children(ds, "dependency"):
            g = text(child(d, "groupId"))
            a = text(child(d, "artifactId"))
            v = text(child(d, "version"))
            scope = text(child(d, "scope")) or "compile"
            typ = text(child(d, "type")) or "jar"
            opt = text(child(d, "optional")) == "true"
            excl = set()
            ex = child(d, "exclusions")
            if ex is not None:
                for e in children(ex, "exclusion"):
                    excl.add((text(child(e, "groupId")), text(child(e, "artifactId"))))
            if g and a:
                deps.append({
                    "group": g, "artifact": a, "version": v, "scope": scope,
                    "type": typ, "optional": opt, "exclusions": excl,
                })
    parent = None
    par = child(root, "parent")
    if par is not None:
        parent = (text(child(par, "groupId")), text(child(par, "artifactId")),
                  text(child(par, "version")))
    packaging = text(child(root, "packaging")) or "jar"
    return props, mgmt, deps, parent, packaging


def substitute(value, props):
    for _ in range(10):
        m = re.search(r"\$\{([^}]+)\}", value or "")
        if not m:
            return value
        key = m.group(1)
        if key not in props:
            return value
        value = value.replace("${" + key + "}", props[key])
    return value


def load_pom_full(group, artifact, version, depth=0):
    """Return (props, mgmt, deps, packaging) with parent POMs merged."""
    data = fetch_pom(group, artifact, version)
    if data is None:
        raise RuntimeError(f"POM not found: {group}:{artifact}:{version}")
    props, mgmt, deps, parent, packaging = parse_pom(data)
    if parent and depth < 5:
        pprops, pmgmt, _, _, _ = load_pom_full(parent[0], parent[1], parent[2], depth + 1)
        merged_props = dict(pprops)
        merged_props.update(props)
        merged_mgmt = dict(pmgmt)
        merged_mgmt.update(mgmt)
        props, mgmt = merged_props, merged_mgmt
    # substitute property refs in mgmt + deps
    mgmt = {(g, a): substitute(v, props) for (g, a), v in mgmt.items()}
    for d in deps:
        d["group"] = substitute(d["group"], props)
        d["artifact"] = substitute(d["artifact"], props)
        d["version"] = substitute(d["version"], props) if d["version"] else ""
    return props, mgmt, deps, packaging


def version_key(v):
    parts = []
    for p in re.split(r"[.\-]", v or ""):
        parts.append((0, int(p), "") if p.isdigit() else (1, 0, p))
    return parts


def main():
    out_dir = sys.argv[1]
    os.makedirs(out_dir, exist_ok=True)
    resolved = {}  # (group, artifact) -> dict
    queue = [(g, a, v, frozenset()) for (g, a, v) in ROOTS]
    seen = set()
    failures = []
    while queue:
        group, artifact, version, path_excl = queue.pop(0)
        key = (group, artifact)
        if key in path_excl:
            continue
        if key in resolved:
            if version_key(version) > version_key(resolved[key]["version"]):
                resolved[key]["version"] = version
            continue
        if (group, artifact, version) in seen:
            continue
        seen.add((group, artifact, version))
        try:
            props, mgmt, deps, packaging = load_pom_full(group, artifact, version)
        except Exception as e:
            failures.append(f"{group}:{artifact}:{version}: {e}")
            continue
        resolved[key] = {"group": group, "artifact": artifact, "version": version,
                         "packaging": packaging}
        for d in deps:
            if d["scope"] in SKIP_SCOPES:
                continue
            if d["type"] == "pom":
                continue  # BOM-style import; versions already managed above
            if (d["group"], d["artifact"]) in path_excl:
                continue
            v = d["version"]
            if not v:
                v = mgmt.get((d["group"], d["artifact"]), "")
            if not v:
                failures.append(f"no version for {d['group']}:{d['artifact']} "
                                f"(via {group}:{artifact})")
                continue
            queue.append((d["group"], d["artifact"], v,
                          path_excl | d["exclusions"]))
    # Download artifacts
    artifacts = []
    for (group, artifact), info in sorted(resolved.items()):
        version, packaging = info["version"], info["packaging"]
        ext = "aar" if packaging == "aar" else "jar"
        fname = f"{artifact}-{version}.{ext}"
        for base in (GOOGLE, CENTRAL):
            url = f"{base}/{group_path(group)}/{artifact}/{version}/{fname}"
            dest = os.path.join(out_dir, fname)
            if curl(url, out=dest) and os.path.getsize(dest) > 0:
                artifacts.append({"group": group, "artifact": artifact,
                                  "version": version, "packaging": ext,
                                  "file": fname})
                print(f"OK  {group}:{artifact}:{version} ({ext})")
                break
        else:
            failures.append(f"download failed: {group}:{artifact}:{version}")
    with open(os.path.join(out_dir, "artifacts.json"), "w") as f:
        json.dump(artifacts, f, indent=1)
    print(f"\nResolved {len(artifacts)} artifacts, {len(failures)} failures")
    for fail in failures:
        print("FAIL:", fail)


if __name__ == "__main__":
    main()
