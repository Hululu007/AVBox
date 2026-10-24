# -*- coding: utf-8 -*-
"""列出指定文件里的含中文字符串字面量(状态机剥离注释),用于逐文件外置与复查。

用法: python tools/i18n_file.py <file> [file...]
"""
import sys, os, io

sys.stdout.reconfigure(encoding='utf-8')
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from i18n_hardcoded_scan import scan_java


def main():
    paths = sys.argv[1:]
    if not paths:
        print(__doc__)
        return
    total = 0
    for path in paths:
        if os.path.isdir(path):
            continue
        src = io.open(path, encoding='utf-8', errors='ignore').read()
        items = scan_java(src)
        lines = src.split('\n')
        total += len(items)
        print('=== %s (%d) ===' % (os.path.relpath(path).replace('\\', '/'), len(items)))
        for lineno, lit in items:
            line = lines[lineno - 1].strip() if lineno - 1 < len(lines) else ''
            print('L%-5d %s' % (lineno, line[:200]))
    print('TOTAL %d' % total)


if __name__ == '__main__':
    main()
