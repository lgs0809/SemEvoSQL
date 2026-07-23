#!/usr/bin/env python3
"""Offline, read-only catalog validation. No DB calls, SQL execution or eval."""
import argparse
import hashlib
import json
import math
import sys
from pathlib import Path

VERSION = "1.2.0"
ROOT = Path(__file__).resolve().parents[1]
NUMERIC = {"integer", "decimal"}


def digest(raw):
    return "sha256:" + hashlib.sha256(raw).hexdigest()


def fingerprint(value):
    # Protocol v1: sorted object keys, original array order, UTF-8, no whitespace.
    return digest(json.dumps(value, ensure_ascii=False, sort_keys=True,
                             separators=(",", ":"), allow_nan=False).encode("utf-8"))


def strict_json(raw):
    def pairs(items):
        result = {}
        for k, v in items:
            if k in result:
                raise ValueError("Duplicate JSON key: " + k)
            result[k] = v
        return result
    def reject(value):
        raise ValueError("Nonstandard numeric constant: " + value)
    def finite_float(value):
        result = float(value)
        if not math.isfinite(result):
            raise ValueError("Nonfinite numeric value: " + value)
        return result
    return json.loads(raw.decode("utf-8"), object_pairs_hook=pairs,
                      parse_constant=reject, parse_float=finite_float)


def pointer(parts):
    return "/" + "/".join(str(p).replace("~", "~0").replace("/", "~1") for p in parts)


class Checks:
    def __init__(self):
        self.errors = []
        self.warnings = []

    def error(self, code, path, message):
        self.errors.append(dict(code=code, path=path, message=message))

    def unique(self, values, path):
        if len(set(values)) != len(values):
            self.error("DUPLICATE_ID", path, "Duplicate identifier or key component")

    def run(self, package, source):
        if package["sourceSchemaFingerprint"] != fingerprint(source):
            self.error("SCHEMA_FINGERPRINT_MISMATCH", "/sourceSchemaFingerprint", "Source snapshot differs")
        physical = {}
        for i, table in enumerate(source["tables"]):
            p = "/sourceSchema/tables/" + str(i)
            key = tuple(table[k] for k in ("datasource", "schema", "table"))
            if key in physical:
                self.error("DUPLICATE_TABLE", p, str(key))
            physical[key] = table
            cols = {c["name"]: c for c in table["columns"]}
            self.unique([c["name"] for c in table["columns"]], p + "/columns")
            for keys in [table["primaryKey"]] + table["uniqueKeys"]:
                self.unique(keys, p)
                if not set(keys) <= cols.keys():
                    self.error("UNKNOWN_COLUMN", p, "Unknown key column")
            for c in table["primaryKey"]:
                if c in cols and cols[c]["nullable"]:
                    self.error("NULLABLE_PRIMARY_KEY", p, c)
        for i, table in enumerate(source["tables"]):
            for fk in table["foreignKeys"]:
                t = fk["target"]
                dest = physical.get(tuple(t[k] for k in ("datasource", "schema", "table")))
                if (not dest or len(fk["columns"]) != len(t["columns"])
                        or not set(fk["columns"]) <= {c["name"] for c in table["columns"]}
                        or not set(t["columns"]) <= {c["name"] for c in dest["columns"]}):
                    self.error("INVALID_FOREIGN_KEY", "/sourceSchema/tables/" + str(i), "Unresolved foreign key")

        cat = package["catalog"]
        entities = {e["code"]: e for e in cat["entities"]}
        attrs = {k: {a["code"]: a for a in e["attributes"]} for k, e in entities.items()}
        metrics = {m["code"]: m for m in cat["metrics"]}
        targets = {"catalog"}
        for group, kind in [("entities", "entity"), ("metrics", "metric"),
                            ("dimensions", "dimension"), ("relationships", "relationship"), ("rules", "rule")]:
            self.unique([a["code"] for a in cat[group]], "/catalog/" + group)
            targets.update(kind + ":" + a["code"] for a in cat[group])

        def attr(entity, name, path):
            found = attrs.get(entity, {}).get(name)
            if not found:
                self.error("UNKNOWN_ATTRIBUTE", path, entity + "." + name)
            return found

        def filters(items, entity, path):
            for i, f in enumerate(items):
                p = path + "/" + str(i)
                a = attr(entity, f["attribute"], p)
                if not a or "value" not in f:
                    continue
                values = f["value"] if isinstance(f["value"], list) else [f["value"]]
                for v in values:
                    if not compatible(v, a["dataType"]):
                        self.error("FILTER_TYPE", p, "Literal incompatible with attribute type")
                if a["dataType"] == "boolean" and f["operator"] in {"gt", "gte", "lt", "lte"}:
                    self.error("FILTER_OPERATOR", p, "Ordered boolean comparison unsupported")

        for i, entity in enumerate(cat["entities"]):
            p = "/catalog/entities/" + str(i)
            name = entity["code"]
            self.unique([a["code"] for a in entity["attributes"]], p + "/attributes")
            targets.update("entity:" + name + "/attribute:" + a["code"] for a in entity["attributes"])
            tables = entity["source"]["tables"]
            self.unique([t["alias"] for t in tables], p + "/source/tables")
            aliases = {}
            for t in tables:
                key = tuple(t[k] for k in ("datasource", "schema", "table"))
                if key not in physical:
                    self.error("UNKNOWN_TABLE", p + "/source", str(key))
                else:
                    aliases[t["alias"]] = physical[key]
            if len({t["datasource"] for t in tables}) > 1:
                self.error("UNSUPPORTED_SOURCE", p, "v1 entity joins must use one datasource")

            def column(mapping):
                table = aliases.get(mapping["source"])
                c = next((c for c in table["columns"] if c["name"] == mapping["column"]), None) if table else None
                if c is None:
                    self.error("UNKNOWN_COLUMN", p + "/source", str(mapping))
                return c

            base = entity["source"]["base"]
            if base not in aliases:
                self.error("UNKNOWN_SOURCE_ALIAS", p, base)
            joined = {base}
            for j, join in enumerate(entity["source"]["joins"]):
                jp = p + "/source/joins/" + str(j)
                right = join["right"]
                if right in joined or right not in aliases:
                    self.error("INVALID_JOIN", jp, "Right source must be new and known")
                right_cols = []
                for pair in join["on"]:
                    l, r = pair["left"], pair["right"]
                    if l["source"] not in joined or r["source"] != right:
                        self.error("INVALID_JOIN_SCOPE", jp, "Left must be joined; right must match new alias")
                    lc, rc = column(l), column(r)
                    if lc and rc and not same_type(lc["dataType"], rc["dataType"]):
                        self.error("JOIN_TYPE", jp, "Join key types incompatible")
                    right_cols.append(r["column"])
                self.unique(right_cols, jp)
                if right in aliases and not key_is_unique(aliases[right], right_cols):
                    self.error("JOIN_FANOUT", jp, "Right join columns must cover an exported unique key; history selection/aggregation is outside v1")
                joined.add(right)
            if joined != set(aliases):
                self.error("DISCONNECTED_SOURCE", p, "Every source must join the base")
            for a in entity["attributes"]:
                c = column(a["mapping"])
                if c and c["dataType"] != a["dataType"]:
                    self.error("MAPPING_TYPE", p + "/attributes", a["code"] + ": casts are not supported")
                vals = [json.dumps(v["value"], sort_keys=True) for v in a.get("enumValues", [])]
                self.unique(vals, p + "/attributes")
                if any(not compatible(v["value"], a["dataType"]) for v in a.get("enumValues", [])):
                    self.error("ENUM_TYPE", p, a["code"])
            self.unique(entity["primaryKey"], p + "/primaryKey")
            pk = [attr(name, k, p + "/primaryKey") for k in entity["primaryKey"]]
            if all(pk) and base in aliases:
                mappings = [a["mapping"] for a in pk]
                cols = [m["column"] for m in mappings]
                if (any(m["source"] != base for m in mappings)
                        or not key_is_unique(aliases[base], cols)
                        or any((column(m) or {}).get("nullable", True) for m in mappings)):
                    self.error("UNPROVEN_GRAIN", p + "/primaryKey", "v1 requires a nonnullable exported unique key on base table")
            filters(entity["filters"], name, p + "/filters")

        def expression(node, entity, path, stack):
            # Returns semantic type and aggregation level: 0 literal, 1 row, 2 aggregate.
            if "literal" in node:
                return "decimal", 0
            if "attribute" in node:
                a = attr(entity, node["attribute"], path)
                return (a["dataType"] if a else "unknown"), 1
            if "metric" in node:
                name = node["metric"]
                m = metrics.get(name)
                if not m or m["entity"] != entity:
                    self.error("UNKNOWN_METRIC", path, "Metric must exist on same entity: " + name)
                    return "unknown", 2
                if name in stack:
                    self.error("DEPENDENCY_CYCLE", path, " -> ".join(stack + [name]))
                    return "unknown", 2
                if m["filters"]:
                    self.error("FILTERED_METRIC_REFERENCE", path, "v1 does not compose metrics with independent filters")
                if m["timeAttribute"] != metrics[stack[0]]["timeAttribute"]:
                    self.error("METRIC_TIME_MISMATCH", path, "Referenced metric time must match caller")
                return expression(m["expression"], entity, path, stack + [name])
            op = node["op"]
            if op == "count_rows":
                return "integer", 2
            if "arg" in node:
                t, level = expression(node["arg"], entity, path + "/arg", stack)
                if level != 1:
                    self.error("AGGREGATION_LEVEL", path, "Aggregate requires a row expression")
                if op in {"sum", "avg"} and t not in NUMERIC:
                    self.error("EXPRESSION_TYPE", path, op + " requires numeric input")
                if op in {"min", "max"} and t == "boolean":
                    self.error("EXPRESSION_TYPE", path, "Boolean min/max unsupported")
                return ("integer" if op == "count_distinct" else "decimal" if op == "avg" else t), 2
            lt, ll = expression(node["left"], entity, path + "/left", stack)
            rt, rl = expression(node["right"], entity, path + "/right", stack)
            if lt not in NUMERIC or rt not in NUMERIC:
                self.error("EXPRESSION_TYPE", path, "Arithmetic requires numeric input")
            if {ll, rl} == {1, 2}:
                self.error("AGGREGATION_LEVEL", path, "Cannot mix unaggregated rows with aggregates")
            if op == "divide" and node["right"].get("literal") == 0:
                self.error("ZERO_DIVISOR", path, "Constant zero divisor")
            return "decimal", max(ll, rl)

        for i, m in enumerate(cat["metrics"]):
            p = "/catalog/metrics/" + str(i)
            bounds = m.get("valueRange", {})
            if "minimum" in bounds and "maximum" in bounds:
                lower, upper = bounds["minimum"], bounds["maximum"]
                if lower > upper or (lower == upper and (not bounds.get("minimumInclusive", True)
                                                         or not bounds.get("maximumInclusive", True))):
                    self.error("INVALID_RANGE", p + "/valueRange", "Range is empty or reversed")
            if m["entity"] not in entities:
                self.error("UNKNOWN_ENTITY", p, m["entity"])
            _, level = expression(m["expression"], m["entity"], p + "/expression", [m["code"]])
            if level != 2:
                self.error("METRIC_GRAIN", p, "Metric must aggregate rows")
            filters(m["filters"], m["entity"], p + "/filters")
            if m["timeAttribute"] is not None:
                a = attr(m["entity"], m["timeAttribute"], p + "/timeAttribute")
                if a and a["dataType"] not in {"date", "datetime"}:
                    self.error("TIME_TYPE", p + "/timeAttribute", "Expected date or datetime")
        for i, d in enumerate(cat["dimensions"]):
            attr(d["entity"], d["attribute"], "/catalog/dimensions/" + str(i))
        for i, rule in enumerate(cat["rules"]):
            p = "/catalog/rules/" + str(i)
            a = attr(rule["entity"], rule["attribute"], p)
            if a and a["dataType"] not in NUMERIC:
                self.error("RULE_TYPE", p, "Range rule requires numeric attribute")
            if rule["minimum"] > rule["maximum"]:
                self.error("INVALID_RANGE", p, "minimum exceeds maximum")
        for i, r in enumerate(cat["relationships"]):
            p = "/catalog/relationships/" + str(i)
            f, t = r["fromEntity"], r["toEntity"]
            pairs = r["pairs"]
            for pair in pairs:
                a, b = attr(f, pair["from"], p), attr(t, pair["to"], p)
                if a and b and not same_type(a["dataType"], b["dataType"]):
                    self.error("JOIN_TYPE", p, "Relationship key types incompatible")
            for side, name, field in [("from", f, "from"), ("to", t, "to")]:
                needs_one = r["cardinality"] == "one_to_one" or (side == "to" and r["cardinality"] == "many_to_one") or (side == "from" and r["cardinality"] == "one_to_many")
                if needs_one and name in entities and not set(entities[name]["primaryKey"]) <= {v[field] for v in pairs}:
                    self.error("UNPROVEN_CARDINALITY", p, "One side must cover entity primary key")
        evidenced = set()
        for i, e in enumerate(package["evidence"]):
            if e["target"] not in targets:
                self.error("UNKNOWN_EVIDENCE_TARGET", "/evidence/" + str(i), e["target"])
            evidenced.add(e["target"])
        for target in sorted(targets - evidenced - {"catalog"}):
            self.error("MISSING_EVIDENCE", "/evidence", "Evidence/confirmation required for " + target)
        for i, issue in enumerate(package["unresolvedIssues"]):
            p = "/unresolvedIssues/" + str(i)
            if issue["target"] not in targets:
                self.error("UNKNOWN_ISSUE_TARGET", p, issue["target"])
            if issue["blocking"]:
                self.error("UNRESOLVED_DEFINITION", p, issue["question"])
            else:
                self.warnings.append(dict(code="OPEN_QUESTION", path=p, message=issue["question"]))


def compatible(value, datatype):
    if datatype == "integer":
        return isinstance(value, int) and not isinstance(value, bool)
    if datatype == "decimal":
        return isinstance(value, (int, float)) and not isinstance(value, bool)
    if datatype == "boolean":
        return isinstance(value, bool)
    return isinstance(value, str)


def same_type(a, b):
    return a == b or (a in NUMERIC and b in NUMERIC)


def key_is_unique(table, cols):
    return any(k and set(k) <= set(cols) for k in [table["primaryKey"]] + table["uniqueKeys"])


def validate(catalog_path, source_path):
    checks = Checks()
    report = dict(valid=False, validationScope="offline-package-v1", validatorVersion=VERSION,
                  formatVersion="1.0", importerCompatibility="not_verified",
                  catalogSha256=None, sourceSchemaFingerprint=None,
                  errors=checks.errors, warnings=checks.warnings)
    try:
        from jsonschema import Draft202012Validator
        schemas = {}
        for name in ("semantic-catalog", "semantic-catalog-1.1", "semantic-catalog-1.2", "source-schema"):
            schemas[name] = strict_json((ROOT / "schemas" / (name + ".schema.json")).read_bytes())
            Draft202012Validator.check_schema(schemas[name])
    except Exception as exc:
        checks.error("VALIDATOR_SETUP", "/", str(exc))
        return report, 2
    try:
        raw = Path(catalog_path).read_bytes()
        report["catalogSha256"] = digest(raw)
        package = strict_json(raw)
        source = strict_json(Path(source_path).read_bytes())
        report["sourceSchemaFingerprint"] = fingerprint(source)
    except (ValueError, UnicodeError, OSError, RecursionError) as exc:
        checks.error("INPUT_ERROR", "/", str(exc))
        return report, 1
    version = package.get("formatVersion") if isinstance(package, dict) else None
    contract = schemas["semantic-catalog-" + version] if version in ("1.1", "1.2") else schemas["semantic-catalog"]
    report["formatVersion"] = version
    report["schemaContractFingerprint"] = fingerprint({"semantic-catalog": contract, "source-schema": schemas["source-schema"]})
    for name, data, schema in [("semantic-catalog", package, contract), ("source-schema", source, schemas["source-schema"])]:
        for error in Draft202012Validator(schema).iter_errors(data):
            prefix = [] if name == "semantic-catalog" else ["sourceSchema"]
            checks.error("SCHEMA_VIOLATION", pointer(prefix + list(error.absolute_path)), error.message)
    if checks.errors:
        return report, 1
    from semantic_review import review_inventory
    report["semanticReview"] = review_inventory(package, source)
    if version == "1.2":
        from shared_catalog import expand_shared
        try:
            package = expand_shared(package)
        except ValueError as error:
            checks.error(str(error), "/catalog", "Shared meaning or model role is invalid")
            return report, 1
    checks.run(package, source)
    report["valid"] = not checks.errors
    return report, 0 if report["valid"] else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", required=True)
    parser.add_argument("--source-schema", required=True)
    parser.add_argument("--report", required=True)
    args = parser.parse_args()
    output = Path(args.report).resolve()
    protected = [Path(args.catalog).resolve(), Path(args.source_schema).resolve()]
    if output in protected or ROOT == output or ROOT in output.parents:
        print("Report must not overwrite inputs or Skill resources", file=sys.stderr)
        return 2
    try:
        report, status = validate(args.catalog, args.source_schema)
    except Exception as exc:
        report = dict(valid=False, errors=[dict(code="VALIDATOR_FAILURE", path="/", message=str(exc))], warnings=[])
        status = 2
    text = json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False)
    try:
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(text + "\n", encoding="utf-8")
    except OSError as exc:
        print("Cannot write report: " + str(exc), file=sys.stderr)
        return 2
    print(text)
    return status


if __name__ == "__main__":
    sys.exit(main())
