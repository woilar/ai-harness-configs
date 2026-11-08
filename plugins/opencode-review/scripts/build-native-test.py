#!/usr/bin/env python3
"""Build a separate, opt-in IDE test plugin. Never bundled into OpenCode Review."""
from pathlib import Path
import os, subprocess, zipfile
root=Path(__file__).resolve().parents[1]
idea=Path(os.environ.get('IDEA_HOME','/Applications/IntelliJ IDEA.app/Contents'))
build=root/'build/native-test';build.mkdir(parents=True,exist_ok=True)
cp=os.pathsep.join(map(str,(idea/'lib').rglob('*.jar')))
sources=list((root/'intellij/src/test/native').rglob('*.java'))
subprocess.run([str(idea/'jbr/Contents/Home/bin/javac'),'-cp',cp,'-d',str(build),*map(str,sources)],check=True)
with zipfile.ZipFile(build/'probe.jar','w',zipfile.ZIP_DEFLATED) as z:
    for p in build.rglob('*.class'):z.write(p,p.relative_to(build))
    z.writestr('META-INF/plugin.xml','<idea-plugin><id>local.markdown.probe</id><name>Local Markdown Verification</name><version>1</version><vendor>Local test</vendor><depends>dev.opencodereview</depends><extensions defaultExtensionNs="com.intellij"><postStartupActivity implementation="probe.ProbeStartup"/></extensions><actions><action id="local.markdown.verify" class="probe.MarkdownProbeAction" text="Verify OpenCode Markdown"/></actions></idea-plugin>')
with zipfile.ZipFile(build/'markdown-native-test.zip','w',zipfile.ZIP_DEFLATED) as z:z.write(build/'probe.jar','markdown-probe/lib/probe.jar')
print(build/'markdown-native-test.zip')
