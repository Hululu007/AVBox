# -*- coding: utf-8 -*-
"""删除指定文件里"没被正文引用"的 import。

用法: python prune_imports.py [--apply] <file.kt> [file2.kt ...]
默认只报告;--apply 才落盘(落盘后必须重新编译验证)。
委托/运算符类 import 由隐式语法使用、名字不出现在正文,列入白名单不动。
注意:文件里注释提到的类名会被当成"使用中",所以**注释精简之后要再跑一次**本脚本。
"""
import re
import sys

WHITELIST = {
    "getValue", "setValue", "provideDelegate", "iterator", "contains", "invoke", "get", "set",
    "compareTo", "equals", "hashCode", "toString", "plus", "minus", "times", "div", "rem",
    "unaryMinus", "unaryPlus", "not", "inc", "dec", "rangeTo", "plusAssign", "minusAssign",
    "and", "or", "xor", "shl", "shr", "ushr",
}


def unused_in(lines):
    imp_idx = [i for i, l in enumerate(lines) if l.startswith("import ")]
    imp_set = set(imp_idx)
    body = "\n".join(l for i, l in enumerate(lines) if i not in imp_set)
    out = []
    for i in imp_idx:
        target = lines[i].strip()[len("import "):]
        if target.endswith(".*"):
            continue
        m = re.search(r"\bas\s+(\w+)$", target)
        name = m.group(1) if m else target.rsplit(".", 1)[-1]
        if name in WHITELIST:
            continue
        if not re.search(r"\b" + re.escape(name) + r"\b", body):
            out.append(i)
    return out


def main():
    args = [a for a in sys.argv[1:] if a != "--apply"]
    apply = "--apply" in sys.argv
    if not args:
        raise SystemExit(__doc__)
    for path in args:
        with open(path, encoding="utf-8", newline="") as f:
            lines = f.read().split("\n")
        drop = unused_in(lines)
        print("%-22s 未使用 import %d 个" % (path.rsplit("/", 1)[-1], len(drop)))
        for i in drop:
            print("      " + lines[i].strip())
        if apply and drop:
            drop_set = set(drop)
            with open(path, "w", encoding="utf-8", newline="") as f:
                f.write("\n".join(l for i, l in enumerate(lines) if i not in drop_set))


if __name__ == "__main__":
    main()
