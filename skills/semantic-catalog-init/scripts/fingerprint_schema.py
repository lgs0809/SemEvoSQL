#!/usr/bin/env python3
"""Print a fingerprint for a structurally valid source snapshot; no writes."""
import argparse
import sys
from pathlib import Path
from validate_catalog import ROOT, fingerprint, strict_json


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-schema", required=True)
    args = parser.parse_args()
    try:
        from jsonschema import Draft202012Validator
        schema = strict_json((ROOT / "schemas/source-schema.schema.json").read_bytes())
        Draft202012Validator.check_schema(schema)
    except Exception as exc:
        print("Validator setup error: " + str(exc), file=sys.stderr)
        return 2
    try:
        data = strict_json(Path(args.source_schema).read_bytes())
        Draft202012Validator(schema).validate(data)
        print(fingerprint(data))
        return 0
    except Exception as exc:
        print("Invalid input: " + str(exc), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
