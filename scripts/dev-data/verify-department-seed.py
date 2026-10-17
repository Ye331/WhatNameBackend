#!/usr/bin/env python3
"""Explicit personal Docker MySQL import and verification; never runs at app startup.

Run from the repository: python scripts/dev-data/verify-department-seed.py --import-and-verify
No credentials are printed or copied. Docker reads its mounted application secret.
"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess

HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("department_data", HERE / "build-department-data.py")
DATA = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(DATA)
REPO = DATA.REPO


def mysql(sql, *, must_succeed=True):
    command = ["docker", "compose", "exec", "-T", "mysql", "sh", "-c",
               'MYSQL_PWD="$(cat /run/secrets/mysql_app_password)" '
               'mysql --default-character-set=utf8mb4 --protocol=tcp -h127.0.0.1 '
               '-uiam_dev unified_permission --batch --raw --skip-column-names']
    result = subprocess.run(command, cwd=REPO, input=sql, text=True, encoding="utf-8",
                            capture_output=True, timeout=90)
    if must_succeed and result.returncode:
        raise RuntimeError(result.stderr.strip() or "MySQL command failed")
    return result


def snapshot():
    result = mysql("""SELECT JSON_OBJECT('id', id, 'parent_id', parent_id,
        'dept_code', dept_code, 'dept_name', dept_name, 'dept_path', dept_path,
        'manager_id', manager_id, 'sort_order', sort_order, 'status', status,
        'hr_version', hr_version, 'created_at', created_at, 'updated_at', updated_at)
        FROM sys_dept ORDER BY id;""")
    return [json.loads(line) for line in result.stdout.splitlines() if line.strip()]


def fingerprint(rows):
    return hashlib.sha256(json.dumps(rows, ensure_ascii=False, sort_keys=True).encode()).hexdigest()


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def verify_structure(records, rows):
    existing = {r["dept_code"]: r for r in rows}
    for record in records:
        actual = existing[record["dept_code"]]
        parent = existing[record["parent_code"]] if record["parent_code"] else None
        require(actual["dept_name"] == record["dept_name"], "name mismatch: " + record["dept_code"])
        require(actual["parent_id"] == (parent["id"] if parent else 0), "parent mismatch")
        require(actual["dept_path"] == (parent["dept_path"] if parent else "/") + str(actual["id"]) + "/",
                "path mismatch: " + record["dept_code"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--import-and-verify", action="store_true", required=True,
                        help="explicitly opt in to inserting personal development departments")
    args = parser.parse_args()
    data = DATA.build(DATA.DEFAULT_SOURCE)
    records = data["departments"]
    sql = (REPO / "db/dev-data/department-seed.sql").read_text(encoding="utf-8")
    require(sql == DATA.make_sql(records), "Regenerate SQL first; checked-in output differs")
    before = snapshot()
    mysql(sql)
    after_first = snapshot()
    verify_structure(records, after_first)
    before_by_code = {r["dept_code"]: r for r in before}
    inserted = 0
    for actual in after_first:
        if actual["dept_code"] in before_by_code:
            require(actual == before_by_code[actual["dept_code"]], "Existing data was changed")
        else:
            require(actual["dept_code"] in {r["dept_code"] for r in records}, "Unexpected inserted code")
            require(actual["manager_id"] is None and actual["status"] == 1 and actual["hr_version"] == 0,
                    "Unexpected initial business fields")
            inserted += 1
    mysql(sql)
    require(snapshot() == after_first, "Second import must leave every row/field unchanged")

    # Insert a disposable root BEFORE a deliberate existing-code conflict, in the same
    # transaction. Each failed mysql batch disconnects; even that new root must roll back.
    probe = dict(records[0], dept_code="DEV-DEPT-ROLLBACK-PROBE", dept_name="rollback probe")
    require(not any(r["dept_code"] == probe["dept_code"] for r in after_first), "Probe code is already in use")
    conflict_results = []
    for kind in ("name", "parent", "path"):
        conflict = dict(records[0])
        if kind == "name":
            conflict["dept_name"] = "deliberate conflicting name"
        elif kind == "parent":
            conflict["parent_code"] = probe["dept_code"]
        conflict_sql = DATA.make_sql([probe, conflict])
        if kind == "path":
            old = "BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/')"
            conflict_sql = conflict_sql.replace(old, "BINARY dept_path = BINARY '/deliberate-conflict/'")
            # Only the existing row is asserted; the new probe still inserts normally.
        result = mysql(conflict_sql, must_succeed=False)
        require(result.returncode != 0 and "department_seed_structure_conflict" in result.stderr,
                "Expected explicit conflict error: " + kind)
        require(records[0]["dept_code"] in result.stdout, "Conflict must report the exact code")
        require(snapshot() == after_first, "Conflict must roll back all changes: " + kind)
        conflict_results.append(kind + ": rejected; earlier insert rolled back")

    # Change test business fields only inside a rolled-back transaction, then replay
    # the generated DML to demonstrate it does not reset runtime state on existing rows.
    preamble, body = sql.split("START TRANSACTION;\n", 1)
    body = body.split("COMMIT;", 1)[0]
    preserve_sql = preamble + "START TRANSACTION;\n" + (
        "UPDATE sys_dept SET status=0, hr_version=7, sort_order=9876 WHERE dept_code="
        + DATA.sql_text(records[0]["dept_code"]) + ";\n") + body + (
        "SELECT JSON_OBJECT('status',status,'hr_version',hr_version,'sort_order',sort_order) "
        "FROM sys_dept WHERE dept_code=" + DATA.sql_text(records[0]["dept_code"]) + ";\n"
        "ROLLBACK;\nDO RELEASE_LOCK('whatname.dev.department-seed');\n"
        "DROP TEMPORARY TABLE department_seed_guard;\n")
    preserved = json.loads(mysql(preserve_sql).stdout.strip())
    require(preserved == {"status": 0, "hr_version": 7, "sort_order": 9876}, "Business fields were reset")
    require(snapshot() == after_first, "Business-field verification must roll back")

    examples = [r for r in after_first if r["dept_code"] in ("DEV-DEPT-R0002", "DEV-DEPT-R0014", "DEV-DEPT-R0015")]
    report = {"source_count": data["count"], "before_total": len(before),
              "after_total": len(after_first), "inserted": inserted,
              "repeat_import": "all rows and timestamps unchanged", "all_paths": "verified from internal IDs",
              "conflicts": conflict_results, "business_fields": "preserved; verification rolled back",
              "snapshot_sha256": fingerprint(after_first),
              "examples": [{k: r[k] for k in ("id", "parent_id", "dept_code", "dept_name", "dept_path",
                                              "manager_id", "status", "hr_version")} for r in examples]}
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
