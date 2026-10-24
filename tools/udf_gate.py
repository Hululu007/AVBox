#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""单向数据流(UDF)计数闸门:阻断新增,存量按基线只减不增(refactor-plan-20261024 §3.3 / 片 0)。

口径(规则编号对齐该文档 §3.3):
  r1_eventbus      EventBus.getDefault().post(...) 与 @Subscribe 出现点(import 行不计;片 5 归零)
  r2_public_flow   ViewModel 持有者文件(*ViewModel.kt/*VM.kt 或内嵌 `class .. : ViewModel()`)中
                   public MutableStateFlow / mutableStateOf 暴露(要求 private + 只读投影);
                   `private` 修饰、`private set`、remember/rememberSaveable 局部状态不计;
                   PlayerUiState/MusicPlayerState 等状态容器不在判定域(项目认可形态)
  r3_ui_flow_write ui/ 包中对 *.value 的直写(= / += / -=),接收者为同文件自有属性时不计
                   (内嵌 VM 写自己的流不算违规;写别人(如 vm.editTarget.value)才计)
  r4_unused_expr   @Suppress("UNUSED_EXPRESSION")(revision hack 类;片 3 归零)
  r5_override_var  override var(Host 可变接口属性模式)
  r6_vodinfo_slot  App 全局单槽引用 = base/App.kt 内 setVodInfo/getVodInfo 全部出现 +
                   其他文件的 `getInstance()?.setVodInfo/getVodInfo` 调用形态;
                   同名的仓库方法(HistoryRepository.getVodInfo(key,id))不计(片 3 归零)
  r7_scheduler_set ui/ 包 scheduler.set*/mark* 调用(阻断项,2026-10-24 观察项升阻断;
  r8_public_sharedflow ViewModel 持有者文件中 public MutableSharedFlow 暴露(要求 private +
                   asSharedFlow 只读投影);与 r2 同族,2026-10-24 事件流收尾时补上
                   (r2 只数 StateFlow,公开 MutableSharedFlow 是其盲区)
                   基线锁死当前值,只允许下调,片 4 剩余批次推进到归零)

  - 扫描集 = `git ls-files "*.kt"` 限 app/src/main/;计数前剥离注释与字符串/字符字面量。
  - 基线只允许下调;`--update` 拒绝上调,首冻或人工确认后加 `--accept-new`。

用法:
  python tools/udf_gate.py             校验(默认;有违规 exit 1)
  python tools/udf_gate.py --detail    打印各规则逐文件计数
  python tools/udf_gate.py --update    下调基线(只允许降/删)
  python tools/udf_gate.py --update --accept-new    首冻,或人工确认后接受新增/上调
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
BASELINE = os.path.join(ROOT, 'tools', 'udf_gate_baseline.json')
MAIN_PREFIX = 'app/src/main/'
VM_NAME_RE = re.compile(r'(ViewModel|VM)\.kt$')


def _skip_literal(text, i, mask):
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


def kt_files():
    out = subprocess.run(
        ['git', '-C', ROOT, 'ls-files', '*.kt'], capture_output=True, text=True, encoding='utf-8',
    ).stdout
    files = []
    for raw in out.split('\n'):
        rel = raw.strip().replace('\\', '/')
        if rel.startswith(MAIN_PREFIX):
            files.append(rel)
    return files


def is_ui(rel):
    return '/osc/ui/' in rel


def is_ui_nonvm(rel):
    return is_ui(rel) and not VM_NAME_RE.search(os.path.basename(rel))


def line_bounds(text, pos):
    ls = text.rfind('\n', 0, pos) + 1
    le = text.find('\n', pos)
    if le < 0:
        le = len(text)
    return ls, le


RULES = [
    ('r1_eventbus', [
        re.compile(r'EventBus\s*\.\s*getDefault\s*\(\s*\)\s*\.\s*post\s*\('),
        re.compile(r'@\s*Subscribe\b'),
    ], lambda rel: True),
    ('r2_public_flow', None, lambda rel: True),
    ('r3_ui_flow_write', None, is_ui),
    ('r4_unused_expr', [
        re.compile(r'@\s*Suppress\s*\(\s*"UNUSED_EXPRESSION"'),
    ], lambda rel: True),
    ('r5_override_var', [
        re.compile(r'\boverride\s+var\b'),
    ], lambda rel: True),
    ('r6_vodinfo_slot', None, lambda rel: True),
    ('r7_scheduler_set', [
        re.compile(r'\bscheduler\s*\.\s*(?:set|mark)\w*\s*\('),
    ], is_ui),
    ('r8_public_sharedflow', None, lambda rel: True),
]
OBSERVE_RULES = set()
IMPORT_LINE = re.compile(r'^\s*import\s')
DECL_RE = re.compile(r'(?m)^([ \t]*)((?:[a-z]+\s+)*)(private\s+)?(val|var)\s+(\w+)')
FLOW_HINT = re.compile(r'MutableStateFlow|mutableStateOf|MutableState\b')
SHARED_HINT = re.compile(r'MutableSharedFlow')
REMEMBER_HINT = re.compile(r'\bremember(?:Saveable)?\s*[({]')
PRIVATE_SET = re.compile(r'private\s+set')
VM_CLASS_RE = re.compile(r'class\s+\w+\s*:\s*ViewModel\s*\(')
VALUE_WRITE_RE = re.compile(r'\b((?:[A-Za-z_]\w*\s*\.\s*)+?)value\s*(?:\+|-)?=(?!=)')
APP_SLOT_CALL_RE = re.compile(r'(?:App\s*\.\s*)?getInstance\s*\(\s*\)\s*\?\s*\.\s*(?:set|get)VodInfo\s*\(')
VODINFO_NAME_RE = re.compile(r'\b(?:set|get)VodInfo\s*\(')


def in_code(mask, m):
    return any(mask[p] for p in range(m.start(), m.end()))


def declared_names(text, mask):
    names = set()
    for m in DECL_RE.finditer(text):
        if mask[m.start()]:
            names.add(m.group(5))
    return names


def count_rule(rid, patterns, scope, rel, text, mask):
    if not scope(rel):
        return 0
    if rid in ('r2_public_flow', 'r8_public_sharedflow'):
        hint = SHARED_HINT if rid == 'r8_public_sharedflow' else FLOW_HINT
        if not (VM_NAME_RE.search(os.path.basename(rel)) or VM_CLASS_RE.search(text)):
            return 0
        total = 0
        for m in DECL_RE.finditer(text):
            if not mask[m.start()] or not in_code(mask, m):
                continue
            ls, le = line_bounds(text, m.start())
            line = text[ls:le]
            if not hint.search(line) or REMEMBER_HINT.search(line):
                continue
            if 'private' in m.group(2).split() or m.group(3):
                continue
            nxt_le = text.find('\n', le + 1)
            if nxt_le < 0:
                nxt_le = len(text)
            if PRIVATE_SET.search(text[m.start():nxt_le]):
                continue
            total += 1
        return total
    if rid == 'r3_ui_flow_write':
        names = declared_names(text, mask)
        total = 0
        for m in VALUE_WRITE_RE.finditer(text):
            if not in_code(mask, m):
                continue
            chain = re.sub(r'\s+', '', m.group(1)).rstrip('.')
            if chain.startswith('this.'):
                chain = chain[len('this.'):]
            if '.' not in chain and chain in names:
                continue
            total += 1
        return total
    if rid == 'r6_vodinfo_slot':
        if rel.endswith('base/App.kt'):
            return sum(1 for m in VODINFO_NAME_RE.finditer(text) if in_code(mask, m))
        return sum(1 for m in APP_SLOT_CALL_RE.finditer(text) if in_code(mask, m))
    total = 0
    for pat in patterns:
        for m in pat.finditer(text):
            if not in_code(mask, m):
                continue
            ls, le = line_bounds(text, m.start())
            if IMPORT_LINE.match(text[ls:le]):
                continue
            total += 1
    return total


def scan():
    counts = {rid: {} for rid, _, _ in RULES}
    files = kt_files()
    for rel in files:
        path = os.path.join(ROOT, rel)
        if not os.path.exists(path):
            continue
        with open(path, 'rb') as fh:
            text = fh.read().decode('utf-8', 'replace').replace('\r\n', '\n')
        mask = code_mask(text)
        for rid, patterns, scope in RULES:
            n = count_rule(rid, patterns, scope, rel, text, mask)
            if n:
                counts[rid][rel] = counts[rid].get(rel, 0) + n
    return counts, len(files)


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

    counts, scanned = scan()
    old = load_baseline()

    if update:
        refused = False
        new_data = {}
        for rid, _, _ in RULES:
            cur, prev = counts[rid], old.get(rid, {})
            grown = [(f, prev.get(f), c) for f, c in cur.items()
                     if prev.get(f) is None or c > prev.get(f)]
            if grown and not accept_new:
                refused = True
                print('=== 拒绝上调基线 %s (需人工确认后加 --accept-new) ===' % rid)
                for f, o, c in sorted(grown):
                    print('  %s  baseline=%s current=%d' % (f, '∅' if o is None else o, c))
            new_data[rid] = dict(sorted(cur.items()))
        if refused:
            print('\n基线未写入。')
            return 1
        for rid, _, _ in RULES:
            prev = old.get(rid, {})
            for f in sorted(set(prev) - set(counts[rid])):
                print('  移除 %s / %s: %d' % (rid, f, prev[f]))
            for f, c in sorted(counts[rid].items()):
                o = prev.get(f)
                if o is not None and c < o:
                    print('  下调 %s / %s: %d -> %d' % (rid, f, o, c))
            if not prev and counts[rid]:
                print('  首冻 %s: %d 文件 %d 处' % (rid, len(counts[rid]), sum(counts[rid].values())))
        save_baseline(new_data)
        print('基线已写入 %s' % BASELINE)
        return 0

    viol = []
    shrink = []
    for rid, _, _ in RULES:
        cur, prev = counts[rid], old.get(rid, {})
        for f, c in sorted(cur.items()):
            o = prev.get(f)
            if (o is None or c > o) and rid not in OBSERVE_RULES:
                viol.append((rid, f, o, c))
        for f, o in sorted(prev.items()):
            if cur.get(f, 0) < o:
                shrink.append((rid, f, o, cur.get(f, 0)))

    if detail:
        for rid, _, _ in RULES:
            if counts[rid]:
                print('=== %s (current / baseline) ===' % rid)
                for f, c in sorted(counts[rid].items(), key=lambda kv: -kv[1]):
                    o = old.get(rid, {}).get(f)
                    print('%5d  %5s  %s' % (c, '∅' if o is None else o, f))
        print()

    for rid, f, o, c in viol:
        print('FAIL %s  %s  baseline=%s current=%d' % (rid, f, '∅' if o is None else o, c))
    for rid, f, o, c in shrink:
        print('可下调 %s  %s  %d -> %d (收尾时跑 --update)' % (rid, f, o, c))

    parts = []
    for rid, _, _ in RULES:
        mark = ' [观察]' if rid in OBSERVE_RULES else ''
        parts.append('%s=%d%s' % (rid, sum(counts[rid].values()), mark))
    print('# scanned=%d baseline=%s' % (scanned, 'yes' if old else 'no'))
    print('# ' + '  '.join(parts))
    print('RESULT: ' + ('FAIL — %d 条超基线' % len(viol) if viol else 'PASS'))
    return 1 if viol else 0


if __name__ == '__main__':
    sys.exit(main())
