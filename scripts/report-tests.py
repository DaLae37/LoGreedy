"""Report JUnit outcomes as bounded GitHub annotations and a job summary."""
import os
from pathlib import Path
import xml.etree.ElementTree as ET


def Escape(value):
    return value.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def Main():
    totals = {key: 0 for key in ("tests", "failures", "errors", "skipped")}
    emitted = 0
    for path in sorted(Path("logreedy-core/build/test-results/test").glob("TEST-*.xml")):
        suite = ET.parse(path).getroot()
        for key in totals:
            totals[key] += int(suite.get(key, "0"))
        for case in suite.findall("testcase"):
            for failure in list(case.findall("failure")) + list(case.findall("error")):
                if emitted < 20:
                    text = case.get("classname", "") + "." + case.get("name", "") + ": " + failure.get("message", "")
                    stack = "\n".join((failure.text or "").splitlines()[:8])
                    print("::error title=JUnit failure::" + Escape((text + "\n" + stack)[:4000]))
                    emitted += 1
    summary = "JUnit: " + ", ".join(key + "=" + str(value) for key, value in totals.items())
    print(summary)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with Path(os.environ["GITHUB_STEP_SUMMARY"]).open("a", encoding="utf-8") as output:
            output.write("## Test results\n\n" + summary + "\n")


if __name__ == "__main__":
    Main()
