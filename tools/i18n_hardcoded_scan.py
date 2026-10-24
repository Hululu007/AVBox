# -*- coding: utf-8 -*-
"""统计 Java/Kotlin 源码中"含中文的字符串字面量"(排除注释),用于多语言适配工作量评估。"""
import os, re, sys, json
from collections import defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CJK = re.compile(r'[\u4e00-\u9fff\u3400-\u4dbf\uf900-\ufaff]')
# 近似判断"日志/调试"上下文
LOG_HINT = re.compile(r'(LOG\.|Log\.[dviwe]|printStackTrace|System\.out|DebugLog|logger\.|LogUtils)', re.I)


def scan_java(src):
    out = []
    i, n, line = 0, len(src), 1
    while i < n:
        c = src[i]
        if c == '\n':
            line += 1; i += 1; continue
        if c == '/' and i + 1 < n:
            if src[i + 1] == '/':
                j = src.find('\n', i)
                if j < 0: break
                i = j; continue
            if src[i + 1] == '*':
                j = src.find('*/', i + 2)
                if j < 0: break
                line += src.count('\n', i, j)
                i = j + 2; continue
        if c == '"':
            if src.startswith('"""', i):
                j = src.find('"""', i + 3)
                if j < 0: break
                lit = src[i + 3:j]
                if CJK.search(lit): out.append((line, lit.strip()[:70]))
                line += src.count('\n', i, j + 3)
                i = j + 3; continue
            j = i + 1; buf = []
            while j < n:
                if src[j] == '\\': j += 2; continue
                if src[j] == '"' or src[j] == '\n': break
                buf.append(src[j]); j += 1
            lit = ''.join(buf)
            if CJK.search(lit): out.append((line, lit[:70]))
            i = j + 1; continue
        i += 1
    return out


def line_text(src, lineno):
    lines = src.split('\n')
    return lines[lineno - 1] if 0 < lineno <= len(lines) else ''


def main():
    targets = {
        'app': os.path.join(ROOT, 'app/src'),
        'player': os.path.join(ROOT, 'player/src'),
        'quickjs': os.path.join(ROOT, 'quickjs/src'),
        'libs': os.path.join(ROOT, 'libs'),
        'pyramid': os.path.join(ROOT, 'pyramid/src'),
    }
    stats = defaultdict(lambda: {'total': 0, 'ui': 0, 'log': 0})
    ui_items = defaultdict(list)
    for mod, base in targets.items():
        for dirpath, dirnames, filenames in os.walk(base):
            dirnames[:] = [d for d in dirnames if d not in ('build', '.gradle')]
            for fn in filenames:
                if not fn.endswith(('.java', '.kt')):
                    continue
                p = os.path.join(dirpath, fn)
                rel = os.path.relpath(p, ROOT).replace('\\', '/')
                try:
                    src = open(p, encoding='utf-8', errors='ignore').read()
                except Exception:
                    continue
                items = scan_java(src)
                if not items:
                    continue
                for lineno, lit in items:
                    lt = line_text(src, lineno)
                    islog = bool(LOG_HINT.search(lt))
                    stats[rel]['total'] += 1
                    if islog:
                        stats[rel]['log'] += 1
                    else:
                        stats[rel]['ui'] += 1
                        if len(ui_items[rel]) < 28:
                            ui_items[rel].append((lineno, lit))
    # 汇总
    tot_all = sum(v['total'] for v in stats.values())
    tot_log = sum(v['log'] for v in stats.values())
    tot_ui = sum(v['ui'] for v in stats.values())
    print('=== 全局汇总 (含中文的字符串字面量, 已排除注释) ===')
    print('总计: %d | 疑似日志/调试: %d | 其余(UI/提示/数据): %d' % (tot_all, tot_log, tot_ui))
    print()
    print('=== 非日志类 Top 30 文件 ===')
    for rel, v in sorted(stats.items(), key=lambda kv: -kv[1]['ui'])[:30]:
        print('%5d  %s' % (v['ui'], rel))
    print()
    print('=== ui/ 目录明细(含中文的非日志字面量) ===')
    for rel, items in sorted(ui_items.items(), key=lambda kv: -stats[kv[0]]['ui']):
        if '/ui/' not in rel and 'Settings' not in rel and 'viewmodel' not in rel:
            continue
        print('--- %s (非日志 %d / 总 %d) ---' % (rel, stats[rel]['ui'], stats[rel]['total']))
        for lineno, lit in items:
            print('   L%-5d %s' % (lineno, lit))
    # 导出 JSON 便于复查（产物落 gitignore 目录，fresh clone 下需先建目录）
    out_dir = os.path.join(ROOT, '.codebuddy', 'tools')
    os.makedirs(out_dir, exist_ok=True)
    with open(os.path.join(out_dir, 'i18n_scan_out.json'), 'w', encoding='utf-8') as f:
        json.dump({'stats': {k: v for k, v in stats.items()}, 'ui_items': {k: v for k, v in ui_items.items()}},
                  f, ensure_ascii=False, indent=1)


if __name__ == '__main__':
    main()
