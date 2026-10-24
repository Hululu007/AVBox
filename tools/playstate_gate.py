# -*- coding: utf-8 -*-
"""播放状态面统一(refactor-plan-20261007 步 4)静态卡口:生产代码里旧 int 面与旧监听 API 必须零残留。

禁用模式(app/src/main/**/*.kt|java):
  AppPlayerView.STATE_   旧 int 状态常量(media3 的 Player.STATE_* / PlaybackStateCompat.STATE_* 不算)
  currentPlayState       旧 int 读口
  curPlayState           控制器 int 缓存副本
  fromLegacy / toLegacy  迁移期映射(已删,映射只留测试)
  OnStateChangeListener  旧监听 API

用法:python tools/playstate_gate.py [--detail]
"""
import io, os, re, sys

sys.stdout.reconfigure(encoding='utf-8')

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
BASE = os.path.join(ROOT, 'app', 'src', 'main')

PATTERNS = [
    ('AppPlayerView.STATE_', re.compile(r'AppPlayerView\.STATE_')),
    ('currentPlayState', re.compile(r'currentPlayState')),
    ('curPlayState', re.compile(r'curPlayState')),
    ('fromLegacy/toLegacy', re.compile(r'fromLegacy|toLegacy')),
    ('OnStateChangeListener', re.compile(r'OnStateChangeListener')),
]

hits = []
for dirpath, dirnames, filenames in os.walk(BASE):
    dirnames[:] = [d for d in dirnames if d not in ('build', '.gradle')]
    for fn in filenames:
        if not fn.endswith(('.kt', '.java')):
            continue
        p = os.path.join(dirpath, fn)
        rel = os.path.relpath(p, ROOT).replace('\\', '/')
        src = io.open(p, encoding='utf-8', errors='ignore').read()
        for lineno, line in enumerate(src.split('\n'), 1):
            for name, rx in PATTERNS:
                if rx.search(line):
                    hits.append((name, rel, lineno, line.strip()))

print('=== 旧面残留 %d 处 ===' % len(hits))
by_file = {}
for name, rel, lineno, text in hits:
    by_file.setdefault(rel, []).append((name, lineno, text))
for rel in sorted(by_file):
    print('%4d  %s' % (len(by_file[rel]), rel))
if '--detail' in sys.argv:
    for rel in sorted(by_file):
        for name, lineno, text in by_file[rel]:
            print('%s\t%s:%d\t%s' % (name, rel, lineno, text))
print('\nTOTAL remaining = %d' % len(hits))
sys.exit(1 if hits else 0)
