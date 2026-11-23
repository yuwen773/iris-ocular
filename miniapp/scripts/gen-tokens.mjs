// 从 web/src/styles/tokens.css 生成小程序用的 WXSS 令牌。
//
// 为什么是「生成」而不是手抄：DESIGN.md 与 tokens.css 是设计真源，手抄一份迟早漂移。
// 为什么不是把 tokens.css 整个搬过来：里面用了 color-mix() / clamp()，WXSS 的
// WebView（尤其 iOS 旧版本）支持度不够，原样搬过去会在真机上静默失效。
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const repoRoot = resolve(here, '..', '..')
const sourceFile = resolve(repoRoot, 'web', 'src', 'styles', 'tokens.css')
const outputFile = resolve(here, '..', 'src', 'styles', 'tokens.scss')

const css = readFileSync(sourceFile, 'utf8')

/** 取「选择器后紧跟 {」的那个块，按大括号配平截取内容。 */
function blockOf(selector) {
  const pattern = new RegExp(`^${selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s*\\{`, 'm')
  const match = pattern.exec(css)
  if (!match) {
    throw new Error(`tokens.css 里找不到选择器 ${selector}`)
  }
  const open = css.indexOf('{', match.index)
  let depth = 0
  for (let i = open; i < css.length; i += 1) {
    if (css[i] === '{') depth += 1
    if (css[i] === '}') {
      depth -= 1
      if (depth === 0) return css.slice(open + 1, i)
    }
  }
  throw new Error(`选择器 ${selector} 的块没有闭合`)
}

function declarations(block) {
  const pairs = []
  const pattern = /(--[a-zA-Z0-9-]+)\s*:\s*([^;]+);/g
  let match
  while ((match = pattern.exec(block)) !== null) {
    pairs.push([match[1], match[2].replace(/\s+/g, ' ').trim()])
  }
  return pairs
}

/** WXSS 里不可靠的取值：跳过并把名字写进输出文件头，便于发现缺失。 */
const UNSUPPORTED = [/color-mix\(/i, /clamp\(/i, /light-dark\(/i]

function render(selector, pairs, skipped) {
  const lines = [`${selector} {`]
  for (const [name, value] of pairs) {
    if (UNSUPPORTED.some((rule) => rule.test(value))) {
      skipped.push(name)
      continue
    }
    lines.push(`  ${name}: ${value};`)
  }
  lines.push('}')
  return lines.join('\n')
}

const skipped = []
const rootBlock = render('page', declarations(blockOf(':root')), skipped)
const darkBlock = render('page', declarations(blockOf('html.dark')), skipped)

const header = [
  '// ⚠️ 本文件由 miniapp/scripts/gen-tokens.mjs 自动生成，请勿手改。',
  '// 真源：web/src/styles/tokens.css（`npm run gen:tokens` 重新生成）。',
  skipped.length
    ? `// 已跳过（WXSS 支持度不足）：${[...new Set(skipped)].join(', ')}`
    : '// 没有跳过任何令牌。',
  '',
].join('\n')

const body = [
  '// 浅色：Clinical 判读纸面就是白纸墨字，与 web/ 的默认主题同值。',
  rootBlock,
  '',
  '// 深色：跟随系统。小程序 theme.json 只换导航栏，令牌在这里翻转。',
  '@media (prefers-color-scheme: dark) {',
  darkBlock
    .split('\n')
    .map((line) => (line ? `  ${line}` : line))
    .join('\n'),
  '}',
  '',
].join('\n')

mkdirSync(dirname(outputFile), { recursive: true })
writeFileSync(outputFile, header + body, 'utf8')
console.log(`[gen-tokens] ${outputFile}${skipped.length ? `（跳过 ${skipped.length} 个令牌）` : ''}`)
