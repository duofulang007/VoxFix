"""Read APK metadata and inventory without installing or executing its code."""
import argparse
import hashlib
import json
import struct
import zipfile
from pathlib import Path
from xml.etree import ElementTree as ET

ANDROID = "http://schemas.android.com/apk/res/android"
ET.register_namespace("android", ANDROID)


def decode_axml(data):
    def u16(offset):
        return struct.unpack_from("<H", data, offset)[0]

    def u32(offset):
        return struct.unpack_from("<I", data, offset)[0]

    def length(offset, utf8):
        value = data[offset] if utf8 else u16(offset)
        step, mask = (1, 0x80) if utf8 else (2, 0x8000)
        if value & mask:
            tail = data[offset + step] if utf8 else u16(offset + step)
            return ((value & (mask - 1)) << (7 if utf8 else 16)) | tail, offset + 2 * step
        return value, offset + step

    assert u16(0) == 3 and u32(4) == len(data), "Not a binary XML document"
    strings, stack, root = [], [], None
    offset = u16(2)
    while offset < len(data):
        kind, header, size = struct.unpack_from("<HHI", data, offset)
        if size < header or offset + size > len(data):
            raise ValueError("Invalid XML chunk bounds")
        if kind == 1:
            count, flags, start = u32(offset + 8), u32(offset + 16), u32(offset + 20)
            utf8 = bool(flags & 0x100)
            for index in range(count):
                position = offset + start + u32(offset + header + 4 * index)
                if utf8:
                    _, position = length(position, True)
                count_bytes, position = length(position, utf8)
                raw = data[position:position + count_bytes * (1 if utf8 else 2)]
                strings.append(raw.decode("utf-8" if utf8 else "utf-16-le"))
        elif kind == 0x102:
            ext = offset + 16
            namespace, name = u32(ext), u32(ext + 4)
            tag = strings[name] if namespace == 0xFFFFFFFF else "{" + strings[namespace] + "}" + strings[name]
            element = ET.Element(tag)
            attr_start, attr_size, attr_count = struct.unpack_from("<HHH", data, ext + 8)
            for index in range(attr_count):
                attr = ext + attr_start + index * attr_size
                ns, key, raw = struct.unpack_from("<III", data, attr)
                value_type, value = data[attr + 15], u32(attr + 16)
                key = strings[key] if ns == 0xFFFFFFFF else "{" + strings[ns] + "}" + strings[key]
                if raw != 0xFFFFFFFF:
                    value = strings[raw]
                elif value_type == 3:
                    value = strings[value]
                elif value_type == 0x12:
                    value = "true" if value else "false"
                elif value_type == 1:
                    value = f"@0x{value:08x}"
                else:
                    value = str(value)
                element.set(key, value)
            if stack:
                stack[-1].append(element)
            else:
                root = element
            stack.append(element)
        elif kind == 0x103:
            stack.pop()
        offset += size
    assert root is not None and root.tag == "manifest" and not stack
    return root


def inspect(source, output):
    output.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(source) as archive:
        entries = archive.infolist()
        names = [entry.filename for entry in entries]
        assert "AndroidManifest.xml" in names and "classes.dex" in names
        root = decode_axml(archive.read("AndroidManifest.xml"))
        ET.indent(root)
        ET.ElementTree(root).write(output / "AndroidManifest.decoded.xml", encoding="utf-8", xml_declaration=True)
        (output / "inventory.tsv").write_text("path\tuncompressed_bytes\tcompressed_bytes\n" + "\n".join(f"{e.filename}\t{e.file_size}\t{e.compress_size}" for e in entries), encoding="utf-8")
        with source.open("rb") as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
        report = {
            "source": str(source), "archive_bytes": source.stat().st_size,
            "sha256": digest, "entry_count": len(entries),
            "uncompressed_bytes": sum(e.file_size for e in entries),
            "manifest": root.attrib,
            "sdk": [e.attrib for e in root.findall("uses-sdk")],
            "permissions": [e.attrib for e in root.findall("uses-permission")],
            "ime_services": [e.attrib for e in root.findall("application/service") if any(a.get("{" + ANDROID + "}name") == "android.view.InputMethod" for a in e.findall("intent-filter/action"))],
            "dex_files": [n for n in names if n.endswith(".dex")],
            "native_libraries": [n for n in names if n.startswith("lib/")],
            "largest_entries": [{"path": e.filename, "bytes": e.file_size} for e in sorted(entries, key=lambda e: e.file_size, reverse=True)[:25]],
            "ui_resource_names": [n for n in names if n.startswith(("res/layout", "assets/")) and any(term in n.lower() for term in ("keyboard", "keyb", "voice", "speech", "skin", "pinyin"))],
        }
        (output / "summary.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    inspect(args.source, args.output)
