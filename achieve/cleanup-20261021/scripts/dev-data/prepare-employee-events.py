#!/usr/bin/env python3
"""Prepare local HR02 UPSERT requests. No network, database, passwords or signing.

Python 3.10+, standard library only. Diagnostics never contain employee values.
"""
import argparse
from collections import Counter
from datetime import datetime
import hashlib
from io import BytesIO
import json
import os
from pathlib import Path
import posixpath
import re
import sys
import tempfile
import unicodedata
from xml.etree import ElementTree as ET
from zipfile import ZipFile, BadZipFile

REPO = Path(__file__).resolve().parents[2]
ROOT = REPO.parent
PRIVATE = ROOT / "tmp" / "task09-private"
RULE = "task09-hr02-v1"
NS = {"s": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}
REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
PKG = "http://schemas.openxmlformats.org/package/2006/relationships"
HEADERS = ("姓名", "用户名", "分公司", "部门", "岗位", "电话号码")
LIMITS = (64, 64, 128, 128, 128, 32)
TYPES = ("FORMAL", "OUTSOURCED")


def digest(data):
    return hashlib.sha256(data).hexdigest()


def json_bytes(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True,
                       separators=(",", ":")) + "\n").encode("utf-8")


class InvalidData(ValueError):
    def __init__(self, errors, summary=None):
        self.errors = errors
        self.summary = summary or {}
        super().__init__("validation failed; employee values redacted")


def fail(category, row=None, field=None, **metadata):
    error = {"category": category}
    if row is not None:
        error["row"] = row
    if field is not None:
        error["field"] = field
    error.update(metadata)
    raise InvalidData([error])


def clean_text(value, limit):
    # Match Java @Size's UTF-16 units, reject hidden controls and silent trimming.
    return (isinstance(value, str) and bool(value) and value == value.strip()
            and len(value.encode("utf-16-le")) // 2 <= limit
            and not any(unicodedata.category(c) in ("Cc", "Cf", "Cs", "Zl", "Zp")
                        for c in value))


def read_sheet(data, sheet_name=None, columns=6):
    """Use workbook relationships and physical sheetData, ignoring dimension."""
    with ZipFile(BytesIO(data)) as z:
        book = ET.fromstring(z.read("xl/workbook.xml"))
        sheets = book.findall("s:sheets/s:sheet", NS)
        choices = [s for s in sheets if sheet_name is None or s.get("name") == sheet_name]
        if len(choices) != 1:
            fail("SHEET_SELECTION_REQUIRED")
        sheet = choices[0]
        rels = ET.fromstring(z.read("xl/_rels/workbook.xml.rels"))
        matches = [r for r in rels.findall(f"{{{PKG}}}Relationship")
                   if r.get("Id") == sheet.get(f"{{{REL}}}id")]
        if len(matches) != 1 or matches[0].get("TargetMode") == "External":
            fail("INVALID_SHEET_RELATIONSHIP")
        target = matches[0].get("Target", "")
        member = (target.lstrip("/") if target.startswith("/")
                  else posixpath.normpath(posixpath.join("xl", target)))
        if not member.startswith("xl/worksheets/") or ".." in member.split("/"):
            fail("INVALID_SHEET_RELATIONSHIP")
        strings = []
        if "xl/sharedStrings.xml" in z.namelist():
            strings = ["".join(t.text or "" for t in si.iter(f"{{{NS['s']}}}t"))
                       for si in ET.fromstring(z.read("xl/sharedStrings.xml")).findall("s:si", NS)]
        xml = ET.fromstring(z.read(member))
        physical = xml.findall("s:sheetData/s:row", NS)
        records, used_rows = [], set()
        for row in physical:
            n = int(row.get("r", "0"))
            if n < 1 or n in used_rows:
                fail("INVALID_SOURCE_ROW", n)
            used_rows.add(n)
            values = [None] * columns
            used_columns = set()
            for c in row.findall("s:c", NS):
                match = re.fullmatch(r"([A-Z]+)([1-9][0-9]*)", c.get("r", ""))
                if not match or int(match[2]) != n:
                    fail("INVALID_CELL_REFERENCE", n)
                col = 0
                for letter in match[1]:
                    col = col * 26 + ord(letter) - 64
                if col > columns:
                    if any(c.find(tag, NS) is not None for tag in ("s:v", "s:is", "s:f")):
                        fail("UNEXPECTED_COLUMN", n)
                    continue
                if col in used_columns:
                    fail("DUPLICATE_CELL", n)
                used_columns.add(col)
                if c.find("s:f", NS) is not None or c.get("t") in ("e", "b", "d"):
                    fail("UNSUPPORTED_CELL_TYPE", n, col)
                v = c.find("s:v", NS)
                value = v.text if v is not None else None
                if c.get("t") == "s" and value is not None:
                    index = int(value)
                    if not 0 <= index < len(strings):
                        fail("INVALID_SHARED_STRING", n, col)
                    value = strings[index]
                elif c.get("t") == "inlineStr":
                    value = "".join(t.text or "" for t in c.findall("s:is//s:t", NS))
                values[col - 1] = value
            records.append((n, values))
        dimension = xml.find("s:dimension", NS)
        return records, {"sheet": sheet.get("name"), "sheet_xml": member,
                         "declared_dimension": dimension.get("ref") if dimension is not None else None,
                         "physical_rows": len(physical)}


class Organization:
    def __init__(self, mapping, department_data):
        rows, meta = read_sheet(department_data, mapping.get("sheet"), 3)
        source = {n: v for n, v in rows if n > 1 and any(v)}
        if mapping.get("source_sha256") != digest(department_data):
            fail("DEPARTMENT_SOURCE_HASH_MISMATCH")
        items = mapping.get("departments", [])
        if not items or len(items) != len(source) or mapping.get("count") != len(items):
            fail("DEPARTMENT_MAPPING_COUNT")
        self.by_code, used_rows = {}, set()
        for d in items:
            code, n = d.get("dept_code"), d.get("source_row")
            if (not isinstance(code, str) or not re.fullmatch(r"DEV-DEPT-R[0-9]{4,}", code)
                    or code != f"DEV-DEPT-R{n:04d}" or code in self.by_code
                    or n in used_rows or n not in source):
                fail("INVALID_DEPARTMENT_MAPPING")
            original = source[n]
            name, parent = (original[0] or "").strip(), (original[2] or "").strip() or None
            if (not clean_text(name, 128) or d.get("dept_name") != name
                    or d.get("parent_name") != parent or d.get("raw_name") != original[0]
                    or d.get("raw_parent_name") != original[2] or len(code) > 64):
                fail("DEPARTMENT_SOURCE_ROW_MISMATCH", n)
            self.by_code[code] = d
            used_rows.add(n)
        roots = [d for d in items if d.get("parent_code") is None]
        if len(roots) != 1 or mapping.get("root_code") != roots[0]["dept_code"]:
            fail("INVALID_DEPARTMENT_ROOT")
        self.root = roots[0]
        self.paths = {}
        for code, d in self.by_code.items():
            chain, seen, current = [], set(), d
            while current is not None:
                c = current["dept_code"]
                if c in seen:
                    fail("DEPARTMENT_CYCLE")
                seen.add(c)
                chain.append(current["dept_name"])
                parent_code = current.get("parent_code")
                if parent_code is not None and parent_code not in self.by_code:
                    fail("UNKNOWN_DEPARTMENT_PARENT")
                parent = self.by_code.get(parent_code)
                if current.get("parent_name") != (parent["dept_name"] if parent else None):
                    fail("DEPARTMENT_PARENT_MISMATCH")
                current = parent
            if d.get("depth") != len(chain) - 1:
                fail("DEPARTMENT_DEPTH_MISMATCH")
            self.paths[code] = list(reversed(chain))
        self.metadata = meta

    def resolve(self, branch, department):
        branches = [d for d in self.by_code.values()
                    if d["dept_name"] == branch and
                    (d["dept_code"] == self.root["dept_code"] or
                     d["parent_code"] == self.root["dept_code"])]
        candidates = set()
        for b in branches:
            if department == b["dept_name"]:
                candidates.add(b["dept_code"])
            candidates.update(d["dept_code"] for d in self.by_code.values()
                              if d["parent_code"] == b["dept_code"] and
                              d["dept_name"] in (department, branch + "-" + department))
        if len(branches) > 1 or len(candidates) > 1:
            return None, "AMBIGUOUS_ORGANIZATION", self.candidate_metadata(candidates or [b["dept_code"] for b in branches])
        if not candidates:
            # Only controlled mapping values enter error reports, never input strings.
            nearby = [d["dept_code"] for d in self.by_code.values()
                      if d["dept_name"] == department or d["dept_name"].endswith("-" + department)]
            return None, "UNKNOWN_ORGANIZATION", self.candidate_metadata(nearby)
        return next(iter(candidates)), None, []

    def candidate_metadata(self, codes):
        return [{"deptCode": c, "path": self.paths[c]} for c in sorted(codes)]


def validate_config(employment_type, event_version, occurred_at, batch):
    if employment_type not in TYPES:
        fail("EXPLICIT_EMPLOYMENT_TYPE_REQUIRED")
    if type(event_version) is not int or not 1 <= event_version <= 9223372036854775807:
        fail("INVALID_EVENT_VERSION")
    if not isinstance(batch, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,15}", batch):
        fail("INVALID_BATCH")
    if (not isinstance(occurred_at, str) or not re.fullmatch(
            r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?(?:Z|[+-]\d{2}:\d{2})", occurred_at)):
        fail("TIMEZONE_REQUIRED")
    try:
        parsed = datetime.fromisoformat(occurred_at.replace("Z", "+00:00"))
        offset = occurred_at[-6:] if not occurred_at.endswith("Z") else "+00:00"
        if (int(offset[-2:]) > 59 or parsed.utcoffset() is None
                or abs(parsed.utcoffset().total_seconds()) > 18 * 3600):
            fail("INVALID_OCCURRED_AT")
    except ValueError:
        fail("INVALID_OCCURRED_AT")


def prepare(employee_data, department_data, mapping_data, *, employment_type,
            event_version, occurred_at, batch, sheet_name=None):
    validate_config(employment_type, event_version, occurred_at, batch)
    org = Organization(json.loads(mapping_data), department_data)
    rows, metadata = read_sheet(employee_data, sheet_name)
    headers = [v for n, v in rows if n == 1]
    if headers != [list(HEADERS)]:
        fail("INVALID_EMPLOYEE_HEADERS")
    records = [(n, v) for n, v in rows if n > 1]
    if not records:
        fail("EMPTY_EMPLOYEE_DATA")
    errors, accepted, sg_rows, phone_rows = [], [], {}, {}
    counts = Counter(input_rows=len(records), matched=0, unmatched=0, ambiguous=0,
                     duplicate_sg=0, duplicate_phone=0, invalid_fields=0)
    dept_counts = Counter()
    for n, values in sorted(records):
        invalid = False
        for col, (value, limit) in enumerate(zip(values, LIMITS)):
            if not clean_text(value, limit):
                errors.append({"row": n, "field": HEADERS[col], "category": "EMPTY_OR_INVALID_FIELD"})
                counts["invalid_fields"] += 1
                invalid = True
        name, sg, branch, department, position, phone = values
        for value, pattern, label, seen, counter in (
                (sg, r"SG[0-9]+", "用户名", sg_rows, "sg"),
                (phone, r"1[3-9][0-9]{9}", "电话号码", phone_rows, "phone")):
            if not isinstance(value, str) or not re.fullmatch(pattern, value):
                errors.append({"row": n, "field": label, "category": "INVALID_" + counter.upper()})
                invalid = True
            elif value in seen:
                errors.append({"row": n, "field": label, "category": "DUPLICATE_" + counter.upper(),
                               "first_row": seen[value]})
                counts["duplicate_" + counter] += 1
                invalid = True
            else:
                seen[value] = n
        if not clean_text(branch, 128) or not clean_text(department, 128):
            continue
        code, reason, candidates = org.resolve(branch, department)
        if reason:
            errors.append({"row": n, "category": reason, "candidates": candidates})
            counts["ambiguous" if reason == "AMBIGUOUS_ORGANIZATION" else "unmatched"] += 1
            continue
        counts["matched"] += 1
        dept_counts[code] += 1
        if not invalid:
            accepted.append((n, name, sg, phone, code))
    counts.update(unique_sg=len(sg_rows), unique_phone=len(phone_rows), valid_rows=len(accepted))
    provenance = {"employee_source_sha256": digest(employee_data),
                  "department_source_sha256": digest(department_data),
                  "department_mapping_sha256": digest(mapping_data),
                  "rule_version": RULE, "employment_type": employment_type,
                  "employment_type_origin": "EXPLICIT_DEVELOPMENT_PARAMETER_NOT_SOURCE_CLASSIFICATION",
                  "event_version": event_version, "occurred_at": occurred_at, "batch": batch,
                  "employee_sheet": metadata, "department_sheet": org.metadata}
    summary = {"counts": dict(counts), "provenance": provenance,
               "department_counts": dict(sorted(dept_counts.items())), "actual_imported": 0}
    if errors:
        raise InvalidData(errors, summary)
    dataset = digest(json_bytes(provenance))
    output = []
    for n, name, sg, phone, code in accepted:
        event_id = f"dev-hr02:{batch}:{dataset[:32]}:{sg}"
        if len(event_id) > 128:
            fail("EVENT_ID_TOO_LONG", n)
        output.append(json_bytes({
            "method": "PUT", "path": "/api/v1/integrations/hr/employees/" + sg,
            "body": {"sourceSystem": "HR", "eventId": event_id,
                     "eventVersion": event_version, "sgNo": sg, "realName": name,
                     "phone": phone, "deptCode": code, "employeeAction": "UPSERT",
                     "employmentType": employment_type, "occurredAt": occurred_at},
            "provenance": {"sheet": metadata["sheet"], "sourceRow": n,
                           "departmentSourceRow": org.by_code[code]["source_row"]}}))
    payload = b"".join(output)
    summary.update(status="PREPARED_NOT_IMPORTED", dataset_sha256=dataset,
                   event_count=len(output), output_sha256=digest(payload),
                   output_bytes=len(payload))
    return payload, summary


def safe_directory(output_dir, inputs=()):
    directory = Path(output_dir).resolve()
    if not directory.is_relative_to(PRIVATE.resolve()) or directory.is_relative_to(REPO.resolve()):
        fail("OUTPUT_MUST_BE_PRIVATE_OUTSIDE_REPOSITORY")
    if any((p / ".git").exists() for p in (directory, *directory.parents)):
        fail("OUTPUT_INSIDE_GIT_WORKTREE")
    if any(directory == Path(p).resolve() or directory.is_relative_to(Path(p).resolve()) for p in inputs):
        fail("OUTPUT_OVERLAPS_INPUT")
    return directory


def atomic_write(path, data, inputs=()):
    path = Path(path)
    safe_directory(path.parent, inputs)
    if path.is_symlink() or path.resolve() in {Path(p).resolve() for p in inputs}:
        fail("OUTPUT_OVERLAPS_INPUT")
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=path.parent, prefix=".task09-", delete=False) as f:
            temporary = Path(f.name)
            f.write(data)
            f.flush()
            os.fsync(f.fileno())
        os.replace(temporary, path)
    finally:
        if temporary is not None and temporary.exists():
            temporary.unlink()


def run(args):
    inputs = (args.employees, args.departments, args.mapping)
    directory = safe_directory(args.output_dir, inputs)
    try:
        payload, report = prepare(*(p.read_bytes() for p in inputs),
                                  employment_type=args.employment_type,
                                  event_version=args.event_version,
                                  occurred_at=args.occurred_at, batch=args.batch,
                                  sheet_name=args.sheet)
    except InvalidData as error:
        report = {"status": "REJECTED_NO_EVENTS_WRITTEN", **error.summary,
                  "errors": error.errors, "actual_imported": 0}
        atomic_write(directory / "validation-errors.json", json_bytes(report), inputs)
        raise
    stem = f"employees-{args.batch}-{report['dataset_sha256'][:16]}"
    output = directory / (stem + ".ndjson")
    manifest = directory / (stem + ".manifest.json")
    # Preflight both paths before publishing either file.
    for p in (output, manifest):
        if p.is_symlink() or p.resolve() in {i.resolve() for i in inputs}:
            fail("OUTPUT_OVERLAPS_INPUT")
    report["output_path"] = str(output)
    report["input_paths"] = {key: str(p.resolve()) for key, p in
                             zip(("employees", "departments", "mapping"), inputs)}
    atomic_write(output, payload, inputs)
    atomic_write(manifest, json_bytes(report), inputs)
    return report


def parser():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--employees", type=Path, default=ROOT / "RBAC0" / "市政公司员工信息表.xlsx")
    p.add_argument("--departments", type=Path, default=ROOT / "RBAC0" / "市政公司部门信息表.xlsx")
    p.add_argument("--mapping", type=Path, default=REPO / "db" / "dev-data" / "department-source.json")
    p.add_argument("--sheet", help="Required for workbooks with multiple sheets")
    p.add_argument("--employment-type", choices=TYPES, required=True,
                   help="Explicit development configuration, not an HR fact")
    p.add_argument("--event-version", type=int, required=True)
    p.add_argument("--occurred-at", required=True, help="ISO 8601 with seconds and timezone")
    p.add_argument("--batch", required=True, help="1-16 ASCII letters/digits/_/-; start alphanumeric")
    p.add_argument("--output-dir", type=Path, default=PRIVATE)
    return p


def main():
    try:
        report = run(parser().parse_args())
        print(json.dumps({k: report[k] for k in ("status", "event_count", "output_sha256",
                                                "output_path", "actual_imported")}, ensure_ascii=False))
        return 0
    except InvalidData as error:
        print(json.dumps({"status": "REJECTED", "error_count": len(error.errors),
                          "categories": dict(Counter(e["category"] for e in error.errors))}), file=sys.stderr)
    except (OSError, ValueError, TypeError, KeyError, IndexError, ET.ParseError, BadZipFile, RuntimeError):
        # Do not echo exception messages: malformed source strings may contain PII.
        print('{"status":"REJECTED","category":"INPUT_OR_OUTPUT_ERROR"}', file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
