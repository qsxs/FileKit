#!/usr/bin/env python3
"""合并标准与 CPF FileKit Maven staging 仓库。"""

from __future__ import annotations

import argparse
import copy
import hashlib
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath
from typing import Any


GROUP = "io.github.qsxs.cpf.filekit"
GROUP_PATH = Path(*GROUP.split("."))
VERSION_PREFIX = "0.16.0-cpf-unified-"
MODULES = ("filekit-core", "filekit-dialogs")
SIDECAR_SUFFIXES = (".md5", ".sha1", ".sha256", ".sha512")
HASH_ALGORITHMS = ("md5", "sha1", "sha256", "sha512")

STANDARD_LEAVES = {
    "android": {
        "target": None,
        "api_variant": "androidApiElements-published",
        "structure_variants": {"androidApiElements", "androidRuntimeElements"},
        "platform": "androidJvm",
        "artifact_extension": ".aar",
        "compiler_prefix": "2.4.20",
    },
    "iosarm64": {
        "target": "ios_arm64",
        "api_variant": "iosArm64ApiElements-published",
        "structure_variants": {"iosArm64ApiElements"},
        "platform": "native",
        "artifact_extension": ".klib",
        "compiler_prefix": "2.4.20",
    },
    "iossimulatorarm64": {
        "target": "ios_simulator_arm64",
        "api_variant": "iosSimulatorArm64ApiElements-published",
        "structure_variants": {"iosSimulatorArm64ApiElements"},
        "platform": "native",
        "artifact_extension": ".klib",
        "compiler_prefix": "2.4.20",
    },
}

OHOS_LEAVES = {
    "ohosarm64": {
        "target": "ohos_arm64",
        "api_variant": "ohosArm64ApiElements-published",
        "structure_variants": {"ohosArm64ApiElements"},
        "platform": "native",
        "artifact_extension": ".klib",
        "compiler_prefix": "2.4.21-0.1.0-13",
    },
    "ohosx64": {
        "target": "ohos_x64",
        "api_variant": "ohosX64ApiElements-published",
        "structure_variants": {"ohosX64ApiElements"},
        "platform": "native",
        "artifact_extension": ".klib",
        "compiler_prefix": "2.4.21-0.1.0-13",
    },
}

CPF_DEPENDENCY_VERSIONS = {
    ("org.jetbrains.kotlin", "kotlin-stdlib"): "2.4.21-0.1.0-13",
    ("org.jetbrains.kotlinx", "kotlinx-coroutines-core"): "1.11.0-0.1.0-07",
    ("org.jetbrains.kotlinx", "kotlinx-io-core"): "0.9.1-0.1.0-07",
    ("org.jetbrains.kotlinx", "kotlinx-serialization-core"): "1.11.0-0.1.0-07",
    ("org.jetbrains.kotlinx", "kotlinx-serialization-json"): "1.11.0-0.1.0-07",
    ("org.jetbrains.compose.runtime", "runtime"): "1.12.0-0.1.0-23",
    ("org.jetbrains.compose.ui", "ui-adaptive"): "1.12.0-0.1.0-23",
}


class MergeError(RuntimeError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise MergeError(message)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def io_path(path: Path) -> Path:
    """返回可跨越 Windows MAX_PATH 限制的文件系统路径。"""
    value = os.path.abspath(str(path))
    if os.name != "nt" or value.startswith("\\\\?\\"):
        return Path(value)
    if value.startswith("\\\\"):
        return Path("\\\\?\\UNC\\" + value.lstrip("\\"))
    return Path("\\\\?\\" + value)


def read_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_bytes())
    except (OSError, json.JSONDecodeError) as error:
        raise MergeError(f"Cannot read JSON file {path}: {error}") from error
    require(isinstance(value, dict), f"Expected a JSON object in {path}")
    return value


def json_bytes(value: Any) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def normalized_coordinate(value: Any) -> tuple[str, str, str]:
    require(isinstance(value, dict), "Expected a component coordinate object")
    return (
        str(value.get("group", "")),
        str(value.get("module", "")),
        str(value.get("version", "")),
    )


def maven_path(repository: Path, module: str, version: str) -> Path:
    return repository / GROUP_PATH / module / version


def checked_relative_url(value: str, context: str) -> str:
    path = PurePosixPath(value)
    require(
        value and not path.is_absolute() and ".." not in path.parts and "\\" not in value,
        f"Unsafe artifact URL in {context}: {value}",
    )
    return value


def variant_by_name(module_data: dict[str, Any], name: str) -> dict[str, Any]:
    matches = [variant for variant in module_data.get("variants", []) if variant.get("name") == name]
    require(len(matches) == 1, f"Expected exactly one {name} variant")
    return matches[0]


def artifact_url(variant: dict[str, Any], context: str) -> str:
    files = variant.get("files", [])
    require(files, f"Missing artifact files in {context}")
    urls = [file.get("url") or file.get("name") for file in files]
    require(all(isinstance(url, str) for url in urls), f"Invalid artifact URL in {context}")
    require(len(urls) == 1, f"Expected one primary file in {context}")
    return checked_relative_url(urls[0], context)


def root_primary_files(module_data: dict[str, Any], version_dir: Path) -> tuple[Path, Path]:
    primary_name = artifact_url(variant_by_name(module_data, "metadataApiElements"), "metadataApiElements")
    sources_name = artifact_url(variant_by_name(module_data, "metadataSourcesElements"), "metadataSourcesElements")
    primary = version_dir / Path(*PurePosixPath(primary_name).parts)
    sources = version_dir / Path(*PurePosixPath(sources_name).parts)
    require(primary.is_file(), f"Root metadata JAR does not exist: {primary}")
    require(sources.is_file(), f"Root sources JAR does not exist: {sources}")
    return primary, sources


def leaf_primary_files(
    module_data: dict[str, Any], version_dir: Path, leaf_name: str, leaf_spec: dict[str, Any]
) -> tuple[Path, Path]:
    api = variant_by_name(module_data, leaf_spec["api_variant"])
    api_file = version_dir / Path(*PurePosixPath(artifact_url(api, leaf_spec["api_variant"])).parts)
    require(api_file.is_file(), f"Leaf primary artifact does not exist: {api_file}")
    source_variants = [
        variant
        for variant in module_data.get("variants", [])
        if variant.get("name", "").lower().startswith(leaf_spec["api_variant"].removesuffix("ApiElements-published").lower())
        and "SourcesElements-published" in variant.get("name", "")
    ]
    require(len(source_variants) == 1, f"Expected one published sources variant for {leaf_name}")
    sources_name = artifact_url(source_variants[0], source_variants[0]["name"])
    sources = version_dir / Path(*PurePosixPath(sources_name).parts)
    require(sources.is_file(), f"Leaf sources JAR does not exist: {sources}")
    return api_file, sources


def read_pom(path: Path) -> tuple[ET.Element, dict[str, str]]:
    try:
        root = ET.fromstring(path.read_bytes())
    except (OSError, ET.ParseError) as error:
        raise MergeError(f"Cannot parse POM {path}: {error}") from error

    def direct_text(name: str) -> str:
        for child in list(root):
            if child.tag.split("}")[-1] == name and child.text:
                return child.text.strip()
        return ""

    coordinates = {name: direct_text(name) for name in ("groupId", "artifactId", "version")}
    require(coordinates["groupId"] == GROUP, f"Unexpected POM group in {path}: {coordinates['groupId']}")
    require(coordinates["artifactId"], f"Missing POM artifactId in {path}")
    return root, coordinates


def local_name(tag: str) -> str:
    return tag.split("}")[-1]


def direct_child(parent: ET.Element, name: str) -> ET.Element | None:
    for child in list(parent):
        if local_name(child.tag) == name:
            return child
    return None


def check_pom_coordinate(path: Path, module: str, version: str) -> ET.Element:
    root, coordinate = read_pom(path)
    require(coordinate["artifactId"] == module, f"Unexpected POM artifactId in {path}: {coordinate['artifactId']}")
    require(coordinate["version"] == version, f"Unexpected POM version in {path}: {coordinate['version']}")
    return root


def dependency_objects(module_data: dict[str, Any]) -> list[dict[str, Any]]:
    found: list[dict[str, Any]] = []
    for variant in module_data.get("variants", []):
        for dependency in variant.get("dependencies", []):
            if isinstance(dependency, dict):
                found.append(dependency)
    return found


def dependency_version(dependency: dict[str, Any]) -> str:
    version = dependency.get("version", {})
    if isinstance(version, dict):
        return str(version.get("requires") or version.get("strictly") or version.get("prefers") or "")
    return ""


def check_no_foreign_filekit_group(module_data: dict[str, Any], context: str) -> None:
    for dependency in dependency_objects(module_data):
        group = dependency.get("group", "")
        require(
            group != "io.github.vinceglb",
            f"Unexpected upstream FileKit group in {context}: {group}:{dependency.get('module')}",
        )


def write_pom(path: Path, root: ET.Element) -> None:
    namespace = root.tag.split("}")[0][1:] if root.tag.startswith("{") else ""
    if namespace:
        ET.register_namespace("", namespace)
    path.write_bytes(ET.tostring(root, encoding="utf-8", xml_declaration=True))


def parse_source_provenance(path: Path, expected_commit: str) -> dict[str, Any]:
    result = read_json(path)
    require(result.get("sourceCommit") == expected_commit, f"Source commit mismatch in provenance file {path}")
    source_hashes = result.get("sha256")
    require(isinstance(source_hashes, dict) and source_hashes, f"Missing source sha256 map in {path}")
    normalized: dict[str, str] = {}
    for source_path, digest in source_hashes.items():
        require(isinstance(source_path, str), f"Invalid source path in {path}")
        pure_path = PurePosixPath(source_path.replace("\\", "/"))
        require(
            not pure_path.is_absolute() and ".." not in pure_path.parts and bool(pure_path.parts),
            f"Unsafe source path in provenance file {path}: {source_path}",
        )
        require(isinstance(digest, str) and re.fullmatch(r"[0-9a-fA-F]{64}", digest), f"Invalid SHA-256 for {source_path}")
        normalized[pure_path.as_posix()] = digest.lower()
    result["sha256"] = normalized
    return result


def verify_source_blobs(source_root: Path | None, commit: str, provenance: dict[str, Any], path: Path) -> None:
    if source_root is None:
        return
    for source_path, expected in provenance["sha256"].items():
        spec = f"{commit}:{source_path}"
        process = subprocess.run(
            ["git", "-C", str(source_root), "show", spec],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )
        require(process.returncode == 0, f"Source path is absent from committed source {source_path}")
        require(sha256(process.stdout) == expected, f"Source SHA-256 mismatch for {source_path} from {path}")


def source_maps_equal_on_overlap(first: dict[str, Any], second: dict[str, Any], context: str) -> None:
    overlap = set(first["sha256"]) & set(second["sha256"])
    for source_path in overlap:
        require(
            first["sha256"][source_path] == second["sha256"][source_path],
            f"Producer source hashes disagree for {context}: {source_path}",
        )


def merged_root_provenance(
    standard: dict[str, Any], ohos: dict[str, Any], commit: str
) -> dict[str, Any]:
    merged = copy.deepcopy(standard)
    hashes = dict(standard["sha256"])
    for source_path, digest in ohos["sha256"].items():
        prior = hashes.get(source_path)
        require(prior is None or prior == digest, f"Conflicting source hash for {source_path}")
        hashes[source_path] = digest
    merged["sourceCommit"] = commit
    merged["sha256"] = dict(sorted(hashes.items()))
    merged["mergedProducers"] = {
        "standard": sha256(json_bytes(standard["sha256"])),
        "ohos": sha256(json_bytes(ohos["sha256"])),
    }
    return merged


def find_required_file(version_dir: Path, module: str, version: str, classifier: str) -> Path:
    path = version_dir / f"{module}-{version}-{classifier}"
    require(path.is_file(), f"Missing {classifier} classifier for {module} in {version_dir}")
    return path


def publication_assets(version_dir: Path, module: str, version: str, commit: str) -> dict[str, Any]:
    provenance_path = find_required_file(version_dir, module, version, "provenance.json")
    upstream_license = find_required_file(version_dir, module, version, "upstream-license.txt")
    community_license = find_required_file(version_dir, module, version, "community-license.txt")
    provenance = parse_source_provenance(provenance_path, commit)
    return {
        "provenance_path": provenance_path,
        "provenance": provenance,
        "upstream_license": upstream_license,
        "community_license": community_license,
    }


def load_publication(
    repository: Path, artifact_id: str, root_module: str, version: str
) -> dict[str, Any]:
    version_dir = maven_path(repository, artifact_id, version)
    require(version_dir.is_dir(), f"Missing publication directory: {version_dir}")
    module_path = version_dir / f"{artifact_id}-{version}.module"
    pom_path = version_dir / f"{artifact_id}-{version}.pom"
    require(module_path.is_file(), f"Missing Gradle module metadata: {module_path}")
    require(pom_path.is_file(), f"Missing POM file: {pom_path}")
    module_data = read_json(module_path)
    component = normalized_coordinate(module_data.get("component"))
    require(component == (GROUP, root_module, version), f"Unexpected KMP module owner in {module_path}: {component}")
    pom_root, pom_coordinate = read_pom(pom_path)
    require(pom_coordinate["artifactId"] == artifact_id, f"Unexpected POM artifactId in {pom_path}")
    require(pom_coordinate["version"] == version, f"Unexpected POM version in {pom_path}")
    require(pom_coordinate["version"] == version, f"Unexpected POM version in {pom_path}")
    return {
        "artifact_id": artifact_id,
        "root_module": root_module,
        "version": version,
        "version_dir": version_dir,
        "module_path": module_path,
        "module_data": module_data,
        "pom_path": pom_path,
        "pom_root": pom_root,
        "pom_coordinate": pom_coordinate,
    }


def expected_leaf_modules(module: str, leaves: dict[str, dict[str, Any]]) -> set[str]:
    return {f"{module}-{leaf}" for leaf in leaves}


def available_module(variant: dict[str, Any]) -> str | None:
    available = variant.get("available-at")
    if not isinstance(available, dict):
        return None
    return str(available.get("module", ""))


def validate_available_target(
    variant: dict[str, Any], module: str, version: str, expected_leaf: str
) -> None:
    available = variant.get("available-at")
    require(isinstance(available, dict), f"Missing available-at in variant {variant.get('name')}")
    require(
        normalized_coordinate(available) == (GROUP, expected_leaf, version),
        f"Unexpected target coordinate in variant {variant.get('name')}: {normalized_coordinate(available)}",
    )
    attributes = variant.get("attributes", {})
    require(isinstance(attributes, dict), f"Invalid attributes in variant {variant.get('name')}")


def root_variants_for_producer(
    module_data: dict[str, Any],
    module: str,
    version: str,
    allowed_leaves: set[str],
    context: str,
) -> list[dict[str, Any]]:
    require(normalized_coordinate(module_data.get("component")) == (GROUP, module, version), f"Invalid root component in {context}")
    selected: list[dict[str, Any]] = []
    seen_available: set[str] = set()
    metadata_names = {"metadataApiElements", "metadataSourcesElements"}
    for variant in module_data.get("variants", []):
        name = str(variant.get("name", ""))
        target = available_module(variant)
        if name in metadata_names:
            selected.append(copy.deepcopy(variant))
            continue
        if target in allowed_leaves:
            validate_available_target(variant, module, version, target)
            selected.append(copy.deepcopy(variant))
            seen_available.add(target)
    for leaf in allowed_leaves:
        require(leaf in seen_available, f"Root metadata is missing an available-at variant for {leaf} in {context}")
        require(
            any(
                available_module(variant) == leaf and variant.get("name", "").endswith("ApiElements-published")
                for variant in selected
            ),
            f"Root metadata is missing an API variant for {leaf} in {context}",
        )
    for required_name in metadata_names:
        require(sum(variant.get("name") == required_name for variant in selected) == 1, f"Missing {required_name} in {context}")
    return selected


def assert_unique_variants(variants: list[dict[str, Any]], context: str) -> None:
    names: set[str] = set()
    attribute_sets: set[tuple[str, str]] = set()
    for variant in variants:
        name = str(variant.get("name", ""))
        require(name and name not in names, f"Duplicate Gradle variant name {name} in {context}")
        names.add(name)
        attrs = variant.get("attributes", {})
        capabilities = variant.get("capabilities", [])
        signature = (json.dumps(attrs, sort_keys=True), json.dumps(capabilities, sort_keys=True))
        require(signature not in attribute_sets, f"Duplicate Gradle variant attributes in {context}: {name}")
        attribute_sets.add(signature)


def module_dependency_pairs(source_set: dict[str, Any]) -> set[str]:
    deps = source_set.get("moduleDependency", [])
    require(isinstance(deps, list), f"Invalid moduleDependency in source set {source_set.get('name')}")
    return {str(item) for item in deps}


def source_set_map(structure: dict[str, Any], context: str) -> dict[str, dict[str, Any]]:
    result: dict[str, dict[str, Any]] = {}
    for source_set in structure.get("sourceSets", []):
        name = source_set.get("name")
        require(isinstance(name, str) and name, f"Invalid source set in {context}")
        require(name not in result, f"Duplicate source set {name} in {context}")
        result[name] = copy.deepcopy(source_set)
    return result


def structure_variant_map(structure: dict[str, Any], context: str) -> dict[str, dict[str, Any]]:
    result: dict[str, dict[str, Any]] = {}
    for variant in structure.get("variants", []):
        name = variant.get("name")
        require(isinstance(name, str) and name, f"Invalid project structure variant in {context}")
        require(name not in result, f"Duplicate project structure variant {name} in {context}")
        require(isinstance(variant.get("sourceSet"), list), f"Missing sourceSet mapping for {name} in {context}")
        result[name] = copy.deepcopy(variant)
    return result


def collect_ancestors(source_sets: dict[str, dict[str, Any]], start: set[str]) -> set[str]:
    result: set[str] = set()
    pending = list(start)
    while pending:
        name = pending.pop()
        if name in result:
            continue
        require(name in source_sets, f"Source set graph references missing node {name}")
        result.add(name)
        parents = source_sets[name].get("dependsOn", [])
        require(isinstance(parents, list), f"Invalid dependsOn list for source set {name}")
        pending.extend(str(parent) for parent in parents)
    return result


def json_true(value: Any) -> bool:
    return value is True or (isinstance(value, str) and value.strip().lower() == "true")


def validate_source_set_graph(source_sets: dict[str, dict[str, Any]], context: str) -> None:
    for name, source_set in source_sets.items():
        parents = source_set.get("dependsOn", [])
        require(isinstance(parents, list), f"Invalid dependsOn list in {context}: {name}")
        for parent in parents:
            require(parent in source_sets, f"Missing parent source set {parent} for {name} in {context}")
    visiting: set[str] = set()
    visited: set[str] = set()

    def visit(name: str) -> None:
        if name in visited:
            return
        require(name not in visiting, f"Cycle in source set graph at {name} in {context}")
        visiting.add(name)
        for parent in source_sets[name].get("dependsOn", []):
            visit(parent)
        visiting.remove(name)
        visited.add(name)

    for source_set_name in source_sets:
        visit(source_set_name)


def merge_structure(
    standard_wrapper: dict[str, Any],
    ohos_wrapper: dict[str, Any],
    module: str,
) -> tuple[dict[str, Any], set[str], set[str]]:
    std = standard_wrapper.get("projectStructure")
    ohos = ohos_wrapper.get("projectStructure")
    require(isinstance(std, dict) and isinstance(ohos, dict), f"Missing projectStructure JSON for {module}")
    require(std.get("formatVersion") == ohos.get("formatVersion"), f"Kotlin structure metadata versions differ for {module}")
    require(json_true(std.get("isPublishedAsRoot")), f"Standard metadata is not a KMP root for {module}")
    require(json_true(ohos.get("isPublishedAsRoot")), f"CPF metadata is not a KMP root for {module}")

    std_sources = source_set_map(std, f"standard {module}")
    ohos_sources = source_set_map(ohos, f"OHOS {module}")
    std_variants = structure_variant_map(std, f"standard {module}")
    ohos_variants = structure_variant_map(ohos, f"OHOS {module}")

    selected_std_names: set[str] = set()
    for leaf, spec in STANDARD_LEAVES.items():
        for variant_name in spec["structure_variants"]:
            require(variant_name in std_variants, f"Missing {variant_name} source-set mapping in standard {module}")
            selected_std_names.add(variant_name)

    selected_ohos_names: set[str] = set()
    for spec in OHOS_LEAVES.values():
        for variant_name in spec["structure_variants"]:
            require(variant_name in ohos_variants, f"Missing {variant_name} source-set mapping in OHOS {module}")
            selected_ohos_names.add(variant_name)

    selected_std_variants = [std_variants[name] for name in std_variants if name in selected_std_names]
    selected_ohos_variants = [ohos_variants[name] for name in ohos_variants if name in selected_ohos_names]
    for variant in selected_ohos_variants:
        names = set(str(item) for item in variant["sourceSet"])
        require("commonMain" in names and "ohosMain" in names, f"Incomplete OHOS source set mapping for {variant['name']}")

    std_used = collect_ancestors(std_sources, {item for variant in selected_std_variants for item in variant["sourceSet"]})
    ohos_used = collect_ancestors(ohos_sources, {item for variant in selected_ohos_variants for item in variant["sourceSet"]})

    merged_sources: dict[str, dict[str, Any]] = {}
    for name in std_sources:
        if name in std_used:
            merged_sources[name] = copy.deepcopy(std_sources[name])
    for name in ohos_sources:
        if name in std_sources:
            standard_parents = sorted(str(item) for item in std_sources[name].get("dependsOn", []))
            ohos_parents = sorted(str(item) for item in ohos_sources[name].get("dependsOn", []))
            require(standard_parents == ohos_parents, f"Source-set parent mismatch for shared node {name} in {module}")
        elif name in ohos_used:
            merged_sources[name] = copy.deepcopy(ohos_sources[name])
    require("ohosMain" in merged_sources, f"OHOS metadata does not contribute ohosMain for {module}")
    validate_source_set_graph(merged_sources, f"merged {module}")

    variant_names = {variant["name"] for variant in selected_std_variants}
    require(not (variant_names & {variant["name"] for variant in selected_ohos_variants}), f"Source-set variant collision in {module}")
    merged_structure = copy.deepcopy(std)
    merged_structure["sourceSets"] = [merged_sources[name] for name in std_sources if name in merged_sources]
    merged_structure["sourceSets"].extend(
        merged_sources[name] for name in ohos_sources if name in merged_sources and name not in std_sources
    )
    merged_structure["variants"] = selected_std_variants + selected_ohos_variants
    return {**standard_wrapper, "projectStructure": merged_structure}, set(merged_sources), ohos_used


def archive_entries(path: Path) -> tuple[dict[str, bytes], dict[str, zipfile.ZipInfo]]:
    try:
        with zipfile.ZipFile(path, "r") as archive:
            values: dict[str, bytes] = {}
            infos: dict[str, zipfile.ZipInfo] = {}
            for info in archive.infolist():
                name = info.filename
                require(name not in values, f"Duplicate ZIP entry {name} in {path}")
                values[name] = archive.read(info)
                infos[name] = info
            return values, infos
    except (OSError, zipfile.BadZipFile) as error:
        raise MergeError(f"Cannot read ZIP/JAR {path}: {error}") from error


def encode_archive(entries: dict[str, bytes], infos: dict[str, zipfile.ZipInfo] | None = None) -> bytes:
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        names = sorted(entries)
        if "META-INF/MANIFEST.MF" in names:
            names.remove("META-INF/MANIFEST.MF")
            names.insert(0, "META-INF/MANIFEST.MF")
        for name in names:
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            if name.endswith("/") or (infos and name in infos and infos[name].is_dir()):
                info.filename = name.rstrip("/") + "/"
                info.external_attr = 0o40755 << 16
                info.compress_type = zipfile.ZIP_STORED
                archive.writestr(info, b"")
            else:
                archive.writestr(info, entries[name])
    return output.getvalue()


def metadata_structure(entries: dict[str, bytes], context: str) -> dict[str, Any]:
    entry_name = "META-INF/kotlin-project-structure-metadata.json"
    require(entry_name in entries, f"Missing {entry_name} in {context}")
    try:
        value = json.loads(entries[entry_name])
    except json.JSONDecodeError as error:
        raise MergeError(f"Invalid Kotlin project structure metadata in {context}: {error}") from error
    require(isinstance(value, dict), f"Invalid Kotlin project structure metadata in {context}")
    return value


def source_set_entry(name: str) -> str | None:
    if name == "META-INF" or name.startswith("META-INF/"):
        return None
    return name.split("/", 1)[0] if "/" in name else None


def merge_root_metadata_jar(
    standard_jar: Path,
    ohos_jar: Path,
    merged_wrapper: dict[str, Any],
    kept_source_sets: set[str],
    ohos_source_sets: set[str],
    context: str,
) -> bytes:
    standard_entries, standard_infos = archive_entries(standard_jar)
    ohos_entries, _ = archive_entries(ohos_jar)
    standard_structure = metadata_structure(standard_entries, f"standard {context}")
    ohos_structure = metadata_structure(ohos_entries, f"OHOS {context}")
    require(standard_structure.get("projectStructure", {}).get("formatVersion") == ohos_structure.get("projectStructure", {}).get("formatVersion"), f"Structure format mismatch in {context}")

    result: dict[str, bytes] = {}
    directories: set[str] = set()
    for name, data in standard_entries.items():
        top_level = source_set_entry(name)
        if top_level is None or top_level in kept_source_sets:
            if name.endswith("/"):
                directories.add(name)
            else:
                result[name] = data

    for name, data in ohos_entries.items():
        top_level = source_set_entry(name)
        add = False
        if top_level == "ohosMain":
            add = True
        elif top_level in ohos_source_sets:
            add = re.search(r"/default/targets/ohos_(?:x64|arm64)(?:/|$)", name) is not None
        if name == "META-INF/kotlin-project-structure-metadata.json":
            continue
        if name.endswith("/"):
            if add:
                directories.add(name)
            continue
        if not add:
            continue
        previous = result.get(name)
        require(previous is None or previous == data, f"Conflicting OHOS metadata payload in {context}: {name}")
        result[name] = data

    require(any(name.startswith("ohosMain/") for name in result), f"Missing OHOS intermediate metadata payload in {context}")
    result["META-INF/kotlin-project-structure-metadata.json"] = json_bytes(merged_wrapper)
    for name in directories:
        result.setdefault(name, b"")
    return encode_archive(result, standard_infos)


def merge_source_jar(
    standard_jar: Path,
    ohos_jar: Path,
    kept_source_sets: set[str],
    context: str,
) -> bytes:
    standard_entries, standard_infos = archive_entries(standard_jar)
    ohos_entries, _ = archive_entries(ohos_jar)
    result: dict[str, bytes] = {}
    directories: set[str] = set()
    for name, data in standard_entries.items():
        top_level = source_set_entry(name)
        if top_level is None or top_level in kept_source_sets:
            if name.endswith("/"):
                directories.add(name)
            else:
                result[name] = data
    for name, data in ohos_entries.items():
        top_level = source_set_entry(name)
        if top_level not in kept_source_sets:
            continue
        if name.endswith("/"):
            directories.add(name)
            continue
        previous = result.get(name)
        require(previous is None or previous == data, f"Source bytes differ across producers in {context}: {name}")
        result[name] = data
    require(any(name.startswith("ohosMain/") for name in result), f"Missing OHOS sources in merged {context}")
    for name in directories:
        result.setdefault(name, b"")
    return encode_archive(result, standard_infos)


def read_target_manifest(path: Path) -> dict[str, str]:
    try:
        with zipfile.ZipFile(path, "r") as archive:
            try:
                raw = archive.read("default/manifest").decode("utf-8")
            except KeyError as error:
                raise MergeError(f"Missing KLIB default/manifest in {path}") from error
    except (OSError, zipfile.BadZipFile) as error:
        raise MergeError(f"Invalid KLIB archive {path}: {error}") from error
    manifest: dict[str, str] = {}
    for line in raw.splitlines():
        if "=" not in line:
            continue
        key, value = line.split("=", 1)
        manifest[key.strip()] = value.strip()
    return manifest


def validate_leaf_artifact(path: Path, leaf_spec: dict[str, Any], root_module: str, origin: str) -> dict[str, str] | None:
    expected_extension = leaf_spec["artifact_extension"]
    require(path.suffix == expected_extension, f"Unexpected artifact type for {origin}: {path.name}")
    if expected_extension == ".klib":
        manifest = read_target_manifest(path)
        require(manifest.get("native_targets") == leaf_spec["target"], f"KLIB target mismatch in {path}")
        require(manifest.get("compiler_version", "").startswith(leaf_spec["compiler_prefix"]), f"KLIB compiler mismatch in {path}")
        unique_name = manifest.get("unique_name", "").replace("\\:", ":")
        require(unique_name.rsplit(":", 1)[-1] == root_module, f"KLIB module identity mismatch in {path}: {unique_name}")
        with zipfile.ZipFile(path, "r") as archive:
            names = set(archive.namelist())
            require("default/linkdata/module" in names and any(name.startswith("default/ir/") for name in names), f"Incomplete native KLIB in {path}")
        return manifest
    else:
        try:
            with zipfile.ZipFile(path, "r") as archive:
                names = set(archive.namelist())
        except (OSError, zipfile.BadZipFile) as error:
            raise MergeError(f"Invalid Android AAR {path}: {error}") from error
        require("AndroidManifest.xml" in names, f"Android AAR is missing AndroidManifest.xml: {path}")
        require("classes.jar" in names, f"Android AAR is missing classes.jar: {path}")
        return None


def verify_leaf_module(
    publication: dict[str, Any],
    root_module: str,
    leaf_suffix: str,
    leaf_spec: dict[str, Any],
    version: str,
    origin: str,
    require_cpf_versions: bool,
) -> tuple[Path, Path, dict[str, str] | None]:
    module_data = publication["module_data"]
    api = variant_by_name(module_data, leaf_spec["api_variant"])
    attrs = api.get("attributes", {})
    require(attrs.get("org.jetbrains.kotlin.platform.type") == leaf_spec["platform"], f"Wrong platform attribute in {leaf_spec['api_variant']}")
    if leaf_spec["target"]:
        require(attrs.get("org.jetbrains.kotlin.native.target") == leaf_spec["target"], f"Wrong native target attribute in {leaf_spec['api_variant']}")
    require(
        any(dep.get("group") == GROUP and dep.get("module") == "filekit-core" and dependency_version(dep) == version for dep in api.get("dependencies", []))
        if root_module == "filekit-dialogs"
        else not any(dep.get("group") == GROUP and dep.get("module") == "filekit-dialogs" for dep in api.get("dependencies", [])),
        f"Unexpected internal dependency in {publication['artifact_id']} {leaf_spec['api_variant']}",
    )

    if require_cpf_versions:
        for variant in module_data.get("variants", []):
            if "SourcesElements" in variant.get("name", ""):
                continue
            for dep in variant.get("dependencies", []):
                key = (str(dep.get("group", "")), str(dep.get("module", "")))
                expected = CPF_DEPENDENCY_VERSIONS.get(key)
                if expected is not None:
                    require(dependency_version(dep) == expected, f"Unexpected CPF dependency version in {publication['artifact_id']}: {key}={dependency_version(dep)}")
    check_no_foreign_filekit_group(module_data, publication["artifact_id"])
    primary, sources = leaf_primary_files(module_data, publication["version_dir"], leaf_suffix, leaf_spec)
    manifest = validate_leaf_artifact(primary, leaf_spec, root_module, origin)
    return primary, sources, manifest


def verify_projected_root_dependencies(module: str, root_data: dict[str, Any], version: str) -> list[dict[str, Any]]:
    metadata = variant_by_name(root_data, "metadataApiElements")
    dependencies = copy.deepcopy(metadata.get("dependencies", []))
    core_deps = [
        dependency
        for dependency in dependencies
        if dependency.get("group") == GROUP and dependency.get("module") == "filekit-core"
    ]
    if module == "filekit-dialogs":
        require(len(core_deps) == 1 and dependency_version(core_deps[0]) == version, "Standard dialogs root metadata must use the unified core GAV")
    else:
        require(not core_deps, "Core root metadata must not depend on itself")
    for dependency in dependencies:
        require(dependency.get("group") != "io.github.vinceglb", "Standard root metadata still references upstream FileKit group")
    return dependencies


def verify_ohos_source_dependencies(
    root_publication: dict[str, Any], leaf_publications: dict[str, dict[str, Any]], module: str, version: str
) -> None:
    root_primary, _ = root_primary_files(root_publication["module_data"], root_publication["version_dir"])
    root_entries, _ = archive_entries(root_primary)
    structure = metadata_structure(root_entries, f"OHOS {module} root JAR").get("projectStructure", {})
    source_sets = source_set_map(structure, f"OHOS {module}")
    require("ohosMain" in source_sets, f"OHOS root metadata omits ohosMain for {module}")
    target_mappings = structure_variant_map(structure, f"OHOS {module}")
    target_source_sets: set[str] = set()
    for spec in OHOS_LEAVES.values():
        for variant_name in spec["structure_variants"]:
            require(variant_name in target_mappings, f"OHOS root metadata omits {variant_name} for {module}")
            target_source_sets.update(str(item) for item in target_mappings[variant_name]["sourceSet"])
    target_source_sets = collect_ancestors(source_sets, target_source_sets)
    required = set().union(*(module_dependency_pairs(source_sets[name]) for name in target_source_sets))
    for leaf, publication in leaf_publications.items():
        spec = OHOS_LEAVES[leaf]
        api = variant_by_name(publication["module_data"], spec["api_variant"])
        provided = {
            f"{dependency.get('group', '')}:{dependency.get('module', '')}"
            for dependency in api.get("dependencies", [])
        }
        missing = required - provided
        require(not missing, f"OHOS {publication['artifact_id']} leaf does not provide ohosMain dependencies: {sorted(missing)}")
        if module == "filekit-dialogs":
            core = [
                dependency
                for dependency in api.get("dependencies", [])
                if dependency.get("group") == GROUP and dependency.get("module") == "filekit-core"
            ]
            require(len(core) == 1 and dependency_version(core[0]) == version, f"OHOS dialogs leaf has the wrong core edge: {publication['artifact_id']}")


def verify_dialogs_pom_dependencies(
    root: ET.Element, module: str, version: str, expected_core_module: str, required: bool
) -> None:
    dependencies = direct_child(root, "dependencies")
    hits: list[str] = []
    if dependencies is not None:
        for dependency in list(dependencies):
            if local_name(dependency.tag) != "dependency":
                continue
            group = direct_child(dependency, "groupId")
            artifact = direct_child(dependency, "artifactId")
            dep_version = direct_child(dependency, "version")
            if group is None or artifact is None or dep_version is None:
                continue
            if (group.text or "").strip() == GROUP and (artifact.text or "").strip().startswith("filekit-core"):
                hits.append((dep_version.text or "").strip())
                require((artifact.text or "").strip() == expected_core_module, f"Wrong FileKit core artifact in {module} POM")
    require(not required or hits == [version], f"POM dependency mismatch for {module}: {hits}")


def gmm_dependencies_by_variant(module_data: dict[str, Any]) -> dict[str, list[dict[str, Any]]]:
    return {
        str(variant.get("name")): copy.deepcopy(variant.get("dependencies", []))
        for variant in module_data.get("variants", [])
        if variant.get("name")
    }


def merge_root_module_metadata(
    standard_data: dict[str, Any], ohos_data: dict[str, Any], module: str, version: str
) -> dict[str, Any]:
    standard_allowed = expected_leaf_modules(module, STANDARD_LEAVES)
    ohos_allowed = expected_leaf_modules(module, OHOS_LEAVES)
    standard_variants = root_variants_for_producer(standard_data, module, version, standard_allowed, f"standard {module}")
    ohos_variants = root_variants_for_producer(ohos_data, module, version, ohos_allowed, f"OHOS {module}")
    selected_standard_names = {variant.get("name") for variant in standard_variants}
    result = copy.deepcopy(standard_data)
    merged_variants = standard_variants + [
        copy.deepcopy(variant)
        for variant in ohos_variants
        if variant.get("name") not in {"metadataApiElements", "metadataSourcesElements"}
    ]
    for variant in merged_variants:
        target = available_module(variant)
        if target:
            expected = standard_allowed | ohos_allowed
            require(target in expected, f"Unsupported target reference in final {module} metadata: {target}")
            validate_available_target(variant, module, version, target)
    require(selected_standard_names.issubset({variant.get("name") for variant in merged_variants}), "Standard variants were lost during merge")
    assert_unique_variants(merged_variants, module)
    result["variants"] = merged_variants
    return result


def update_gmm_file_hashes(module_data: dict[str, Any], version_dir: Path) -> None:
    for variant in module_data.get("variants", []):
        for item in variant.get("files", []):
            value = item.get("url") or item.get("name")
            if not isinstance(value, str):
                continue
            relative = checked_relative_url(value, f"variant {variant.get('name')}")
            path = version_dir / Path(*PurePosixPath(relative).parts)
            require(path.is_file(), f"GMM file reference is missing: {path}")
            data = path.read_bytes()
            item["size"] = len(data)
            for algorithm in HASH_ALGORITHMS:
                item[algorithm] = hashlib.new(algorithm, data).hexdigest()


def copy_version_files(source_dir: Path, destination_dir: Path, exclusions: set[str] | None = None) -> None:
    exclusions = exclusions or set()
    destination_dir.mkdir(parents=True, exist_ok=True)
    for path in source_dir.iterdir():
        if not path.is_file() or path.name in exclusions or path.name == "maven-metadata.xml":
            continue
        if any(path.name.endswith(suffix) for suffix in SIDECAR_SUFFIXES):
            continue
        if "arkts-sources" in path.name.lower():
            continue
        shutil.copyfile(path, destination_dir / path.name)


def write_sidecars(path: Path) -> None:
    data = path.read_bytes()
    for algorithm in HASH_ALGORITHMS:
        checksum_path = path.with_name(path.name + "." + algorithm)
        checksum_path.write_text(hashlib.new(algorithm, data).hexdigest(), encoding="ascii")


def write_maven_metadata(path: Path, group: str, module: str, version: str, prior_versions: set[str] | None = None) -> None:
    versions = set(prior_versions or set())
    versions.add(version)
    ordered = sorted(versions)
    metadata = ET.Element("metadata")
    ET.SubElement(metadata, "groupId").text = group
    ET.SubElement(metadata, "artifactId").text = module
    versioning = ET.SubElement(metadata, "versioning")
    ET.SubElement(versioning, "latest").text = ordered[-1]
    ET.SubElement(versioning, "release").text = ordered[-1]
    version_node = ET.SubElement(versioning, "versions")
    for item in ordered:
        ET.SubElement(version_node, "version").text = item
    ET.SubElement(versioning, "lastUpdated").text = datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(ET.tostring(metadata, encoding="utf-8", xml_declaration=True))


def read_existing_versions(existing_repository: Path | None, module: str) -> set[str]:
    if existing_repository is None:
        return set()
    path = existing_repository / GROUP_PATH / module / "maven-metadata.xml"
    if not path.is_file():
        return set()
    try:
        root = ET.fromstring(path.read_bytes())
    except ET.ParseError as error:
        raise MergeError(f"Invalid existing Maven metadata {path}: {error}") from error
    versions: set[str] = set()
    for element in root.iter():
        if local_name(element.tag) == "version" and element.text:
            versions.add(element.text.strip())
    return versions


def merge_root_artifacts(
    standard_root: dict[str, Any],
    ohos_root: dict[str, Any],
    output_root_dir: Path,
    module: str,
    version: str,
    commit: str,
    source_root: Path | None,
) -> dict[str, Any]:
    standard_version_dir = standard_root["version_dir"]
    ohos_version_dir = ohos_root["version_dir"]
    standard_primary, standard_sources = root_primary_files(standard_root["module_data"], standard_version_dir)
    ohos_primary, ohos_sources = root_primary_files(ohos_root["module_data"], ohos_version_dir)

    standard_wrapper = metadata_structure(archive_entries(standard_primary)[0], f"standard {module} root JAR")
    ohos_wrapper = metadata_structure(archive_entries(ohos_primary)[0], f"OHOS {module} root JAR")
    merged_wrapper, kept_source_sets, ohos_source_sets = merge_structure(standard_wrapper, ohos_wrapper, module)

    output_version_dir = output_root_dir / GROUP_PATH / module / version
    copy_version_files(standard_version_dir, output_version_dir)

    root_provenance_standard = publication_assets(standard_version_dir, module, version, commit)
    root_provenance_ohos = publication_assets(ohos_version_dir, module, version, commit)
    source_maps_equal_on_overlap(root_provenance_standard["provenance"], root_provenance_ohos["provenance"], module)
    verify_source_blobs(source_root, commit, root_provenance_standard["provenance"], root_provenance_standard["provenance_path"])
    verify_source_blobs(source_root, commit, root_provenance_ohos["provenance"], root_provenance_ohos["provenance_path"])
    require(
        root_provenance_standard["upstream_license"].read_bytes() == root_provenance_ohos["upstream_license"].read_bytes(),
        f"Upstream license differs between producers for {module}",
    )
    require(
        root_provenance_standard["community_license"].read_bytes() == root_provenance_ohos["community_license"].read_bytes(),
        f"Community license differs between producers for {module}",
    )

    merged_primary = merge_root_metadata_jar(
        standard_primary,
        ohos_primary,
        merged_wrapper,
        kept_source_sets,
        ohos_source_sets,
        module,
    )
    merged_sources = merge_source_jar(standard_sources, ohos_sources, kept_source_sets, module)
    shutil.copyfile(standard_primary, output_version_dir / standard_primary.name)
    shutil.copyfile(standard_sources, output_version_dir / standard_sources.name)
    (output_version_dir / standard_primary.name).write_bytes(merged_primary)
    (output_version_dir / standard_sources.name).write_bytes(merged_sources)

    merged_provenance = merged_root_provenance(
        root_provenance_standard["provenance"], root_provenance_ohos["provenance"], commit
    )
    provenance_name = root_provenance_standard["provenance_path"].name
    (output_version_dir / provenance_name).write_bytes(json_bytes(merged_provenance))

    standard_module = merge_root_module_metadata(
        standard_root["module_data"], ohos_root["module_data"], module, version
    )
    canonical_dependencies = verify_projected_root_dependencies(module, standard_module, version)
    require(
        canonical_dependencies == verify_projected_root_dependencies(module, standard_root["module_data"], version),
        f"Standard root metadata dependencies changed during merge for {module}",
    )
    update_gmm_file_hashes(standard_module, output_version_dir)
    (output_version_dir / standard_root["module_path"].name).write_bytes(json_bytes(standard_module))

    root_pom_path = output_version_dir / standard_root["pom_path"].name
    root_pom = check_pom_coordinate(root_pom_path, module, version)
    verify_dialogs_pom_dependencies(root_pom, module, version, "filekit-core", module == "filekit-dialogs")
    return {
        "version_dir": output_version_dir,
        "merged_variants": [variant.get("name") for variant in standard_module.get("variants", [])],
        "source_sets": sorted(kept_source_sets),
        "source_metadata": merged_wrapper,
    }


def root_leaf_variants(module_data: dict[str, Any], allowed: set[str], context: str) -> list[dict[str, Any]]:
    variants = []
    for variant in module_data.get("variants", []):
        target = available_module(variant)
        if target in allowed:
            variants.append(variant)
    require(variants, f"No allowed target variants in {context}")
    return variants


def check_root_metadata_dependencies_unchanged(
    root_module_path: Path, expected_dependencies: list[dict[str, Any]], module: str, version: str
) -> None:
    data = read_json(root_module_path)
    dependencies = variant_by_name(data, "metadataApiElements").get("dependencies", [])
    require(dependencies == expected_dependencies, f"Root metadataApiElements dependencies changed for {module}")
    verify_projected_root_dependencies(module, data, version)


def verify_root_structure_json(path: Path, module: str) -> set[str]:
    entries, _ = archive_entries(path)
    wrapper = metadata_structure(entries, f"final {module} root JAR")
    structure = wrapper.get("projectStructure", {})
    sources = source_set_map(structure, f"final {module}")
    variants = structure_variant_map(structure, f"final {module}")
    require("ohosArm64ApiElements" in variants and "ohosX64ApiElements" in variants, f"Final {module} metadata omits an OHOS ABI mapping")
    standard_names = {
        name
        for spec in STANDARD_LEAVES.values()
        for name in spec["structure_variants"]
    }
    require(standard_names.issubset(variants), f"Final {module} metadata omits supported Android/iOS mappings")
    for name in ("ohosArm64ApiElements", "ohosX64ApiElements"):
        mapped = set(variants[name]["sourceSet"])
        require("ohosMain" in mapped and "commonMain" in mapped, f"Incomplete {name} metadata mapping")
    validate_source_set_graph(sources, f"final {module}")
    for variant in structure.get("variants", []):
        for source_set in variant.get("sourceSet", []):
            require(source_set in sources, f"Final {module} mapping references missing source set {source_set}")
    return set(sources)


def verify_checksum_files(repository: Path) -> None:
    for path in repository.rglob("*"):
        if not path.is_file() or any(path.name.endswith(suffix) for suffix in SIDECAR_SUFFIXES):
            continue
        data = path.read_bytes()
        for algorithm in HASH_ALGORITHMS:
            sidecar = path.with_name(path.name + "." + algorithm)
            require(sidecar.is_file(), f"Missing {algorithm} checksum for {path}")
            actual = sidecar.read_text(encoding="ascii").strip().lower()
            expected = hashlib.new(algorithm, data).hexdigest()
            require(actual == expected, f"Invalid {algorithm} checksum for {path}")


def verify_maven_metadata(path: Path, module: str, version: str) -> None:
    try:
        root = ET.fromstring(path.read_bytes())
    except (OSError, ET.ParseError) as error:
        raise MergeError(f"Invalid Maven metadata index {path}: {error}") from error
    values = {element.text for element in root.iter() if local_name(element.tag) == "version" and element.text}
    require(version in values, f"Maven metadata index does not list {version}: {module}")


def verify_output_repository(
    output: Path,
    version: str,
    commit: str,
    expected_root_dependencies: dict[str, list[dict[str, Any]]],
) -> dict[str, Any]:
    module_report: dict[str, Any] = {}
    for root_module in MODULES:
        root_dir = maven_path(output, root_module, version)
        root_module_path = root_dir / f"{root_module}-{version}.module"
        root_data = read_json(root_module_path)
        require(normalized_coordinate(root_data.get("component")) == (GROUP, root_module, version), f"Incorrect output root component for {root_module}")
        check_root_metadata_dependencies_unchanged(root_module_path, expected_root_dependencies[root_module], root_module, version)
        primary, _ = root_primary_files(root_data, root_dir)
        structure_sets = verify_root_structure_json(primary, root_module)
        publication_assets(root_dir, root_module, version, commit)

        targets_report: dict[str, Any] = {}
        allowed = expected_leaf_modules(root_module, STANDARD_LEAVES) | expected_leaf_modules(root_module, OHOS_LEAVES)
        root_refs = {available_module(variant) for variant in root_data.get("variants", []) if available_module(variant)}
        require(root_refs == allowed, f"Final {root_module} root references unexpected target modules: {sorted(root_refs ^ allowed)}")
        for leaf, spec in {**STANDARD_LEAVES, **OHOS_LEAVES}.items():
            artifact_id = f"{root_module}-{leaf}"
            publication = load_publication(output, artifact_id, root_module, version)
            leaf_api, leaf_sources, leaf_manifest = verify_leaf_module(
                publication,
                root_module,
                leaf,
                spec,
                version,
                "OHOS" if leaf.startswith("ohos") else "standard",
                leaf.startswith("ohos"),
            )
            leaf_pom = check_pom_coordinate(publication["pom_path"], artifact_id, version)
            expected_core = "filekit-core" if leaf == "root" else f"filekit-core-{leaf}"
            verify_dialogs_pom_dependencies(leaf_pom, artifact_id, version, expected_core, root_module == "filekit-dialogs")
            publication_assets(publication["version_dir"], artifact_id, version, commit)
            targets_report[leaf] = {
                "module": artifact_id,
                "artifact": leaf_api.name,
                "artifactSha256": sha256(leaf_api.read_bytes()),
                "sources": leaf_sources.name,
                "nativeTarget": leaf_manifest.get("native_targets") if leaf_manifest else None,
                "compilerVersion": leaf_manifest.get("compiler_version") if leaf_manifest else None,
                "uniqueName": leaf_manifest.get("unique_name") if leaf_manifest else None,
            }
        verify_maven_metadata(output / GROUP_PATH / root_module / "maven-metadata.xml", root_module, version)
        module_report[root_module] = {
            "rootVariants": [variant.get("name") for variant in root_data.get("variants", [])],
            "sourceSets": sorted(structure_sets),
            "targets": targets_report,
        }
    verify_checksum_files(output)
    return module_report


def generate_maven_indexes(output: Path, version: str, existing_repository: Path | None) -> None:
    artifact_ids: set[str] = set()
    for module in MODULES:
        artifact_ids.add(module)
        artifact_ids.update(f"{module}-{leaf}" for leaf in STANDARD_LEAVES)
        artifact_ids.update(f"{module}-{leaf}" for leaf in OHOS_LEAVES)
    for artifact_id in sorted(artifact_ids):
        index = output / GROUP_PATH / artifact_id / "maven-metadata.xml"
        versions = read_existing_versions(existing_repository, artifact_id)
        write_maven_metadata(index, GROUP, artifact_id, version, versions)
        write_sidecars(index)


def create_output(
    standard_repo: Path,
    ohos_repo: Path,
    output_repo: Path,
    version: str,
    commit: str,
    source_root: Path | None,
    existing_repo: Path | None,
    release_ready: bool,
) -> dict[str, Any]:
    require(not output_repo.exists(), f"Output repository already exists: {output_repo}")
    standard_roots: dict[str, dict[str, Any]] = {}
    ohos_roots: dict[str, dict[str, Any]] = {}
    standard_leaves: dict[tuple[str, str], dict[str, Any]] = {}
    ohos_leaves: dict[tuple[str, str], dict[str, Any]] = {}
    for module in MODULES:
        standard_roots[module] = load_publication(standard_repo, module, module, version)
        ohos_roots[module] = load_publication(ohos_repo, module, module, version)
        for leaf in STANDARD_LEAVES:
            artifact_id = f"{module}-{leaf}"
            standard_leaves[(module, leaf)] = load_publication(standard_repo, artifact_id, module, version)
        for leaf in OHOS_LEAVES:
            artifact_id = f"{module}-{leaf}"
            ohos_leaves[(module, leaf)] = load_publication(ohos_repo, artifact_id, module, version)
        verify_ohos_source_dependencies(
            ohos_roots[module],
            {leaf: ohos_leaves[(module, leaf)] for leaf in OHOS_LEAVES},
            module,
            version,
        )

    for module in MODULES:
        check_no_foreign_filekit_group(standard_roots[module]["module_data"], f"standard root {module}")
        check_no_foreign_filekit_group(ohos_roots[module]["module_data"], f"OHOS root {module}")
        verify_projected_root_dependencies(module, standard_roots[module]["module_data"], version)
        root_leaf_variants(
            standard_roots[module]["module_data"],
            expected_leaf_modules(module, STANDARD_LEAVES),
            f"standard {module}",
        )
        root_leaf_variants(
            ohos_roots[module]["module_data"],
            expected_leaf_modules(module, OHOS_LEAVES),
            f"OHOS {module}",
        )

    output_repo.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="filekit-unified-", dir=output_repo.parent) as temp_name:
        temporary = Path(temp_name) / "repository"
        temporary.mkdir()
        root_dependencies: dict[str, list[dict[str, Any]]] = {}
        for module in MODULES:
            root_dependencies[module] = verify_projected_root_dependencies(
                module, standard_roots[module]["module_data"], version
            )
            merge_root_artifacts(
                standard_roots[module],
                ohos_roots[module],
                temporary,
                module,
                version,
                commit,
                source_root,
            )

        for module in MODULES:
            for leaf, spec in STANDARD_LEAVES.items():
                publication = standard_leaves[(module, leaf)]
                verify_leaf_module(publication, module, leaf, spec, version, "standard", False)
                assets = publication_assets(publication["version_dir"], publication["artifact_id"], version, commit)
                verify_source_blobs(source_root, commit, assets["provenance"], assets["provenance_path"])
                destination = temporary / GROUP_PATH / publication["artifact_id"] / version
                copy_version_files(publication["version_dir"], destination)
                leaf_module_data = copy.deepcopy(publication["module_data"])
                update_gmm_file_hashes(leaf_module_data, destination)
                (destination / publication["module_path"].name).write_bytes(json_bytes(leaf_module_data))
            for leaf, spec in OHOS_LEAVES.items():
                publication = ohos_leaves[(module, leaf)]
                module_data = copy.deepcopy(publication["module_data"])
                verify_leaf_module(publication, module, leaf, spec, version, "OHOS", True)
                assets = publication_assets(publication["version_dir"], publication["artifact_id"], version, commit)
                verify_source_blobs(source_root, commit, assets["provenance"], assets["provenance_path"])
                destination = temporary / GROUP_PATH / publication["artifact_id"] / version
                copy_version_files(publication["version_dir"], destination)
                target_module_path = destination / publication["module_path"].name
                update_gmm_file_hashes(module_data, destination)
                target_module_path.write_bytes(json_bytes(module_data))
                target_pom_path = destination / publication["pom_path"].name
                pom_root = check_pom_coordinate(target_pom_path, publication["artifact_id"], version)
                expected_core = f"filekit-core-{leaf}"
                verify_dialogs_pom_dependencies(pom_root, publication["artifact_id"], version, expected_core, module == "filekit-dialogs")

        generate_maven_indexes(temporary, version, existing_repo)
        for module in MODULES:
            for artifact_id in [module, *[f"{module}-{leaf}" for leaf in STANDARD_LEAVES], *[f"{module}-{leaf}" for leaf in OHOS_LEAVES]]:
                version_dir = temporary / GROUP_PATH / artifact_id / version
                for file_path in version_dir.iterdir():
                    if file_path.is_file() and not any(file_path.name.endswith(suffix) for suffix in SIDECAR_SUFFIXES):
                        write_sidecars(file_path)

        report = verify_output_repository(temporary, version, commit, root_dependencies)
        report_path = temporary / "merge-report.json"
        report_path.write_bytes(
            json_bytes(
                {
                    "schemaVersion": 1,
                    "group": GROUP,
                    "version": version,
                    "sourceCommit": commit,
                    "sourceBlobsVerified": source_root is not None,
                    "releaseReady": release_ready,
                    "supportedLeaves": [*STANDARD_LEAVES, *OHOS_LEAVES],
                    "modules": report,
                    "pagesCopy": "Copy the selected group path and its maven-metadata.xml sidecars into the clean Pages branch; preserve its prior version entries when merging each index.",
                }
            )
        )
        output_repo.parent.mkdir(parents=True, exist_ok=True)
        temporary.rename(output_repo)
        return report


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Merge standard and CPF FileKit KMP staging repositories into one five-target Maven repository."
    )
    parser.add_argument("--standard-repository", type=Path, required=True, help="Standard Kotlin staging repository root")
    parser.add_argument("--ohos-repository", type=Path, required=True, help="CPF OHOS staging repository root")
    parser.add_argument("--output-repository", type=Path, required=True, help="New output repository path; must not exist")
    parser.add_argument("--source-commit", required=True, help="Full 40-character FileKit commit SHA")
    parser.add_argument(
        "--source-root",
        type=Path,
        help="Read-only Git checkout containing the source commit; source blobs are checked with git show",
    )
    parser.add_argument(
        "--release-ready",
        action="store_true",
        help="Require --source-root and exact committed-blob SHA-256 verification",
    )
    parser.add_argument(
        "--existing-repository",
        type=Path,
        help="Optional read-only Pages repository used to preserve prior Maven metadata versions",
    )
    parser.add_argument("--version", help="Optional expected version; defaults to the commit-derived version")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    commit = args.source_commit.lower()
    require(bool(re.fullmatch(r"[0-9a-f]{40}", commit)), "--source-commit must be a full 40-character hexadecimal SHA")
    version = VERSION_PREFIX + commit[:12]
    require(args.version is None or args.version == version, f"Version must be exactly {version}")
    source_root = args.source_root.resolve() if args.source_root is not None else None
    require(not args.release_ready or source_root is not None, "--release-ready requires --source-root")
    if source_root is not None:
        require(source_root.is_dir(), f"Source root does not exist: {source_root}")
        top = subprocess.run(
            ["git", "-C", str(source_root), "rev-parse", "--show-toplevel"],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            check=False,
        )
        require(top.returncode == 0, "Source root is not a Git checkout")
    report = create_output(
        io_path(args.standard_repository),
        io_path(args.ohos_repository),
        io_path(args.output_repository),
        version,
        commit,
        source_root,
        io_path(args.existing_repository) if args.existing_repository else None,
        args.release_ready,
    )
    print(f"Merged {GROUP}:filekit-core:{version} and {GROUP}:filekit-dialogs:{version}")
    print("Supported leaves: Android, iOS arm64, iOS simulator arm64, OHOS arm64, OHOS x64")
    print(f"Modules verified: {', '.join(sorted(report))}")
    if source_root is None:
        print("Warning: source blobs were compared between producer receipts but not verified against Git.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except MergeError as error:
        print(f"merge error: {error}", file=sys.stderr)
        raise SystemExit(2)
