"""FileKit OHOS 隔离 producer；只读取固定 Git 对象并发布到独立目录。"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
GROUP = "io.github.qsxs.cpf.filekit"
UPSTREAM = "e8876bcefec8e502ac61a0af45f00de02c253fbf"
PORT_COMMIT = "f9a23a22ab1839754802535ea172c2c0891f1eb1"
TOOL_COMMIT = "d0cff88fd1ea2e4f08bb4409f1c802cca0c7f285"
TOOL_PATH = "toolchains/kotlin-native/2.4.20-0.1.0-08/"
MODULES = ("filekit-core", "filekit-dialogs")
RECIPE = (
    ".gitattributes", "FileKitPort.py", "README.md", "build.gradle.kts",
    "dependency-pins.json", "filekit-core/build.gradle.kts",
    "filekit-dialogs/build.gradle.kts", "gradle.properties",
    "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties",
    "gradlew", "gradlew.bat", "port-config.json", "settings.gradle.kts",
    "tooling-lock.json", "provenance/source-map.json",
)
TASKS = {
    "Tasks": ["tasks", "--all", "discoverFileKitPort"],
    "Resolve": ["resolveFileKitPort"],
    "Metadata": ["recordFileKitMetadata"],
    "NativeX64": ["recordFileKitX64"],
    "NativeArm64": ["recordFileKitArm64"],
    "Publish": ["publishFileKitPort"],
}
ORDER = ("Tasks", "Resolve", "Metadata", "NativeX64", "NativeArm64", "Publish")
EVIDENCE = {
    "Tasks": "tasks",
    "Resolve": "resolve",
    "Metadata": "recordFileKitMetadata",
    "NativeX64": "recordFileKitX64",
    "NativeArm64": "recordFileKitArm64",
}


def physical(path):
    path = Path(path)
    if os.name != "nt":
        return path
    absolute = os.path.abspath(path)
    if absolute.startswith("\\\\?\\"):
        return Path(absolute)
    if absolute.startswith("\\\\"):
        return Path("\\\\?\\UNC\\" + absolute[2:])
    return Path("\\\\?\\" + absolute)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def file_record(path):
    path = Path(path)
    h = hashlib.sha256()
    size = 0
    with physical(path).open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            h.update(block)
            size += len(block)
    return {"path": str(path), "size": size, "sha256": h.hexdigest()}


def read_json(path):
    return json.loads(physical(path).read_text(encoding="utf-8"))


def write_bytes(path, data):
    path = Path(path)
    physical(path.parent).mkdir(parents=True, exist_ok=True)
    with physical(path).open("xb") as output:
        output.write(data)


def write_json(path, value):
    write_bytes(path, (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))


def safe_relative(value):
    part = PurePosixPath(value)
    if not value or part.is_absolute() or "\\" in value or any(p in ("", ".", "..") for p in part.parts):
        raise RuntimeError("Unsafe relative path: " + value)
    return Path(*part.parts)


def environment(work=None):
    keys = ("PATH", "HOME", "USERPROFILE", "SystemRoot", "ComSpec", "JAVA_HOME", "LANG", "LC_ALL", "SSL_CERT_FILE", "SSL_CERT_DIR", "LD_LIBRARY_PATH")
    env = {key: os.environ[key] for key in keys if key in os.environ}
    env["GIT_NO_LAZY_FETCH"] = "1"
    if work is not None:
        for key, folder in (("GRADLE_USER_HOME", "gradle-home"), ("KONAN_DATA_DIR", "konan"),
                            ("TMPDIR", "temp"), ("TEMP", "temp"), ("TMP", "temp")):
            path = Path(work) / folder
            physical(path).mkdir(parents=True, exist_ok=True)
            env[key] = str(path)
    return env


def git_bytes(commit, path):
    safe_relative(path)
    return subprocess.check_output(["git", "-c", "core.longpaths=true", "-C", str(ROOT),
                                    "show", commit + ":" + path], env=environment())


def require_bytes(data, item, label):
    if len(data) != item["bytes"] or digest(data) != item["sha256"]:
        raise RuntimeError("Source size/SHA-256 differs: " + label)


def source_facts(commit):
    data = git_bytes(commit, "ohos/provenance/source-map.json")
    if (len(data), digest(data)) != (30908, "5a443c68ab9615be60e11c5ce4df60cf2ed3c1721a2a91fba6a8429d44053cf1"):
        raise RuntimeError("Pinned source map bytes differ")
    model = json.loads(data)
    if model["upstream"]["commit"] != UPSTREAM or model["originalPort"]["commit"] != PORT_COMMIT:
        raise RuntimeError("Source provenance revisions differ")
    rows = model["files"]
    if len(rows) != 67 or len({row["forkPath"] for row in rows}) != 67:
        raise RuntimeError("Source map must contain 67 distinct fork paths")
    blobs = {}
    by_original = {}
    for row in rows:
        path = row["forkPath"]
        safe_relative(path)
        content = git_bytes(commit, path)
        require_bytes(content, row, path)
        blobs[path] = content
        by_original.setdefault(row["originalPath"], []).append(row)
    counts = {kind: sum(row["category"] == kind for row in rows)
              for kind in ("upstream-input", "community-input", "overlay", "review-patch",
                           "abi-handler", "arkts", "original-manifest")}
    if counts != {"upstream-input": 35, "community-input": 11, "overlay": 8,
                  "review-patch": 8, "abi-handler": 2, "arkts": 1, "original-manifest": 2}:
        raise RuntimeError("Source map category counts differ")
    inputs = json.loads(blobs["ohos/provenance/source-inputs.json"])
    patches = json.loads(blobs["ohos/provenance/source-patch.json"])
    for item in inputs["files"]:
        expected_kind = item["sourceKind"] + "-input"
        matches = [row for row in by_original.get(item["rawPath"], []) if row["category"] == expected_kind]
        if len(matches) != 1:
            raise RuntimeError("Original input mapping differs: " + item["rawPath"])
        row = matches[0]
        if (row["bytes"], row["sha256"], row["sourceCommit"]) != (item["bytes"], item["sha256"], item["sourceCommit"]):
            raise RuntimeError("Original input manifest differs: " + item["rawPath"])
    if len(inputs["files"]) != 46 or len(inputs["unmodifiedMappings"]) != 35:
        raise RuntimeError("Original FileKit input counts differ")
    sources = []
    targets = set()

    def add(row, destination, kind):
        relative = safe_relative(destination).as_posix()
        if relative in targets:
            raise RuntimeError("Conflicting source destination: " + relative)
        targets.add(relative)
        parts = PurePosixPath(relative).parts
        if len(parts) < 3 or parts[0] not in MODULES or parts[1] not in (
            "commonMain", "nonWebMain", "mobileMain", "ohosMain", "ohosX64Main", "ohosArm64Main"
        ) or not relative.endswith(".kt"):
            raise RuntimeError("Unexpected compiled source path: " + relative)
        sources.append({"module": parts[0], "sourceSet": parts[1],
                        "member": "/".join(parts[1:]), "path": "sources/" + relative,
                        "forkPath": row["forkPath"], "originalPath": row["originalPath"],
                        "kind": kind, "size": row["bytes"], "sha256": row["sha256"]})

    for item in inputs["unmodifiedMappings"]:
        kind = "abi-handler" if item["rawPath"].startswith("vendor/community/") else "upstream-input"
        matches = [row for row in by_original.get(item["rawPath"], []) if row["category"] == kind]
        if len(matches) != 1:
            raise RuntimeError("Compiled input mapping differs: " + item["rawPath"])
        add(matches[0], item["compiledPath"], kind)
    if len(patches["overlays"]) != 8 or patches["metadataWaiver"] != "NONE_STANDARD_KGP_FIRST":
        raise RuntimeError("Source patch policy differs")
    for item in patches["overlays"]:
        matches = [row for row in by_original.get(item["path"], []) if row["category"] == "overlay"]
        patch = [row for row in by_original.get(item["patch"]["path"], []) if row["category"] == "review-patch"]
        if len(matches) != 1 or len(patch) != 1 or matches[0]["sha256"] != item["sha256"] or \
                matches[0]["bytes"] != item["bytes"] or patch[0]["sha256"] != item["patch"]["sha256"]:
            raise RuntimeError("Overlay or review patch differs: " + item["path"])
        for base in item["basedOn"]:
            if not any(row["sha256"] == base["sha256"] for row in by_original.get(base["path"], [])):
                raise RuntimeError("Overlay base differs: " + base["path"])
        add(matches[0], item["path"].removeprefix("overlay/"), "overlay")
    if len(patches["unmodifiedAbiHandlers"]) != 2:
        raise RuntimeError("ABI handler count differs")
    for item in patches["unmodifiedAbiHandlers"]:
        if not any(row["originalPath"] == item["rawPath"] and row["path"] == "sources/" + item["compiledPath"] for row in sources):
            raise RuntimeError("ABI handler mapping differs")
    expected_counts = {
        "filekit-core": {"commonMain": 9, "nonWebMain": 8, "ohosMain": 5},
        "filekit-dialogs": {"commonMain": 10, "nonWebMain": 2, "mobileMain": 4,
                            "ohosMain": 3, "ohosX64Main": 1, "ohosArm64Main": 1},
    }
    observed = {module: {key: sum(row["module"] == module and row["sourceSet"] == key for row in sources)
                         for key in expected_counts[module]} for module in MODULES}
    if len(sources) != 43 or observed != expected_counts:
        raise RuntimeError("Compiled source topology differs")
    arkts = next(row for row in rows if row["category"] == "arkts")
    licenses = {}
    for key in ("upstream", "community"):
        path = model[key]["licenseForkPath"]
        if path not in blobs:
            raise RuntimeError("License is absent from source map")
        licenses[key] = path
    return model, inputs, patches, sorted(sources, key=lambda row: row["path"]), arkts, licenses, blobs


def paths(args):
    work, repository = args.work.resolve(), args.repository.resolve()
    roots = (work, repository)
    for left, right in ((work, repository), (work, ROOT.resolve()), (repository, ROOT.resolve())):
        if left == right or left.is_relative_to(right) or right.is_relative_to(left):
            raise RuntimeError("Work, staging and fork checkout must be separate")
    for path in (args.native_home.resolve(), args.tooling.resolve()):
        if any(path == out or path.is_relative_to(out) or out.is_relative_to(path) for out in roots):
            raise RuntimeError("Native home and tooling must be separate from work and staging")
    return roots


def check_tools(directory):
    directory = Path(directory).resolve()
    lock = read_json(HERE / "tooling-lock.json")
    if lock["revision"] != TOOL_COMMIT or lock["repository"] != "https://github.com/qsxs/cpf-port-tooling":
        raise RuntimeError("Shared tooling revision differs")
    command = ["git", "-c", "core.longpaths=true", "-C", str(directory), "rev-parse"]
    try:
        git_root = subprocess.check_output(command + ["--show-toplevel"], env=environment(),
                                           stderr=subprocess.PIPE, text=True).strip()
        git_commit = subprocess.check_output(command + ["--verify", "HEAD^{commit}"], env=environment(),
                                             stderr=subprocess.PIPE, text=True).strip()
    except (OSError, subprocess.CalledProcessError) as error:
        raise RuntimeError("Shared tooling must be a readable Git checkout") from error
    if Path(git_root).resolve() != directory or git_commit != TOOL_COMMIT:
        raise RuntimeError("Shared tooling Git root or HEAD differs: " + git_commit)
    rows = lock["files"]
    if len(rows) != 20 or len({row["path"] for row in rows}) != 20:
        raise RuntimeError("Shared tooling file lock differs")
    for row in rows:
        if not row["path"].startswith(TOOL_PATH):
            raise RuntimeError("Unexpected tooling path")
        current = file_record(directory / safe_relative(row["path"]))
        if (current["size"], current["sha256"]) != (row["size"], row["sha256"]):
            raise RuntimeError("Shared tooling file differs: " + row["path"])
    return {"gitRoot": str(directory), "commit": git_commit, "lockedFiles": len(rows)}


def check_native(home, tooling):
    home = home.resolve()
    properties = dict(line.split("=", 1) for line in physical(home / "konan/konan.properties").read_text(encoding="utf-8").splitlines() if "=" in line)
    if properties.get("compilerVersion", "").strip() != "2.4.20-0.1.0-08":
        raise RuntimeError("Kotlin/Native version differs")
    if sys.platform.startswith("linux"):
        expected = read_json(tooling / TOOL_PATH / "bootstrap-linux.json")["nativeBundle"]
        receipt = read_json(home.parent / "bootstrap-receipt.json")
        archive = home.parent / (expected["directory"] + ".tar.gz")
        actual = file_record(archive)
        if home.name != expected["directory"] or Path(receipt["nativeHome"]).resolve() != home or \
                (actual["size"], actual["sha256"]) != (expected["bytes"], expected["sha256"]) or \
                (receipt["bytes"], receipt["sha256"]) != (expected["bytes"], expected["sha256"]):
            raise RuntimeError("Linux Native bootstrap bundle differs")
    elif os.name == "nt":
        compiler = file_record(home / "konan/lib/kotlin-native-compiler-embeddable.jar")
        if (compiler["size"], compiler["sha256"]) != (225009008, "f0db0d77740004ea5604d8647b8b555603d7deb5827339dc4b82b139d52641f1"):
            raise RuntimeError("Windows Native compiler differs")
    else:
        raise RuntimeError("Unsupported producer host")


def identity(args, facts, outputs):
    if not re.fullmatch(r"[0-9a-f]{40}", args.source_sha) or not re.fullmatch(r"[1-9][0-9]*", args.run_id) or \
            not re.fullmatch(r"[1-9][0-9]*", args.run_attempt):
        raise RuntimeError("Full source SHA and positive run identifiers are required")
    current = subprocess.check_output(["git", "-c", "core.longpaths=true", "-C", str(ROOT),
                                       "rev-parse", "HEAD"], env=environment(), text=True).strip()
    if current != args.source_sha:
        raise RuntimeError("Fork checkout does not match the requested source SHA")
    model, inputs, patches, sources, arkts, licenses, _ = facts
    config = read_json(HERE / "port-config.json")
    if (config["group"], config["upstreamCommit"], config["originalPortCommit"], config["toolingCommit"],
        config["kotlin"], config["gradle"], config["metadataPolicy"]) != (
        GROUP, UPSTREAM, PORT_COMMIT, TOOL_COMMIT, "2.4.20-0.1.0-08", "8.11.1", "STANDARD_KGP_FIRST_NO_WAIVER"
    ):
        raise RuntimeError("Build configuration identity differs")
    recipe = []
    for relative in RECIPE:
        actual = file_record(HERE / safe_relative(relative))
        recipe.append({"path": relative, "size": actual["size"], "sha256": actual["sha256"]})
    value = {"sourceCommit": args.source_sha, "upstreamCommit": UPSTREAM, "originalPortCommit": PORT_COMMIT,
             "expectedToolingCommit": TOOL_COMMIT, "group": GROUP, "modules": list(MODULES),
             "version": "0.16.0-cpf11-preview-" + args.source_sha[:12] + "-" + args.run_id + "-" + args.run_attempt,
             "recipe": recipe, "sources": sources, "arkts": arkts, "licenses": licenses}
    value["fingerprint"] = digest(json.dumps(value, sort_keys=True, separators=(",", ":")).encode())
    value.update({"work": str(outputs[0]), "repository": str(outputs[1]),
                  "nativeHome": str(args.native_home.resolve()), "tooling": str(args.tooling.resolve())})
    return value


def verify_recipe_commit(value):
    for row in value["recipe"]:
        content = git_bytes(value["sourceCommit"], "ohos/" + row["path"])
        if (len(content), digest(content)) != (row["size"], row["sha256"]):
            raise RuntimeError("Recipe differs from pinned fork commit: " + row["path"])


def prepare(value, facts):
    work, repository = Path(value["work"]), Path(value["repository"])
    for output in (work, repository):
        if output.exists():
            raise RuntimeError("Work and staging must not already exist: " + str(output))
    tooling = check_tools(Path(value["tooling"]))
    model, inputs, patches, sources, arkts, licenses, blobs = facts
    work.mkdir(parents=True, exist_ok=False)
    try:
        for row in sources:
            write_bytes(work / safe_relative(row["path"]), blobs[row["forkPath"]])
        write_bytes(work / "arkts/FileKitShareFactory.ets", blobs[arkts["forkPath"]])
        for key, path in licenses.items():
            write_bytes(work / ("legal/" + key + "-LICENSE"), blobs[path])
        manifest = {"schemaVersion": 1, "fingerprint": value["fingerprint"], "sourceCommit": value["sourceCommit"],
                    "sourceMap": model, "arktsSources": [{"path": "arkts/FileKitShareFactory.ets",
                                                          "size": arkts["bytes"], "sha256": arkts["sha256"]}],
                    "licenses": licenses}
        compiled = {"schemaVersion": 1, "fingerprint": value["fingerprint"], "files": sources}
        provenance = {"schemaVersion": 1, "fingerprint": value["fingerprint"], "sourceCommit": value["sourceCommit"],
                      "upstream": model["upstream"], "community": model["community"],
                      "originalPort": model["originalPort"], "sourceInputs": inputs, "sourcePatch": patches,
                      "compiledSources": sources, "runtime": "NOT_RUN"}
        write_json(work / "source-manifest.json", manifest)
        write_json(work / "compiled-source-manifest.json", compiled)
        write_json(work / "provenance.json", provenance)
        write_json(work / "tooling-verification.json", {"fingerprint": value["fingerprint"], **tooling})
        write_json(work / "prepared.json", value)
    except Exception as error:
        try:
            write_json(work / "prepare-failure.json", {"status": "FAILED", "fingerprint": value["fingerprint"],
                                                       "error": str(error)})
        except Exception:
            pass
        raise


def verify_prepared(value, facts):
    work = Path(value["work"])
    if read_json(work / "prepared.json") != value:
        raise RuntimeError("Prepared identity differs")
    tooling_proof = {"fingerprint": value["fingerprint"], "gitRoot": str(Path(value["tooling"]).resolve()),
                     "commit": TOOL_COMMIT, "lockedFiles": 20}
    if read_json(work / "tooling-verification.json") != tooling_proof:
        raise RuntimeError("Prepared tooling verification differs")
    model, inputs, patches, sources, arkts, licenses, blobs = facts
    manifest = read_json(work / "source-manifest.json")
    compiled = read_json(work / "compiled-source-manifest.json")
    provenance = read_json(work / "provenance.json")
    expected_manifest = {"schemaVersion": 1, "fingerprint": value["fingerprint"],
                         "sourceCommit": value["sourceCommit"], "sourceMap": model,
                         "arktsSources": [{"path": "arkts/FileKitShareFactory.ets",
                                           "size": arkts["bytes"], "sha256": arkts["sha256"]}],
                         "licenses": licenses}
    expected_provenance = {"schemaVersion": 1, "fingerprint": value["fingerprint"],
                           "sourceCommit": value["sourceCommit"], "upstream": model["upstream"],
                           "community": model["community"], "originalPort": model["originalPort"],
                           "sourceInputs": inputs, "sourcePatch": patches, "compiledSources": sources,
                           "runtime": "NOT_RUN"}
    if manifest != expected_manifest or \
            compiled != {"schemaVersion": 1, "fingerprint": value["fingerprint"], "files": sources} or \
            provenance != expected_provenance:
        raise RuntimeError("Prepared source manifests differ")
    expected = set()
    source_root = physical(work / "sources")
    for row in sources:
        path = work / safe_relative(row["path"])
        record = file_record(path)
        if (record["size"], record["sha256"]) != (row["size"], row["sha256"]):
            raise RuntimeError("Prepared Kotlin source differs: " + row["path"])
        expected.add(row["path"].removeprefix("sources/"))
    actual = {p.relative_to(source_root).as_posix() for p in source_root.rglob("*.kt") if p.is_file()}
    if actual != expected:
        raise RuntimeError("Prepared source set contains missing or unexpected Kotlin files")
    for target, source in [("arkts/FileKitShareFactory.ets", arkts["forkPath"])] + \
            [("legal/" + key + "-LICENSE", source) for key, source in licenses.items()]:
        if physical(work / target).read_bytes() != blobs[source]:
            raise RuntimeError("Prepared sidecar differs: " + target)


def stage_receipt(value, stage):
    work = Path(value["work"])
    result = read_json(work / "invocations" / (stage + ".json"))
    if result.get("fingerprint") != value["fingerprint"] or result.get("exitCode") != 0:
        raise RuntimeError("Stage did not complete successfully: " + stage)
    for key in ("stdout", "stderr"):
        if file_record(result[key]["path"]) != result[key]:
            raise RuntimeError("Stage log differs: " + stage)
    for key, filename in (("sourceManifest", "source-manifest.json"), ("compiledManifest", "compiled-source-manifest.json")):
        if result[key] != file_record(work / filename):
            raise RuntimeError("Stage source manifest differs: " + stage)
    if stage in EVIDENCE:
        evidence_path = work / "evidence" / (EVIDENCE[stage] + ".json")
        if result.get("evidence") != file_record(evidence_path):
            raise RuntimeError("Stage evidence file differs: " + stage)
        proof = read_json(evidence_path)
        if proof.get("fingerprint") != value["fingerprint"]:
            raise RuntimeError("Stage evidence identity differs: " + stage)
        if stage not in ("Tasks", "Resolve"):
            if proof["sourceManifestSha256"] != result["sourceManifest"]["sha256"] or \
                    proof["compiledSourceManifestSha256"] != result["compiledManifest"]["sha256"] or not proof["producers"]:
                raise RuntimeError("Compilation evidence differs: " + stage)
            for producer in proof["producers"]:
                if not producer["outputs"]:
                    raise RuntimeError("Compilation output evidence is empty: " + stage)
                for output in producer["outputs"]:
                    if file_record(output["path"]) != output:
                        raise RuntimeError("Compilation output differs: " + output["path"])
    return result


def invoke(value, stage):
    work, repository = Path(value["work"]), Path(value["repository"])
    for previous in ORDER[:ORDER.index(stage)]:
        stage_receipt(value, previous)
    if stage == "Publish" and repository.exists():
        raise RuntimeError("Staging already exists; refusing to overwrite")
    if stage in EVIDENCE and (work / "evidence" / (EVIDENCE[stage] + ".json")).exists():
        raise RuntimeError("Stage evidence already exists")
    logs = work / "logs"
    logs.mkdir(parents=True, exist_ok=True)
    stdout, stderr = logs / (stage + ".stdout.log"), logs / (stage + ".stderr.log")
    receipt_path = work / "invocations" / (stage + ".json")
    if stdout.exists() or stderr.exists() or receipt_path.exists():
        raise RuntimeError("Stage logs or receipt already exist; use a fresh work")
    wrapper = ["cmd.exe", "/d", "/c", str(HERE / "gradlew.bat")] if os.name == "nt" else ["sh", str(HERE / "gradlew")]
    argv = wrapper + ["-p", str(HERE), "--project-cache-dir", str(work / "project-cache"),
                      "-PportWork=" + str(work), "-PportRepository=" + str(repository),
                      "-PportFingerprint=" + value["fingerprint"], "-PportVersion=" + value["version"],
                      "-Pkotlin.project.persistent.dir=" + str(work / "kotlin-cache"),
                      "-Pkotlin.native.home=" + value["nativeHome"], "--console=plain", "--stacktrace"] + TASKS[stage]
    code, failure = None, None
    with physical(stdout).open("xb") as out, physical(stderr).open("xb") as err:
        try:
            run = subprocess.run(argv, env=environment(work), stdout=out, stderr=err, check=False)
            code = run.returncode
        except Exception as error:
            failure = str(error)
    evidence_path = work / "evidence" / (EVIDENCE[stage] + ".json") if stage in EVIDENCE else None
    receipt = {"stage": stage, "fingerprint": value["fingerprint"], "argv": argv, "exitCode": code,
               "failure": failure, "stdout": file_record(stdout), "stderr": file_record(stderr),
               "evidence": file_record(evidence_path) if evidence_path and evidence_path.is_file() else None,
               "sourceManifest": file_record(work / "source-manifest.json"),
               "compiledManifest": file_record(work / "compiled-source-manifest.json")}
    write_json(receipt_path, receipt)
    if code != 0:
        raise RuntimeError("Gradle stage failed; retained logs and receipt: " + stage + " exit=" + str(code))
    stage_receipt(value, stage)
    return receipt


def checked_url(base, url, boundary):
    path = (base / url).resolve()
    if not path.is_relative_to(boundary.resolve()):
        raise RuntimeError("Publication URL escapes its expected directory: " + url)
    return path


def published_dependencies(variants, pins, version):
    dependencies = set()
    for variant in variants:
        for dep in variant.get("dependencies", []):
            ga = dep["group"] + ":" + dep["module"]
            expected = pins.get(ga)
            if expected == "$portVersion":
                expected = version
            actual = dep.get("version", {}).get("requires")
            if expected is None or actual != expected:
                raise RuntimeError("Unpinned publication dependency: " + ga + ":" + str(actual))
            dependencies.add(ga)
    return dependencies


def require_variant_artifact(variants, directory, expected):
    if not variants:
        raise RuntimeError("Required publication variant is missing")
    for variant in variants:
        matches = []
        for entry in variant.get("files", []):
            path = checked_url(directory, entry["url"], directory)
            if path == expected.resolve():
                actual = file_record(path)
                if (entry.get("size"), entry.get("sha256")) != (actual["size"], actual["sha256"]) or not actual["size"]:
                    raise RuntimeError("Publication variant artifact size/SHA-256 differs: " + str(path))
                matches.append(entry)
        if len(matches) != 1:
            raise RuntimeError("Publication variant does not reference its published artifact: " + str(expected))


def require_root_api_redirect(variants, target, group, name, version, root_directory, gmm_path, repository):
    api = [item for item in variants if item.get("attributes", {}).get("org.gradle.usage") == "kotlin-api"
           and item.get("attributes", {}).get("org.jetbrains.kotlin.native.target") == target
           and "available-at" in item]
    if not api:
        raise RuntimeError("Root native API redirect is missing: " + name)
    links = [item["available-at"] for item in variants
             if item.get("attributes", {}).get("org.jetbrains.kotlin.native.target") == target
             and "available-at" in item]
    for link in links:
        if (link.get("group"), link.get("module"), link.get("version")) != (group, name, version) or \
                checked_url(root_directory, link["url"], repository) != gmm_path.resolve():
            raise RuntimeError("Root native redirect differs: " + name)


def verify_pom_dependencies(pom, ns, pins, version, module, suffix, target):
    core_edges = []
    for dep in pom.findall("m:dependencies/m:dependency", ns):
        group = dep.findtext("m:groupId", namespaces=ns)
        artifact = dep.findtext("m:artifactId", namespaces=ns)
        ga = group + ":" + artifact
        declared_version = dep.findtext("m:version", namespaces=ns)
        scope = dep.findtext("m:scope", namespaces=ns)
        expected = pins.get(ga)
        if expected == "$portVersion":
            expected = version
        if expected is None or declared_version != expected or scope not in ("compile", "runtime"):
            raise RuntimeError("POM dependency differs: " + ga)
        if group == GROUP and artifact in ("filekit-core", "filekit-core-ohosx64", "filekit-core-ohosarm64"):
            core_edges.append((ga, declared_version, scope))
    if module == "filekit-dialogs":
        expected_edge = (GROUP + ":filekit-core" + suffix, version, "compile" if target else "runtime")
        if core_edges != [expected_edge]:
            raise RuntimeError("Dialogs POM core dependency differs: " + module + suffix)


def verify_native_payload(archive, target):
    manifests = [item for item in archive.namelist() if item == "manifest" or item.endswith("/manifest")]
    if len(manifests) != 1:
        raise RuntimeError("Native Klib manifest count differs")
    properties = dict(line.split("=", 1) for line in archive.read(manifests[0]).decode().splitlines() if "=" in line)
    unique = properties.get("unique_name")
    if properties.get("native_targets", "").split() != [target] or not unique:
        raise RuntimeError("Native Klib target or identity differs")
    for prefix in ("default/ir/", "default/linkdata/"):
        members = [item for item in archive.infolist()
                   if item.filename.startswith(prefix) and not item.is_dir() and item.file_size > 0]
        if not members or not archive.read(members[0]):
            raise RuntimeError("Native Klib payload is empty: " + prefix)
    return unique


def source_variants(variants, target):
    expected_platform = "native" if target else "common"
    return [item for item in variants
            if item.get("attributes", {}).get("org.gradle.category") == "documentation"
            and item.get("attributes", {}).get("org.gradle.docstype") == "sources"
            and item.get("attributes", {}).get("org.gradle.usage") == "kotlin-runtime"
            and item.get("attributes", {}).get("org.jetbrains.kotlin.platform.type") == expected_platform
            and item.get("attributes", {}).get("org.jetbrains.kotlin.native.target") == target]


def require_sources_redirect(variants, target, group, name, version, root_directory, gmm_path, repository):
    matches = source_variants(variants, target)
    if not matches:
        raise RuntimeError("Root native sources redirect is missing: " + name)
    for item in matches:
        link = item.get("available-at")
        if not link or (link.get("group"), link.get("module"), link.get("version")) != (group, name, version) or \
                checked_url(root_directory, link["url"], repository) != gmm_path.resolve():
            raise RuntimeError("Root native sources redirect differs: " + name)


def verify_sources_archive(archive, sources, module, target, license_members, manifest_members):
    leaf = {None: None, "ohos_x64": "ohosX64Main", "ohos_arm64": "ohosArm64Main"}
    if target not in leaf or module not in MODULES:
        raise RuntimeError("Unexpected sources publication target")
    selected = [row for row in sources if row["module"] == module and
                (row["sourceSet"] not in ("ohosX64Main", "ohosArm64Main") or row["sourceSet"] == leaf[target])]
    expected = {row["member"]: (row["size"], row["sha256"]) for row in selected}
    expected_count = 22 if module == "filekit-core" else 20 if target else 19
    if len(expected) != len(selected) or len(expected) != expected_count:
        raise RuntimeError("Expected sources mapping differs: " + module)
    entries = [item for item in archive.infolist() if item.filename.endswith(".kt")]
    if len(entries) != len(expected) or len({item.filename for item in entries}) != len(entries):
        raise RuntimeError("Source JAR Kotlin member count differs: " + module)
    actual = {item.filename: (item.file_size, digest(archive.read(item))) for item in entries}
    if actual != expected:
        raise RuntimeError("Source JAR source mapping differs: " + module)
    for member, data in license_members.items():
        matches = [item for item in archive.infolist() if item.filename == member and not item.is_dir()]
        if len(matches) != 1 or archive.read(matches[0]) != data:
            raise RuntimeError("Source JAR license differs: " + module)
    for member, data in manifest_members.items():
        matches = [item for item in archive.infolist() if item.filename == member and not item.is_dir()]
        if len(matches) != 1 or archive.read(matches[0]) != data:
            raise RuntimeError("Source JAR source manifest differs: " + module)


def verify_publication(value, facts):
    work, repository = Path(value["work"]), Path(value["repository"])
    for stage in ORDER:
        stage_receipt(value, stage)
    pins = read_json(HERE / "dependency-pins.json")
    model, _, _, sources, arkts, licenses, blobs = facts
    published = []
    native_ids = set()
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    required_common = {
        "filekit-core": {"org.jetbrains.kotlinx:kotlinx-io-core", "org.jetbrains.kotlinx:kotlinx-coroutines-core",
                         "org.jetbrains.kotlinx:kotlinx-serialization-core"},
        "filekit-dialogs": {GROUP + ":filekit-core", "org.jetbrains.kotlinx:kotlinx-coroutines-core"},
    }
    required_platform = {
        "filekit-core": {"org.jetbrains.compose.annotation-internal:annotation"},
        "filekit-dialogs": {"org.jetbrains.kotlinx:kotlinx-serialization-json"},
    }
    for module in MODULES:
        root_directory = repository / GROUP.replace(".", "/") / module / value["version"]
        root_file = root_directory / (module + "-" + value["version"] + ".module")
        root = read_json(root_file)
        common = [item for item in root["variants"]
                  if item.get("attributes", {}).get("org.gradle.usage") == "kotlin-metadata"
                  and item.get("attributes", {}).get("org.jetbrains.kotlin.platform.type") == "common"]
        if not common or not required_common[module].issubset(published_dependencies(common, pins, value["version"])):
            raise RuntimeError("Common dependency edges differ: " + module)
        require_variant_artifact(common, root_directory, root_directory / (module + "-" + value["version"] + ".jar"))
        for suffix, target in (("", None), ("-ohosx64", "ohos_x64"), ("-ohosarm64", "ohos_arm64")):
            name = module + suffix
            directory = repository / GROUP.replace(".", "/") / name / value["version"]
            prefix = name + "-" + value["version"]
            gmm_path = directory / (prefix + ".module")
            gmm = read_json(gmm_path)
            if list(gmm)[0] != "formatVersion" or not isinstance(gmm["formatVersion"], str):
                raise RuntimeError("GMM formatVersion is missing or out of order: " + name)
            component = gmm["component"]
            if (component.get("group"), component.get("module"), component.get("version")) != (GROUP, module, value["version"]):
                raise RuntimeError("Publication owner differs: " + name)
            if target:
                owner_url = component.get("url")
                if not owner_url or checked_url(directory, owner_url, repository) != root_file.resolve():
                    raise RuntimeError("Native publication owner URL differs: " + name)
                require_root_api_redirect(root["variants"], target, GROUP, name, value["version"],
                                          root_directory, gmm_path, repository)
                api = [item for item in gmm["variants"] if item.get("attributes", {}).get("org.gradle.usage") == "kotlin-api"
                       and item.get("attributes", {}).get("org.jetbrains.kotlin.native.target") == target]
                required = required_common[module] | required_platform[module]
                if not api or not required.issubset(published_dependencies(api, pins, value["version"])):
                    raise RuntimeError("Native dependency edges differ: " + name)
                require_variant_artifact(api, directory, directory / (prefix + ".klib"))
            published_dependencies(gmm["variants"], pins, value["version"])
            files_seen = 0
            for variant in gmm["variants"]:
                for entry in variant.get("files", []):
                    path = checked_url(directory, entry["url"], directory)
                    actual = file_record(path)
                    if "sha256" not in entry or (actual["size"], actual["sha256"]) != (entry["size"], entry["sha256"]) or not actual["size"]:
                        raise RuntimeError("Published file size/SHA-256 differs: " + str(path))
                    files_seen += 1
            if not files_seen:
                raise RuntimeError("Publication has no GMM files: " + name)
            pom = ET.fromstring(physical(directory / (prefix + ".pom")).read_bytes())
            if any(pom.findtext("m:" + key, namespaces=ns) != expected for key, expected in
                   (("groupId", GROUP), ("artifactId", name), ("version", value["version"]))):
                raise RuntimeError("POM identity differs: " + name)
            if pom.findtext("m:licenses/m:license/m:name", namespaces=ns) != "MIT License" or \
                    pom.findtext("m:licenses/m:license/m:url", namespaces=ns) != "https://opensource.org/license/mit/":
                raise RuntimeError("POM license differs: " + name)
            verify_pom_dependencies(pom, ns, pins, value["version"], module, suffix, target)
            for key, source in licenses.items():
                actual = physical(directory / (prefix + "-" + key + "-license.txt")).read_bytes()
                if actual != blobs[source]:
                    raise RuntimeError("Published MIT license differs: " + name + " " + key)
            for filename, source in (("source-manifest.json", "source-manifest.json"), ("provenance.json", "provenance.json")):
                if physical(directory / (prefix + "-" + filename)).read_bytes() != physical(work / source).read_bytes():
                    raise RuntimeError("Published provenance companion differs: " + name)
            source_jar = directory / (prefix + "-sources.jar")
            if target:
                require_sources_redirect(root["variants"], target, GROUP, name, value["version"],
                                         root_directory, gmm_path, repository)
            require_variant_artifact(source_variants(gmm["variants"], target), directory, source_jar)
            license_members = {"META-INF/licenses/" + key + "-LICENSE": blobs[source] for key, source in licenses.items()}
            manifest_members = {"META-INF/upstream/" + name: physical(work / name).read_bytes()
                                for name in ("source-manifest.json", "compiled-source-manifest.json")}
            with zipfile.ZipFile(physical(source_jar)) as archive:
                verify_sources_archive(archive, sources, module, target, license_members, manifest_members)
            if target:
                klib = directory / (prefix + ".klib")
                with zipfile.ZipFile(physical(klib)) as archive:
                    unique = verify_native_payload(archive, target)
                    if (target, unique) in native_ids:
                        raise RuntimeError("Duplicate Native Klib identity: " + name)
                    native_ids.add((target, unique))
                    stage = "recordFileKitX64" if target == "ohos_x64" else "recordFileKitArm64"
                    evidence = read_json(work / "evidence" / (stage + ".json"))
                    producer = next(item for item in evidence["producers"] if item["task"] == ":" + module + ":compileKotlin" +
                                    ("OhosX64" if target == "ohos_x64" else "OhosArm64"))
                    matches = 0
                    for item in producer["outputs"]:
                        path = Path(item["path"])
                        if "/default/" in path.as_posix():
                            member = "default/" + path.as_posix().split("/default/", 1)[1]
                            data = archive.read(member)
                            if (len(data), digest(data)) != (item["size"], item["sha256"]):
                                raise RuntimeError("Published Klib member differs from compiler output: " + member)
                            matches += 1
                        elif path.suffix == ".klib":
                            if file_record(klib)["sha256"] != item["sha256"]:
                                raise RuntimeError("Published Klib differs from compiler output: " + name)
                            matches += 1
                    if not matches:
                        raise RuntimeError("No compiler output could be matched to the published Klib: " + name)
            else:
                evidence = read_json(work / "evidence/recordFileKitMetadata.json")
                producer = next(item for item in evidence["producers"] if item["task"] == ":" + module + ":allMetadataJar")
                jars = [item for item in producer["outputs"] if item["path"].endswith(".jar")]
                if len(jars) != 1 or file_record(directory / (prefix + ".jar"))["sha256"] != jars[0]["sha256"]:
                    raise RuntimeError("Published metadata differs from KGP task output: " + module)
            published.extend(file_record(path) for path in sorted(directory.iterdir()) if path.is_file())
    sidecar = repository / GROUP.replace(".", "/") / "filekit-dialogs" / value["version"] / \
        ("filekit-dialogs-" + value["version"] + "-arkts-sources.zip")
    with zipfile.ZipFile(physical(sidecar)) as archive:
        entries = archive.infolist()
        if len(entries) != 1 or entries[0].filename != "FileKitShareFactory.ets" or entries[0].is_dir() or \
                ((entries[0].external_attr >> 16) & 0o170000) not in (0, 0o100000):
            raise RuntimeError("ArkTS archive must contain exactly one regular member")
        data = archive.read(entries[0])
        if data != blobs[arkts["forkPath"]]:
            raise RuntimeError("ArkTS published bytes differ")
    archives = {path.relative_to(physical(repository)).as_posix()
                for path in physical(repository).rglob("*-arkts-sources.zip")}
    if archives != {sidecar.relative_to(repository).as_posix()}:
        raise RuntimeError("ArkTS classifier was published outside dialogs root")
    result = {"schemaVersion": 1, "status": "PRODUCER_PUBLICATION_VERIFIED_CONSUMER_RUNTIME_NOT_RUN",
              "fingerprint": value["fingerprint"], "sourceCommit": value["sourceCommit"],
              "group": GROUP, "version": value["version"], "repository": str(repository),
              "toolingVerification": read_json(work / "tooling-verification.json"),
              "publications": published, "nativeIdentities": sorted([list(item) for item in native_ids]),
              "arkts": file_record(sidecar), "consumer": "NOT_RUN", "runtime": "NOT_RUN",
              "armDevice": "NOT_RUN", "androidIosRegression": "NOT_RUN"}
    write_json(work / "publication-verification.json", result)
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("stage", choices=("Plan", "Prepare", *TASKS, "Verify"))
    parser.add_argument("--source-sha", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--run-attempt", required=True)
    parser.add_argument("--work", type=Path, required=True)
    parser.add_argument("--repository", type=Path, required=True)
    parser.add_argument("--native-home", type=Path, required=True)
    parser.add_argument("--tooling", type=Path, required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"[0-9a-f]{40}", args.source_sha) or not re.fullmatch(r"[1-9][0-9]*", args.run_id) or \
            not re.fullmatch(r"[1-9][0-9]*", args.run_attempt):
        raise RuntimeError("Full source SHA and positive run identifiers are required")
    outputs = paths(args)
    facts = source_facts(args.source_sha)
    value = identity(args, facts, outputs)
    if args.stage == "Plan":
        print(json.dumps({"stage": "Plan", **value, "recipeCommitCheck": "NOT_RUN",
                          "build": "NOT_RUN", "runtime": "NOT_RUN"}, ensure_ascii=False))
        return
    verify_recipe_commit(value)
    if args.stage == "Prepare":
        prepare(value, facts)
        verify_prepared(value, facts)
        print(json.dumps({"stage": "Prepare", "status": "SOURCES_PREPARED_BUILD_NOT_RUN",
                          "fingerprint": value["fingerprint"], "version": value["version"]}))
        return
    check_tools(Path(value["tooling"]))
    verify_prepared(value, facts)
    check_native(Path(value["nativeHome"]), Path(value["tooling"]))
    if args.stage == "Verify":
        try:
            result = verify_publication(value, facts)
        except Exception as error:
            failure = Path(value["work"]) / "verification-failure.json"
            if not failure.exists():
                write_json(failure, {"status": "FAILED", "fingerprint": value["fingerprint"], "error": str(error)})
            raise
        print(json.dumps(result, ensure_ascii=False))
        return
    print(json.dumps(invoke(value, args.stage), ensure_ascii=False))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print("FileKit port failed: " + str(error), file=sys.stderr)
        sys.exit(1)
