# -*- coding: utf-8 -*-
"""§7-A 静态卡口:统计生产代码里"尚未外置的含中文字符串字面量"。

白名单 = 该行或上一行带 `// i18n: keep`(§1.3 R1–R11 的数据值/日志)。
硬闸门:`app/src/main/java/**/ui/**` 与 `app/src/main/java/com/github/tvbox/osc/player/ui/**` 必须为 0。
用法:python tools/i18n_gate.py [--all] [--detail]   (--all 打印全部非 ui 文件;--detail 逐条打印剩余字面量)
"""
import io, os, sys

sys.stdout.reconfigure(encoding='utf-8')
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from i18n_hardcoded_scan import scan_java, LOG_HINT

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ROOTS = ['app/src/main']
UI_MARKERS = ('/ui/', '/player/ui/')

RESULT = []
for base_rel in ROOTS:
    base = os.path.join(ROOT, base_rel)
    for dirpath, dirnames, filenames in os.walk(base):
        dirnames[:] = [d for d in dirnames if d not in ('build', '.gradle')]
        for fn in filenames:
            if not fn.endswith(('.kt', '.java')):
                continue
            p = os.path.join(dirpath, fn)
            rel = os.path.relpath(p, ROOT).replace('\\', '/')
            src = io.open(p, encoding='utf-8', errors='ignore').read()
            lines = src.split('\n')
            items = []
            for lineno, lit in scan_java(src):
                cur = lines[lineno - 1] if lineno - 1 < len(lines) else ''
                prev = lines[lineno - 2] if lineno - 2 >= 0 else ''
                if 'i18n: keep' in cur or 'i18n: keep' in prev:
                    continue
                if LOG_HINT.search(cur):
                    continue  # 日志文案不进 UI,不翻译(spec §7-A 只卡用户可见文案)
                items.append((lineno, lit))
            RESULT.append((rel, items))

ui_files = [(r, i) for r, i in RESULT if any(m in r for m in UI_MARKERS) and i]
other = [(r, i) for r, i in RESULT if not any(m in r for m in UI_MARKERS) and i]
ui_total = sum(len(i) for _, i in ui_files)
other_total = sum(len(i) for _, i in other)

print('=== ui 层(硬闸门,须 0) 剩余 %d 处 / %d 文件 ===' % (ui_total, len(ui_files)))
for r, i in sorted(ui_files, key=lambda kv: -len(kv[1])):
    print('%4d  %s' % (len(i), r))
print('\n=== 非 ui 层(逐条甄别) 剩余 %d 处 / %d 文件 ===' % (other_total, len(other)))
show_all = '--all' in sys.argv
for r, i in sorted(other, key=lambda kv: -len(kv[1]))[:None if show_all else 12]:
    print('%4d  %s' % (len(i), r))
if '--detail' in sys.argv:
    for grp, files in (('ui', ui_files), ('non-ui', other)):
        for r, i in sorted(files, key=lambda kv: -len(kv[1])):
            for lineno, lit in i:
                print('%s\t%s:%d\t%s' % (grp, r, lineno, lit))
print('\nTOTAL remaining = %d' % (ui_total + other_total))
sys.exit(1 if ui_total else 0)
