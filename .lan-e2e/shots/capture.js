// 局域网共享 E2E 截图取证(v3:数据源映射 + 逐行明细 + 勾选同步全流程)
const { chromium } = require('playwright-core')
const path = require('path')

const B = 'http://localhost:10102'
const A = 'http://localhost:10101'
const OUT = path.join(__dirname, 'screenshots')

async function main() {
  const browser = await chromium.launch({ channel: 'chrome', headless: true })
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 2 })
  const page = await ctx.newPage()

  // 首次打开会按 dq-seen-version 判定自动弹「本次更新」,先置为已读避免抢占路由
  for (const base of [B, A]) {
    await page.goto(`${base}/datasources`, { waitUntil: 'domcontentloaded' })
    await page.evaluate(() => localStorage.setItem('dq-seen-version', '1.9.8'))
  }

  // 1. B 的「局域网共享」页:在线实例(应显示 A)
  await page.goto(`${B}/lan-share`, { waitUntil: 'networkidle' })
  await page.waitForSelector('text=实例A-小王的电脑', { timeout: 20000 })
  await page.waitForTimeout(600)
  await page.screenshot({ path: path.join(OUT, '01-b-lan-share-peers.png') })

  // 2. 点「同步标记与描述」→ 预览弹窗(标记清单)
  await page.click('button:has-text("同步标记与描述")')
  await page.waitForSelector('text=标记定义', { timeout: 15000 })
  await page.waitForTimeout(500)
  await page.screenshot({ path: path.join(OUT, '02-b-preview-annotations.png') })

  // 3. 展开「表级打标」明细(逐行:表/标记/数据源)
  await page.click('.el-collapse-item__header:has-text("表级打标")')
  await page.waitForTimeout(500)
  await page.locator('.el-collapse-item__header:has-text("表级打标")').scrollIntoViewIfNeeded()
  await page.waitForTimeout(300)
  await page.screenshot({ path: path.join(OUT, '03-b-preview-detail-tags.png') })

  // 4. 滚到「数据源映射」:对方数据源与本机的对应选择(异名 → 默认新建无密码副本)
  await page.locator('.lan-preview-section-title:has-text("数据源映射")').scrollIntoViewIfNeeded()
  await page.waitForTimeout(300)
  await page.screenshot({ path: path.join(OUT, '04-b-preview-ds-mapping.png') })

  // 5. 映射下拉展开(选项:现有数据源/新建(不含密码)/跳过)
  await page.locator('.lan-ds-map-table .el-select').first().click()
  await page.waitForSelector('.el-select-dropdown__item:has-text("新建到本机")', { timeout: 5000 })
  await page.waitForTimeout(300)
  await page.screenshot({ path: path.join(OUT, '05-b-ds-mapping-options.png') })
  await page.keyboard.press('Escape')

  // 6. 取消勾选「需治理」,只同步「核心业务」
  await page.locator('.lan-preview-section-title:has-text("标注数据")').scrollIntoViewIfNeeded()
  await page.click('.el-checkbox:has-text("需治理")')
  await page.waitForTimeout(200)
  await page.screenshot({ path: path.join(OUT, '06-b-preview-annotations-partial.png') })

  // 7. 开始拉取 → 结果弹窗(含数据源副本提示)
  await page.click('.el-dialog button:has-text("开始拉取")')
  await page.waitForSelector('text=同步完成', { timeout: 30000 })
  await page.waitForTimeout(300)
  await page.screenshot({ path: path.join(OUT, '07-b-pull-result-annotations.png') })
  await page.click('button:has-text("知道了")').catch(() => {})

  // 8. B 的数据源页:「分析库」无密码副本已在列表
  await page.goto(`${B}/datasources`, { waitUntil: 'networkidle' })
  await page.waitForSelector('text=分析库', { timeout: 10000 })
  await page.waitForTimeout(600)
  await page.screenshot({ path: path.join(OUT, '08-b-datasource-created.png') })

  // 9. B 的标记统计页
  await page.goto(`${B}/tags`, { waitUntil: 'networkidle' })
  await page.waitForSelector('text=核心业务', { timeout: 10000 })
  await page.waitForTimeout(600)
  await page.screenshot({ path: path.join(OUT, '09-b-tags-after-sync.png') })

  // 10. 点「导入全部数据」→ 预览弹窗(标注 + 映射 + 扫描任务表)
  await page.goto(`${B}/lan-share`, { waitUntil: 'networkidle' })
  await page.waitForSelector('text=实例A-小王的电脑', { timeout: 20000 })
  await page.click('button:has-text("导入全部数据")')
  await page.waitForSelector('.el-dialog >> text=创建时间', { timeout: 15000 })
  await page.waitForTimeout(400)
  await page.locator('.el-dialog >> text=扫描记录').first().scrollIntoViewIfNeeded()
  await page.waitForTimeout(300)
  await page.screenshot({ path: path.join(OUT, '10-b-preview-all-with-jobs.png') })

  // 11. 开始拉取(幂等重拉显示判重跳过) → 结果弹窗
  await page.click('.el-dialog button:has-text("开始拉取")')
  await page.waitForSelector('text=同步完成', { timeout: 60000 })
  await page.waitForTimeout(300)
  await page.screenshot({ path: path.join(OUT, '11-b-pull-result-all.png') })
  await page.click('button:has-text("知道了")').catch(() => {})

  // 12. B 的扫描记录页:从 A 导入的任务(挂在异名映射的「我的MySQL」上)
  await page.goto(`${B}/dashboard`, { waitUntil: 'networkidle' })
  await page.waitForSelector('text=我的MySQL', { timeout: 10000 })
  await page.waitForTimeout(600)
  await page.screenshot({ path: path.join(OUT, '12-b-scan-jobs-imported.png') })

  // 13. B 的表列表页:同步来的表描述 + 标记
  await page.goto(`${B}/datasources/1/schemas/dqdemo/tables?name=x`, { waitUntil: 'networkidle' })
  await page.waitForTimeout(1200)
  await page.screenshot({ path: path.join(OUT, '13-b-tables-doc-tags.png') })

  // 14. A 的「局域网共享」页:双向发现(A 看到 B)
  await page.goto(`${A}/lan-share`, { waitUntil: 'networkidle' })
  await page.waitForSelector('text=实例B-小李的电脑', { timeout: 20000 })
  await page.waitForTimeout(600)
  await page.screenshot({ path: path.join(OUT, '14-a-lan-share-peers.png') })

  await browser.close()
  console.log('screenshots done')
}

main().catch((e) => { console.error(e); process.exit(1) })
