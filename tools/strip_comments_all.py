#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""全量删注释(只删注释,代码一个字符不动)。

用法:
  python tools/strip_comments_all.py --dry      仅报告(保留清单 + 统计)
  python tools/strip_comments_all.py --apply    写回
  python tools/strip_comments_all.py --verify   与 git HEAD 做"代码投影"逐行比对

硬约束:
  - 字符串/字符字面量/raw string(三引号)/${} 模板内的 `//` `/*` 不是注释
  - 许可证块(含 Copyright / Permission is hereby granted / SPDX-License)整块保留
  - 机器可读指令(`// i18n: keep` / `// null-assert: keep` / `//noinspection`)保留
  - 每个文件保留原有换行风格(CRLF/LF)
  - 只含注释的行整行删除(含缩进); 连续空行压成 1 行(字符串内不动)
  - `#` 类文件只在行首(允许前置空白)剥离 —— 实测本项目无非行首 `#`
"""
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]

GLOBS = [
    'app/src/main/**/*.kt',
    'app/src/main/**/*.java',
    'app/src/python/**/*.kt',
    'app/src/test/**/*.kt',
    'app/src/test/**/*.java',
    'app/src/main/**/*.xml',
    'app/src/test/**/*.xml',
    'app/build.gradle.kts',
    'build.gradle.kts',
    'settings.gradle.kts',
    'app/proguard-rules.pro',
    'app/proguard-python.pro',
    'gradle.properties',
    'gradle/libs.versions.toml',
]

XML_LIKE = {'.xml'}
HASH_LIKE = {'.pro', '.properties', '.toml'}

# 保留的机器可读指令
KEEP_LINE_RE = re.compile(r'i18n:\s*keep|null-assert:\s*keep|noinspection|@formatter:(?:off|on)|^//\s*(?:region|endregion)\b')
# 保留的许可证声明
KEEP_BLOCK_RE = re.compile(r'(?i)copyright|permission is hereby granted|spdx-license|i18n:\s*keep|null-assert:\s*keep')


class Lines:
    """按行收集输出字符,并记录每行是否含代码/注释/字符串。"""

    def __init__(self):
        self.chars = [[]]
        self.flags = [{'code': False, 'comment': False, 'str': False}]
        self.preserved = []
        self.dropped = 0

    @property
    def cur(self):
        return self.chars[-1]

    @property
    def flag(self):
        return self.flags[-1]

    def newline(self):
        self.chars.append([])
        self.flags.append({'code': False, 'comment': False, 'str': False})

    def add(self, ch, code=True, is_str=False):
        self.cur.append(ch)
        if code and not ch.isspace():
            self.flag['code'] = True
        if is_str:
            self.flag['str'] = True

    def raw(self, s, code=True, comment=False, is_str=False):
        """s 可含换行。"""
        for k, part in enumerate(s.split('\n')):
            if k:
                self.newline()
            if part:
                self.cur.append(part)
                if code and not part.isspace():
                    self.flag['code'] = True
                if is_str:
                    self.flag['str'] = True
            if comment:
                self.flag['comment'] = True

    def keep(self, body, reason):
        """保留整段注释(许可证/指令)。"""
        self.preserved.append((reason, body.strip().split('\n')[0][:110]))
        self.raw(body)

    def trim_ws(self):
        while self.cur:
            last = self.cur[-1]
            if not last:
                self.cur.pop()
                continue
            stripped = last.rstrip(' \t')
            if stripped == last:
                break
            self.cur[-1] = stripped
            if not stripped:
                self.cur.pop()


def scan_string(src, i, lb):
    lb.add('"', is_str=True)
    j, n = i + 1, len(src)
    while j < n:
        ch = src[j]
        if ch == '\\':
            lb.raw(src[j:j + 2], is_str=True)
            j += 2
            continue
        if ch == '$' and j + 1 < n and src[j + 1] == '{':
            lb.raw('${', is_str=True)
            j = scan_template(src, j + 2, lb)
            continue
        lb.add(ch, is_str=True)
        j += 1
        if ch == '"' or ch == '\n':
            return j
    return j


def scan_template(src, j, lb):
    depth, n = 1, len(src)
    while j < n:
        ch = src[j]
        if src.startswith('"""', j):
            j = scan_raw(src, j, lb)
        elif ch == '"':
            j = scan_string(src, j, lb)
        elif ch == "'":
            j = scan_char(src, j, lb)
        elif ch == '{':
            depth += 1
            lb.add(ch, is_str=True)
            j += 1
        elif ch == '}':
            depth -= 1
            lb.add(ch, is_str=True)
            j += 1
            if depth == 0:
                return j
        else:
            lb.add(ch, is_str=True)
            j += 1
    return j


def scan_raw(src, i, lb):
    lb.raw('"""', is_str=True)
    j, n = i + 3, len(src)
    while j < n:
        if src.startswith('"""', j):
            lb.raw('"""', is_str=True)
            return j + 3
        lb.add(src[j], is_str=True)
        j += 1
    return j


def scan_char(src, i, lb):
    lb.add("'", is_str=True)
    j, n = i + 1, len(src)
    while j < n:
        ch = src[j]
        if ch == '\\':
            lb.raw(src[j:j + 2], is_str=True)
            j += 2
            continue
        lb.add(ch, is_str=True)
        j += 1
        if ch == "'" or ch == '\n':
            return j
    return j


def _drop_comment(lb, src, start, end):
    """删掉 src[start:end] 这段注释:正文一律不落地,只按内部换行推进行号。

    被注释覆盖到的每一行都标 comment=True —— 若该行没有代码,整行丢弃。
    """
    lb.flag['comment'] = True
    nl = src.find('\n', end)
    if nl < 0:
        nl = len(src)
    nlines = src[start:end].count('\n')
    if src[end:nl].strip() == '':
        lb.trim_ws()
        for _ in range(nlines):
            lb.newline()
            lb.flag['comment'] = True
        k = end
        while k < nl and src[k] in ' \t':
            k += 1
        return k
    for _ in range(nlines):
        lb.newline()
        lb.flag['comment'] = True
    if lb.cur and lb.cur[-1] and lb.cur[-1][-1] in ' \t' and end < len(src) and src[end] in ' \t':
        return end + 1
    return end


def strip_c_like(src, lb):
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if c == '/' and src.startswith('//', i):
            j = src.find('\n', i)
            if j < 0:
                j = n
            body = src[i:j]
            if KEEP_LINE_RE.search(body):
                lb.keep(body, 'directive')
            else:
                lb.flag['comment'] = True
                lb.trim_ws()
            i = j
            continue
        if c == '/' and src.startswith('/*', i):
            j = src.find('*/', i + 2)
            end = n if j < 0 else j + 2
            body = src[i:end]
            if KEEP_BLOCK_RE.search(body):
                lb.keep(body, 'license')
                i = end
                continue
            i = _drop_comment(lb, src, i, end)
            continue
        if src.startswith('"""', i):
            i = scan_raw(src, i, lb)
            continue
        if c == '"':
            i = scan_string(src, i, lb)
            continue
        if c == "'":
            i = scan_char(src, i, lb)
            continue
        if c == '\n':
            lb.newline()
        else:
            lb.add(c)
        i += 1


def strip_xml(src, lb):
    i, n = 0, len(src)
    while i < n:
        if src.startswith('<!--', i):
            j = src.find('-->', i + 4)
            end = n if j < 0 else j + 3
            body = src[i:end]
            if KEEP_BLOCK_RE.search(body):
                lb.keep(body, 'license')
                i = end
                continue
            i = _drop_comment(lb, src, i, end)
            continue
        if src[i] == '\n':
            lb.newline()
        else:
            lb.add(src[i])
        i += 1


def strip_hash(src, lb):
    """行首(允许前置空白)`#` 起至行尾为注释。"""
    for line in src.split('\n'):
        s = line.lstrip(' \t')
        if s.startswith('#'):
            lb.flag['comment'] = True
            lb.trim_ws()
            body = s.rstrip()
            if KEEP_LINE_RE.search(body):
                lb.keep(body, 'directive')
        elif line:
            lb.raw(line)
        lb.newline()


def assemble(lb, newline, had_trailing_newline):
    recs = []
    for chars, flag in zip(lb.chars, lb.flags):
        if flag['comment'] and not flag['code']:
            lb.dropped += 1
            continue
        recs.append((''.join(chars), flag))
    out = []
    prev_blank = False
    for text, flag in recs:
        if flag['str']:
            out.append(text)
            prev_blank = False
            continue
        if not text.strip():
            if prev_blank:
                continue
            out.append('')
            prev_blank = True
            continue
        out.append(text.rstrip())
        prev_blank = False
    while out and out[-1] == '':
        out.pop()
    body = newline.join(out)
    if out and had_trailing_newline:
        body += newline
    return body


def read_source(path):
    """二进制读入,保留 BOM 与行尾风格(不经 Python 文本模式的换行转换)。"""
    raw = path.read_bytes()
    bom = raw.startswith(b'\xef\xbb\xbf')
    if bom:
        raw = raw[3:]
    text = raw.decode('utf-8')
    crlf = text.count('\r\n')
    lf_only = text.count('\n') - crlf
    newline = '\r\n' if (crlf and not lf_only) else '\n'
    return text.replace('\r\n', '\n'), newline, bom


def write_source(path, text, newline, bom):
    if newline == '\r\n':
        text = text.replace('\n', '\r\n')
    raw = text.encode('utf-8')
    path.write_bytes((b'\xef\xbb\xbf' if bom else b'') + raw)


def process(text, suffix):
    """text 须为 \\n 归一化后的文本; 返回 (新文本, Lines)。"""
    lb = Lines()
    if suffix in XML_LIKE:
        strip_xml(text, lb)
    elif suffix in HASH_LIKE:
        strip_hash(text, lb)
    else:
        strip_c_like(text, lb)
    return assemble(lb, '\n', text.endswith('\n')), lb


def targets():
    seen = []
    for pattern in GLOBS:
        for p in sorted(ROOT.glob(pattern)):
            if p.is_file() and p not in seen:
                seen.append(p)
    return seen


def projection(raw_text, suffix):
    stripped, _ = process(raw_text.replace('\r\n', '\n'), suffix)
    return [l.rstrip() for l in stripped.split('\n') if l.strip()]


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else '--dry'
    files = targets()
    print('目标文件 %d 个' % len(files))

    if mode == '--verify':
        bad = []
        for p in files:
            rel = p.relative_to(ROOT).as_posix()
            try:
                head = subprocess.run(['git', '-C', str(ROOT), 'show', 'HEAD:' + rel], capture_output=True, check=True)
                old = head.stdout.decode('utf-8')
            except subprocess.CalledProcessError:
                print('  (HEAD 无此文件,跳过) %s' % rel)
                continue
            new = p.read_bytes().decode('utf-8')
            a, b = projection(old, p.suffix), projection(new, p.suffix)
            if a != b:
                bad.append(rel)
                print('!!! 代码投影不一致 %s  (基线 %d 行 / 现 %d 行)' % (rel, len(a), len(b)))
                shown = 0
                for idx in range(max(len(a), len(b))):
                    la = a[idx] if idx < len(a) else '<无>'
                    lb_ = b[idx] if idx < len(b) else '<无>'
                    if la != lb_:
                        print('    第 %d 行\n      基线: %s\n      现在: %s' % (idx + 1, la, lb_))
                        shown += 1
                        if shown >= 5:
                            print('    ...(只列前 5 处)')
                            break
        print('\nRESULT: ' + ('PASS — 只动了注释' if not bad else 'FAIL — %d 个文件代码有变化' % len(bad)))
        return 0 if not bad else 1

    total_old = total_new = 0
    preserved = []
    written = 0
    for p in files:
        text, newline, bom = read_source(p)
        new, lb = process(text, p.suffix)
        total_old += len(text.split('\n'))
        total_new += len(new.split('\n'))
        for reason, head in lb.preserved:
            preserved.append((reason, p.as_posix(), head))
        if not lb.dropped and not lb.preserved:
            continue
        if new == text:
            continue
        if mode == '--apply':
            write_source(p, new, newline, bom)
        written += 1
    print('总行数 %d → %d  (删 %d 行)' % (total_old, total_new, total_old - total_new))
    print('需要改动/已改动的文件 %d 个' % written)
    print('\n保留的注释块 %d 段:' % len(preserved))
    for reason, f, head in preserved:
        print('  [%s] %s\n      %s' % (reason, f, head))
    return 0


if __name__ == '__main__':
    sys.exit(main())
