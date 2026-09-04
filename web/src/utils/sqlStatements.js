/**
 * SQL 语句拆分工具:按顶层分号把一段 SQL 文本切成多条语句,供 SQL 控制台实现
 * 类 DataGrip 的「执行光标所在语句」。识别单/双引号、反引号字符串与
 * 行注释(--、#)、块注释(注释/字符串内部的分号不算分隔符)。
 */

/**
 * 把 SQL 文本按顶层分号切成语句区间。
 * @param {string} text SQL 原文
 * @returns {{from: number, to: number, text: string}[]} 语句数组,from/to 为已 trim 的原文区间(不含分号);纯空白片段不产生语句
 */
export function splitStatements(text) {
  const stmts = []
  let start = 0
  let i = 0
  const n = text.length
  while (i < n) {
    const c = text[i]
    const next = text[i + 1]
    if (c === "'" || c === '"' || c === '`') {
      i = skipQuoted(text, i, c)
    } else if (c === '-' && next === '-') {
      i = skipLineComment(text, i)
    } else if (c === '#') {
      i = skipLineComment(text, i)
    } else if (c === '/' && next === '*') {
      i = skipBlockComment(text, i)
    } else if (c === ';') {
      pushStatement(stmts, text, start, i)
      start = i + 1
      i++
    } else {
      i++
    }
  }
  pushStatement(stmts, text, start, n)
  return stmts
}

/**
 * 取光标所在语句。
 * 规则(类 DataGrip,但更保守):光标落在某条语句区间内取该语句;
 * 落在语句之间的空白处时,只认「紧贴」的语句(光标与语句之间没有空行)——
 * 上下都紧贴时取上面一条;被空行隔开(或全文没有任何语句)返回 ''。
 * @param {string} text SQL 原文
 * @param {number} pos 光标位置(字符偏移)
 * @returns {string} 待执行语句文本,'' 表示光标处没有可执行语句
 */
export function statementAt(text, pos) {
  const stmts = splitStatements(text)
  if (!stmts.length) return ''
  for (const s of stmts) {
    if (pos >= s.from && pos <= s.to) return s.text
  }
  const prev = [...stmts].reverse().find((s) => s.to < pos)
  const next = stmts.find((s) => s.from > pos)
  // 「紧贴」判定:光标与语句之间只隔空白且不包含空行(空行 = 两个换行之间只有空白)
  const prevAttached = !!prev && !/\n\s*\n/.test(text.slice(prev.to, pos))
  const nextAttached = !!next && !/\n\s*\n/.test(text.slice(pos, next.from))
  if (prevAttached) return prev.text // 上下都紧贴时取上面一条
  if (nextAttached) return next.text
  return ''
}

/** 跳过一次引号字符串,返回闭引号之后的下标;支持反斜杠与双写引号两种转义,未闭合返回文本末尾 */
function skipQuoted(text, i, quote) {
  i++
  while (i < text.length) {
    if (text[i] === '\\') {
      i += 2
      continue
    }
    if (text[i] === quote) {
      if (text[i + 1] === quote) {
        i += 2
        continue
      }
      return i + 1
    }
    i++
  }
  return text.length
}

/** 跳过行注释(到换行符为止,换行符本身留给主循环按普通字符处理) */
function skipLineComment(text, i) {
  const nl = text.indexOf('\n', i)
  return nl === -1 ? text.length : nl
}

/** 跳过块注释,返回结束后下标;未闭合返回文本末尾 */
function skipBlockComment(text, i) {
  const end = text.indexOf('*/', i + 2)
  return end === -1 ? text.length : end + 2
}

/** 收集 [start, end) 区间的语句:trim 首尾空白,纯空白(空语句)直接丢弃 */
function pushStatement(stmts, text, start, end) {
  const raw = text.slice(start, end)
  const trimmed = raw.trim()
  if (!trimmed) return
  const from = start + raw.indexOf(trimmed[0])
  stmts.push({ from, to: from + trimmed.length, text: trimmed })
}
