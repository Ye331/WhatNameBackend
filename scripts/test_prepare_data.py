"""来源整理的边界测试；不访问数据库、不修改原始 Excel。"""
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZipFile

spec = importlib.util.spec_from_file_location("prepare_data", Path(__file__).with_name("prepare-data.py"))
data = importlib.util.module_from_spec(spec)
spec.loader.exec_module(data)


class SourceTests(unittest.TestCase):
    def workbook(self, directory, cells):
        path = Path(directory) / "source.xlsx"
        with ZipFile(path, "w") as archive:
            archive.writestr("xl/workbook.xml", '<workbook xmlns="' + data.NS['s'] + '" xmlns:r="' + data.REL + '"><sheets><sheet name="数据" r:id="rId1"/></sheets></workbook>')
            archive.writestr("xl/_rels/workbook.xml.rels", '<Relationships><Relationship Id="rId1" Target="worksheets/actual.xml"/></Relationships>')
            archive.writestr("xl/worksheets/actual.xml", '<worksheet xmlns="' + data.NS['s'] + '"><dimension ref="A1"/><sheetData>' + cells + '</sheetData><mergeCells><mergeCell ref="A2:A3"/></mergeCells></worksheet>')
        return path

    def test_real_rows_and_merged_cells_ignore_inaccurate_dimension(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.workbook(directory, '<row r="1"><c r="A1" t="inlineStr"><is><t>名称</t></is></c></row><row r="2"><c r="A2" t="inlineStr"><is><t>应用</t></is></c></row><row r="3"><c r="A3"/></row>')
            result = data.read_table(path, 1, ["名称"])
            self.assertEqual([r['名称'] for r in result['records']], ['应用', '应用'])
            self.assertEqual([r['sourceRow'] for r in result['records']], [2, 3])

    def test_wrong_header_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.workbook(directory, '<row r="1"><c r="A1" t="inlineStr"><is><t>错误列</t></is></c></row>')
            with self.assertRaisesRegex(ValueError, '表头'):
                data.read_table(path, 1, ['名称'])

    def test_formula_is_not_trusted_as_source_value(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.workbook(directory, '<row r="1"><c r="A1"><f>1+1</f><v>2</v></c></row>')
            with self.assertRaisesRegex(ValueError, '公式'):
                data.read_table(path, 1, ['名称'])

    def test_duplicate_employee_identifier_rejected(self):
        tables = [{'records': [{'部门名称': '部门', 'sourceRow': 2}]},
                  {'records': [{'用户名': 'SG1', 'sourceRow': 2}, {'用户名': 'SG1', 'sourceRow': 3}]},
                  {'records': []}]
        with patch.object(data, 'read_table', side_effect=tables):
            with self.assertRaisesRegex(ValueError, '第 3 行'):
                data.prepare(Path('.'))


if __name__ == '__main__':
    unittest.main()
