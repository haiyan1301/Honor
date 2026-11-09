#!/usr/bin/env python3
"""Audit architectural constraints: the module observes native auto requests, never builds/sends one."""
import argparse
import json
import re
from pathlib import Path

p = argparse.ArgumentParser()
p.add_argument('--out', type=Path)
a = p.parse_args()
root = Path(__file__).resolve().parent / 'app/src/main/java/dev/codex/fastpass'
files = {f.name: f.read_text() for f in root.glob('*.java')}
combined = '\n'.join(files.values())
backend = files['NativeBackend.java']
cloud = backend.split('private void installCloudHooks()', 1)[1].split('private JSONObject quotaSnapshot', 1)[0]
checks = [
    ('no reflective request construction', not re.search(r'getConstructor\s*\(|Constructor\s*<|\.newInstance\(plmn', combined)),
    ('no direct native submit from module', not re.search(r'call(?:Static)?Method\s*\([^,]+,\s*"requestDedicatedBearer"', combined)),
    ('no request setters', not any('"'+s+'"' in combined for s in ['setToken','setIpv6','setApplyId'])),
    ('no native field mutation', not re.search(r'XposedHelpers\.set(?:Object|Int|Boolean|Long)Field\s*\(', combined)),
    ('no replacement arguments', not re.search(r'\.args\[\d+\]\s*=(?!=)', combined)),
    ('no replacement cloud result', '.setResult(' not in cloud),
    ('no manual request command in UI', 'command("request"' not in files['MainActivity.java']),
    ('bridge accepts only mode/refresh controls', 'if (!"setAuto".equals(arg) && !"refresh".equals(arg))' in files['BridgeProvider.java']),
    ('no module bearer monitoring start/stop', 'reportDedicatedBearRequest' not in combined),
    ('no independent HTTP client', not re.search(r'new\s+URL\s*\(|\.openConnection\s*\(', combined)),
    ('native provenance captured', 'autoHooks.originFor(p.args[0])' in backend and 'constructDBRequestInfo' in files['NativeAutoHooks.java']),
    ('native callback retained', 'tagNativeCallback' in backend and 'handleDbCloudDone' in backend and 'callback.obtainMessage' not in backend),
]
report = [{'check': name, 'ok': ok} for name, ok in checks]
if a.out:
    a.out.parent.mkdir(parents=True, exist_ok=True)
    a.out.write_text(json.dumps(report, indent=2)+'\n')
bad = [r for r in report if not r['ok']]
if bad:
    print(json.dumps(bad, indent=2)); raise SystemExit(1)
print('PASS:', len(report), 'native-auto architecture checks')
