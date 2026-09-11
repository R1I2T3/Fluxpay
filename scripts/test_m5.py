"""Run M5 test groups and require actual executed-test evidence."""
import argparse
from pathlib import Path
import uuid

from m5_support import (PROJECT_ROOT, check_reports, fail, load_environment,
                        m5_maven_command, run_owned_process, validate_isolated_environment)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--suite", choices=("unit", "oracle", "all"), default="unit")
    parser.add_argument("--env-file", type=Path)
    args = parser.parse_args(argv)
    try:
        env = load_environment(args.env_file)
        groups = []
        if args.suite in {"unit", "all"}:
            groups.extend(("UnitTest", "WebTest", "ContractTest"))
        if args.suite in {"oracle", "all"}:
            if args.env_file is None:
                raise ValueError("Oracle selection requires --env-file with an explicitly designated disposable schema")
            validate_isolated_environment(env)
            groups.append("OracleTest")
        reports = PROJECT_ROOT / "backend" / "target" / "m5-reports" / str(uuid.uuid4())
        command = m5_maven_command("-Dtest=" + ",".join("M5*" + group for group in groups),
                                   "-Dm5.solo.launch=false", "-DfailIfNoTests=true",
                                   "-Dsurefire.reportsDirectory=" + str(reports), "test")
        result = run_owned_process(command, env)
        if result:
            return result
        counts = check_reports(reports, groups)
        print("M5 executed: " + ", ".join(f"{group}={count}" for group, count in counts.items()))
        if args.suite == "unit":
            print("Oracle acceptance remains separate; no database checks were selected.")
        return 0
    except (ValueError, OSError, RuntimeError) as error:
        return fail(str(error))


if __name__ == "__main__":
    raise SystemExit(main())
