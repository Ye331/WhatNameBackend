"""Run: python -m unittest discover -s scripts/dev-data -p 'test_*.py'."""
import importlib.util
import json
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location("department_data", Path(__file__).with_name("build-department-data.py"))
DATA = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(DATA)


def row(number, name, parent=None):
    return dict(source_row=number, raw_name=name, raw_parent_name=parent, raw_manager_name=None)


class DepartmentDataTest(unittest.TestCase):
    def test_real_source_and_stable_generation(self):
        first = DATA.build(DATA.DEFAULT_SOURCE)
        second = DATA.build(DATA.DEFAULT_SOURCE)
        self.assertEqual(first, second)
        self.assertEqual(111, first["count"])
        self.assertEqual(112, first["physical_rows"])
        self.assertEqual("A1", first["declared_dimension"])
        records = first["departments"]
        self.assertEqual("总公司", records[0]["dept_name"])
        self.assertEqual("DEV-DEPT-R0002", records[0]["dept_code"])
        self.assertEqual(1, sum(r["parent_code"] is None for r in records))
        seen = set()
        for record in records:
            self.assertTrue(record["parent_code"] is None or record["parent_code"] in seen)
            self.assertIsNone(record["manager_id"])
            seen.add(record["dept_code"])
        self.assertEqual(111, len(seen))
        sql = DATA.make_sql(records)
        self.assertEqual(sql, DATA.make_sql(second["departments"]))
        self.assertEqual(111, sql.count("INSERT INTO sys_dept"))
        self.assertNotIn("TRUNCATE", sql.upper())
        self.assertNotIn("DELETE FROM", sql.upper())
        self.assertEqual(first, json.loads((DATA.REPO / "db/dev-data/department-source.json").read_text(encoding="utf-8")))
        self.assertEqual(sql, (DATA.REPO / "db/dev-data/department-seed.sql").read_text(encoding="utf-8"))

    def test_parent_after_child_is_sorted(self):
        records = DATA.normalize([row(2, "child", "root"), row(3, "root")])
        self.assertEqual(["root", "child"], [r["dept_name"] for r in records])
        self.assertEqual("DEV-DEPT-R0003", records[1]["parent_code"])

    def test_missing_parent(self):
        with self.assertRaisesRegex(ValueError, "缺少父部门.*child.*missing"):
            DATA.normalize([row(2, "root"), row(3, "child", "missing")])

    def test_cycle_even_with_valid_root(self):
        with self.assertRaisesRegex(ValueError, "部门环.*a.*b.*a"):
            DATA.normalize([row(2, "root"), row(3, "a", "b"), row(4, "b", "a")])

    def test_self_parent(self):
        with self.assertRaisesRegex(ValueError, "自父节点"):
            DATA.normalize([row(2, "a", "a")])

    def test_duplicate_normalized_name(self):
        with self.assertRaisesRegex(ValueError, "重复部门名称"):
            DATA.normalize([row(2, "a"), row(3, " a ")])

    def test_multiple_or_no_roots(self):
        for records in ([], [row(2, "a"), row(3, "b")]):
            with self.assertRaisesRegex(ValueError, "必须恰有一个根节点"):
                DATA.normalize(records)

    def test_invalid_row_or_name(self):
        for records in ([row(2, "")], [row(2, "a"), row(2, "b", "a")]):
            with self.assertRaises(ValueError):
                DATA.normalize(records)

    def test_utf8_hex_literal(self):
        self.assertEqual("CONVERT(X'61275C0A' USING utf8mb4)", DATA.sql_text("a'\\\n"))


if __name__ == "__main__":
    unittest.main()
