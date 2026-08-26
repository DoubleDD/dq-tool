#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把《水库矩阵平台数据库表结构文档》改造为扫描结果 Word 导出的 poi-tl 标签模板(一次性工具,留档备查)。

用法(仓库根目录,需 python-docx,如 /tmp/poivenv):
    python3 scripts/make-word-template-dbstruct.py [示例docx] [输出路径]
默认输入 docs/水库矩阵平台数据库表结构文档_模板V1.0.docx,
输出 common/src/main/resources/templates/db-structure-report.docx。

扫描结果 Word 导出一个扫描任务 = 一个库(schema),故模板只保留单库结构:
1. 封面主标题 -> {{title}}(数据源名)
2. 第一章 总体情况表数据行 -> {{dbCount}}/{{schemaCount}}/{{tableCount}}/{{totalRows}}/{{totalSize}}
3. 第二章 数据库清单表只留一行 -> {{dbName}}/{{schemaName}}/{{schemaDesc}}/{{tableCount}}/{{totalSize}}
4. 第三章 表清单:只保留第一个「数据库:{{dbName}}」H2 + 「模式:{{schemaName}}」H3 + 第一张表;
   表头首格插入 {{tables}} 循环锚点(LoopRowTableRenderPolicy),首行数据行改写为 [name]/[comment]/[totalRows]/[tags]
5. 第四章 表结构:H2 -> 「数据库:{{dbName}}中模式:{{schemaName}}」;只保留第一个 H3 标题段 + 第一张字段表
   作为原型小节(渲染期由 TableStructsPolicy 逐表深拷贝并删除原型),原型前插入 {{tableStructs}} 锚点段;
   其后示例小节全部删除(保留文档末尾 sectPr)

目录(sdt 块)不动,页码与条目由 Word 打开后 F9 更新域。
"""
import sys

from docx import Document
from docx.oxml.ns import qn

DEFAULT_SRC = 'docs/水库矩阵平台数据库表结构文档_模板V1.0.docx'
DEFAULT_OUT = 'common/src/main/resources/templates/db-structure-report.docx'

W = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'


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
    """单元格首段最前面插入独立 run 的循环锚点标签(仿 make-word-template.py)"""
    p = tc_el.findall(qn('w:p'))[0]
    r = p.makeelement(qn('w:r'), {})
    t = p.makeelement(qn('w:t'), {})
    t.text = tag
    r.append(t)
    ppr = p.find(qn('w:pPr'))
    p.insert(1 if ppr is not None else 0, r)


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_SRC
    out = sys.argv[2] if len(sys.argv) > 2 else DEFAULT_OUT
    doc = Document(src)
    blocks = body_blocks(doc)

    # ---- 封面主标题 ----
    for tag, el in blocks:
        if tag == 'p' and text_of(el).strip():
            set_text_keep_style(el, '{{title}}')
            break

    # ---- 第一章 总体情况 ----
    i1 = find_h1(blocks, '总体情况')
    t1 = blocks[next_tbl(blocks, i1)][1]
    for cell, tag in zip(row_cells(t1, 1),
                         ['{{dbCount}}', '{{schemaCount}}', '{{tableCount}}', '{{totalRows}}', '{{totalSize}}']):
        set_cell_text(cell, tag)

    # ---- 第二章 数据库清单(只留表头 + 一行) ----
    i2 = find_h1(blocks, '数据库清单')
    t2 = blocks[next_tbl(blocks, i2)][1]
    for cell, tag in zip(row_cells(t2, 1),
                         ['{{dbName}}', '{{schemaName}}', '{{schemaDesc}}', '{{tableCount}}', '{{totalSize}}']):
        set_cell_text(cell, tag)
    delete_rows(t2, 2)

    # ---- 第三章 表清单(只保留首个 H2 + 首个 H3 + 首表) ----
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
    set_text_keep_style(h2, '数据库：{{dbName}}')
    set_text_keep_style(h3, '模式：{{schemaName}}')
    for cell, tag in zip(row_cells(t3, 1), ['[name]', '[comment]', '[totalRows]', '[tags]']):
        set_cell_text(cell, tag)
    insert_anchor_run(row_cells(t3, 0)[0], '{{tables}}')
    delete_rows(t3, 2)
    keep3 = {id(h2), id(h3), id(t3)}
    for tag, el in blocks[i3 + 1:i4]:
        if id(el) not in keep3:
            el.getparent().remove(el)

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
    set_text_keep_style(h2, '数据库：{{dbName}}中模式：{{schemaName}}')
    # 原型字段表只留表头 + 一行数据原型(渲染期按字段数克隆该行)
    delete_rows(proto_tbl, 2)
    # 锚点段(渲染期删除,样式无所谓)
    anchor = h2.makeelement(qn('w:p'), {})
    r = anchor.makeelement(qn('w:r'), {})
    t = anchor.makeelement(qn('w:t'), {})
    t.text = '{{tableStructs}}'
    r.append(t)
    anchor.append(r)
    proto_h3.addprevious(anchor)
    keep4 = {id(h2), id(proto_h3), id(proto_tbl), id(anchor)}
    for tag, el in blocks[i4 + 1:sect_idx]:
        if id(el) not in keep4:
            el.getparent().remove(el)

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
