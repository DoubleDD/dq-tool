#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把《水库矩阵平台数据库表结构文档》改造为数据源整库表结构 Word 导出的 poi-tl 标签模板(一次性工具,留档备查)。

用法(仓库根目录,需 python-docx,如 /tmp/poivenv):
    python3 scripts/make-word-template-fulldb.py [示例docx] [输出路径]
默认输入 docs/水库矩阵平台数据库表结构文档_模板V1.0.docx,
输出 common/src/main/resources/templates/db-structure-full.docx。

与 make-word-template-dbstruct.py(单库扫描快照版)的差异:本模板面向「数据源下所有库(白名单过滤后)」,
保留多库两级循环结构,库 → 模式 → 表的嵌套由渲染期 policy 克隆原型生成;封面保持原样(不替换数据源名):
1. 第一章 总体情况表数据行 -> {{dbCount}}/{{schemaCount}}/{{tableCount}}/{{totalRows}}/{{totalSize}}
2. 第二章 数据库清单表只留表头 + 一行:表头首格插入 {{dbs}} 循环锚点(LoopRowTableRenderPolicy),
   数据行改写为 [dbName]/[schemaName]/[schemaDesc]/[tableCount]/[totalSize]
3. 第三章 表清单:只保留第一个 H2(数据库)+ 第一个 H3(模式)+ 第一张表(裁到表头 + 一行数据原型)作为原型,
   原型前插入 {{tableSections}} 锚点段(渲染期由 TableListSectionsPolicy 按 库→模式 两级克隆并删除原型)
4. 第四章 表结构:只保留第一个 H2(数据库:X中模式:Y)+ 第一个 H3 + 第一张字段表(裁到表头 + 一行)作为原型,
   原型前插入 {{structSections}} 锚点段(渲染期由 StructSectionsPolicy 按 模式→表 两级克隆并删除原型)
5. 原型小节文本统一改写为 PROTO_* 标记,便于单测断言原型已删除;其余示例小节全部删除(保留文档末尾 sectPr)
6. 目录(sdt 块)内容不动,但 TOC 域 begin fldChar 置 w:dirty=true,配合 settings.xml 的 w:updateFields,
   Word 打开时自动重算目录条目与页码,不残留模板示例目录(WPS 可能仍需手动「更新域」)
"""
import sys

from docx import Document
from docx.oxml.ns import qn

DEFAULT_SRC = 'docs/水库矩阵平台数据库表结构文档_模板V1.0.docx'
DEFAULT_OUT = 'common/src/main/resources/templates/db-structure-full.docx'


def text_of(el):
    return ''.join(t.text or '' for t in el.iter(qn('w:t')))


def p_style(p_el):
    ppr = p_el.find(qn('w:pPr'))
    if ppr is None:
        return ''
    st = ppr.find(qn('w:pStyle'))
    return st.get(qn('w:val')) if st is not None else ''


def set_text_keep_style(p_el, text):
    """段落替换为单 run 文本,保留首个 run 的字符格式"""
    runs = p_el.findall(qn('w:r'))
    if not runs:
        r = p_el.makeelement(qn('w:r'), {})
        p_el.append(r)
        runs = [r]
    first = runs[0]
    for t in first.findall(qn('w:t')):
        first.remove(t)
    t = first.makeelement(qn('w:t'), {})
    t.text = text
    t.set(qn('xml:space'), 'preserve')
    first.append(t)
    for r in runs[1:]:
        p_el.remove(r)


def set_cell_text(tc_el, text):
    """单元格替换为单段单 run 文本,保留原格式"""
    ps = tc_el.findall(qn('w:p'))
    for p in ps[1:]:
        tc_el.remove(p)
    set_text_keep_style(ps[0], text)


def body_blocks(doc):
    """正文顶层元素序列:[('p'|'tbl'|'sdt'|'sectPr', element)]"""
    out = []
    for ch in doc.element.body:
        tag = ch.tag.split('}')[1]
        out.append((tag, ch))
    return out


def find_h1(blocks, text):
    for i, (tag, el) in enumerate(blocks):
        if tag == 'p' and p_style(el) == '3' and text_of(el).strip() == text:
            return i
    raise SystemExit(f'未找到一级标题: {text}')


def next_tbl(blocks, idx):
    for i in range(idx + 1, len(blocks)):
        if blocks[i][0] == 'tbl':
            return i
    raise SystemExit('标题后未找到表格')


def delete_rows(tbl_el, keep):
    """表格只保留前 keep 行"""
    trs = tbl_el.findall(qn('w:tr'))
    for tr in trs[keep:]:
        tbl_el.remove(tr)


def row_cells(tbl_el, row_idx):
    return tbl_el.findall(qn('w:tr'))[row_idx].findall(qn('w:tc'))


def insert_anchor_run(tc_el, tag):
    """单元格首段最前面插入独立 run 的循环锚点标签(仿 make-word-template-dbstruct.py)"""
    p = tc_el.findall(qn('w:p'))[0]
    r = p.makeelement(qn('w:r'), {})
    t = p.makeelement(qn('w:t'), {})
    t.text = tag
    r.append(t)
    ppr = p.find(qn('w:pPr'))
    p.insert(1 if ppr is not None else 0, r)


def make_anchor_paragraph(text):
    """锚点段(渲染期删除,样式无所谓)"""
    from docx.oxml import OxmlElement
    p = OxmlElement('w:p')
    r = OxmlElement('w:r')
    t = OxmlElement('w:t')
    t.text = text
    r.append(t)
    p.append(r)
    return p


def mark_toc_dirty(doc):
    """目录(sdt)TOC 域的 begin fldChar 置 w:dirty=true:Word 打开时自动重算目录条目与页码,
    不残留模板示例标题(与 settings.xml 的 w:updateFields 互为双保险)"""
    for ch in doc.element.body:
        if ch.tag.split('}')[1] != 'sdt':
            continue
        instr = ''.join(t.text or '' for t in ch.iter(qn('w:instrText')))
        if 'TOC' not in instr:
            continue
        for fld in ch.findall('.//' + qn('w:fldChar')):
            if fld.get(qn('w:fldCharType')) == 'begin':
                fld.set(qn('w:dirty'), 'true')
                return
    raise SystemExit('未找到目录 TOC 域')


def keep_only(blocks, start, end, keep):
    """删除 [start, end) 区间内除 keep 外的所有顶层元素"""
    for tag, el in blocks[start:end]:
        if id(el) not in keep:
            el.getparent().remove(el)


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_SRC
    out = sys.argv[2] if len(sys.argv) > 2 else DEFAULT_OUT
    doc = Document(src)
    blocks = body_blocks(doc)

    # ---- 目录域标记 dirty:Word 打开时强制刷新该域(配合 settings.xml 的 w:updateFields);
    # 否则目录仍是模板残留的示例条目。WPS 可能仍需手动「更新域」 ----
    mark_toc_dirty(doc)

    # ---- 第一章 总体情况 ----
    i1 = find_h1(blocks, '总体情况')
    t1 = blocks[next_tbl(blocks, i1)][1]
    for cell, tag in zip(row_cells(t1, 1),
                         ['{{dbCount}}', '{{schemaCount}}', '{{tableCount}}', '{{totalRows}}', '{{totalSize}}']):
        set_cell_text(cell, tag)

    # ---- 第二章 数据库清单(LoopRow:表头 + 一行数据原型) ----
    i2 = find_h1(blocks, '数据库清单')
    t2 = blocks[next_tbl(blocks, i2)][1]
    for cell, tag in zip(row_cells(t2, 1),
                         ['[dbName]', '[schemaName]', '[schemaDesc]', '[tableCount]', '[totalSize]']):
        set_cell_text(cell, tag)
    insert_anchor_run(row_cells(t2, 0)[0], '{{dbs}}')
    delete_rows(t2, 2)

    # ---- 第三章 表清单(保留首个 H2 + 首个 H3 + 首表作原型,插锚点,删其余) ----
    i3 = find_h1(blocks, '表清单')
    i4 = find_h1(blocks, '表结构')
    h2 = h3 = t3 = None
    for tag, el in blocks[i3 + 1:i4]:
        if tag == 'p' and p_style(el) == '4' and h2 is None:
            h2 = el
        elif tag == 'p' and p_style(el) == '5' and h3 is None:
            h3 = el
        elif tag == 'tbl' and t3 is None:
            t3 = el
    if h2 is None or h3 is None or t3 is None:
        raise SystemExit('第三章结构异常(缺 H2/H3/表格)')
    set_text_keep_style(h2, '数据库：PROTO_DB')
    set_text_keep_style(h3, '模式：PROTO_SCHEMA')
    for cell, text in zip(row_cells(t3, 1), ['PROTO_TABLE', 'PROTO_COMMENT', '0', '-']):
        set_cell_text(cell, text)
    delete_rows(t3, 2)
    anchor3 = make_anchor_paragraph('{{tableSections}}')
    h2.addprevious(anchor3)
    keep_only(blocks, i3 + 1, i4, {id(h2), id(h3), id(t3), id(anchor3)})

    # ---- 第四章 表结构(保留首个 H2 + 原型 H3/字段表,插锚点,删其余) ----
    blocks = body_blocks(doc)  # 删除后重取
    i4 = find_h1(blocks, '表结构')
    sect_idx = next(i for i, (tag, _) in enumerate(blocks) if tag == 'sectPr')
    h2 = proto_h3 = proto_tbl = None
    for tag, el in blocks[i4 + 1:sect_idx]:
        if tag == 'p' and p_style(el) == '4' and h2 is None:
            h2 = el
        elif tag == 'p' and p_style(el) == '5' and proto_h3 is None:
            proto_h3 = el
        elif tag == 'tbl' and proto_tbl is None:
            proto_tbl = el
    if h2 is None or proto_h3 is None or proto_tbl is None:
        raise SystemExit('第四章结构异常(缺 H2/原型小节)')
    set_text_keep_style(h2, '数据库：PROTO_DB中模式：PROTO_SCHEMA')
    set_text_keep_style(proto_h3, 'PROTO_TABLE_TITLE')
    # 原型字段表只留表头 + 一行数据原型(渲染期按字段数克隆该行)
    for cell in row_cells(proto_tbl, 1):
        set_cell_text(cell, 'PROTO_COL')
    delete_rows(proto_tbl, 2)
    anchor4 = make_anchor_paragraph('{{structSections}}')
    h2.addprevious(anchor4)
    keep_only(blocks, i4 + 1, sect_idx, {id(h2), id(proto_h3), id(proto_tbl), id(anchor4)})

    doc.save(out)
    enable_update_fields(out)
    print('模板改造完成:', out)


def enable_update_fields(path):
    """settings.xml 加 <w:updateFields>:Word/WPS 打开时自动更新域(目录条目与页码随渲染结果刷新,免手动 F9)。
    按 CT_Settings 顺序插在 footnotePr 之前。"""
    from docx import Document as Doc
    d = Doc(path)
    settings = d.settings.element
    if settings.find(qn('w:updateFields')) is None:
        uf = settings.makeelement(qn('w:updateFields'), {})
        uf.set(qn('w:val'), 'true')
        footnote_pr = settings.find(qn('w:footnotePr'))
        if footnote_pr is not None:
            footnote_pr.addprevious(uf)
        else:
            settings.append(uf)
        d.save(path)


if __name__ == '__main__':
    main()
