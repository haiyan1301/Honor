#!/usr/bin/env python3
"""Build without Gradle using an installed Android SDK and the compile-only Xposed API."""
import argparse
import os
import shutil
import subprocess
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent
p = argparse.ArgumentParser()
p.add_argument('--android-jar', type=Path, required=True)
p.add_argument('--build-tools', type=Path, required=True)
p.add_argument('--xposed-api', type=Path, required=True)
p.add_argument('--key', type=Path, required=True)
p.add_argument('--cert', type=Path, required=True)
p.add_argument('--out', type=Path, default=ROOT/'build'/'HonorFastPassObserver-1.0.0.apk')
a = p.parse_args()
work = ROOT/'build'
for sub in ['classes', 'dex', 'tests']:
    shutil.rmtree(work/sub, ignore_errors=True)
    (work/sub).mkdir(parents=True)

def run(*args):
    subprocess.run([str(x) for x in args], cwd=ROOT, check=True)

javac = ['java', '-m', 'jdk.compiler/com.sun.tools.javac.Main']
sources = sorted((ROOT/'app/src/main/java').rglob('*.java'))
(work/'sources.txt').write_text('\n'.join(str(s) for s in sources))
run(*javac, '-encoding', 'UTF-8', '-g:none', '-source', '8', '-target', '8',
    '-bootclasspath', str(a.android_jar.resolve())+os.pathsep+str(a.build_tools.resolve()/'core-lambda-stubs.jar'),
    '-classpath', a.xposed_api.resolve(),
    '-d', work/'classes', '@'+str(work/'sources.txt'))

run(*javac, '-encoding', 'UTF-8', '-d', work/'tests',
    ROOT/'app/src/main/java/dev/codex/fastpass/OutcomePolicy.java',
    ROOT/'app/src/main/java/dev/codex/fastpass/OperatorRoute.java', ROOT/'tests/OutcomePolicyTest.java')
run('java', '-cp', work/'tests', 'dev.codex.fastpass.OutcomePolicyTest')

with zipfile.ZipFile(work/'classes.jar', 'w', zipfile.ZIP_DEFLATED) as z:
    for path in sorted((work/'classes').rglob('*.class')):
        z.write(path, path.relative_to(work/'classes'))
run('java', '-cp', a.build_tools.resolve()/'lib/d8.jar', 'com.android.tools.r8.D8',
    '--min-api', '33', '--lib', a.android_jar.resolve(), '--classpath', a.xposed_api.resolve(),
    '--output', work/'dex', work/'classes.jar')

run(a.build_tools.resolve()/'aapt2', 'compile', '--dir', ROOT/'app/src/main/res', '-o', work/'resources.zip')
manifest = ET.parse(ROOT/'app/src/main/AndroidManifest.xml')
manifest.getroot().set('package', 'dev.codex.fastpass')
manifest.write(work/'AndroidManifest.xml', encoding='utf-8', xml_declaration=True)
run(a.build_tools.resolve()/'aapt2', 'link', '-o', work/'unsigned.apk', '-I', a.android_jar.resolve(),
    '--manifest', work/'AndroidManifest.xml', '-A', ROOT/'app/src/main/assets', work/'resources.zip')
with zipfile.ZipFile(work/'unsigned.apk', 'a', zipfile.ZIP_DEFLATED) as z:
    for dex in sorted((work/'dex').glob('*.dex')): z.write(dex, dex.name)
run(a.build_tools.resolve()/'zipalign', '-f', '4', work/'unsigned.apk', work/'aligned.apk')
a.out.parent.mkdir(parents=True, exist_ok=True)
run('java', '-jar', a.build_tools.resolve()/'lib/apksigner.jar', 'sign', '--key', a.key.resolve(),
    '--cert', a.cert.resolve(), '--out', a.out.resolve(), work/'aligned.apk')
run('java', '-jar', a.build_tools.resolve()/'lib/apksigner.jar', 'verify', '--verbose', a.out.resolve())
print('Built:', a.out.resolve())
