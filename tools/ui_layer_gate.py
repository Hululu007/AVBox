#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""ui 层穿透闸门:禁止 Composable 直连下层,存量按基线只减不增。

口径:
  - 扫描集 = `app/src/main/java/com/github/tvbox/osc/ui/` 下的 `.kt`(含未跟踪文件,便于提交前拦截)。
  - 判定对象 = **文件内含 `@Composable`** 的文件。理由是语义明确:Composable 是纯渲染,
    不应发起网络/读写数据库/读全局配置单例。
  - 违规 = 该文件出现 `import com.github.tvbox.osc.{api,base,data,sourcedata,net}.*`。
  - 符号白名单(不算违规):
      * `*ViewModel`  —— Composable 持有 ViewModel 引用是 MVVM 的常规形态;
      * `AppBootstrap` —— presentation 的全局启动状态,包归属待定,暂放行。
    其余符号(Repository / ApiConfig / Tmdb* / WatchProgressStore / OkGoHelper …)一律计入。
  - ViewModel / Activity / Controller 等非 Composable 文件不在判定范围内:
    它们依赖下层是合理方向,不属于本闸门治理对象。

用法:
  python tools/ui_layer_gate.py            校验(默认;有新增/增长 exit 1)
  python tools/ui_layer_gate.py --detail   校验并打印当前全部违规文件
  python tools/ui_layer_gate.py --update   下调基线(只允许降/删)
  python tools/ui_layer_gate.py --update --accept-new   首冻,或人工确认后接受新增
"""
import json
import os
import re
import subprocess
import sys

try:
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BASELINE = os.path.join(ROOT, 'tools', 'ui_layer_baseline.json')
UI_ROOT = 'app/src/main/java/com/github/tvbox/osc/ui/'
PKG_PREFIX = 'com.github.tvbox.osc.'
FORBIDDEN_PACKAGES = ('api', 'base', 'data', 'sourcedata', 'net')
ALLOW_SYMBOLS = ('AppBootstrap',)

IMPORT_RE = re.compile(r'^import\s+(?:static\s+)?(' + re.escape(PKG_PREFIX)
                       + r'(?:api|base|data|sourcedata|net)\.[\w.]+)\s*$')
COMPOSABLE_RE = re.compile(r'@Composable\b')


def ui_files():
    out = subprocess.run(
        ['git', '-C', ROOT, 'ls-files', '--cached', '--others', '--exclude-standard', UI_ROOT + '*.kt'],
        capture_output=True, text=True, encoding='utf-8',
    ).stdout
    files = []
    for raw in out.split('\n'):
        rel = raw.strip().replace('\\', '/')
        if not rel or not rel.endswith('.kt'):
            continue
        if os.path.basename(rel).startswith('.'):
            continue
        files.append(rel)
    return sorted(set(files))


def allowed(symbol):
    return symbol.endswith('ViewModel') or symbol in ALLOW_SYMBOLS


def violations(rel):
    path = os.path.join(ROOT, rel)
    try:
        with open(path, encoding='utf-8') as fh:
            lines = fh.read().splitlines()
    except OSError:
        return []
    if not any(COMPOSABLE_RE.search(line) for line in lines):
        return []
    hits = []
    for line in lines:
        m = IMPORT_RE.match(line)
        if not m:
            continue
        symbol = m.group(1).rsplit('.', 1)[-1]
        if allowed(symbol):
            continue
        hits.append(m.group(1))
    return hits


def scan():
    counts = {}
    total = 0
    for rel in ui_files():
        hits = violations(rel)
        if hits:
            counts[rel] = len(hits)
            total += len(hits)
    return counts, total


def load_baseline():
    if not os.path.exists(BASELINE):
        return {}
    with open(BASELINE, encoding='utf-8') as fh:
        return json.load(fh)


def save_baseline(data):
    with open(BASELINE, 'w', encoding='utf-8', newline='\n') as fh:
        json.dump(data, fh, ensure_ascii=False, indent=2, sort_keys=True)
        fh.write('\n')


def main():
    argv = sys.argv[1:]
    detail = '--detail' in argv
    update = '--update' in argv
    accept_new = '--accept-new' in argv

    counts, total = scan()
    old = load_baseline()
    files = len(ui_files())

    if update:
        grown = [(f, old.get(f), c) for f, c in counts.items() if old.get(f) is None or c > old.get(f)]
        if grown and not accept_new:
            print('=== 拒绝上调基线 (需人工确认后加 --accept-new) ===')
            for f, o, c in sorted(grown):
                print('  %s  baseline=%s current=%d' % (f, '∅' if o is None else o, c))
            print('\n基线未写入。')
            return 1
        for f in sorted(f for f in old if f not in counts):
            print('  移除基线条目(已归零) %s: %d' % (f, old[f]))
        for f, c in sorted(counts.items()):
            o = old.get(f)
            if o is not None and c < o:
                print('  下调 %s: %d -> %d' % (f, o, c))
        if grown:
            print('=== 已接受新增/上调 (留痕) ===')
            for f, o, c in sorted(grown):
                print('  %s  baseline=%s current=%d' % (f, '∅' if o is None else o, c))
        save_baseline(dict(sorted(counts.items())))
        print('基线已写入 %s\n  baseline_files=%d hits=%d' % (BASELINE, len(counts), total))
        return 0

    viol = []
    for f, c in sorted(counts.items(), key=lambda kv: -kv[1]):
        o = old.get(f)
        if o is None or c > o:
            viol.append((f, o, c))
    shrinkable = [(f, o, counts.get(f, 0)) for f, o in sorted(old.items()) if counts.get(f, 0) < o]

    if detail:
        print('=== 判定域违规文件 (current / baseline) ===')
        for f, c in sorted(counts.items(), key=lambda kv: -kv[1]):
            print('%3d  %4s  %s' % (c, '∅' if old.get(f) is None else old[f], f))

    if viol:
        print('\n=== FAIL: 新增或增长 ===')
        for f, o, c in viol:
            print('  %s  baseline=%s current=%d (+%d)' % (f, '∅' if o is None else o, c, c - (o or 0)))

    if shrinkable:
        print('\n=== 基线可下调 (收尾时跑 --update) ===')
        for f, o, c in sorted(shrinkable, key=lambda kv: -(kv[1] - kv[2])):
            print('  %s  %d -> %d' % (f, o, c))

    print('\n# scanned=%d violated_files=%d hits=%d baseline_files=%d'
          % (files, len(counts), total, len(old)))
    print('RESULT: ' + ('FAIL — %d 个文件超基线' % len(viol) if viol else 'PASS'))
    return 1 if viol else 0


if __name__ == '__main__':
    sys.exit(main())
