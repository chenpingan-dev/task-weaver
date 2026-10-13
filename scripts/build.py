"""Portable Java 17 build, test and runnable JAR without third-party dependencies."""
import argparse
import subprocess
from pathlib import Path

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument("--test", action="store_true")
args = parser.parse_args()
classes = root / "build/classes"
classes.mkdir(parents=True, exist_ok=True)
sources = sorted((root / "src/main/java").rglob("*.java"))
if args.test:
    sources += sorted((root / "src/test/java").rglob("*.java"))
subprocess.run(["javac", "--release", "17", "-encoding", "UTF-8", "-Xlint:all", "-d", str(classes),
                *map(str, sources)], check=True)
if args.test:
    subprocess.run(["java", "-cp", str(classes), "dev.taskweaver.TaskEngineTest"], check=True)
subprocess.run(["jar", "--create", "--file", str(root / "build/task-weaver.jar"), "--main-class",
                "dev.taskweaver.Demo", "-C", str(classes), "dev/taskweaver/TaskEngine.class"], check=True)
# Add the public nested types and demo without shipping test classes.
members = [path.relative_to(classes).as_posix() for path in sorted((classes / "dev/taskweaver").glob("TaskEngine$*.class"))]
members.append("dev/taskweaver/Demo.class")
subprocess.run(["jar", "--update", "--file", str(root / "build/task-weaver.jar"),
                *sum((["-C", str(classes), member] for member in members), [])], check=True)
print("Built build/task-weaver.jar")
