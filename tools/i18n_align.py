# -*- coding: utf-8 -*-
"""第 2/3/4 步校验:values-<lang> 与 values 的 key 集合、占位符、空值、CJK 残留。

用法:
    python tools/i18n_align.py en                 # values-en(第 2 步)
    python tools/i18n_align.py b+zh+Hant          # values-b+zh+Hant(第 3 步,全量层)
    python tools/i18n_align.py zh-rHK --subset    # values-zh-rHK(第 4 步,地区差异层)

口径:
- key 集合必须与 values 完全相等(无多无少);`--subset` = 差异层(只允许少、禁止多,缺失条目回落上级层);
- 占位符(含 %% 与是否带位置)逐条一致,防止多语言重排错位;
- 语言名条目保留各自语言的自称(与系统语言选择器一致)⇒ CJK 白名单;
- 允许英文译文重复(中文区分粒度与英文不同属正常,不做重复值检查)。
"""
import re, io, os, sys

sys.stdout.reconfigure(encoding='utf-8')
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, 'app/src/main/res')

ENDONYM_KEYS = {
    'settings_language_zh_hans',
    'settings_language_zh_hant_tw',
    'settings_language_zh_hant_hk',
}

WS_KEYS = {
    'follow_days_separator',
}

PH = re.compile(r'%(?:\d+\$)?[a-z]|%%')


def entries(path):
    xml = io.open(path, encoding='utf-8').read()
    pairs = re.findall(r'<string name="([^"]+)">(.*?)</string>', xml, re.S)
    names = [n for n, _ in pairs]
    dup = sorted(n for n in set(names) if names.count(n) > 1)
    return dict(pairs), dup


def ph_set(v):
    return sorted(m.group(0) for m in PH.finditer(v))


def load_s2t_map():
    """从 Trans.kt 的字表反推简体→繁体映射(仅保留字形不同的对)"""
    src = io.open(os.path.join(ROOT, 'app/src/main/java/com/github/catvod/crawler/js/Trans.kt'),
                  encoding='utf-8').read()
    t = re.search(r'UTF8T\s*=\s*"([^"]*)"', src).group(1)
    s = re.search(r'UTF8S\s*=\s*"([^"]*)"', src).group(1)
    return {a: b for a, b in zip(s, t) if a != b}


def s2t_suspect(v, table):
    return ''.join(table.get(c, c) for c in v) != v


def main():
    subset = '--subset' in sys.argv[1:]
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    lang = args[0] if args else 'en'
    check_cjk = lang == 'en'
    base, base_dup = entries(os.path.join(RES, 'values/strings.xml'))
    target_path = os.path.join(RES, 'values-' + lang, 'strings.xml')
    if not os.path.exists(target_path):
        print('MISSING FILE: %s' % target_path)
        sys.exit(2)
    tgt, dup = entries(target_path)

    missing = sorted(k for k in base if k not in tgt)
    extra = sorted(k for k in tgt if k not in base)
    print('MODE=%s base=%d target=%d' % ('subset' if subset else 'full', len(base), len(tgt)))
    print('DUP-NAME(base=%s target=%s)' % (base_dup, dup))
    if subset:
        print('MISSING(%d, subset 允许,不打印清单)' % len(missing))
    else:
        print('MISSING(%d): %s' % (len(missing), missing))
    print('EXTRA(%d): %s' % (len(extra), extra))

    ph_bad, cjk, empty, ws = [], [], [], []
    for k, bv in base.items():
        if k not in tgt:
            continue
        tv = tgt[k]
        if not tv.strip():
            empty.append(k)
        if tv != tv.strip() and k not in WS_KEYS:
            ws.append(k)
        if ph_set(bv) != ph_set(tv):
            ph_bad.append((k, ph_set(bv), ph_set(tv)))
        if check_cjk and re.search(r'[\u4e00-\u9fff]', tv) and k not in ENDONYM_KEYS:
            cjk.append((k, tv[:30]))

    print('PLACEHOLDER-MISMATCH(%d):' % len(ph_bad))
    for k, a, b in ph_bad:
        print('   %s base=%s target=%s' % (k, a, b))
    print('CJK-IN-TARGET(%d): %s' % (len(cjk), cjk))
    if not check_cjk:
        table = load_s2t_map()
        hits = [(k, v[:30]) for k, v in tgt.items() if s2t_suspect(v, table)]
        print('S2T-SUSPECT(%d, 提示项;简繁同形字会假阳性,人工核对):' % len(hits))
        for k, v in hits[:60]:
            print('   %s = %s' % (k, v))
    print('EMPTY: %s' % empty)
    print('LEADING/TRAILING-WS: %s' % ws)

    if subset:
        upper_path = os.path.join(RES, 'values-b+zh+Hant/strings.xml')
        if os.path.exists(upper_path):
            upper, _ = entries(upper_path)
            redundant = sorted(k for k, v in tgt.items() if upper.get(k) == v)
            print('REDUNDANT-VS-UPPER(%d, 差异层里与上级层同值的条目,应删或改写): %s'
                  % (len(redundant), redundant))

    ok = not (extra or dup or ph_bad or cjk or empty or ws)
    if not subset:
        ok = ok and not missing
    print('RESULT: ' + ('PASS' if ok else 'FAIL'))
    sys.exit(0 if ok else 1)


main()
