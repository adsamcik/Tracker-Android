#!/usr/bin/env python3
"""Strict native-library inventory and 16 KiB release checks."""

from __future__ import annotations

import hashlib
import os
import re
import struct
import subprocess
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable, Mapping, Sequence

PT_LOAD = 1
PT_GNU_RELRO = 0x6474E552
PAGE_ALIGNMENT_4K = 4 * 1024
PAGE_ALIGNMENT_16K = 16 * 1024
SIXTEEN_KB_ABIS = frozenset({"arm64-v8a", "x86_64"})
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")


class ReleaseValidationError(RuntimeError):
    """Raised when release evidence is incomplete or unsafe."""


@dataclass(frozen=True)
class ElfInfo:
    elf_class: int
    load_alignments: tuple[int, ...]
    has_relro: bool


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _power_of_two(value: int) -> bool:
    return value > 0 and value & (value - 1) == 0


def inspect_elf(
    data: bytes,
    label: str = "<memory>",
    *,
    minimum_alignment: int = PAGE_ALIGNMENT_16K,
) -> ElfInfo:
    """Parse the ELF program headers without relying on a host NDK tool."""
    if len(data) < 64 or data[:4] != b"\x7fELF":
        raise ReleaseValidationError(f"{label}: packaged .so is not a valid ELF file")

    elf_class = data[4]
    encoding = data[5]
    if elf_class not in (1, 2):
        raise ReleaseValidationError(f"{label}: unsupported ELF class {elf_class}")
    if encoding not in (1, 2):
        raise ReleaseValidationError(f"{label}: unsupported ELF byte order {encoding}")

    endian = "<" if encoding == 1 else ">"
    if elf_class == 1:
        phoff = struct.unpack_from(endian + "I", data, 28)[0]
        phentsize = struct.unpack_from(endian + "H", data, 42)[0]
        phnum = struct.unpack_from(endian + "H", data, 44)[0]
        minimum_phentsize = 32
    else:
        phoff = struct.unpack_from(endian + "Q", data, 32)[0]
        phentsize = struct.unpack_from(endian + "H", data, 54)[0]
        phnum = struct.unpack_from(endian + "H", data, 56)[0]
        minimum_phentsize = 56

    if phnum <= 0 or phentsize < minimum_phentsize:
        raise ReleaseValidationError(f"{label}: ELF program-header table is missing or malformed")
    if phoff + phentsize * phnum > len(data):
        raise ReleaseValidationError(f"{label}: ELF program-header table is truncated")

    load_alignments: list[int] = []
    has_relro = False
    for index in range(phnum):
        offset = phoff + index * phentsize
        segment_type = struct.unpack_from(endian + "I", data, offset)[0]
        if segment_type == PT_GNU_RELRO:
            has_relro = True
        if segment_type != PT_LOAD:
            continue

        if elf_class == 1:
            file_offset = struct.unpack_from(endian + "I", data, offset + 4)[0]
            virtual_address = struct.unpack_from(endian + "I", data, offset + 8)[0]
            alignment = struct.unpack_from(endian + "I", data, offset + 28)[0]
        else:
            file_offset = struct.unpack_from(endian + "Q", data, offset + 8)[0]
            virtual_address = struct.unpack_from(endian + "Q", data, offset + 16)[0]
            alignment = struct.unpack_from(endian + "Q", data, offset + 48)[0]

        if not _power_of_two(alignment):
            raise ReleaseValidationError(
                f"{label}: ELF LOAD segment has invalid p_align={alignment}"
            )
        if alignment < minimum_alignment:
            raise ReleaseValidationError(
                f"{label}: ELF LOAD p_align={alignment} is below required "
                f"{minimum_alignment // 1024} KiB"
            )
        if file_offset % alignment != virtual_address % alignment:
            raise ReleaseValidationError(
                f"{label}: ELF LOAD file/virtual offsets are incongruent for p_align={alignment}"
            )
        load_alignments.append(alignment)

    if not load_alignments:
        raise ReleaseValidationError(f"{label}: ELF contains no LOAD segment")
    if not has_relro:
        raise ReleaseValidationError(f"{label}: ELF is missing a GNU_RELRO program header")

    return ElfInfo(
        elf_class=32 if elf_class == 1 else 64,
        load_alignments=tuple(load_alignments),
        has_relro=has_relro,
    )


def expected_native_pairs(release_inputs: Mapping[str, Any]) -> dict[tuple[str, str], str]:
    expected: dict[tuple[str, str], str] = {}
    expected_abis = set(release_inputs["expectedAbis"])
    for library in release_inputs["nativeLibraries"]:
        name = library["name"]
        coordinate = library["coordinate"]
        for abi in library["abis"]:
            if abi not in expected_abis:
                raise ReleaseValidationError(
                    f"release inputs list unsupported ABI {abi} for {name}"
                )
            pair = (abi, name)
            if pair in expected:
                raise ReleaseValidationError(f"duplicate native allowlist entry {abi}/{name}")
            expected[pair] = coordinate
    return expected


def _classify_native_path(member_path: str) -> tuple[str, str]:
    parts = member_path.replace("\\", "/").split("/")
    if not parts[-1].endswith(".so"):
        raise ReleaseValidationError(f"{member_path}: expected a .so member")
    try:
        lib_index = parts.index("lib")
    except ValueError as exc:
        raise ReleaseValidationError(
            f"{member_path}: unknown packaged .so location (expected lib/<abi>/<name>)"
        ) from exc
    if len(parts) != lib_index + 3:
        raise ReleaseValidationError(
            f"{member_path}: unknown packaged .so layout (expected lib/<abi>/<name>)"
        )
    return parts[lib_index + 1], parts[lib_index + 2]


def validate_native_entries(
    entries: Iterable[tuple[str, bytes]],
    release_inputs: Mapping[str, Any],
    label: str,
    *,
    require_complete: bool = True,
) -> list[dict[str, Any]]:
    expected = expected_native_pairs(release_inputs)
    records: list[dict[str, Any]] = []
    seen: set[tuple[str, str]] = set()

    for member_path, data in entries:
        abi, name = _classify_native_path(member_path)
        pair = (abi, name)
        if pair not in expected:
            raise ReleaseValidationError(
                f"{label}:{member_path}: unknown native file; update release/release-inputs.json "
                "only after reviewing its provenance"
            )
        if pair in seen:
            raise ReleaseValidationError(f"{label}: duplicate native file {abi}/{name}")
        seen.add(pair)

        minimum_alignment = (
            PAGE_ALIGNMENT_16K if abi in SIXTEEN_KB_ABIS else PAGE_ALIGNMENT_4K
        )
        elf = inspect_elf(
            data, f"{label}:{member_path}", minimum_alignment=minimum_alignment
        )
        digest = sha256_bytes(data)
        if not SHA256_RE.fullmatch(digest):
            raise AssertionError("hashlib returned a malformed SHA-256")
        records.append(
            {
                "abi": abi,
                "name": name,
                "path": member_path,
                "coordinate": expected[pair],
                "sha256": digest,
                "elfClass": elf.elf_class,
                "loadAlignments": list(elf.load_alignments),
                "requiredLoadAlignment": minimum_alignment,
                "relro": elf.has_relro,
            }
        )

    if require_complete:
        missing = sorted(expected.keys() - seen)
        if missing:
            rendered = ", ".join(f"{abi}/{name}" for abi, name in missing)
            raise ReleaseValidationError(f"{label}: expected native files are missing: {rendered}")

    return sorted(records, key=lambda item: (item["abi"], item["name"]))


def native_entries_from_zip(archive: zipfile.ZipFile) -> list[tuple[str, bytes]]:
    result: list[tuple[str, bytes]] = []
    seen_names: set[str] = set()
    for info in archive.infolist():
        if info.is_dir() or not info.filename.endswith(".so"):
            continue
        if info.filename in seen_names:
            raise ReleaseValidationError(f"{archive.filename}: duplicate member {info.filename}")
        seen_names.add(info.filename)
        result.append((info.filename, archive.read(info)))
    return result


def _collect_alignment_values(value: Any, found: list[str]) -> None:
    if isinstance(value, dict):
        for key, child in value.items():
            if key.lower() == "alignment":
                found.append(str(child))
            _collect_alignment_values(child, found)
    elif isinstance(value, list):
        for child in value:
            _collect_alignment_values(child, found)


def validate_bundle_config(bundle_config: Mapping[str, Any], has_native_code: bool) -> list[str]:
    alignments: list[str] = []
    _collect_alignment_values(bundle_config, alignments)
    if has_native_code and not alignments:
        raise ReleaseValidationError(
            "bundle configuration has native code but declares no page alignment"
        )
    invalid = [value for value in alignments if value != "PAGE_ALIGNMENT_16K"]
    if invalid:
        raise ReleaseValidationError(
            "bundle configuration is not PAGE_ALIGNMENT_16K: " + ", ".join(invalid)
        )
    return alignments


def _version_key(path: Path) -> tuple[int, ...]:
    numbers = re.findall(r"\d+", path.name)
    return tuple(int(number) for number in numbers)


def find_android_build_tool(name: str, explicit: Path | None = None) -> Path:
    if explicit is not None:
        if explicit.is_file():
            return explicit.resolve()
        raise ReleaseValidationError(f"{name} does not exist: {explicit}")

    executable = f"{name}.exe" if os.name == "nt" else name
    for variable in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        raw = os.environ.get(variable)
        if not raw:
            continue
        build_tools = Path(raw) / "build-tools"
        if not build_tools.is_dir():
            continue
        for version_dir in sorted(build_tools.iterdir(), key=_version_key, reverse=True):
            candidate = version_dir / executable
            if candidate.is_file():
                return candidate.resolve()
    raise ReleaseValidationError(
        f"{name} was not found; set ANDROID_HOME/ANDROID_SDK_ROOT or pass --{name}"
    )


def find_zipalign(explicit: Path | None = None) -> Path:
    return find_android_build_tool("zipalign", explicit)


def check_zipalign(apk: Path, zipalign: Path) -> str:
    result = subprocess.run(
        [str(zipalign), "-c", "-P", "16", "-v", "4", str(apk)],
        check=False,
        capture_output=True,
        text=True,
    )
    output = (result.stdout + result.stderr).strip()
    if result.returncode != 0:
        raise ReleaseValidationError(
            f"{apk}: zipalign -P 16 failed with exit {result.returncode}\n{output}"
        )
    return output


def archive_native_symbols(
    symbol_root: Path,
    destination: Path,
    native_records: Sequence[Mapping[str, Any]],
) -> dict[str, Any]:
    """Archive AGP symbol tables for packaged libraries and report exact coverage."""
    if not symbol_root.is_dir():
        raise ReleaseValidationError(f"native symbol table directory is missing: {symbol_root}")

    known = {(str(record["abi"]), str(record["name"])) for record in native_records}
    packaged_abis = {abi for abi, _ in known}
    symbol_files = sorted(path for path in symbol_root.rglob("*") if path.is_file())
    if not symbol_files:
        raise ReleaseValidationError(f"native symbol table directory is empty: {symbol_root}")

    covered: set[tuple[str, str]] = set()
    entries: list[dict[str, Any]] = []
    destination.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(destination, "w") as archive:
        for symbol_file in symbol_files:
            relative = symbol_file.relative_to(symbol_root)
            if len(relative.parts) != 2 or not relative.name.endswith(".so.sym"):
                raise ReleaseValidationError(
                    f"unknown native symbol table layout: {relative.as_posix()}"
                )
            key = (relative.parts[0], relative.name.removesuffix(".sym"))
            if key not in known:
                # AGP extracts every ABI offered by an upstream AAR even when the application
                # packaging filter excludes that ABI. Those tables do not describe the shipped
                # APK set and must not expand its symbol-coverage or ABI contract.
                if key[0] not in packaged_abis:
                    continue
                raise ReleaseValidationError(
                    f"native symbol table has no packaged library: {key[0]}/{key[1]}"
                )
            data = symbol_file.read_bytes()
            if not data:
                raise ReleaseValidationError(
                    f"native symbol table is empty: {relative.as_posix()}"
                )

            archive_name = relative.as_posix()
            info = zipfile.ZipInfo(archive_name, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            archive.writestr(info, data)
            covered.add(key)
            entries.append(
                {
                    "abi": key[0],
                    "name": key[1],
                    "path": archive_name,
                    "sha256": hashlib.sha256(data).hexdigest(),
                }
            )

    unavailable = [
        {"abi": abi, "name": name}
        for abi, name in sorted(known - covered)
    ]
    return {
        "format": "AGP_SYMBOL_TABLE",
        "entries": entries,
        "unavailable": unavailable,
    }


def _safe_extract_member(archive: zipfile.ZipFile, member: zipfile.ZipInfo, output: Path) -> Path:
    target = (output / member.filename).resolve()
    output_root = output.resolve()
    if output_root != target and output_root not in target.parents:
        raise ReleaseValidationError(f"unsafe APK-set member path: {member.filename}")
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(archive.read(member))
    return target


def validate_native_archives(
    aab: Path,
    apk_set: Path,
    bundle_config: Mapping[str, Any],
    release_inputs: Mapping[str, Any],
    extracted_apks: Path,
    *,
    zipalign: Path | None = None,
) -> dict[str, Any]:
    with zipfile.ZipFile(aab) as archive:
        aab_records = validate_native_entries(
            native_entries_from_zip(archive), release_inputs, aab.name
        )

    validate_bundle_config(bundle_config, has_native_code=bool(aab_records))
    zipalign_path = find_zipalign(zipalign)

    if extracted_apks.exists():
        for child in extracted_apks.rglob("*"):
            if child.is_file():
                child.unlink()
        for child in sorted(extracted_apks.rglob("*"), reverse=True):
            if child.is_dir():
                child.rmdir()
    extracted_apks.mkdir(parents=True, exist_ok=True)

    apk_records: list[dict[str, Any]] = []
    apk_artifacts: list[dict[str, Any]] = []
    with zipfile.ZipFile(apk_set) as archive:
        apk_members = [
            info for info in archive.infolist()
            if not info.is_dir() and info.filename.endswith(".apk")
        ]
        if len(apk_members) != 1 or Path(apk_members[0].filename).name != "universal.apk":
            names = ", ".join(info.filename for info in apk_members)
            raise ReleaseValidationError(
                "representative APK set must contain exactly universal.apk; found: " + names
            )
        for member in apk_members:
            apk = _safe_extract_member(archive, member, extracted_apks)
            with zipfile.ZipFile(apk) as apk_archive:
                records = validate_native_entries(
                    native_entries_from_zip(apk_archive), release_inputs, apk.name
                )
            apk_records.extend(records)
            apk_artifacts.append(
                {
                    "path": apk,
                    "zipalignOutput": check_zipalign(apk, zipalign_path),
                }
            )

    expected_abis = list(release_inputs["expectedAbis"])
    actual_abis = sorted({record["abi"] for record in aab_records})
    if sorted(expected_abis) != actual_abis:
        raise ReleaseValidationError(
            f"AAB ABI inventory {actual_abis} does not match reviewed inventory "
            f"{sorted(expected_abis)}"
        )

    return {
        "abis": expected_abis,
        "aab": aab_records,
        "apks": apk_records,
        "apkArtifacts": apk_artifacts,
        "zipalign": str(zipalign_path),
    }
