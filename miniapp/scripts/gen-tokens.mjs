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

/**
 * 取「选择器后紧跟 {」的**每一个**块的声明，按出现顺序合并。
 *
 * <p>必须收集全部，不能只取第一块：`tokens.css` 里 `html.dark` 出现了两次
 * （基础暗色托盘 + 后补的 `--on-dark*` / `--dark-line*` 覆盖），只取第一块会让
 * 深色下 `--ink` 仍是浅色的墨色，正文变成深色字压深色底。
 * 后面的块覆盖前面的同名令牌，与 CSS 的层叠顺序一致。
 */
function blocksOf(selector) {
  const pattern = new RegExp(`^${selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s*\\{`, 'gm')
  const pairs = []
  let found = 0
  let match
  while ((match = pattern.exec(css)) !== null) {
    found += 1
    const open = css.indexOf('{', match.index)
    let depth = 0
    let close = -1
    for (let i = open; i < css.length; i += 1) {
      if (css[i] === '{') depth += 1
      if (css[i] === '}') {
        depth -= 1
        if (depth === 0) {
          close = i
          break
        }
      }
    }
    if (close < 0) {
      throw new Error(`选择器 ${selector} 的块没有闭合`)
    }
    pairs.push(...declarations(css.slice(open + 1, close)))
    pattern.lastIndex = close
  }
  if (found === 0) {
    throw new Error(`tokens.css 里找不到选择器 ${selector}`)
  }
  return dedupe(pairs)
}

/** 同名令牌以后出现的为准（CSS 层叠），只在输出里保留一份。 */
function dedupe(pairs) {
  const order = []
  const latest = new Map()
  for (const [name, value] of pairs) {
    if (!latest.has(name)) order.push(name)
    latest.set(name, value)
  }
  return order.map((name) => [name, latest.get(name)])
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
const rootBlock = render('page', blocksOf(':root'), skipped)
const darkBlock = render('page', blocksOf('html.dark'), skipped)

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

// ── theme.json ────────────────────────────────────────────────────────────────
// 导航栏 / 窗口底色只能写字面色值，不能读 CSS 变量。手抄的十六进制会和令牌漂移
// （曾经 dark.tabBgColor 比 --surface 差三个色阶），所以这里也从令牌解析出来。
const themeFile = resolve(here, '..', 'src', 'theme.json')

function resolutionMap(pairs) {
  return new Map(pairs)
}

function resolveColor(pairs, name) {
  const map = resolutionMap(pairs)
  const alias = /^var\((--[a-zA-Z0-9-]+)\)$/
  let value = map.get(name)
  let guard = 0
  while (value && alias.test(value)) {
    value = map.get(value.match(alias)[1])
    if (guard++ > 12) throw new Error(`令牌 ${name} 的 var() 链过长`)
  }
  if (!value) {
    throw new Error(`令牌 ${name} 不存在或其 var() 链断了，theme.json 无法生成`)
  }
  if (!/^#[0-9a-fA-F]{3,8}$/.test(value)) {
    throw new Error(`令牌 ${name} 的值 ${value} 不是十六进制色值，theme.json 只接受色值`)
  }
  return value
}

const lightPairs = blocksOf(':root')
const darkPairs = [...lightPairs, ...blocksOf('html.dark')]

const theme = {
  light: {
    navBgColor: resolveColor(lightPairs, '--field'),
    navTxtStyle: 'black',
    bgColor: resolveColor(lightPairs, '--field'),
    bgTxtStyle: 'dark',
    tabBgColor: resolveColor(lightPairs, '--surface'),
    tabBorderStyle: 'white',
    tabFontColor: resolveColor(lightPairs, '--ink-3'),
    tabSelectedColor: resolveColor(lightPairs, '--ink'),
  },
  dark: {
    navBgColor: resolveColor(darkPairs, '--field'),
    navTxtStyle: 'white',
    bgColor: resolveColor(darkPairs, '--field'),
    bgTxtStyle: 'light',
    tabBgColor: resolveColor(darkPairs, '--surface'),
    tabBorderStyle: 'black',
    tabFontColor: resolveColor(darkPairs, '--ink-3'),
    tabSelectedColor: resolveColor(darkPairs, '--ink'),
  },
}

writeFileSync(themeFile, `${JSON.stringify(theme, null, 2)}\n`, 'utf8')
console.log(`[gen-tokens] ${themeFile}（导航栏与 tabBar 色值跟随令牌）`)
