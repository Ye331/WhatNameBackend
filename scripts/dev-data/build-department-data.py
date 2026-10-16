#!/usr/bin/env python3
"""Read the controlled department workbook without trusting its dimension.

Standard library only. This is local development data, never an HR sync API.
"""
import argparse
import hashlib
import json
import posixpath
from pathlib import Path
import re
import sys
from xml.etree import ElementTree as ET
from zipfile import ZipFile

NS = {"s": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}
REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
PKG_REL = "http://schemas.openxmlformats.org/package/2006/relationships"
REPO = Path(__file__).resolve().parents[2]
DEFAULT_SOURCE = REPO.parent / "RBAC0" / "市政公司部门信息表.xlsx"


def read_source(path, sheet_name="部门信息"):
    """Resolve the named sheet through workbook relationships; inspect sheetData."""
    with ZipFile(path) as archive:
        workbook = ET.fromstring(archive.read("xl/workbook.xml"))
        sheet = next((s for s in workbook.findall("s:sheets/s:sheet", NS)
                      if s.get("name") == sheet_name), None)
        if sheet is None:
            raise ValueError(f"工作表不存在: {sheet_name}")
        relations = ET.fromstring(archive.read("xl/_rels/workbook.xml.rels"))
        relation = next(r for r in relations.findall(f"{{{PKG_REL}}}Relationship")
                        if r.get("Id") == sheet.get(f"{{{REL}}}id"))
        if relation.get("TargetMode") == "External":
            raise ValueError("不读取外部工作表关系")
        target = relation.get("Target")
        sheet_path = (target.lstrip("/") if target.startswith("/")
                      else posixpath.normpath(posixpath.join("xl", target)))
        strings = []
        if "xl/sharedStrings.xml" in archive.namelist():
            strings = ["".join(si.itertext()) for si in
                       ET.fromstring(archive.read("xl/sharedStrings.xml")).findall("s:si", NS)]
        xml = ET.fromstring(archive.read(sheet_path))
        physical_rows = xml.findall("s:sheetData/s:row", NS)
        records = []
        for row in physical_rows:
            row_number = int(row.get("r"))
            cells = {}
            for cell in row.findall("s:c", NS):
                column = re.sub(r"\d", "", cell.get("r", ""))
                if column not in ("A", "B", "C"):
                    continue
                if cell.find("s:f", NS) is not None:
                    raise ValueError(f"来源字段不能为公式: {cell.get('r')}")
                value = cell.find("s:v", NS)
                inline = cell.find("s:is", NS)
                text = value.text if value is not None else None
                if cell.get("t") == "s" and text is not None:
                    text = strings[int(text)]
                elif inline is not None:
                    text = "".join(inline.itertext())
                cells[column] = text
            if row_number == 1:
                if not cells.get("A") or not cells.get("C"):
                    raise ValueError("缺少A/C列表头")
                continue
            if not any(cells.values()):
                continue
            records.append({"source_row": row_number,
                            "raw_name": cells.get("A"),
                            "raw_manager_name": cells.get("B"),
                            "raw_parent_name": cells.get("C")})
        dimension = xml.find("s:dimension", NS)
        return records, {"sheet": sheet_name, "sheet_xml": sheet_path,
                         "declared_dimension": dimension.get("ref") if dimension is not None else None,
                         "physical_rows": len(physical_rows)}


def normalize(records):
    by_name, by_row = {}, set()
    for record in records:
        item = dict(record)
        row = item["source_row"]
        name = (item.get("raw_name") or "").strip()
        parent = (item.get("raw_parent_name") or "").strip() or None
        if not name or len(name) > 128:
            raise ValueError(f"部门名称为空或超长: 原表第{row}行")
        if name in by_name:
            raise ValueError(f"重复部门名称: {name}, 第{row}行")
        if row in by_row or row < 2:
            raise ValueError(f"重复或无效来源行号: {row}")
        by_row.add(row)
        item.update(dept_name=name, parent_name=parent, dept_code=f"DEV-DEPT-R{row:04d}")
        by_name[name] = item
    for name, item in by_name.items():
        parent = item["parent_name"]
        if parent == name:
            raise ValueError(f"自父节点: {name}")
        if parent is not None and parent not in by_name:
            raise ValueError(f"缺少父部门: {name} -> {parent}")
    ordered, visited, visiting = [], set(), []

    def visit(name):
        if name in visited:
            return
        if name in visiting:
            raise ValueError("部门环: " + " -> ".join(visiting + [name]))
        visiting.append(name)
        item = by_name[name]
        parent = item["parent_name"]
        if parent:
            visit(parent)
        item["parent_code"] = by_name[parent]["dept_code"] if parent else None
        item["depth"] = by_name[parent]["depth"] + 1 if parent else 0
        item["manager_id"] = None
        item["initial_status"] = 1
        item["initial_hr_version"] = 0
        visiting.pop()
        visited.add(name)
        ordered.append(item)

    for item in sorted(by_name.values(), key=lambda r: r["source_row"]):
        visit(item["dept_name"])
    roots = [r for r in ordered if r["parent_code"] is None]
    if len(roots) != 1:
        raise ValueError(f"必须恰有一个根节点, 实际{len(roots)}个")
    return ordered


def sql_text(value):
    # Hex UTF-8 literals avoid quote/backslash and SQL-mode dependent escaping.
    return "CONVERT(X'" + value.encode("utf-8").hex().upper() + "' USING utf8mb4)"


def make_sql(records):
    lines = [
        "-- LOCAL DEVELOPMENT DATA ONLY. Not HR codes, not a migration or startup initializer.",
        "-- Run against your personal MySQL 8 database in ONE fresh connection, stop on error.",
        "-- mysql batch mode WITHOUT --force: a failing assertion disconnects and rolls back.",
        "-- Existing name/parent/path conflicts are errors; existing business fields are untouched.",
        "-- Path convention: /<root internal id>/<child internal id>/ (includes self, trailing slash).",
        "SET NAMES utf8mb4;",
        "SET SESSION sql_mode = 'STRICT_ALL_TABLES,NO_ENGINE_SUBSTITUTION';",
        "CREATE TEMPORARY TABLE department_seed_guard (",
        "  ok INT NOT NULL, CONSTRAINT department_seed_structure_conflict CHECK (ok = 1)",
        ");",
        "INSERT INTO department_seed_guard VALUES (GET_LOCK('whatname.dev.department-seed', 10));",
        "START TRANSACTION;",
    ]
    for record in records:
        code, name = sql_text(record["dept_code"]), sql_text(record["dept_name"])
        lines.append(f"-- Source row {record['source_row']}: {record['dept_code']}")
        if record["parent_code"] is None:
            lines.append("SET @seed_parent = 0, @seed_parent_path = '/';")
        else:
            parent_code = sql_text(record["parent_code"])
            lines.extend([
                "SET @seed_parent = NULL, @seed_parent_path = NULL;",
                "SELECT id, dept_path INTO @seed_parent, @seed_parent_path",
                f"FROM sys_dept WHERE dept_code = {parent_code} FOR UPDATE;",
                "INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));",
            ])
        lines.extend([
            "SET @seed_id = NULL, @seed_matches = 0;",
            "SELECT id, IF(BINARY dept_code = BINARY " + code,
            f"  AND BINARY dept_name = BINARY {name} AND parent_id = @seed_parent",
            "  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)",
            "INTO @seed_id, @seed_matches",
            f"FROM sys_dept WHERE dept_code = {code} FOR UPDATE;",
            # A conflict report names the exact code; no personal manager names enter SQL.
            f"SELECT {code} AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason",
            "WHERE @seed_id IS NOT NULL AND @seed_matches = 0;",
            "INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));",
            "SET @seed_new = (@seed_id IS NULL);",
            "INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)",
            f"SELECT @seed_parent, {code}, {name}, @seed_parent_path, NULL, {record['source_row']}, 1, 0",
            "WHERE @seed_new;",
            "SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);",
            "UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')",
            "WHERE id = @seed_id AND @seed_new;",
        ])
    lines.extend(["COMMIT;", "DO RELEASE_LOCK('whatname.dev.department-seed');",
                  "DROP TEMPORARY TABLE department_seed_guard;", ""])
    return "\n".join(lines)


def build(source):
    raw, metadata = read_source(source)
    records = normalize(raw)
    return {
        "purpose": "LOCAL DEVELOPMENT ONLY; DEV-DEPT codes are generated from original sheet row numbers, not HR codes",
        "source_file": source.name,
        "source_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
        **metadata,
        "count": len(records),
        "root_code": next(r["dept_code"] for r in records if r["parent_code"] is None),
        "path_convention": "/<root internal id>/<child internal id>/; includes self; trailing slash",
        "manager_policy": "Source names retained as provenance only; no user lookup or creation",
        "departments": records,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=DEFAULT_SOURCE)
    parser.add_argument("--output-dir", type=Path, default=REPO / "db" / "dev-data")
    args = parser.parse_args()
    data = build(args.source)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    (args.output_dir / "department-source.json").write_text(
        json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    (args.output_dir / "department-seed.sql").write_text(make_sql(data["departments"]), encoding="utf-8", newline="\n")
    print(json.dumps({"count": data["count"], "root_code": data["root_code"],
                      "source_sha256": data["source_sha256"]}, ensure_ascii=False))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, ET.ParseError) as error:
        print(f"部门数据校验失败: {error}", file=sys.stderr)
        sys.exit(1)
