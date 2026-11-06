#!/usr/bin/env python3
"""Build against local IDEA 2026.2/2026.3 (IDEA_HOME); no network or Gradle required."""
from pathlib import Path
import os
import shutil
import subprocess
import zipfile
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
dist = root / "dist"
dist.mkdir(exist_ok=True)
idea = Path(os.environ.get("IDEA_HOME", "/Applications/IntelliJ IDEA.app/Contents"))
build = Path(os.environ.get("OPENCODE_REVIEW_BUILD_DIR", str(root / "build")))
classes = build / "classes"
if classes.exists(): shutil.rmtree(classes)
classes.mkdir(parents=True, exist_ok=True)
jars = [str(p) for directory in ["lib", "plugins/terminal/lib"] for p in (idea / directory).rglob("*.jar")]
sources = [str(p) for p in (root / "intellij/src/main/java").rglob("*.java")]
subprocess.run([str(idea / "jbr/Contents/Home/bin/javac"), "--release", "25", "-classpath", os.pathsep.join(jars), "-d", str(classes), *sources], check=True)
shutil.copytree(root / "intellij/src/main/resources", classes, dirs_exist_ok=True)
jar = build / "opencode-review.jar"
with zipfile.ZipFile(jar, "w", zipfile.ZIP_DEFLATED) as z:
    for path in classes.rglob("*"):
        if path.is_file(): z.write(path, path.relative_to(classes))
version = ET.parse(root / "intellij/src/main/resources/META-INF/plugin.xml").findtext("version")
output = dist / f"opencode-review-{version}.zip"
with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as z:
    z.write(jar, "opencode-review/lib/" + jar.name)
print(output)
