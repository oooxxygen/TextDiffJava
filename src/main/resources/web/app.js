/* TextDiff 前端：无构建 Vue3 应用。
   提交对比 / 作业列表 / 类 Git 三分区结果展示（字符级高亮 + 懒加载）。 */
const { createApp, reactive, ref, computed, onMounted, watch, nextTick } = Vue;

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

/* ---------- 工具：自定义来源名（替代默认 A/B），贯穿结果页/列表/导出 ---------- */
function srcA(cfg) { return (cfg && cfg.source_a) ? cfg.source_a : "A"; }
function srcB(cfg) { return (cfg && cfg.source_b) ? cfg.source_b : "B"; }

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
  props: ["row", "skipSet", "columnNames", "sourceA", "sourceB", "forceOpen", "forceCollapse", "defaultOpen", "note", "supportsNote"],
  emits: ["save-note"],
  setup(props, { emit }) {
    const localOpen = ref(!!props.defaultOpen);
    const isOpen = computed(() => localOpen.value || props.forceOpen);
    const toggle = () => { localOpen.value = !localOpen.value; };
    // 收起全部信号：父级 bump forceCollapse 时强制收起本行
    watch(() => props.forceCollapse, () => { localOpen.value = false; });
    const colName = (i) => (props.columnNames && props.columnNames[i]) ? props.columnNames[i] : "";

    // ---- 记录评议(note) ----
    const noteEditing = ref(false);
    const noteDraft = ref("");
    const hasNote = computed(() => !!(props.note && String(props.note).trim()));
    function openNote() { noteDraft.value = props.note || ""; noteEditing.value = true; }
    function cancelNote() { noteEditing.value = false; }
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
      if (diffSet.value.has(i)) cls.push("diff");
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
             noteEditing, noteDraft, hasNote, openNote, cancelNote, saveNote, delNote };
  },
  template: `
  <div class="rec" :class="['s-' + row.status, isOpen ? 'open' : '', hasNote ? 'has-note' : '']">
    <div class="rec-head" @click="toggle">
      <span class="caret">▶</span>
      <span class="key">{{ keyText(row.key) }}</span>
      <span class="tag" :class="row.status">{{ statusLabel(row.status) }}</span>
      <span class="meta" v-if="row.diff_cols && row.diff_cols.length">差异列: {{ row.diff_cols.map(c=> (colName(c) ? (c+1)+'·'+colName(c) : (c+1))).join(', ') }}</span>
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
        <button class="mini-btn" @click="cancelNote">取消</button>
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
          </div>
          <div class="gcell" :class="[lineClass('a', i-1), sideClass('a')]">
            <template v-if="sideCols('a')">
              <span class="ci">{{ i }}</span>
              <span class="cv"><span v-for="(s,si) in cellSegs('a', i-1, (sideCols('a')[i-1]||''))" :key="si" :class="s.cls">{{ s.text }}</span></span>
            </template>
          </div>
          <div class="gcell" :class="[lineClass('b', i-1), sideClass('b')]">
            <template v-if="sideCols('b')">
              <span class="ci">{{ i }}</span>
              <span class="cv"><span v-for="(s,si) in cellSegs('b', i-1, (sideCols('b')[i-1]||''))" :key="si" :class="s.cls">{{ s.text }}</span></span>
            </template>
          </div>
        </template>
      </div>
    </div>
  </div>`,
  methods: {
    keyText(k) {
      return (k || "").split(String.fromCharCode(31)).join("  /  ") || "(空键)";
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
          "openByDefault", "recordsOpen", "pageSize", "exportUrl",
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
        <input class="zone-search" v-model="q" @keyup.enter="doSearch" placeholder="按键值搜索" />
        <button class="mini-btn" @click="doSearch">搜索</button>
        <button class="mini-btn" v-if="q.trim()" @click="clearSearch">清除</button>
        <a v-if="exportUrl" class="mini-btn" :href="exportUrl">导出 Excel</a>
        <button class="mini-btn" @click="toggleExpand">{{ allOpen ? '收起本页' : '展开本页' }}</button>
        <span class="zone-pager" v-if="open && totalPages > 1">
          <button class="mini-btn" :disabled="page<=1" @click="prevPage">‹ 上一页</button>
          第 <input class="pager-input" :value="page" @keyup.enter="gotoInput($event.target.value)" /> / {{ totalPages }} 页
          <button class="mini-btn" :disabled="page>=totalPages" @click="nextPage">下一页 ›</button>
        </span>
        <select class="pager-size" v-if="open" :value="pageSize" @change="setPageSize(+$event.target.value)">
          <option :value="10">10/页</option>
          <option :value="50">50/页</option>
        </select>
      </div>
    </div>
    <!-- note 筛选栏（仅数据分区） -->
    <div class="note-filter" v-if="supportsNote && open" @click.stop>
      <button :class="{active: noteFilter===''}" @click="applyNoteFilter('')">全部 {{ fullTotal }}</button>
      <button :class="{active: noteFilter==='has'}" @click="applyNoteFilter('has')">📝 已评议 {{ hasNoteCount }}</button>
      <button :class="{active: noteFilter==='none'}" @click="applyNoteFilter('none')">未评议 {{ noNoteCount }}</button>
    </div>
    <div class="zone-body" v-if="open">
      <div class="empty" v-if="total === 0">{{ active ? '无匹配记录' : '无记录' }}</div>
      <RecordRow v-for="(r, idx) in rows" :key="idx" :row="r" :skip-set="skipSet" :column-names="columnNames"
                 :source-a="sourceA" :source-b="sourceB" :force-open="allOpen" :force-collapse="collapseNonce"
                 :default-open="recordsOpen" :supports-note="supportsNote" :note="noteOf(r.key)" @save-note="onSaveNote" />
      <div class="load-more" v-if="loading">
        <span class="muted">加载中…</span>
      </div>
    </div>
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
      key_seq: "", omit_seq: "", replace_rules_text: "",
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
      <span class="group-badge" v-if="(meta.config||{}).group" style="margin-left:10px;">归属组 {{ meta.config.group }}</span>
      <div style="margin-left:auto; display:flex; gap:8px;">
        <button class="mini-btn" @click="syncLatest" :disabled="busy" title="按昵称从配置库拉取最新生效配置填入">↻ 同步最新配置</button>
        <button v-if="!editing" class="mini-btn" @click="startEdit">编辑</button>
        <button v-if="editing" class="mini-btn" @click="cancel">取消</button>
        <button v-if="editing" class="mini-btn" @click="saveConfig" :disabled="busy">保存到配置</button>
        <button v-if="editing" class="btn" style="padding:4px 14px;" @click="rerun" :disabled="busy">
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
  props: ["jobId", "encodings", "backBatch"],
  emits: ["rerun", "back"],
  setup(props, { emit }) {
    const meta = ref(null);
    const summary = ref(null);
    const zoneCounts = ref({});
    const error = ref("");
    const skipSet = ref(new Set());
    const columnNames = ref([]);
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
          if (window.confirm(`当前已用全局搜索「${globalQ.value.trim()}」筛选，是否对全部命中的记录标记相同评议？`)) {
            const r = await jpost(`/api/jobs/${props.jobId}/notes/bulk`, { q: globalQ.value.trim(), note: txt });
            alert(`已对 ${r.count} 条命中记录写入相同评议。`);
          }
        }
        await loadNotes();
        notesNonce.value++;   // 通知按 note 过滤的分区重载
      } catch (e) { alert("评议保存失败: " + e.message); }
    }

    const trailerChips = computed(() => {
      const tf = (summary.value && summary.value.trailer_fields) || {};
      return Object.keys(tf).map(k => ({ key: k, ...tf[k] }));
    });

    // 差异列差异度：列出全部有差异的列，按频次降序（freq 键经 JSON 后为字符串），附列名
    const diffConcentration = computed(() => {
      const s = summary.value;
      if (!s || !s.diff_col_freq) return [];
      const diff = s.diff || 0;
      const names = columnNames.value || [];
      return Object.keys(s.diff_col_freq)
        .map(k => {
          const i = Number(k);
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

    async function editLabel() {
      const cur = (meta.value && meta.value.label) || "";
      const v = window.prompt("设置标签（留空清除）：", cur);
      if (v === null) return;
      try { await jpost("/api/jobs/" + props.jobId + "/label", { label: v });
            if (meta.value) meta.value.label = v; }
      catch (e) { alert(e.message); }
    }

    return { meta, summary, zoneCounts, error, skipSet, columnNames, trailerChips,
             diffConcentration, concList, concShown, loadMoreConc,
             ai, aiHtml, runAnalyze, exportUrl, onRerun, fmtTime, editLabel,
             sourceA, sourceB, goBack: () => emit("back"),
             notes, zoneNoteCounts, notesNonce, globalQ, globalApplied, globalNonce,
             doGlobalSearch, clearGlobal, onSaveNote };
  },
  template: `
  <div>
    <div class="error-box" v-if="error">{{ error }}</div>
    <div class="loading" v-else-if="!meta">加载中…</div>
    <template v-else>
      <div style="margin-bottom:12px;">
        <button class="btn ghost" @click="goBack">{{ backBatch ? '← 返回批次列表' : '← 返回作业列表' }}</button>
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
        <div class="card-head">📊 对比概览 · {{ meta.job_id }}
          <span class="nick-badge" v-if="(meta.config||{}).nickname" style="margin-left:8px;">{{ meta.config.nickname }}</span>
          <span class="group-badge" v-if="(meta.config||{}).group" style="margin-left:6px;">归属组 {{ meta.config.group }}</span>
          <span class="group-badge" v-if="meta.label" style="margin-left:6px;" :title="meta.label">🏷 {{ meta.label }}</span>
          <button class="mini-btn" style="margin-left:6px;" @click="editLabel">{{ meta.label ? '改标签' : '加标签' }}</button>
          <span class="badge-status" :class="meta.status">{{ meta.status }}</span><span v-if="meta.key_warning" class="badge-keywarn" title="主键配置在新旧文本中存在重复键，无法唯一定位记录，该对比配置需要重检">⚠ 主键重复·配置需重检</span>
          <a class="mini-btn" style="margin-left:auto;" :href="'/api/jobs/' + jobId + '/export-all'"
             title="将三个数据分区（差异/未匹配/完全匹配）合并导出为单个 Excel（含概览/评议note，单元格全文本）">⬇ 全部导出 Excel</a>
        </div>
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
          <div style="margin-top:12px; font-size:12px; color:var(--text-soft);">
            触发时间 {{ fmtTime(meta.created_at) }} · 完成时间 {{ fmtTime(meta.finished_at) }}<span v-if="meta.locked"> · 🔒 已锁定</span><br/>
            编码 {{ sourceA }}={{ meta.detected_encoding_a }} · {{ sourceB }}={{ meta.detected_encoding_b }}
            <span v-if="meta.used_disk_fallback"> · ⚠ 已启用磁盘回退</span>
            <span v-if="summary.recnum_check_a===false || summary.recnum_check_b===false"> · ⚠ RecNum 校验不一致</span>
          </div>
        </div>
      </div>

      <!-- 本次配置展览 + 重跑 -->
      <ConfigPanel v-if="meta.config" :meta="meta" :encodings="encodings" @rerun="onRerun" />

      <!-- AI 优化建议 -->
      <div class="card" v-if="summary">
        <div class="card-head">🤖 AI 优化建议
          <div style="margin-left:auto; display:flex; gap:8px; align-items:center;">
            <input v-if="ai.enabled" v-model="ai.model" class="ai-model-input" placeholder="模型" />
            <button v-if="ai.enabled" class="btn" style="padding:5px 14px;" @click="runAnalyze" :disabled="ai.loading">
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
                   :skip-set="skipSet" :column-names="columnNames" :source-a="sourceA" :source-b="sourceB" :open-by-default="true" :records-open="false" :page-size="10" :export-url="exportUrl('diff')"
                   :supports-note="true" :notes="notes" :note-count="zoneNoteCounts.diff||0" :ext-query="globalQ" :ext-query-nonce="globalNonce" :notes-nonce="notesNonce" @save-note="onSaveNote" />
        <ZonePanel :job-id="jobId" zone="unmatched" label="⬛ 未匹配（仅单侧存在）" :total="zoneCounts.unmatched||0"
                   :skip-set="skipSet" :column-names="columnNames" :source-a="sourceA" :source-b="sourceB" :open-by-default="false" :records-open="false" :page-size="10" :export-url="exportUrl('unmatched')"
                   :supports-note="true" :notes="notes" :note-count="zoneNoteCounts.unmatched||0" :ext-query="globalQ" :ext-query-nonce="globalNonce" :notes-nonce="notesNonce" @save-note="onSaveNote" />
        <ZonePanel :job-id="jobId" zone="equal" label="🟢 完全匹配（默认折叠）" :total="zoneCounts.equal||0"
                   :skip-set="skipSet" :column-names="columnNames" :source-a="sourceA" :source-b="sourceB" :open-by-default="false" :records-open="false" :page-size="10" :export-url="exportUrl('equal')"
                   :supports-note="true" :notes="notes" :note-count="zoneNoteCounts.equal||0" :ext-query="globalQ" :ext-query-nonce="globalNonce" :notes-nonce="notesNonce" @save-note="onSaveNote" />
        <ZonePanel :job-id="jobId" zone="trailer" label="🔵 尾部信息对比" :total="zoneCounts.trailer||0"
                   :skip-set="skipSet" :column-names="columnNames" :source-a="sourceA" :source-b="sourceB" :open-by-default="true" :records-open="false" :page-size="10" :export-url="exportUrl('trailer')" />
      </template>
    </template>
  </div>`,
};

/* ---------- 提交表单 ---------- */
const SubmitForm = {
  components: { SeqField },
  props: ["encodings"],
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
      key_seq: "", omit_seq: "",
      replace_rules_text: "",
      batch_config_file: "__all__",
      column_names: [],
      label: "",
    });
    const uploadsA = ref([]); const uploadsB = ref([]);
    const busy = ref(false); const err = ref(""); const toast = ref("");
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
    async function saveConfig() {
      err.value = "";
      if (!form.nickname) { err.value = "保存配置需填写昵称"; return; }
      if (!form.key_seq) { err.value = "保存配置需填写主键序列"; return; }
      try {
        const body = {
          nickname: form.nickname, file_glob: form.file_glob || "*",
          delimiter: form.delimiter, encoding_a: form.encoding_a, encoding_b: form.encoding_b,
          trailer_prefix: form.trailer_prefix,
          key_seq: form.key_seq, omit_seq: form.omit_seq || null,
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

    onMounted(() => { searchConfigs(); loadConfigFiles(); });

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
        column_names: (form.column_names && form.column_names.length) ? form.column_names : null,
        label: form.label || null,
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
          // 目录对比 → 批次：选配置文件(多规则) 或 用下方内联单规则
          const p = { dir_a: form.dir_a, dir_b: form.dir_b,
                      delimiter: form.delimiter, encoding_a: form.encoding_a, encoding_b: form.encoding_b,
                      source_a: form.source_a || null, source_b: form.source_b || null,
                      label: form.label || null };
          if (form.batch_config_file === "__all__") p.use_all = true;   // 全部生效配置(current.conf)
          else if (form.batch_config_file) p.config_file = form.batch_config_file;
          else {
            p.nickname = form.nickname || null; p.file_glob = form.file_glob || "*";
            p.key_seq = form.key_seq || null; p.omit_seq = form.omit_seq || null;
            if (!form.key_seq) throw new Error("请填写主键序列（或选择「全部配置」/一个配置文件）");
          }
          const r = await jpost("/api/batch-compare", p);
          emit("batched", r.batch_id);
        } else {
          const r = await jpost("/api/compare", buildPayload());
          emit("submitted", r.job_ids);
        }
      } catch (e) { err.value = e.message; }
      finally { busy.value = false; }
    }

    return { form, uploadsA, uploadsB, busy, err, toast, parseConfig, uploadFiles, submit,
             cfgQuery, cfgResults, cfgFiles, saved, searchConfigs, pickConfig, saveConfig, viewSavedFile };
  },
  template: `
  <div>
    <div class="card">
      <div class="card-head">📁 选择对比来源</div>
      <div class="card-body">
        <div class="seg" style="margin-bottom:16px;">
          <button :class="form.mode==='dirs'?'active':''" @click="form.mode='dirs'">目录对比（批量）</button>
          <button :class="form.mode==='paths'?'active':''" @click="form.mode='paths'">指定文件路径</button>
          <button :class="form.mode==='uploads'?'active':''" @click="form.mode='uploads'">上传文件</button>
        </div>

        <div v-if="form.mode==='dirs'">
          <div class="form-grid">
            <div class="field mono"><label>A 目录（旧/IBM）</label><input v-model="form.dir_a" placeholder="O:\\CodeRepos\\v01\\bocso" /></div>
            <div class="field mono"><label>B 目录（新/X86）</label><input v-model="form.dir_b" placeholder="O:\\CodeRepos\\v01\\bocsoxc" /></div>
            <div class="field"><label>对比配置文件（多规则，可选）</label>
              <select v-model="form.batch_config_file">
                <option value="__all__">全部配置（current.conf 生效，含修改，推荐）</option>
                <option value="">（不选 = 用下方单规则）</option>
                <option v-for="f in cfgFiles" :key="f" :value="f">{{ f }}</option>
              </select></div>
          </div>
          <p class="hint" style="margin-top:10px;">目录对比将生成<b>批次</b>：选配置文件则按其多条规则的通配名匹配并同名配对；不选则用下方单条配置。无匹配规则的文件不对比，会在批次里列出。</p>
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
      <div class="card-head">🔎 选择已有配置（按昵称/通配名搜索）</div>
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

    <div class="card" v-show="!(form.mode==='dirs' && form.batch_config_file)">
      <div class="card-head">⚙️ 对比配置{{ form.mode==='dirs' ? '（目录对比的单规则）' : '' }}</div>
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

    <div class="card"><div class="card-body">
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

    <!-- 保存成功弹框：列出已写入文件，可点击查看 -->
    <div class="modal-mask" v-if="saved.open" @click.self="saved.open=false">
      <div class="modal">
        <div class="modal-head">✅ 配置「{{ saved.nickname }}」已保存
          <span class="modal-x" @click="saved.open=false">✕</span></div>
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
  emits: ["open", "open-batch", "open-split"],
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
      (b.batch.batch_id + " " + b.batch.dir_a + " " + b.batch.dir_b + " " + b.batch.config_source).toLowerCase().includes(ql.value)));
    const fJobs = computed(() => !ql.value ? standalone.value : standalone.value.filter(j =>
      (j.job_id + " " + (j.file_a || "") + " " + (j.file_b || "")).toLowerCase().includes(ql.value)));

    async function act(url, method, confirmMsg) {
      if (confirmMsg && !window.confirm(confirmMsg)) return;
      try { await api(url, { method: method || "POST" }); await load(); }
      catch (e) { alert(e.message); }
    }
    const lockJob = (id) => act("/api/jobs/" + id + "/lock");
    const unlockJob = (id) => act("/api/jobs/" + id + "/unlock");
    const delJob = (id) => act("/api/jobs/" + id, "DELETE", "确认删除该作业？");
    const lockBatch = (id) => act("/api/batches/" + id + "/lock");
    const unlockBatch = (id) => act("/api/batches/" + id + "/unlock");
    const delBatch = (id) => act("/api/batches/" + id, "DELETE", "确认删除整个批次及其所有子作业？");

    // 标签就地编辑：prompt 输入，留空即清除
    async function editLabel(url, current) {
      const v = window.prompt("设置标签（留空清除）：", current || "");
      if (v === null) return;   // 取消
      try { await jpost(url, { label: v }); await load(); }
      catch (e) { alert(e.message); }
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
    const toggleJob = (id) => { selJobs.has(id) ? selJobs.delete(id) : selJobs.add(id); bumpSel(); };
    const toggleBatch = (id) => { selBatches.has(id) ? selBatches.delete(id) : selBatches.add(id); bumpSel(); };
    const isJobSel = (id) => (selVer.value, selJobs.has(id));
    const isBatchSel = (id) => (selVer.value, selBatches.has(id));
    const selCount = computed(() => (selVer.value, selJobs.size + selBatches.size));
    const allJobsSel = computed(() => (selVer.value, fJobs.value.length > 0 && fJobs.value.every(j => selJobs.has(j.job_id))));
    const allBatchesSel = computed(() => (selVer.value, fBatches.value.length > 0 && fBatches.value.every(b => selBatches.has(b.batch.batch_id))));
    function toggleAllJobs() {
      if (allJobsSel.value) fJobs.value.forEach(j => selJobs.delete(j.job_id));
      else fJobs.value.forEach(j => selJobs.add(j.job_id));
      bumpSel();
    }
    function toggleAllBatches() {
      if (allBatchesSel.value) fBatches.value.forEach(b => selBatches.delete(b.batch.batch_id));
      else fBatches.value.forEach(b => selBatches.add(b.batch.batch_id));
      bumpSel();
    }
    function clearSel() { selJobs.clear(); selBatches.clear(); bumpSel(); }
    async function bulkDelete() {
      if (!selCount.value) return;
      if (!window.confirm("确认删除选中的 " + selBatches.size + " 个批次（含其所有子作业）与 " + selJobs.size + " 个单文件作业？锁定项将被跳过。")) return;
      try {
        const r = await jpost("/api/jobs/bulk-delete", { job_ids: [...selJobs], batch_ids: [...selBatches] });
        clearSel();
        await load();
        let msg = "已删除 " + r.deleted_batches + " 个批次、" + r.deleted_jobs + " 个作业";
        if (r.locked && r.locked.length) msg += "；跳过锁定项 " + r.locked.length + " 个（请先解锁）";
        alert(msg);
      } catch (e) { alert("批量删除失败: " + e.message); }
    }

    return { batches, standalone, splitJobs, q, fBatches, fJobs, fSplit, fmtTime, srcA, srcB,
             open: (id) => emit("open", id), openBatch: (id) => emit("open-batch", id),
             openSplit: (id) => emit("open-split", id),
             lockJob, unlockJob, delJob, lockBatch, unlockBatch, delBatch,
             editJobLabel, editBatchLabel, delSplit, editSplitLabel, splitGroupsText,
             toggleJob, toggleBatch, isJobSel, isBatchSel, selCount,
             allJobsSel, allBatchesSel, toggleAllJobs, toggleAllBatches, clearSel, bulkDelete };
  },
  template: `
  <div>
    <div class="card"><div class="card-body" style="padding:12px 18px;">
      <div class="field"><input v-model="q" placeholder="🔎 搜索作业/批次（按 ID、目录、文件路径）" /></div>
    </div></div>

    <!-- 多选批量删除工具条 -->
    <div class="bulk-bar" v-if="selCount">
      <span>已选 <b>{{ selCount }}</b> 项</span>
      <button class="btn ghost" @click="clearSel">清除选择</button>
      <button class="btn danger" @click="bulkDelete">🗑 批量删除选中项</button>
    </div>

    <div class="card" v-if="fBatches.length">
      <div class="card-head">📦 批量对比批次（点击进入查看各文件）
        <label class="sel-all" @click.stop><input type="checkbox" :checked="allBatchesSel" @change="toggleAllBatches" /> 全选批次</label>
      </div>
      <div>
        <div class="job-row batch-row" :class="{ selected: isBatchSel(b.batch.batch_id) }" v-for="b in fBatches" :key="b.batch.batch_id" @click="openBatch(b.batch.batch_id)">
          <input class="row-check" type="checkbox" :checked="isBatchSel(b.batch.batch_id)" @click.stop="toggleBatch(b.batch.batch_id)" />
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
        <label class="sel-all" v-if="fJobs.length" @click.stop><input type="checkbox" :checked="allJobsSel" @change="toggleAllJobs" /> 全选</label>
      </div>
      <div>
        <div class="empty" v-if="!fJobs.length">无匹配作业</div>
        <div class="job-row" :class="{ selected: isJobSel(j.job_id) }" v-for="j in fJobs" :key="j.job_id" @click="open(j.job_id)">
          <input class="row-check" type="checkbox" :checked="isJobSel(j.job_id)" @click.stop="toggleJob(j.job_id)" />
          <span class="jid">{{ j.job_id }}</span>
          <span class="group-badge" v-if="(j.config||{}).group">{{ j.config.group }}</span>
          <span class="badge-status" :class="j.status">{{ j.status }}</span><span v-if="j.key_warning" class="badge-keywarn" title="主键配置在新旧文本中存在重复键，无法唯一定位记录，该对比配置需要重检">⚠ 主键重复·配置需重检</span>
          <span class="files">{{ j.file_a }} ↔ {{ j.file_b }}</span>
          <span class="group-badge" v-if="j.label" :title="j.label">🏷 {{ j.label }}</span>
          <span class="meta-time">{{ fmtTime(j.created_at) }}</span>
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
      <div class="card-head">🪓 文本拆分作业</div>
      <div>
        <div class="job-row" v-for="s in fSplit" :key="s.job_id" @click="openSplit(s.job_id)">
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
  emits: ["open", "back"],
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
    async function toggleStar(c) {
      try {
        await api("/api/jobs/" + c.job_id + (c.starred ? "/unstar" : "/star"), { method: "POST" });
        c.starred = !c.starred;   // 本地即时反映，避免等待下次轮询
      } catch (e) { alert("标记失败: " + e.message); }
    }
    // 导出链接：随当前归属组筛选导出（不选则导出整批）
    const exportHref = computed(() => "/api/batches/" + (ov.value ? ov.value.batch.batch_id : "")
      + "/export" + (groupFilter.value ? "?group=" + encodeURIComponent(groupFilter.value) : ""));
    // 打包下载：批次内每表的全量导出 Excel（zip）
    const exportAllHref = computed(() => "/api/batches/"
      + (ov.value ? ov.value.batch.batch_id : "") + "/export-all");
    async function editLabel() {
      const cur = (ov.value && ov.value.batch && ov.value.batch.label) || "";
      const v = window.prompt("设置批次标签（留空清除）：", cur);
      if (v === null) return;
      try { await jpost("/api/batches/" + props.batchId + "/label", { label: v });
            if (ov.value && ov.value.batch) ov.value.batch.label = v; }
      catch (e) { alert(e.message); }
    }
    return { ov, q, children, baseName, nickOf, groupOf, fmtTime, srcA, srcB, sourceA, sourceB,
             starFilter, starCounts, toggleStar, groupFilter, groupOptions, exportHref, exportAllHref,
             editLabel,
             open: (id) => emit("open", id), goBack: () => emit("back") };
  },
  template: `
  <div v-if="ov">
    <div style="margin-bottom:12px;">
      <button class="btn ghost" @click="goBack">← 返回作业列表</button>
    </div>
    <div class="card">
      <div class="card-head">📦 批量对比批次 · {{ ov.batch.batch_id }}
        <span class="group-badge" v-if="ov.batch.label" style="margin-left:6px;" :title="ov.batch.label">🏷 {{ ov.batch.label }}</span>
        <button class="mini-btn" style="margin-left:6px;" @click="editLabel">{{ ov.batch.label ? '改标签' : '加标签' }}</button>
        <span class="badge-status" :class="ov.status">{{ ov.status }}</span>
      </div>
      <div class="card-body">
        <div class="summary-metrics">
          <div class="metric total"><div class="num">{{ ov.total_files }}</div><div class="lbl">对比文件数</div></div>
          <div class="metric diff"><div class="num">{{ ov.diff_files }}</div><div class="lbl">存在差异的文件</div></div>
          <div class="metric"><div class="num">{{ ov.done }}</div><div class="lbl">已完成</div></div>
          <div class="metric only"><div class="num">{{ ov.no_rule_count }}</div><div class="lbl">无规则未对比</div></div>
        </div>
        <div style="margin-top:12px;font-size:12px;color:var(--text-soft);">
          {{ sourceA }}：{{ ov.batch.dir_a }}<br/>{{ sourceB }}：{{ ov.batch.dir_b }}<br/>
          触发时间 {{ fmtTime(ov.batch.created_at) }} · 配置来源 {{ ov.batch.config_source }}
        </div>
        <div v-if="ov.batch.no_rule_files && ov.batch.no_rule_files.length" class="no-rule" style="margin-top:10px;">
          ⚠ 无对应对比规则（未对比）：{{ ov.batch.no_rule_files.join('、') }}
        </div>
      </div>
    </div>

    <div class="card">
      <div class="card-head">📄 文件对比结果（点击查看单文件详情）
        <a class="mini-btn" style="margin-left:auto;" :href="exportHref"
           title="导出批次明细 Excel：文件昵称 / 归属组 / 对比配置 / 对比详情（记录总数与差异情况）。已选归属组时只导出该组">⬇ 导出明细 Excel{{ groupFilter ? '（' + groupFilter + '）' : '' }}</a>
        <a class="mini-btn" style="margin-left:8px;" :href="exportAllHref"
           title="打包下载批次内每个表的全量导出 Excel（zip）">📦 打包下载全部表</a>
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
        <div class="job-row" v-for="c in children" :key="c.job_id" @click="open(c.job_id)">
          <button class="star-btn" :class="{on: c.starred}" @click.stop="toggleStar(c)" :title="c.starred ? '取消星标' : '加星标'">{{ c.starred ? '★' : '☆' }}</button>
          <span class="nick-badge" v-if="nickOf(c)" :title="'配置昵称 ' + nickOf(c)">{{ nickOf(c) }}</span>
          <span class="jid">{{ baseName(c.file_a) }}</span>
          <span class="group-badge" v-if="(c.config||{}).group">{{ c.config.group }}</span>
          <span class="badge-status" :class="c.status">{{ c.status }}</span><span v-if="c.key_warning" class="badge-keywarn" title="主键配置在新旧文本中存在重复键，无法唯一定位记录，该对比配置需要重检">⚠ 主键重复·配置需重检</span>
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
    const form = reactive({ provider: "custom", protocol: "openai", base_url: "", api_key: "", model: "", timeout: 60, enabled: false });
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
        const hit = providers.find(p => p.base_url === r.base_url && (p.protocol || "openai") === form.protocol);
        form.provider = hit ? hit.id : "custom";
      } catch (e) {}
    }
    onMounted(load);

    async function save() {
      busy.value = true; toast.value = ""; test.msg = "";
      try {
        const payload = { protocol: form.protocol, base_url: form.base_url, model: form.model,
                          timeout: Number(form.timeout) || 60, enabled: true };
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

    return { providers, form, apiKeySet, busy, toast, test, curModels, pickProvider, save, testConn,
             rt, saveRuntime, imp, onImpFiles, importStructure,
             base, onBaseFiles, importBaseline, exportConfigs,
             dirs, loadDirs, saveDirs };
  },
  template: `
  <div class="card">
    <div class="card-head">🤖 AI 大模型接入设置</div>
    <div class="card-body">
      <p class="ai-hint" style="margin-top:0;">
        支持 <b>OpenAI 兼容</b>（<code>/v1/chat/completions</code>）与 <b>Anthropic 原生</b>（<code>/v1/messages</code>）两种协议。对接 Claude Code 的 API：选「Anthropic Claude（原生）」预设、填 <code>sk-ant-…</code> 密钥即可。仅向模型发送<b>聚合统计</b>，<b>不发送任何单元格原始值</b>；密钥保存在服务端 <code>results/ai_settings.json</code>。
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
      </div>
      <div class="btn-row">
        <button class="btn" @click="save" :disabled="busy">{{ busy ? '保存中…' : '保存' }}</button>
        <button class="btn ghost" @click="testConn">测试连接</button>
        <span v-if="test.msg" :style="{ color: test.ok ? 'var(--eq-bar)' : 'var(--diff-bar)', fontSize:'13px' }">{{ test.msg }}</span>
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
    let timer = null; let stopped = false;
    async function load() {
      try {
        const [jl, tl] = await Promise.all([api("/api/joblist"), api("/api/tasks")]);
        batches.value = jl.batches; standalone.value = jl.standalone;
        tasks.value = tl.tasks || [];
      } catch (e) {}
      if (!stopped) timer = setTimeout(load, 2500);
    }
    onMounted(load);

    const tasksOf = (jobId) => tasks.value.filter(t => t.job_id === jobId);
    const typeText = (t) => TASK_TYPE_TEXT[t.task_type] || t.task_type;
    const statusText = (t) => TASK_STATUS_TEXT[t.status] || t.status;
    const fileName = (t) => (t.file_a || "").split(/[\\/]/).pop();
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
      catch (e) { alert("重新生成失败: " + e.message); }
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
        if (isNaN(ms)) { alert("执行时间格式错误"); return; }
        run_at = Math.floor(ms / 1000);
      }
      const when = run_at ? "定时 " + new Date(run_at * 1000).toLocaleString() : "立即";
      if (!window.confirm(`将重新生成选中的 ${ids.length} 个任务（差异CSV/AI分析），执行方式：${when}。确认？`)) return;
      try {
        const r = await jpost("/api/tasks/batch-regenerate", { task_ids: ids, run_at });
        alert(`已提交 ${r.updated}/${r.requested} 个任务` + (run_at ? "（到点自动执行，可在任务行查看定时标记）" : ""));
        selTasks.clear(); runAt.value = "";
        await load();
      } catch (e) { alert("批量重新生成失败: " + e.message); }
    }
    async function delTask(id) {
      if (!window.confirm("确认删除该任务记录？（不删除已生成的产物文件）")) return;
      try { await api("/api/tasks/" + id, { method: "DELETE" }); await load(); }
      catch (e) { alert(e.message); }
    }
    return { batches, standalone, openBatches, openJobs, q, tasksOf, typeText, statusText, fileName,
             toggle, toggleBatch, batchChildren, regen, delTask, fmtTime,
             selTasks, runAt, jobAllSel, toggleJobSel, batchRegen,
             open: (id) => emit("open", id), openBatch: (id) => emit("open-batch", id) };
  },
  template: `
  <div>
    <div class="card"><div class="card-body" style="padding:12px 18px;">
      <div class="field"><input v-model="q" placeholder="🔎 搜索任务（按批次/作业/文件/产物路径）" /></div>
      <div style="display:flex;gap:10px;align-items:center;flex-wrap:wrap;margin-top:10px;">
        <span class="count">已选 {{ selTasks.size }} 项任务</span>
        <input type="datetime-local" v-model="runAt" style="width:210px;" title="留空 = 立即执行；选择未来时间 = 定时执行" />
        <button class="mini-btn" @click="batchRegen" :disabled="!selTasks.size">⚡ 批量重新生成（差异CSV / AI分析）</button>
        <button class="mini-btn" @click="selTasks.clear()" :disabled="!selTasks.size">清空选择</button>
        <span class="count" style="opacity:.7;">勾选作业后提交；填了时间则到点自动执行（重启后仍恢复）</span>
      </div>
    </div></div>

    <div class="card" v-for="b in batches" :key="b.batch.batch_id">
      <div class="job-row batch-row" @click="toggleBatch(b.batch.batch_id)">
        <span class="jid">{{ b.batch.batch_id }}</span>
        <span class="badge-status" :class="b.status">{{ b.status }}</span>
        <span class="files">{{ b.batch.dir_a }} ↔ {{ b.batch.dir_b }}</span>
        <span class="group-badge" v-if="b.batch.label">🏷 {{ b.batch.label }}</span>
        <span class="meta-time">{{ fmtTime(b.batch.created_at) }}</span>
        <span class="count">共 {{ b.total_files }} 文件</span>
        <span class="row-actions" @click.stop>
          <button class="mini-btn" @click="openBatch(b.batch.batch_id)">批次详情</button>
          <button class="mini-btn" @click.stop="toggleBatch(b.batch.batch_id)">{{ openBatches.has(b.batch.batch_id) ? '收起 ▲' : '展开 ▼' }}</button>
        </span>
      </div>
      <template v-if="openBatches.has(b.batch.batch_id)">
        <template v-for="c in (batchChildren[b.batch.batch_id] || [])" :key="c.job_id">
          <div class="job-row" style="padding-left:32px;" @click="toggle(openJobs, c.job_id)">
            <label class="sel-all" style="margin-left:0;margin-right:10px;" @click.stop title="选中该作业的差异CSV + AI分析任务"><input type="checkbox" :checked="jobAllSel(c.job_id)" @change="toggleJobSel(c.job_id)" /></label>
            <span class="jid">{{ (c.file_a||'').split(/[\\\\/]/).pop() }}</span>
            <span class="badge-status" :class="c.status">{{ c.status }}</span>
            <span class="row-actions" @click.stop>
              <button class="mini-btn" @click="open(c.job_id)">结果</button>
              <button class="mini-btn" v-if="tasksOf(c.job_id).length" @click.stop="toggle(openJobs, c.job_id)">{{ openJobs.has(c.job_id) ? '收起 ▲' : '展开 ▼' }}</button>
            </span>
          </div>
          <template v-if="openJobs.has(c.job_id)">
            <div class="job-row" style="padding-left:64px;" v-for="t in tasksOf(c.job_id)" :key="t.task_id">
              <span class="jid">{{ typeText(t) }}</span>
              <span class="badge-status" :class="t.status">{{ statusText(t) }}</span>
              <span class="files" :title="t.output_path">{{ t.output_path || (t.error ? '✗ ' + t.error : '—') }}</span>
              <span class="group-badge" v-if="t.trigger==='manual'">手动</span>
              <span class="group-badge" v-if="t.trigger==='batch'">批量</span>
              <span class="group-badge" v-if="t.scheduled_at>0 && t.status==='pending'">⏰ {{ fmtTime(t.scheduled_at) }} 执行</span>
              <span class="meta-time" v-if="t.finished_at">{{ fmtTime(t.finished_at) }}</span>
              <span class="row-actions">
                <button class="mini-btn" @click="regen(t.task_id)" :disabled="t.status==='running'">↻ 重新生成</button>
                <button class="mini-btn danger" @click="delTask(t.task_id)">删除</button>
              </span>
            </div>
          </template>
        </template>
      </template>
    </div>

    <div class="card">
      <div class="card-head">🗂️ 单文件作业任务</div>
      <div>
        <div class="empty" v-if="!standalone.length">无作业</div>
        <template v-for="j in standalone" :key="j.job_id">
          <div class="job-row" @click="toggle(openJobs, j.job_id)">
            <label class="sel-all" style="margin-left:0;margin-right:10px;" @click.stop title="选中该作业的差异CSV + AI分析任务"><input type="checkbox" :checked="jobAllSel(j.job_id)" @change="toggleJobSel(j.job_id)" /></label>
            <span class="jid">{{ j.job_id }}</span>
            <span class="badge-status" :class="j.status">{{ j.status }}</span>
            <span class="files">{{ j.file_a }} ↔ {{ j.file_b }}</span>
            <span class="row-actions" @click.stop>
              <button class="mini-btn" @click="open(j.job_id)">结果</button>
              <button class="mini-btn" v-if="tasksOf(j.job_id).length" @click.stop="toggle(openJobs, j.job_id)">{{ openJobs.has(j.job_id) ? '收起 ▲' : '展开 ▼' }}</button>
            </span>
          </div>
          <template v-if="openJobs.has(j.job_id)">
            <div class="job-row" style="padding-left:32px;" v-for="t in tasksOf(j.job_id)" :key="t.task_id">
              <span class="jid">{{ typeText(t) }}</span>
              <span class="badge-status" :class="t.status">{{ statusText(t) }}</span>
              <span class="files" :title="t.output_path">{{ t.output_path || (t.error ? '✗ ' + t.error : '—') }}</span>
              <span class="group-badge" v-if="t.trigger==='manual'">手动</span>
              <span class="group-badge" v-if="t.trigger==='batch'">批量</span>
              <span class="group-badge" v-if="t.scheduled_at>0 && t.status==='pending'">⏰ {{ fmtTime(t.scheduled_at) }} 执行</span>
              <span class="meta-time" v-if="t.finished_at">{{ fmtTime(t.finished_at) }}</span>
              <span class="row-actions">
                <button class="mini-btn" @click="regen(t.task_id)" :disabled="t.status==='running'">↻ 重新生成</button>
                <button class="mini-btn danger" @click="delTask(t.task_id)">删除</button>
              </span>
            </div>
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
      } catch (e) { alert(e.message); }
    }
    const isOpen = (side, group) => !!opened[side + ":" + group];
    const exportUrl = (fmt) => "/api/split-jobs/" + props.jobId + "/export?fmt=" + fmt;

    return { meta, opened, openGroup, isOpen, exportUrl, ruleText, fmtTime,
             sides: ["a", "b"], back: () => emit("back") };
  },
  template: `
  <div v-if="meta">
    <div style="margin-bottom:12px;">
      <button class="btn ghost" @click="back">← 返回作业列表</button>
    </div>
    <div class="card">
      <div class="card-head">🪓 拆分结果 · {{ meta.job_id }}
        <span class="group-badge" v-if="meta.label" :title="meta.label">🏷 {{ meta.label }}</span>
        <span class="badge-status" :class="meta.status">{{ meta.status }}</span><span v-if="meta.key_warning" class="badge-keywarn" title="主键配置在新旧文本中存在重复键，无法唯一定位记录，该对比配置需要重检">⚠ 主键重复·配置需重检</span>
      </div>
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

    <div class="card" v-for="side in sides" v-if="meta.summary && meta.summary[side]" :key="side">
      <div class="card-head">输入 {{ side.toUpperCase() }} · 共 {{ meta.summary[side].total }} 行
        · trailer {{ meta.summary[side].trailer }} 行</div>
      <div>
        <div class="job-row" v-for="g in meta.summary[side].groups" :key="g.name"
             @click="openGroup(side, g.name)">
          <span class="jid">{{ g.name }}</span>
          <span class="count">{{ g.count }} 条</span>
          <span class="meta-time">{{ isOpen(side, g.name) ? '▲ 收起' : '▼ 查看' }}</span>
        </div>
        <div v-for="g in meta.summary[side].groups" :key="g.name + '-body'"
             v-if="isOpen(side, g.name)" class="saved-content"
             style="white-space:pre-wrap;padding:8px 14px;max-height:320px;overflow:auto;">
          <div v-if="opened[side + ':' + g.name].total > opened[side + ':' + g.name].rows.length"
               style="color:var(--muted);">（仅展示前 {{ opened[side + ':' + g.name].rows.length }} / {{ opened[side + ':' + g.name].total }} 行，完整请导出）</div>
          <div v-for="(ln, k) in opened[side + ':' + g.name].rows" :key="k">{{ ln }}</div>
        </div>
      </div>
    </div>
  </div>
  <div v-else class="empty">加载中…</div>`,
};

const App = {
  components: { SubmitForm, JobList, BatchView, ResultView, SettingsPage, SplitPage, SplitResultView,
                TaskManagerPage },
  setup() {
    const tab = ref("submit");
    const jobId = ref(null);
    const batchId = ref(null);
    const backBatch = ref(null);   // 结果页从某批次进入时记录，用于返回
    const backTab = ref(null);     // 从任务管理进入结果/批次时记录，返回按钮回到任务管理
    const resultNonce = ref(0);    // 自增令牌：即使 job_id 不变（就地重跑）也强制结果页重挂载刷新
    const splitJobId = ref(null);  // 当前查看的拆分作业
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
    return { tab, jobId, batchId, backBatch, backTab, resultNonce, splitJobId, encodings,
             onSubmitted, onBatched, openJob, openBatch, openChild, openJobFromTasks,
             openBatchFromTasks, onRerun, onResultBack, backToJobs, openSplit };
  },
  template: `
  <div class="app-header">
    <div class="logo"><span class="dot"></span> TextDiff</div>
    <div class="spacer"></div>
    <button class="nav-btn" :class="tab==='submit'?'active':''" @click="tab='submit'">新建对比</button>
    <button class="nav-btn" :class="tab==='split'?'active':''" @click="tab='split'">文本拆分</button>
    <button class="nav-btn" :class="(tab==='jobs'||tab==='batch')?'active':''" @click="tab='jobs'">作业列表</button>
    <button class="nav-btn" :class="tab==='tasks'?'active':''" @click="tab='tasks'">任务管理</button>
    <button class="nav-btn" :class="tab==='result'?'active':''" @click="tab='result'" :disabled="!jobId">结果</button>
    <button class="nav-btn" :class="tab==='settings'?'active':''" @click="tab='settings'">设置</button>
  </div>
  <div class="container">
    <SubmitForm v-if="tab==='submit'" :encodings="encodings" @submitted="onSubmitted" @batched="onBatched" />
    <JobList v-else-if="tab==='jobs'" @open="openJob" @open-batch="openBatch" @open-split="openSplit" />
    <TaskManagerPage v-else-if="tab==='tasks'" @open="openJobFromTasks" @open-batch="openBatchFromTasks" />
    <SplitPage v-else-if="tab==='split'" @open-split="openSplit" />
    <SplitResultView v-else-if="tab==='splitresult' && splitJobId" :job-id="splitJobId" :key="splitJobId" @back="backToJobs" />
    <BatchView v-else-if="tab==='batch' && batchId" :batch-id="batchId" :key="batchId" @open="openChild" @back="backToJobs" />
    <ResultView v-else-if="tab==='result' && jobId" :job-id="jobId" :key="jobId + '-' + resultNonce" :encodings="encodings"
                :back-batch="backBatch" @rerun="onRerun" @back="onResultBack" />
    <SettingsPage v-else-if="tab==='settings'" />
  </div>`,
};

createApp(App).mount("#app");
