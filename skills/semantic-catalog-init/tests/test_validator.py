import copy
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
from validate_catalog import validate, fingerprint, strict_json, digest


class ValidatorTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.work = Path(self.tmp.name)
        self.catalog = strict_json((ROOT / "examples/minimal-catalog.json").read_bytes())
        self.source = strict_json((ROOT / "examples/source-schema.json").read_bytes())

    def tearDown(self):
        self.tmp.cleanup()

    def check(self, catalog=None, source=None, sync=True):
        catalog = copy.deepcopy(self.catalog if catalog is None else catalog)
        source = copy.deepcopy(self.source if source is None else source)
        if sync:
            catalog["sourceSchemaFingerprint"] = fingerprint(source)
        cp, sp = self.work / "catalog.json", self.work / "source.json"
        cp.write_text(json.dumps(catalog, ensure_ascii=False), encoding="utf-8")
        sp.write_text(json.dumps(source, ensure_ascii=False), encoding="utf-8")
        return validate(cp, sp)

    def test_valid_fixture(self):
        report, status = self.check(sync=False)
        self.assertEqual(status, 0, report)
        self.assertTrue(report["valid"])
        self.assertEqual(report["importerCompatibility"], "not_verified")
        self.assertEqual(report["catalogSha256"], digest((self.work / "catalog.json").read_bytes()))
        review=report["semanticReview"]
        self.assertEqual(review["businessQuality"], "not_assessed_by_program")
        self.assertEqual(len(review["reviewTargets"]), 6)
        self.assertTrue(all(row["evidence"] for row in review["reviewTargets"]))
        self.assertTrue(all(row["businessDecision"]=="requires_model_review" for row in review["reviewTargets"]))

    def test_shared_retrieval_protocol_and_negative_examples(self):
        sample = strict_json((ROOT / "examples/retrieval-catalog-1.1.json").read_bytes())
        report, status = self.check(sample)
        self.assertEqual(status, 0, report)
        self.assertEqual(report["formatVersion"], "1.1")
        for case in strict_json((ROOT / "examples/retrieval-negative-cases-1.1.json").read_bytes()):
            with self.subTest(name=case["name"]):
                value = copy.deepcopy(sample)
                segments = case["path"].strip("/").split("/")
                target = value
                for part in segments[:-1]:
                    target = target[int(part)] if isinstance(target, list) else target[part]
                target[int(segments[-1]) if isinstance(target, list) else segments[-1]] = case["value"]
                report, status = self.check(value)
                self.assertEqual(status, 1, report)

    def test_negative_cases(self):
        def entity(c): return c["catalog"]["entities"][0]
        def metric(c): return c["catalog"]["metrics"][0]
        cases = [
            ("unknown_property", lambda c: c.update(extra=True), "SCHEMA_VIOLATION"),
            ("version", lambda c: c.update(formatVersion="9"), "SCHEMA_VIOLATION"),
            ("table", lambda c: entity(c)["source"]["tables"][0].update(table="absent"), "UNKNOWN_TABLE"),
            ("field", lambda c: entity(c)["attributes"][1]["mapping"].update(column="absent"), "UNKNOWN_COLUMN"),
            ("duplicate", lambda c: entity(c)["attributes"].append(copy.deepcopy(entity(c)["attributes"][0])), "DUPLICATE_ID"),
            ("reference", lambda c: metric(c).update(timeAttribute="payment_time"), "UNKNOWN_ATTRIBUTE"),
            ("time_type", lambda c: metric(c).update(timeAttribute="status"), "TIME_TYPE"),
            ("unit_cast", lambda c: entity(c)["attributes"][1].update(dataType="decimal"), "MAPPING_TYPE"),
            ("filter_type", lambda c: metric(c)["filters"][0].update(value="paid"), "FILTER_TYPE"),
            ("expression", lambda c: metric(c).update(expression={"op":"shell"}), "SCHEMA_VIOLATION"),
            ("missing_arg", lambda c: metric(c).update(expression={"op":"sum"}), "SCHEMA_VIOLATION"),
            ("string_sum", lambda c: metric(c).update(expression={"op":"sum","arg":{"attribute":"paid_at"}}), "EXPRESSION_TYPE"),
            ("nested", lambda c: metric(c).update(expression={"op":"sum","arg":{"op":"count_rows"}}), "AGGREGATION_LEVEL"),
            ("row_metric", lambda c: metric(c).update(expression={"attribute":"paid_amount"}), "METRIC_GRAIN"),
            ("mixed", lambda c: metric(c).update(expression={"op":"add","left":{"op":"count_rows"},"right":{"attribute":"paid_amount"}}), "AGGREGATION_LEVEL"),
            ("zero", lambda c: metric(c)["expression"]["right"].update(literal=0), "ZERO_DIVISOR"),
            ("cycle", lambda c: metric(c).update(expression={"metric":"payment_amount"}), "DEPENDENCY_CYCLE"),
            ("grain", lambda c: entity(c).update(primaryKey=["status"]), "UNPROVEN_GRAIN"),
            ("no_evidence", lambda c: c.update(evidence=[]), "SCHEMA_VIOLATION"),
            ("partial_evidence", lambda c: c["evidence"].pop(), "MISSING_EVIDENCE"),
            ("wrong_evidence", lambda c: c["evidence"][0].update(target="entity:ghost"), "UNKNOWN_EVIDENCE_TARGET"),
            ("blocking", lambda c: c["unresolvedIssues"].append({"target":"catalog","question":"单位未确定","blocking":True}), "UNRESOLVED_DEFINITION"),
        ]
        for name, mutate, expected in cases:
            with self.subTest(name=name):
                c = copy.deepcopy(self.catalog)
                mutate(c)
                report, status = self.check(c)
                self.assertEqual(status, 1, report)
                self.assertIn(expected, {e["code"] for e in report["errors"]}, report)

    def test_shared_definitions_use_explicit_roles_not_names_or_joins(self):
        sample = strict_json((ROOT / "examples/shared-catalog-1.2.json").read_bytes())
        report, status = self.check(sample)
        self.assertEqual(status, 0, report)
        self.assertEqual(report["formatVersion"], "1.2")
        from shared_catalog import expand_shared, asset_code
        expanded = expand_shared(sample)
        self.assertNotEqual(asset_code("order", "payment"), asset_code("paid_order", "payment"))
        self.assertEqual(len(expanded["catalog"]["metrics"]), 2)
        self.assertEqual(expanded["catalog"]["relationships"], [])
        self.assertEqual(expanded["catalog"]["metrics"][1]["timeAttribute"], "settled_on")
        warning = copy.deepcopy(sample)
        warning["unresolvedIssues"].append({"target":"definition:payment_amount@1", "question":"以后补充检索示例", "blocking":False})
        self.assertEqual(self.check(warning)[1], 0)
        for case in strict_json((ROOT / "examples/shared-negative-cases-1.2.json").read_bytes()):
            with self.subTest(name=case["name"]):
                value = copy.deepcopy(sample)
                parts = case["path"].strip("/").split("/")
                node = value
                for part in parts[:-1]:
                    node = node[int(part)] if isinstance(node, list) else node[part]
                node[int(parts[-1]) if isinstance(node,list) else parts[-1]] = case["value"]
                report, status = self.check(value)
                self.assertEqual(status, 1, report)

    def test_failure_fix_revalidate(self):
        c = copy.deepcopy(self.catalog)
        c["catalog"]["metrics"][0]["timeAttribute"] = "payment_time"
        report, status = self.check(c)
        self.assertEqual(status, 1)
        self.assertTrue(any(e["path"].endswith("/timeAttribute") for e in report["errors"]))
        c["catalog"]["metrics"][0]["timeAttribute"] = "paid_at"
        self.assertEqual(self.check(c)[1], 0)

    def test_fingerprint_and_report_change(self):
        before, _ = self.check()
        c = copy.deepcopy(self.catalog)
        c["catalog"]["entities"][0]["name"] = "业务订单"
        after, _ = self.check(c)
        self.assertNotEqual(before["catalogSha256"], after["catalogSha256"])
        c["sourceSchemaFingerprint"] = "sha256:" + "0" * 64
        self.assertEqual(self.check(c, sync=False)[1], 1)

    def test_unique_join_and_fanout(self):
        c, s = copy.deepcopy(self.catalog), copy.deepcopy(self.source)
        extra = copy.deepcopy(s["tables"][0])
        extra["table"] = "t_order_ext"
        s["tables"].append(extra)
        src = c["catalog"]["entities"][0]["source"]
        src["tables"].append({"alias":"x","datasource":"mall","schema":"shop","table":"t_order_ext"})
        src["joins"].append({"type":"left","right":"x","on":[{"left":{"source":"o","column":"id"},"right":{"source":"x","column":"id"}}]})
        self.assertEqual(self.check(c, s)[1], 0)
        s["tables"][1]["primaryKey"] = []
        report, status = self.check(c, s)
        self.assertEqual(status, 1)
        self.assertIn("JOIN_FANOUT", {e["code"] for e in report["errors"]})

    def test_warning_does_not_hide_errors(self):
        self.catalog["unresolvedIssues"].append({"target":"catalog","question":"以后补充说明文字","blocking":False})
        report, status = self.check()
        self.assertEqual(status, 0)
        self.assertEqual(len(report["warnings"]), 1)
        self.catalog["catalog"]["metrics"][0]["timeAttribute"] = "bad"
        self.assertEqual(self.check()[1], 1)

    def test_strict_json(self):
        for raw in [b'{"a":1,"a":2}', b'{"a":NaN}', b'{"a":Infinity}', b'{"a":1e999}', b'{"a":1,}']:
            with self.subTest(raw=raw):
                with self.assertRaises(ValueError): strict_json(raw)

    def test_cli_and_input_protection(self):
        self.check()
        cp, sp = self.work / "catalog.json", self.work / "source.json"
        original = cp.read_bytes()
        cmd = [sys.executable, str(ROOT / "scripts/validate_catalog.py"), "--catalog", str(cp), "--source-schema", str(sp), "--report"]
        run = subprocess.run(cmd + [str(self.work / "report.json")], capture_output=True)
        self.assertEqual(run.returncode, 0, run.stderr)
        run = subprocess.run(cmd + [str(cp)], capture_output=True)
        self.assertEqual(run.returncode, 2)
        self.assertEqual(cp.read_bytes(), original)
        # -S removes site packages: missing JSON Schema validator must not pass.
        run = subprocess.run([sys.executable, "-S"] + cmd[1:] + [str(self.work / "missing-dependency.json")], capture_output=True)
        self.assertEqual(run.returncode, 2)
        self.assertFalse(json.loads(run.stdout)["valid"])


if __name__ == "__main__":
    unittest.main()
