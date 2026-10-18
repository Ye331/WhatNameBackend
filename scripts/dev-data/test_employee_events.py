"""Task09 tests use synthetic data only; real-data verification is a separate run."""
import argparse
import copy
import importlib.util
from io import BytesIO
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from xml.etree import ElementTree as ET
from zipfile import ZipFile

spec = importlib.util.spec_from_file_location("employee_events", Path(__file__).with_name("prepare-employee-events.py"))
tool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tool)


def workbook(rows, headers, sheet="fixture", formula=False, shared=False):
    ns = tool.NS["s"]
    root = ET.Element("worksheet", xmlns=ns)
    ET.SubElement(root, "dimension", ref="A1")  # Deliberately wrong.
    data = ET.SubElement(root, "sheetData")
    all_rows = [list(headers)] + rows
    strings = []
    for n, values in enumerate(all_rows, 1):
        row = ET.SubElement(data, "row", r=str(n))
        for i, text in enumerate(values):
            if text is None:
                continue
            cell = ET.SubElement(row, "c", r=f"{chr(65+i)}{n}", t="s" if shared else "inlineStr")
            if formula and n == 2 and i == 0:
                ET.SubElement(cell, "f").text = "1+1"
            if shared:
                ET.SubElement(cell, "v").text = str(len(strings))
                strings.append(text)
            else:
                ET.SubElement(ET.SubElement(cell, "is"), "t").text = text
    out = BytesIO()
    with ZipFile(out, "w") as z:
        z.writestr("xl/workbook.xml", f'<workbook xmlns="{ns}" xmlns:r="{tool.REL}"><sheets><sheet name="{sheet}" sheetId="2" r:id="rId5"/></sheets></workbook>')
        z.writestr("xl/_rels/workbook.xml.rels", f'<Relationships xmlns="{tool.PKG}"><Relationship Id="rId5" Target="worksheets/sheet2.xml"/></Relationships>')
        z.writestr("xl/worksheets/sheet2.xml", ET.tostring(root, encoding="utf-8"))
        if shared:
            sst = ET.Element("sst", xmlns=ns)
            for s in strings:
                ET.SubElement(ET.SubElement(sst, "si"), "t").text = s
            z.writestr("xl/sharedStrings.xml", ET.tostring(sst, encoding="utf-8"))
    return out.getvalue()


def organization(definitions=None):
    # (name, parent source row). Repeated leaf names in different branches are legal.
    definitions = definitions or [("总公司", None), ("甲分公司", 2), ("甲分公司-办公室", 3),
                                  ("乙分公司", 2), ("办公室", 5)]
    names = {i: name for i, (name, _) in enumerate(definitions, 2)}
    rows = [[name, "测试负责人", names.get(parent)] for name, parent in definitions]
    source = workbook(rows, ("部门", "负责人", "上级部门"), "部门信息")
    depts = []
    for i, (name, parent) in enumerate(definitions, 2):
        depth, seen, cursor = 0, set(), parent
        while cursor is not None and cursor not in seen:
            seen.add(cursor)
            depth += 1
            cursor = definitions[cursor-2][1]
        depts.append({"source_row": i, "raw_name": name, "raw_parent_name": names.get(parent),
                      "dept_name": name, "parent_name": names.get(parent),
                      "dept_code": f"DEV-DEPT-R{i:04d}",
                      "parent_code": f"DEV-DEPT-R{parent:04d}" if parent else None, "depth": depth})
    return source, {"sheet": "部门信息", "source_sha256": tool.digest(source),
                    "departments": depts, "count": len(depts), "root_code": "DEV-DEPT-R0002"}


class EmployeeEventsTest(unittest.TestCase):
    def setUp(self):
        self.rows = [["测试甲", "SG001", "甲分公司", "办公室", "工程师", "13800000001"],
                     ["测试乙", "SG002", "乙分公司", "办公室", "技术员", "13800000002"]]
        self.depts, self.mapping = organization()
        self.config = dict(employment_type="FORMAL", event_version=1,
                           occurred_at="2026-10-16T14:00:00+08:00", batch="task09-fixture")

    def prepare(self, rows=None, **config):
        return tool.prepare(workbook(self.rows if rows is None else rows, tool.HEADERS),
                            self.depts, tool.json_bytes(self.mapping), **(self.config | config))

    def rejected(self, category, rows=None, **config):
        with self.assertRaises(tool.InvalidData) as caught:
            self.prepare(rows, **config)
        self.assertIn(category, {e["category"] for e in caught.exception.errors})
        diagnostic = tool.json_bytes(caught.exception.errors)
        self.assertTrue(all(v.encode() not in diagnostic for row in self.rows for v in (row[0], row[5])))

    def test_wrong_dimension_relationship_and_shared_strings(self):
        for shared in (False, True):
            data = workbook(self.rows, tool.HEADERS, shared=shared)
            rows, meta = tool.read_sheet(data)
            self.assertEqual(len(rows), 3)
            self.assertEqual(meta["sheet_xml"], "xl/worksheets/sheet2.xml")
            self.assertEqual(meta["declared_dimension"], "A1")
            self.assertEqual(rows[2][1], self.rows[1])

    def test_same_leaf_correct_branch_and_branch_self(self):
        rows = self.rows + [["测试丙", "SG003", "甲分公司", "甲分公司", "经理", "13800000003"]]
        payload, report = self.prepare(rows)
        events = [json.loads(line) for line in payload.splitlines()]
        self.assertEqual([e["body"]["deptCode"] for e in events],
                         ["DEV-DEPT-R0004", "DEV-DEPT-R0006", "DEV-DEPT-R0003"])
        self.assertEqual(report["counts"]["matched"], 3)
        self.assertEqual(report["actual_imported"], 0)

    def test_ambiguous_sibling_alias_rejected(self):
        self.depts, self.mapping = organization([("总公司", None), ("甲分公司", 2),
                                                 ("甲分公司-办公室", 3), ("办公室", 3)])
        self.rejected("AMBIGUOUS_ORGANIZATION", self.rows[:1])

    def test_unknown_branch_and_cross_branch_never_fallback(self):
        for branch, dept in (("不存在", "办公室"), ("甲分公司", "乙分公司-办公室"),
                             ("总公司", "甲分公司-办公室")):
            rows = copy.deepcopy(self.rows[:1])
            rows[0][2:4] = [branch, dept]
            self.rejected("UNKNOWN_ORGANIZATION", rows)

    def test_all_fields_nonempty_and_no_controls_or_padding(self):
        for i in range(6):
            for value in (None, "", " ", "abc\u200b", "x\n", " padded"):
                with self.subTest(column=i, case=len(value or "")):
                    rows = copy.deepcopy(self.rows)
                    rows[0][i] = value
                    self.rejected("EMPTY_OR_INVALID_FIELD", rows)

    def test_limits_include_java_utf16_units(self):
        for i, limit in enumerate(tool.LIMITS):
            rows = copy.deepcopy(self.rows)
            rows[0][i] = "X" * (limit + 1)
            self.rejected("EMPTY_OR_INVALID_FIELD", rows)
        self.assertFalse(tool.clean_text("\U00020000" * 33, 64))
        rows = copy.deepcopy(self.rows)
        rows[0][0] = "测" * 64
        self.assertEqual(self.prepare(rows)[1]["event_count"], 2)

    def test_sg_phone_format_and_global_duplicates(self):
        for col, values, category in ((1, ("sg001", "SG١", "SG1/2"), "INVALID_SG"),
                                      (5, ("12800000001", "+8613800000001", "１３８０００００００１"), "INVALID_PHONE")):
            for value in values:
                rows = copy.deepcopy(self.rows)
                rows[0][col] = value
                self.rejected(category, rows)
        for col, category in ((1, "DUPLICATE_SG"), (5, "DUPLICATE_PHONE")):
            rows = copy.deepcopy(self.rows)
            rows[1][col] = rows[0][col]
            self.rejected(category, rows)

    def test_headers_empty_and_formula_rejected(self):
        self.rejected("EMPTY_EMPLOYEE_DATA", [])
        for data, category in ((workbook(self.rows, list(reversed(tool.HEADERS))), "INVALID_EMPLOYEE_HEADERS"),
                               (workbook(self.rows, tool.HEADERS, formula=True), "UNSUPPORTED_CELL_TYPE")):
            with self.assertRaises(tool.InvalidData) as caught:
                tool.prepare(data, self.depts, tool.json_bytes(self.mapping), **self.config)
            self.assertEqual(caught.exception.errors[0]["category"], category)

    def test_explicit_employment_type_required(self):
        self.rejected("EXPLICIT_EMPLOYMENT_TYPE_REQUIRED", employment_type=None)
        self.rejected("EXPLICIT_EMPLOYMENT_TYPE_REQUIRED", employment_type="formal")
        self.assertEqual(self.prepare(employment_type="OUTSOURCED")[1]["event_count"], 2)
        with patch("sys.stderr"):
            with self.assertRaises(SystemExit):
                tool.parser().parse_args(["--event-version", "1", "--occurred-at", self.config["occurred_at"], "--batch", "fixture"])

    def test_explicit_version_time_batch_validation(self):
        for version in (0, -1, True, 9223372036854775808):
            self.rejected("INVALID_EVENT_VERSION", event_version=version)
        for timestamp in ("2026-10-16T14:00:00", "2026-10-16", "yesterday"):
            self.rejected("TIMEZONE_REQUIRED", occurred_at=timestamp)
        for timestamp in ("2026-02-30T14:00:00Z", "2026-10-16T14:00:00+19:00", "2026-10-16T14:00:00+08:60"):
            self.rejected("INVALID_OCCURRED_AT", occurred_at=timestamp)
        for batch in ("../escape", "x" * 17, "", "命名"):
            self.rejected("INVALID_BATCH", batch=batch)

    def test_longest_sg_and_batch_fit_dto_event_id(self):
        rows = copy.deepcopy(self.rows[:1])
        rows[0][1] = "SG" + "1" * 62
        payload, _ = self.prepare(rows, batch="b" * 16)
        self.assertLessEqual(len(json.loads(payload)["body"]["eventId"]), 128)

    def test_multiple_sheets_external_relationship_and_duplicate_rows(self):
        data = workbook(self.rows, tool.HEADERS)
        def rewrite(member, transform):
            out = BytesIO()
            with ZipFile(BytesIO(data)) as source, ZipFile(out, "w") as target:
                for name in source.namelist():
                    value = source.read(name)
                    target.writestr(name, transform(value) if name == member else value)
            return out.getvalue()
        multi = rewrite("xl/workbook.xml", lambda b: b.replace(b'</sheets>', b'<sheet name="other" sheetId="3" r:id="rId6"/></sheets>'))
        with self.assertRaises(tool.InvalidData):
            tool.read_sheet(multi)
        self.assertEqual(len(tool.read_sheet(multi, "fixture")[0]), 3)
        external = rewrite("xl/_rels/workbook.xml.rels", lambda b: b.replace(b'Target="worksheets/sheet2.xml"', b'Target="https://example.invalid/" TargetMode="External"'))
        with self.assertRaises(tool.InvalidData):
            tool.read_sheet(external)
        duplicate = rewrite("xl/worksheets/sheet2.xml", lambda b: b.replace(b'<row r="3">', b'<row r="2">'))
        with self.assertRaises(tool.InvalidData):
            tool.read_sheet(duplicate)

    def test_fixed_parameters_bytes_and_ids_repeat(self):
        a, ra = self.prepare()
        b, rb = self.prepare()
        self.assertTrue(a == b)
        self.assertEqual(ra, rb)
        self.assertEqual(tool.digest(a), ra["output_sha256"])
        events = [json.loads(x) for x in a.splitlines()]
        self.assertEqual(len({x["body"]["eventId"] for x in events}), 2)
        self.assertTrue(all(len(x["body"]["eventId"]) <= 128 for x in events))
        for event in events:
            self.assertEqual(event["path"].rsplit("/", 1)[1], event["body"]["sgNo"])
            self.assertEqual(set(event["body"]), {"sourceSystem", "eventId", "eventVersion", "sgNo", "realName", "phone", "deptCode", "employeeAction", "employmentType", "occurredAt"})

    def test_any_input_or_parameter_change_changes_namespace(self):
        a, report = self.prepare()
        base = json.loads(a.splitlines()[0])["body"]["eventId"]
        variants = []
        rows = copy.deepcopy(self.rows)
        rows[1][4] = "另一开发岗位"  # Even non-body source facts change the dataset.
        variants.append(self.prepare(rows))
        for kw in ({"batch": "fixture2"}, {"event_version": 2}, {"employment_type": "OUTSOURCED"},
                   {"occurred_at": "2026-10-16T06:00:00Z"}):
            variants.append(self.prepare(**kw))
        self.mapping["review_note"] = "controlled mapping revision"
        variants.append(self.prepare())
        for payload, other in variants:
            self.assertNotEqual(report["dataset_sha256"], other["dataset_sha256"])
            self.assertNotEqual(base, json.loads(payload.splitlines()[0])["body"]["eventId"])

    def test_mapping_source_hash_row_code_parent_depth_and_cycle(self):
        original = copy.deepcopy(self.mapping)
        for field, value in (("source_sha256", "0" * 64), ("count", 7), ("root_code", "bad")):
            self.mapping = copy.deepcopy(original)
            self.mapping[field] = value
            with self.assertRaises(tool.InvalidData):
                self.prepare()
        for field, value in (("dept_name", "wrong"), ("dept_code", "DEV-DEPT-R9999"),
                             ("parent_code", "DEV-DEPT-R9999"), ("parent_code", "DEV-DEPT-R0005"), ("depth", 9)):
            self.mapping = copy.deepcopy(original)
            self.mapping["departments"][2][field] = value
            with self.assertRaises(tool.InvalidData):
                self.prepare()
        self.depts, self.mapping = organization([("总公司", None), ("环甲", 4), ("环乙", 3)])
        self.rejected("DEPARTMENT_CYCLE")

    def test_output_restrictions_and_error_preserves_existing_events(self):
        tool.PRIVATE.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="test09-", dir=tool.PRIVATE) as td:
            directory = Path(td)
            inputs = directory / "inputs"
            inputs.mkdir()
            ep, dp, mp = [inputs / name for name in ("e.xlsx", "d.xlsx", "m.json")]
            ep.write_bytes(workbook(self.rows, tool.HEADERS))
            dp.write_bytes(self.depts)
            mp.write_bytes(tool.json_bytes(self.mapping))
            args = argparse.Namespace(employees=ep, departments=dp, mapping=mp, sheet=None,
                                      output_dir=directory / "outputs", **self.config)
            report = tool.run(args)
            output = Path(report["output_path"])
            original = output.read_bytes()
            self.rows[1][5] = self.rows[0][5]
            ep.write_bytes(workbook(self.rows, tool.HEADERS))
            with self.assertRaises(tool.InvalidData):
                tool.run(args)
            self.assertTrue(output.read_bytes() == original)
            self.assertEqual(len(list(output.parent.glob("*.ndjson"))), 1)
            diagnostics = (output.parent / "validation-errors.json").read_bytes()
            self.assertTrue(all(v.encode() not in diagnostics for r in self.rows for v in (r[0], r[5])))
            for location in (tool.REPO, tool.REPO / ".." / "WhatNameBackend" / "docs", ep):
                with self.assertRaises(tool.InvalidData):
                    tool.safe_directory(location, (ep, dp, mp))
            with self.assertRaises(tool.InvalidData):
                tool.atomic_write(ep, b"no", (ep,))
            self.assertTrue(ep.read_bytes() != b"no")
            gitdir = directory / "git-worktree"
            gitdir.mkdir()
            (gitdir / ".git").write_text("gitdir: elsewhere")
            with self.assertRaises(tool.InvalidData):
                tool.safe_directory(gitdir / "nested")

    def test_atomic_io_failure_keeps_valid_file_and_cleans_temp(self):
        tool.PRIVATE.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="test09-", dir=tool.PRIVATE) as td:
            path = Path(td) / "valid.ndjson"
            path.write_bytes(b"valid\n")
            with patch.object(tool.os, "replace", side_effect=OSError("synthetic failure")):
                with self.assertRaises(OSError):
                    tool.atomic_write(path, b"replacement\n")
            self.assertEqual(path.read_bytes(), b"valid\n")
            self.assertEqual(len(list(Path(td).iterdir())), 1)


if __name__ == "__main__":
    unittest.main()
