// Writes a pi session whose agent message is a sweep of formula constructs, so the real
// app can be asked to typeset all of them on a device.
//
// Why this exists: a formula the renderer refuses is drawn as its LaTeX source and logged
// (`MathCache`: "formula not typeset, drawn as its source: …"), so the only honest inventory
// of what the renderer can do comes from a device's logcat. Two sweeps found constructs falling
// back to their source, and the second turned out not to be about the renderer at all — the
// *release* APK had lost six constructs to R8 (ARCHITECTURE §12.2), which is why the sweep is
// worth running against the release build and not only the debug one.
//
// It is a device fixture, not a checker: `tools/check-release-math.py` is the check.
//
//   node tools/formula-sweep.mjs sweep.jsonl
//   adb push sweep.jsonl /data/local/tmp/sweep.jsonl
//   adb shell run-as pi.kit.mob cp /data/local/tmp/sweep.jsonl \
//     files/pi-sessions/2026-01-01T00-00-00-000Z_01a0b800-8a0b-72ad-974b-606b971a9070.jsonl
//   # open the app's 历史对话 and tap "公式能力清单", then:
//   adb logcat -d -s PiKit:V | Select-String "not typeset"
//
// `run-as` needs a debuggable build, and the data directory survives `adb install -r`, so
// the fixture can be written with the debug APK and read back with the release one.
import { writeFileSync } from 'node:fs'

//: One construct per paragraph, and the spellings are the ones models actually emit rather
//: than the ones the library documents. `\oiint` and `\oiiint` are the two that are expected
//: to fail (no such glyph in the bundled fonts); everything else failing is a bug.
const CONSTRUCTS = [
  '$\\frac{a}{b}$',
  '$\\dfrac{a}{b}$',
  '$\\tfrac{a}{b}$',
  '$\\cfrac{1}{2}$',
  '$\\binom{n}{k}$',
  '$\\sqrt{3}$',
  '$\\sum_{i=1}^{n} a_i$',
  '$\\sum_j P(A_j)$',
  '$\\int_{0}^{1} f(t)\\,dt$',
  '$\\oint$',
  '$\\oiint$',
  '$\\oiiint$',
  '$\\prod_{k=1}^{n} k$',
  '$\\lim_{x\\to 0} \\frac{\\sin x}{x}$',
  '$\\underset{n\\to\\infty}{\\lim} a_n$',
  '$\\overset{a}{\\to}$',
  '$\\stackrel{?}{=}$',
  '$\\overbrace{a+b}^{n}$',
  '$\\underbrace{c+d}_{m}$',
  '$\\xrightarrow{n\\to\\infty}$',
  '$\\left\\{\\begin{matrix} 1 & 0 \\\\ 0 & 1 \\end{matrix}\\right.$',
  '$\\begin{align} a &= b \\\\ c &= d \\end{align}$',
  '$\\text{中文文字}$',
  '$\\mathrm{d}x$',
  '$\\mathbf{AB}$',
  '$\\mathbb{R}^{n}$',
  '$\\mathcal{L}$',
  '$\\mathfrak{g}$',
  '$\\operatorname{sgn}(x)$',
  '$\\ce{H2O}$',
  '$\\color{red}{x}$',
  '$\\textcolor{blue}{y}$',
  '$\\tag{1} x=1$',
  '$\\label{a}$',
  '$\\boxed{x=1}$',
  '$\\cancel{x}$',
  '$\\hcancel{x}$',
  '$\\vec{v}$ $\\hat{H}$ $\\bar{z}$ $\\dot{x}$',
  '$\\displaystyle\\sum_{i=1}^{n}$',
  '$\\substack{a \\\\ b}$',
  '$\\mid$ $\\nmid$ $\\shortmid$',
  '$P(A_i\\mid B)=\\dfrac{P(A_i)P(B\\mid A_i)}{\\sum_j P(A_j)P(B\\mid A_j)}$',
]

// The id and timestamp decide where the session sorts in 历史对话; a fixed one keeps the run
// reproducible and the `cp` line above valid.
const ID = '01a0b800-8a0b-72ad-974b-606b971a9070'
const START = Date.parse('2026-09-19T05:10:00.000Z')
const at = (offset) => new Date(START + offset).toISOString()

const records = [
  {
    type: 'session',
    version: 3,
    id: ID,
    timestamp: at(0),
    cwd: '/data/user/0/pi.kit.mob/files/home/workspace',
  },
  {
    type: 'model_change',
    id: 'c0000002',
    parentId: null,
    timestamp: at(100),
    provider: 'deepseek',
    modelId: 'deepseek-flash',
  },
  {
    type: 'message',
    id: 'b0000001',
    parentId: 'c0000002',
    timestamp: at(1000),
    message: { role: 'user', content: [{ type: 'text', text: '公式能力清单' }], timestamp: START + 1000 },
  },
  {
    type: 'message',
    id: 'b0000002',
    parentId: 'b0000001',
    timestamp: at(2000),
    message: {
      role: 'assistant',
      content: [{ type: 'text', text: CONSTRUCTS.join('\n\n') }],
      api: 'openai-completions',
      provider: 'deepseek',
      model: 'deepseek-flash',
      usage: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, totalTokens: 0, cost: {} },
      stopReason: 'stop',
      timestamp: START + 2000,
    },
  },
]

const out = process.argv[2] ?? 'formula-sweep.jsonl'
writeFileSync(out, records.map((record) => JSON.stringify(record)).join('\n') + '\n', 'utf8')
console.log(`${out}: ${CONSTRUCTS.length} constructs`)
