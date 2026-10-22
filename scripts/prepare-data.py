"""将三份原始 Excel 校验并整理为一个本地数据包，不连接数据库。仅使用标准库。"""
import argparse
import hashlib
import json
from pathlib import Path
import posixpath
import re
from xml.etree import ElementTree as ET
from zipfile import ZipFile

NS = {"s": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}
REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
ROOT = Path(__file__).resolve().parents[1]
SOURCES = {
    "departments": ("市政公司部门信息表.xlsx", 1, ["部门名称", "部门经理", "上级部门"]),
    "employees": ("市政公司员工信息表.xlsx", 1, ["姓名", "用户名", "分公司", "部门", "岗位", "电话号码"]),
    "permissions": ("市政公司应用软件功能权限清单(1).xlsx", 2,
                    ["软件系统", "功能模块", "功能点", "功能说明", "系统管理员", "公司领导", "部门经理", "项目经理", "普通员工"]),
}


def read_table(path, header_row, headers):
    with ZipFile(path) as archive:
        sheets = ET.fromstring(archive.read("xl/workbook.xml")).findall("s:sheets/s:sheet", NS)
        if len(sheets) != 1:
            raise ValueError(f"{path.name}: 需要恰好一个来源工作表")
        links = ET.fromstring(archive.read("xl/_rels/workbook.xml.rels"))
        link = next(r for r in links if r.get("Id") == sheets[0].get(f"{{{REL}}}id"))
        if link.get("TargetMode") == "External":
            raise ValueError("不读取外部工作表")
        target = link.get("Target")
        target = target.lstrip("/") if target.startswith("/") else posixpath.normpath("xl/" + target)
        strings = []
        if "xl/sharedStrings.xml" in archive.namelist():
            strings = ["".join(t.text or "" for t in item.findall(".//s:t", NS))
                       for item in ET.fromstring(archive.read("xl/sharedStrings.xml"))]
        sheet = ET.fromstring(archive.read(target))
        rows = {}
        for row in sheet.findall("s:sheetData/s:row", NS):
            cells = {}
            for cell in row.findall("s:c", NS):
                if cell.find("s:f", NS) is not None:
                    raise ValueError(f"{path.name}: 来源含公式 {cell.get('r')}")
                column = re.sub(r"[0-9]", "", cell.get("r"))
                value = cell.find("s:v", NS)
                text = value.text if value is not None else "".join(t.text or "" for t in cell.findall(".//s:t", NS))
                cells[column] = strings[int(text)] if cell.get("t") == "s" else text
            rows[int(row.get("r"))] = cells
        columns = [chr(ord("A") + i) for i in range(len(headers))]
        if [rows.get(header_row, {}).get(c, "") for c in columns] != headers:
            raise ValueError(f"{path.name}: 表头与预期不符")
        # 合并单元格按其左上角值展开，其他空值保留；不猜测业务权限或管理员身份。
        for merge in sheet.findall("s:mergeCells/s:mergeCell", NS):
            match = re.fullmatch(r"([A-Z]+)(\d+):([A-Z]+)(\d+)", merge.get("ref"))
            if not match:
                raise ValueError("合并范围无法解析")
            first, start, last, end = match.groups()
            if first == last and int(start) > header_row:
                value = rows.get(int(start), {}).get(first, "")
                for number in range(int(start), int(end) + 1):
                    rows.setdefault(number, {}).setdefault(first, value)
                    if not rows[number][first]:
                        rows[number][first] = value
        records = [{"sourceRow": number, **{h: cells.get(c, "") for h, c in zip(headers, columns)}}
                   for number, cells in sorted(rows.items())
                   if number > header_row and any(cells.get(c) for c in columns)]
        if not records:
            raise ValueError(f"{path.name}: 没有数据")
        return {"file": path.name, "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                "sheet": sheets[0].get("name"), "records": records}


def prepare(source):
    tables = {key: read_table(source / name, header, columns)
              for key, (name, header, columns) in SOURCES.items()}
    for key, field in [("employees", "用户名"), ("departments", "部门名称")]:
        seen = set()
        for row in tables[key]["records"]:
            value = row[field]
            if not value or value in seen:
                raise ValueError(f"{key}: 第 {row['sourceRow']} 行标识为空或重复")
            seen.add(value)
    return {"status": "SOURCE_PREPARED_NOT_IMPORTED", "actualImported": 0, "tables": tables}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=ROOT.parent / "data")
    parser.add_argument("--output", type=Path, default=ROOT / "target/prepared-data.json")
    args = parser.parse_args()
    if args.output.resolve() in {(args.source / name).resolve() for name, _, _ in SOURCES.values()}:
        parser.error("输出不能覆盖原始 Excel")
    result = prepare(args.source)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"status": result["status"], "counts": {k: len(v["records"]) for k, v in result["tables"].items()},
                      "output": str(args.output), "actualImported": 0}))


if __name__ == "__main__":
    main()
