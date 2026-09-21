#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
jar 宿主契约审计 / DEX 引用定位工具

配套 skill/history/features.md:205 的「宿主契约审计」方法：
    jar 引用 ∧ jar 未定义 ∧ 宿主缺失 = 宿主必须补的类

为什么用 Python 而不是内联 PowerShell：
    内联 pwsh 的 Add-Type 定义的类型不跨进程，解析与比对必须挤在同一次调用里，
    脚本一长就没法维护；且类型描述符的 L...; 形态容易写错（features.md:207 的坑①）。

用法
    # 1) 审计：列出 jar 需要、而宿主 APK 完全没有的类
    python jar_host_audit.py audit <jar> <宿主APK>

    # 2) 定位：找出 jar 里哪个类/方法引用了某个类型（追崩溃点）
    python jar_host_audit.py locate <jar> <类型描述符关键字>

    # 例
    python jar_host_audit.py audit  示例文件/fm.jar app/build/outputs/apk/release/AVBox_release.apk
    python jar_host_audit.py locate 示例文件/fm.jar sardineandroid/impl/OkHttpSardine

注意
    - 必须审 release 产物：debug 不混淆，契约缺口只在 release 暴露（features.md:220）。
    - 「常量池引用 ≠ 宿主应当提供」：jar 若把某库 shade 进自己的包，宿主再补反而会
      IncompatibleClassChangeError（slf4j 教训，features.md:229）。审计结果是候选列表。
"""
import os
import re
import struct
import sys
import zipfile

TYPE_RE = re.compile(rb'^L[A-Za-z0-9_$/<>\-\.]+;$')

# 平台/系统自带，不算宿主契约缺口
PLATFORM_PREFIXES = (
    'Ljava/', 'Ljavax/', 'Landroid/', 'Ldalvik/', 'Lorg/w3c/', 'Lorg/xml/',
    'Lorg/json/', 'Lsun/', 'Ljunit/', 'Lorg/apache/', 'Lorg/xmlpull/',
)

# DEX 头内各 id 段偏移（注意：class_defs 在 96/100，别错读成 proto_ids 的 72/76）
HDR_STRING = 56
HDR_TYPE = 64
HDR_METHOD = 88
HDR_CLASS_DEFS = 96


def uleb128(data, off):
    result = 0
    shift = 0
    while True:
        b = data[off]
        off += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80):
            return result, off
        shift += 7


class Dex:
    def __init__(self, data):
        self.d = data
        self.string_ids_size, self.string_ids_off = struct.unpack_from('<II', data, HDR_STRING)
        self.type_ids_size, self.type_ids_off = struct.unpack_from('<II', data, HDR_TYPE)
        self.method_ids_size, self.method_ids_off = struct.unpack_from('<II', data, HDR_METHOD)
        self.class_defs_size, self.class_defs_off = struct.unpack_from('<II', data, HDR_CLASS_DEFS)

    def string(self, idx):
        off = struct.unpack_from('<I', self.d, self.string_ids_off + 4 * idx)[0]
        n, p = uleb128(self.d, off)
        return self.d[p:p + n].decode('utf-8', 'replace')

    def type_desc(self, type_idx):
        if type_idx >= self.type_ids_size:
            return '<bad>'
        desc_idx = struct.unpack_from('<I', self.d, self.type_ids_off + 4 * type_idx)[0]
        return self.string(desc_idx)

    def method(self, m_idx):
        if m_idx >= self.method_ids_size:
            return '<bad>', '<bad>'
        cls_idx, _proto, name_idx = struct.unpack_from('<HHI', self.d, self.method_ids_off + 8 * m_idx)
        return self.type_desc(cls_idx), self.string(name_idx)

    def strings(self):
        return [self.string(i) for i in range(self.string_ids_size)]

    def referenced_types(self):
        """常量池里所有 L...; 形态的类型（含 jar 自己定义的）"""
        out = set()
        for i in range(self.string_ids_size):
            raw = self.string(i)
            if TYPE_RE.match(raw.encode('utf-8', 'replace')):
                out.add(raw)
        return out

    def defined_types(self):
        """class_defs -> class_idx -> type_ids -> 描述符"""
        out = set()
        for i in range(self.class_defs_size):
            class_idx = struct.unpack_from('<I', self.d, self.class_defs_off + 32 * i)[0]
            out.add(self.type_desc(class_idx))
        return out

    def classes(self):
        """-> [(类描述符, class_data_off)]"""
        out = []
        for i in range(self.class_defs_size):
            base = self.class_defs_off + 32 * i
            class_idx = struct.unpack_from('<I', self.d, base)[0]
            class_data_off = struct.unpack_from('<I', self.d, base + 24)[0]
            out.append((self.type_desc(class_idx), class_data_off))
        return out

    def class_methods(self, class_data_off):
        """-> [(所属类, 方法名, code_off)]"""
        if class_data_off == 0:
            return []
        p = class_data_off
        static_fields, p = uleb128(self.d, p)
        instance_fields, p = uleb128(self.d, p)
        direct_methods, p = uleb128(self.d, p)
        virtual_methods, p = uleb128(self.d, p)
        for _ in range(static_fields + instance_fields):
            _, p = uleb128(self.d, p)
            _, p = uleb128(self.d, p)
        out = []
        for _ in range(direct_methods + virtual_methods):
            midx, p = uleb128(self.d, p)
            _, p = uleb128(self.d, p)
            code_off, p = uleb128(self.d, p)
            cls, name = self.method(midx)
            out.append((cls, name, code_off))
        return out


def load_dex_blobs(path):
    """支持 .dex / .jar / .apk"""
    if path.lower().endswith('.dex'):
        with open(path, 'rb') as f:
            return [(os.path.basename(path), f.read())]
    with zipfile.ZipFile(path) as z:
        return [(n, z.read(n)) for n in z.namelist() if n.endswith('.dex')]


def cmd_audit(jar_path, apk_path):
    jar_refs, jar_defs = set(), set()
    for _name, data in load_dex_blobs(jar_path):
        dex = Dex(data)
        jar_refs |= dex.referenced_types()
        jar_defs |= dex.defined_types()

    host_types = set()
    host_blobs = load_dex_blobs(apk_path)
    for _name, data in host_blobs:
        host_types |= Dex(data).referenced_types()

    gaps = sorted(jar_refs - jar_defs - host_types)
    platform = [g for g in gaps if g.startswith(PLATFORM_PREFIXES)]
    real = [g for g in gaps if g not in platform]

    print('=== 规模 ===')
    print('jar 定义类    :', len(jar_defs))
    print('jar 引用类    :', len(jar_refs))
    print('宿主 dex 数   :', len(host_blobs))
    print('宿主出现类型  :', len(host_types))
    print()
    print('=== 真缺口（宿主完全没有，jar 需要补的类）===')
    print('(无)' if not real else '')
    for g in real:
        print(' ', g)
    print()
    print('=== 平台/系统自带（不算缺口）共 %d 个 ===' % len(platform))
    for g in platform:
        print(' ', g)


def cmd_locate(jar_path, key):
    for name, data in load_dex_blobs(jar_path):
        dex = Dex(data)
        targets = [(i, dex.type_desc(i)) for i in range(dex.type_ids_size) if key in dex.type_desc(i)]
        if not targets:
            continue
        print('=== %s ===' % name)
        for ti, desc in targets:
            print('目标类型: %s  (type_idx=%d)' % (desc, ti))
            for cls_desc, cdo in dex.classes():
                for _mcls, mname, coff in dex.class_methods(cdo):
                    if coff == 0:
                        continue
                    for hit in scan_code(dex, coff, ti, desc):
                        print('  引用者: %s.%s()  ->  %s' % (cls_desc, mname, hit))
            print()


def scan_code(dex, code_off, target_type_idx, target_cls_desc):
    """在 code_item 的指令流里找对目标类型/目标类方法的引用"""
    d = dex.d
    insns_size = struct.unpack_from('<I', d, code_off + 12)[0]
    units = struct.unpack_from('<%dH' % insns_size, d, code_off + 16)
    hits = []
    for i in range(insns_size - 1):
        op = units[i] & 0xFF
        nxt = units[i + 1]
        # 21c/22c：const-class / check-cast / instance-of / new-instance / new-array
        if op in (0x1C, 0x1F, 0x20, 0x22, 0x23) and nxt == target_type_idx:
            hits.append('opcode 0x%02x 引用 type@%d' % (op, nxt))
        # 35c/3rc：invoke-*
        if op in (0x6E, 0x6F, 0x70, 0x71, 0x72, 0x74, 0x75, 0x76, 0x77, 0x78):
            cls, name = dex.method(nxt)
            if cls == target_cls_desc:
                hits.append('invoke %s.%s' % (cls, name))
    return sorted(set(hits))


def main():
    if len(sys.argv) < 4:
        print(__doc__)
        return 1
    mode, jar, arg = sys.argv[1], sys.argv[2], sys.argv[3]
    if mode == 'audit':
        cmd_audit(jar, arg)
    elif mode == 'locate':
        cmd_locate(jar, arg)
    else:
        print(__doc__)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
