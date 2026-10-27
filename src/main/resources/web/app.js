/* TextDiff 前端：无构建 Vue3 应用。
   提交对比 / 作业列表 / 类 Git 三分区结果展示（字符级高亮 + 懒加载）。 */
const { createApp, reactive, ref, computed, onMounted, onBeforeUnmount, watch, nextTick } = Vue;

/* ---------- API ---------- */
async function api(url, opts) {
  const r = await fetch(url, opts);
  const text = await r.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch (e) { data = text; }
  if (!r.ok) {
    const msg = (data && data.detail) ? data.detail : ("请求失败 " + r.status);
    throw new Error(typeof msg === "string" ? msg : JSON.stringify(msg));
  }
  return data;
}
const jpost = (url, body) => api(url, {
  method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
});

/* ---------- 工具：0-based 列数组 <-> 1-based 序列串 ---------- */
function seqToStr(arr) { return (arr || []).map(i => i + 1).join("/"); }

/* ---------- 工具：时间戳(秒) -> 本地时间字符串 ---------- */
function fmtTime(ts) {
  if (!ts) return "—";
  const d = new Date(ts * 1000);
  const p = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

/* ---------- 工具：秒数 -> 耗时可读文本（37秒 / 8分03秒 / 1时05分30秒） ---------- */
function fmtDur(sec) {
  if (sec == null || sec < 0 || !isFinite(sec)) return "—";
  sec = Math.round(sec);
  if (sec < 60) return sec + "秒";
  const m = Math.floor(sec / 60), s = sec % 60;
  if (m < 60) return m + "分" + String(s).padStart(2, "0") + "秒";
  const h = Math.floor(m / 60);
  return h + "时" + String(m % 60).padStart(2, "0") + "分" + String(s).padStart(2, "0") + "秒";
}

/* ---------- 工具：自定义来源名（替代默认 A/B），贯穿结果页/列表/导出 ---------- */
function srcA(cfg) { return (cfg && cfg.source_a) ? cfg.source_a : "A"; }
function srcB(cfg) { return (cfg && cfg.source_b) ? cfg.source_b : "B"; }

/* ---------- 工具：数量偏离总体评判（与导出 Excel 的 DiffGrade 同规则）----------
   偏离率 = |totalA-totalB| / ((totalA+totalB)/2)；≥50% 严重问题、≥20% 差异明显、≥10% 保持关注 */
function gradeOf(ta, tb) {
  ta = ta || 0; tb = tb || 0;
  const d = Math.abs(ta - tb), sum = ta + tb;
  if (!sum) return { label: "正常", cls: "ok", pct: 0, diff: 0 };
  const pct = Math.round(d * 2000 / sum) / 10; // 相对总和一半的百分比
  if (pct >= 50) return { label: "严重问题", cls: "severe", pct, diff: d };
  if (pct >= 20) return { label: "差异明显", cls: "mark", pct, diff: d };
  if (pct >= 10) return { label: "保持关注", cls: "watch", pct, diff: d };
  return { label: "正常", cls: "ok", pct, diff: d };
}

/* ---------- 全局对话框与通知：应用内统一弹层，替代原生 prompt/confirm/alert ----------
   askConfirm(message, {title, danger}) → Promise<boolean>
   askInput({title, message, value, placeholder}) → Promise<string|null>（取消返回 null）
   notify(message, type='ok') → 顶部 toast，3.5s 自动消失 */
const dialogState = reactive({ open: false, mode: "confirm", title: "", message: "", value: "",
                               placeholder: "", danger: false, okText: "确定", _resolve: null });
const toastState = reactive({ open: false, message: "", type: "ok", _timer: null });

function askConfirm(message, opts) {
  opts = opts || {};
  return new Promise(resolve => {
    Object.assign(dialogState, { open: true, mode: "confirm", title: opts.title || "请确认",
      message, value: "", placeholder: "", danger: !!opts.danger,
      okText: opts.okText || (opts.danger ? "删除" : "确定"), _resolve: resolve });
  });
}
function askInput(opts) {
  return new Promise(resolve => {
    Object.assign(dialogState, { open: true, mode: "input", title: opts.title || "输入",
      message: opts.message || "", value: opts.value || "",
      placeholder: opts.placeholder || "", danger: false, okText: opts.okText || "确定",
      _resolve: resolve });
  });
}
function settleDialog(result) {
  if (!dialogState.open) return;
  dialogState.open = false;
  const r = dialogState._resolve;
  dialogState._resolve = null;
  if (r) r(result);
}
function notify(message, type) {
  toastState.message = message || "";
  toastState.type = type || "ok";
  toastState.open = true;
  if (toastState._timer) clearTimeout(toastState._timer);
  toastState._timer = setTimeout(() => { toastState.open = false; }, 3500);
}

/* AppDialog：全局确认/输入模态（Esc 取消、Enter 确认，输入模式自动聚焦全选） */
const AppDialog = {
  setup() {
    const inputEl = ref(null);
    const d = dialogState;
    function onKey(e) {
      if (!d.open) return;
      if (e.key === "Escape") { e.preventDefault(); settleDialog(d.mode === "input" ? null : false); }
      else if (e.key === "Enter") {
        e.preventDefault();
        settleDialog(d.mode === "input" ? d.value : true);
      }
    }
    watch(() => d.open, (open) => {
      if (open) {
        window.addEventListener("keydown", onKey, true);
        if (d.mode === "input") nextTick(() => {
          if (inputEl.value) { inputEl.value.focus(); inputEl.value.select(); }
        });
      } else {
        window.removeEventListener("keydown", onKey, true);
      }
    });
    return { d, inputEl, ok: () => settleDialog(d.mode === "input" ? d.value : true),
             cancel: () => settleDialog(d.mode === "input" ? null : false) };
  },
  template: `
  <div class="modal-mask" v-if="d.open" @click.self="cancel">
    <div class="modal modal-sm" role="dialog" aria-modal="true">
      <div class="modal-head">{{ d.title }}
        <button class="modal-x" @click="cancel" title="关闭" aria-label="关闭">✕</button></div>
      <div class="modal-body">
        <div class="dialog-msg" v-if="d.message">{{ d.message }}</div>
        <input v-if="d.mode === 'input'" ref="inputEl" v-model="d.value" class="dialog-input"
               :placeholder="d.placeholder" spellcheck="false"
               @keydown.enter.prevent="ok" />
      </div>
      <div class="modal-foot">
        <button class="btn ghost dialog-btn" @click="cancel">取消</button>
        <button class="btn dialog-btn" :class="{ danger: d.danger }" @click="ok">{{ d.okText }}</button>
      </div>
    </div>
  </div>`,
};

/* AppToast：全局轻提示（成功/失败） */
const AppToast = {
  setup() { return { t: toastState }; },
  template: `
  <transition name="toast">
    <div v-if="t.open" class="toast toast-app" :class="t.type">{{ t.message }}</div>
  </transition>`,
};

/* PageHeader：统一页头（返回 + 页标题 + 徽标插槽 + 页级操作插槽 + 可选副标题行）。
   全站详情页/功能页共用，替代各自重复的「← 返回…」按钮行，建立一致的页面身份层级。 */
const PageHeader = {
  props: { backText: String, title: String, sub: String },
  emits: ["back"],
  template: `
  <div class="page-head">
    <button v-if="backText" class="page-back" @click="$emit('back')">← {{ backText }}</button>
    <div class="page-title">{{ title }}<slot name="meta"></slot></div>
    <div class="spacer"></div>
    <div class="page-actions"><slot name="actions"></slot></div>
    <div class="page-sub" v-if="sub">{{ sub }}</div>
  </div>`,
};

/* ---------- 轻量 Markdown 渲染（无依赖、先转义后渲染，防 XSS） ---------- */
function mdEscape(s) {
  return String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}
function mdInline(s) {
  // s 已 HTML 转义；以下仅注入我们自己的安全标签
  s = s.replace(/`([^`]+)`/g, (m, c) => "<code>" + c + "</code>");
  s = s.replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>");
  s = s.replace(/__([^_]+)__/g, "<strong>$1</strong>");
  s = s.replace(/(^|[^*])\*([^*\n]+)\*/g, "$1<em>$2</em>");
  s = s.replace(/\[([^\]]+)\]\((https?:\/\/[^)\s]+)\)/g,
                '<a href="$2" target="_blank" rel="noopener noreferrer">$1</a>');
  return s;
}
function renderMarkdown(src) {
  if (!src) return "";
  const lines = String(src).replace(/\r\n/g, "\n").split("\n");
  let html = "", i = 0, inCode = false, codeBuf = [], listType = null, listBuf = [];
  const flushList = () => {
    if (listType) { html += "<" + listType + ">" + listBuf.join("") + "</" + listType + ">"; listType = null; listBuf = []; }
  };
  while (i < lines.length) {
    const line = lines[i];
    if (/^\s*```/.test(line)) {
      if (!inCode) { flushList(); inCode = true; codeBuf = []; }
      else { html += '<pre class="md-code">' + codeBuf.map(mdEscape).join("\n") + "</pre>"; inCode = false; }
      i++; continue;
    }
    if (inCode) { codeBuf.push(line); i++; continue; }
    const esc = mdEscape(line);
    const h = esc.match(/^(#{1,6})\s+(.*)$/);
    if (h) { flushList(); const n = h[1].length; html += "<h" + n + ' class="md-h">' + mdInline(h[2]) + "</h" + n + ">"; i++; continue; }
    if (/^\s*([-*_])\1\1+\s*$/.test(line)) { flushList(); html += "<hr/>"; i++; continue; }
    const ul = esc.match(/^\s*[-*+]\s+(.*)$/);
    if (ul) { if (listType !== "ul") { flushList(); listType = "ul"; } listBuf.push("<li>" + mdInline(ul[1]) + "</li>"); i++; continue; }
    const ol = esc.match(/^\s*\d+\.\s+(.*)$/);
    if (ol) { if (listType !== "ol") { flushList(); listType = "ol"; } listBuf.push("<li>" + mdInline(ol[1]) + "</li>"); i++; continue; }
    if (/^\s*$/.test(line)) { flushList(); i++; continue; }
    flushList();
    const para = [esc]; i++;
    while (i < lines.length && !/^\s*$/.test(lines[i]) && !/^\s*```/.test(lines[i])
           && !/^#{1,6}\s+/.test(lines[i]) && !/^\s*[-*+]\s+/.test(lines[i]) && !/^\s*\d+\.\s+/.test(lines[i])) {
      para.push(mdEscape(lines[i])); i++;
    }
    html += "<p>" + para.map(mdInline).join("<br/>") + "</p>";
  }
  if (inCode) html += '<pre class="md-code">' + codeBuf.map(mdEscape).join("\n") + "</pre>";
  flushList();
  return html;
}

/* ---------- 主流大模型 provider 预设（OpenAI 兼容接口） ---------- */
const AI_PROVIDERS = [
  { id: "anthropic", name: "Anthropic Claude（原生）", base_url: "https://api.anthropic.com", protocol: "anthropic",
    models: ["claude-sonnet-4-5", "claude-opus-4-1", "claude-3-7-sonnet-latest", "claude-3-5-haiku-latest"] },
  { id: "anthropic_oai", name: "Anthropic Claude（OpenAI 兼容）", base_url: "https://api.anthropic.com/v1", protocol: "openai",
    models: ["claude-sonnet-4-5", "claude-opus-4-1", "claude-3-7-sonnet-latest", "claude-3-5-haiku-latest"] },
  { id: "openai", name: "OpenAI", base_url: "https://api.openai.com/v1",
    models: ["gpt-4o-mini", "gpt-4o", "gpt-4.1", "gpt-4.1-mini", "o3-mini", "o4-mini"] },
  { id: "deepseek", name: "DeepSeek", base_url: "https://api.deepseek.com/v1",
    models: ["deepseek-chat", "deepseek-reasoner"] },
  { id: "qwen", name: "通义千问 (DashScope)", base_url: "https://dashscope.aliyuncs.com/compatible-mode/v1",
    models: ["qwen-max", "qwen-plus", "qwen-turbo", "qwen2.5-72b-instruct"] },
  { id: "zhipu", name: "智谱 GLM（glm-4-flash 免费）", base_url: "https://open.bigmodel.cn/api/paas/v4",
    models: ["glm-4-flash", "glm-4-flashx", "glm-4-air", "glm-4-plus", "glm-4"] },
  { id: "siliconflow", name: "硅基流动 SiliconFlow（含免费档）", base_url: "https://api.siliconflow.cn/v1",
    models: ["Qwen/Qwen2.5-7B-Instruct", "THUDM/glm-4-9b-chat", "Qwen/Qwen2.5-72B-Instruct", "deepseek-ai/DeepSeek-V3"] },
  { id: "groq", name: "Groq（免费档·需境外网络）", base_url: "https://api.groq.com/openai/v1",
    models: ["llama-3.3-70b-versatile", "llama-3.1-8b-instant", "gemma2-9b-it"] },
  { id: "gemini", name: "Google Gemini（免费档·需境外网络）", base_url: "https://generativelanguage.googleapis.com/v1beta/openai",
    models: ["gemini-2.0-flash", "gemini-1.5-flash", "gemini-1.5-pro"] },
  { id: "openrouter", name: "OpenRouter（含 :free 模型·需境外网络）", base_url: "https://openrouter.ai/api/v1",
    models: ["deepseek/deepseek-chat-v3:free", "meta-llama/llama-3.3-70b-instruct:free", "google/gemini-2.0-flash-exp:free"] },
  { id: "moonshot", name: "Moonshot (Kimi)", base_url: "https://api.moonshot.cn/v1",
    models: ["moonshot-v1-8k", "moonshot-v1-32k", "moonshot-v1-128k"] },
  { id: "baichuan", name: "百川", base_url: "https://api.baichuan-ai.com/v1",
    models: ["Baichuan4", "Baichuan3-Turbo"] },
  { id: "minimax", name: "MiniMax", base_url: "https://api.minimax.chat/v1",
    models: ["abab6.5s-chat", "abab6.5-chat"] },
  { id: "ollama", name: "Ollama（本地·完全免费）", base_url: "http://localhost:11434/v1",
    models: ["qwen2.5", "deepseek-r1", "llama3.1", "glm4", "gemma2"] },
  { id: "custom", name: "自定义", base_url: "", models: [] },
];

/* ---------- 字符级差异（前端实时计算，后端不存 opcodes） ---------- */
// 基于 LCS 的 opcode 生成，输出 difflib 风格 [tag,i1,i2,j1,j2]；
// 仅对当前展示的差异单元格计算，单元格通常很短，开销可忽略。
function computeOpcodes(a, b) {
  const n = a.length, m = b.length;
  if (n === 0 && m === 0) return [["equal", 0, 0, 0, 0]];
  // 超长单元格退化为前后缀对齐，避免 O(n*m) 过大
  if (n * m > 40000) {
    let p = 0; while (p < n && p < m && a[p] === b[p]) p++;
    let s = 0; while (s < n - p && s < m - p && a[n - 1 - s] === b[m - 1 - s]) s++;
    const ops = [];
    if (p) ops.push(["equal", 0, p, 0, p]);
    if (n - p - s > 0) ops.push(["delete", p, n - s, p, p]);
    if (m - p - s > 0) ops.push(["insert", n - s, n - s, p, m - s]);
    if (s) ops.push(["equal", n - s, n, m - s, m]);
    return ops.length ? ops : [["equal", 0, n, 0, m]];
  }
  const dp = [];
  for (let i = 0; i <= n; i++) dp.push(new Int32Array(m + 1));
  for (let i = n - 1; i >= 0; i--)
    for (let j = m - 1; j >= 0; j--)
      dp[i][j] = a[i] === b[j] ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
  const raw = [];
  let i = 0, j = 0;
  const push = (tag, i1, i2, j1, j2) => {
    const last = raw[raw.length - 1];
    if (last && last[0] === tag) { last[2] = i2; last[4] = j2; }
    else raw.push([tag, i1, i2, j1, j2]);
  };
  while (i < n && j < m) {
    if (a[i] === b[j]) { push("equal", i, i + 1, j, j + 1); i++; j++; }
    else if (dp[i + 1][j] >= dp[i][j + 1]) { push("delete", i, i + 1, j, j); i++; }
    else { push("insert", i, i, j, j + 1); j++; }
  }
  if (i < n) push("delete", i, n, j, j);
  if (j < m) push("insert", i, i, j, m);
  return raw.length ? raw : [["equal", 0, n, 0, m]];
}

/* ---------- 字符级差异分段 ---------- */
// side: 'a' 用 i 区间(删除/替换/相等)；'b' 用 j 区间(插入/替换/相等)
function segments(value, opcodes, side) {
  if (!opcodes || !opcodes.length) return [{ text: value, cls: "" }];
  const out = [];
  for (const op of opcodes) {
    const [tag, i1, i2, j1, j2] = op;
    if (tag === "equal") {
      out.push({ text: value.slice(side === "a" ? i1 : j1, side === "a" ? i2 : j2), cls: "" });
    } else if (tag === "replace") {
      const t = side === "a" ? value.slice(i1, i2) : value.slice(j1, j2);
      if (t) out.push({ text: t, cls: "seg-rep" });
    } else if (tag === "delete") {
      if (side === "a") { const t = value.slice(i1, i2); if (t) out.push({ text: t, cls: "seg-del" }); }
    } else if (tag === "insert") {
      if (side === "b") { const t = value.slice(j1, j2); if (t) out.push({ text: t, cls: "seg-ins" }); }
    }
  }
  return out.length ? out : [{ text: "", cls: "" }];
}

/* ---------- 序列输入框（自动换行 + 列号-列名 tips） ---------- */
const SeqField = {
  props: ["label", "modelValue", "columnNames", "readonly", "placeholder"],
  emits: ["update:modelValue"],
  setup(props, { emit }) {
    const show = ref(false);
    const parsed = computed(() => {
      const names = props.columnNames || [];
      const out = [];
      for (const tok of String(props.modelValue || "").split("/")) {
        const t = tok.trim();
        if (!t) continue;
        const n = parseInt(t, 10);
        if (!n) continue;
        out.push({ col: n, name: (names[n - 1] || "") });
      }
      return out;
    });
    function autosize(el) { if (el) { el.style.height = "auto"; el.style.height = Math.min(el.scrollHeight, 160) + "px"; } }
    function onInput(e) { emit("update:modelValue", e.target.value); autosize(e.target); }
    return { show, parsed, onInput, autosize };
  },
  template: `
  <div class="field mono seq-field">
    <label>{{ label }}
      <span class="tips-ico" @mouseenter="show=true" @mouseleave="show=false" tabindex="0">ⓘ
        <div class="tips-pop" v-if="show" @mouseenter="show=true">
          <div class="tips-title">列号 — 列名</div>
          <div class="tips-empty" v-if="!parsed.length">（填入列号后在此解析）</div>
          <div class="tips-row" v-for="p in parsed" :key="p.col">
            <span class="tc">{{ p.col }}</span><span class="tn">{{ p.name || '（无列名映射）' }}</span>
          </div>
        </div>
      </span>
    </label>
    <textarea rows="1" wrap="soft" :value="modelValue" :readonly="readonly" :placeholder="placeholder"
              @input="onInput" @focus="e=>autosize(e.target)" ref="ta"></textarea>
  </div>`,
};

/* ---------- 记录行组件 ---------- */
const RecordRow = {
  props: ["row", "skipSet", "columnNames", "sourceA", "sourceB", "forceOpen", "forceCollapse", "defaultOpen", "note", "supportsNote", "dynCols"],
  emits: ["save-note"],
  setup(props, { emit }) {
    const localOpen = ref(!!props.defaultOpen);
    const isOpen = computed(() => localOpen.value || props.forceOpen);
    const toggle = () => { localOpen.value = !localOpen.value; };
    // 收起全部信号：父级 bump forceCollapse 时强制收起本行
    watch(() => props.forceCollapse, () => { localOpen.value = false; });
    const colName = (i) => (props.columnNames && props.columnNames[i]) ? props.columnNames[i] : "";

    // ---- CTIS 动态栏位（虚拟子列）----
    const VBASE = 1000000;
    const dynDescByIndex = computed(() => {
      const m = {};
      for (const d of (props.dynCols || [])) m[d.index] = d;
      return m;
    });
    const dynParentSet = computed(() => new Set((props.dynCols || []).map(d => d.parent_col)));
    const isDynCol = (i) => dynParentSet.value.has(i) && (props.row.dyn || []).some(v => v.c === i);
    const dynSubs = (i) => (props.row.dyn || []).filter(v => v.c === i);
    function dynDesc(idx) { return dynDescByIndex.value[idx]; }
    function dynSubLabel(idx) {
      const d = dynDesc(idx);
      return d ? (d.parent_label + '-' + d.sub) : String(idx);
    }
    function dynTitle(idx) {
      const d = dynDesc(idx);
      if (!d) return '';
      const parts = ['字段名称：' + (d.dyn_name || '—')];
      parts.push('数据项名称：' + (d.item_name || d.sub));
      return parts.join('\n');
    }
    // 子栏位字符级高亮：差异子项按 a/b 对计算 opcodes，相等子项原样
    function dynSegs(v, side) {
      if (!diffSet.value.has(v.idx)) return [{ text: (side === 'a' ? v.a : v.b) || "", cls: "" }];
      const a = v.a || "", b = v.b || "";
      return segments(side === "a" ? a : b, computeOpcodes(a, b), side);
    }
    const dynDiffOf = (i) => dynSubs(i).some(v => diffSet.value.has(v.idx));
    // 差异列标签：虚拟子列 → 107-1B；真实列 → N·名称
    function diffColLabel(c) {
      if (c >= VBASE) return dynSubLabel(c);
      return colName(c) ? (c + 1) + '·' + colName(c) : String(c + 1);
    }

    // ---- 记录评议(note) ----
    const noteEditing = ref(false);
    const noteDraft = ref("");
    const hasNote = computed(() => !!(props.note && String(props.note).trim()));
    function openNote() { noteDraft.value = props.note || ""; noteEditing.value = true; }
    function cancel() { noteEditing.value = false; }
    function saveNote() { emit("save-note", { key: props.row.key, note: noteDraft.value }); noteEditing.value = false; }
    function delNote() { emit("save-note", { key: props.row.key, note: "" }); noteEditing.value = false; }

    const diffSet = computed(() => new Set(props.row.diff_cols || []));

    const present = computed(() => props.row.a_cols || props.row.b_cols || []);
    const maxLen = computed(() => Math.max(
      (props.row.a_cols || []).length, (props.row.b_cols || []).length, present.value.length
    ));

    // 某侧的列内容；equal 行 b 隐含等于 a
    function sideCols(side) {
      const r = props.row;
      if (side === "a") {
        if (r.a_cols) return r.a_cols;
        if (r.status === "unmatched_b") return null; // A 缺失
        return r.b_cols; // 理论不发生
      } else {
        if (r.b_cols) return r.b_cols;
        if (r.status === "equal") return r.a_cols;     // 隐含相等
        if (r.status === "unmatched_a") return null;   // B 缺失
        return null;
      }
    }

    function lineClass(side, i) {
      const cls = [];
      if (props.skipSet.has(i)) cls.push("skip");
      if (diffSet.value.has(i) || (isDynCol(i) && dynDiffOf(i))) cls.push("diff");
      return cls;
    }

    // 返回某侧某列的分段（用于字符级高亮）；差异列实时计算 opcodes
    function cellSegs(side, i, val) {
      if (diffSet.value.has(i)) {
        const a = props.row.a_cols ? (props.row.a_cols[i] || "") : "";
        const b = props.row.b_cols ? (props.row.b_cols[i] || "") : "";
        return segments(side === "a" ? a : b, computeOpcodes(a, b), side);
      }
      return [{ text: val, cls: "" }];
    }

    const hasNames = computed(() => Array.isArray(props.columnNames) && props.columnNames.some(n => n));
    return { isOpen, toggle, sideCols, lineClass, cellSegs, maxLen, colName, hasNames,
             isDynCol, dynSubs, dynSegs, dynSubLabel, dynTitle, dynDiffOf, diffColLabel,
             noteEditing, noteDraft, hasNote, openNote, cancel, saveNote, delNote };
  },
  template: `
  <div class="rec" :class="['s-' + row.status, isOpen ? 'open' : '', hasNote ? 'has-note' : '']">
    <div class="rec-head" @click="toggle">
      <span class="caret">▶</span>
      <span class="key">{{ keyText(row.key) }}</span>
      <span class="tag" :class="row.status">{{ statusLabel(row.status) }}</span>
      <span class="meta" v-if="row.diff_cols && row.diff_cols.length">差异列: {{ row.diff_cols.map(diffColLabel).join(', ') }}</span>
      <span class="rec-fill"></span>
      <button v-if="supportsNote" class="note-mark" :class="{ has: hasNote }" @click.stop="openNote"
              :title="hasNote ? '查看/编辑评议' : '添加评议'">{{ hasNote ? '📝 已评议' : '✏️ 评议' }}</button>
    </div>
    <!-- 评议编辑器 / 展示（独立于明细展开） -->
    <div class="note-editor" v-if="supportsNote && noteEditing" @click.stop>
      <textarea v-model="noteDraft" rows="2" placeholder="对该记录的评议 / 分析结论…"></textarea>
      <div class="note-actions">
        <button class="mini-btn" @click="saveNote">保存</button>
        <button class="mini-btn danger" v-if="hasNote" @click="delNote">删除</button>
        <button class="mini-btn" @click="cancel">取消</button>
      </div>
    </div>
    <div class="note-show" v-else-if="supportsNote && hasNote" @click.stop="openNote" title="点击编辑评议">📝 {{ note }}</div>
    <div class="rec-body" v-if="isOpen">
      <!-- 行主序网格：同一行的 字段/A/B 三格处于同一网格行，超长内容在格内横向滚动且整行等高对齐 -->
      <div class="recgrid" :class="{ 'with-names': hasNames }">
        <div class="ghdr field" v-if="hasNames">字段</div>
        <div class="ghdr" :class="sideClass('a')">{{ sourceA || 'A' }}<span class="absent-note" v-if="!sideCols('a')"> · {{ sourceA || 'A' }} 中无此键</span></div>
        <div class="ghdr" :class="sideClass('b')">{{ sourceB || 'B' }}<span class="absent-note" v-if="!sideCols('b')"> · {{ sourceB || 'B' }} 中无此键</span></div>
        <template v-for="i in maxLen" :key="i">
          <div class="gcell field" v-if="hasNames" :class="{ diff: lineClass('a', i-1).includes('diff') }">
            <span class="ci">{{ i }}</span><span class="nm">{{ colName(i-1) }}</span>
            <span class="dyn-mark" v-if="isDynCol(i-1)" title="CTIS 动态栏位：已按 ~~ 编号展开为子栏位">⧉</span>
          </div>
          <div class="gcell" :class="[lineClass('a', i-1), sideClass('a')]">
            <template v-if="sideCols('a')">
              <span class="ci">{{ i }}</span>
              <div class="dynsubs" v-if="isDynCol(i-1)">
                <div v-for="v in dynSubs(i-1)" :key="v.idx" class="dynsub" :class="{ diff: row.diff_cols.includes(v.idx) }">
                  <span class="dyn-chip" :title="dynTitle(v.idx)">{{ dynSubLabel(v.idx) }}</span>
                  <span class="cv"><span v-for="(s,si) in dynSegs(v,'a')" :key="si" :class="s.cls">{{ s.text }}</span></span>
                </div>
              </div>
              <span class="cv" v-else><span v-for="(s,si) in cellSegs('a', i-1, (sideCols('a')[i-1]||''))" :key="si" :class="s.cls">{{ s.text }}</span></span>
            </template>
          </div>
          <div class="gcell" :class="[lineClass('b', i-1), sideClass('b')]">
            <template v-if="sideCols('b')">
              <span class="ci">{{ i }}</span>
              <div class="dynsubs" v-if="isDynCol(i-1)">
                <div v-for="v in dynSubs(i-1)" :key="v.idx" class="dynsub" :class="{ diff: row.diff_cols.includes(v.idx) }">
                  <span class="dyn-chip" :title="dynTitle(v.idx)">{{ dynSubLabel(v.idx) }}</span>
                  <span class="cv"><span v-for="(s,si) in dynSegs(v,'b')" :key="si" :class="s.cls">{{ s.text }}</span></span>
                </div>
              </div>
              <span class="cv" v-else><span v-for="(s,si) in cellSegs('b', i-1, (sideCols('b')[i-1]||''))" :key="si" :class="s.cls">{{ s.text }}</span></span>
            </template>
          </div>
        </template>
      </div>
    </div>
  </div>`,
  methods: {
    keyText(k) {
      // 组合主键分隔符（0x1F）在导出 CSV 与界面统一显示为 ':'（导出层 CsvExporter.displayKey 同规则）
      return (k || "").split(String.fromCharCode(31)).join(":") || "(空键)";
    },
    statusLabel(s) {
      const a = this.sourceA || "A", b = this.sourceB || "B";
      return { equal: "完全匹配", diff: "有差异", unmatched_a: "仅" + a + "存在", unmatched_b: "仅" + b + "存在" }[s] || s;
    },
    sideClass(side) {
      const r = this.row;
      if (r.status === "unmatched_a") return side === "a" ? "present" : "absent";
      if (r.status === "unmatched_b") return side === "b" ? "present" : "absent";
      return "";
    },
  },
};

/* ---------- 分区面板组件 ---------- */
const ZonePanel = {
  components: { RecordRow },
  props: ["jobId", "zone", "label", "total", "skipSet", "columnNames", "sourceA", "sourceB",
          "openByDefault", "recordsOpen", "pageSize", "exportUrl", "exportLabel", "dynCols",
          "supportsNote", "notes", "noteCount", "extQuery", "extQueryNonce", "notesNonce"],
  emits: ["save-note"],
  setup(props, { emit }) {
    const open = ref(!!props.openByDefault);
    const rows = ref([]);
    const loading = ref(false);
    const allOpen = ref(false);
    const collapseNonce = ref(0);   // bump 触发各行强制收起
    const pageSize = ref(props.pageSize ? +props.pageSize : 10);
    const page = ref(1);
    const q = ref("");
    const noteFilter = ref("");      // "" | "has" | "none"
    const filteredTotal = ref(0);
    const needFirst = ref(false);    // 过滤刚触发、尚未取得首页（total 未知，不应被守卫拦截）

    // 是否处于过滤态（按键值搜索 或 note 筛选）
    const active = computed(() => !!q.value.trim() || noteFilter.value !== "");
    const total = computed(() => active.value ? filteredTotal.value : props.total);
    const fullTotal = computed(() => props.total || 0);
    const totalPages = computed(() => Math.max(1, Math.ceil((total.value || 0) / pageSize.value)));
    // note 计数（来自父级按分区统计）
    const hasNoteCount = computed(() => props.noteCount || 0);
    const noNoteCount = computed(() => Math.max(0, (props.total || 0) - hasNoteCount.value));

    // 取第 p 页（替换当前页，非追加）；p 越界自动夹到 [1, totalPages]
    async function loadPage(p) {
      if (loading.value) return;
      const tp = totalPages.value;
      const np = Math.min(Math.max(1, (p | 0) || 1), tp);
      loading.value = true;
      try {
        const off = (np - 1) * pageSize.value;
        let url = `/api/jobs/${props.jobId}/result?zone=${props.zone}&offset=${off}&limit=${pageSize.value}`;
        if (q.value.trim()) url += `&q=${encodeURIComponent(q.value)}`;
        if (noteFilter.value) url += `&note=${noteFilter.value}`;
        const res = await api(url);
        if (active.value) filteredTotal.value = res.total;
        rows.value = res.rows;            // 替换当前页
        page.value = np;
        allOpen.value = false; collapseNonce.value++;   // 新页默认折叠
        needFirst.value = false;
      } finally { loading.value = false; }
    }
    // 过滤条件变化时重置到第 1 页重取
    async function reload() {
      rows.value = []; filteredTotal.value = 0; page.value = 1;
      open.value = true; needFirst.value = active.value;
      await loadPage(1);
    }
    function prevPage() { if (page.value > 1) loadPage(page.value - 1); }
    function nextPage() { if (page.value < totalPages.value) loadPage(page.value + 1); }
    function gotoInput(v) { const n = parseInt(v, 10); if (!isNaN(n)) loadPage(n); }
    function setPageSize(n) { pageSize.value = n; loadPage(1); }
    async function ensureOpen() {
      if (!open.value) { open.value = true; if (rows.value.length === 0 && total.value > 0) await loadPage(1); }
      else { open.value = false; }
    }
    // 展开本页 ↔ 收起本页（仅作用于当前页已加载记录）
    function toggleExpand() {
      if (allOpen.value) { allOpen.value = false; collapseNonce.value++; return; }
      allOpen.value = true;
    }
    async function doSearch() { await reload(); }
    async function clearSearch() { q.value = ""; await reload(); }
    async function applyNoteFilter(f) { if (noteFilter.value === f) return; noteFilter.value = f; await reload(); }

    // 全局搜索框驱动：父级 bump extQueryNonce → 同步本区 q 并重检索
    watch(() => props.extQueryNonce, () => {
      q.value = (props.extQuery || "").trim();
      reload();
    });
    // note 变更后（保存/批量），若正按 note 过滤则重载以反映成员变化
    watch(() => props.notesNonce, () => { if (noteFilter.value) reload(); });

    if (props.openByDefault && props.total > 0) loadPage(1);

    const noteOf = (key) => ((props.notes && props.notes[key]) ? props.notes[key].note : "");
    function onSaveNote(payload) { emit("save-note", { ...payload, zone: props.zone }); }

    return { open, rows, loading, allOpen, collapseNonce, ensureOpen, toggleExpand,
             page, pageSize, totalPages, loadPage, prevPage, nextPage, gotoInput, setPageSize,
             q, active, noteFilter, total, fullTotal, doSearch, clearSearch, applyNoteFilter,
             hasNoteCount, noNoteCount, noteOf, onSaveNote };
  },
  template: `
  <div class="zone" :class="zone">
    <div class="zone-head" @click="ensureOpen">
      <span class="bar"></span>
      <span>{{ label }}</span>
      <span class="count">{{ rows.length }} / {{ total }}{{ active ? ' (筛选)' : '' }}</span>
      <div class="actions" @click.stop>
        <button class="mini-btn" @click="toggleExpand">{{ allOpen ? '收起本页' : '展开本页' }}</button>
        <a v-if="exportUrl" class="mini-btn" :href="exportUrl">{{ exportLabel || '导出 Excel' }}</a>
      </div>
    </div>
    <!-- 分区工具行：搜索 / 评议筛选 / 分页 / 页大小（独立于分区头，不再拥挤） -->
    <div class="zone-toolbar" v-if="open" @click.stop>
      <input class="zone-search" v-model="q" @keyup.enter="doSearch" placeholder="按键值搜索" />
      <button class="mini-btn" @click="doSearch">搜索</button>
      <button class="mini-btn" v-if="q.trim()" @click="clearSearch">清除</button>
      <template v-if="supportsNote">
        <button :class="['mini-btn', noteFilter==='' ? 'active' : '']" @click="applyNoteFilter('')">全部 {{ fullTotal }}</button>
        <button :class="['mini-btn', noteFilter==='has' ? 'active' : '']" @click="applyNoteFilter('has')">📝 已评议 {{ hasNoteCount }}</button>
        <button :class="['mini-btn', noteFilter==='none' ? 'active' : '']" @click="applyNoteFilter('none')">未评议 {{ noNoteCount }}</button>
      </template>
      <span class="zt-spacer"></span>
      <span class="zone-pager" v-if="totalPages > 1">
        <button class="mini-btn" :disabled="page<=1" @click="prevPage">‹ 上一页</button>
        第 <input class="pager-input" :value="page" @keyup.enter="gotoInput($event.target.value)" /> / {{ totalPages }} 页
        <button class="mini-btn" :disabled="page>=totalPages" @click="nextPage">下一页 ›</button>
      </span>
      <select class="pager-size" :value="pageSize" @change="setPageSize(+$event.target.value)">
        <option :value="10">10/页</option>
        <option :value="50">50/页</option>
      </select>
    </div>
    <div class="zone-body" v-if="open">
      <div class="empty" v-if="total === 0">{{ active ? '无匹配记录' : '无记录' }}</div>
      <RecordRow v-for="(r, idx) in rows" :key="idx" :row="r" :skip-set="skipSet" :column-names="columnNames"
                 :source-a="sourceA" :source-b="sourceB" :force-open="allOpen" :force-collapse="collapseNonce"
                 :default-open="recordsOpen" :supports-note="supportsNote" :note="noteOf(r.key)" :dyn-cols="dynCols" @save-note="onSaveNote" />
      <div class="load-more" v-if="loading">
        <span class="muted">加载中…</span>
      </div>
    </div>
  </div>`,
};

/* ---------- 列表分页（lazy load）：大列表按页切片渲染，避免超多条目一次铺满 DOM ---------- */
function usePager(listGetter, defaultSize) {
  const page = ref(1);
  const size = ref(defaultSize);
  const total = computed(() => listGetter().length);
  const pages = computed(() => Math.max(1, Math.ceil(total.value / size.value)));
  const paged = computed(() => listGetter().slice((page.value - 1) * size.value, page.value * size.value));
  function go(n) { page.value = Math.min(Math.max(1, (n | 0) || 1), pages.value); }
  function resize(s) { size.value = s; page.value = 1; }
  function reset() { page.value = 1; }
  // reactive 包装：嵌套 ref 在模板中自动解包（pg.paged 直接是数组、pg.page 是数值）
  return reactive({ page, size, total, pages, paged, go, resize, reset });
}
/* 分页控件（列表卡片头部；复用 zone-pager 样式） */
const ListPager = {
  props: { page: Number, pages: Number, total: Number, size: Number },
  emits: ["go", "resize"],
  template: `
  <span class="zone-pager" @click.stop>
    <button class="mini-btn" :disabled="page<=1" @click="$emit('go', page-1)">‹ 上一页</button>
    <span>第 <input class="pager-input" :value="page"
        @keyup.enter="$emit('go', parseInt($event.target.value) || 1)" /> / {{ pages }} 页 · 共 {{ total }} 条</span>
    <button class="mini-btn" :disabled="page>=pages" @click="$emit('go', page+1)">下一页 ›</button>
    <select class="pager-size" :value="size" @change="$emit('resize', +$event.target.value)" title="每页条数">
      <option :value="50">50/页</option>
      <option :value="100">100/页</option>
      <option :value="200">200/页</option>
    </select>
  </span>`,
};

/* ---------- 关注字段监测（DISCARD_COLS）表 + 卡片 + 批次情况总结页 ---------- */
/* 监测取值表：批次卡片 / 单文件结果卡片 / 批次总结页共用（自带 100/页分页）。 */
const DiscardTable = {
  props: { rows: { type: Array, default: () => [] }, srcA: { type: String, default: "A" }, srcB: { type: String, default: "B" } },
  setup(props) {
    const pg = usePager(() => props.rows || [], 100);
    function vals(r, side) {
      const src = side === "a" ? props.srcA : props.srcB;
      if (r.file_missing) return "（" + src + " 原文不可采集" + (r.error ? "：" + r.error : "") + "）";
      const list = side === "a" ? r.values_a : r.values_b;
      let s = !list || !list.length ? "（无数据行）" : list.map(v => !v ? "（空）" : v).join("、");
      if (side === "a" ? r.truncated_a : r.truncated_b) s += "…（超出上限已截断）";
      return s;
    }
    // 是否匹配：B 侧（新线）取值 ⊆ A 侧（旧线）取值 → 正常；否则不为子集；无法判定 —
    function matchText(r) {
      if (r.matched === true) return "新线输出正常";
      if (r.matched === false) return "新线不为旧线子集";
      return "—";
    }
    function matchCls(r) {
      if (r.matched === true) return "ok";
      if (r.matched === false) return "severe";
      return "";
    }
    return { pg, vals, matchText, matchCls };
  },
  template: `
  <div style="overflow-x:auto;">
    <table class="discard-table" v-if="rows.length">
      <thead><tr>
        <th>昵称</th><th>归属组</th><th>实际文件名</th><th>字段序号</th><th>字段名</th><th>字段含义</th>
        <th>{{ srcA }} 的所有取值</th><th>{{ srcB }} 的所有取值</th><th>是否匹配</th>
      </tr></thead>
      <tbody>
        <tr v-for="(r, i) in pg.paged" :key="i">
          <td>{{ r.nickname }}</td>
          <td>{{ r.group }}</td>
          <td class="mono">{{ r.file_name }}</td>
          <td>{{ r.col_seq }}</td>
          <td>{{ r.field_name }}</td>
          <td>{{ r.field_meaning }}</td>
          <td class="vals" :class="{ bad: r.file_missing }">{{ vals(r, 'a') }}</td>
          <td class="vals" :class="{ bad: r.file_missing }">{{ vals(r, 'b') }}</td>
          <td><span class="badge-grade" :class="matchCls(r)"
                    :title="r.matched === true ? (srcB + ' 侧取值为 ' + srcA + ' 侧取值的子集或相等') : (r.matched === false ? (srcB + ' 侧存在超出 ' + srcA + ' 侧的取值，需人工核查') : '原文不可采集，无法判定')">{{ matchText(r) }}</span></td>
        </tr>
      </tbody>
    </table>
    <div class="empty" v-if="!rows.length">无监测数据行</div>
    <div v-if="rows.length" style="margin-top:8px; display:flex; justify-content:flex-end;">
      <ListPager :page="pg.page" :pages="pg.pages" :total="pg.total" :size="pg.size"
                 @go="pg.go" @resize="pg.resize" />
    </div>
  </div>`,
};

/* 关注字段监测卡片：batchId（批次详情）/ jobId（单文件·报表结果页）二选一；未命中不渲染。 */
const DiscardCard = {
  props: { batchId: String, jobId: String },
  setup(props) {
    const data = ref(null); const err = ref(""); let timer = null; let stopped = false;
    async function load() {
      if (stopped) return;
      try {
        const url = props.batchId ? "/api/batches/" + props.batchId + "/discard-cols"
                                  : "/api/jobs/" + props.jobId + "/discard-cols";
        data.value = await api(url);
        err.value = "";
        if (data.value && data.value.status === "pending" && !stopped) timer = setTimeout(load, 2500);
      } catch (e) { err.value = e.message; }
    }
    onMounted(load);
    onBeforeUnmount(() => { stopped = true; if (timer) clearTimeout(timer); });
    const show = computed(() => data.value && (data.value.hit || data.value.status === "pending"));
    const srcA2 = computed(() => (data.value && data.value.src_a) || "A");
    const srcB2 = computed(() => (data.value && data.value.src_b) || "B");
    const waiting = computed(() => data.value && !((data.value.rows || []).length)
      && (data.value.status === "pending" || data.value.status === "running"));
    const csvHref = computed(() => props.batchId ? "/api/batches/" + props.batchId + "/discard-cols.csv" : "");
    return { data, err, show, srcA2, srcB2, waiting, csvHref, fmtTime };
  },
  template: `
  <div class="card" v-if="show">
    <div class="card-head">🔍 关注字段监测（DISCARD_COLS）
      <div class="head-actions">
        <span v-if="data.generated_at" class="count" style="align-self:center;">采集于 {{ fmtTime(data.generated_at) }}</span>
        <a v-if="batchId && data.hit" class="mini-btn" :href="csvHref"
           title="下载本批次关注字段 A/B 取值范围 CSV（UTF-8 BOM，可直接用 Excel 打开）">⬇ DISCARD_COLS.csv</a>
      </div>
    </div>
    <div class="card-body">
      <div v-if="waiting" class="empty">⏳ 对比仍在执行中，完成后自动采集监测栏位取值。</div>
      <template v-else>
        <DiscardTable :rows="data.rows || []" :src-a="srcA2" :src-b="srcB2" />
        <p class="hint" style="margin:8px 0 0;">取值按首次出现顺序去重（每侧上限 1000 个）；（空）= 栏位存在但值为空。这些栏位新旧线理论上取值范围固定且两侧一致，不一致需人工核查。</p>
      </template>
      <div v-if="err" class="error-box">{{ err }}</div>
    </div>
  </div>`,
};

/* 批次情况总结页：概览 + 全部文本偏离度双指标（内容/条数）+ 关注字段监测表；
   各分区按表名/归属组搜索；可下载自包含 HTML 与一分区一 Sheet 的 Excel。 */
const BatchSummaryPage = {
  components: { ListPager },
  emits: ["back"],
  props: { batchId: String, backText: { type: String, default: "返回" } },
  setup(props, { emit }) {
    const p = ref(null); const err = ref(""); const busy = ref(false);
    async function load() {
      busy.value = true;
      try { p.value = await api("/api/batches/" + props.batchId + "/summary-report"); err.value = ""; }
      catch (e) { err.value = e.message; }
      finally { busy.value = false; }
    }
    onMounted(load);
    const htmlHref = computed(() => "/api/batches/" + props.batchId + "/summary-report.html");
    const xlsxHref = computed(() => "/api/batches/" + props.batchId + "/summary-report.xlsx");
    const STATUS_TEXT = { done: "已完成", running: "执行中", preparing: "准备中", error: "异常" };
    const d = computed(() => (p.value && p.value.discard) || {});
    // 偏离度双指标徽标：微量偏移（紫）/ 务必关注（黄）/ 严重问题（红）/ 正常（绿）
    const GRADE_CLS = { "微量偏移": "purple", "务必关注": "yellow", "严重问题": "severe", "正常": "ok" };
    function gradeCls(g) { return GRADE_CLS[g] || "ok"; }
    function pct(v) { return (v * 100).toFixed(1); }
    // 分区搜索（表名 + 归属组，AND 组合；在线页与离线 HTML 同语义）
    const devNick = ref(""); const devGroup = ref("");
    const discNick = ref(""); const discGroup = ref("");
    function hits(list, nick, group) {
      const n = nick.value.trim().toLowerCase(); const g = group.value.trim().toLowerCase();
      if (!n && !g) return list;
      return list.filter(r => {
        const rn = (r.nickname || "").toLowerCase(); const rg = (r.group || "").toLowerCase();
        return (!n || rn.indexOf(n) >= 0) && (!g || rg.indexOf(g) >= 0);
      });
    }
    const devs = computed(() => hits(p.value ? (p.value.deviations || []) : [], devNick, devGroup));
    const discRows = computed(() => hits(d.value.rows || [], discNick, discGroup));
    const devPg = usePager(() => devs.value, 50);
    const discPg = usePager(() => discRows.value, 100);
    watch([devNick, devGroup], () => devPg.reset());
    watch([discNick, discGroup], () => discPg.reset());
    return { p, err, busy, load, htmlHref, xlsxHref, STATUS_TEXT, d,
             gradeCls, pct, devNick, devGroup, discNick, discGroup, devs, discRows, devPg, discPg,
             fmtTime, goBack: () => emit("back") };
  },
  template: `
  <div>
    <PageHeader :back-text="backText" @back="goBack" title="批次情况总结">
      <template #meta>
        <span class="jid" v-if="p">{{ p.batch_id }}</span>
        <span class="group-badge" v-if="p && p.label">🏷 {{ p.label }}</span>
        <span class="badge-status" v-if="p" :class="p.status">{{ STATUS_TEXT[p.status] || p.status }}</span>
      </template>
      <template #actions>
        <button class="mini-btn" @click="load" :disabled="busy">↻ 刷新</button>
        <a class="mini-btn" v-if="p" :href="xlsxHref"
           title="下载批次总结 Excel：概览 / 偏离度（全部文本双指标）/ 关注字段监测，一个分区一个 Sheet">⬇ 下载 Excel</a>
        <a class="mini-btn" v-if="p" :href="htmlHref"
           title="下载自包含 HTML 报告（全量内容 + 分区搜索，可离线打开归档）">⬇ 下载 HTML</a>
      </template>
    </PageHeader>
    <div class="loading" v-if="!p && !err">加载中…</div>
    <div class="error-box" v-if="err">{{ err }}</div>
    <template v-if="p">
      <div class="card">
        <div class="card-head">📦 批次概览</div>
        <div class="card-body">
          <div class="summary-metrics">
            <div class="metric total"><div class="num">{{ p.total_files }}</div><div class="lbl">文本总数</div></div>
            <div class="metric diff"><div class="num">{{ p.diff_files }}</div><div class="lbl">存在差异</div></div>
            <div class="metric"><div class="num">{{ p.done }}</div><div class="lbl">已完成</div></div>
            <div class="metric" :class="{ diff: p.failed > 0 }"><div class="num">{{ p.failed }}</div><div class="lbl">失败</div></div>
            <div class="metric only"><div class="num">{{ p.no_rule_count }}</div><div class="lbl">无规则未对比</div></div>
            <div class="metric only"><div class="num">{{ p.missing_count }}</div><div class="lbl">缺失文件</div></div>
          </div>
          <div style="margin-top:12px;font-size:12px;color:var(--text-soft);">
            {{ p.src_a }}：{{ p.dir_a }}<br/>{{ p.src_b }}：{{ p.dir_b }}<br/>
            对比范围：{{ p.group_name ? '分组「' + p.group_name + '」（仅分组成员命中的文本）' : '全部文本（未选分组）' }}<br/>
            开始 {{ fmtTime(p.started_at) }} · 完成 {{ fmtTime(p.finished_at) }} · 报告生成于 {{ fmtTime(p.generated_at) }}
          </div>
        </div>
      </div>
      <div class="card">
        <div class="card-head">📉 文本偏离度（全部文本 · 双指标）
          <div class="head-actions"><ListPager :page="devPg.page" :pages="devPg.pages" :total="devPg.total"
                    :size="devPg.size" @go="devPg.go" @resize="devPg.resize" /></div>
        </div>
        <div class="card-body">
          <div class="sec-search">
            <label>表名 <input v-model="devNick" placeholder="按表名（昵称）搜索" /></label>
            <label>归属组 <input v-model="devGroup" placeholder="按归属组搜索" /></label>
            <span class="hint" v-if="devs.length !== (p.deviations || []).length">命中 {{ devs.length }} / {{ (p.deviations || []).length }}</span>
          </div>
          <div class="empty" v-if="!devs.length">无已完成文本（或全部完全匹配无差异行）。</div>
          <div v-else style="overflow-x:auto;">
            <table class="discard-table">
              <thead><tr><th>#</th><th>表名（昵称）</th><th>归属组</th><th>文件名</th>
                <th style="min-width:150px;">内容偏离度</th><th style="min-width:150px;">条数偏离度</th>
                <th>完全匹配</th><th>有差异</th><th>仅{{ p.src_a }}</th><th>仅{{ p.src_b }}</th></tr></thead>
              <tbody>
                <tr v-for="(t, i) in devPg.paged" :key="i">
                  <td>{{ (devPg.page - 1) * devPg.size + i + 1 }}</td>
                  <td>{{ t.nickname }}</td>
                  <td>{{ t.group }}</td>
                  <td class="mono">{{ t.file_name }}</td>
                  <td>
                    <span class="conc-bar" style="width:90px;"><span :style="{ width: Math.min(100, pct(t.content_ratio)) + '%' }"></span></span>
                    <b style="margin-left:6px;">{{ pct(t.content_ratio) }}%</b>
                    <span class="badge-grade" :class="gradeCls(t.content_grade)">{{ t.content_grade }}</span>
                  </td>
                  <td>
                    <span class="conc-bar count-bar" style="width:90px;"><span :style="{ width: Math.min(100, pct(t.count_ratio)) + '%' }"></span></span>
                    <b style="margin-left:6px;">{{ pct(t.count_ratio) }}%</b>
                    <span class="badge-grade" :class="gradeCls(t.count_grade)">{{ t.count_grade }}</span>
                  </td>
                  <td>{{ t.equal }}</td><td><b style="color:var(--diff-bar)">{{ t.diff }}</b></td>
                  <td>{{ t.only_a }}</td><td>{{ t.only_b }}</td>
                </tr>
              </tbody>
            </table>
            <p class="hint" style="margin:8px 0 0;">内容偏离度 =（有差异 + 仅{{ p.src_a }} + 仅{{ p.src_b }}）/ {{ p.src_a }}选区总条数；条数偏离度 = |{{ p.src_b }} − {{ p.src_a }}|总条数差 / {{ p.src_a }}选区总条数。分级（两指标同）：≥10% 微量偏移（紫）、≥20% 务必关注（黄）、≥50% 严重问题（红）。</p>
          </div>
        </div>
      </div>
      <div class="card">
        <div class="card-head">🔍 关注字段取值监测（DISCARD_COLS）
          <div class="head-actions" v-if="d.hit">
            <a class="mini-btn" :href="'/api/batches/' + p.batch_id + '/discard-cols.csv'"
               title="下载本批次关注字段 A/B 取值范围 CSV">⬇ DISCARD_COLS.csv</a>
          </div>
        </div>
        <div class="card-body">
          <div class="empty" v-if="!d.hit">{{ d.status === 'pending' ? '⏳ 批次仍在执行中，完成后自动采集。' : '本批次无可监测命中文本（未在「设置」导入 DISCARD 监测配置，或昵称未命中）。' }}</div>
          <template v-else>
            <div class="sec-search">
              <label>表名 <input v-model="discNick" placeholder="按表名（昵称）搜索" /></label>
              <label>归属组 <input v-model="discGroup" placeholder="按归属组搜索" /></label>
              <span class="hint" v-if="discRows.length !== (d.rows || []).length">命中 {{ discRows.length }} / {{ (d.rows || []).length }}</span>
            </div>
            <DiscardTable :rows="discRows" :src-a="d.src_a || p.src_a" :src-b="d.src_b || p.src_b" />
            <p class="hint" style="margin:8px 0 0;">取值按首次出现顺序去重（每侧上限 1000 个）；（空）= 栏位存在但值为空。两侧取值范围理论上应一致，不一致需人工核查。</p>
          </template>
        </div>
      </div>
    </template>
  </div>`,
};

/* ---------- 配置展览区（只读 + 编辑重跑） ---------- */
const ConfigPanel = {
  components: { SeqField },
  props: ["meta", "encodings"],
  emits: ["rerun"],
  setup(props, { emit }) {
    const editing = ref(false);
    const busy = ref(false);
    const err = ref("");
    const form = reactive({
      nickname: "", file_glob: "*", delimiter: " | ", trailer_prefix: "|||||",
      encoding_a: "auto", encoding_b: "auto",
      source_a: "A", source_b: "B",
      key_seq: "", omit_seq: "", pri_seq: "", replace_rules_text: "",
    });
    // 可被“同步最新配置”覆盖，故用 ref（而非只读 computed）
    const columnNames = ref((props.meta.config || {}).column_names || []);

    function rrToText(rr) {
      const disp = {};
      Object.keys(rr || {}).forEach(k => { disp[String(Number(k) + 1)] = rr[k]; });
      return Object.keys(disp).length ? JSON.stringify(disp) : "";
    }

    function loadFromMeta() {
      const c = props.meta.config || {};
      form.nickname = c.nickname || "";
      form.file_glob = c.file_glob || "*";
      form.delimiter = c.delimiter != null ? c.delimiter : " | ";
      form.trailer_prefix = c.trailer_prefix || "|||||";
      form.encoding_a = c.encoding_a || "auto";
      form.encoding_b = c.encoding_b || "auto";
      form.source_a = c.source_a || "A";
      form.source_b = c.source_b || "B";
      form.key_seq = seqToStr(c.key_columns);
      form.omit_seq = seqToStr(c.omit_columns);
      form.pri_seq = c.pri_seq || "";
      form.addmod = c.addmod || "";
      form.replace_rules_text = rrToText(c.replace_rules);
      columnNames.value = c.column_names || [];
    }
    loadFromMeta();
    watch(() => props.meta, loadFromMeta);

    function startEdit() { editing.value = true; }
    function cancel() { editing.value = false; err.value = ""; loadFromMeta(); }

    const toast = ref("");

    function buildBody() {
      const body = {
        nickname: form.nickname || null, file_glob: form.file_glob || null,
        delimiter: form.delimiter,
        trailer_prefix: form.trailer_prefix,
        encoding_a: form.encoding_a, encoding_b: form.encoding_b,
        source_a: form.source_a || null, source_b: form.source_b || null,
        key_seq: form.key_seq || null, omit_seq: form.omit_seq || null,
        pri_seq: form.pri_seq || null,
        addmod: form.addmod || null,
        // 透传列名，确保重跑结果与正常发起一致地显示列名
        column_names: columnNames.value && columnNames.value.length ? columnNames.value : null,
      };
      if (form.replace_rules_text.trim()) {
        try { body.replace_rules = JSON.parse(form.replace_rules_text); }
        catch (e) { throw new Error("替换规则 JSON 解析失败: " + e.message); }
      }
      return body;
    }

    async function rerun() {
      err.value = ""; busy.value = true;
      try {
        // 就地重跑：覆盖原作业结果并持久化（沿用 job_id，可重入查看；批次结果同步刷新）
        const r = await jpost(`/api/jobs/${props.meta.job_id}/rerun`, buildBody());
        editing.value = false;
        emit("rerun", r.job_id);
      } catch (e) { err.value = e.message; }
      finally { busy.value = false; }
    }

    // 点 4：同步最新的对比配置（按昵称从配置库取生效合并版本，填入表单，便于直接重跑）
    async function syncLatest() {
      err.value = ""; busy.value = true;
      try {
        const nick = form.nickname || (props.meta.config || {}).nickname;
        if (!nick) throw new Error("当前配置无昵称，无法同步最新配置");
        const c = await api("/api/configs/get?nickname=" + encodeURIComponent(nick));
        form.nickname = c.nickname || form.nickname;
        if (c.delimiter != null) form.delimiter = c.delimiter;
        if (c.trailer_prefix) form.trailer_prefix = c.trailer_prefix;
        if (c.encoding_a) form.encoding_a = c.encoding_a;
        if (c.encoding_b) form.encoding_b = c.encoding_b;
        if (c.source_a) form.source_a = c.source_a;
        if (c.source_b) form.source_b = c.source_b;
        form.key_seq = seqToStr(c.key_columns);
        form.omit_seq = seqToStr(c.omit_columns);
        form.replace_rules_text = rrToText(c.replace_rules);
        columnNames.value = c.column_names || columnNames.value;
        editing.value = true;
        toast.value = "已同步最新配置（来源 " + (c._source || "配置库") + "），确认后点「修改并重新执行」";
        setTimeout(() => toast.value = "", 3500);
      } catch (e) { err.value = e.message; }
      finally { busy.value = false; }
    }

    // 优化项2：将结果页修改的规则直接持久化到对比配置（current.*），下次读取/导出可见
    async function saveConfig() {
      err.value = ""; busy.value = true;
      try {
        if (!form.nickname) throw new Error("需填写昵称才能保存到配置");
        if (!form.key_seq) throw new Error("需填写主键序列才能保存");
        const r = await jpost("/api/configs", buildBody());
        toast.value = "已保存到配置（" + (r.files || []).join("、") + "）";
        setTimeout(() => toast.value = "", 2500);
      } catch (e) { err.value = e.message; }
      finally { busy.value = false; }
    }

    return { editing, busy, err, form, columnNames, toast, startEdit, cancel, rerun, saveConfig, syncLatest };
  },
  template: `
  <div class="card">
    <div class="card-head">⚙️ 本次对比配置
      <span class="group-badge" v-if="(meta.config||{}).group">归属组 {{ meta.config.group }}</span>
      <div class="head-actions">
        <button class="mini-btn" @click="syncLatest" :disabled="busy" title="按昵称从配置库拉取最新生效配置填入">↻ 同步最新配置</button>
        <button v-if="!editing" class="mini-btn" @click="startEdit">编辑</button>
        <button v-if="editing" class="mini-btn" @click="cancel">取消</button>
        <button v-if="editing" class="mini-btn" @click="saveConfig" :disabled="busy">保存到配置</button>
        <button v-if="editing" class="btn sm" @click="rerun" :disabled="busy">
          {{ busy ? '提交中…' : '修改并重新执行' }}
        </button>
      </div>
    </div>
    <div class="card-body">
      <div class="cfg-files">{{ form.source_a || 'A' }}: <code>{{ meta.file_a }}</code> &nbsp;↔&nbsp; {{ form.source_b || 'B' }}: <code>{{ meta.file_b }}</code></div>
      <div class="form-grid" style="margin-top:12px;">
        <div class="field"><label>昵称</label>
          <input v-model="form.nickname" :readonly="!editing" /></div>
        <div class="field mono"><label>文件通配名</label>
          <input v-model="form.file_glob" :readonly="!editing" placeholder="如 01A***0*.v01" /></div>
        <div class="field mono"><label>分隔符</label>
          <input v-model="form.delimiter" :readonly="!editing" /></div>
        <SeqField label="主键序列 KEYSEQ" v-model="form.key_seq" :column-names="columnNames" :readonly="!editing" />
        <SeqField label="跳过序列 OMITSEQ" v-model="form.omit_seq" :column-names="columnNames" :readonly="!editing" />
        <div class="field"><label>A 编码</label>
          <select v-model="form.encoding_a" :disabled="!editing">
            <option v-for="e in encodings" :key="e" :value="e">{{ e }}</option></select></div>
        <div class="field"><label>B 编码</label>
          <select v-model="form.encoding_b" :disabled="!editing">
            <option v-for="e in encodings" :key="e" :value="e">{{ e }}</option></select></div>
        <div class="field"><label>A 来源名称</label>
          <input v-model="form.source_a" :readonly="!editing" /></div>
        <div class="field"><label>B 来源名称</label>
          <input v-model="form.source_b" :readonly="!editing" /></div>
        <div class="field mono"><label>尾部前缀</label>
          <input v-model="form.trailer_prefix" :readonly="!editing" /></div>
        <div class="field"><label>对比优先级 PRISEQ（1 最高）</label>
          <select v-model="form.pri_seq" :disabled="!editing">
            <option value="">默认（5）</option>
            <option v-for="n in [1,2,3,4,5]" :key="n" :value="String(n)">{{ n }}</option>
          </select></div>
        <div class="field mono"><label>增量过滤 ADDMOD（1-based 栏位，逗号分隔）</label>
          <input v-model="form.addmod" :readonly="!editing" placeholder="如 2,5；留空 = 全量对比" /></div>
      </div>
      <div class="field mono" style="margin-top:12px;">
        <label>按列正则替换（JSON，列 1-based）</label>
        <textarea v-model="form.replace_rules_text" rows="2" :readonly="!editing" placeholder="（无）"></textarea>
      </div>
      <div class="error-box" v-if="err" style="margin-top:12px;">{{ err }}</div>
      <div v-if="toast" style="margin-top:10px; color:var(--eq-bar); font-size:13px;">✓ {{ toast }}</div>
    </div>
  </div>`,
};

/* ---------- 结果视图 ---------- */
const ResultView = {
  components: { ZonePanel, ConfigPanel },
  props: ["jobId", "encodings", "backBatch", "backTasks"],
  emits: ["rerun", "back"],
  setup(props, { emit }) {
    const meta = ref(null);
    const summary = ref(null);
    const zoneCounts = ref({});
    const error = ref("");
    const skipSet = ref(new Set());
    const columnNames = ref([]);
    const dynCols = ref([]);          // CTIS 动态栏位虚拟子列描述（meta.dyn_cols）
    const DYN_VBASE = 1000000;
    const ai = reactive({ enabled: false, model: "", loading: false, content: "", error: "" });
    // 记录评议(note) + 全局搜索
    const notes = ref({});               // { key: {note, zone, updated_at} }
    const zoneNoteCounts = ref({});      // { zone: count }
    const notesNonce = ref(0);           // bump 通知分区按 note 过滤时重载
    const globalQ = ref("");
    const globalApplied = ref(false);    // 是否已应用全局搜索
    const globalNonce = ref(0);          // bump 驱动三个数据分区同步检索
    let timer = null;

    async function load() {
      try {
        const r = await api(`/api/jobs/${props.jobId}/meta`);
        meta.value = r.meta; summary.value = r.summary; zoneCounts.value = r.zone_counts || {};
        const cfg = r.meta.config || {};
        const s = new Set([...(cfg.omit_columns || []), ...(cfg.ignore_columns || [])]);
        skipSet.value = s;
        columnNames.value = cfg.column_names || [];
        dynCols.value = r.meta.dyn_cols || [];
        if (r.meta.status === "running" || r.meta.status === "pending") {
          timer = setTimeout(load, 600);
        }
      } catch (e) { error.value = e.message; }
    }
    async function loadAi() {
      try { const r = await api("/api/ai/status"); ai.enabled = r.enabled; ai.model = r.model; }
      catch (e) {}
    }
    async function loadNotes() {
      try {
        const r = await api(`/api/jobs/${props.jobId}/notes`);
        notes.value = r.notes || {}; zoneNoteCounts.value = r.zone_note_counts || {};
      } catch (e) {}
    }
    onMounted(() => { load(); loadAi(); loadNotes(); });
    watch(() => props.jobId, () => {
      meta.value = null; summary.value = null; ai.content = ""; ai.error = "";
      notes.value = {}; zoneNoteCounts.value = {}; globalQ.value = ""; globalApplied.value = false;
      load(); loadNotes();
    });

    // ---- 全局搜索：驱动三个数据分区 ----
    function doGlobalSearch() { globalApplied.value = !!globalQ.value.trim(); globalNonce.value++; }
    function clearGlobal() { globalQ.value = ""; globalApplied.value = false; globalNonce.value++; }

    // ---- 记录评议：单条保存（空=删除），并在全局搜索态下询问是否批量标记 ----
    async function onSaveNote(payload) {
      // payload: { key, note, zone }
      try {
        await jpost(`/api/jobs/${props.jobId}/notes`, { key: payload.key, note: payload.note, zone: payload.zone });
        // 全局搜索命中多条时，询问是否对全部筛选结果标记相同评议（仅在“新增/修改”而非删除时）
        const txt = (payload.note || "").trim();
        if (txt && globalApplied.value && globalQ.value.trim()) {
          if (await askConfirm(`当前已用全局搜索「${globalQ.value.trim()}」筛选，是否对全部命中的记录标记相同评议？`, { okText: "批量标记" })) {
            const r = await jpost(`/api/jobs/${props.jobId}/notes/bulk`, { q: globalQ.value.trim(), note: txt });
            notify(`已对 ${r.count} 条命中记录写入相同评议。`);
          }
        }
        await loadNotes();
        notesNonce.value++;   // 通知按 note 过滤的分区重载
      } catch (e) { notify("评议保存失败: " + e.message, "error"); }
    }

    // ---- 主键重复 TOP10（排查面板）与增量过滤统计 ----
    const dupTopA = computed(() => (summary.value || {}).dup_top_a || []);
    const dupTopB = computed(() => (summary.value || {}).dup_top_b || []);
    const incrText = computed(() => {
      const inc = meta.value && meta.value.incr;
      if (!inc) return "";
      const vals = (inc.values || []).filter(v => v && v.trim()).join(" & ");
      return "栏位 " + (inc.fields || []).join(",") + (vals ? " = " + vals : "");
    });

    const trailerChips = computed(() => {
      const tf = (summary.value && summary.value.trailer_fields) || {};      return Object.keys(tf).map(k => ({ key: k, ...tf[k] }));
    });

    // 差异列差异度：列出全部有差异的列，按频次降序（freq 键经 JSON 后为字符串），附列名
    // CTIS 虚拟子列：编号显示 107-1B 短标签、名称用完整列名
    const diffConcentration = computed(() => {
      const s = summary.value;
      if (!s || !s.diff_col_freq) return [];
      const diff = s.diff || 0;
      const names = columnNames.value || [];
      const dynByIndex = {};
      for (const d of (dynCols.value || [])) dynByIndex[d.index] = d;
      return Object.keys(s.diff_col_freq)
        .map(k => {
          const i = Number(k);
          if (i >= DYN_VBASE) {
            const d = dynByIndex[i] || {};
            return { col: d.parent_label + '-' + (d.sub || i), name: (d.name || ""), count: s.diff_col_freq[k],
                     pct: diff ? (s.diff_col_freq[k] * 100 / diff) : 0 };
          }
          return { col: i + 1, name: (names[i] || ""), count: s.diff_col_freq[k],
                   pct: diff ? (s.diff_col_freq[k] * 100 / diff) : 0 };
        })
        .sort((a, b) => b.count - a.count);
    });
    // 默认仅展示前 30 行，其余点「加载更多」逐步展开
    const CONC_PAGE = 30;
    const concShown = ref(CONC_PAGE);
    // 切换作业 / summary 变化时重置展示行数
    watch(diffConcentration, () => { concShown.value = CONC_PAGE; });
    const concList = computed(() => diffConcentration.value.slice(0, concShown.value));
    function loadMoreConc() {
      concShown.value = Math.min(concShown.value + CONC_PAGE, diffConcentration.value.length);
    }

    async function runAnalyze() {
      ai.loading = true; ai.error = ""; ai.content = "";
      try {
        const r = await jpost(`/api/jobs/${props.jobId}/analyze`, { model: ai.model || null });
        ai.content = r.content;
      } catch (e) { ai.error = e.message; }
      finally { ai.loading = false; }
    }
    function exportUrl(zone) { return `/api/jobs/${props.jobId}/export?zone=${zone}`; }
    function onRerun(id) { emit("rerun", id); }
    const aiHtml = computed(() => renderMarkdown(ai.content));
    const sourceA = computed(() => srcA(meta.value && meta.value.config));
    const sourceB = computed(() => srcB(meta.value && meta.value.config));
    // 返回键文案：按进入来源区分（批次 / 任务管理 / 作业列表）
    const backText = computed(() => props.backBatch ? "返回批次列表" : (props.backTasks ? "返回任务管理" : "返回作业列表"));

    async function editLabel() {
      const cur = (meta.value && meta.value.label) || "";
      const v = await askInput({ title: "设置标签", message: "留空即清除标签", value: cur, placeholder: "标签内容" });
      if (v === null) return;
      try { await jpost("/api/jobs/" + props.jobId + "/label", { label: v });
            if (meta.value) meta.value.label = v; }
      catch (e) { notify(e.message, "error"); }
    }

    return { meta, summary, zoneCounts, error, skipSet, columnNames, dynCols, trailerChips,
             diffConcentration, concList, concShown, loadMoreConc,
             dupTopA, dupTopB, incrText,
             ai, aiHtml, runAnalyze, exportUrl, onRerun, fmtTime, fmtDur, editLabel, backText,
             sourceA, sourceB, goBack: () => emit("back"),
             notes, zoneNoteCounts, notesNonce, globalQ, globalApplied, globalNonce,
             doGlobalSearch, clearGlobal, onSaveNote };
  },
  template: `
  <div>
    <div class="error-box" v-if="error">{{ error }}</div>
    <div class="loading" v-else-if="!meta">加载中…</div>
    <template v-else>
      <PageHeader :back-text="backText" @back="goBack" title="对比结果">
        <template #meta>
          <span class="jid">{{ meta.job_id }}</span>
          <span class="nick-badge" v-if="(meta.config||{}).nickname">{{ meta.config.nickname }}</span>
          <span class="group-badge" v-if="(meta.config||{}).group">归属组 {{ meta.config.group }}</span>
          <span class="group-badge" v-if="meta.label" :title="meta.label">🏷 {{ meta.label }}</span>
          <button class="mini-btn" @click="editLabel">{{ meta.label ? '改标签' : '加标签' }}</button>
          <span class="badge-status" :class="meta.status">{{ meta.status }}</span>
          <span v-if="meta.incr_state" class="badge-incr" :class="{ 'incr-empty': meta.incr_state === '增量-空文本' }"
                title="增量过滤（ADDMOD）已生效：仅对比条件命中的行">⤴ {{ meta.incr_state }}</span>
          <span v-if="meta.key_warning" class="badge-keywarn" title="主键配置在新旧文本中存在重复键，无法唯一定位记录，该对比配置需要重检">⚠ 主键重复·配置需重检</span>
        </template>
        <template #actions>
          <a class="mini-btn" :href="'/api/jobs/' + jobId + '/export-all'"
             title="将三个数据分区（差异/未匹配/完全匹配）合并导出为单个 Excel（含概览/评议note，单元格全文本）">⬇ 全部导出 Excel</a>
        </template>
      </PageHeader>
      <!-- 主键重复 TOP10 排查面板：A/B 侧各列前 10 个重复键值与次数 -->
      <div class="card dup-card" v-if="meta.key_warning && (dupTopA.length || dupTopB.length)">
        <div class="card-head">⚠ 重复键 TOP10（主键唯一性检测 · 供排查主键配置）</div>
        <div class="card-body dup-panel">
          <div class="dup-col">
            <div class="dup-title">{{ sourceA }} 侧重复键</div>
            <div class="dup-item" v-for="d in dupTopA" :key="'a' + d.key"><code>{{ d.key }}</code><span class="cnt">{{ d.count }} 次</span></div>
            <div class="empty" v-if="!dupTopA.length">无</div>
          </div>
          <div class="dup-col">
            <div class="dup-title">{{ sourceB }} 侧重复键</div>
            <div class="dup-item" v-for="d in dupTopB" :key="'b' + d.key"><code>{{ d.key }}</code><span class="cnt">{{ d.count }} 次</span></div>
            <div class="empty" v-if="!dupTopB.length">无</div>
          </div>
        </div>
      </div>
      <!-- 运行中 -->
      <div class="card" v-if="meta.status === 'running' || meta.status === 'pending'">
        <div class="card-body">
          <div style="margin-bottom:10px;">作业 <b>{{ meta.job_id }}</b> · {{ meta.message || '处理中' }}</div>
          <div class="progress"><span :style="{ width: ((meta.progress||0)*100).toFixed(1) + '%' }"></span></div>
        </div>
      </div>
      <div class="error-box" v-else-if="meta.status === 'error'">对比失败：{{ meta.error }}</div>

      <!-- Brief Summary -->
      <div class="card" v-if="summary">
        <div class="card-head">📊 对比概览</div>
        <div class="card-body">
          <div class="summary-metrics">
            <div class="metric total"><div class="num">{{ summary.total_a }}</div><div class="lbl">{{ sourceA }} 总行数（数据{{summary.data_rows_a}}+尾部{{summary.trailer_rows_a}}）</div></div>
            <div class="metric total"><div class="num">{{ summary.total_b }}</div><div class="lbl">{{ sourceB }} 总行数（数据{{summary.data_rows_b}}+尾部{{summary.trailer_rows_b}}）</div></div>
            <div class="metric eq"><div class="num">{{ summary.equal }}</div><div class="lbl">完全匹配</div></div>
            <div class="metric diff"><div class="num">{{ summary.diff }}</div><div class="lbl">键匹配但有差异</div></div>
            <div class="metric only"><div class="num">{{ summary.only_a }}</div><div class="lbl">仅 {{ sourceA }} 存在</div></div>
            <div class="metric only"><div class="num">{{ summary.only_b }}</div><div class="lbl">仅 {{ sourceB }} 存在</div></div>
          </div>
          <div class="trailer-fields" v-if="trailerChips.length">
            <div class="chip" :class="c.equal ? 'ok' : 'bad'" v-for="c in trailerChips" :key="c.key">
              <span class="k">{{ c.key }}</span>
              <span>{{ sourceA }}={{ c.a }} · {{ sourceB }}={{ c.b }}</span>
              <span class="badge">{{ c.equal ? '一致' : '不一致' }}</span>
            </div>
          </div>
          <!-- 差异列差异度：列出全部有差异的列（默认前 30，可加载更多） -->
          <div class="concentration" v-if="diffConcentration.length">
            <div class="conc-title">差异列差异度（共 {{ diffConcentration.length }} 列）</div>
            <div class="conc-row" v-for="c in concList" :key="c.col">
              <span class="conc-col" :title="'列 ' + c.col + (c.name ? ' · ' + c.name : '')">列 {{ c.col }}<span v-if="c.name" class="conc-name"> · {{ c.name }}</span></span>
              <span class="conc-bar"><span :style="{ width: c.pct.toFixed(1) + '%' }"></span></span>
              <span class="conc-num">{{ c.count }} 次 · {{ c.pct.toFixed(1) }}%</span>
            </div>
            <div class="conc-more" v-if="concShown < diffConcentration.length">
              <button class="btn ghost" @click="loadMoreConc">
                加载更多（已显示 {{ concShown }} / {{ diffConcentration.length }}）
              </button>
            </div>
          </div>
          <div class="incr-line" v-if="meta.incr">⤴ 增量过滤（{{ incrText }}）：{{ sourceA }} {{ meta.incr.a_before }}→{{ meta.incr.a_after }} 行 · {{ sourceB }} {{ meta.incr.b_before }}→{{ meta.incr.b_after }} 行</div>
          <div class="incr-line" v-if="meta.dyn_warnings && meta.dyn_warnings.length">⚠ CTIS 动态栏位告警：{{ meta.dyn_warnings.join('；') }}</div>
          <div style="margin-top:12px; font-size:12px; color:var(--text-soft);">
            触发时间 {{ fmtTime(meta.created_at) }} · 完成时间 {{ fmtTime(meta.finished_at) }}<span v-if="meta.started_at && meta.finished_at"> · ⏱ 耗时 {{ fmtDur(meta.finished_at - meta.started_at) }}</span><span v-if="meta.locked"> · 🔒 已锁定</span><br/>
            编码 {{ sourceA }}={{ meta.detected_encoding_a }} · {{ sourceB }}={{ meta.detected_encoding_b }}
            <span v-if="meta.used_disk_fallback"> · ⚠ 已启用磁盘回退</span>
            <span v-if="summary.recnum_check_a===false || summary.recnum_check_b===false"> · ⚠ RecNum 校验不一致</span>
          </div>
        </div>
      </div>

      <!-- 本次配置展览 + 重跑 -->
      <ConfigPanel v-if="meta.config" :meta="meta" :encodings="encodings" @rerun="onRerun" />

      <!-- 关注字段监测（昵称命中才有内容） -->
      <DiscardCard :job-id="jobId" />

      <!-- AI 优化建议 -->
      <div class="card" v-if="summary">
        <div class="card-head">🤖 AI 优化建议
          <div class="head-actions">
            <input v-if="ai.enabled" v-model="ai.model" class="ai-model-input" placeholder="模型" />
            <button v-if="ai.enabled" class="btn sm" @click="runAnalyze" :disabled="ai.loading">
              {{ ai.loading ? '分析中…' : '生成建议' }}
            </button>
          </div>
        </div>
        <div class="card-body">
          <div v-if="!ai.enabled" class="ai-hint">未启用 AI。请到「设置」页配置大模型 base_url 与 API Key 后使用。</div>
          <div v-else-if="ai.error" class="error-box">{{ ai.error }}</div>
          <div v-else-if="ai.content" class="ai-content md" v-html="aiHtml"></div>
          <div v-else class="ai-hint">点击「生成建议」，将本次<b>聚合统计</b>（不含任何单元格原始值）交由大模型分析。</div>
        </div>
      </div>

      <!-- 全局搜索：作用于下方三个数据分区 -->
      <div class="card" v-if="summary">
        <div class="card-body global-search-bar">
          <span class="gs-label">🔎 全局搜索</span>
          <input class="global-search" v-model="globalQ" @keyup.enter="doGlobalSearch"
                 placeholder="按键值模糊搜索，作用于下方「匹配但有差异 / 未匹配 / 完全匹配」三个分区" />
          <button class="btn" style="padding:6px 16px;" @click="doGlobalSearch">搜索</button>
          <button class="btn ghost" v-if="globalApplied" @click="clearGlobal">清除</button>
          <span class="hint" v-if="globalApplied">已对三个数据分区应用搜索：{{ globalQ }}</span>
        </div>
      </div>

      <!-- 三分区 + trailer -->
      <template v-if="summary">
        <ZonePanel :job-id="jobId" zone="diff" label="🔴 匹配但有差异" :total="zoneCounts.diff||0"
                   :skip-set="skipSet" :column-names="columnNames" :dyn-cols="dynCols" :source-a="sourceA" :source-b="sourceB" :open-by-default="true" :records-open="false" :page-size="10" :export-url="'/api/jobs/' + jobId + '/export?mode=diff'" export-label="⬇ 差异采集表(xlsx)"
                   :supports-note="true" :notes="notes" :note-count="zoneNoteCounts.diff||0" :ext-query="globalQ" :ext-query-nonce="globalNonce" :notes-nonce="notesNonce" @save-note="onSaveNote" />
        <ZonePanel :job-id="jobId" zone="unmatched" label="⬛ 未匹配（仅单侧存在）" :total="zoneCounts.unmatched||0"
                   :skip-set="skipSet" :column-names="columnNames" :dyn-cols="dynCols" :source-a="sourceA" :source-b="sourceB" :open-by-default="false" :records-open="false" :page-size="10" :export-url="exportUrl('unmatched')"
                   :supports-note="true" :notes="notes" :note-count="zoneNoteCounts.unmatched||0" :ext-query="globalQ" :ext-query-nonce="globalNonce" :notes-nonce="notesNonce" @save-note="onSaveNote" />
        <ZonePanel :job-id="jobId" zone="equal" label="🟢 完全匹配（默认折叠）" :total="zoneCounts.equal||0"
                   :skip-set="skipSet" :column-names="columnNames" :dyn-cols="dynCols" :source-a="sourceA" :source-b="sourceB" :open-by-default="false" :records-open="false" :page-size="10" :export-url="exportUrl('equal')"
                   :supports-note="true" :notes="notes" :note-count="zoneNoteCounts.equal||0" :ext-query="globalQ" :ext-query-nonce="globalNonce" :notes-nonce="notesNonce" @save-note="onSaveNote" />
        <ZonePanel :job-id="jobId" zone="trailer" label="🔵 尾部信息对比" :total="zoneCounts.trailer||0"
                   :skip-set="skipSet" :column-names="columnNames" :dyn-cols="dynCols" :source-a="sourceA" :source-b="sourceB" :open-by-default="true" :records-open="false" :page-size="10" :export-url="exportUrl('trailer')" />
      </template>
    </template>
  </div>`,
};

/* ---------- 提交表单 ---------- */
/* 增量条件模态框（ADDMOD）：逐文本填过滤条件值，50 条/页，单行可应用到全部（按栏位号匹配复制）。 */
const IncrCondModal = {
  props: ["rows"], // [{nickname, file_name, addmod:[2,5]}]
  emits: ["confirm", "cancel"],
  setup(props, { emit }) {
    const conds = ref(props.rows.map(() => [])); // 每行与该行 addmod 顺序对齐的值数组
    const page = ref(1);
    const pageSize = 50;
    const totalPages = computed(() => Math.max(1, Math.ceil(props.rows.length / pageSize)));
    const pageRows = computed(() => props.rows
        .map((r, i) => ({ ...r, idx: i }))
        .slice((page.value - 1) * pageSize, page.value * pageSize));
    const enabledCount = computed(() => conds.value.filter(c => (c || []).some(v => v && v.trim())).length);
    const val = (ri, fi) => ((conds.value[ri] || [])[fi]) || "";
    const setVal = (ri, fi, v) => {
      if (!conds.value[ri]) conds.value[ri] = [];
      conds.value[ri][fi] = v;
    };
    // 应用到全部：按栏位号匹配复制当前行已填的各列值
    function applyAll(ri) {
      const src = props.rows[ri] || {};
      props.rows.forEach((r, i) => {
        if (i === ri) return;
        if (!conds.value[i]) conds.value[i] = [];
        (src.addmod || []).forEach((f, fi) => {
          const tfi = (r.addmod || []).indexOf(f);
          if (tfi >= 0) conds.value[i][tfi] = (conds.value[ri] || [])[fi] || "";
        });
      });
    }
    function confirm() {
      emit("confirm", props.rows.map((r, i) => (r.addmod || []).map((f, fi) => val(i, fi).trim())));
    }
    return { conds, page, pageSize, totalPages, pageRows, enabledCount, val, setVal, applyAll, confirm };
  },
  template: `
  <div class="modal-mask" @click.self="$emit('cancel')">
    <div class="modal incr-modal" role="dialog" aria-modal="true">
      <div class="modal-head">增量过滤条件（ADDMOD）
        <button class="modal-x" @click="$emit('cancel')" title="取消" aria-label="关闭">✕</button></div>
      <div class="modal-body">
        <p class="incr-tip">各栏位条件 <b>同时满足</b> 才保留该行（栏位内逗号分隔多值任一命中）；留空 = 该文本不过滤、按全量对比。本批共 {{ rows.length }} 个文本，启用 {{ enabledCount }} 个。</p>
        <table class="incr-table">
          <thead><tr><th style="width:38%;">文本项</th><th>过滤条件值（按栏位）</th><th style="width:86px;"></th></tr></thead>
          <tbody>
            <tr v-for="r in pageRows" :key="r.idx">
              <td class="incr-file"><span class="nick">{{ r.nickname }}</span><code>{{ r.file_name }}</code></td>
              <td>
                <span class="incr-cell" v-for="(f, fi) in (r.addmod || [])" :key="f">
                  <label>栏位{{ f }}</label>
                  <input :value="val(r.idx, fi)" @input="setVal(r.idx, fi, $event.target.value)"
                         placeholder="值1,值2" />
                </span>
                <span v-if="!(r.addmod || []).length" class="incr-none">（该规则未配置 ADDMOD，全量对比）</span>
              </td>
              <td><button class="mini-btn" @click="applyAll(r.idx)" title="把本行已填的各栏位值复制到全部文本">应用到全部</button></td>
            </tr>
          </tbody>
        </table>
        <div class="zone-pager" v-if="totalPages > 1">
          <button class="mini-btn" :disabled="page <= 1" @click="page--">‹ 上一页</button>
          <span>第 {{ page }} / {{ totalPages }} 页（每页 {{ pageSize }} 条）</span>
          <button class="mini-btn" :disabled="page >= totalPages" @click="page++">下一页 ›</button>
        </div>
      </div>
      <div class="modal-foot">
        <button class="btn ghost" @click="$emit('cancel')">取消</button>
        <button class="btn primary" @click="confirm">确认提交（增量 {{ enabledCount }} / {{ rows.length }}）</button>
      </div>
    </div>
  </div>`,
};

const SubmitForm = {
  components: { SeqField, IncrCondModal },
  props: ["encodings", "ctis"],
  emits: ["submitted", "batched"],
  setup(props, { emit }) {
    const form = reactive({
      mode: "dirs",
      dir_a: "", dir_b: "", file_glob: "01A***0*.v01",
      path_a: "", path_b: "",
      config_text: "",
      nickname: "", delimiter: " | ", trailer_prefix: "|||||",
      encoding_a: "auto", encoding_b: "auto",
      source_a: "A", source_b: "B",
      key_seq: "", omit_seq: "", pri_seq: "", addmod: "",
      replace_rules_text: "",
      batch_config_file: "__all__",
      batch_group: "",
      column_names: [],
      label: "",
    });
    const uploadsA = ref([]); const uploadsB = ref([]);
    const busy = ref(false); const err = ref(""); const toast = ref("");
    // 增量条件模态框状态（open 时由 IncrCondModal 渲染，confirm/cancel 经 resolve 回到提交流程）
    const incrModal = reactive({ open: false, rows: [], resolve: null });
    function openIncrModal(rows) {
      return new Promise(resolve => { incrModal.rows = rows; incrModal.open = true; incrModal.resolve = resolve; });
    }
    function onIncrConfirm(conds) { incrModal.open = false; if (incrModal.resolve) incrModal.resolve(conds); incrModal.resolve = null; }
    function onIncrCancel() { incrModal.open = false; if (incrModal.resolve) incrModal.resolve(null); incrModal.resolve = null; }

    // 对比批次/轮次输入框（目录对比提交前弹出）：可留空；下拉默认 P2703/P2711 与 1~4，新输入值记忆到 localStorage
    const CMP_BATCH_KEY = "td_cmp_batches"; const CMP_ROUND_KEY = "td_cmp_rounds";
    function loadOpts(key, dft) {
      try { return [...new Set([...dft, ...(JSON.parse(localStorage.getItem(key) || "[]") || [])])]; }
      catch (e) { return [...dft]; }
    }
    const caseModal = reactive({ open: false, batch: "", round: "", resolve: null,
      batches: loadOpts(CMP_BATCH_KEY, ["P2703", "P2711"]), rounds: loadOpts(CMP_ROUND_KEY, ["1", "2", "3", "4"]) });
    function openCaseModal() {
      caseModal.batch = ""; caseModal.round = "";
      return new Promise(resolve => { caseModal.open = true; caseModal.resolve = resolve; });
    }
    function onCaseConfirm() {
      caseModal.open = false;
      const val = (s) => (s || "").trim();
      try { // 新批次/轮次记忆（本地，最多各存 20 个）
        if (val(caseModal.batch) && !caseModal.batches.includes(val(caseModal.batch))) {
          caseModal.batches.push(val(caseModal.batch));
          localStorage.setItem(CMP_BATCH_KEY, JSON.stringify(caseModal.batches.slice(-20)));
        }
        if (val(caseModal.round) && !caseModal.rounds.includes(val(caseModal.round))) {
          caseModal.rounds.push(val(caseModal.round));
          localStorage.setItem(CMP_ROUND_KEY, JSON.stringify(caseModal.rounds.slice(-20)));
        }
      } catch (e) {}
      if (caseModal.resolve) caseModal.resolve({ batch: val(caseModal.batch), round: val(caseModal.round) });
      caseModal.resolve = null;
    }
    function onCaseCancel() { caseModal.open = false; if (caseModal.resolve) caseModal.resolve(null); caseModal.resolve = null; }
    // 配置选择/搜索
    const cfgQuery = ref(""); const cfgResults = ref([]); const cfgFiles = ref([]);
    const saved = reactive({ open: false, nickname: "", files: [], viewing: "", content: "" });

    async function searchConfigs() {
      try {
        const r = await api("/api/configs?q=" + encodeURIComponent(cfgQuery.value));
        cfgResults.value = r.configs;
      } catch (e) {}
    }
    function pickConfig(c) {
      form.nickname = c.nickname || "";
      form.file_glob = c.file_glob || "*";
      form.key_seq = seqToStr(c.key_columns);
      form.omit_seq = seqToStr(c.omit_columns);
      form.pri_seq = c.pri_seq || "";
      form.addmod = c.addmod || "";
      if (c.delimiter) form.delimiter = c.delimiter;
      form.column_names = c.column_names || [];   // 携带列名映射
      form.config_text = "";
      const named = (c.column_names && c.column_names.length) ? "（含 " + c.column_names.length + " 个列名）" : "";
      toast.value = "已载入配置 " + c.nickname + named; setTimeout(() => toast.value = "", 1800);
    }
    async function loadConfigFiles() {
      try { const r = await api("/api/config-files"); cfgFiles.value = r.files;
            if (!form.batch_config_file && r.files.length) form.batch_config_file = r.files[0]; } catch (e) {}
    }
    // 对比配置分组（目录对比圈定范围；空 = 全部文本）
    const cmpGroups = ref([]);
    async function loadGroups() {
      try { const r = await api("/api/compare-groups"); cmpGroups.value = r.groups || []; } catch (e) {}
    }
    async function saveConfig() {
      err.value = "";
      if (!form.nickname) { err.value = "保存配置需填写昵称"; return; }
      if (!form.key_seq) { err.value = "保存配置需填写主键序列"; return; }
      try {
        const body = {
          nickname: form.nickname, file_glob: form.file_glob || "*",
          delimiter: form.delimiter, encoding_a: form.encoding_a, encoding_b: form.encoding_b,
          trailer_prefix: form.trailer_prefix,
          key_seq: form.key_seq, omit_seq: form.omit_seq || null, pri_seq: form.pri_seq || null,
          addmod: form.addmod || null,
        };
        if (form.replace_rules_text.trim()) body.replace_rules = JSON.parse(form.replace_rules_text);
        const r = await jpost("/api/configs", body);
        saved.open = true; saved.nickname = r.nickname; saved.files = r.files;
        saved.viewing = ""; saved.content = "";
        searchConfigs(); loadConfigFiles();
      } catch (e) { err.value = "保存失败: " + e.message; }
    }
    async function viewSavedFile(name) {
      saved.viewing = name; saved.content = "加载中…";
      try {
        const r = await api("/api/configs/raw?file=" + encodeURIComponent(name));
        saved.content = r.content;
      } catch (e) { saved.content = "读取失败: " + e.message; }
    }

    onMounted(() => { searchConfigs(); loadConfigFiles(); loadGroups(); });

    async function parseConfig() {
      try {
        const r = await jpost("/api/parse-config", { config_text: form.config_text });
        form.nickname = r.nickname; form.file_glob = r.file_glob;
        form.key_seq = r.key_seq; form.omit_seq = r.omit_seq;
        toast.value = "已解析配置串"; setTimeout(() => toast.value = "", 1500);
      } catch (e) { err.value = e.message; }
    }

    async function uploadFiles(ev, target) {
      const files = ev.target.files;
      if (!files.length) return;
      const fd = new FormData();
      for (const f of files) fd.append("files", f);
      const r = await api("/api/upload", { method: "POST", body: fd });
      if (target === "a") uploadsA.value = r.files; else uploadsB.value = r.files;
    }

    function buildPayload() {
      const p = {
        config_text: form.config_text || null,
        nickname: form.nickname || null,
        file_glob: form.file_glob || null,
        delimiter: form.delimiter,
        trailer_prefix: form.trailer_prefix,
        encoding_a: form.encoding_a, encoding_b: form.encoding_b,
        source_a: form.source_a || null, source_b: form.source_b || null,
        key_seq: form.key_seq || null, omit_seq: form.omit_seq || null,
        pri_seq: form.pri_seq || null,
        addmod: form.addmod || null,
        column_names: (form.column_names && form.column_names.length) ? form.column_names : null,
        label: form.label || null,
        kind: props.ctis ? "ctis" : null,
      };
      if (form.replace_rules_text.trim()) {
        try { p.replace_rules = JSON.parse(form.replace_rules_text); }
        catch (e) { throw new Error("替换规则 JSON 解析失败: " + e.message); }
      }
      if (form.mode === "paths") { p.pairs = [{ a: form.path_a, b: form.path_b }]; }
      else {
        const n = Math.min(uploadsA.value.length, uploadsB.value.length);
        if (!n) throw new Error("请先上传 A、B 文件");
        p.pairs = [];
        for (let i = 0; i < n; i++) p.pairs.push({ a: uploadsA.value[i].path, b: uploadsB.value[i].path });
      }
      return p;
    }

    async function submit() {
      err.value = ""; busy.value = true;
      try {
        if (form.mode === "dirs") {
          // 对比批次/轮次输入框（可留空）：确认后才继续拉起对比任务
          const caseInfo = await openCaseModal();
          if (caseInfo === null) { busy.value = false; return; }
          // 目录对比 → 批次：选配置文件(多规则) 或 用下方内联单规则
          const p = { dir_a: form.dir_a, dir_b: form.dir_b,
                      delimiter: form.delimiter, encoding_a: form.encoding_a, encoding_b: form.encoding_b,
                      source_a: form.source_a || null, source_b: form.source_b || null,
                      label: form.label || null,
                      kind: props.ctis ? "ctis" : null,
                      cmp_batch: caseInfo.batch || null, cmp_round: caseInfo.round || null };
          if (form.batch_group) p.group = form.batch_group;
          if (form.batch_config_file === "__all__") p.use_all = true;   // 全部生效配置(current.conf)
          else if (form.batch_config_file) p.config_file = form.batch_config_file;
          else {
            p.nickname = form.nickname || null; p.file_glob = form.file_glob || "*";
            p.key_seq = form.key_seq || null; p.omit_seq = form.omit_seq || null;
            p.pri_seq = form.pri_seq || null;
            p.addmod = form.addmod || null;
            if (!form.key_seq) throw new Error("请填写主键序列（或选择「全部配置」/一个配置文件）");
          }
          // 增量对比（ADDMOD）：先 dry-run 配对预览，任一文本配置了 ADDMOD 才弹条件模态框
          const pv = await jpost("/api/batch-preview", p);
          if ((pv.pairs || []).some(x => (x.addmod || []).length)) {
            const conds = await openIncrModal(pv.pairs);
            if (conds === null) { busy.value = false; return; } // 用户取消
            p.incr_conds = conds;
          }
          const r = await jpost("/api/batch-compare", p);
          emit("batched", r.batch_id);
        } else {
          const p = buildPayload();
          // 增量对比（ADDMOD）：内联规则声明了 ADDMOD 即弹条件模态框（每文件对一行）
          if (form.addmod && form.addmod.trim()) {
            const fields = form.addmod.split(",").map(s => parseInt(s.trim(), 10)).filter(n => n >= 1);
            const base = (form.path_a || (uploadsA.value[0] && uploadsA.value[0].path) || "").split(/[\\/]/).pop();
            const rows = p.pairs.map(pr => ({
              nickname: form.nickname || "JOB",
              file_name: (pr.a || "").split(/[\\/]/).pop() || base,
              addmod: fields,
            }));
            const conds = await openIncrModal(rows);
            if (conds === null) { busy.value = false; return; }
            p.incr_conds = conds;
          }
          const r = await jpost("/api/compare", p);
          emit("submitted", r.job_ids);
        }
      } catch (e) { err.value = e.message; }
      finally { busy.value = false; }
    }

    return { form, uploadsA, uploadsB, busy, err, toast, parseConfig, uploadFiles, submit,
             incrModal, onIncrConfirm, onIncrCancel,
             caseModal, onCaseConfirm, onCaseCancel,
             cfgQuery, cfgResults, cfgFiles, cmpGroups, saved, searchConfigs, pickConfig, saveConfig, viewSavedFile };
  },
  template: `
  <div>
    <PageHeader :title="ctis ? 'CTIS 对比' : '新建对比'"
                :sub="ctis ? 'CTIS 专用文本对比（配置来源于系统共用配置库）；目录批量 / 指定路径，支持压缩文件预解压与 PRISEQ 优先级'
                           : '目录对比生成批次（多文件）；指定路径 / 上传文件生成单文件作业'" />
    <div class="card">
      <div class="card-head">① 📁 选择对比来源</div>
      <div class="card-body">
        <div class="seg" style="margin-bottom:16px;">
          <button :class="form.mode==='dirs'?'active':''" @click="form.mode='dirs'">目录对比（批量）</button>
          <button :class="form.mode==='paths'?'active':''" @click="form.mode='paths'">指定文件路径</button>
          <button v-if="!ctis" :class="form.mode==='uploads'?'active':''" @click="form.mode='uploads'">上传文件</button>
        </div>

        <div v-if="form.mode==='dirs'">
          <div class="form-grid">
            <div class="field mono"><label>A 目录（旧/IBM）</label><input v-model="form.dir_a" placeholder="O:\\CodeRepos\\v01\\bocso" /></div>
            <div class="field mono"><label>B 目录（新/X86）</label><input v-model="form.dir_b" placeholder="O:\\CodeRepos\\v01\\bocsoxc" /></div>
            <div class="field"><label>对比配置文件（多规则，可选）</label>
              <select v-model="form.batch_config_file">
                <option value="__all__">全部配置（current.conf 生效，含修改，推荐）</option>
                <option v-if="!ctis" value="">（不选 = 用下方单规则）</option>
                <option v-for="f in cfgFiles" :key="f" :value="f">{{ f }}</option>
              </select></div>
            <div class="field"><label>对比配置分组（圈定范围，可选）</label>
              <select v-model="form.batch_group">
                <option value="">不分组（对比目录下全部文本）</option>
                <option v-for="g in cmpGroups" :key="g.name" :value="g.name">{{ g.name }}（{{ g.count }} 个文本）</option>
              </select></div>
          </div>
          <p class="hint" style="margin-top:10px;">目录对比将生成<b>批次</b>：选配置文件则按其多条规则的通配名匹配并同名配对；不选则用下方单条配置。选择<b>对比配置分组</b>后仅对比分组成员（配置昵称）命中的文本，名单外文本不读取不操作；不分组 = 全部。无匹配规则的文件不对比，会在批次里列出。目录内的 <code>*.gz</code> 压缩文件（含日期后缀）会先解压再参与匹配对比。分组可在「配置管理」页维护或 Excel 导入。</p>
        </div>
        <div v-else-if="form.mode==='paths'" class="form-grid">
          <div class="field mono"><label>A 文件路径</label><input v-model="form.path_a" /></div>
          <div class="field mono"><label>B 文件路径</label><input v-model="form.path_b" /></div>
        </div>
        <div v-else class="form-grid">
          <div class="field"><label>上传 A 文件</label><input type="file" multiple @change="e=>uploadFiles(e,'a')" />
            <span class="hint">{{ uploadsA.length }} 个已上传</span></div>
          <div class="field"><label>上传 B 文件（与 A 按顺序配对）</label><input type="file" multiple @change="e=>uploadFiles(e,'b')" />
            <span class="hint">{{ uploadsB.length }} 个已上传</span></div>
        </div>

        <!-- 自定义来源名称：贯穿结果页/列表/导出所有原显示 A/B 之处；所有模式均可填 -->
        <div class="form-grid" style="margin-top:14px;">
          <div class="field"><label>A 来源名称（替代“A”显示，默认 A）</label><input v-model="form.source_a" placeholder="如 旧系统/IBM" /></div>
          <div class="field"><label>B 来源名称（替代“B”显示，默认 B）</label><input v-model="form.source_b" placeholder="如 新系统/X86" /></div>
        </div>
        <p class="hint" style="margin-top:8px;">来源名称会显示在结果页概览、左右表头、作业/批次列表的缺失明细及 Excel 导出来源列，便于识别两个对比源。</p>
      </div>
    </div>

    <!-- 配置选择/搜索 -->
    <div class="card">
      <div class="card-head">② 🔎 选择已有配置（按昵称/通配名搜索）</div>
      <div class="card-body">
        <div class="field"><input v-model="cfgQuery" @input="searchConfigs" placeholder="输入昵称关键字搜索，如 INCT" /></div>
        <div class="cfg-results" v-if="cfgResults.length">
          <div class="cfg-item" v-for="c in cfgResults" :key="c.nickname + c._source" @click="pickConfig(c)">
            <span class="nick">{{ c.nickname }}</span>
            <span class="glob mono">{{ c.file_glob }}</span>
            <span class="src">来源 {{ c._source }} · 主键 {{ c.key_columns.map(i=>i+1).join('/') }}</span>
          </div>
        </div>
        <div class="empty" v-else style="padding:10px;">未找到匹配配置，可在下方手动输入并保存</div>
      </div>
    </div>

    <div class="card" v-show="!ctis && !(form.mode==='dirs' && form.batch_config_file)">
      <div class="card-head">③ ⚙️ 对比配置{{ form.mode==='dirs' ? '（目录对比的单规则）' : '' }}</div>
      <div class="card-body">
        <div class="field mono" style="margin-bottom:14px;">
          <label>配置串（昵称:通配名:KEYSEQ=…:OMITSEQ=…）</label>
          <textarea v-model="form.config_text" rows="2" placeholder="可粘贴配置串，点下方解析"></textarea>
          <div class="btn-row" style="margin-top:8px;"><button class="btn ghost" @click="parseConfig">解析填充下方字段</button></div>
        </div>
        <div class="form-grid">
          <div class="field"><label>昵称</label><input v-model="form.nickname" /></div>
          <div class="field mono"><label>分隔符</label><input v-model="form.delimiter" /></div>
          <SeqField label="主键序列 KEYSEQ (1-based, 如 3/4/5)" v-model="form.key_seq" :column-names="form.column_names" placeholder="如 3/4/5" />
          <SeqField label="跳过序列 OMITSEQ" v-model="form.omit_seq" :column-names="form.column_names" placeholder="如 1/2/10" />
          <div class="field"><label>A 编码</label>
            <select v-model="form.encoding_a"><option v-for="e in encodings" :key="e" :value="e">{{ e }}</option></select></div>
          <div class="field"><label>B 编码</label>
            <select v-model="form.encoding_b"><option v-for="e in encodings" :key="e" :value="e">{{ e }}</option></select></div>
          <div class="field mono"><label>尾部前缀</label><input v-model="form.trailer_prefix" /></div>
          <div class="field"><label>对比优先级 PRISEQ（1 最高）</label>
            <select v-model="form.pri_seq">
              <option value="">默认（5）</option>
              <option v-for="n in [1,2,3,4,5]" :key="n" :value="String(n)">{{ n }}</option>
            </select></div>
          <div class="field mono"><label>增量过滤 ADDMOD（1-based 栏位，逗号分隔）</label>
            <input v-model="form.addmod" placeholder="如 2,5；留空 = 全量对比" /></div>
        </div>
        <div class="field mono" style="margin-top:14px;">
          <label>按列正则替换（可选，JSON：{"6": [["\\\\s",""]]}，列为 1-based）</label>
          <textarea v-model="form.replace_rules_text" rows="2" placeholder='留空则不替换'></textarea>
        </div>
        <div class="btn-row">
          <button class="btn ghost" @click="saveConfig">保存为配置（写入 current.conf）</button>
        </div>
      </div>
    </div>

    <div class="card"><div class="card-head">④ 🚀 提交</div><div class="card-body">
      <label class="field" style="display:block;margin-bottom:10px;">
        <span>标签（可选，标注本次对比目的/来源）</span>
        <input v-model="form.label" placeholder="如：月结对账-生产环境" style="width:100%;" />
      </label>
      <div class="error-box" v-if="err">{{ err }}</div>
      <div class="btn-row">
        <button class="btn" @click="submit" :disabled="busy">{{ busy ? '提交中…' : (form.mode==='dirs' ? '开始目录对比（批次）' : '开始对比') }}</button>
      </div>
    </div></div>
    <div class="toast" v-if="toast">{{ toast }}</div>

    <!-- 增量条件模态框（ADDMOD 提交前确认） -->
    <IncrCondModal v-if="incrModal.open" :rows="incrModal.rows"
                   @confirm="onIncrConfirm" @cancel="onIncrCancel" />

    <!-- 对比批次/轮次输入框（目录对比确认前弹出；可留空，下拉可输可选） -->
    <div class="modal-mask" v-if="caseModal.open" @click.self="onCaseCancel">
      <div class="modal" role="dialog" aria-modal="true" style="max-width:440px;">
        <div class="modal-head">📋 对比批次与轮次
          <button class="modal-x" @click="onCaseCancel" title="取消" aria-label="关闭">✕</button></div>
        <div class="modal-body">
          <p class="hint" style="margin-top:0;">指定本次对比的<b>批次</b>与<b>轮次</b>（可留空不填），确认后开始对比。取值将随批次记录并显示在批次详情，同时写入每个文本差异导出的采集对比案例 Excel（批次/轮次列）。</p>
          <div class="field"><label>对比批次</label>
            <input v-model="caseModal.batch" list="td-cmp-batch-opts" placeholder="如 P2703 / P2711，可输入新批次" />
            <datalist id="td-cmp-batch-opts">
              <option v-for="b in caseModal.batches" :key="b" :value="b" />
            </datalist>
          </div>
          <div class="field"><label>轮次</label>
            <input v-model="caseModal.round" list="td-cmp-round-opts" placeholder="如 1~4，可输入新轮次" />
            <datalist id="td-cmp-round-opts">
              <option v-for="r in caseModal.rounds" :key="r" :value="r" />
            </datalist>
          </div>
        </div>
        <div class="modal-foot">
          <button class="btn ghost" @click="onCaseCancel">取消</button>
          <button class="btn primary" @click="onCaseConfirm">确认并开始对比</button>
        </div>
      </div>
    </div>

    <!-- 保存成功弹框：列出已写入文件，可点击查看 -->
    <div class="modal-mask" v-if="saved.open" @click.self="saved.open=false">
      <div class="modal">
        <div class="modal-head">✅ 配置「{{ saved.nickname }}」已保存
          <button class="modal-x" @click="saved.open=false" title="关闭" aria-label="关闭">✕</button></div>
        <div class="modal-body">
          <p>已写入以下文件（点击查看内容）：</p>
          <div class="saved-files">
            <button class="mini-btn" v-for="f in saved.files" :key="f"
                    :class="{active: saved.viewing===f}" @click="viewSavedFile(f)">{{ f }}</button>
          </div>
          <pre class="saved-content" v-if="saved.viewing">{{ saved.content }}</pre>
        </div>
        <div class="modal-foot"><button class="btn" @click="saved.open=false">关闭</button></div>
      </div>
    </div>
  </div>`,
};

/* ---------- 作业列表（批次条目 + 独立作业，均可搜索过滤） ---------- */
const JobList = {
  emits: ["open", "open-batch", "open-split", "open-report-batch", "open-custom-batch"],
  setup(props, { emit }) {
    const batches = ref([]); const standalone = ref([]); const splitJobs = ref([]);
    const q = ref("");
    let timer = null; let stopped = false;
    // 多选删除：选中的作业/批次 id 集合
    const selJobs = reactive(new Set());
    const selBatches = reactive(new Set());
    const selVer = ref(0);   // 触发 Set 变更的响应式刷新
    const bumpSel = () => { selVer.value++; };
    async function load() {
      try {
        const r = await api("/api/joblist");
        batches.value = r.batches; standalone.value = r.standalone;
        splitJobs.value = r.split_jobs || [];
        // 清理已不存在的选中项
        const jids = new Set(standalone.value.map(j => j.job_id));
        const bids = new Set(batches.value.map(b => b.batch.batch_id));
        [...selJobs].forEach(id => { if (!jids.has(id)) selJobs.delete(id); });
        [...selBatches].forEach(id => { if (!bids.has(id)) selBatches.delete(id); });
        bumpSel();
      } catch (e) {}
      if (!stopped) timer = setTimeout(load, 2000);
    }
    onMounted(load);

    const ql = computed(() => q.value.trim().toLowerCase());
    const fBatches = computed(() => !ql.value ? batches.value : batches.value.filter(b =>
      (b.batch.batch_id + " " + b.batch.dir_a + " " + b.batch.dir_b + " " + b.batch.config_source
       + " " + (b.batch.label || "")).toLowerCase().includes(ql.value)));
    // 报表对比批次：徽标区分 + 点击进报表批次视图；自定义格式对比批次同理；CTIS 批次走普通文件批次视图
    const isReport = (b) => (b.batch || {}).batch_type === "report";
    const isCustom = (b) => (b.batch || {}).batch_type === "custom";
    const isCtis = (b) => (b.batch || {}).batch_type === "ctis";
    const openBatchEntry = (b) => emit(isReport(b) ? "open-report-batch"
        : isCustom(b) ? "open-custom-batch" : "open-batch", b.batch.batch_id);
    // 需求：报表对比结果在结果界面独立成卡，与文件/段对比一并查看；from 标记让返回键回到本页
    const fReportBatches = computed(() => fBatches.value.filter(isReport));
    const fFileBatches = computed(() => fBatches.value.filter(b => !isReport(b)));
    const openReportBatchFromList = (b) => emit("open-report-batch", b.batch.batch_id, "jobs");
    const fJobs = computed(() => !ql.value ? standalone.value : standalone.value.filter(j =>
      (j.job_id + " " + (j.file_a || "") + " " + (j.file_b || "")).toLowerCase().includes(ql.value)));

    async function act(url, method, confirmMsg) {
      if (confirmMsg && !(await askConfirm(confirmMsg, { danger: true }))) return;
      try { await api(url, { method: method || "POST" }); await load(); }
      catch (e) { notify(e.message, "error"); }
    }
    const lockJob = (id) => act("/api/jobs/" + id + "/lock");
    const unlockJob = (id) => act("/api/jobs/" + id + "/unlock");
    const delJob = (id) => act("/api/jobs/" + id, "DELETE", "确认删除该作业？");
    const lockBatch = (id) => act("/api/batches/" + id + "/lock");
    const unlockBatch = (id) => act("/api/batches/" + id + "/unlock");
    const delBatch = (id) => act("/api/batches/" + id, "DELETE", "确认删除整个批次及其所有子作业？");

    // 标签就地编辑：prompt 输入，留空即清除
    async function editLabel(url, current) {
      const v = await askInput({ title: "设置标签", message: "留空即清除该作业/批次的标签", value: current || "", placeholder: "标签内容" });
      if (v === null) return;   // 取消
      try { await jpost(url, { label: v }); await load(); }
      catch (e) { notify(e.message, "error"); }
    }
    const editJobLabel = (j) => editLabel("/api/jobs/" + j.job_id + "/label", j.label);
    const editBatchLabel = (b) => editLabel("/api/batches/" + b.batch.batch_id + "/label", b.batch.label);

    // ---- 文本拆分作业 ----
    const fSplit = computed(() => !ql.value ? splitJobs.value : splitJobs.value.filter(s =>
      (s.job_id + " " + (s.label || "")).toLowerCase().includes(ql.value)));
    const delSplit = (id) => act("/api/split-jobs/" + id, "DELETE", "确认删除该拆分作业？");
    const editSplitLabel = (s) => editLabel("/api/split-jobs/" + s.job_id + "/label", s.label);
    function splitGroupsText(s) {
      const a = ((s.summary || {}).a || {}).groups || [];
      return a.map(g => g.name + ":" + g.count).join(" · ");
    }

    // ---- 多选 ----
    const toggleJob = (id) => { selJobs.has(id) ? selJobs.delete(id) : selJobs.add(id); bumpSel(); };    const toggleBatch = (id) => { selBatches.has(id) ? selBatches.delete(id) : selBatches.add(id); bumpSel(); };
    const isJobSel = (id) => (selVer.value, selJobs.has(id));
    const isBatchSel = (id) => (selVer.value, selBatches.has(id));
    const selCount = computed(() => (selVer.value, selJobs.size + selBatches.size));
    const allJobsSel = computed(() => (selVer.value, fJobs.value.length > 0 && fJobs.value.every(j => selJobs.has(j.job_id))));
    const allBatchesSel = computed(() => (selVer.value, fFileBatches.value.length > 0 && fFileBatches.value.every(b => selBatches.has(b.batch.batch_id))));
    function toggleAllJobs() {
      if (allJobsSel.value) fJobs.value.forEach(j => selJobs.delete(j.job_id));
      else fJobs.value.forEach(j => selJobs.add(j.job_id));
      bumpSel();
    }
    function toggleAllBatches() {
      if (allBatchesSel.value) fFileBatches.value.forEach(b => selBatches.delete(b.batch.batch_id));
      else fFileBatches.value.forEach(b => selBatches.add(b.batch.batch_id));
      bumpSel();
    }
    function clearSel() { selJobs.clear(); selBatches.clear(); bumpSel(); }
    async function bulkDelete() {
      if (!selCount.value) return;
      if (!(await askConfirm("确认删除选中的 " + selBatches.size + " 个批次（含其所有子作业）与 " + selJobs.size + " 个单文件作业？锁定项将被跳过。", { danger: true }))) return;
      try {
        const r = await jpost("/api/jobs/bulk-delete", { job_ids: [...selJobs], batch_ids: [...selBatches] });
        clearSel();
        await load();
        let msg = "已删除 " + r.deleted_batches + " 个批次、" + r.deleted_jobs + " 个作业";
        if (r.locked && r.locked.length) msg += "；跳过锁定项 " + r.locked.length + " 个（请先解锁）";
        notify(msg);
      } catch (e) { notify("批量删除失败: " + e.message, "error"); }
    }

    // ---- 分页 lazy load（默认 50/页）：超多条目下只渲染当前页 ----
    const pgReport = usePager(() => fReportBatches.value, 50);
    const pgFile = usePager(() => fFileBatches.value, 50);
    const pgJobs = usePager(() => fJobs.value, 50);
    const pgSplit = usePager(() => fSplit.value, 50);
    watch(q, () => { pgReport.reset(); pgFile.reset(); pgJobs.reset(); pgSplit.reset(); });

    return { batches, standalone, splitJobs, q, fBatches, fFileBatches, fReportBatches, fJobs, fSplit,
             pgReport, pgFile, pgJobs, pgSplit,
             fmtTime, srcA, srcB, gradeOf,
             isReport, isCustom, isCtis, openBatchEntry, openReportBatchFromList,
             open: (id) => emit("open", id), openBatch: (id) => emit("open-batch", id),
             openSplit: (id) => emit("open-split", id),
             lockJob, unlockJob, delJob, lockBatch, unlockBatch, delBatch,
             editJobLabel, editBatchLabel, delSplit, editSplitLabel, splitGroupsText,
             toggleJob, toggleBatch, isJobSel, isBatchSel, selCount,
             allJobsSel, allBatchesSel, toggleAllJobs, toggleAllBatches, clearSel, bulkDelete };
  },
  template: `
  <div>
    <PageHeader title="作业列表" sub="批次 / 报表对比 / 单文件作业 / 文本拆分作业统一入口，点击行查看详情" />
    <div class="card">
      <div class="toolbar">
        <input class="search-input" v-model="q" placeholder="🔎 搜索作业/批次（按 ID、目录、文件路径）" />
        <span class="toolbar-count">批次 {{ fFileBatches.length }} · 报表对比 {{ fReportBatches.length }} · 单文件作业 {{ fJobs.length }}<template v-if="fSplit.length"> · 拆分 {{ fSplit.length }}</template></span>
      </div>
    </div>

    <!-- 多选批量删除工具条 -->
    <div class="bulk-bar" v-if="selCount">
      <span>已选 <b>{{ selCount }}</b> 项</span>
      <button class="btn ghost" @click="clearSel">清除选择</button>
      <button class="btn danger" @click="bulkDelete">🗑 批量删除选中项</button>
    </div>

    <div class="card" v-if="fReportBatches.length">
      <div class="card-head">🧾 报表对比结果（点击进入查看各报表）
        <div class="head-actions">
          <ListPager :page="pgReport.page" :pages="pgReport.pages" :total="pgReport.total" :size="pgReport.size"
                     @go="pgReport.go" @resize="pgReport.resize" />
        </div>
      </div>      <div>
        <div class="job-row batch-row" v-for="b in pgReport.paged" :key="b.batch.batch_id" @click="openReportBatchFromList(b)">
          <span class="nick-badge" title="报表对比批次">🧾 报表</span>
          <span class="jid">{{ b.batch.batch_id }}</span>
          <span class="badge-status" :class="b.status">{{ b.status }}</span>
          <span class="files">{{ b.batch.dir_a }} ↔ {{ b.batch.dir_b }}</span>
          <span class="group-badge" v-if="b.batch.label" :title="b.batch.label">🏷 {{ b.batch.label }}</span>
          <span class="meta-time">{{ fmtTime(b.batch.created_at) }}</span>
          <span class="count">共 {{ b.total_files }} 报表 · <b style="color:var(--diff-bar)">{{ b.diff_files }}</b> 有差异</span>
          <span class="lock-ico" v-if="b.batch.locked" title="已锁定">🔒</span>
          <span class="row-actions" @click.stop>
            <button class="mini-btn" @click="editBatchLabel(b)">标签</button>
            <button class="mini-btn" v-if="!b.batch.locked" @click="lockBatch(b.batch.batch_id)">锁定</button>
            <button class="mini-btn" v-else @click="unlockBatch(b.batch.batch_id)">解锁</button>
            <button class="mini-btn danger" @click="delBatch(b.batch.batch_id)">删除</button>
          </span>
        </div>
      </div>
    </div>

    <div class="card" v-if="fFileBatches.length">
      <div class="card-head">📦 批量对比批次（点击进入查看各文件）
        <div class="head-actions">
          <label class="sel-all" @click.stop><input type="checkbox" :checked="allBatchesSel" @change="toggleAllBatches" /> 全选批次</label>
          <ListPager :page="pgFile.page" :pages="pgFile.pages" :total="pgFile.total" :size="pgFile.size"
                     @go="pgFile.go" @resize="pgFile.resize" />
        </div>
      </div>      <div>
        <div class="job-row batch-row" :class="{ selected: isBatchSel(b.batch.batch_id) }" v-for="b in pgFile.paged" :key="b.batch.batch_id" @click="openBatchEntry(b)">
          <input class="row-check" type="checkbox" :checked="isBatchSel(b.batch.batch_id)" @click.stop="toggleBatch(b.batch.batch_id)" />
          <span class="nick-badge" v-if="isCtis(b)" title="CTIS 文本对比批次">🔀 CTIS</span>
          <span class="nick-badge" v-else-if="isCustom(b)" title="自定义格式对比批次">📐 段对比</span>
          <span class="jid">{{ b.batch.batch_id }}</span>
          <span class="badge-status" :class="b.status">{{ b.status }}</span>
          <span class="files">{{ b.batch.dir_a }} ↔ {{ b.batch.dir_b }}</span>
          <span class="group-badge" v-if="b.batch.label" :title="b.batch.label">🏷 {{ b.batch.label }}</span>
          <span class="meta-time">{{ fmtTime(b.batch.created_at) }}</span>
          <span class="count">共 {{ b.total_files }} 文件 · <b style="color:var(--diff-bar)">{{ b.diff_files }}</b> 有差异 · {{ b.no_rule_count }} 无规则</span>
          <span class="lock-ico" v-if="b.batch.locked" title="已锁定">🔒</span>
          <span class="row-actions" @click.stop>
            <button class="mini-btn" @click="editBatchLabel(b)">标签</button>
            <button class="mini-btn" v-if="!b.batch.locked" @click="lockBatch(b.batch.batch_id)">锁定</button>
            <button class="mini-btn" v-else @click="unlockBatch(b.batch.batch_id)">解锁</button>
            <button class="mini-btn danger" @click="delBatch(b.batch.batch_id)">删除</button>
          </span>
        </div>
      </div>
    </div>

    <div class="card">
      <div class="card-head">🗂️ 单文件对比作业
        <div class="head-actions">
          <label class="sel-all" v-if="fJobs.length" @click.stop><input type="checkbox" :checked="allJobsSel" @change="toggleAllJobs" /> 全选</label>
          <ListPager :page="pgJobs.page" :pages="pgJobs.pages" :total="pgJobs.total" :size="pgJobs.size"
                     @go="pgJobs.go" @resize="pgJobs.resize" />
        </div>
      </div>
      <div>
        <div class="empty" v-if="!fJobs.length">无匹配作业</div>
        <div class="job-row" :class="{ selected: isJobSel(j.job_id) }" v-for="j in pgJobs.paged" :key="j.job_id" @click="open(j.job_id)">
          <input class="row-check" type="checkbox" :checked="isJobSel(j.job_id)" @click.stop="toggleJob(j.job_id)" />
          <span class="jid">{{ j.job_id }}</span>
          <span class="group-badge" v-if="(j.config||{}).group">{{ j.config.group }}</span>
          <span class="badge-status" :class="j.status">{{ j.status }}</span><span v-if="j.incr_state" class="badge-incr" :class="{ 'incr-empty': j.incr_state === '增量-空文本' }" title="增量过滤（ADDMOD）已生效">⤴ {{ j.incr_state }}</span><span v-if="j.key_warning" class="badge-keywarn" title="主键配置在新旧文本中存在重复键，无法唯一定位记录，该对比配置需要重检">⚠ 主键重复·配置需重检</span>
          <span class="files">{{ j.file_a }} ↔ {{ j.file_b }}</span>
          <span class="group-badge" v-if="j.label" :title="j.label">🏷 {{ j.label }}</span>
          <span class="meta-time">{{ fmtTime(j.created_at) }}</span>
          <span v-if="j.summary" class="badge-grade" :class="gradeOf(j.summary.total_a, j.summary.total_b).cls" :title="'状态情况：数量差 ' + gradeOf(j.summary.total_a, j.summary.total_b).diff + '（偏离 ' + gradeOf(j.summary.total_a, j.summary.total_b).pct + '%）'">{{ gradeOf(j.summary.total_a, j.summary.total_b).label }}</span>
          <span v-if="j.summary" class="count">匹配 {{ j.summary.equal }} · 差异 <b style="color:var(--diff-bar)">{{ j.summary.diff }}</b> · 缺失 {{ j.summary.only_a + j.summary.only_b }}（{{ srcA(j.config) }}有{{ srcB(j.config) }}无 {{ j.summary.only_a }} / {{ srcB(j.config) }}有{{ srcA(j.config) }}无 {{ j.summary.only_b }}）</span>
          <span class="lock-ico" v-if="j.locked" title="已锁定">🔒</span>
          <span class="row-actions" @click.stop>
            <button class="mini-btn" @click="editJobLabel(j)">标签</button>
            <button class="mini-btn" v-if="!j.locked" @click="lockJob(j.job_id)">锁定</button>
            <button class="mini-btn" v-else @click="unlockJob(j.job_id)">解锁</button>
            <button class="mini-btn danger" @click="delJob(j.job_id)">删除</button>
          </span>
        </div>
      </div>
    </div>

    <div class="card" v-if="fSplit.length">
      <div class="card-head">🪓 文本拆分作业
        <div class="head-actions">
          <ListPager :page="pgSplit.page" :pages="pgSplit.pages" :total="pgSplit.total" :size="pgSplit.size"
                     @go="pgSplit.go" @resize="pgSplit.resize" />
        </div>
      </div>
      <div>
        <div class="job-row" v-for="s in pgSplit.paged" :key="s.job_id" @click="openSplit(s.job_id)">
          <span class="jid">{{ s.job_id }}</span>
          <span class="badge-status" :class="s.status">{{ s.status }}</span>
          <span class="group-badge" v-if="s.label" :title="s.label">🏷 {{ s.label }}</span>
          <span class="meta-time">{{ fmtTime(s.created_at) }}</span>
          <span class="count" v-if="s.summary">{{ splitGroupsText(s) }}</span>
          <span class="row-actions" @click.stop>
            <button class="mini-btn" @click="editSplitLabel(s)">标签</button>
            <button class="mini-btn danger" @click="delSplit(s.job_id)">删除</button>
          </span>
        </div>
      </div>
    </div>
  </div>`,
};

/* ---------- 批次详情（二级页：路径+概况 + 可搜索的文件列表） ---------- */
const BatchView = {
  props: ["batchId"],
  emits: ["open", "back", "summary"],
  setup(props, { emit }) {
    const ov = ref(null); const q = ref(""); let timer = null; let stopped = false;
    const starFilter = ref("all");   // all | starred | unstarred
    async function load() {
      try { ov.value = await api("/api/batches/" + props.batchId); } catch (e) {}
      if (!stopped && ov.value && ov.value.status !== "done") timer = setTimeout(load, 1500);
    }
    onMounted(load);
    function baseName(p) { return (p || "").split(/[\\/]/).pop(); }
    const allChildren = computed(() => (ov.value ? ov.value.children : []));
    // 批次内所有子作业共用同一来源名（批量发起时统一透传），取首个子作业的配置即可
    const sourceA = computed(() => srcA(allChildren.value[0] && allChildren.value[0].config));
    const sourceB = computed(() => srcB(allChildren.value[0] && allChildren.value[0].config));
    const starCounts = computed(() => {
      const list = allChildren.value;
      const starred = list.filter(c => c.starred).length;
      return { total: list.length, starred, unstarred: list.length - starred };
    });
    const nickOf = (c) => ((c.config || {}).nickname || "");
    const groupOf = (c) => ((c.config || {}).group || "");
    const groupFilter = ref("");   // "" = 全部组
    // 批次内出现的归属组（去重、含数量），供下拉筛选
    const groupOptions = computed(() => {
      const m = {};
      allChildren.value.forEach(c => { const g = groupOf(c); if (g) m[g] = (m[g] || 0) + 1; });
      return Object.keys(m).sort().map(g => ({ name: g, count: m[g] }));
    });
    const children = computed(() => {
      const ql = q.value.trim().toLowerCase();
      let list = allChildren.value;
      if (groupFilter.value) list = list.filter(c => groupOf(c) === groupFilter.value);
      if (starFilter.value === "starred") list = list.filter(c => c.starred);
      else if (starFilter.value === "unstarred") list = list.filter(c => !c.starred);
      // 支持按昵称 / 文件名 / 归属组模糊搜索
      return !ql ? list : list.filter(c =>
        nickOf(c).toLowerCase().includes(ql) || baseName(c.file_a).toLowerCase().includes(ql)
        || groupOf(c).toLowerCase().includes(ql));
    });
    // 子作业分页 lazy load（默认 100/页）：超大批次只渲染当前页
    const pgChildren = usePager(() => children.value, 100);
    watch([q, groupFilter, starFilter], () => pgChildren.reset());
    async function toggleStar(c) {
      try {
        await api("/api/jobs/" + c.job_id + (c.starred ? "/unstar" : "/star"), { method: "POST" });
        c.starred = !c.starred;   // 本地即时反映，避免等待下次轮询
      } catch (e) { notify("标记失败: " + e.message, "error"); }
    }
    // 导出明细 Excel（Sheet1 对比总览：数量+配置；Sheet2 差异栏位）：随当前归属组筛选导出
    const exportHref = computed(() => "/api/batches/" + (ov.value ? ov.value.batch.batch_id : "")
      + "/export-detail" + (groupFilter.value ? "?group=" + encodeURIComponent(groupFilter.value) : ""));
    // 打包下载：批次内每表的全量导出 Excel（zip）
    const exportAllHref = computed(() => "/api/batches/"
      + (ov.value ? ov.value.batch.batch_id : "") + "/export-all");
    async function editLabel() {
      const cur = (ov.value && ov.value.batch && ov.value.batch.label) || "";
      const v = await askInput({ title: "设置批次标签", message: "留空即清除标签", value: cur, placeholder: "标签内容" });
      if (v === null) return;
      try { await jpost("/api/batches/" + props.batchId + "/label", { label: v });
            if (ov.value && ov.value.batch) ov.value.batch.label = v; }
      catch (e) { notify(e.message, "error"); }
    }
    return { ov, q, children, pgChildren, baseName, nickOf, groupOf, fmtTime, srcA, srcB, sourceA, sourceB, gradeOf,
             starFilter, starCounts, toggleStar, groupFilter, groupOptions, exportHref, exportAllHref,
             editLabel,
             open: (id) => emit("open", id), goBack: () => emit("back") };
  },
  template: `
  <div v-if="ov">
    <PageHeader back-text="返回作业列表" @back="goBack" title="批量对比批次">
      <template #meta>
        <span class="jid">{{ ov.batch.batch_id }}</span>
        <span class="group-badge" v-if="ov.batch.label" :title="ov.batch.label">🏷 {{ ov.batch.label }}</span>
        <button class="mini-btn" @click="editLabel">{{ ov.batch.label ? '改标签' : '加标签' }}</button>
        <span class="badge-status" :class="ov.status">{{ ov.status }}</span>
      </template>
    </PageHeader>
    <div class="card">
      <div class="card-head">📦 批次概况</div>
      <div class="card-body">
        <div v-if="ov.batch.prepare_state === 'preparing'" class="no-rule preparing" style="margin-top:0;">
          ⏳ 正在配对建档，作业将陆续加入并自动开始对比（本页持续刷新）
        </div>
        <div v-else-if="ov.batch.prepare_state === 'failed'" class="no-rule" style="margin-top:0;">
          ❌ 建档失败：{{ ov.batch.prepare_error || '未知原因' }}
        </div>
        <div class="summary-metrics">
          <div class="metric total"><div class="num">{{ ov.total_files }}</div><div class="lbl">对比文件数{{ ov.batch.prepare_state === 'preparing' ? '（建档中）' : '' }}</div></div>
          <div class="metric diff"><div class="num">{{ ov.diff_files }}</div><div class="lbl">存在差异的文件</div></div>
          <div class="metric"><div class="num">{{ ov.done }}</div><div class="lbl">已完成</div></div>
          <div class="metric only"><div class="num">{{ ov.no_rule_count }}</div><div class="lbl">无规则未对比</div></div>
        </div>
        <div style="margin-top:12px;font-size:12px;color:var(--text-soft);">
          {{ sourceA }}：{{ ov.batch.dir_a }}<br/>{{ sourceB }}：{{ ov.batch.dir_b }}<br/>
          触发时间 {{ fmtTime(ov.batch.created_at) }} · 配置来源 {{ ov.batch.config_source }}<template v-if="ov.batch.group_name"> · 对比范围：分组「{{ ov.batch.group_name }}」</template> · 对比批次 <b>{{ ov.batch.cmp_batch || '—' }}</b> · 轮次 <b>{{ ov.batch.cmp_round || '—' }}</b>
        </div>
        <div v-if="ov.batch.no_rule_files && ov.batch.no_rule_files.length" class="no-rule" style="margin-top:10px;">
          ⚠ 无对应对比规则（未对比）：{{ ov.batch.no_rule_files.join('、') }}
        </div>
        <div v-if="ov.batch.extract_failed && ov.batch.extract_failed.length" class="no-rule" style="margin-top:10px;">
          🗜 解压失败（附具体文件名）：{{ ov.batch.extract_failed.join('；') }}
        </div>
        <div v-if="ov.batch.missing_files && ov.batch.missing_files.length" class="no-rule" style="margin-top:10px;">
          ❓ 未能读取到相应文件（昵称 · 通配名）：{{ ov.batch.missing_files.join('；') }}
        </div>
      </div>
    </div>

    <!-- 关注字段监测（昵称命中才有内容；批次运行中显示等待） -->
    <DiscardCard :batch-id="ov.batch.batch_id" />

    <div class="card">
      <div class="card-head">📄 文件对比结果（点击查看单文件详情）
        <div class="head-actions">
          <button class="mini-btn" @click="$emit('summary')"
                  title="打开批次情况总结（概览 / 偏离度 Top / 关注字段监测，可下载 HTML）">📋 批次情况总结</button>
          <a class="mini-btn" :href="exportHref"
             title="导出批次明细 Excel：文件昵称 / 归属组 / 对比配置 / 对比详情（记录总数与差异情况）。已选归属组时只导出该组">⬇ 导出明细 Excel{{ groupFilter ? '（' + groupFilter + '）' : '' }}</a>
          <a class="mini-btn" :href="exportAllHref"
             title="打包下载批次内每个表的全量导出 Excel（zip）">📦 打包下载全部表</a>
          <ListPager :page="pgChildren.page" :pages="pgChildren.pages" :total="pgChildren.total" :size="pgChildren.size"
                     @go="pgChildren.go" @resize="pgChildren.resize" />
        </div>
      </div>
      <div class="card-body" style="padding:12px 18px;">
        <div style="display:flex; flex-wrap:wrap; gap:10px; align-items:center;">
          <div class="star-filter">
            <button :class="{active: starFilter==='all'}" @click="starFilter='all'">全部 {{ starCounts.total }}</button>
            <button :class="{active: starFilter==='starred'}" @click="starFilter='starred'">★ 已星标 {{ starCounts.starred }}</button>
            <button :class="{active: starFilter==='unstarred'}" @click="starFilter='unstarred'">☆ 未星标 {{ starCounts.unstarred }}</button>
          </div>
          <label v-if="groupOptions.length" style="display:flex; align-items:center; gap:6px; font-size:13px; color:var(--text-soft);">
            归属组
            <select v-model="groupFilter" class="styled-select" style="min-width:160px;">
              <option value="">全部组 ({{ starCounts.total }})</option>
              <option v-for="g in groupOptions" :key="g.name" :value="g.name">{{ g.name }} ({{ g.count }})</option>
            </select>
          </label>
        </div>
        <div class="field" style="margin-top:10px;"><input v-model="q" placeholder="🔎 按昵称 / 文件名 / 归属组模糊搜索" /></div>
      </div>
      <div>
        <div class="empty" v-if="!children.length">无匹配文件</div>
        <div class="job-row" v-for="c in pgChildren.paged" :key="c.job_id" @click="open(c.job_id)">
          <button class="star-btn" :class="{on: c.starred}" @click.stop="toggleStar(c)" :title="c.starred ? '取消星标' : '加星标'">{{ c.starred ? '★' : '☆' }}</button>
          <span class="nick-badge" v-if="nickOf(c)" :title="'配置昵称 ' + nickOf(c)">{{ nickOf(c) }}</span>
          <span class="jid">{{ baseName(c.file_a) }}</span>
          <span class="group-badge" v-if="(c.config||{}).group">{{ c.config.group }}</span>
          <span class="badge-status" :class="c.status">{{ c.status }}</span><span v-if="c.incr_state" class="badge-incr" :class="{ 'incr-empty': c.incr_state === '增量-空文本' }" title="增量过滤（ADDMOD）已生效">⤴ {{ c.incr_state }}</span><span v-if="c.key_warning" class="badge-keywarn" title="主键配置在新旧文本中存在重复键，无法唯一定位记录，该对比配置需要重检">⚠ 主键重复·配置需重检</span>
          <span v-if="c.summary" class="badge-grade" :class="gradeOf(c.summary.total_a, c.summary.total_b).cls" :title="'状态情况：数量差 ' + gradeOf(c.summary.total_a, c.summary.total_b).diff + '（偏离 ' + gradeOf(c.summary.total_a, c.summary.total_b).pct + '%）'">{{ gradeOf(c.summary.total_a, c.summary.total_b).label }}</span>
          <span class="count" v-if="c.summary">匹配 {{ c.summary.equal }} · 差异 <b style="color:var(--diff-bar)">{{ c.summary.diff }}</b> · 缺失 {{ c.summary.only_a + c.summary.only_b }}（{{ srcA(c.config) }}有{{ srcB(c.config) }}无 {{ c.summary.only_a }} / {{ srcB(c.config) }}有{{ srcA(c.config) }}无 {{ c.summary.only_b }}）</span>
          <span class="lock-ico" v-if="c.locked" title="已锁定">🔒</span>
        </div>
      </div>
    </div>
  </div>
  <div v-else class="loading">加载中…</div>`,
};

/* ---------- 设置页（AI 接入配置） ---------- */
const SettingsPage = {
  setup() {
    const providers = AI_PROVIDERS;
    const form = reactive({ provider: "custom", protocol: "openai", base_url: "", api_key: "", model: "", timeout: 60, enabled: false, max_concurrency: 2 });
    const apiKeySet = ref(false);
    const busy = ref(false); const toast = ref(""); const test = reactive({ msg: "", ok: null });

    const curModels = computed(() => {
      const p = providers.find(x => x.id === form.provider);
      return p ? p.models : [];
    });
    function pickProvider() {
      const p = providers.find(x => x.id === form.provider);
      if (p && p.id !== "custom") {
        form.base_url = p.base_url;
        form.protocol = p.protocol || "openai";
        if (p.models.length && !p.models.includes(form.model)) form.model = p.models[0];
      }
    }
    async function load() {
      try {
        const r = await api("/api/settings/ai");
        form.base_url = r.base_url || ""; form.model = r.model || "";
        form.timeout = r.timeout || 60; form.enabled = r.enabled; apiKeySet.value = r.api_key_set;
        form.protocol = r.protocol || "openai";
        form.max_concurrency = r.max_concurrency || 2;
        const hit = providers.find(p => p.base_url === r.base_url && (p.protocol || "openai") === form.protocol);
        form.provider = hit ? hit.id : "custom";
      } catch (e) {}
    }
    onMounted(load);

    async function save() {
      busy.value = true; toast.value = ""; test.msg = "";
      try {
        const payload = { protocol: form.protocol, base_url: form.base_url, model: form.model,
                          timeout: Number(form.timeout) || 60, enabled: true,
                          max_concurrency: Number(form.max_concurrency) || 2 };
        if (form.api_key) payload.api_key = form.api_key;  // 留空=不改
        const r = await jpost("/api/settings/ai", payload);
        apiKeySet.value = r.api_key_set; form.api_key = "";
        toast.value = "已保存"; setTimeout(() => toast.value = "", 1500);
      } catch (e) { toast.value = "保存失败: " + e.message; }
      finally { busy.value = false; }
    }
    async function testConn() {
      test.msg = "测试中…"; test.ok = null;
      try {
        const r = await jpost("/api/settings/ai/test", {});
        test.ok = r.ok; test.msg = r.ok ? ("连接成功（" + (r.model || "") + "）") : ("失败: " + (r.error || ""));
      } catch (e) { test.ok = false; test.msg = "失败: " + e.message; }
    }

    // 并发设置
    const rt = reactive({ max_workers: 4, msg: "" });
    async function loadRuntime() {
      try { const r = await api("/api/settings/runtime"); rt.max_workers = r.max_workers; } catch (e) {}
    }
    async function saveRuntime() {
      try {
        const r = await jpost("/api/settings/runtime", { max_workers: Number(rt.max_workers) || 1 });
        rt.max_workers = r.max_workers; rt.msg = "已保存，最大并发分析文件数 = " + r.max_workers;
        setTimeout(() => rt.msg = "", 2500);
      } catch (e) { rt.msg = "保存失败: " + e.message; }
    }
    onMounted(loadRuntime);

    // 列名映射导入
    const imp = reactive({ busy: false, msg: "", ok: null, files: [] });
    function onImpFiles(ev) { imp.files = Array.from(ev.target.files); }
    async function importStructure() {
      if (!imp.files.length) { imp.msg = "请先选择 CSV 文件（报表类型表 + 字段配置表）"; imp.ok = false; return; }
      imp.busy = true; imp.msg = "导入中…"; imp.ok = null;
      try {
        const fd = new FormData();
        for (const f of imp.files) fd.append("files", f);
        const r = await api("/api/configs/import-structure", { method: "POST", body: fd });
        imp.ok = true;
        imp.msg = "导入成功，映射已落库：报表类型 " + r.types + " 个（昵称↔文件名/归属组）、字段 "
          + r.fields + " 条（昵称↔字段名/类型）。对比作业按文件名自动注入列名，AI 分析产物按归属组命名。";
      } catch (e) { imp.ok = false; imp.msg = "导入失败: " + e.message; }
      finally { imp.busy = false; }
    }

    // 基线导入（写入 default.conf）
    const base = reactive({ busy: false, msg: "", ok: null, files: [] });
    function onBaseFiles(ev) { base.files = Array.from(ev.target.files); }
    async function importBaseline() {
      if (!base.files.length) { base.msg = "请先选择基线配置文件（.conf/.txt/.json）"; base.ok = false; return; }
      base.busy = true; base.msg = "导入中…"; base.ok = null;
      try {
        const fd = new FormData();
        for (const f of base.files) fd.append("files", f);
        const r = await api("/api/configs/import-baseline", { method: "POST", body: fd });
        base.ok = true; base.msg = "基线导入成功：" + r.imported + " 条已写入 " + r.file + "（" + (r.nicknames || []).join("、") + "）";
      } catch (e) { base.ok = false; base.msg = "导入失败: " + e.message; }
      finally { base.busy = false; }
    }
    function exportConfigs() { window.location = "/api/configs/export"; }

    // 特殊编码字段映射导入（混合编码栏位转码，如 EBCDIC 文件内嵌 UTF-16 字段）
    const cs = reactive({ busy: false, msg: "", ok: null, file: null, status: null });
    function onCsFile(ev) { cs.file = ev.target.files[0] || null; }
    async function importCharset() {
      if (!cs.file) { cs.msg = "请先选择数据表结构 Excel（.xlsx）"; cs.ok = false; return; }
      cs.busy = true; cs.msg = "导入中…"; cs.ok = null;
      try {
        const fd = new FormData();
        fd.append("file", cs.file);
        const r = await api("/api/charset-map/import", { method: "POST", body: fd });
        cs.ok = true;
        cs.msg = "导入成功：数据表 " + r.tables + " 个、特殊编码字段 " + r.fields + " 条（" + r.source_file + "）"
          + (r.db_available ? "，已同步 H2 镜像" : "");
        loadCharsetStatus();
      } catch (e) { cs.ok = false; cs.msg = "导入失败: " + e.message; }
      finally { cs.busy = false; }
    }
    async function loadCharsetStatus() {
      try { cs.status = await api("/api/charset-map/status"); } catch (e) {}
    }
    onMounted(loadCharsetStatus);

    // CTIS 动态栏位配置导入（'~~' 子栏位展开规则，全量替换）
    const ctdyn = reactive({ busy: false, msg: "", ok: null, file: null, status: null });
    function onCtDynFile(ev) { ctdyn.file = ev.target.files[0] || null; }
    async function importCtDyn() {
      if (!ctdyn.file) { ctdyn.msg = "请先选择动态栏位配置 Excel（.xlsx）"; ctdyn.ok = false; return; }
      ctdyn.busy = true; ctdyn.msg = "导入中…"; ctdyn.ok = null;
      try {
        const fd = new FormData();
        fd.append("file", ctdyn.file);
        const r = await api("/api/ctis-dyn/import", { method: "POST", body: fd });
        ctdyn.ok = true;
        ctdyn.msg = "导入成功：适用文本 " + r.applies + " 行、子栏位 " + r.items + " 条（"
          + (r.dyn_names || []).join("、") + "）"
          + (r.db_available ? "，已同步 H2 镜像" : "");
        if (r.warnings && r.warnings.length) {
          ctdyn.msg += "；告警：" + r.warnings.join("；");
        }
        loadCtDynStatus();
      } catch (e) { ctdyn.ok = false; ctdyn.msg = "导入失败: " + e.message; }
      finally { ctdyn.busy = false; }
    }
    async function loadCtDynStatus() {
      try { ctdyn.status = await api("/api/ctis-dyn/status"); } catch (e) {}
    }
    onMounted(loadCtDynStatus);

    // 关注字段监测导入（DISCARD.xlsx，全量替换）
    const discard = reactive({ busy: false, msg: "", ok: null, file: null, status: null });
    function onDiscardFile(ev) { discard.file = ev.target.files[0] || null; }
    async function importDiscard() {
      if (!discard.file) { discard.msg = "请先选择关注字段监测 Excel（.xlsx）"; discard.ok = false; return; }
      discard.busy = true; discard.msg = "导入中…"; discard.ok = null;
      try {
        const fd = new FormData();
        fd.append("file", discard.file);
        const r = await api("/api/discard-fields/import", { method: "POST", body: fd });
        discard.ok = true;
        discard.msg = "导入成功：监测 " + r.count + " 条 / " + r.nickname_count + " 个昵称（"
          + (r.nicknames || []).join("、") + "）"
          + (r.db_available ? "，已同步 H2 镜像" : "");
        if (r.warnings && r.warnings.length) {
          discard.msg += "；告警：" + r.warnings.join("；");
        }
        loadDiscardStatus();
      } catch (e) { discard.ok = false; discard.msg = "导入失败: " + e.message; }
      finally { discard.busy = false; }
    }
    async function loadDiscardStatus() {
      try { discard.status = await api("/api/discard-fields/status"); } catch (e) {}
    }
    async function clearDiscard() {
      const ok = await askConfirm("确认清空全部关注字段监测配置？后续对比将不再采集监测栏位。", { okText: "清空" });
      if (!ok) return;
      try {
        await api("/api/discard-fields", { method: "DELETE" });
        discard.msg = "已清空监测配置"; discard.ok = true;
        loadDiscardStatus();
      } catch (e) { discard.ok = false; discard.msg = "清空失败: " + e.message; }
    }
    onMounted(loadDiscardStatus);

    // 生成路径设置（任务管理：差异CSV导出目录；AI 分析结果随该目录）
    const dirs = reactive({ export_dir: "", msg: "", ok: null });
    async function loadDirs() {
      try {
        const r = await api("/api/settings/tasks");
        dirs.export_dir = r.export_dir || "";
      } catch (e) {}
    }
    async function saveDirs() {
      dirs.msg = "保存中…"; dirs.ok = null;
      try {
        await jpost("/api/settings/tasks", { export_dir: dirs.export_dir.trim() });
        dirs.ok = true; dirs.msg = "已保存，重新生成的任务将使用新路径";
        setTimeout(() => dirs.msg = "", 2500);
      } catch (e) { dirs.ok = false; dirs.msg = "保存失败: " + e.message; }
    }
    onMounted(loadDirs);

    // 临时解压设置（压缩文件预解压）：目录 + 预解压数量，保存即热生效并持久化 config.ini
    const extract = reactive({ extract_dir: "", pre_extract_count: 8, msg: "", ok: null });
    async function loadExtract() {
      try {
        const r = await api("/api/settings/extract");
        extract.extract_dir = r.extract_dir || "";
        extract.pre_extract_count = r.pre_extract_count || 8;
      } catch (e) {}
    }
    async function saveExtract() {
      extract.msg = "保存中…"; extract.ok = null;
      const n = parseInt(extract.pre_extract_count, 10);
      if (isNaN(n) || n < 1) { extract.ok = false; extract.msg = "预解压数量需为 ≥1 的整数"; return; }
      try {
        const r = await jpost("/api/settings/extract",
                { extract_dir: extract.extract_dir.trim(), pre_extract_count: n });
        extract.extract_dir = r.extract_dir; extract.pre_extract_count = r.pre_extract_count;
        extract.ok = true; extract.msg = "已保存并立即生效";
        setTimeout(() => extract.msg = "", 2500);
      } catch (e) { extract.ok = false; extract.msg = "保存失败: " + e.message; }
    }
    onMounted(loadExtract);

    // JVM 运行参数（config.ini [jvm] jvm_opts）：启动脚本注入，保存后需重启服务生效
    const jvm = reactive({ opts: "", baked: [], msg: "", ok: null });
    async function loadJvm() {
      try {
        const r = await api("/api/settings/jvm");
        jvm.opts = r.opts || ""; jvm.baked = r.baked || [];
      } catch (e) {}
    }
    async function saveJvm() {
      jvm.msg = "保存中…"; jvm.ok = null;
      try {
        const r = await jpost("/api/settings/jvm", { opts: jvm.opts.trim() });
        jvm.opts = r.opts; jvm.ok = true; jvm.msg = r.note || "已保存，重启服务后生效";
      } catch (e) { jvm.ok = false; jvm.msg = "保存失败: " + e.message; }
    }
    onMounted(loadJvm);

    return { providers, form, apiKeySet, busy, toast, test, curModels, pickProvider, save, testConn,
             rt, saveRuntime, imp, onImpFiles, importStructure,
             base, onBaseFiles, importBaseline, exportConfigs,
             cs, onCsFile, importCharset,
             ctdyn, onCtDynFile, importCtDyn,
             discard, onDiscardFile, importDiscard, clearDiscard,
             dirs, loadDirs, saveDirs,
             extract, loadExtract, saveExtract,
             jvm, loadJvm, saveJvm };
  },
  template: `
  <div>
    <PageHeader title="设置" sub="AI 接入 · 数据映射 · 配置版本 · 任务路径 —— 所有修改即时生效并持久化" />
    <div class="section-title">AI 分析</div>
    <div class="card">
    <div class="card-head">🤖 AI 大模型接入设置</div>
    <div class="card-body">
      <p class="ai-hint" style="margin-top:0;">
        支持 <b>OpenAI 兼容</b>（<code>/v1/chat/completions</code>）与 <b>Anthropic 原生</b>（<code>/v1/messages</code>）两种协议。对接 Claude Code 的 API：选「Anthropic Claude（原生）」预设、填 <code>sk-ant-…</code> 密钥即可。仅向模型发送<b>聚合统计</b>，<b>不发送任何单元格原始值</b>；配置保存后<b>即时生效</b>（写入服务端 <code>config.ini</code> [ai] 段，重启后仍有效）。
      </p>
      <div class="form-grid">
        <div class="field"><label>服务商预设</label>
          <select v-model="form.provider" @change="pickProvider">
            <option v-for="p in providers" :key="p.id" :value="p.id">{{ p.name }}</option>
          </select></div>
        <div class="field"><label>协议</label>
          <select v-model="form.protocol">
            <option value="openai">OpenAI 兼容 (/v1/chat/completions)</option>
            <option value="anthropic">Anthropic 原生 (/v1/messages)</option>
          </select></div>
        <div class="field mono"><label>Base URL</label>
          <input v-model="form.base_url" :placeholder="form.protocol==='anthropic' ? 'https://api.anthropic.com' : 'https://api.openai.com/v1'" /></div>
        <div class="field"><label>模型</label>
          <input v-model="form.model" list="model-list" placeholder="模型名称" />
          <datalist id="model-list"><option v-for="m in curModels" :key="m" :value="m" /></datalist></div>
        <div class="field"><label>API Key {{ apiKeySet ? '（已配置，留空不改）' : '' }}</label>
          <input v-model="form.api_key" type="password" :placeholder="apiKeySet ? '●●●●●●（保留现有）' : 'sk-...'" /></div>
        <div class="field"><label>超时（秒）</label>
          <input v-model="form.timeout" type="number" /></div>
        <div class="field"><label>AI 并发上限（1–64）</label>
          <input v-model="form.max_concurrency" type="number" min="1" max="64" /></div>
      </div>
      <div class="btn-row">
        <button class="btn" @click="save" :disabled="busy">{{ busy ? '保存中…' : '保存' }}</button>
        <button class="btn ghost" @click="testConn">测试连接</button>
        <span v-if="test.msg" :style="{ color: test.ok ? 'var(--eq-bar)' : 'var(--diff-bar)', fontSize:'13px' }">{{ test.msg }}</span>
      </div>
    </div>
    </div>

    <div class="card">
      <div class="card-head">⚡ 批量分析并发度</div>
      <div class="card-body">
        <p class="ai-hint" style="margin-top:0;">目录批量对比时，系统按队列依次调度、最多同时分析 N 个文件，避免一次性占满内存/线程。修改即时生效。</p>
        <div class="form-grid">
          <div class="field"><label>单次最大并发分析文件数（1–64）</label>
            <input v-model="rt.max_workers" type="number" min="1" max="64" /></div>
        </div>
        <div class="btn-row">
          <button class="btn" @click="saveRuntime">保存并发设置</button>
          <span v-if="rt.msg" style="font-size:13px;color:var(--eq-bar);">{{ rt.msg }}</span>
        </div>
      </div>
    </div>

    <div class="section-title">数据映射与导入</div>
    <div class="card">
      <div class="card-head">🗺️ 列名映射导入（源系统字段配置）</div>
      <div class="card-body">
        <p class="ai-hint" style="margin-top:0;">上传源系统的两个 CSV，<b>纯映射落库</b>（不生成 .conf 对比配置）：<b>报表类型表</b> bat_report_type_parm（report_id 昵称 ↔ report_file_name 文件名、文件类型、归属组 ownership_group）；<b>字段配置表</b> bat_report_conf_field（report_id 昵称 ↔ field_index 定序的字段清单，含 field_name 字段名、field_format 类型、field_length 长度）。数据落库 H2 表 report_type_parm / report_conf_field：对比作业按文件名自动注入列名，AI 分析阶段附栏位属性辅助归纳。仅本地解析，不外发。</p>
        <div class="field"><label>选择 CSV 文件（可多选，含类型表 + 字段表）</label>
          <input type="file" accept=".csv" multiple @change="onImpFiles" /></div>
        <div class="btn-row">
          <button class="btn" @click="importStructure" :disabled="imp.busy">{{ imp.busy ? '导入中…' : '导入映射' }}</button>
          <span v-if="imp.msg" :style="{ color: imp.ok===false ? 'var(--diff-bar)' : 'var(--eq-bar)', fontSize:'13px' }">{{ imp.msg }}</span>
        </div>
      </div>
    </div>

    <div class="card">
      <div class="card-head">🔤 特殊编码字段映射导入（混合编码转码）</div>
      <div class="card-body">
        <p class="ai-hint" style="margin-top:0;">上传源系统<b>下传数据表结构 Excel</b>（含列名「数据表英文名 / 表内字段序号 / BOCS字段内部存储字符集」的 sheet），仅保留非 E 码的特殊字符集字段（如 UTF-16），落库 H2 表 <code>field_charset</code>。对比作业按<b>文件名匹配表名</b>后，对这些栏位做<b>按值探测转码 UTF-8</b>：仅主机单字节字符集（cp037/cp500/cp1047）文件启用、逐值校验，对侧正常文本原样保留（兼容只有一侧有特殊编码的场景），未命中映射的对比零开销。</p>
        <div class="field"><label>数据表结构 Excel（.xlsx，自动识别全部结构 sheet）</label>
          <input type="file" accept=".xlsx" @change="onCsFile" /></div>
        <div class="btn-row">
          <button class="btn" @click="importCharset" :disabled="cs.busy">{{ cs.busy ? '导入中…' : '导入编码映射' }}</button>
          <span v-if="cs.status" style="font-size:13px;color:var(--text-soft);">已导入：{{ cs.status.tables }} 表 / {{ cs.status.fields }} 字段{{ cs.status.source_file ? '（' + cs.status.source_file + '）' : '' }}{{ cs.status.db_available ? ' · H2 镜像正常' : ' · 纯文件模式' }}</span>
          <span v-if="cs.msg" :style="{ color: cs.ok===false ? 'var(--diff-bar)' : 'var(--eq-bar)', fontSize:'13px' }">{{ cs.msg }}</span>
        </div>
      </div>
    </div>

    <div class="card">
      <div class="card-head">🧩 CTIS 动态栏位配置导入（'~~' 子栏位展开）</div>
      <div class="card-body">
        <p class="ai-hint" style="margin-top:0;">上传 CTIS <b>动态栏位区域说明 Excel</b>（如 BXXF_BUFFER 区域说明）：<b>sheet1</b> 适用清单（适用文本名 = 报表昵称 report_id、关联行序号 = 文本内栏位序号、关联行名称 = 动态栏位逻辑名）；<b>sheet2</b> 子栏位明细（数据项名称 / 主键 / 数据类型长度 / <b>字段编号</b> / 对齐补位 / 描述 / 备注），落库 H2 表 <code>ctis_dyn_field</code> / <code>ctis_dyn_item</code>。启用条件（双条件）：报表类型表 parm_report_type=<b>CTS</b> 且适用清单命中该文本——对比时该栏位按 <code>~~+编号+内容</code> 展开为虚拟子栏位逐项比对（编号配对、顺序无关），结果展示 / 导出 / AI 栏位名按 <code>107-1B（数据项名称）</code> 命名。一个文件只允许一个关联行名称。导入为全量替换。</p>
        <div class="field"><label>动态栏位配置 Excel（.xlsx：sheet1 适用清单 + sheet2 子栏位明细）</label>
          <input type="file" accept=".xlsx" @change="onCtDynFile" /></div>
        <div class="btn-row">
          <button class="btn" @click="importCtDyn" :disabled="ctdyn.busy">{{ ctdyn.busy ? '导入中…' : '导入动态栏位配置' }}</button>
          <span v-if="ctdyn.status" style="font-size:13px;color:var(--text-soft);">已导入：{{ ctdyn.status.applies }} 适用行 / {{ ctdyn.status.items }} 子栏位（{{ (ctdyn.status.dyn_names||[]).join('、') }}，适用文本 {{ (ctdyn.status.texts||[]).join('、') }}）{{ ctdyn.status.db_available ? ' · H2 镜像正常' : ' · 纯文件模式' }}</span>
          <span v-if="ctdyn.msg" :style="{ color: ctdyn.ok===false ? 'var(--diff-bar)' : 'var(--eq-bar)', fontSize:'13px' }">{{ ctdyn.msg }}</span>
        </div>
      </div>
    </div>
    <div class="card">
      <div class="card-head">🔍 关注字段监测导入（DISCARD_COLS）</div>
      <div class="card-body">
        <p class="ai-hint" style="margin-top:0;">上传<b>关注字段监测 Excel</b>（列：文本名称 / 栏位 / 栏位名 / 含义；栏位为 ≥1 整数，1-based 同 KEYSEQ 语义），落库 H2 表 <code>discard_field</code>。批次对比、单文本对比、报表对比时按<b>昵称命中</b>自动采集这些栏位在 A/B 两侧数据行的<b>去重取值</b>（每侧上限 1000 个），批次完成后生成 <code>DISCARD_COLS.csv</code> 于 <code>results/{批次ID}/export/</code>，并在批次详情与批次情况总结中展示。适用于理论上取值范围固定的新旧线枚举型栏位监控。导入为全量替换。</p>
        <div class="field"><label>关注字段监测 Excel（.xlsx：文本名称 / 栏位 / 栏位名 / 含义）</label>
          <input type="file" accept=".xlsx" @change="onDiscardFile" /></div>
        <div class="btn-row">
          <button class="btn" @click="importDiscard" :disabled="discard.busy">{{ discard.busy ? '导入中…' : '导入监测配置' }}</button>
          <button class="btn ghost" v-if="discard.status && discard.status.count" @click="clearDiscard"
                  title="清空全部关注字段监测配置">清空监测配置</button>
          <span v-if="discard.status" style="font-size:13px;color:var(--text-soft);"
                >已导入：{{ discard.status.count }} 条 / {{ discard.status.nickname_count }} 个昵称{{ discard.status.source_file ? '（' + discard.status.source_file + '）' : '' }}{{ discard.status.db_available ? ' · H2 镜像正常' : ' · 纯文件模式' }}</span>
          <span v-if="discard.msg" :style="{ color: discard.ok===false ? 'var(--diff-bar)' : 'var(--eq-bar)', fontSize:'13px' }">{{ discard.msg }}</span>
        </div>
      </div>
    </div>
    <div class="section-title">配置版本管理</div>
    <div class="card">
      <div class="card-head">📥 配置版本管理（基线 / 变更 / 导出）</div>
      <div class="card-body">
        <p class="ai-hint" style="margin-top:0;">
          <b>基线导入</b>：上传 .conf/.txt/.json 配置文件，合并写入 <code>default.conf</code> 作为基线（初次导入用）。
          基线导入会写入 <code>default.conf</code> 并复制生成一份 <code>current.conf</code>。此后页面上的所有配置修改**只写入 <code>current.conf</code>**（扩展文本，含全部字段与修改时间），不改动基线；列名信息来自【列名映射导入】落库的字段映射（对比时按文件名自动注入）。
          <b>配置导出</b>：导出 Excel（Sheet1 现生效版本；Sheet2 基线↔生效对比，含修改标识与修改时间）。
        </p>
        <div class="field"><label>基线配置文件（.conf/.txt/.json，可多选）</label>
          <input type="file" accept=".conf,.txt,.json" multiple @change="onBaseFiles" /></div>
        <div class="btn-row">
          <button class="btn ghost" @click="importBaseline" :disabled="base.busy">{{ base.busy ? '导入中…' : '导入为基线' }}</button>
          <button class="btn" @click="exportConfigs">导出配置 Excel</button>
          <span v-if="base.msg" :style="{ color: base.ok===false ? 'var(--diff-bar)' : 'var(--eq-bar)', fontSize:'13px' }">{{ base.msg }}</span>
        </div>
      </div>
    </div>
    <div class="section-title">任务与路径</div>
    <div class="card">
      <div class="card-head">📁 任务生成路径设置</div>
      <div class="card-body">
        <p class="ai-hint" style="margin-top:0;">「任务管理」页中每次对比完成自动生成的产物目录。留空使用默认 <code>results/{批次ID}/export</code>。<b>差异CSV导出</b>与<b>AI分析（Markdown）</b>均写入该目录；AI 分析文件名为 <code>[归属组]文件昵称_实际文件名.md</code>。修改即时生效，对之后（重新）生成的任务生效。</p>
        <div class="form-grid">
          <div class="field mono"><label>导出目录（差异 CSV + AI 分析）</label>
            <input v-model="dirs.export_dir" placeholder="默认 results/{batch}/export" /></div>
        </div>
        <div class="btn-row">
          <button class="btn" @click="saveDirs">保存路径设置</button>
          <span v-if="dirs.msg" :style="{ color: dirs.ok===false ? 'var(--diff-bar)' : 'var(--eq-bar)', fontSize:'13px' }">{{ dirs.msg }}</span>
        </div>
      </div>
    </div>

    <div class="card">
      <div class="card-head">🗜 临时解压设置（压缩文件预解压）</div>
      <div class="card-body">
        <p class="ai-hint" style="margin-top:0;">目录批量对比时 <code>*.gz</code> 压缩文件（含 <code>.YYYYMMDD+1位标识</code> 日期后缀）先复制到临时解压目录解压再对比，原目录不改动；解压产物随对比完成自动删除。预解压数量 = 已解压待比文本的动态补位上限（按 PRISEQ 优先级排序）。修改保存后<b>立即热生效</b>并持久化到 config.ini。</p>
        <div class="form-grid">
          <div class="field mono"><label>临时解压目录（相对路径按数据目录解析）</label>
            <input v-model="extract.extract_dir" placeholder="默认 ./temp" /></div>
          <div class="field"><label>预解压数量（并行度×2 可按需调整）</label>
            <input v-model="extract.pre_extract_count" type="number" min="1" max="200" placeholder="默认 8" /></div>
        </div>
        <div class="btn-row">
          <button class="btn" @click="saveExtract">保存解压设置</button>
          <span v-if="extract.msg" :style="{ color: extract.ok===false ? 'var(--diff-bar)' : 'var(--eq-bar)', fontSize:'13px' }">{{ extract.msg }}</span>
        </div>
      </div>
    </div>

    <div class="card">
      <div class="card-head">☕ JVM 运行参数（config.ini [jvm]）</div>
      <div class="card-body">
        <p class="ai-hint" style="margin-top:0;">启动脚本在<b>进程启动时</b>读取 config.ini 的 <code>jvm_opts</code> 注入 JVM（当前工作目录优先，其次程序目录）；空 = 仅使用内置缺省（{{ jvm.baked.join(' ') }}）。环境变量 <code>TEXT_DIFF_JAVA_OPTS</code> / <code>JAVA_OPTS</code> 仍可覆盖。保存写入 config.ini 后需<b>重启服务</b>生效（可在 /api/health 的 max_memory_mb 核对实际堆）。</p>
        <div class="field mono">
          <label>JVM 参数（空格分隔，如 -Xmx4g -XX:+UseG1GC）</label>
          <input v-model="jvm.opts" placeholder="如 -Xmx4g；留空 = 仅内置缺省" />
        </div>
        <div class="btn-row">
          <button class="btn" @click="saveJvm">保存 JVM 参数</button>
          <span v-if="jvm.msg" :style="{ color: jvm.ok===false ? 'var(--diff-bar)' : 'var(--eq-bar)', fontSize:'13px' }">{{ jvm.msg }}</span>
        </div>
      </div>
    </div>
    <div class="toast" v-if="toast">{{ toast }}</div>
  </div>`,
};

/* ---------- 任务管理页（批次 → 作业 → 默认生成任务：差异CSV导出 / AI分析） ---------- */
const TASK_TYPE_TEXT = { export_diff: "差异CSV导出", ai_analysis: "AI分析（文本模板）" };
const TASK_STATUS_TEXT = { pending: "待开始", running: "进行中", done: "已完成", failed: "失败" };
const TaskManagerPage = {
  emits: ["open", "open-batch"],
  setup(props, { emit }) {
    const batches = ref([]); const standalone = ref([]);
    const tasks = ref([]);           // 全量任务记录（含 job 元信息）
    const openBatches = reactive(new Set());
    const openJobs = reactive(new Set());
    const selTasks = reactive(new Set()); // 批量重新生成选中的任务
    const runAt = ref("");                // 可选执行时间（datetime-local；空 = 立即）
    const q = ref("");
    const nowSec = ref(Math.floor(Date.now() / 1000)); // running 任务实时计时的走针基准
    setInterval(() => { nowSec.value = Math.floor(Date.now() / 1000); }, 1000);
    let timer = null; let stopped = false;
    async function load() {
      try {
        const [jl, tl] = await Promise.all([api("/api/joblist"), api("/api/tasks")]);
        batches.value = jl.batches; standalone.value = jl.standalone;
        tasks.value = tl.tasks || [];
        // 展开中的未完成批次持续刷新子作业（建档准备中作业陆续加入）
        for (const id of openBatches) {
          const b = batches.value.find(x => x.batch.batch_id === id);
          if (b && b.status !== "done") {
            try { const r = await api("/api/batches/" + id); batchChildren[id] = r.children || []; } catch (e) {}
          }
        }
      } catch (e) {}
      if (!stopped) timer = setTimeout(load, 2500);
    }
    onMounted(load);

    const tasksOf = (jobId) => tasks.value.filter(t => t.job_id === jobId);
    const typeText = (t) => TASK_TYPE_TEXT[t.task_type] || t.task_type;
    const statusText = (t) => TASK_STATUS_TEXT[t.status] || t.status;
    const fileName = (t) => (t.file_a || "").split(/[\\/]/).pop();
    // 执行耗时：完成任务 = 结束-开始；进行中 = 实时已运行（nowSec 走针驱动刷新）；未开始 = 不显示
    const durTask = (t) => {
      if (t.status === "running") return t.started_at > 0 ? "已运行 " + fmtDur(nowSec.value - t.started_at) : "";
      if ((t.status === "done" || t.status === "failed") && t.started_at > 0 && t.finished_at > 0)
        return "耗时 " + fmtDur(t.finished_at - t.started_at);
      return "";
    };
    const durJob = (j) => {
      if (j.status === "running") return j.started_at > 0 ? "已运行 " + fmtDur(nowSec.value - j.started_at) : "";
      if ((j.status === "done" || j.status === "error") && j.started_at > 0 && j.finished_at > 0)
        return "耗时 " + fmtDur(j.finished_at - j.started_at);
      return "";
    };
    const durBatch = (b) => {
      const s = b.batch.started_at || 0, f = b.batch.finished_at || 0;
      if (b.status === "running") return s > 0 ? "已运行 " + fmtDur(nowSec.value - s) : "";
      if ((b.status === "done" || b.status === "error") && s > 0 && f > 0)
        return "总耗时 " + fmtDur(f - s);
      return "";
    };
    function toggle(set, id) { set.has(id) ? set.delete(id) : set.add(id); }
    // 展开批次时懒加载其子作业列表（层级与对比结果批次页一致）
    const batchChildren = reactive({});
    async function toggleBatch(id) {
      if (!openBatches.has(id) && !batchChildren[id]) {
        try {
          const r = await api("/api/batches/" + id);
          batchChildren[id] = r.children || [];
        } catch (e) { batchChildren[id] = []; }
      }
      toggle(openBatches, id);
    }
    async function regen(id) {
      try { await api("/api/tasks/" + id + "/regenerate", { method: "POST" }); await load(); }
      catch (e) { notify("重新生成失败: " + e.message, "error"); }
    }
    function toggleTask(id) { selTasks.has(id) ? selTasks.delete(id) : selTasks.add(id); }
    // 作业级选择：勾选=选中该作业全部任务（差异CSV + AI分析），再点取消
    const jobAllSel = (jobId) => {
      const ts = tasksOf(jobId);
      return ts.length > 0 && ts.every(t => selTasks.has(t.task_id));
    };
    const toggleJobSel = (jobId) => {
      const ids = tasksOf(jobId).map(t => t.task_id);
      if (!ids.length) return;
      if (ids.every(id => selTasks.has(id))) ids.forEach(id => selTasks.delete(id));
      else ids.forEach(id => selTasks.add(id));
    };
    async function batchRegen() {
      if (!selTasks.size) return;
      const ids = [...selTasks];
      let run_at = 0;
      if (runAt.value) {
        const ms = new Date(runAt.value).getTime();
        if (isNaN(ms)) { notify("执行时间格式错误"); return; }
        run_at = Math.floor(ms / 1000);
      }
      const when = run_at ? "定时 " + new Date(run_at * 1000).toLocaleString() : "立即";
      if (!(await askConfirm(`将重新生成选中的 ${ids.length} 个任务（差异CSV/AI分析），执行方式：${when}。确认？`, { okText: "重新生成" }))) return;
      try {
        const r = await jpost("/api/tasks/batch-regenerate", { task_ids: ids, run_at });
        notify(`已提交 ${r.updated}/${r.requested} 个任务` + (run_at ? "（到点自动执行，可在任务行查看定时标记）" : ""));
        selTasks.clear(); runAt.value = "";
        await load();
      } catch (e) { notify("批量重新生成失败: " + e.message, "error"); }
    }
    async function delTask(id) {
      if (!(await askConfirm("确认删除该任务记录？（不删除已生成的产物文件）", { danger: true }))) return;
      try { await api("/api/tasks/" + id, { method: "DELETE" }); await load(); }
      catch (e) { notify(e.message, "error"); }
    }
    // ---- 搜索过滤 + 分页 lazy load（批次/单文件作业默认 50/页） ----
    const ql = computed(() => q.value.trim().toLowerCase());
    const fBatches = computed(() => !ql.value ? batches.value : batches.value.filter(b =>
      (b.batch.batch_id + " " + b.batch.dir_a + " " + b.batch.dir_b + " " + (b.batch.label || "")).toLowerCase().includes(ql.value)));
    const fStandalone = computed(() => !ql.value ? standalone.value : standalone.value.filter(j =>
      (j.job_id + " " + (j.file_a || "") + " " + (j.file_b || "")).toLowerCase().includes(ql.value)));
    const pgBatches = usePager(() => fBatches.value, 50);
    const pgJobs = usePager(() => fStandalone.value, 50);
    watch(q, () => { pgBatches.reset(); pgJobs.reset(); });
    // ---- CSV 分文件管理（2 万条/文件的切割产物） ----
    const openTaskFiles = reactive(new Set());
    const toggleTaskFiles = (id) => toggle(openTaskFiles, id);
    const fileNameOf = (p) => (p || "").split(/[\\/]/).pop();
    const fmtSize = (n) => n >= 1073741824 ? (n / 1073741824).toFixed(1) + " GB"
      : n >= 1048576 ? (n / 1048576).toFixed(1) + " MB"
      : n >= 1024 ? (n / 1024).toFixed(0) + " KB" : (n || 0) + " B";
    const taskZipUrl = (t) => "/api/tasks/" + t.task_id + "/files/zip";
    // 展开批次的子作业分页（默认 100/页，防超大批次展开卡顿）
    const childPg = reactive({});   // batchId -> {page, size}
    const childState = (id) => childPg[id] || (childPg[id] = { page: 1, size: 100 });
    const childTotal = (id) => (batchChildren[id] || []).length;
    const childPages = (id) => Math.max(1, Math.ceil(childTotal(id) / childState(id).size));
    const childPaged = (id) => {
      const st = childState(id);
      return (batchChildren[id] || []).slice((st.page - 1) * st.size, st.page * st.size);
    };
    const childGo = (id, n) => { const st = childState(id); st.page = Math.min(Math.max(1, (n | 0) || 1), childPages(id)); };
    const childResize = (id, s) => { const st = childState(id); st.size = s; st.page = 1; };
    return { batches, standalone, openBatches, openJobs, q, tasksOf, typeText, statusText, fileName,
             durTask, durJob, durBatch,
             fBatches, fStandalone, pgBatches, pgJobs,
             toggle, toggleBatch, batchChildren, regen, delTask, fmtTime,
             childTotal, childPages, childPaged, childGo, childResize,
             selTasks, runAt, jobAllSel, toggleJobSel, batchRegen,
             openTaskFiles, toggleTaskFiles, fileNameOf, fmtSize, taskZipUrl,
             open: (id) => emit("open", id), openBatch: (id) => emit("open-batch", id) };
  },
  template: `
  <div>
    <PageHeader title="任务管理" sub="对比完成后自动生成的产物任务（差异CSV导出 / AI分析）的统一重生成与清理入口" />
    <div class="card">
      <div class="toolbar">
        <input class="search-input" v-model="q" placeholder="🔎 搜索任务（按批次/作业/文件/产物路径）" />
        <span class="toolbar-count">批次 {{ fBatches.length }} · 单文件作业 {{ fStandalone.length }} · 已选 {{ selTasks.size }} 项任务</span>
        <input type="datetime-local" v-model="runAt" style="width:210px;" title="留空 = 立即执行；选择未来时间 = 定时执行" />
        <button class="mini-btn" @click="batchRegen" :disabled="!selTasks.size">⚡ 批量重新生成（差异CSV / AI分析）</button>
        <button class="mini-btn" @click="selTasks.clear()" :disabled="!selTasks.size">清空选择</button>
        <span class="toolbar-note">勾选作业后提交；填了时间则到点自动执行（重启后仍恢复）</span>
        <ListPager :page="pgBatches.page" :pages="pgBatches.pages" :total="pgBatches.total" :size="pgBatches.size"
                   @go="pgBatches.go" @resize="pgBatches.resize" />
      </div>
    </div>

    <div class="card" v-for="b in pgBatches.paged" :key="b.batch.batch_id">
      <div class="job-row batch-row" @click="toggleBatch(b.batch.batch_id)">
        <span class="jid">{{ b.batch.batch_id }}</span>
        <span class="badge-status" :class="b.status">{{ b.status }}</span>
        <span class="files">{{ b.batch.dir_a }} ↔ {{ b.batch.dir_b }}</span>
        <span class="group-badge" v-if="b.batch.label">🏷 {{ b.batch.label }}</span>
        <span class="meta-time">{{ fmtTime(b.batch.created_at) }}</span>
        <span class="count">共 {{ b.total_files }} 文件{{ b.batch.prepare_state === 'preparing' ? '（⏳ 建档中）' : '' }}</span>
        <span class="dur" :class="{ running: b.status==='running' }" v-if="durBatch(b)" title="批次执行耗时（首作业开始 → 末作业完成）">⏱ {{ durBatch(b) }}</span>
        <span class="row-actions" @click.stop>
          <button class="mini-btn" @click="openBatch(b.batch.batch_id)">批次详情</button>
          <button class="mini-btn" @click.stop="toggleBatch(b.batch.batch_id)">{{ openBatches.has(b.batch.batch_id) ? '收起 ▲' : '展开 ▼' }}</button>
        </span>
      </div>
      <template v-if="openBatches.has(b.batch.batch_id)">
        <template v-for="c in childPaged(b.batch.batch_id)" :key="c.job_id">
          <div class="job-row" style="padding-left:32px;" @click="toggle(openJobs, c.job_id)">
            <label class="sel-all" style="margin-left:0;margin-right:10px;" @click.stop title="选中该作业的差异CSV + AI分析任务"><input type="checkbox" :checked="jobAllSel(c.job_id)" @change="toggleJobSel(c.job_id)" /></label>
            <span class="jid">{{ (c.file_a||'').split(/[\\\\/]/).pop() }}</span>
            <span class="badge-status" :class="c.status">{{ c.status }}</span>
            <span class="dur" :class="{ running: c.status==='running' }" v-if="durJob(c)">⏱ {{ durJob(c) }}</span>
            <span class="row-actions" @click.stop>
              <button class="mini-btn" @click="open(c.job_id)">结果</button>
              <button class="mini-btn" v-if="tasksOf(c.job_id).length" @click.stop="toggle(openJobs, c.job_id)">{{ openJobs.has(c.job_id) ? '收起 ▲' : '展开 ▼' }}</button>
            </span>
          </div>
          <template v-if="openJobs.has(c.job_id)">
            <template v-for="t in tasksOf(c.job_id)" :key="t.task_id">
              <div class="job-row" style="padding-left:64px;">
                <span class="jid">{{ typeText(t) }}</span>
                <span class="badge-status" :class="t.status">{{ statusText(t) }}</span>
                <span class="files" :title="t.output_path">{{ t.output_path ? fileNameOf(t.output_path) : (t.error ? '✗ ' + t.error : '—') }}</span>
                <span class="group-badge" v-if="t.output_count > 1" title="每 2 万条一个文件">📦 {{ t.output_count }} 个文件</span>
                <span class="group-badge" v-if="t.trigger==='manual'">手动</span>
                <span class="group-badge" v-if="t.trigger==='batch'">批量</span>
                <span class="group-badge" v-if="t.scheduled_at>0 && t.status==='pending'">⏰ {{ fmtTime(t.scheduled_at) }} 执行</span>
                <span class="meta-time" v-if="t.finished_at">{{ fmtTime(t.finished_at) }}</span>
                <span class="dur" :class="{ running: t.status==='running' }" v-if="durTask(t)">⏱ {{ durTask(t) }}</span>
                <span class="row-actions">
                  <button class="mini-btn" v-if="t.output_count > 1" @click.stop="toggleTaskFiles(t.task_id)">{{ openTaskFiles.has(t.task_id) ? '收起 ▲' : '文件 ▼' }}</button>
                  <a class="mini-btn" v-if="t.output_count > 1" :href="taskZipUrl(t)" title="打包下载全部产物">📦 打包</a>
                  <button class="mini-btn" @click="regen(t.task_id)" :disabled="t.status==='running'">↻ 重新生成</button>
                  <button class="mini-btn danger" @click="delTask(t.task_id)">删除</button>
                </span>
              </div>
              <template v-if="openTaskFiles.has(t.task_id)">
                <div class="job-row" style="padding-left:96px;" v-for="f in (t.outputs || [])" :key="t.task_id + '-' + f.index">
                  <span class="jid">{{ f.name }}</span>
                  <span class="count">{{ fmtSize(f.size) }}</span>
                  <span v-if="!f.exists" class="count" style="color:var(--diff-bar);">✗ 文件不存在（可能已被清理）</span>
                  <span class="row-actions">
                    <a class="mini-btn" v-if="f.exists" :href="'/api/tasks/' + t.task_id + '/files/' + f.index">⬇ 下载</a>
                  </span>
                </div>
              </template>
            </template>
          </template>
        </template>
        <!-- 展开批次的子作业分页（默认 100/页） -->
        <div class="job-row" style="padding-left:32px;" v-if="childPages(b.batch.batch_id) > 1">
          <ListPager :page="childState(b.batch.batch_id).page" :pages="childPages(b.batch.batch_id)"
                     :total="childTotal(b.batch.batch_id)" :size="childState(b.batch.batch_id).size"
                     @go="(n) => childGo(b.batch.batch_id, n)" @resize="(s) => childResize(b.batch.batch_id, s)" />
        </div>
      </template>
    </div>

    <div class="card">
      <div class="card-head">🗂️ 单文件作业任务
        <div class="head-actions">
          <ListPager :page="pgJobs.page" :pages="pgJobs.pages" :total="pgJobs.total" :size="pgJobs.size"
                     @go="pgJobs.go" @resize="pgJobs.resize" />
        </div>
      </div>
      <div>
        <div class="empty" v-if="!fStandalone.length">无作业</div>
        <template v-for="j in pgJobs.paged" :key="j.job_id">
          <div class="job-row" @click="toggle(openJobs, j.job_id)">
            <label class="sel-all" style="margin-left:0;margin-right:10px;" @click.stop title="选中该作业的差异CSV + AI分析任务"><input type="checkbox" :checked="jobAllSel(j.job_id)" @change="toggleJobSel(j.job_id)" /></label>
            <span class="jid">{{ j.job_id }}</span>
            <span class="badge-status" :class="j.status">{{ j.status }}</span>
            <span class="files">{{ j.file_a }} ↔ {{ j.file_b }}</span>
            <span class="dur" :class="{ running: j.status==='running' }" v-if="durJob(j)">⏱ {{ durJob(j) }}</span>
            <span class="row-actions" @click.stop>
              <button class="mini-btn" @click="open(j.job_id)">结果</button>
              <button class="mini-btn" v-if="tasksOf(j.job_id).length" @click.stop="toggle(openJobs, j.job_id)">{{ openJobs.has(j.job_id) ? '收起 ▲' : '展开 ▼' }}</button>
            </span>
          </div>
          <template v-if="openJobs.has(j.job_id)">
            <template v-for="t in tasksOf(j.job_id)" :key="t.task_id">
              <div class="job-row" style="padding-left:32px;">
                <span class="jid">{{ typeText(t) }}</span>
                <span class="badge-status" :class="t.status">{{ statusText(t) }}</span>
                <span class="files" :title="t.output_path">{{ t.output_path ? fileNameOf(t.output_path) : (t.error ? '✗ ' + t.error : '—') }}</span>
                <span class="group-badge" v-if="t.output_count > 1" title="每 2 万条一个文件">📦 {{ t.output_count }} 个文件</span>
                <span class="group-badge" v-if="t.trigger==='manual'">手动</span>
                <span class="group-badge" v-if="t.trigger==='batch'">批量</span>
                <span class="group-badge" v-if="t.scheduled_at>0 && t.status==='pending'">⏰ {{ fmtTime(t.scheduled_at) }} 执行</span>
                <span class="meta-time" v-if="t.finished_at">{{ fmtTime(t.finished_at) }}</span>
                <span class="dur" :class="{ running: t.status==='running' }" v-if="durTask(t)">⏱ {{ durTask(t) }}</span>
                <span class="row-actions">
                  <button class="mini-btn" v-if="t.output_count > 1" @click.stop="toggleTaskFiles(t.task_id)">{{ openTaskFiles.has(t.task_id) ? '收起 ▲' : '文件 ▼' }}</button>
                  <a class="mini-btn" v-if="t.output_count > 1" :href="taskZipUrl(t)" title="打包下载全部产物">📦 打包</a>
                  <button class="mini-btn" @click="regen(t.task_id)" :disabled="t.status==='running'">↻ 重新生成</button>
                  <button class="mini-btn danger" @click="delTask(t.task_id)">删除</button>
                </span>
              </div>
              <template v-if="openTaskFiles.has(t.task_id)">
                <div class="job-row" style="padding-left:64px;" v-for="f in (t.outputs || [])" :key="t.task_id + '-' + f.index">
                  <span class="jid">{{ f.name }}</span>
                  <span class="count">{{ fmtSize(f.size) }}</span>
                  <span v-if="!f.exists" class="count" style="color:var(--diff-bar);">✗ 文件不存在（可能已被清理）</span>
                  <span class="row-actions">
                    <a class="mini-btn" v-if="f.exists" :href="'/api/tasks/' + t.task_id + '/files/' + f.index">⬇ 下载</a>
                  </span>
                </div>
              </template>
            </template>
          </template>
        </template>
      </div>
    </div>
  </div>`,
};

/* ---------- 根组件 ---------- */
/* ---------- 文本拆分：算符中文映射 ---------- */
const SPLIT_OPS = [
  { v: "eq", t: "等于" },
  { v: "contains", t: "包含" },
  { v: "prefix", t: "前缀（以…开头）" },
  { v: "suffix", t: "后缀（以…结尾）" },
  { v: "regex", t: "正则" },
];
const OP_TEXT = { eq: "等于", contains: "包含", prefix: "以…开头", suffix: "以…结尾", regex: "匹配正则" };
function condText(c) { return "列" + c.col + " " + (OP_TEXT[c.op] || c.op) + " " + JSON.stringify(c.value); }
function ruleText(r) { return (r.conditions || []).map(condText).join(" 且 "); }

/* ---------- 文本拆分：配置编辑 + 输入 + 提交 ---------- */
const SplitPage = {
  emits: ["open-split"],
  setup(props, { emit }) {
    const cfg = reactive({ nickname: "", delimiter: " | ", encoding: "auto",
                           trailer_prefix: "|||||", rules: [] });
    const inputs = reactive({ a_path: "", b_path: "" });
    const label = ref("");
    const savedList = ref([]);
    const pickNick = ref("");
    const busy = ref(false);
    const err = ref("");
    const ops = SPLIT_OPS;

    async function loadConfigs() {
      try { const r = await api("/api/split-configs"); savedList.value = r.configs || []; }
      catch (e) {}
    }
    onMounted(loadConfigs);

    function addRule() { cfg.rules.push({ name: "", conditions: [{ col: 1, op: "eq", value: "" }] }); }
    function removeRule(i) { cfg.rules.splice(i, 1); }
    function addCond(r) { r.conditions.push({ col: 1, op: "eq", value: "" }); }
    function removeCond(r, j) { r.conditions.splice(j, 1); }

    async function pickConfig() {
      if (!pickNick.value) return;
      try {
        const c = await api("/api/split-configs/get?nickname=" + encodeURIComponent(pickNick.value));
        cfg.nickname = c.nickname; cfg.delimiter = c.delimiter; cfg.encoding = c.encoding;
        cfg.trailer_prefix = c.trailer_prefix; cfg.rules = c.rules || [];
        err.value = "";
      } catch (e) { err.value = e.message; }
    }

    async function saveConfig() {
      err.value = "";
      try {
        await jpost("/api/split-configs", {
          nickname: cfg.nickname, delimiter: cfg.delimiter, encoding: cfg.encoding,
          trailer_prefix: cfg.trailer_prefix, rules: cfg.rules });
        await loadConfigs();
        pickNick.value = cfg.nickname;
      } catch (e) { err.value = e.message; }
    }

    async function uploadTo(side, ev) {
      const file = ev.target.files[0]; if (!file) return;
      const fd = new FormData(); fd.append("files", file);
      try {
        const r = await api("/api/upload", { method: "POST", body: fd });
        if (r.files && r.files[0]) inputs[side + "_path"] = r.files[0].path;
      } catch (e) { err.value = e.message; }
    }

    async function submit() {
      err.value = "";
      const ins = {};
      if (inputs.a_path) ins.a = inputs.a_path;
      if (inputs.b_path) ins.b = inputs.b_path;
      if (!ins.a && !ins.b) { err.value = "至少需要一个输入（A 或 B）"; return; }
      busy.value = true;
      try {
        const r = await jpost("/api/split", {
          inputs: ins,
          config: { nickname: cfg.nickname, delimiter: cfg.delimiter, encoding: cfg.encoding,
                    trailer_prefix: cfg.trailer_prefix, rules: cfg.rules },
          label: label.value });
        emit("open-split", r.job_id);
      } catch (e) { err.value = e.message; }
      finally { busy.value = false; }
    }

    return { cfg, inputs, label, savedList, pickNick, busy, err, ops,
             addRule, removeRule, addCond, removeCond, pickConfig, saveConfig, uploadTo, submit };
  },
  template: `
  <div>
    <PageHeader title="文本拆分" sub="按规则把输入文本拆成组（A 必填，B 可选），提交后生成拆分作业" />
    <div class="card">
      <div class="card-head">🪓 文本拆分 · 配置</div>
      <div class="card-body" style="padding:14px 18px;">
        <div class="field-row" style="display:flex;gap:12px;flex-wrap:wrap;align-items:flex-end;">
          <label class="field" style="flex:1;min-width:160px;"><span>配置昵称</span>
            <input v-model="cfg.nickname" placeholder="如 INCT0101-按险种拆分" /></label>
          <label class="field"><span>分隔符</span><input v-model="cfg.delimiter" /></label>
          <label class="field"><span>编码</span><input v-model="cfg.encoding" /></label>
          <label class="field"><span>尾部前缀</span><input v-model="cfg.trailer_prefix" /></label>
        </div>
        <div class="field-row" style="display:flex;gap:8px;align-items:flex-end;margin-top:8px;">
          <label class="field"><span>载入已存配置</span>
            <select v-model="pickNick">
              <option value="">—</option>
              <option v-for="c in savedList" :key="c.nickname" :value="c.nickname">{{ c.nickname }}</option>
            </select></label>
          <button class="btn ghost" @click="pickConfig">载入</button>
          <button class="btn ghost" @click="saveConfig">💾 保存配置</button>
        </div>

        <div style="margin-top:14px;">
          <div v-for="(r, i) in cfg.rules" :key="i" class="card" style="margin:8px 0;">
            <div class="card-body" style="padding:10px 14px;">
              <div style="display:flex;gap:8px;align-items:center;">
                <b>规则 {{ i + 1 }}</b>
                <input v-model="r.name" placeholder="组名（如 养老）" style="flex:1;" />
                <button class="mini-btn" @click="addCond(r)">+ 条件</button>
                <button class="mini-btn danger" @click="removeRule(i)">删规则</button>
              </div>
              <div v-for="(c, j) in r.conditions" :key="j"
                   style="display:flex;gap:6px;align-items:center;margin-top:6px;">
                <span>列</span>
                <input type="number" min="1" v-model.number="c.col" style="width:64px;" />
                <select v-model="c.op">
                  <option v-for="o in ops" :key="o.v" :value="o.v">{{ o.t }}</option>
                </select>
                <input v-model="c.value" placeholder="值 / 正则" style="flex:1;" />
                <button class="mini-btn danger" @click="removeCond(r, j)">×</button>
              </div>
            </div>
          </div>
          <button class="btn ghost" @click="addRule">+ 新增规则</button>
          <span class="hint" style="margin-left:10px;color:var(--muted);">多条件为「且」关系；首条命中即归属，未命中归「其他」。</span>
        </div>
      </div>
    </div>

    <div class="card">
      <div class="card-head">📥 输入文件（A 必填，B 可选）</div>
      <div class="card-body" style="padding:14px 18px;">
        <label class="field"><span>A 路径</span>
          <input v-model="inputs.a_path" placeholder="服务器文件路径" /></label>
        <input type="file" @change="uploadTo('a', $event)" style="margin:6px 0 12px;" />
        <label class="field"><span>B 路径（可选）</span>
          <input v-model="inputs.b_path" placeholder="服务器文件路径（可留空）" /></label>
        <input type="file" @change="uploadTo('b', $event)" style="margin:6px 0 12px;" />
        <label class="field"><span>标签（可选，标注用途/来源）</span>
          <input v-model="label" placeholder="如：月结-按险种拆分" /></label>
        <div style="margin-top:10px;">
          <button class="btn" :disabled="busy" @click="submit">{{ busy ? '提交中…' : '开始拆分' }}</button>
        </div>
        <div v-if="err" class="err" style="color:var(--diff-bar);margin-top:10px;">{{ err }}</div>
      </div>
    </div>
  </div>`,
};

/* ---------- 文本拆分结果页 ---------- */
const SplitResultView = {
  props: ["jobId"],
  emits: ["back"],
  setup(props, { emit }) {
    const meta = ref(null);
    const opened = reactive({});   // key `${side}:${group}` -> {rows, total}
    let timer = null; let stopped = false;

    async function load() {
      try {
        const r = await api("/api/split-jobs/" + props.jobId);
        meta.value = r.meta;
      } catch (e) {}
      if (!stopped && meta.value && !["done", "error"].includes(meta.value.status))
        timer = setTimeout(load, 1500);
    }
    onMounted(load);

    async function openGroup(side, group) {
      const key = side + ":" + group;
      if (opened[key]) { delete opened[key]; return; }
      try {
        const r = await api("/api/split-jobs/" + props.jobId + "/rows?side=" + side
                            + "&group=" + encodeURIComponent(group) + "&offset=0&limit=200");
        opened[key] = { rows: r.rows, total: r.total };
      } catch (e) { notify(e.message, "error"); }
    }
    const isOpen = (side, group) => !!opened[side + ":" + group];
    const exportUrl = (fmt) => "/api/split-jobs/" + props.jobId + "/export?fmt=" + fmt;

    return { meta, opened, openGroup, isOpen, exportUrl, ruleText, fmtTime,
             sides: ["a", "b"], back: () => emit("back") };
  },
  template: `
  <div v-if="meta">
    <PageHeader back-text="返回作业列表" @back="back" title="拆分结果">
      <template #meta>
        <span class="jid">{{ meta.job_id }}</span>
        <span class="group-badge" v-if="meta.label" :title="meta.label">🏷 {{ meta.label }}</span>
        <span class="badge-status" :class="meta.status">{{ meta.status }}</span>
        <span v-if="meta.key_warning" class="badge-keywarn" title="主键配置在新旧文本中存在重复键，无法唯一定位记录，该对比配置需要重检">⚠ 主键重复·配置需重检</span>
      </template>
    </PageHeader>
    <div class="card">
      <div class="card-head">🪓 拆分概览</div>
      <div class="card-body" style="padding:12px 18px;">
        <div v-if="meta.error" class="err" style="color:var(--diff-bar);">{{ meta.error }}</div>
        <div><b>拆分规则：</b></div>
        <ul>
          <li v-for="(r, i) in (meta.config||{}).rules" :key="i">
            <b>{{ r.name }}</b>：{{ ruleText(r) }}
          </li>
          <li>其他：以上规则均未命中的记录</li>
        </ul>
        <div style="margin-top:8px;">
          <a class="btn ghost" :href="exportUrl('zip')">⬇ 导出各组文本(zip)</a>
          <a class="btn ghost" :href="exportUrl('xlsx')">⬇ 导出汇总(xlsx)</a>
        </div>
      </div>
    </div>

    <template v-for="side in sides" :key="side">
      <div class="card" v-if="meta.summary && meta.summary[side]">
      <div class="card-head">输入 {{ side.toUpperCase() }} · 共 {{ meta.summary[side].total }} 行
        · trailer {{ meta.summary[side].trailer }} 行</div>
      <div>
        <div class="job-row" v-for="g in meta.summary[side].groups" :key="g.name"
             @click="openGroup(side, g.name)">
          <span class="jid">{{ g.name }}</span>
          <span class="count">{{ g.count }} 条</span>
          <span class="meta-time">{{ isOpen(side, g.name) ? '▲ 收起' : '▼ 查看' }}</span>
        </div>
        <template v-for="g in meta.summary[side].groups" :key="g.name + '-body'">
          <div v-if="isOpen(side, g.name)" class="saved-content"
               style="white-space:pre-wrap;padding:8px 14px;max-height:320px;overflow:auto;">
            <div v-if="opened[side + ':' + g.name].total > opened[side + ':' + g.name].rows.length"
                 style="color:var(--muted);">（仅展示前 {{ opened[side + ':' + g.name].rows.length }} / {{ opened[side + ':' + g.name].total }} 行，完整请导出）</div>
            <div v-for="(ln, k) in opened[side + ':' + g.name].rows" :key="k">{{ ln }}</div>
          </div>
        </template>
      </div>
      </div>
    </template>
  </div>
  <div v-else class="empty">加载中…</div>`,
};

/* ---------- 报表对比（header 模板核对） ---------- */
const REPORT_STATUS = { pending: "待开始", running: "进行中", done: "已完成", error: "失败", stopped: "已终止" };

/* 报表行渲染：业务行按栏位双栏对照（差异栏位字符级高亮），表头/表尾整行对照。 */
const ReportRowRec = {
  props: ["row", "sourceA", "sourceB", "section", "fieldNames"],
  setup(props) {
    const isOpen = ref(false); // 条目一律默认收起，点击展开（含表头/表尾块）
    const toggle = () => { isOpen.value = !isOpen.value; };
    // 整行原貌（报表业务行 a_raw/b_raw；折行记录含 \n，单格整体渲染）优先，缺省回退栏位切分；
    // 单侧缺失（仅对方有）不得回填对侧内容——B 侧必须显示「无此行」
    const aDisp = computed(() => props.row.a_raw != null ? [props.row.a_raw]
        : props.row.status === 'unmatched_b' ? [] : (props.row.a_cols || []));
    const bDisp = computed(() => props.row.b_raw != null ? [props.row.b_raw]
        : props.row.status === 'unmatched_a' ? []
        : (props.row.b_cols || props.row.a_cols || []));
    const maxLen = computed(() => Math.max(aDisp.value.length, bDisp.value.length, 1));
    const isLine = computed(() => maxLen.value === 1); // 整行对照（表头/表尾物理行 + 表体整行模式）
    const isFolded = computed(() => (props.row.a_raw || props.row.b_raw || "").includes("\n"));
    const diffSet = computed(() => new Set(props.row.diff_cols || []));
    const showLcol = computed(() => isLine.value && props.section === 'data'
        && props.row.status === 'diff' && diffSet.value.size > 0); // 差异栏位左列仅业务区展示
    // 差异位标签：折行记录/表头表尾块差异位 = 物理行号「第N行」；业务行 = 导出映射「列号·列名」
    const colName = (c) => ((props.fieldNames || [])[c]) || ('栏位' + (c + 1));
    const colLabel = (c) => isFolded.value ? '第' + (c + 1) + '行' : (c + 1) + '·' + colName(c);
    const diffLabelText = computed(() => (props.row.diff_cols || []).map(colLabel).join(', '));
    function segs(side, i) {
      if (!diffSet.value.has(i)) {
        return [{ text: (side === "a" ? aDisp.value : bDisp.value)[i] || "", cls: "" }];
      }
      const a = aDisp.value[i] || "";
      const b = bDisp.value[i] || "";
      return segments(side === "a" ? a : b, computeOpcodes(a, b), side);
    }
    function statusLabel(s) {
      const a = props.sourceA || "A", b = props.sourceB || "B";
      return { equal: "完全匹配", diff: "部分匹配", unmatched_a: "仅" + a + "有", unmatched_b: "仅" + b + "有" }[s] || s;
    }
    // 表头/表尾块：整块单柄横向滚动（格内容不再各自出滚动条）；各条目独立滚动，不做跨条目联动
    const isBlock = computed(() => props.section === 'header' || props.section === 'footer');
    return { isOpen, toggle, maxLen, isLine, isFolded, isBlock, showLcol, diffSet, segs, statusLabel, aDisp, bDisp, colName, colLabel, diffLabelText };
  },
  template: `
  <div class="rec" :class="['s-' + row.status, isOpen ? 'open' : '']">
    <div class="rec-head" @click="toggle">
      <span class="caret">▶</span>
      <span class="key">{{ (row.key || '').split(String.fromCharCode(31)).join(':') }}<template v-if="isBlock && maxLen > 1">（1~{{ maxLen }}行）</template></span>
      <span class="tag" :class="row.status">{{ statusLabel(row.status) }}</span>
      <span class="meta" v-if="row.status==='diff' && row.diff_cols && row.diff_cols.length">{{ showLcol ? '差异栏位: ' + diffLabelText : '内容存在差异' }}</span>
    </div>
    <div class="rec-body" :class="{ hscroll: isBlock }" v-if="isOpen">
      <div class="recgrid" :class="[{ 'with-lcol': showLcol }, { hblock: isBlock }]">
        <template v-if="isLine">
          <div class="ghdr lcol-head" v-if="showLcol">差异栏位</div>
          <div class="ghdr">{{ sourceA || 'A' }}<span class="absent-note" v-if="!aDisp.length"> · 无此行</span></div>
          <div class="ghdr">{{ sourceB || 'B' }}<span class="absent-note" v-if="!bDisp.length"> · 无此行</span></div>
          <div class="lcol" v-if="showLcol">
            <span class="lcol-item" v-for="c in row.diff_cols" :key="c" :title="colName(c)">{{ colLabel(c) }}</span>
          </div>
          <div class="gcell" :class="{ diff: diffSet.has(0) }">
            <span class="cv"><span v-for="(s,si) in segs('a', 0)" :key="si" :class="s.cls">{{ s.text }}</span></span>
          </div>
          <div class="gcell" :class="{ diff: diffSet.has(0) }">
            <span class="cv"><span v-for="(s,si) in segs('b', 0)" :key="si" :class="s.cls">{{ s.text }}</span></span>
          </div>
        </template>
        <template v-else>
          <div class="ghdr">{{ sourceA || 'A' }}<span class="absent-note" v-if="!aDisp.length"> · 无此行</span></div>
          <div class="ghdr">{{ sourceB || 'B' }}<span class="absent-note" v-if="!bDisp.length"> · 无此行</span></div>
          <template v-for="i in maxLen" :key="i">
            <div class="gcell" :class="{ diff: diffSet.has(i-1) }">
              <template v-if="aDisp.length"><span class="ci">{{ i }}</span><span class="cv"><span v-if="aDisp[i-1] === undefined" class="absent-note">（无此行）</span><span v-else v-for="(s,si) in segs('a', i-1)" :key="si" :class="s.cls">{{ s.text }}</span></span></template>
            </div>
            <div class="gcell" :class="{ diff: diffSet.has(i-1) }">
              <template v-if="bDisp.length"><span class="ci">{{ i }}</span><span class="cv"><span v-if="bDisp[i-1] === undefined" class="absent-note">（无此行）</span><span v-else v-for="(s,si) in segs('b', i-1)" :key="si" :class="s.cls">{{ s.text }}</span></span></template>
            </div>
          </template>
        </template>
      </div>
    </div>
  </div>`,
};

/* 报表分区面板：懒加载分页读取某一 section/zone（base 可换 custom-jobs 等同构端点）。 */
const ReportZone = {
  components: { ReportRowRec },
  props: ["jobId", "section", "zone", "title", "tone", "defaultOpen", "sourceA", "sourceB", "base", "fieldNames"],
  setup(props) {
    const apiBase = computed(() => props.base || "report-jobs");
    const open = ref(!!props.defaultOpen);
    const rows = ref([]); const total = ref(0);
    const page = ref(1); const pageSize = ref(20);
    const loading = ref(false);
    async function loadPage(p) {
      loading.value = true;
      try {
        const off = (p - 1) * pageSize.value;
        const r = await api(`/api/${apiBase.value}/${props.jobId}/result?section=${props.section}&zone=${props.zone}&offset=${off}&limit=${pageSize.value}`);
        rows.value = r.rows || []; total.value = r.total || 0; page.value = p;
      } catch (e) {} finally { loading.value = false; }
    }
    function ensureOpen() { if (open.value && !rows.value.length && !loading.value) loadPage(1); }
    function toggle() { open.value = !open.value; if (open.value) ensureOpen(); }
    onMounted(() => { if (open.value) ensureOpen(); }); // 默认展开的分区挂载即加载
    const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)));
    return { open, toggle, ensureOpen, rows, total, page, totalPages, loadPage, loading };
  },
  template: `
  <div class="zone" :class="tone">
    <div class="zone-head" @click="toggle">
      <span class="bar"></span><span class="title">{{ title }}</span>
      <span class="count">{{ total }}</span>
      <span class="caret2">{{ open ? '▾' : '▸' }}</span>
    </div>
    <div class="zone-body" v-if="open">
      <div class="empty" v-if="!loading && !rows.length">（无记录）</div>
      <ReportRowRec v-for="r in rows" :key="r.key + r.status" :row="r" :sourceA="sourceA" :sourceB="sourceB" :section="section" :field-names="fieldNames" />
      <div class="zone-pager" v-if="total > pageSize" @click.stop>
        <button class="mini-btn" :disabled="page<=1" @click="loadPage(page-1)">上一页</button>
        <span>{{ page }} / {{ totalPages }}</span>
        <button class="mini-btn" :disabled="page>=totalPages" @click="loadPage(page+1)">下一页</button>
      </div>
    </div>
  </div>`,
};

/* 【报表对比】首页：路径对比 / 上传文件对比（模板路径或 .header 文件）。批次结果统一在作业列表查看。 */
const ReportPage = {
  emits: ["open-batch"],
  setup(props, { emit }) {
    const mode = ref("path");
    const form = reactive({ template_path: "", dir_a: "", dir_b: "", label: "", source_a: "", source_b: "" });
    const up = reactive({ files_a: [], files_b: [], files_tpl: [] });
    const busy = ref(false); const err = ref("");
    function onFiles(ev, target) { up[target] = Array.from(ev.target.files); }
    async function submitPath() {
      err.value = "";
      if (!form.dir_a || !form.dir_b) { err.value = "请填写数据文本路径 A / B"; return; }
      busy.value = true;
      try {
        const r = await jpost("/api/report-compare", {
          template_path: form.template_path || null, dir_a: form.dir_a, dir_b: form.dir_b, label: form.label || null,
          source_a: form.source_a || null, source_b: form.source_b || null,
        });
        emit("open-batch", r.batch_id);
      } catch (e) { err.value = e.message; } finally { busy.value = false; }
    }
    async function submitUpload() {
      err.value = "";
      if (!up.files_a.length || !up.files_b.length) { err.value = "请选择 A / B 两侧报表文件"; return; }
      busy.value = true;
      try {
        const fd = new FormData();
        up.files_a.forEach(f => fd.append("files_a", f));
        up.files_b.forEach(f => fd.append("files_b", f));
        up.files_tpl.forEach(f => fd.append("files_tpl", f));
        const r = await api("/api/report-compare/upload", { method: "POST", body: fd });
        const r2 = await jpost("/api/report-compare", {
          template_path: r.template_dir || null, dir_a: r.dir_a, dir_b: r.dir_b, label: form.label || null,
          source_a: form.source_a || null, source_b: form.source_b || null,
        });
        emit("open-batch", r2.batch_id);
      } catch (e) { err.value = e.message; } finally { busy.value = false; }
    }
    return { mode, form, up, busy, err, submitPath, submitUpload, onFiles, REPORT_STATUS, fmtTime };
  },
  template: `
  <div>
    <PageHeader title="报表对比" sub="基于 header 模板核对报表文件（表头 / 业务内容 / 表尾 三分区对照）" />
    <div class="card">
      <div class="card-head">🧾 新建报表对比（基于 header 模板核对）</div>
      <div class="card-body">
        <div class="star-filter" style="margin-bottom:12px;">
          <button :class="{active: mode==='path'}" @click="mode='path'">📁 设置比对路径</button>
          <button :class="{active: mode==='upload'}" @click="mode='upload'">⬆ 上传文件对比</button>
        </div>
        <div class="form-grid single">
          <label class="field">模板路径（目录或单个 .header 模板文件；留空则在数据目录旁查找 &lt;文件名去扩展名&gt;.header）
            <input v-if="mode==='path'" v-model="form.template_path" placeholder="如 O:\\Data\\templates 或 O:\\Data\\01.CORD900U.header" spellcheck="false" />
            <input v-else type="file" multiple accept=".header" @change="onFiles($event,'files_tpl')" />
          </label>
          <label class="field">数据文本路径 A（旧）
            <input v-if="mode==='path'" v-model="form.dir_a" placeholder="如 O:\\CodeRepos\\ExampleData" spellcheck="false" />
            <input v-else type="file" multiple @change="onFiles($event,'files_a')" />
          </label>
          <label class="field">数据文本路径 B（新）
            <input v-if="mode==='path'" v-model="form.dir_b" placeholder="如 O:\\Data\\reports_new" spellcheck="false" />
            <input v-else type="file" multiple @change="onFiles($event,'files_b')" />
          </label>
          <label class="field">批次标签（可选，显示在批次任务中）
            <input v-model="form.label" placeholder="如 2026-07-10 夜批量核对" />
          </label>
          <label class="field">A 来源名称（可选，替代“A”显示于监测表/总结报告）
            <input v-model="form.source_a" placeholder="默认 A，如 旧线/IBM" />
          </label>
          <label class="field">B 来源名称（可选，替代“B”显示于监测表/总结报告）
            <input v-model="form.source_b" placeholder="默认 B，如 新线/X86" />
          </label>
        </div>
        <div class="btn-row" style="margin-top:10px;">
          <button v-if="mode==='path'" class="btn primary" :disabled="busy" @click="submitPath">{{ busy ? '提交中…' : '开始报表核对' }}</button>
          <button v-else class="btn primary" :disabled="busy" @click="submitUpload">{{ busy ? '上传并对比中…' : '上传并开始报表核对' }}</button>
          <span class="count" style="align-self:center;">两目录按文件名配对非 header 文件；昵称取列名映射（#占位符通配）；主键/跳过栏位按昵称从 conf 规则读取（未命中按整行对比）</span>
        </div>
        <div class="error-box" v-if="err">{{ err }}</div>
      </div>
    </div>
  </div>`,
};

/* 报表批次详情：总览 + 子作业（昵称/文件名/条数/差异统计）+ 导出明细 Excel。 */
const ReportBatchView = {
  emits: ["open", "back", "summary"],
  props: { batchId: [String, Number], backText: { type: String, default: "返回报表对比" } },
  setup(props, { emit }) {
    const ov = ref(null); const q = ref(""); let timer = null; let stopped = false;
    async function load() {
      if (stopped) return;
      try {
        ov.value = await api("/api/batches/" + props.batchId);
        const running = (ov.value.children || []).some(c => c.status === "pending" || c.status === "running");
        if (running) timer = setTimeout(load, 2000);
      } catch (e) {}
    }
    onMounted(load);
    const children = computed(() => {
      const ql = q.value.trim().toLowerCase();
      let list = (ov.value && ov.value.children) || [];
      return !ql ? list : list.filter(c =>
        ((c.label || "") + baseName2(c.file_a)).toLowerCase().includes(ql));
    });
    const exportHref = computed(() => "/api/report-batches/" + props.batchId + "/export-detail");
    // 子报表分页 lazy load（默认 100/页）
    const pgChildren = usePager(() => children.value, 100);
    watch(q, () => pgChildren.reset());
    async function editLabel() {
      const cur = (ov.value && ov.value.batch && ov.value.batch.label) || "";
      const v = await askInput({ title: "设置批次标签", message: "留空即清除标签", value: cur, placeholder: "标签内容" });
      if (v === null) return;
      try { await jpost("/api/batches/" + props.batchId + "/label", { label: v });
            if (ov.value && ov.value.batch) ov.value.batch.label = v; }
      catch (e) { notify(e.message, "error"); }
    }
    function baseName2(p) { return (p || "").split(/[\\/]/).pop(); }
    return { ov, q, children, pgChildren, exportHref, fmtTime, baseName: baseName2, editLabel, REPORT_STATUS,
             open: (id) => emit("open", id), goBack: () => emit("back") };
  },
  template: `
  <div v-if="ov">
    <PageHeader :back-text="backText" @back="goBack" title="报表对比批次">
      <template #meta>
        <span class="jid">{{ ov.batch.batch_id }}</span>
        <span class="group-badge" v-if="ov.batch.label">🏷 {{ ov.batch.label }}</span>
        <button class="mini-btn" @click="editLabel">{{ ov.batch.label ? '改标签' : '加标签' }}</button>
        <span class="badge-status" :class="ov.status">{{ REPORT_STATUS[ov.status] || ov.status }}</span>
      </template>
      <template #actions>
        <button class="mini-btn" @click="$emit('summary')"
                title="打开批次情况总结（概览 / 偏离度 Top / 关注字段监测，可下载 HTML）">📋 批次情况总结</button>
        <a class="mini-btn" :href="exportHref"
           title="Sheet1 报表对比总览（昵称/文件名/总条数/差异统计/条数核对），Sheet2 差异明细">⬇ 导出明细 Excel</a>
      </template>
    </PageHeader>
    <div class="card">
      <div class="card-head">🧾 批次概况</div>
      <div class="card-body">
        <div class="summary-metrics">
          <div class="metric total"><div class="num">{{ ov.total_files }}</div><div class="lbl">报表数</div></div>
          <div class="metric diff"><div class="num">{{ ov.diff_files }}</div><div class="lbl">存在差异</div></div>
          <div class="metric"><div class="num">{{ ov.done }}</div><div class="lbl">已完成</div></div>
        </div>
        <div style="margin-top:12px;font-size:12px;color:var(--text-soft);">
          A：{{ ov.batch.dir_a }}<br/>B：{{ ov.batch.dir_b }}<br/>{{ ov.batch.config_source }}
        </div>
        <div v-if="ov.batch.no_rule_files && ov.batch.no_rule_files.length" class="no-rule" style="margin-top:10px;">
          ⚠ 未配对（B 侧无同名文件）：{{ ov.batch.no_rule_files.join('、') }}
        </div>
      </div>
    </div>
    <!-- 关注字段监测（昵称命中才有内容；批次运行中显示等待） -->
    <DiscardCard :batch-id="ov.batch.batch_id" />
    <div class="card">
      <div class="card-head">📄 报表核对结果（点击查看单报表详情）
        <div class="head-actions">
          <ListPager :page="pgChildren.page" :pages="pgChildren.pages" :total="pgChildren.total" :size="pgChildren.size"
                     @go="pgChildren.go" @resize="pgChildren.resize" />
        </div>
      </div>
      <div class="card-body" style="padding:12px 18px;">
        <div class="field"><input v-model="q" placeholder="🔎 按昵称 / 文件名模糊搜索" /></div>
      </div>
      <div>
        <div class="empty" v-if="!children.length">无匹配报表</div>
        <div class="job-row" v-for="c in pgChildren.paged" :key="c.job_id" @click="open(c.job_id)">
          <span class="nick-badge">{{ (c.config||{}).nickname || c.label }}</span>
          <span class="jid">{{ baseName(c.file_a) }}</span>
          <span class="badge-status" :class="c.status">{{ REPORT_STATUS[c.status] || c.status }}</span>
          <span v-if="c.error" class="count" style="color:var(--diff-bar);" :title="c.error">✗ {{ c.error }}</span>
          <span class="count" v-else-if="c.summary">
            条数 {{ c.summary.equal + c.summary.diff + c.summary.only_a }}↔{{ c.summary.equal + c.summary.diff + c.summary.only_b }} ·
            匹配 {{ c.summary.equal }} · 部分匹配 <b style="color:var(--diff-bar)">{{ c.summary.diff }}</b> ·
            仅A {{ c.summary.only_a }} / 仅B {{ c.summary.only_b }}
          </span>
        </div>
      </div>
    </div>
  </div>
  <div v-else class="loading">加载中…</div>`,
};

/* 报表结果页：摘要卡（条数/表头表尾/匹配统计/条数核对）+ 三分区（表头 / 业务内容 / 表尾）。 */
const ReportResultView = {
  components: { ReportZone },
  emits: ["back"],
  props: ["jobId"],
  setup(props, { emit }) {
    const sum = ref(null); let timer = null;
    async function load() {
      try {
        sum.value = await api(`/api/report-jobs/${props.jobId}/summary`);
        const st = sum.value.status;
        if (st === "pending" || st === "running") timer = setTimeout(load, 2000);
      } catch (e) {}
    }
    onMounted(load);
    const s = computed(() => (sum.value && sum.value.summary) || null);
    const cfg = computed(() => (sum.value && sum.value.config) || {});
    const confHit = computed(() => !!cfg.value.conf_hit);
    const confHint = computed(() => {
      const c = cfg.value;
      if (!confHit.value) return "配置来源：conf 规则未命中（昵称「" + (c.nickname || "—") + "」无对应规则）→ 默认整行对比";
      let t = "配置来源：conf 规则（已命中昵称「" + c.nickname + "」";
      const cur = [];
      if (c.conf_key_seq) cur.push("KEYSEQ=" + c.conf_key_seq);
      if (c.conf_omit_seq) cur.push("OMITSEQ=" + c.conf_omit_seq);
      t += cur.length ? "，conf 当前 " + cur.join(" / ") : "，conf 未配置主键 = 整行对比";
      return t + "）";
    });
    // 重跑（就地覆盖原结果）：sync=true 按昵称重查 conf 规则刷新主键/跳过栏位
    const busyRerun = ref(false); const rerunErr = ref("");
    async function doRerun(sync) {
      const msg = sync
        ? "将按昵称重新读取 conf 规则（主键/跳过栏位）并就地重跑，覆盖原结果。确认？"
        : "将沿用本次配置就地重跑，覆盖原结果。确认？";
      if (!(await askConfirm(msg, { okText: "重新执行" }))) return;
      busyRerun.value = true; rerunErr.value = "";
      try {
        await jpost(`/api/report-jobs/${props.jobId}/rerun`, { sync });
        await load(); // 状态转 pending 后 load 内部持续轮询，完成后视图自动刷新
      } catch (e) { rerunErr.value = e.message; }
      finally { busyRerun.value = false; }
    }
    const fileName = (p) => (p || "").split(/[\\/]/).pop();
    /* 条数核对展示：相符项收敛为汇总一行，仅逐条列出不符项（避免多段报表平铺几十行）。 */
    const countCheckText = computed(() => {
      if (!s.value || !s.value.count_checks || !s.value.count_checks.length) return [];
      const fmt = (c) =>
        `${c.side}侧 段${c.section + 1}：声明 ${c.declared == null ? '—' : c.declared} / 实计 ${c.counted}`;
      const bad = s.value.count_checks.filter(c => !c.match);
      const okCount = s.value.count_checks.length - bad.length;
      const lines = [];
      if (okCount) lines.push(`✓ ${okCount} 段声明条数与实计相符`);
      bad.forEach(c => lines.push(fmt(c) + ' ✗ 不符'));
      return lines;
    });
    const exportHref = computed(() => `/api/report-jobs/${props.jobId}/export`);
    return { sum, s, cfg, confHit, confHint, busyRerun, rerunErr, doRerun, fileName, countCheckText, exportHref, REPORT_STATUS, goBack: () => emit("back") };
  },
  template: `
  <div v-if="sum">
    <PageHeader back-text="返回批次" @back="goBack" :title="sum.label || '报表对比结果'">
      <template #meta>
        <span class="jid">{{ fileName(sum.file_a) }} ↔ {{ fileName(sum.file_b) }}</span>
        <span class="badge-status" :class="sum.status">{{ REPORT_STATUS[sum.status] || sum.status }}</span>
      </template>
      <template #actions>
        <a class="mini-btn" :href="exportHref"
           title="仅导出单侧不匹配 + 行部分匹配（部分匹配一条差异栏位一行）">⬇ 导出差异 CSV</a>
      </template>
    </PageHeader>
    <!-- 配置卡片（始终显示；error 态重跑即重试入口） -->
    <div class="card">
      <div class="card-head">⚙️ 本次对比配置
        <div class="head-actions">
          <button class="mini-btn" @click="doRerun(true)" :disabled="busyRerun"
                  title="按昵称重新读取 conf 规则（主键/跳过栏位）后就地重跑">↻ 同步最新配置并重跑</button>
          <button class="mini-btn" @click="doRerun(false)" :disabled="busyRerun"
                  title="沿用本次配置就地重跑">↺ 重新执行</button>
        </div>
      </div>
      <div class="card-body">
        <div class="cfg-files">A: <code>{{ sum.file_a }}</code> &nbsp;↔&nbsp; B: <code>{{ sum.file_b }}</code></div>
        <div class="form-grid" style="margin-top:12px;">
          <div class="field"><label>昵称</label><input :value="cfg.nickname" readonly /></div>
          <div class="field mono"><label>文件名</label><input :value="cfg.file_name" readonly /></div>
          <div class="field mono"><label>主键序列 KEYSEQ</label><input :value="cfg.key_seq" readonly placeholder="（空 = 整行对比）" /></div>
          <div class="field mono"><label>跳过序列 OMITSEQ</label><input :value="cfg.omit_seq" readonly placeholder="（空 = 不跳过）" /></div>
        </div>
        <div style="margin-top:10px;font-size:12px;color:var(--text-soft);">{{ confHint }}</div>
        <div class="error-box" v-if="rerunErr" style="margin-top:10px;">{{ rerunErr }}</div>
      </div>
    </div>
    <!-- 关注字段监测（昵称命中才有内容） -->
    <DiscardCard :job-id="jobId" />
    <div class="card" v-if="sum.status === 'error'">
      <div class="card-head">❌ 对比失败</div>
      <div class="card-body"><div class="error-box">{{ sum.error }}</div></div>
    </div>
    <!-- 重跑进行中：结果文件正被覆盖，避免展示半截结果 -->
    <template v-else-if="sum.status === 'pending' || sum.status === 'running'">
      <div class="card"><div class="card-body loading">⏳ 对比执行中，结果就绪后自动刷新…</div></div>
    </template>
    <template v-else>
      <div class="card">
        <div class="card-head">🧾 对比概览</div>
        <div class="card-body" v-if="s">
          <div class="summary-metrics">
            <div class="metric total"><div class="num">{{ s.row_count_a }}</div><div class="lbl">总条数 A（程序计数）</div></div>
            <div class="metric total"><div class="num">{{ s.row_count_b }}</div><div class="lbl">总条数 B（程序计数）</div></div>
            <div class="metric diff"><div class="num">{{ s.header_line_diff }}</div><div class="lbl">表头差异行</div></div>
            <div class="metric diff"><div class="num">{{ s.footer_line_diff }}</div><div class="lbl">表尾差异行</div></div>
            <div class="metric"><div class="num">{{ s.equal }}</div><div class="lbl">完全匹配</div></div>
            <div class="metric diff"><div class="num">{{ s.partial }}</div><div class="lbl">部分匹配</div></div>
            <div class="metric only"><div class="num">{{ s.only_a }}</div><div class="lbl">仅A有</div></div>
            <div class="metric only"><div class="num">{{ s.only_b }}</div><div class="lbl">仅B有</div></div>
          </div>
          <div style="margin-top:10px;font-size:12px;color:var(--text-soft);">
            报表段数 A/B：{{ s.section_count_a }} / {{ s.section_count_b }}
            <span v-if="s.control_format" class="group-badge" style="margin-left:8px;" title="控制行版式（1@OD@|）：无需模板，自分区解析；业务记录按折行原貌逐行对照">📐 控制行版式 · 折行 {{ s.fold_lines }} 行</span>
            <span v-if="s.field_names && s.field_names.length" style="margin-left:12px;">加工字段（铺底）：{{ s.field_names.join('、') }}</span>
          </div>
          <div v-if="countCheckText.length" style="margin-top:10px;font-size:12px;">
            <div style="font-weight:600;margin-bottom:4px;">条数核对（表尾声明 vs 程序计数）</div>
            <div v-for="(t,i) in countCheckText" :key="i"
                 :style="t.includes('✗') ? 'color:var(--diff-bar)' : 'color:var(--text-soft)'">{{ t }}</div>
          </div>
          <div v-if="s.warnings && s.warnings.length" class="no-rule" style="margin-top:10px;">
            ⚠ 解析提示：{{ [...new Set(s.warnings)].join('；') }}
          </div>
        </div>
      </div>

      <!-- key 含 finished_at：就地重跑完成后强制分区重挂载重新取数 -->
      <ReportZone :job-id="jobId" section="header" zone="all" title="报表头 · 全部行对照（差异字符高亮）" tone="diff"
                  :default-open="true" sourceA="A" sourceB="B" :field-names="s.field_names || []" :key="'h' + jobId + '-' + (sum.finished_at || 0)" />
      <ReportZone :job-id="jobId" section="footer" zone="all" title="报表尾 · 全部行对照（差异字符高亮）" tone="diff"
                  :default-open="true" sourceA="A" sourceB="B" :field-names="s.field_names || []" :key="'f' + jobId + '-' + (sum.finished_at || 0)" />
      <ReportZone :job-id="jobId" section="data" zone="diff" title="业务内容 · 部分匹配" tone="diff"
                  :default-open="true" sourceA="A" sourceB="B" :field-names="s.field_names || []" :key="'d1' + jobId + '-' + (sum.finished_at || 0)" />
      <ReportZone :job-id="jobId" section="data" zone="unmatched" title="业务内容 · 单侧不匹配" tone="unmatched"
                  :default-open="true" sourceA="A" sourceB="B" :key="'d2' + jobId + '-' + (sum.finished_at || 0)" />
      <ReportZone :job-id="jobId" section="data" zone="equal" title="业务内容 · 完全匹配（已按字段排序对齐）" tone="equal"
                  :default-open="false" sourceA="A" sourceB="B" :key="'d3' + jobId + '-' + (sum.finished_at || 0)" />
    </template>
  </div>
  <div v-else class="loading">加载中…</div>`,
};

/* ---------- 【自定义格式对比】：非传统结构化文本（一段段报文，如 MT950）按用户指定的段起止/主键正则对比 ---------- */

/* 自定义格式对比首页：路径 / 上传双模式 + 段格式配置（起始/结束/主键正则）+ 批次列表。 */
const CustomPage = {
  emits: ["open-batch"],
  setup(props, { emit }) {
    const mode = ref("path");
    const form = reactive({ start_pattern: "^\\{1:", end_pattern: "^-\\}", key_pattern: ":20:(\\S+)", dir_a: "", dir_b: "", label: "", source_a: "", source_b: "" });
    const up = reactive({ files_a: [], files_b: [] });
    const busy = ref(false); const err = ref("");
    function onFiles(ev, target) { up[target] = Array.from(ev.target.files); }
    function useMt950() {
      form.start_pattern = "^\\{1:"; form.end_pattern = "^-\\}"; form.key_pattern = ":20:(\\S+)";
    }
    async function submitPath() {
      err.value = "";
      if (!form.dir_a || !form.dir_b) { err.value = "请填写文本路径 A / B"; return; }
      if (!form.start_pattern) { err.value = "请填写段起始匹配式"; return; }
      busy.value = true;
      try {
        const r = await jpost("/api/custom-compare", {
          start_pattern: form.start_pattern, end_pattern: form.end_pattern || null,
          key_pattern: form.key_pattern || null, dir_a: form.dir_a, dir_b: form.dir_b, label: form.label || null,
          source_a: form.source_a || null, source_b: form.source_b || null,
        });
        emit("open-batch", r.batch_id);
      } catch (e) { err.value = e.message; } finally { busy.value = false; }
    }
    async function submitUpload() {
      err.value = "";
      if (!up.files_a.length || !up.files_b.length) { err.value = "请选择 A / B 两侧文本文件"; return; }
      if (!form.start_pattern) { err.value = "请填写段起始匹配式"; return; }
      busy.value = true;
      try {
        const fd = new FormData();
        up.files_a.forEach(f => fd.append("files_a", f));
        up.files_b.forEach(f => fd.append("files_b", f));
        const r = await api("/api/custom-compare/upload", { method: "POST", body: fd });
        const r2 = await jpost("/api/custom-compare", {
          start_pattern: form.start_pattern, end_pattern: form.end_pattern || null,
          key_pattern: form.key_pattern || null, dir_a: r.dir_a, dir_b: r.dir_b, label: form.label || null,
          source_a: form.source_a || null, source_b: form.source_b || null,
        });
        emit("open-batch", r2.batch_id);
      } catch (e) { err.value = e.message; } finally { busy.value = false; }
    }
    return { mode, form, up, busy, err, submitPath, submitUpload, onFiles, useMt950, REPORT_STATUS, fmtTime };
  },
  template: `
  <div>
    <PageHeader title="自定义格式对比" sub="非传统结构化文本（如 MT950 报文）按段起止/主键正则解析并配对对比" />
    <div class="card">
      <div class="card-head">📐 新建自定义格式对比（非传统结构化文本 · 按段解析匹配）</div>
      <div class="card-body">
        <div class="star-filter" style="margin-bottom:12px;">
          <button :class="{active: mode==='path'}" @click="mode='path'">📁 设置比对路径</button>
          <button :class="{active: mode==='upload'}" @click="mode='upload'">⬆ 上传文件对比</button>
        </div>
        <div class="form-grid single">
          <label class="field">段起始匹配式（正则，必填；命中行 = 一段的开始）
            <div style="display:flex;gap:8px;">
              <input v-model="form.start_pattern" placeholder="如 ^\\{1:（MT950 报文段起始）" spellcheck="false" style="flex:1;" />
              <button class="mini-btn" style="align-self:center;" @click="useMt950" title="填入 MT950 报文示例">MT950 示例</button>
            </div>
          </label>
          <label class="field">段结束匹配式（正则，可选；命中行 = 一段的结束。留空 = 段延伸到下一段起始行之前）
            <input v-model="form.end_pattern" placeholder="如 ^-\\}（MT950 报文段结束）" spellcheck="false" />
          </label>
          <label class="field">主键提取式（正则，可选；取段内首个命中行的捕获组 1 作主键配对，免疫段序差异。留空 = 按段全文精确匹配）
            <input v-model="form.key_pattern" placeholder="如 :20:(\\S+)（MT950 报文 :20: 域作主键）" spellcheck="false" />
          </label>
          <label class="field">文本路径 A（旧）
            <input v-if="mode==='path'" v-model="form.dir_a" placeholder="如 O:\\CodeRepos\\NonStructuralData" spellcheck="false" />
            <input v-else type="file" multiple @change="onFiles($event,'files_a')" />
          </label>
          <label class="field">文本路径 B（新）
            <input v-if="mode==='path'" v-model="form.dir_b" placeholder="目录按文件名配对；也可直接填单文件路径" spellcheck="false" />
            <input v-else type="file" multiple @change="onFiles($event,'files_b')" />
          </label>
          <label class="field">批次标签（可选，显示在批次任务中）
            <input v-model="form.label" placeholder="如 MT950 对账核对" />
          </label>
          <label class="field">A 来源名称（可选，替代“A”显示于总结报告）
            <input v-model="form.source_a" placeholder="默认 A，如 旧线/IBM" />
          </label>
          <label class="field">B 来源名称（可选，替代“B”显示于总结报告）
            <input v-model="form.source_b" placeholder="默认 B，如 新线/X86" />
          </label>
        </div>
        <div class="btn-row" style="margin-top:10px;">
          <button v-if="mode==='path'" class="btn primary" :disabled="busy" @click="submitPath">{{ busy ? '提交中…' : '开始段对比' }}</button>
          <button v-else class="btn primary" :disabled="busy" @click="submitUpload">{{ busy ? '上传并对比中…' : '上传并开始段对比' }}</button>
          <span class="count" style="align-self:center;">段 = 起始行（含）~ 结束行（含）；行展示保持段内原始行原貌</span>
        </div>
        <div class="error-box" v-if="err">{{ err }}</div>
      </div>
    </div>
  </div>`,
};

/* 自定义格式对比批次详情：总览 + 子作业（段数/匹配统计）。 */
const CustomBatchView = {
  emits: ["open", "back", "summary"],
  props: ["batchId"],
  setup(props, { emit }) {
    const ov = ref(null); const q = ref(""); let timer = null; let stopped = false;
    async function load() {
      if (stopped) return;
      try {
        ov.value = await api("/api/batches/" + props.batchId);
        const running = (ov.value.children || []).some(c => c.status === "pending" || c.status === "running");
        if (running) timer = setTimeout(load, 2000);
      } catch (e) {}
    }
    onMounted(load);
    const children = computed(() => {
      const ql = q.value.trim().toLowerCase();
      let list = (ov.value && ov.value.children) || [];
      return !ql ? list : list.filter(c =>
        ((c.label || "") + baseName2(c.file_a)).toLowerCase().includes(ql));
    });
    async function editLabel() {
      const cur = (ov.value && ov.value.batch && ov.value.batch.label) || "";
      const v = await askInput({ title: "设置批次标签", message: "留空即清除标签", value: cur, placeholder: "标签内容" });
      if (v === null) return;
      try { await jpost("/api/batches/" + props.batchId + "/label", { label: v });
            if (ov.value && ov.value.batch) ov.value.batch.label = v; }
      catch (e) { notify(e.message, "error"); }
    }
    function baseName2(p) { return (p || "").split(/[\\/]/).pop(); }
    // 子文件分页 lazy load（默认 100/页）
    const pgChildren = usePager(() => children.value, 100);
    watch(q, () => pgChildren.reset());
    return { ov, q, children, pgChildren, fmtTime, baseName: baseName2, editLabel, REPORT_STATUS,
             open: (id) => emit("open", id), goBack: () => emit("back") };
  },
  template: `
  <div v-if="ov">
    <PageHeader back-text="返回段对比" @back="goBack" title="自定义格式对比批次">
      <template #meta>
        <span class="jid">{{ ov.batch.batch_id }}</span>
        <span class="group-badge" v-if="ov.batch.label">🏷 {{ ov.batch.label }}</span>
        <button class="mini-btn" @click="editLabel">{{ ov.batch.label ? '改标签' : '加标签' }}</button>
        <span class="badge-status" :class="ov.status">{{ REPORT_STATUS[ov.status] || ov.status }}</span>
      </template>
      <template #actions>
        <button class="mini-btn" @click="$emit('summary')"
                title="打开批次情况总结（概览 / 偏离度 Top / 关注字段监测，可下载 HTML）">📋 批次情况总结</button>
      </template>
    </PageHeader>
    <div class="card">
      <div class="card-head">📐 批次概况</div>
      <div class="card-body">
        <div class="summary-metrics">
          <div class="metric total"><div class="num">{{ ov.total_files }}</div><div class="lbl">文件数</div></div>
          <div class="metric diff"><div class="num">{{ ov.diff_files }}</div><div class="lbl">存在差异</div></div>
          <div class="metric"><div class="num">{{ ov.done }}</div><div class="lbl">已完成</div></div>
        </div>
        <div style="margin-top:12px;font-size:12px;color:var(--text-soft);">
          A：{{ ov.batch.dir_a }}<br/>B：{{ ov.batch.dir_b }}<br/>{{ ov.batch.config_source }}
        </div>
        <div v-if="ov.batch.no_rule_files && ov.batch.no_rule_files.length" class="no-rule" style="margin-top:10px;">
          ⚠ 未配对（B 侧无同名文件）：{{ ov.batch.no_rule_files.join('、') }}
        </div>
      </div>
    </div>
    <!-- 关注字段监测（昵称命中才有内容；批次运行中显示等待） -->
    <DiscardCard :batch-id="ov.batch.batch_id" />
    <div class="card">
      <div class="card-head">📄 段对比结果（点击查看单文件详情）
        <div class="head-actions">
          <ListPager :page="pgChildren.page" :pages="pgChildren.pages" :total="pgChildren.total" :size="pgChildren.size"
                     @go="pgChildren.go" @resize="pgChildren.resize" />
        </div>
      </div>
      <div class="card-body" style="padding:12px 18px;">
        <div class="field"><input v-model="q" placeholder="🔎 按文件名模糊搜索" /></div>
      </div>
      <div>
        <div class="empty" v-if="!children.length">无匹配文件</div>
        <div class="job-row" v-for="c in pgChildren.paged" :key="c.job_id" @click="open(c.job_id)">
          <span class="jid">{{ baseName(c.file_a) }}</span>
          <span class="badge-status" :class="c.status">{{ REPORT_STATUS[c.status] || c.status }}</span>
          <span v-if="c.error" class="count" style="color:var(--diff-bar);" :title="c.error">✗ {{ c.error }}</span>
          <span class="count" v-else-if="c.summary">
            段数 {{ c.summary.equal + c.summary.diff + c.summary.only_a }}↔{{ c.summary.equal + c.summary.diff + c.summary.only_b }} ·
            一致 {{ c.summary.equal }} · 有差异 <b style="color:var(--diff-bar)">{{ c.summary.diff }}</b> ·
            仅A {{ c.summary.only_a }} / 仅B {{ c.summary.only_b }}
          </span>
        </div>
      </div>
    </div>
  </div>
  <div v-else class="loading">加载中…</div>`,
};

/* 自定义格式对比结果页：摘要卡（段数/匹配统计/主键未命中/残行）+ 段差异分区。 */
const CustomResultView = {
  components: { ReportZone },
  emits: ["back"],
  props: ["jobId"],
  setup(props, { emit }) {
    const sum = ref(null); let timer = null;
    async function load() {
      try {
        sum.value = await api(`/api/custom-jobs/${props.jobId}/summary`);
        const st = sum.value.status;
        if (st === "pending" || st === "running") timer = setTimeout(load, 2000);
      } catch (e) {}
    }
    onMounted(load);
    const s = computed(() => (sum.value && sum.value.summary) || null);
    const fileName = (p) => (p || "").split(/[\\/]/).pop();
    const exportHref = computed(() => `/api/custom-jobs/${props.jobId}/export`);
    return { sum, s, fileName, exportHref, REPORT_STATUS, goBack: () => emit("back") };
  },
  template: `
  <div v-if="sum">
    <PageHeader back-text="返回批次" @back="goBack" :title="sum.label || '自定义格式对比结果'">
      <template #meta>
        <span class="jid">{{ fileName(sum.file_a) }} ↔ {{ fileName(sum.file_b) }}</span>
        <span class="badge-status" :class="sum.status">{{ REPORT_STATUS[sum.status] || sum.status }}</span>
      </template>
      <template #actions>
        <a class="mini-btn" :href="exportHref"
           title="仅导出有差异段（一条差异行一行）与单侧段（整段一条）">⬇ 导出差异 CSV</a>
      </template>
    </PageHeader>
    <div class="card" v-if="sum.status === 'error'">
      <div class="card-head">❌ 对比失败</div>
      <div class="card-body"><div class="error-box">{{ sum.error }}</div></div>
    </div>
    <template v-else>
      <div class="card">
        <div class="card-head">📐 对比概览</div>
        <div class="card-body" v-if="s">
          <div class="summary-metrics">
            <div class="metric total"><div class="num">{{ s.segments_a }}</div><div class="lbl">段数 A</div></div>
            <div class="metric total"><div class="num">{{ s.segments_b }}</div><div class="lbl">段数 B</div></div>
            <div class="metric"><div class="num">{{ s.equal }}</div><div class="lbl">完全一致</div></div>
            <div class="metric diff"><div class="num">{{ s.diff }}</div><div class="lbl">有差异</div></div>
            <div class="metric only"><div class="num">{{ s.only_a }}</div><div class="lbl">仅A有</div></div>
            <div class="metric only"><div class="num">{{ s.only_b }}</div><div class="lbl">仅B有</div></div>
            <div class="metric" :class="{ diff: s.key_miss_a + s.key_miss_b > 0 }"><div class="num">{{ s.key_miss_a + s.key_miss_b }}</div><div class="lbl">主键未命中段(A+B)</div></div>
          </div>
          <div style="margin-top:10px;font-size:12px;color:var(--text-soft);">
            <div>段起始：<code>{{ s.start_pattern }}</code>；段结束：<code>{{ s.end_pattern || '（留空：到下一段起始前）' }}</code>；主键：<code>{{ s.key_pattern || '（未配置：按段全文精确匹配）' }}</code></div>
            <div style="margin-top:4px;" v-if="s.head_lines_a + s.head_lines_b + s.tail_lines_a + s.tail_lines_b">
              段外残行：文件头 A {{ s.head_lines_a }} / B {{ s.head_lines_b }} 行；文件尾 A {{ s.tail_lines_a }} / B {{ s.tail_lines_b }} 行（已参与对照）
            </div>
          </div>
          <div v-if="s.warnings && s.warnings.length" class="no-rule" style="margin-top:10px;">
            ⚠ 解析提示：{{ [...new Set(s.warnings)].join('；') }}
          </div>
        </div>
      </div>

      <ReportZone :job-id="jobId" base="custom-jobs" section="segment" zone="diff" title="段差异（主键相同、内容不同）" tone="diff"
                  :default-open="true" sourceA="A" sourceB="B" :key="'cd' + jobId" />
      <ReportZone :job-id="jobId" base="custom-jobs" section="segment" zone="unmatched" title="段 · 单侧不匹配（按主键配对后多余/缺失）" tone="unmatched"
                  :default-open="true" sourceA="A" sourceB="B" :key="'cu' + jobId" />
      <ReportZone :job-id="jobId" base="custom-jobs" section="segment" zone="equal" title="段 · 完全一致" tone="equal"
                  :default-open="false" sourceA="A" sourceB="B" :key="'ce' + jobId" />
    </template>
  </div>
  <div v-else class="loading">加载中…</div>`,
};

/* ---------- 配置管理页：全部文本对比配置（查询/修改/新增/删除）+ 对比配置分组（管理与 Excel 导入） ---------- */
const ConfigManagePage = {
  components: { SeqField, ListPager },
  props: ["encodings"],
  setup(props) {
    // ---- 对比配置 ----
    const cfgQuery = ref(""); const configs = ref([]); const badLines = ref(0);
    const cfgErr = ref(""); const busy = ref(false);
    async function loadConfigs() {
      busy.value = true;
      try {
        const r = await api("/api/configs/manage" + (cfgQuery.value.trim() ? "?q=" + encodeURIComponent(cfgQuery.value.trim()) : ""));
        configs.value = r.configs || []; badLines.value = r.bad_lines || 0; cfgErr.value = "";
      } catch (e) { cfgErr.value = e.message; }
      finally { busy.value = false; }
    }
    onMounted(loadConfigs);
    const cfgPg = usePager(() => configs.value, 50);
    watch(cfgQuery, () => { cfgPg.reset(); });

    const cfgModal = reactive({ open: false, mode: "new", err: "",
      form: { nickname: "", file_glob: "*", key_seq: "", omit_seq: "", delimiter: " | ",
              trailer_prefix: "|||||", encoding_a: "auto", encoding_b: "auto",
              source_a: "A", source_b: "B", pri_seq: "", addmod: "" } });
    function newConfig() {
      cfgModal.mode = "new"; cfgModal.err = "";
      Object.assign(cfgModal.form, { nickname: "", file_glob: "*", key_seq: "", omit_seq: "",
        delimiter: " | ", trailer_prefix: "|||||", encoding_a: "auto", encoding_b: "auto",
        source_a: "A", source_b: "B", pri_seq: "", addmod: "" });
      cfgModal.open = true;
    }
    function editConfig(c) {
      cfgModal.mode = "edit"; cfgModal.err = "";
      Object.assign(cfgModal.form, {
        nickname: c.nickname || "", file_glob: c.file_glob || "*",
        key_seq: seqToStr(c.key_columns), omit_seq: seqToStr(c.omit_columns),
        delimiter: c.delimiter != null ? c.delimiter : " | ",
        trailer_prefix: c.trailer_prefix || "|||||",
        encoding_a: c.encoding_a || "auto", encoding_b: c.encoding_b || "auto",
        source_a: c.source_a || "A", source_b: c.source_b || "B",
        pri_seq: c.pri_seq || "", addmod: c.addmod || "" });
      cfgModal.open = true;
    }
    async function saveConfig() {
      cfgModal.err = "";
      if (!cfgModal.form.nickname.trim()) { cfgModal.err = "昵称不能为空"; return; }
      try {
        await jpost("/api/configs", {
          nickname: cfgModal.form.nickname, file_glob: cfgModal.form.file_glob || "*",
          key_seq: cfgModal.form.key_seq || null, omit_seq: cfgModal.form.omit_seq || null,
          delimiter: cfgModal.form.delimiter, trailer_prefix: cfgModal.form.trailer_prefix,
          encoding_a: cfgModal.form.encoding_a, encoding_b: cfgModal.form.encoding_b,
          source_a: cfgModal.form.source_a || null, source_b: cfgModal.form.source_b || null,
          pri_seq: cfgModal.form.pri_seq || null, addmod: cfgModal.form.addmod || null,
        });
        cfgModal.open = false; loadConfigs();
        notify("配置「" + cfgModal.form.nickname + "」已保存（写入 " + cfgModal.form.nickname + ".conf，目录批次生效）");
      } catch (e) { cfgModal.err = "保存失败: " + e.message; }
    }
    async function deleteConfig(c) {
      const ok = await askConfirm("删除配置「" + c.nickname + "」？将删除其 .conf 并从 current/default.conf 移除该行。", { danger: true });
      if (!ok) return;
      try {
        await api("/api/configs?nickname=" + encodeURIComponent(c.nickname), { method: "DELETE" });
        notify("配置「" + c.nickname + "」已删除"); loadConfigs();
      } catch (e) { notify("删除失败: " + e.message, "err"); }
    }

    // ---- 对比配置分组 ----
    const groups = ref([]); const grpErr = ref(""); const grpBusy = ref(false);
    // 分组成员 ↔ 现有配置昵称对照：未命中的成员标红提示（避免清单写错昵称后批次静默漏文本）
    const configNicks = computed(() => new Set(configs.value.map(c => c.nickname)));
    function missCount(g) { return (g.members || []).filter(m => !configNicks.value.has(m)).length; }
    async function loadGroups() {
      grpBusy.value = true;
      try { const r = await api("/api/compare-groups"); groups.value = r.groups || []; grpErr.value = ""; }
      catch (e) { grpErr.value = e.message; }
      finally { grpBusy.value = false; }
    }
    onMounted(loadGroups);

    const grpModal = reactive({ open: false, mode: "new", err: "", form: { name: "", membersText: "" } });
    function newGroup() {
      grpModal.mode = "new"; grpModal.err = "";
      Object.assign(grpModal.form, { name: "", membersText: "" });
      grpModal.open = true;
    }
    function editGroup(g) {
      grpModal.mode = "edit"; grpModal.err = "";
      Object.assign(grpModal.form, { name: g.name, membersText: (g.members || []).join("、") });
      grpModal.open = true;
    }
    async function saveGroup() {
      grpModal.err = "";
      const members = grpModal.form.membersText.split(/[、,，;；\n\r\t|/]+/)
        .map(s => s.trim()).filter(s => s.length);
      if (!grpModal.form.name.trim()) { grpModal.err = "分组名称不能为空"; return; }
      if (!members.length) { grpModal.err = "至少填写一个文本昵称"; return; }
      try {
        await jpost("/api/compare-groups", { name: grpModal.form.name, members });
        grpModal.open = false; loadGroups();
        notify("分组「" + grpModal.form.name + "」已保存（" + members.length + " 个成员）");
      } catch (e) { grpModal.err = "保存失败: " + e.message; }
    }
    async function deleteGroup(g) {
      const ok = await askConfirm("删除分组「" + g.name + "」（" + g.count + " 个成员）？", { danger: true });
      if (!ok) return;
      try {
        await api("/api/compare-groups?name=" + encodeURIComponent(g.name), { method: "DELETE" });
        notify("分组「" + g.name + "」已删除"); loadGroups();
      } catch (e) { notify("删除失败: " + e.message, "err"); }
    }
    async function importGroups(ev) {
      const f = ev.target.files && ev.target.files[0];
      if (!f) return;
      const fd = new FormData(); fd.append("file", f);
      try {
        const r = await api("/api/compare-groups/import", { method: "POST", body: fd });
        notify("分组导入成功：" + (r.groups || []).join("、") + "（共 " + r.total + " 组）");
        loadGroups();
      } catch (e) { notify("导入失败: " + e.message, "err"); }
      ev.target.value = "";
    }

    return { cfgQuery, configs, badLines, cfgErr, busy, loadConfigs, cfgPg,
             cfgModal, newConfig, editConfig, saveConfig, deleteConfig, configNicks, missCount,
             groups, grpErr, grpBusy, loadGroups, grpModal, newGroup, editGroup, saveGroup,
             deleteGroup, importGroups, fmtTime };
  },
  template: `
  <div>
    <PageHeader title="配置管理" sub="全部文本对比配置的查询 / 修改 / 新增 / 删除；对比配置分组用于目录对比时圈定对比范围（分组可 Excel 导入）" />
    <div class="card">
      <div class="card-head">⚙️ 对比配置（{{ configs.length }} 条<template v-if="badLines"> · {{ badLines }} 行坏行已跳过</template>）
        <div class="head-actions">
          <ListPager :page="cfgPg.page" :pages="cfgPg.pages" :total="cfgPg.total" :size="cfgPg.size"
                     @go="cfgPg.go" @resize="cfgPg.resize" />
          <button class="mini-btn" @click="loadConfigs" :disabled="busy">↻ 刷新</button>
          <button class="mini-btn" @click="newConfig">＋ 新增配置</button>
        </div>
      </div>
      <div class="card-body">
        <div class="sec-search">
          <label>搜索 <input v-model="cfgQuery" @input="loadConfigs" placeholder="按昵称 / 通配名搜索" style="width:260px;" /></label>
        </div>
        <div class="error-box" v-if="cfgErr">{{ cfgErr }}</div>
        <div class="empty" v-if="!configs.length">无配置（可在「新建对比」页保存，或点右上「新增配置」）</div>
        <div v-else style="overflow-x:auto;">
          <table class="discard-table">
            <thead><tr><th>昵称</th><th>文件名通配</th><th>主键栏位</th><th>跳过栏位</th><th>分隔符</th>
              <th>编码A/B</th><th>优先级</th><th>增量</th><th>来源文件</th><th style="width:130px;">操作</th></tr></thead>
            <tbody>
              <tr v-for="(c, i) in cfgPg.paged" :key="c.nickname + '-' + c._source + '-' + c.line_no">
                <td><b>{{ c.nickname }}</b></td>
                <td class="mono">{{ c.file_glob }}</td>
                <td>{{ c.key_seq || '—' }}</td>
                <td>{{ c.omit_seq || '—' }}</td>
                <td class="mono">{{ c.delimiter }}</td>
                <td>{{ c.encoding_a }} / {{ c.encoding_b }}</td>
                <td>{{ c.pri_seq || '5' }}</td>
                <td>{{ c.addmod || '—' }}</td>
                <td class="mono">{{ c._source }}<template v-if="c.line_no">:{{ c.line_no }}</template></td>
                <td>
                  <button class="mini-btn" @click="editConfig(c)">✏️ 修改</button>
                  <button class="mini-btn" @click="deleteConfig(c)">🗑 删除</button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <p class="hint" style="margin:8px 0 0;">修改/新增写入 {昵称}.conf（目录批次「全部配置」时优先生效）；同一配置同时存在于聚合文件时，保存后以单配置文件为准。</p>
      </div>
    </div>

    <div class="card">
      <div class="card-head">🏷 对比配置分组（{{ groups.length }} 组）
        <div class="head-actions">
          <label class="mini-btn" title="导入 Excel（分组名称 + 文本昵称 两列，同名分组合并替换）">📥 导入 Excel<input type="file" accept=".xlsx" style="display:none;" @change="importGroups" /></label>
          <button class="mini-btn" @click="loadGroups" :disabled="grpBusy">↻ 刷新</button>
          <button class="mini-btn" @click="newGroup">＋ 新增分组</button>
        </div>
      </div>
      <div class="card-body">
        <div class="error-box" v-if="grpErr">{{ grpErr }}</div>
        <div class="empty" v-if="!groups.length">尚无分组。目录对比不选分组 = 对比目录下全部文本；选定分组 = 仅对比分组成员（配置昵称）命中的文本。</div>
        <div v-else style="overflow-x:auto;">
          <table class="discard-table">
            <thead><tr><th>分组名称</th><th>成员数</th><th>成员（配置昵称）</th><th>来源</th><th>导入时间</th><th style="width:130px;">操作</th></tr></thead>
            <tbody>
              <tr v-for="g in groups" :key="g.name">
                <td><b>{{ g.name }}</b></td>
                <td>{{ g.count }}<span v-if="missCount(g)" class="miss-hint" :title="'其中 ' + missCount(g) + ' 个昵称未命中现有对比配置（目录对比时将被跳过）'"> ⚠{{ missCount(g) }} 未命中</span></td>
                <td class="vals"><template v-for="(m, mi) in g.members" :key="m"><span v-if="mi">、</span><span :class="{ miss: !configNicks.has(m) }" :title="configNicks.has(m) ? '' : '未命中现有对比配置（目录对比时该文本将被跳过）'">{{ m }}</span></template></td>
                <td>{{ g.source_file || '—' }}</td>
                <td>{{ g.imported_at ? fmtTime(g.imported_at) : '—' }}</td>
                <td>
                  <button class="mini-btn" @click="editGroup(g)">✏️ 修改</button>
                  <button class="mini-btn" @click="deleteGroup(g)">🗑 删除</button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <p class="hint" style="margin:8px 0 0;">e.g. 分组「日常对比配置」成员 INXH、INVM、INVM_FACC：目录对比选定该分组后，仅这三类文本读取对比，名单外文本不操作。</p>
      </div>
    </div>

    <!-- 配置 新增/修改 弹框 -->
    <div class="modal-mask" v-if="cfgModal.open" @click.self="cfgModal.open=false">
      <div class="modal">
        <div class="modal-head">{{ cfgModal.mode === 'new' ? '新增对比配置' : '修改对比配置「' + cfgModal.form.nickname + '」' }}
          <button class="modal-x" @click="cfgModal.open=false">✕</button></div>
        <div class="modal-body">
          <div class="form-grid">
            <div class="field"><label>昵称{{ cfgModal.mode === 'edit' ? '（修改=另存新配置，昵称不可改）' : '' }}</label>
              <input v-model="cfgModal.form.nickname" :disabled="cfgModal.mode==='edit'" /></div>
            <div class="field mono"><label>文件名通配</label><input v-model="cfgModal.form.file_glob" /></div>
            <SeqField label="主键序列 KEYSEQ (1-based，留空 = 整行对比)" v-model="cfgModal.form.key_seq" placeholder="如 3/4/5" />
            <SeqField label="跳过序列 OMITSEQ" v-model="cfgModal.form.omit_seq" placeholder="如 1/2/10" />
            <div class="field mono"><label>分隔符</label><input v-model="cfgModal.form.delimiter" /></div>
            <div class="field mono"><label>尾部前缀</label><input v-model="cfgModal.form.trailer_prefix" /></div>
            <div class="field"><label>A 编码</label>
              <select v-model="cfgModal.form.encoding_a"><option v-for="e in encodings" :key="e" :value="e">{{ e }}</option></select></div>
            <div class="field"><label>B 编码</label>
              <select v-model="cfgModal.form.encoding_b"><option v-for="e in encodings" :key="e" :value="e">{{ e }}</option></select></div>
            <div class="field"><label>来源A名称（结果页/导出的 A 侧命名）</label><input v-model="cfgModal.form.source_a" /></div>
            <div class="field"><label>来源B名称（结果页/导出的 B 侧命名）</label><input v-model="cfgModal.form.source_b" /></div>
            <div class="field"><label>对比优先级 PRISEQ</label>
              <select v-model="cfgModal.form.pri_seq">
                <option value="">默认（5）</option>
                <option v-for="n in [1,2,3,4,5]" :key="n" :value="String(n)">{{ n }}</option>
              </select></div>
            <div class="field mono"><label>增量过滤 ADDMOD（栏位，逗号分隔）</label>
              <input v-model="cfgModal.form.addmod" placeholder="留空 = 全量" /></div>
          </div>
          <p class="hint" style="margin:10px 0 0;">保存写入 {昵称}.conf；同昵称旧配置行（current/default 等聚合文件）自动被其覆盖生效。修改/新增仅动此配置，已有替换规则与列名映射保留。</p>
          <div class="error-box" v-if="cfgModal.err">{{ cfgModal.err }}</div>
        </div>
        <div class="modal-foot">
          <button class="btn ghost" @click="cfgModal.open=false">取消</button>
          <button class="btn primary" @click="saveConfig">保存</button>
        </div>
      </div>
    </div>

    <!-- 分组 新增/修改 弹框 -->
    <div class="modal-mask" v-if="grpModal.open" @click.self="grpModal.open=false">
      <div class="modal">
        <div class="modal-head">{{ grpModal.mode === 'new' ? '新增对比配置分组' : '修改分组「' + grpModal.form.name + '」' }}
          <button class="modal-x" @click="grpModal.open=false">✕</button></div>
        <div class="modal-body">
          <div class="field"><label>分组名称</label><input v-model="grpModal.form.name" placeholder="如 日常对比配置" /></div>
          <div class="field" style="margin-top:12px;">
            <label>成员（配置昵称，顿号/逗号/换行分隔）</label>
            <textarea v-model="grpModal.form.membersText" rows="6" placeholder="如 INXH、INVM、INVM_FACC"></textarea>
          </div>
          <div class="error-box" v-if="grpModal.err">{{ grpModal.err }}</div>
        </div>
        <div class="modal-foot">
          <button class="btn ghost" @click="grpModal.open=false">取消</button>
          <button class="btn primary" @click="saveGroup">保存</button>
        </div>
      </div>
    </div>
  </div>`,
};

const App = {
  components: { SubmitForm, JobList, BatchView, ResultView, SettingsPage, SplitPage, SplitResultView,
                TaskManagerPage, ReportPage, ReportBatchView, ReportResultView,
                CustomPage, CustomBatchView, CustomResultView, BatchSummaryPage, ConfigManagePage,
                AppDialog, AppToast },
  setup() {
    const tab = ref("submit");
    const jobId = ref(null);
    const batchId = ref(null);
    const backBatch = ref(null);   // 结果页从某批次进入时记录，用于返回
    const backTab = ref(null);     // 从任务管理进入结果/批次时记录，返回按钮回到任务管理
    const resultNonce = ref(0);    // 自增令牌：即使 job_id 不变（就地重跑）也强制结果页重挂载刷新
    const splitJobId = ref(null);  // 当前查看的拆分作业
    const reportBatchId = ref(null); // 当前查看的报表对比批次
    const reportJobId = ref(null);   // 当前查看的报表对比作业
    const summaryBatchId = ref(null); // 批次情况总结页当前批次
    const summaryBackTab = ref("jobs"); // 总结页返回目标（批次详情来源）
    const encodings = ref(["auto", "utf-8"]);
    onMounted(async () => {
      try { const r = await api("/api/encodings"); encodings.value = r.encodings; } catch (e) {}
    });
    function onSubmitted(ids) {
      if (ids && ids.length) { jobId.value = ids[0]; backBatch.value = null; resultNonce.value++; tab.value = "result"; }
    }
    function onBatched(bid) { batchId.value = bid; tab.value = "batch"; }
    function openJob(id) { jobId.value = id; backBatch.value = null; backTab.value = null; resultNonce.value++; tab.value = "result"; }
    function openBatch(id) { batchId.value = id; backTab.value = null; tab.value = "batch"; }
    function openChild(id) { jobId.value = id; backBatch.value = batchId.value; resultNonce.value++; tab.value = "result"; }
    // 任务管理页跳转：记录来源，结果/批次页返回时回到任务管理
    function openJobFromTasks(id) { jobId.value = id; backBatch.value = null; backTab.value = "tasks"; resultNonce.value++; tab.value = "result"; }
    function openBatchFromTasks(id) { batchId.value = id; backTab.value = "tasks"; tab.value = "batch"; }
    // 就地重跑后：保留 backBatch（仍可返回批次），仅刷新结果页
    function onRerun(id) { jobId.value = id; resultNonce.value++; tab.value = "result"; }
    // 结果页返回：从批次进入则回批次页（批次页返回再按来源处理）；从任务管理直接进入则回任务管理；否则回作业列表
    function onResultBack() {
      if (backBatch.value) { tab.value = "batch"; return; }
      if (backTab.value === "tasks") { tab.value = "tasks"; return; }
      tab.value = "jobs";
    }
    function backToJobs() { tab.value = backTab.value === "tasks" ? "tasks" : "jobs"; }
    function openSplit(id) { splitJobId.value = id; tab.value = "splitresult"; }
    // 报表对比导航（fromJobs：从结果界面进入时，返回键回作业列表而非报表对比页）
    const reportBackTab = ref("report");
    function openReportBatch(id, from) { reportBackTab.value = from === "jobs" ? "jobs" : "report"; reportBatchId.value = id; tab.value = "reportbatch"; }
    function openReportJob(id) { reportJobId.value = id; tab.value = "reportresult"; }
    // 自定义格式对比导航
    const customBatchId = ref(null); // 当前查看的自定义格式对比批次
    const customJobId = ref(null);   // 当前查看的自定义格式对比作业
    function openCustomBatch(id) { customBatchId.value = id; tab.value = "custombatch"; }
    function openCustomJob(id) { customJobId.value = id; tab.value = "customresult"; }
    // 批次情况总结页导航：记录来源批次 tab，返回时回到对应批次详情
    const SUMMARY_BACK = { batch: "返回批次详情", reportbatch: "返回报表批次", custombatch: "返回自定义批次" };
    function openSummary(id, back) {
      summaryBatchId.value = id;
      summaryBackTab.value = back || "jobs";
      tab.value = "batchreport";
    }
    const summaryBackText = computed(() => SUMMARY_BACK[summaryBackTab.value] || "返回作业列表");
    return { tab, jobId, batchId, backBatch, backTab, resultNonce, splitJobId, encodings,
             reportBatchId, reportJobId, reportBackTab, customBatchId, customJobId,
             summaryBatchId, summaryBackTab, summaryBackText,
             onSubmitted, onBatched, openJob, openBatch, openChild, openJobFromTasks,
             openBatchFromTasks, onRerun, onResultBack, backToJobs, openSplit,
             openReportBatch, openReportJob, openCustomBatch, openCustomJob, openSummary };
  },
  template: `
  <div class="app-header">
    <div class="logo"><span class="dot"></span> TextDiff</div>
    <button class="nav-btn" :class="tab==='submit'?'active':''" @click="tab='submit'">新建对比</button>
    <button class="nav-btn" :class="tab==='ctis'?'active':''" @click="tab='ctis'">CTIS对比</button>
    <button class="nav-btn" :class="['report','reportbatch','reportresult'].includes(tab)?'active':''" @click="tab='report'">报表对比</button>
    <button class="nav-btn" :class="['custom','custombatch','customresult'].includes(tab)?'active':''" @click="tab='custom'">自定义格式对比</button>
    <button class="nav-btn" :class="['split','splitresult'].includes(tab)?'active':''" @click="tab='split'">文本拆分</button>
    <div class="spacer"></div>
    <button class="nav-btn" :class="['jobs','batch','batchreport'].includes(tab)?'active':''" @click="tab='jobs'">作业列表</button>
    <button class="nav-btn" :class="tab==='tasks'?'active':''" @click="tab='tasks'">任务管理</button>
    <button class="nav-btn" :class="tab==='configmgr'?'active':''" @click="tab='configmgr'">配置管理</button>
    <span class="nav-divider"></span>
    <button class="nav-btn" :class="tab==='result'?'active':''" @click="tab='result'" :disabled="!jobId">结果</button>
    <button class="nav-btn" :class="tab==='settings'?'active':''" @click="tab='settings'">设置</button>
  </div>
  <div class="container">
    <SubmitForm v-if="tab==='submit'" :encodings="encodings" @submitted="onSubmitted" @batched="onBatched" />
    <SubmitForm v-else-if="tab==='ctis'" :encodings="encodings" :ctis="true" @submitted="onSubmitted" @batched="onBatched" />
    <ReportPage v-else-if="tab==='report'" @open-batch="openReportBatch" />
    <ReportBatchView v-else-if="tab==='reportbatch' && reportBatchId" :batch-id="reportBatchId" :key="reportBatchId"
                     :back-text="reportBackTab === 'jobs' ? '返回作业列表' : '返回报表对比'"
                     @open="openReportJob" @summary="openSummary(reportBatchId, 'reportbatch')" @back="tab = reportBackTab" />
    <ReportResultView v-else-if="tab==='reportresult' && reportJobId" :job-id="reportJobId" :key="reportJobId"
                      @back="tab='reportbatch'" />
    <CustomPage v-else-if="tab==='custom'" @open-batch="openCustomBatch" />
    <CustomBatchView v-else-if="tab==='custombatch' && customBatchId" :batch-id="customBatchId" :key="customBatchId"
                     @open="openCustomJob" @summary="openSummary(customBatchId, 'custombatch')" @back="tab='custom'" />
    <CustomResultView v-else-if="tab==='customresult' && customJobId" :job-id="customJobId" :key="customJobId"
                      @back="tab='custombatch'" />
    <JobList v-else-if="tab==='jobs'" @open="openJob" @open-batch="openBatch" @open-split="openSplit"
             @open-report-batch="openReportBatch" @open-custom-batch="openCustomBatch" />
    <TaskManagerPage v-else-if="tab==='tasks'" @open="openJobFromTasks" @open-batch="openBatchFromTasks" />
    <SplitPage v-else-if="tab==='split'" @open-split="openSplit" />
    <SplitResultView v-else-if="tab==='splitresult' && splitJobId" :job-id="splitJobId" :key="splitJobId" @back="backToJobs" />
    <BatchView v-else-if="tab==='batch' && batchId" :batch-id="batchId" :key="batchId" @open="openChild"
               @summary="openSummary(batchId, 'batch')" @back="backToJobs" />
    <ResultView v-else-if="tab==='result' && jobId" :job-id="jobId" :key="jobId + '-' + resultNonce" :encodings="encodings"
                :back-batch="backBatch" :back-tasks="backTab==='tasks'" @rerun="onRerun" @back="onResultBack" />
    <BatchSummaryPage v-else-if="tab==='batchreport' && summaryBatchId" :batch-id="summaryBatchId"
                      :key="summaryBatchId" :back-text="summaryBackText"
                      @back="tab = summaryBackTab" />
    <ConfigManagePage v-else-if="tab==='configmgr'" :encodings="encodings" />
    <SettingsPage v-else-if="tab==='settings'" />
    <AppDialog />
    <AppToast />
  </div>`,
};

createApp(App).component("PageHeader", PageHeader).component("ListPager", ListPager)
  .component("DiscardTable", DiscardTable).component("DiscardCard", DiscardCard).mount("#app");
