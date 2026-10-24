#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""非空断言(`!!`)计数闸门:阻断新增,存量按基线只减不增。

口径:
  - 扫描集 = `git ls-files "*.kt"`(含 app/src/main、app/src/test、Chaquopy sourceSet、
    libs/backdrop、quickjs);天然排除构建产物与未跟踪文件。
  - 判定域 = 扫描集去掉 `app/src/test/`(L6)与 `libs/backdrop/`、`quickjs/`(L5 vendored);
    两者都不计入 enforced,只打印。
  - 计数前剥离注释与字符串/字符字面量;字符串模板 `${}` 内部属代码,照常计入。
  - 行级豁免:该行或上一行含 `// null-assert: keep` 的行不计入(机制对齐 `// i18n: keep`)。
  - L5 豁免:`libs/backdrop/`、`quickjs/`(vendored)不参与基线比较,永远不计入 enforced。

用法:
  python tools/null_assert_gate.py            校验(默认;有违规 exit 1)
  python tools/null_assert_gate.py --detail   校验并打印判定域全部有断言的文件
  python tools/null_assert_gate.py --update   下调基线(只允许降/删)
  python tools/null_assert_gate.py --update --accept-new   首冻,或人工确认后接受新增/上调
  python tools/null_assert_gate.py --raw      按字面量计数(不剥离,供方案稿附录 B 口径复核)
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
BASELINE = os.path.join(ROOT, 'tools', 'null_assert_baseline.json')
EXCLUDE_PREFIXES = ('示例文件/',)
TEST_PREFIX = 'app/src/test/'
VENDORED_PREFIXES = ('libs/backdrop/', 'quickjs/')
KEEP_RE = re.compile(r'//\s*null-assert:\s*keep')


def kt_files():
    out = subprocess.run(
        ['git', '-C', ROOT, 'ls-files', '*.kt'], capture_output=True, text=True, encoding='utf-8',
    ).stdout
    files = []
    for raw in out.split('\n'):
        rel = raw.strip().replace('\\', '/')
        if not rel or rel.startswith(EXCLUDE_PREFIXES):
            continue
        files.append(rel)
    return files


def domain(rel):
    if rel.startswith(TEST_PREFIX):
        return 'test'
    if rel.startswith(VENDORED_PREFIXES):
        return 'vendored'
    return 'main'


def _skip_literal(text, i, mask):
    """跳过字符串/字符字面量(含 raw string),仅把模板 `${}` 内部标为代码。"""
    n = len(text)
    if text.startswith('"""', i):
        j = i + 3
        while j < n:
            if text.startswith('"""', j):
                return j + 3
            if text.startswith('${', j):
                j = _mark_template(text, j + 2, mask)
                continue
            j += 1
        return j
    quote, j = text[i], i + 1
    while j < n:
        ch = text[j]
        if ch == '\\':
            j += 2
            continue
        if ch == '\n':
            return j
        if text.startswith('${', j):
            j = _mark_template(text, j + 2, mask)
            continue
        if ch == quote:
            return j + 1
        j += 1
    return j


def _mark_template(text, i, mask):
    """i 紧跟 `${`;返回配对 `}` 之后的位置,区间内按代码标记。"""
    depth, j, n = 1, i, len(text)
    while j < n:
        if text.startswith('//', j):
            k = text.find('\n', j)
            j = n if k < 0 else k
            continue
        if text.startswith('/*', j):
            k = text.find('*/', j + 2)
            j = n if k < 0 else k + 2
            continue
        if text.startswith('"""', j) or text[j] in '"\'':
            j = _skip_literal(text, j, mask)
            continue
        ch = text[j]
        if ch == '{':
            depth += 1
        elif ch == '}':
            depth -= 1
            if depth == 0:
                return j + 1
        mask[j] = True
        j += 1
    return j


def code_mask(text):
    """返回与 text 等长的布尔表:该字符是否属于代码(含模板 `${}` 内部)。"""
    mask = [False] * len(text)
    i, n = 0, len(text)
    while i < n:
        if text.startswith('//', i):
            k = text.find('\n', i)
            i = n if k < 0 else k
            continue
        if text.startswith('/*', i):
            k = text.find('*/', i + 2)
            i = n if k < 0 else k + 2
            continue
        if text.startswith('"""', i) or text[i] in '"\'':
            i = _skip_literal(text, i, mask)
            continue
        mask[i] = True
        i += 1
    return mask


def read_text(rel):
    with open(os.path.join(ROOT, rel), 'rb') as fh:
        raw = fh.read()
    return raw.decode('utf-8', 'replace').replace('\r\n', '\n')


def count_file(rel):
    """返回 (计入的 !! 数, 被豁免标记排除的 !! 数, 有豁免标记的行号列表, 字面量总数)。"""
    text = read_text(rel)
    mask = code_mask(text)
    lines = text.split('\n')
    exempt = set()
    marks = []
    for no, ln in enumerate(lines, 1):
        if KEEP_RE.search(ln):
            marks.append(no)
            exempt.add(no)
            exempt.add(no + 1)
    total = skipped = 0
    line, i, n = 1, 0, len(text)
    while i < n - 1:
        ch = text[i]
        if ch == '\n':
            line += 1
            i += 1
            continue
        if ch == '!' and text[i + 1] == '!' and mask[i] and mask[i + 1]:
            if line in exempt:
                skipped += 1
            else:
                total += 1
            i += 2
            continue
        i += 1
    return total, skipped, marks, text.count('!!')


def scan():
    counts = {'main': {}, 'test': {}, 'vendored': {}}
    marks_all = []
    skipped = {'main': 0, 'test': 0, 'vendored': 0}
    literal = {'main': 0, 'test': 0, 'vendored': 0}
    for rel in kt_files():
        if not os.path.exists(os.path.join(ROOT, rel)):
            continue
        dom = domain(rel)
        total, skip, marks, lit = count_file(rel)
        literal[dom] += lit
        skipped[dom] += skip
        for no in marks:
            marks_all.append('%s:%d' % (rel, no))
        if total:
            counts[dom][rel] = total
    return counts, marks_all, skipped, literal


def load_baseline():
    if not os.path.exists(BASELINE):
        return {}
    with open(BASELINE, 'r', encoding='utf-8') as fh:
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
    raw_mode = '--raw' in argv

    counts, marks_all, skipped, literal = scan()
    enforced = dict(counts['main'])
    hits = sum(enforced.values())
    l5_hits = sum(counts['vendored'].values())
    l5_files = len(counts['vendored'])
    l6_hits = sum(counts['test'].values())
    l6_files = len(counts['test'])
    scanned = len(kt_files())

    if raw_mode:
        print('字面量口径(不剥离注释/字符串, 对齐方案稿附录 B)')
        print('  判定域 literal=%d  剥离后=%d  (噪音=%d)'
              % (literal['main'], hits, literal['main'] - hits))
        print('  L5(vendored) literal=%d  剥离后=%d' % (literal['vendored'], l5_hits))
        print('  L6(src/test) literal=%d  剥离后=%d' % (literal['test'], l6_hits))
        print('  合计 literal=%d  剥离后=%d'
              % (sum(literal.values()), hits + l5_hits + l6_hits))
        return 0

    old = load_baseline()

    if update:
        grown = [(f, old.get(f), c) for f, c in enforced.items()
                 if old.get(f) is None or c > old.get(f)]
        dropped = [f for f in old if f not in enforced]
        if grown and not accept_new:
            print('=== 拒绝上调基线 (需人工确认后加 --accept-new) ===')
            for f, o, c in sorted(grown, key=lambda kv: -(kv[2] - (kv[1] or 0))):
                print('  %s  baseline=%s current=%d' % (f, '∅' if o is None else o, c))
            print('\n基线未写入。')
            return 1
        for f in sorted(dropped):
            print('  移除基线条目(L6 豁免域或已归零) %s: %d' % (f, old[f]))
        for f, c in sorted(enforced.items()):
            o = old.get(f)
            if o is not None and c < o:
                print('  下调 %s: %d -> %d' % (f, o, c))
        if grown:
            print('=== 已接受新增/上调 (留痕) ===')
            for f, o, c in sorted(grown):
                print('  %s  baseline=%s current=%d' % (f, '∅' if o is None else o, c))
        save_baseline(dict(sorted(enforced.items())))
        print('基线已写入 %s\n  baseline_files=%d hits=%d' % (BASELINE, len(enforced), hits))
        return 0

    viol = []
    for f, c in sorted(enforced.items(), key=lambda kv: -kv[1]):
        o = old.get(f)
        if o is None or c > o:
            viol.append((f, o, c))
    shrinkable = [(f, o, enforced.get(f, 0)) for f, o in sorted(old.items())
                  if enforced.get(f, 0) < o]

    if detail and enforced:
        print('=== 判定域有断言的文件 (current / baseline) ===')
        for f, c in sorted(enforced.items(), key=lambda kv: -kv[1]):
            print('%5d  %5s  %s' % (c, '∅' if old.get(f) is None else old[f], f))

    if viol:
        print('\n=== FAIL: 新增或增长 ===')
        for f, o, c in viol:
            print('  %s  baseline=%s current=%d (+%d)' % (f, '∅' if o is None else o, c, c - (o or 0)))

    if shrinkable:
        print('\n=== 基线可下调 (收尾时跑 --update) ===')
        for f, o, c in sorted(shrinkable, key=lambda kv: -(kv[1] - kv[2])):
            print('  %s  %d -> %d' % (f, o, c))

    print('\n豁免标记 `// null-assert: keep`: %d 处, 排除断言 %d 次' % (len(marks_all), sum(skipped.values())))
    for m in marks_all:
        print('  %s' % m)

    print('\n# scanned=%d hits=%d baseline_files=%d l5_files=%d l5_hits=%d l6_files=%d l6_hits=%d exempt_marks=%d exempt_hits=%d'
          % (scanned, hits, len(old), l5_files, l5_hits, l6_files, l6_hits, len(marks_all), sum(skipped.values())))
    print('RESULT: ' + ('FAIL — %d 个文件超基线' % len(viol) if viol else 'PASS'))
    return 1 if viol else 0


if __name__ == '__main__':
    sys.exit(main())
