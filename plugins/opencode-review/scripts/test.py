#!/usr/bin/env python3
"""Run IDEA contracts against the locally built plugin without starting the IDE."""
from pathlib import Path
import os,subprocess
root=Path(__file__).resolve().parents[1]
idea=Path(os.environ.get('IDEA_HOME','/Applications/IntelliJ IDEA.app/Contents'))
build=Path(os.environ.get('OPENCODE_REVIEW_BUILD_DIR',str(root/'build')))
classes=build/'classes';tests=build/'test-classes';tests.mkdir(parents=True,exist_ok=True)
if not classes.is_dir():raise SystemExit('Run scripts/build.py first.')
jbr=idea/'jbr/Contents/Home/bin'
cp=os.pathsep.join(map(str,[classes,*[p for base in ['lib','plugins/terminal/lib'] for p in (idea/base).rglob('*.jar')]]))
sources=list((root/'intellij/src/test/java/dev/opencodereview').glob('*.java'))
subprocess.run([str(jbr/'javac'),'--release','25','-cp',cp,'-d',str(tests),*map(str,sources)],check=True)
for source in sorted(sources):
    name=source.stem;args=[str(build/'renders')] if name in ['ThreadMessagesTest','ThreadOverviewTest'] else []
    subprocess.run([str(jbr/'java'),'-Djava.awt.headless=true','-cp',str(tests)+os.pathsep+cp,'dev.opencodereview.'+name,*args],check=True,timeout=45)
