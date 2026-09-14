/* ============================================================
   Freebuff Mobile — 概念原型交互脚本(零依赖,纯模拟)
   文案集中在 STR(数据层),便于后续本地化
   ============================================================ */
"use strict";

/* ---------------- 图标库 ---------------- */
const I = {
  back: '<path d="M15 5l-7 7 7 7"/>',
  sun: '<circle cx="12" cy="12" r="4.2"/><path d="M12 2.5v2M12 19.5v2M4.6 4.6l1.4 1.4M18 18l1.4 1.4M2.5 12h2M19.5 12h2M4.6 19.4L6 18M18 6l1.4-1.4"/>',
  moon: '<path d="M20.5 14.2A8.5 8.5 0 0 1 9.8 3.5 8.5 8.5 0 1 0 20.5 14.2z"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  send: '<path d="M4.5 12L20 4.5 15.5 20l-4-6.5z"/><path d="M11.5 13.5L20 4.5"/>',
  stop: '<rect x="7" y="7" width="10" height="10" rx="2"/>',
  search: '<circle cx="11" cy="11" r="6.5"/><path d="M16 16l4.5 4.5"/>',
  chevD: '<path d="M6 9.5l6 6 6-6"/>',
  more: '<circle cx="5.5" cy="12" r="1.4" fill="currentColor" stroke="none"/><circle cx="12" cy="12" r="1.4" fill="currentColor" stroke="none"/><circle cx="18.5" cy="12" r="1.4" fill="currentColor" stroke="none"/>',
  copy: '<rect x="9" y="9" width="11" height="11" rx="2.4"/><path d="M5.5 15H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v.5"/>',
  check: '<path d="M4.5 12.5l5 5 10-11"/>',
  refresh: '<path d="M20 11.5A8 8 0 1 0 20.5 16"/><path d="M20 5.5v6h-6"/>',
  pin: '<path d="M12 3l6 6-1.8 1.8L15 9.5V16l-3 3-3-3V9.5L7.8 10.8 6 9z"/><path d="M12 2.5v.5"/>',
  spark: '<path d="M12 3l1.9 5.6L19.5 10.5l-5.6 1.9L12 18l-1.9-5.6L4.5 10.5l5.6-1.9z"/><path d="M19 3.5l.6 1.9 1.9.6-1.9.6L19 8.5l-.6-1.9-1.9-.6 1.9-.6z"/>',
  file: '<path d="M6 2.5h8L20 8.5V21a.5.5 0 0 1-.5.5h-13A.5.5 0 0 1 6 21z"/><path d="M14 2.5V8h6"/>',
  terminal: '<rect x="3" y="4.5" width="18" height="15" rx="2.5"/><path d="M7 9.5l3 3-3 3M13 15.5h4"/>',
  bolt: '<path d="M13 2.5L4.5 13.5h6L11 21.5l8.5-11h-6z"/>',
  bug: '<circle cx="12" cy="13" r="5.4"/><path d="M12 7.6V6.5M12 18.4v-1.1M6.6 13h-3M20.4 13h-3M8.3 8.3l-2-2M15.7 17.7l2 2M15.7 8.3l2-2M8.3 17.7l-2 2"/>',
  trash: '<path d="M4 6.5h16M9.5 6.5V4.8A1.3 1.3 0 0 1 10.8 3.5h2.4a1.3 1.3 0 0 1 1.3 1.3v1.7M6.5 6.5l.9 13a1.5 1.5 0 0 0 1.5 1.4h6.2a1.5 1.5 0 0 0 1.5-1.4l.9-13M10 10.5v6M14 10.5v6"/>',
  gear: '<path d="M12 15.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7z"/><path d="M19.4 15a1.7 1.7 0 0 0 .34 1.87l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.7 1.7 0 0 0-1.87-.34 1.7 1.7 0 0 0-1 1.55V21a2 2 0 1 1-4 0v-.09A1.7 1.7 0 0 0 9 19.36a1.7 1.7 0 0 0-1.87.34l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.7 1.7 0 0 0 .34-1.87 1.7 1.7 0 0 0-1.55-1H3a2 2 0 1 1 0-4h.09A1.7 1.7 0 0 0 4.64 9a1.7 1.7 0 0 0-.34-1.87l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.7 1.7 0 0 0 1.87.34h.08A1.7 1.7 0 0 0 10 3.09V3a2 2 0 1 1 4 0v.09c0 .68.4 1.3 1 1.55.57.26 1.27.15 1.87-.34l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.7 1.7 0 0 0-.34 1.87v.08c.26.61.88 1 1.55 1H21a2 2 0 1 1 0 4h-.09a1.7 1.7 0 0 0-1.55 1z"/>',
  chat: '<path d="M4 5.5A2.5 2.5 0 0 1 6.5 3H11v18H6.5A2.5 2.5 0 0 1 4 18.5z"/><path d="M13 3h4.5A2.5 2.5 0 0 1 20 5.5V11h-7z"/><path d="M13 13h7v5.5a2.5 2.5 0 0 1-2.5 2.5H13z"/>',
  db: '<ellipse cx="12" cy="5.5" rx="7.5" ry="3"/><path d="M4.5 5.5v6c0 1.7 3.4 3 7.5 3s7.5-1.3 7.5-3v-6"/><path d="M4.5 11.5v6c0 1.7 3.4 3 7.5 3s7.5-1.3 7.5-3v-6"/>',
  info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v5M12 8v.5"/>',
  shield: '<path d="M12 3l7 2.8v5.4c0 4.6-3 7.6-7 8.8-4-1.2-7-4.2-7-8.8V5.8z"/><path d="M9.2 12l2 2 3.6-4"/>',
  external: '<path d="M14 4.5h5.5V10M19.5 4.5L10 14"/><path d="M19.5 14.5V19a1.5 1.5 0 0 1-1.5 1.5H5A1.5 1.5 0 0 1 3.5 19V6A1.5 1.5 0 0 1 5 4.5h4.5"/>',
  at: '<circle cx="12" cy="12" r="3.6"/><path d="M15.6 12a3.6 3.6 0 0 1-6.3 2.4A3.6 3.6 0 0 1 12 8.4c1 0 1.8.4 2.4 1M16.8 7.8A7.5 7.5 0 1 0 19.5 12v-.8a2.2 2.2 0 1 0-4.4 0"/>',
  slash: '<path d="M13 4.5l-4 15"/><path d="M9.5 4.5H5M19 19.5h-4.5"/>',
  pinFile: '<path d="M7 3.5h7l4 4V20a.5.5 0 0 1-.5.5h-10A.5.5 0 0 1 7 20z"/><path d="M14 3.5V8h4"/>',
  arrowUp: '<path d="M12 19.5v-15M6 10.5l6-6 6 6"/>',
  layers: '<path d="M12 3.5l8.5 4.5L12 12.5 3.5 8z"/><path d="M3.5 13l8.5 4.5 8.5-4.5"/><path d="M3.5 17.5L12 22l8.5-4.5"/>',
  clock: '<circle cx="12" cy="12" r="8.5"/><path d="M12 7.5V12l3 2"/>',
  git: '<path d="M6 3v12"/><circle cx="18" cy="6" r="2.8"/><circle cx="6" cy="18" r="2.8"/><path d="M18 8.8A9.2 9.2 0 0 1 8.8 18"/>',
  link: '<path d="M10 13.2a5 5 0 0 0 7.6.6l2.6-2.6a5 5 0 0 0-7.1-7.1L11.8 5.9"/><path d="M14 10.8a5 5 0 0 0-7.6-.6L3.8 12.8a5 5 0 0 0 7.1 7.1l1.3-1.3"/>',
  key: '<path d="m21 2-9.6 9.6"/><circle cx="7.5" cy="15.5" r="5.5"/><path d="m15.5 7 3 3L22 6l-3-3"/>',
  plug: '<path d="M12 21v-6M8 21v-6M9 9V3M15 9V3M7 9h10a1 1 0 0 1 1 1v2a6 6 0 0 1-6 6 6 6 0 0 1-6-6v-2a1 1 0 0 1 1-1z"/>',
  edit: '<path d="M4.5 19.5l1-4.2L16.6 4.2a2 2 0 0 1 2.8 0l.4.4a2 2 0 0 1 0 2.8L8.7 18.5z"/><path d="M14 6.5l3.5 3.5"/>',
  eye: '<path d="M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z"/><circle cx="12" cy="12" r="3"/>',
  eyeOff: '<path d="M4 4l16 16"/><path d="M6.5 7.6A16 16 0 0 0 2.5 12S6 18.5 12 18.5c1.9 0 3.6-.7 5-1.7"/><path d="M9.9 10a3 3 0 0 0 4.2 4.2"/>',
};
const svg = (name, cls) =>
  `<svg class="${cls || ""}" viewBox="0 0 24 24" aria-hidden="true">${I[name] || ""}</svg>`;

/* ---------------- 文案(数据层) ---------------- */
const STR = {
  appName: "Freebuff",
  protoTag: "概念原型",
  tagline: "免费的 AI 编程与创作助手",
  welcome: {
    h1: "把想法,交给",
    h1Accent: "Freebuff",
    sub: "官方安卓 App · 概念原型。一个随身携带的编码 Agent:会规划、会改代码、会解释,并且完全免费。",
    f1: "多个前沿模型,无需订阅或 API Key",
    f2: "Agent 自动规划、执行并解释每一步",
    f3: "会话云端同步 · 本地优先,随时离线可用",
    login: "登录 Freebuff 账号",
    guest: "先逛逛(访客演示)",
    legal: "登录即代表同意《服务条款》与《隐私政策》。本原型不连接真实服务,登录为演示流程。",
    note: "游客演示 · 数据仅存于本机",
  },
  home: {
    searchPh: "搜索会话",
    groupRecent: "最近",
    groupEarlier: "更早",
    emptyTitle: "还没有会话",
    emptySub: "点右下角「+」发起你的第一个任务",
    newChat: "新对话",
  },
  chat: {
    emptyTitle: "今天想构建点什么?",
    emptySub: "可以直接描述需求,或从下面的示例开始",
    examples: "试试这些",
    composerPh: "输入消息,或输入 / 查看命令…",
    agentName: "Freebuff Agent",
    demoHint: "原型演示:回复为预置模拟,不连接真实模型",
    stop: "已停止生成",
    copy: "复制",
    copied: "已复制",
    statusPlan: "正在理解需求并规划方案",
    statusThink: "正在组织回复",
    think: "思考",
    plan: "规划",
    exec: "执行",
    done: "完成",
    pills: { ctx: "阅读上下文", plan: "生成方案", check: "校验输出" },
    more: { title: "对话操作", rename: "重命名对话", model: "切换模型", theme: "切换主题", clear: "清空消息", del: "删除此对话" },
    sessMenu: { open: "打开对话", del: "删除对话" },
    stoppedNote: "已停止生成,可继续输入或重新发送",
  },
  model: {
    title: "选择模型",
    sub: "模型目录基于 Freebuff 官方版本;可用范围以账号与地区为准",
    current: "当前模型",
    footnote: "GLM 5.3 Flash、DeepSeek V4 Flash 等模型在完整访问下不限量。部分模型按每日会话额度计费。",
    demo: "演示数据",
  },
  task: {
    title: "发起任务",
    sub: "三步配置:仓库 → 模型 → 任务描述",
    step1: "代码仓库",
    step2: "选择模型",
    step3: "任务描述",
    prev: "上一步",
    next: "下一步",
    launch: "发起任务",
    repoNone: "不关联仓库",
    repoNoneSub: "Agent 仅凭任务描述工作,不读写任何仓库",
    repoManual: "手动输入仓库地址",
    repoManualSub: "任意 git 地址(GitHub / GitLab / Gitee 等)",
    repoManualPh: "git@github.com:user/repo.git 或 https://…",
    repoManualReq: "请填写仓库地址",
    repoInvalid: "仓库地址不完整:需包含主机与仓库路径,如 github.com/user/repo 或 git@github.com:user/repo.git",
    repoSchemeAuto: "已补全 https://",
    repoSchemeKeep: "原样使用",
    repoQuick: "常用平台",
    repoParsed: "已解析仓库地址",
    repoGit: "Git 账号仓库",
    repoGitSub: "账号下的仓库(演示数据)",
    repoGitOff: "连接 Git 账号后即可选择其仓库",
    repoGitGo: "去连接 Git 账号",
    official: "官方模型",
    custom: "自定义模型",
    noCustom: "还没有自定义模型,可在「设置 → 集成」中添加",
    taskLabel: "任务描述",
    taskPh: "描述你要完成的任务,例如:给仓库添加一个 CI 工作流,并补充 README 使用说明…",
    taskReq: "请先描述任务内容",
    sumRepo: "仓库",
    sumModel: "模型",
    launched: "任务已发起",
  },
  update: {
    title: "检查更新",
    checking: "正在检查最新版本…",
    latestTitle: "已是最新版本",
    latestSub: "当前已是最新版本,无需更新",
    foundTitle: "发现新版本",
    foundSub: "有可用更新,同意后将下载并安装最新版本",
    curVersion: "当前版本",
    newVersion: "最新版本",
    changelogTitle: "更新内容",
    changelog: [
      "新增「发起任务」三步向导:仓库 → 模型 → 任务",
      "模型选择支持官方模型与自定义模型分组展示",
      "多处体验优化与问题修复",
    ],
    later: "暂不更新",
    agree: "同意并更新",
    downloading: "正在下载更新包…",
    installing: "正在安装更新…",
    restarting: "更新完成,正在重启应用…",
  },
  settings: {
    title: "设置",
    gAppearance: "外观",
    theme: "主题",
    tSystem: "跟随系统",
    tDark: "深色",
    tLight: "浅色",
    gModel: "模型",
    rowModel: "当前模型",
    rowModelSub: "点击选择对话使用的模型",
    gIntegrations: "集成",
    rowGit: "Git 账号",
    rowGitSub: "授权代码托管账号,让 Agent 直接读写你的仓库",
    gitOff: "未连接",
    rowCustom: "自定义模型",
    rowCustomSub: "接入 OpenAI 兼容 API 端点,可作为对话模型使用",
    customOff: "未添加",
    customCount: " 个",
    rowRepoParse: "仓库地址解析",
    rowRepoParseSub: "粘贴仓库地址时的默认解析方式",
    rpStrict: "严格解析",
    rpLoose: "宽松原样",
    rpSnackStrict: "已切换:严格解析(自动解析 git clone 并补全协议)",
    rpSnackLoose: "已切换:宽松原样(保持输入原样,不做解析改写)",
    gData: "会话与数据",
    rowLocal: "数据保存在本机",
    rowLocalSub: "原型阶段会话仅存于当前设备,不上传",
    rowClear: "清除全部会话",
    rowClearSub: "删除本机所有演示会话",
    confirmClear: "再次点击确认清除",
    cleared: "已清除全部会话",
    gAbout: "关于",
    rowFree: "为什么免费?",
    rowFreeSub: "免费模式由文字广告支持",
    rowAbout: "关于 Freebuff Mobile",
    rowVersion: "版本",
    rowVersionSub: "点击检查最新版本",
    version: "0.1.0 · 概念原型",
    rowSite: "官方网站 freebuff.com",
    rowFeedback: "反馈与建议",
    feedbackPh: "原型阶段暂无真实反馈通道(演示)",
    freeNoteTitle: "免费模式",
    freeNote: "Freebuff 免费向所有用户开放,无需订阅或 API Key。部分时段展示文字广告以支持模型访问成本;登录与限额以实际账号为准。",
    aboutBody:
      "这是为 Freebuff 官方开源仓库(CodebuffAI/freebuff)规划的安卓手机 App 概念原型。\n原型用于评审信息架构、交互与视觉方向,所有回复均为预置模拟,不连接真实模型。\n\n界面文案集中存放在 js/app.js 的 STR 数据层,方便后续本地化与正式设计系统接入。",
    aboutPlan: "后续计划",
    aboutPlanBody: "原型评审通过后:1) 输出交互走查清单与视觉规范;2) 讨论是否以官方仓库新增产品目录的方式推进原生安卓工程。",
  },
  integ: {
    gitTitle: "Git 账号",
    gitSub: "授权后,Freebuff Agent 可以直接读取你的仓库、创建分支并提交变更",
    gitHero: "GitHub",
    gitDemoNote:
      "演示连接:仿真 OAuth 授权流程,不会发起真实请求,账号数据仅存在于本机。正式接入将支持 GitHub / GitLab 等平台。",
    connectBtn: "连接 GitHub 账号",
    connecting: "正在打开 GitHub 授权…",
    connPill: "已连接",
    acctName: "Octocat 演示账号",
    permsTitle: "授权范围(演示预设)",
    perms: ["读取你的仓库与分支", "在你的仓库创建分支并提交变更", "不会访问你的私有邮箱"],
    permsNote: "原型数据仅供演示,实际授权范围以正式版接入为准",
    revokeBtn: "断开连接",
    revokeConfirm: "再次点击确认断开",
    revokeHint: "断开后,Agent 将无法再访问你的仓库",
    snackConnected: "已连接 GitHub 账号(演示)",
    snackRevoked: "已断开 Git 账号(演示)",
    customTitle: "自定义模型",
    customSub: "把自托管的 OpenAI 兼容端点接入 Freebuff,添加后即可在模型列表中选用",
    emptyTitle: "还没有自定义模型",
    emptySub:
      "支持任意 OpenAI 兼容的 /v1/chat/completions 服务,例如 Ollama、vLLM 或中转网关",
    addBtn: "添加自定义模型",
    formTitle: "添加自定义模型",
    formSub: "填写端点信息,保存后即可在「选择模型」中看到它",
    fName: "显示名称",
    fNamePh: "例如:本地 Llama 3",
    fBase: "Base URL",
    fBasePh: "https://api.example.com/v1",
    baseHint: "须为 OpenAI 兼容端点;支持中转站:可直接粘贴以 /chat/completions 结尾的完整地址,否则自动拼接该路径",
    epAuto: "自动拼接",
    epDirect: "完整路径 · 直接使用",
    epEmpty: "输入后实时显示请求地址",
    requesting: "正在请求",
    modelsTitle: "可用模型(模拟 /v1/models)",
    modelsSub: "连接成功,已拉取服务端模型列表:点击任意模型填入「模型 ID」",
    snapTitle: "上次可用模型(已保存快照)",
    snapSub: "来自上次测试连接;点击填入「模型 ID」,或重新「测试连接」刷新",
    snapRow: "上次可用 {n} 个模型",
    rebuildBtn: "从快照重建",
    rebuiltSnack: "已从快照填入模型 ID",
    snapDropTitle: "快照模型 ID(点击即切换本端点的模型)",
    snapPicked: "已切换模型 ID",
    repairTitle: "已自动修复 {n} 条异常记录",
    repairSub: "为保证列表稳定,损坏的自定义模型记录已被自动清理或修复",
    repairMore: "查看明细",
    repairLess: "收起",
    repairClose: "关闭",
    repairDrop: "第 {i} 条:记录异常(非对象/空),已移除",
    repairId: "第 {i} 条:缺少有效 id,已自动补全",
    repairName: "第 {i} 条:名称为空,已使用默认名称",
    repairModels: "第 {i} 条:快照列表过滤了 {n} 项非文本内容",
    repairField: "第 {i} 条:存在非文本字段,已置空",
    repairStat: "原始 {a} 条 → 保留 {b} 条(修复 {c} 条)",
    repairReport: "查看报告",
    repairRestore: "恢复原始记录",
    repairRestoreNote: "仅本次会话生效,不写回存档;重新进入后将再次自动修复",
    repairRestoredTitle: "已恢复原始记录",
    repairRestoredSub: "仅本次会话生效;存档仍保持修复后的干净数据",
    repairSnackRestored: "已恢复原始记录(仅本次会话)",
    retestBtn: "重新测试连接",
    retesting: "正在重新测试连接…",
    retestSnack: "已刷新快照,可用 {n} 个模型",
    dotBusy: "测试中…",
    dotTested: "已测试 {m} · 延迟 {ms}ms",
    dotOk: "上次连通 {t} · 延迟 {ms}ms",
    dotUntested: "未测试 · 点击测试该模型",
    repairConfirmTitle: "确认恢复原始记录",
    repairConfirmSub: "恢复将逐条回退为修复前的原始字段(仅本次会话生效)",
    repairDiffRec: "第 {i} 条记录",
    repairDiffDropped: "该记录已丢弃(非对象/空),无法恢复",
    repairDiffCancel: "取消",
    repairDiffDo: "确认恢复",
    repairDiffHint: "仅字段有差异的行会列出:左侧为原始值,右侧为已修复值",
    filledSnack: "已填入模型 ID",
    fModelId: "模型 ID",
    fModelIdPh: "例如:deepseek-chat / gpt-4o-mini",
    fKey: "API Key",
    fKeyOpt: "可选",
    fKeyPh: "sk-…",
    fKeyHint: "演示输入框,原型不会发送、也不会保存真实密钥",
    testBtn: "测试连接",
    testing: "正在测试…",
    testOk: "连接成功 · 返回 200",
    saveBtn: "保存",
    savedSnack: "已添加自定义模型",
    emptyErr: "请填写显示名称、Base URL 与模型 ID",
    useBtn: "使用此模型",
    delConfirm: "再次点击删除",
    delSnack: "已删除自定义模型",
    sectionCustom: "自定义模型",
    sectionOfficial: "官方模型",
    tierText: "自定义",
    modelNote: "官方目录基于 Freebuff 当前版本;自定义模型由你自行提供端点与密钥",
    footGo: "去「设置 → 集成」添加 →",
    formSecBasic: "基本信息",
    formSecEndpoint: "端点",
    fCtx: "上下文长度",
    fCtxOpts: ["8K", "32K", "128K", "524K"],
    fTimeout: "请求超时",
    fToOpts: ["30 秒", "60 秒", "120 秒", "300 秒"],
    fTLS: "关闭 TLS 证书校验",
    fTLSSub: "自建 HTTPS 端点证书异常时开启(演示开关)",
    advTitle: "高级选项",
    fHeaders: "自定义请求头(可选)",
    fHeadersPh: "每行一条,格式: 头名: 值,例如: Authorization: Bearer sk-xxx",
    fHeadersHint: "原型仅演示,不会发送真实请求",
    showKey: "显示密钥",
    hideKey: "隐藏密钥",
    editTitle: "编辑自定义模型",
    editedSnack: "已更新自定义模型",
  },
  snack: {
    guestLogin: "已进入访客演示模式(原型不连接真实服务)",
    loginDemo: "演示版登录流程(概念原型不接真实账号)",
    modelSet: "已切换模型:",
    chatDeleted: "已删除对话",
    chatCleared: "已清空此对话的消息",
    copied: "已复制到剪贴板",
    copiedFail: "复制失败,请手动选择",
    newChat: "已创建新对话",
    themeSet: "主题已切换",
    demoOnly: "原型阶段为演示操作,不产生真实请求",
    notFound: "未找到该会话",
    resetDemo: "已重置为初始演示数据",
  },
  sheets: {
    demoTitle: "关于本原型",
    demoDesc:
      "这是 Freebuff 官方安卓 App 的概念原型(HTML 高保真设计稿)。所有回复为预置模拟,不连接真实模型;界面文案为中文演示版。",
  },
};

/* ---------------- 模型目录(基于官方 README) ---------------- */
const MODELS = [
  { id: "deepseek-v4-flash", name: "DeepSeek V4 Flash", badge: "DS", tier: "full", tierText: "完整访问", desc: "默认模型 · 快速编码与工具调用,不限量" },
  { id: "glm-5.3-flash", name: "GLM 5.3 Flash", badge: "GLM", tier: "full", tierText: "完整访问", desc: "深度推理最强,不限量" },
  { id: "gpt-5.6-luna", name: "GPT-5.6 Luna", badge: "5.6", tier: "full", tierText: "完整访问", desc: "全能表现,原生图像支持" },
  { id: "mimo-2.5", name: "MiMo 2.5", badge: "MiMo", tier: "full", tierText: "完整访问", desc: "性能均衡,支持图像" },
  { id: "solar-pro-4", name: "Solar Pro 4", badge: "S4", tier: "trial", tierText: "限时试用", desc: "524K 超长上下文 · 仅文本" },
];
/* 全部模型 = 自定义(OpenAI 兼容)+ 官方目录 */
function modelList() {
  const customs = (S ? S.customModels : []).map((c) => ({
    id: c.id,
    name: c.name,
    badge: (c.name || "API").trim().slice(0, 2).toUpperCase(),
    tier: "custom",
    tierText: STR.integ.tierText,
    desc: `OpenAI 兼容 · ${c.apiId || c.base || "自定义端点"}${c.ctx ? " · " + c.ctx : ""}`,
  }));
  return [...customs, ...MODELS];
}
const modelById = (id) => modelList().find((m) => m.id === id) || MODELS[0];
const TIER_CLS = { full: "full", limited: "limited", trial: "trial", custom: "custom" };
/* URL 工具:协议自动补全 + 完整请求路径 */
function normEndpoint(raw) {
  let s = (raw || "").trim();
  if (!s) return "";
  if (s.indexOf("://") === -1) s = "https://" + s;
  return s;
}
function endpointUrl(base) {
  let b = normEndpoint(base);
  while (b.endsWith("/")) b = b.slice(0, -1);
  if (!b) return "";
  return b.toLowerCase().endsWith("/chat/completions") ? b : b + "/chat/completions";
}
/* 密钥脱敏(差异对比用) */
function maskKey(k) {
  const st = String(k || "");
  return st.length <= 8 ? "••••" : st.slice(0, 2) + "…" + st.slice(-4);
}
/* 时间格式化 HH:MM:SS */
function fmtT(t) {
  try { return new Date(t).toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false }); }
  catch (e) { return "--:--:--"; }
}
/* 修复差异字段标签 */
const REPAIR_FIELDS = [
  ["id", "ID"], ["name", "名称"], ["apiId", "模型 ID"], ["base", "Base URL"], ["key", "API Key"],
  ["ctx", "上下文"], ["timeout", "超时"], ["headers", "请求头"], ["skipTLS", "跳过 TLS"], ["models", "模型快照"],
];
/* 仓库地址规范化:http(s) 原样 / SSH(git@host:path)原样 / 其余补 https:// */
function normRepoUrl(raw) {
  let s = (raw || "").trim();
  if (!s) return "";
  if (s.indexOf("://") !== -1) return s; // http(s)://… 或 ssh://… 原样
  const at = s.indexOf("@");
  if (at > 0 && s.indexOf(":", at) > at) return s; // git@github.com:user/repo.git 原样
  return "https://" + s;
}
/* 解析 git clone 命令 / 带分支深链,返回 { host, path, repo, branch } */
function parseClone(raw) {
  let s = String(raw || "").trim();
  let branch = "";
  const low = s.toLowerCase();
  if (low.indexOf("git clone") === 0) {
    s = s.slice("git clone".length).trim().split("	").join(" ").split(" ").filter(Boolean);
    const toks = Array.isArray(s) ? s : [];
    const urlTok = [];
    for (let k = 0; k < toks.length; k++) {
      const tk = toks[k];
      if (tk === "-b" || tk === "--branch") { if (toks[k + 1]) { branch = toks[k + 1]; k++; } continue; }
      if (tk.indexOf("--branch=") === 0) { branch = tk.slice("--branch=".length); continue; }
      if (tk === "-o" || tk === "-j" || tk === "--depth" || tk === "--jobs" || tk === "--origin") { k++; continue; }
      if (tk.charAt(0) === "-") continue;
      urlTok.push(tk);
    }
    s = urlTok[0] || "";
  }
  if (!s) return null;
  let frag = "";
  const hash = s.indexOf("#");
  if (hash !== -1) { frag = s.slice(hash + 1); s = s.slice(0, hash); }
  const q = s.indexOf("?");
  if (q !== -1) s = s.slice(0, q);
  const treeIdx = s.lastIndexOf("/tree/");
  if (treeIdx !== -1) {
    const rest = s.slice(treeIdx + 6);
    if (rest && rest.indexOf("/") === -1) {
      if (!branch) branch = rest;
      s = s.slice(0, treeIdx);
    }
  } else if (frag && !branch) {
    branch = frag;
  }
  let host = "";
  let path = "";
  let scheme = "";
  const sc = s.indexOf("://");
  if (sc !== -1) {
    scheme = s.slice(0, sc);
    const after = s.slice(sc + 3);
    const slash = after.indexOf("/");
    if (slash === -1) return null;
    host = after.slice(0, slash);
    path = after.slice(slash + 1);
  } else {
    const at = s.indexOf("@");
    if (at > 0) {
      const colon = s.indexOf(":", at);
      if (colon === -1) return null;
      host = s.slice(at + 1, colon);
      path = s.slice(colon + 1);
    } else {
      const slash = s.indexOf("/");
      if (slash === -1) return null;
      host = s.slice(0, slash);
      path = s.slice(slash + 1);
    }
  }
  if (!host || !path) return null;
  const parts = path.split("/").filter(Boolean);
  if (parts.length < 2) return null;
  const last = parts.length - 1;
  if (parts[last].toLowerCase().endsWith(".git")) parts[last] = parts[last].slice(0, -4);
  return { host: host, path: parts.join("/"), repo: parts[last], branch: branch || "", scheme: scheme };
}

/* ---------------- 预置演示会话 ---------------- */
const esc = (s) =>
  String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");

const SEED = [
  {
    id: "s1",
    title: "用 Python 写一个批量重命名脚本",
    time: "10 分钟前",
    preview: "我帮你写了一个安全、可回滚的批量重命名脚本…",
    messages: [
      {
        role: "user",
        text: "帮我写一个 Python 脚本:把文件夹里所有 jpg 图片按拍摄时间批量重命名为 IMG_20260904_001.jpg 这种格式,顺便去重。",
      },
      {
        role: "agent",
        time: "9 分钟前",
        steps: [
          { name: "规划", sub: "读取目录 → 提取 EXIF 时间 → 冲突去重 → 安全执行" },
        ],
        md: `**好的**,我来帮你实现。这个脚本分三步:

1. 遍历目录,只处理 \`.jpg\` / \`.jpeg\`
2. 从 EXIF 读取拍摄时间,没有则回退到文件修改时间
3. 检查目标文件名是否冲突,冲突自动加 \`_1\`、\`_2\` 后缀

这是完整代码:`,
        code: { lang: "python", text: `import os
from datetime import datetime
from PIL import Image
from PIL.ExifTags import TAGS

def shoot_time(path: str) -> datetime:
    with Image.open(path) as img:
        exif = img.getexif()
        if exif:
            raw = exif.get(0x9003)  # DateTimeOriginal
            if raw:
                return datetime.strptime(raw, "%Y:%m:%d %H:%M:%S")
    return datetime.fromtimestamp(os.path.getmtime(path))

def rename_batch(folder: str) -> None:
    seen: set[str] = set()
    for f in sorted(os.listdir(folder)):
        if not f.lower().endswith((".jpg", ".jpeg")):
            continue
        t = shoot_time(os.path.join(folder, f))
        base = t.strftime("IMG_%Y%m%d_%H%M%S")
        name, i = base, 1
        while name in seen or os.path.exists(os.path.join(folder, name + ".jpg")):
            name, i = f"{base}_{i}", i + 1
        seen.add(name)
        os.rename(os.path.join(folder, f), os.path.join(folder, name + ".jpg"))
        print(f"✓ {f} → {name}.jpg")

if __name__ == "__main__":
    rename_batch(".")` },
        md2: `**使用方式**

\`\`\`bash
pip install pillow
python rename_batch.py
\`\`\`

几个说明:
- **安全**:先跑在小目录上验证;要试运行的话,把最后的 \`os.rename\` 换成 \`print\` 即可
- **去重**:按时间到秒保证唯一,极端同秒时追加序号
- 支持重命名前自动打印一张清单的版本,需要的话告诉我`,
      },
      { role: "user", text: "太棒了!再帮我加一个「dry run」参数?" },
      {
        role: "agent",
        time: "1 分钟前",
        steps: [{ name: "执行", sub: "增加 --dry-run 与 --verbose 参数" }],
        md: `当然可以。用 \`argparse\` 加两个开关,默认走**试运行**,确认无误后再真正改名:

\`\`\`python
import argparse

parser = argparse.ArgumentParser(description="批量重命名图片")
parser.add_argument("folder", nargs="?", default=".", help="目标目录")
parser.add_argument("--dry-run", action="store_true", help="只打印,不改名")
parser.add_argument("--verbose", "-v", action="store_true")
args = parser.parse_args()

# rename_batch 内:
#   if args.dry_run:
#       print(f"将改名: {f} → {name}.jpg")   # 不执行 os.rename
\`\`\`

跑 \`python rename_batch.py --dry-run\` 先看清单,确认无误后去掉参数执行即可 ✅`,
      },
    ],
  },
  {
    id: "s2",
    title: "React 组件为什么重复渲染?",
    time: "昨天",
    preview: "问题的根源在于每次渲染都创建了新函数引用…",
    messages: [
      { role: "user", text: "我的 React 组件每次输入都卡,子组件也一直被重复渲染,是什么原因?" },
      {
        role: "agent",
        time: "昨天",
        steps: [
          { name: "定位", sub: "查找组件树与引用稳定性问题" },
        ],
        md: `先看最常见的三个原因,按概率排序:

1. **内联函数导致子组件 props 引用变化**
2. **Context / 全局状态把整棵子树都带崩**
3. **组件定义在父组件函数体内,每次渲染都是新类型**

其中第一个最常见。看这个例子:`,
        code: {
          lang: "jsx",
          text: `// ❌ 每次渲染,onChange 都是新的引用
function Parent() {
  return <Child onChange={(v) => setVal(v)} />
}

// ✅ 用 useCallback 稳定引用
function Parent() {
  const onChange = useCallback((v) => setVal(v), [])
  return <Child onChange={onChange} />
}`,
        },
        md2: `**快速排查清单**

- 给子组件加 \`React.memo\`,先确认问题是否消失
- 用 React DevTools 的 \`Profiler\` 看是哪一层 \`rendered\`
- 检查是否在 \`useEffect\` 里 \`setState\` 造成死循环

把组件代码发我,我可以帮你精确定位是哪一种。`,
      },
    ],
  },
  {
    id: "s3",
    title: "这段日志报错是什么意思?",
    time: "周二",
    preview: "这是典型的空值访问错误,通常是接口返回的结构和预期不一致…",
    messages: [
      {
        role: "user",
        text: "帮我看看这行报错:\nTypeError: Cannot read properties of undefined (reading 'map')",
      },
      {
        role: "agent",
        time: "周二",
        steps: [{ name: "分析", sub: "定位到 axios 响应层的数据结构问题" }],
        md: `这个报错说明你在 **undefined 上调用了 \`.map()\`**。最常见的来源是接口结构变化——比如服务端把数组包了一层:

\`\`\`json
// 你期望的:
{ "data": [{ ... }, { ... }] }
// 实际返回:
{ "data": { "list": [{ ... }] } }   // data 是对象,没有 map
\`\`\`

**建议这样防御:**

\`\`\`js
const list = res?.data?.list ?? res?.data ?? []
return list.map(item => ...)
\`\`\`

如果还想要更精确的判断,可以把接口的返回 JSON 发给我,我帮你确认是哪一层的问题。`,
      },
    ],
  },
];

/* 建议快捷指令(空对话时) */
const SUGGESTS = [
  { icon: "terminal", text: "帮我写一个定时清理临时文件的脚本" },
  { icon: "bolt", text: "这段代码为什么慢?帮我优化" },
  { icon: "layers", text: "给我一份 30 天 Python 学习路线" },
  { icon: "bug", text: "排查报错:undefined 的 map 调用" },
];

/* 新对话的演示回复(模拟) */
function demoReply(userText) {
  const q = (userText || "").trim().replace(/\s+/g, " ").slice(0, 24);
  return {
    steps: [{ name: "规划", sub: "理解需求 → 拆解步骤 → 给出可直接使用的方案" }],
    md: `收到,**${esc(q)}** ——这是演示会话,我会按 Freebuff 的方式给你一段模拟回复。

先总结一下我会怎么做:

1. 拆解你的需求,明确输入与输出
2. 选择最直接的实现方案
3. 给出可运行的示例并解释关键点

下面是一段示例代码(仅为展示原型中的代码块与复制交互):`,
    code: {
      lang: "python",
      text: `def solve(question: str) -> str:
    """演示:原型阶段不连接真实模型"""
    plan = [
        "1. 明确输入与期望输出",
        "2. 选择合适的数据结构与算法",
        "3. 编写边界测试,逐步验证",
    ]
    return "\\n".join(plan) + f"\\n→ 你的问题: {question}"`,
    },
    md2: `**小结**

- 上面的结构就是 Agent 处理问题的通用套路
- 代码块右上角可以**一键复制**
- 顶部模型胶囊可以随时**切换模型**

> 提示:当前是概念原型(纯模拟)。原型评审通过后,这一步会接入真实模型与官方会话服务。`,
  };
}

/* ============================================================
   状态
   ============================================================ */
const LS_KEY = "freebuff_proto_v1";
let S = {
  themeMode: "dark", // 'system' | 'dark' | 'light'
  modelId: "deepseek-v4-flash",
  version: "0.1.0",
  signedIn: false,
  sessions: [],
  activeId: null,
  route: "welcome",
  streaming: false,
  armedClear: false,
  repoParse: "strict", // 'strict' | 'loose'
  git: { connected: false, provider: "", login: "", name: "" },
  customModels: [], // { id, name, apiId, base, key }
};
const uid = () => "id" + Math.random().toString(36).slice(2, 9);

/* 本地持久化(仅主题/模型/登录态) */
function persist() {
  try {
    localStorage.setItem(
      LS_KEY,
      JSON.stringify({
        themeMode: S.themeMode,
        modelId: S.modelId,
        repoParse: S.repoParse,
        version: S.version,
        signedIn: S.signedIn,
        git: S.git,
        customModels: S.customModels,
      })
    );
  } catch (e) {}
}
/* 修复持久化的自定义模型记录:丢弃脏条目、补齐字段类型,杜绝脏数据导致列表/表单打不开 */
let REPAIR = { fixed: 0, items: [], shown: false, restored: false, raw: null, rawN: 0, nowN: 0 };
function sanitizeCustomModels() {
  const rep = { fixed: 0, items: [], restored: false, raw: null, rawN: 0, nowN: 0 };
  if (!Array.isArray(S.customModels)) { S.customModels = []; return rep; }
  try { rep.raw = JSON.parse(JSON.stringify(S.customModels)); } catch (e) { rep.raw = null; }
  rep.rawN = S.customModels.length;
  const out = [];
  S.customModels.forEach((c, i) => {
    const fixes = [];
    if (!c || typeof c !== "object") {
      fixes.push({ code: "drop", n: 1 });
      rep.items.push({ i: i + 1, fixes, raw: null });
      rep.fixed++;
      return;
    }
    let id = typeof c.id === "string" && c.id ? c.id : "";
    if (!id) { id = "cm-" + uid(); fixes.push({ code: "id", n: 1 }); }
    let name = typeof c.name === "string" && c.name ? c.name : "";
    if (!name) { name = "未命名模型"; fixes.push({ code: "name", n: 1 }); }
    let strFix = 0;
    const s = (v) => (v === undefined ? "" : typeof v === "string" ? v : (strFix++, ""));
    const rawModels = Array.isArray(c.models) ? c.models : [];
    const models = rawModels.filter((m) => typeof m === "string");
    if (models.length !== rawModels.length) fixes.push({ code: "models", n: rawModels.length - models.length });
    const rec = {
      id: id,
      name: name,
      apiId: s(c.apiId),
      base: s(c.base),
      key: s(c.key),
      ctx: s(c.ctx),
      timeout: s(c.timeout),
      headers: s(c.headers),
      skipTLS: !!c.skipTLS,
      models: models.length ? models : undefined,
      probe: c.probe && typeof c.probe === "object" ? c.probe : undefined,
    };
    if (strFix) fixes.push({ code: "field", n: strFix });
    if (fixes.length) {
      rep.fixed++;
      rep.items.push({ i: i + 1, fixes, raw: Object.assign({}, c), fixed: rec });
    }
    out.push(rec);
  });
  S.customModels = out;
  rep.nowN = out.length;
  return rep;
}
function loadPrefs() {
  try {
    const raw = localStorage.getItem(LS_KEY);
    if (raw) Object.assign(S, JSON.parse(raw));
  } catch (e) {}
  // 兼容旧存档:补齐默认形状
  REPAIR = sanitizeCustomModels();
  if (!S.version) S.version = "0.1.0";
  S.git = Object.assign({ connected: false, provider: "", login: "", name: "" }, S.git || {});
  if (S.repoParse !== "loose") S.repoParse = "strict";  if (REPAIR.fixed) persist(); // 修复后立即回写存档,避免每次启动重复修复
}
function seedSessions() {
  S.sessions = SEED.map((s) => ({
    id: s.id,
    title: s.title,
    time: s.time,
    preview: s.preview,
    messages: s.messages.map((m) =>
      m.role === "user"
        ? { id: uid(), role: "user", text: m.text }
        : { id: uid(), role: "agent", time: m.time, steps: m.steps || [], md: m.md, code: m.code || null, md2: m.md2 || "" }
    ),
  }));
}
const activeSession = () => S.sessions.find((s) => s.id === S.activeId);

/* ---------------- DOM 快捷引用 ---------------- */
const $ = (sel) => document.querySelector(sel);
const screensEl = $("#screens");
const tabbarEl = $("#tabbar");
const overlayRoot = $("#overlayRoot");
const snackEl = $("#snackbar");

/* ============================================================
   主题
   ============================================================ */
const mediaDark = window.matchMedia("(prefers-color-scheme: dark)");
function effectiveTheme() {
  if (S.themeMode === "system") return mediaDark.matches ? "dark" : "light";
  return S.themeMode;
}
function applyTheme(quiet) {
  const t = effectiveTheme();
  document.documentElement.dataset.theme = t;
  const meta = document.querySelector('meta[name="theme-color"]');
  if (meta) meta.content = t === "dark" ? "#0c0c0f" : "#f6f6f4";
  if (!quiet) snack(STR.snack.themeSet, "check");
  updateThemeToggles();
}
mediaDark.addEventListener("change", () => {
  if (S.themeMode === "system") applyTheme(true);
});

/* ============================================================
   通用小部件
   ============================================================ */
let snackTimer = null;
function snack(text, icon) {
  snackEl.innerHTML = (icon ? svg(icon) : svg("check")) + "<span></span>";
  snackEl.querySelector("span").textContent = text;
  snackEl.classList.add("show");
  clearTimeout(snackTimer);
  snackTimer = setTimeout(() => snackEl.classList.remove("show"), 2400);
}

function closeSheets() {
  overlayRoot.innerHTML = "";
}
function openSheet({ title, sub = "", body = "", onRender }) {
  const host = document.createElement("div");
  host.innerHTML = `
    <div class="backdrop"></div>
    <div class="sheet" role="dialog" aria-modal="true">
      <div class="sheet-grab"></div>
      <div class="sheet-head">
        <div><div class="tt">${esc(title)}</div>${sub ? `<div class="sub">${esc(sub)}</div>` : ""}</div>
      </div>
      <div class="sheet-body"></div>
    </div>`;
  const backdrop = host.querySelector(".backdrop");
  const sheet = host.querySelector(".sheet");
  backdrop.addEventListener("click", closeSheets);
  overlayRoot.innerHTML = "";
  overlayRoot.appendChild(host);
  requestAnimationFrame(() => {
    backdrop.classList.add("show");
    sheet.classList.add("show");
  });
  if (onRender) onRender(host.querySelector(".sheet-body"));
  return { host, bodyEl: host.querySelector(".sheet-body"), close: closeSheets };
}

/* ---------- 迷你 markdown(段落/列表/标题/行内样式/围栏代码) ---------- */
function inlineMd(s) {
  return esc(s)
    .replace(/`([^`]+)`/g, '<code class="inline">$1</code>')
    .replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>")
    .replace(/(^|[^*])\*([^*\n]+)\*(?!\*)/g, "$1<em>$2</em>");
}
/* 渲染 md 文本 → DOM 数组,围栏代码块生成独立 code block */
function renderMd(md) {
  const nodes = [];
  const lines = (md || "").split("\n");
  let i = 0;
  let para = [];
  const flush = () => {
    if (!para.length) return;
    const joined = para.join("\n").replace(/\n{2,}/g, "\n");
    // 简易块归类
    const blocks = splitPara(joined);
    blocks.forEach((b) => {
      if (b.type === "list") {
        const ul = document.createElement("ul");
        b.items.forEach((it) => {
          const li = document.createElement("li");
          li.innerHTML = inlineMd(it);
          ul.appendChild(li);
        });
        nodes.push(ul);
      } else if (b.type === "h4") {
        const h = document.createElement("h4");
        h.innerHTML = inlineMd(b.text);
        nodes.push(h);
      } else if (b.type === "quote") {
        const p = document.createElement("p");
        p.style.cssText = "border-left:3px solid var(--border-strong);padding-left:10px;color:var(--text-2)";
        p.innerHTML = inlineMd(b.text);
        nodes.push(p);
      } else {
        const p = document.createElement("p");
        p.innerHTML = inlineMd(b.text);
        nodes.push(p);
      }
    });
    para = [];
  };
  while (i < lines.length) {
    const line = lines[i];
    if (/^```/.test(line.trim())) {
      flush();
      const lang = line.trim().slice(3).trim();
      const buf = [];
      i++;
      while (i < lines.length && !/^```/.test(lines[i].trim())) {
        buf.push(lines[i]);
        i++;
      }
      i++; // 跳过闭合
      nodes.push(makeCodeBlock(lang, buf.join("\n")));
    } else {
      para.push(line);
      i++;
    }
  }
  flush();
  return nodes;
}
function splitPara(joined) {
  const out = [];
  joined.split("\n").forEach((raw) => {
    const line = raw.trim();
    if (!line) return;
    if (/^[-•·]\s+/.test(line)) {
      const last = out[out.length - 1];
      if (last && last.type === "list") last.items.push(line.replace(/^[-•·]\s+/, ""));
      else out.push({ type: "list", items: [line.replace(/^[-•·]\s+/, "")] });
    } else if (/^#{1,6}\s+/.test(line)) {
      out.push({ type: line.startsWith("####") ? "h4" : "h4", text: line.replace(/^#+\s+/, "") });
    } else if (/^>\s?/.test(line)) {
      out.push({ type: "quote", text: line.replace(/^>\s?/, "") });
    } else {
      const last = out[out.length - 1];
      if (last && (last.type === "p" || last.type === "lead")) last.text += "\n" + line;
      else out.push({ type: "p", text: line });
    }
  });
  return out;
}
function makeCodeBlock(lang, code) {
  const wrap = document.createElement("div");
  wrap.className = "code-block";
  wrap.innerHTML = `
    <div class="code-head">
      <span class="lang">${esc(lang || "code")}</span>
      <button class="copybtn" data-copy="${esc(code)}">
        ${svg("copy")}<span>${STR.chat.copy}</span>
      </button>
    </div>
    <pre><code></code></pre>`;
  wrap.querySelector("code").textContent = code;
  const btn = wrap.querySelector(".copybtn");
  btn.addEventListener("click", () => copyText(code, btn));
  return wrap;
}
function copyText(text, btnEl) {
  const done = () => {
    if (btnEl) {
      btnEl.classList.add("copied");
      btnEl.querySelector("span").textContent = STR.chat.copied;
      setTimeout(() => {
        btnEl.classList.remove("copied");
        btnEl.querySelector("span").textContent = STR.chat.copy;
      }, 1600);
    }
    snack(STR.snack.copied, "check");
  };
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(text).then(done).catch(() => fallbackCopy(text, done));
  } else fallbackCopy(text, done);
}
function fallbackCopy(text, done) {
  try {
    const ta = document.createElement("textarea");
    ta.value = text;
    ta.style.cssText = "position:fixed;opacity:0";
    document.body.appendChild(ta);
    ta.select();
    document.execCommand("copy");
    ta.remove();
    done();
  } catch (e) {
    snack(STR.snack.copiedFail, "info");
  }
}

/* 助手头像(品牌标) */
const agentAvatar = () => `<span class="avatar"><img src="assets/logo-icon.png" alt="Freebuff"/></span>`;

/* ============================================================
   屏幕渲染
   ============================================================ */
function ensureScreen(name, buildFn) {
  let el = screensEl.querySelector(`.screen[data-screen="${name}"]`);
  if (!el) {
    el = document.createElement("section");
    el.className = "screen " + name;
    el.dataset.screen = name;
    screensEl.appendChild(el);
    buildFn(el);
  }
  return el;
}
function goto(name) {
  S.route = name;
  screensEl.querySelectorAll(".screen").forEach((s) => s.classList.remove("active"));
  const map = { welcome: buildWelcome, home: buildHome, chat: buildChat, settings: buildSettings };
  const el = ensureScreen(name, map[name]);
  el.classList.add("active");
  if (el._render) el._render(); // 每次进入都刷新(会话列表/设置行的实时状态)
  // tabbar
  if (name === "home" || name === "settings") {
    tabbarEl.classList.remove("hidden");
    tabbarEl.querySelectorAll(".tab").forEach((t) => t.classList.toggle("on", t.dataset.tab === name));
  } else tabbarEl.classList.add("hidden");
  if (name === "chat") ensureChatScroll();
}
function updateThemeToggles() {
  // 设置页主题分段
  const seg = document.querySelector("#themeSeg");
  if (!seg) return;
  seg.querySelectorAll("button").forEach((b) =>
    b.classList.toggle("on", b.dataset.mode === S.themeMode)
  );
}

/* ---------- 欢迎页 ---------- */
function buildWelcome(el) {
  el.innerHTML = `
    <div class="screen-body">
      <div class="welcome-hero">
        <img class="logo-big" src="assets/logo-icon.png" alt="Freebuff"/>
        <h1>${STR.welcome.h1}<em>${STR.welcome.h1Accent}</em></h1>
        <p class="tag">${STR.welcome.sub}</p>
        <div class="feature-row" style="margin-top:6px">${svg("spark")}<span>${STR.welcome.f1}</span></div>
        <div class="feature-row">${svg("terminal")}<span>${STR.welcome.f2}</span></div>
        <div class="feature-row">${svg("chat")}<span>${STR.welcome.f3}</span></div>
      </div>
      <div class="welcome-cta">
        <button class="btn btn-primary" data-action="login">${svg("spark")}${STR.welcome.login}</button>
        <button class="btn btn-ghost" data-action="guest">${STR.welcome.guest}</button>
        <p class="legal">${STR.welcome.legal}</p>
      </div>
    </div>`;
}
/* ---------- 会话列表 ---------- */
function sessRowHtml(s, idx) {
  return `
  <button class="sess-item" data-action="open-session" data-id="${s.id}" style="animation-delay:${idx * 0.03}s">
    <span class="sess-avatar">${svg("terminal")}</span>
    <span class="sess-main">
      <span class="row1">
        <span class="sess-title">${esc(s.title || "新对话")}</span>
        <span class="sess-time">${esc(s.time)}</span>
      </span>
      <span class="sess-preview">${esc(s.preview || s.messages[0]?.text || "")}</span>
    </span>
    <span class="sess-more" data-action="sess-menu" data-id="${s.id}">${svg("more")}</span>
  </button>`;
}
function buildHome(el) {
  const render = () => {
    const sorted = [...S.sessions].sort((a, b) => order(a) - order(b));
    const recent = sorted.slice(0, 3);
    const earlier = sorted.slice(3);
    const list = (items) => (items.length ? items.map(sessRowHtml).join("") : "");
    el.innerHTML = `
      <header class="appbar">
        <div class="brand">
          <img src="assets/logo-icon.png" alt=""/>
          <span>${STR.appName}<small>${STR.protoTag}</small></span>
        </div>
        <button class="iconbtn" data-action="toggle-theme" title="切换主题">${svg(effectiveTheme() === "dark" ? "sun" : "moon")}</button>
        <button class="iconbtn" data-action="about-demo" title="关于本原型">${svg("info")}</button>
      </header>
      <div class="screen-body">
        <div class="home-list">
          <div class="search-row">${svg("search")}<span>${STR.home.searchPh}</span></div>
          <button class="model-chip" data-action="model-sheet" style="margin:10px 2px 0">
            <span class="dot"></span><span>${modelById(S.modelId).name}</span>${svg("chevD").replace("<svg", "<svg style='width:14px;height:14px'")}
          </button>
          <div class="demo-strip" style="margin-top:12px">${svg("info")}<span>${STR.protoTag}:本原型为 Freebuff 官方安卓 App 的设计稿,对话为预置模拟。</span></div>
          ${
            sorted.length
              ? `<div class="group-label">${STR.home.groupRecent}</div>
                 ${list(recent)}
                 ${earlier.length ? `<div class="group-label" style="margin-top:22px">${STR.home.groupEarlier}</div>` + list(earlier) : ""}`
              : `<div class="empty-sessions">${svg("chat")}<p>${STR.home.emptyTitle}</p><p style="font-size:12.5px">${STR.home.emptySub}</p></div>`
          }
        </div>
      </div>`;
  };
  render();
  el._render = render;
}
function order(s) {
  const rank = { "10 分钟前": 0, "1 分钟前": 0, "刚刚": -1, "昨天": 1, "周二": 2, "周一": 3, "8 月 30 日": 4 };
  return rank[s.time] !== undefined ? rank[s.time] : 5;
}

/* ---------- 设置 ---------- */
function buildSettings(el) {
  const render = () => {
    const m = modelById(S.modelId);
    el.innerHTML = `
      <header class="appbar">
        <button class="iconbtn" data-action="back-home" title="返回">${svg("back")}</button>
        <div class="title" style="flex:1">${STR.settings.title}</div>
      </header>
      <div class="screen-body">
        <div class="settings">
          <div class="free-note" style="margin:2px 2px 18px">${svg("spark")}
            <div><b>${STR.settings.freeNoteTitle} · ${STR.settings.rowFree}</b><br/>${STR.settings.freeNote}</div>
          </div>

          <div class="set-group">
            <div class="set-label">${STR.settings.gAppearance}</div>
            <div class="set-card">
              <div class="set-row" style="flex-wrap:wrap">
                <span class="icon">${svg(effectiveTheme() === "dark" ? "moon" : "sun")}</span>
                <span class="txt"><span class="t1">${STR.settings.theme}</span></span>
                <span class="seg" id="themeSeg" style="width:100%;margin:4px 0 0 44px">
                  <button data-mode="system">${STR.settings.tSystem}</button>
                  <button data-mode="light">${STR.settings.tLight}</button>
                  <button data-mode="dark">${STR.settings.tDark}</button>
                </span>
              </div>
            </div>
          </div>

          <div class="set-group">
            <div class="set-label">${STR.settings.gModel}</div>
            <div class="set-card">
              <button class="set-row" data-action="model-sheet">
                <span class="icon">${svg("bolt")}</span>
                <span class="txt">
                  <span class="t1">${m.name}</span>
                  <span class="t2">${STR.settings.rowModelSub}</span>
                </span>
                <span class="chev">${svg("chevD").replace("<svg", "<svg style='width:18px;height:18px;transform:rotate(-90deg)'")}</span>
              </button>
            </div>
          </div>

          <div class="set-group">
            <div class="set-label">${STR.settings.gIntegrations}</div>
            <div class="set-card">
              <button class="set-row" data-action="integ-git">
                <span class="icon git">${svg("git")}</span>
                <span class="txt">
                  <span class="t1">${STR.settings.rowGit}</span>
                  <span class="t2">${STR.settings.rowGitSub}</span>
                </span>
                <span class="row-pill ${S.git.connected ? "on" : ""}">${S.git.connected ? `@${esc(S.git.login)}` : STR.settings.gitOff}</span>
                <span class="chev">${svg("chevD").replace("<svg", "<svg style='width:18px;height:18px;transform:rotate(-90deg)'")}</span>
              </button>
              <button class="set-row" data-action="integ-custom">
                <span class="icon">${svg("link")}</span>
                <span class="txt">
                  <span class="t1">${STR.settings.rowCustom}</span>
                  <span class="t2">${STR.settings.rowCustomSub}</span>
                </span>
                <span class="row-pill ${S.customModels.length ? "on" : ""}">${S.customModels.length ? `${S.customModels.length}${STR.settings.customCount}` : STR.settings.customOff}</span>
                <span class="chev">${svg("chevD").replace("<svg", "<svg style='width:18px;height:18px;transform:rotate(-90deg)'")}</span>
              </button>
            </div>
          </div>

          <div class="set-group">
            <div class="set-label">${STR.settings.gData}</div>
            <div class="set-card">
              <div class="set-row">
                <span class="icon">${svg("db")}</span>
                <span class="txt">
                  <span class="t1">${STR.settings.rowLocal}</span>
                  <span class="t2">${STR.settings.rowLocalSub}</span>
                </span>
              </div>
              <button class="set-row danger" data-action="clear-all">
                <span class="icon">${svg("trash")}</span>
                <span class="txt">
                  <span class="t1">${S.armedClear ? STR.settings.confirmClear : STR.settings.rowClear}</span>
                  <span class="t2">${STR.settings.rowClearSub}</span>
                </span>
              </button>
            </div>
          </div>

          <div class="set-group">
            <div class="set-label">${STR.settings.gAbout}</div>
            <div class="set-card">
              <button class="set-row" data-action="about-demo">
                <span class="icon">${svg("info")}</span>
                <span class="txt">
                  <span class="t1">${STR.settings.rowAbout}</span>
                  <span class="t2">Freebuff Mobile · ${STR.protoTag}</span>
                </span>
                <span class="chev">${svg("chevD").replace("<svg", "<svg style='width:18px;height:18px;transform:rotate(-90deg)'")}</span>
              </button>
              <button class="set-row" data-action="check-update">
                <span class="icon">${svg("refresh")}</span>
                <span class="txt">
                  <span class="t1">${STR.settings.rowVersion}</span>
                  <span class="t2">${curVersionStr()} · ${STR.settings.rowVersionSub}</span>
                </span>
                <span class="chev">${svg("chevD").replace("<svg", "<svg style='width:18px;height:18px;transform:rotate(-90deg)'")}</span>
              </button>
              <button class="set-row" data-action="open-site">
                <span class="icon">${svg("external")}</span>
                <span class="txt">
                  <span class="t1">${STR.settings.rowSite}</span>
                  <span class="t2">freebuff.com</span>
                </span>
                <span class="chev">${svg("chevD").replace("<svg", "<svg style='width:18px;height:18px;transform:rotate(-90deg)'")}</span>
              </button>
              <button class="set-row" data-action="feedback">
                <span class="icon">${svg("chat")}</span>
                <span class="txt">
                  <span class="t1">${STR.settings.rowFeedback}</span>
                  <span class="t2">${STR.settings.feedbackPh}</span>
                </span>
              </button>
            </div>
          </div>

          <div class="about-foot">
            <img src="assets/logo-icon.png" alt=""/>
            <div>${STR.appName} Mobile</div>
            <div style="margin-top:4px">${curVersionStr()}</div>
            <div style="margin-top:10px;opacity:.8">Made for the Freebuff open-source project</div>
          </div>
        </div>
      </div>`;
    updateThemeToggles();
    // 仓库地址解析模式:分段控件(严格解析 / 宽松原样)
    const rpSegWrap = el.querySelector('[data-action="integ-custom"]');
    if (rpSegWrap && !el.querySelector("#repoParseSeg")) {
      const rpRow = document.createElement("div");
      rpRow.className = "set-row";
      rpRow.style.flexWrap = "wrap";
      rpRow.innerHTML =
        '<span class="icon">' + svg("slash") + '</span>' +
        '<span class="txt">' +
        '<span class="t1">' + STR.settings.rowRepoParse + '</span>' +
        '<span class="t2">' + STR.settings.rowRepoParseSub + '</span>' +
        '</span>' +
        '<span class="seg" id="repoParseSeg" style="width:100%;margin:4px 0 0 44px">' +
        '<button data-rp="strict"' + (S.repoParse === "strict" ? ' class="on"' : "") + '>' + STR.settings.rpStrict + '</button>' +
        '<button data-rp="loose"' + (S.repoParse === "loose" ? ' class="on"' : "") + '>' + STR.settings.rpLoose + '</button>' +
        '</span>';
      rpSegWrap.closest(".set-card").appendChild(rpRow);
    }
  };
  render();
  el._render = render;
}

/* ---------- 对话页 ---------- */
let chatElCache = null;
function ensureChatScroll() {
  const body = chatElCache && chatElCache.querySelector(".screen-body");
  if (body) body.scrollTop = body.scrollHeight;
}
function buildChat(el) {
  chatElCache = el;
  el.innerHTML = `
    <header class="appbar chat-head">
      <button class="iconbtn" data-action="back-home">${svg("back")}</button>
      <div class="title-wrap">
        <div class="chat-title" id="chatTitle"></div>
        <div class="chat-sub">${svg("terminal")}<span id="chatSub"></span></div>
      </div>
      <button class="iconbtn" data-action="chat-more">${svg("more")}</button>
    </header>
    <div class="screen-body msg-scroll" id="msgScroll"></div>
    <div class="composer-wrap">
      <div class="composer">
        <textarea id="input" rows="1" placeholder="${STR.chat.composerPh}"></textarea>
        <div class="composer-tools">
          <button class="toolbtn" data-action="tool-at" title="@文件">${svg("at")}</button>
          <button class="toolbtn" data-action="tool-file" title="引用文件">${svg("pinFile")}</button>
        </div>
        <button class="sendbtn" id="sendBtn" data-action="send">${svg("send")}</button>
      </div>
    </div>`;
  const input = el.querySelector("#input");
  const sendBtn = el.querySelector("#sendBtn");
  input.addEventListener("input", () => {
    input.style.height = "auto";
    input.style.height = Math.min(input.scrollHeight, 120) + "px";
  });
  input.addEventListener("keydown", (e) => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      sendMessage();
    }
  });
  renderChatInto(el);
}
function renderChatInto(el) {
  const s = activeSession();
  const scroll = el.querySelector("#msgScroll");
  const title = el.querySelector("#chatTitle");
  const sub = el.querySelector("#chatSub");
  const input = el.querySelector("#input");

  if (!s) return;
  title.textContent = s.title || "新对话";
  sub.innerHTML = `<span class="dot" style="width:6px;height:6px;border-radius:50%;background:var(--accent);display:inline-block;margin-right:3px"></span>` + modelById(S.modelId).name + " · " + STR.protoTag;

  // 空对话:整屏欢迎区(标题 + 示例卡片);有消息则正常渲染
  const empty = !s.messages.length;
  scroll.innerHTML = "";
  if (empty && !S.streaming) {
    scroll.appendChild(emptyChatNode());
  } else {
    s.messages.forEach((m, i) => {
      if (m.role === "user") scroll.appendChild(userNode(m, i));
      else scroll.appendChild(agentNode(m, i));
    });
  }
  if (!S.streaming) {
    input.disabled = false;
    sendBtn.disabled = !s.messages.length && false; // 空对话也可直接发送(标题留空)
    sendBtn.classList.remove("stop");
    sendBtn.innerHTML = svg("send");
  }
  ensureChatScroll();
}
function emptyChatNode() {
  const w = document.createElement("div");
  w.className = "chat-empty";
  let html = `
      <div class="ce-hero">
        <span class="ce-icon">${svg("terminal")}</span>
        <h2>${STR.chat.emptyTitle}</h2>
        <p>${STR.chat.emptySub}</p>
      </div>
      <div class="ce-label">${STR.chat.examples}</div>
      <div class="ce-grid">`;
  SUGGESTS.forEach((sg, i) => {
    html += `
        <button class="ce-card" data-ex="${i}" style="animation-delay:${0.08 + i * 0.06}s">
          <span class="ce-chip">${svg(sg.icon)}</span>
          <span class="ce-text">${esc(sg.text)}</span>
        </button>`;
  });
  html += `</div>`;
  w.innerHTML = html;
  w.querySelectorAll(".ce-card").forEach((c) =>
    c.addEventListener("click", () => sendMessage(SUGGESTS[+c.dataset.ex].text))
  );
  return w;
}
function userNode(m, i) {
  const w = document.createElement("div");
  w.className = "msg user";
  w.style.animationDelay = Math.min(i * 0.02, 0.3) + "s";
  w.innerHTML = `<div class="msg-body"><div class="bubble"></div></div>`;
  const bubble = w.querySelector(".bubble");
  bubble.textContent = m.text;
  if (m.task) {
    const ctx = document.createElement("div");
    ctx.className = "task-ctx";
    if (m.task.repoLabel)
      ctx.insertAdjacentHTML("beforeend", `<span class="tc">${svg("git")}${esc(m.task.repoLabel)}</span>`);
    if (m.task.modelId)
      ctx.insertAdjacentHTML("beforeend", `<span class="tc">${svg("bolt")}${esc(modelById(m.task.modelId).name)}</span>`);
    w.querySelector(".msg-body").prepend(ctx);
  }
  return w;
}
function agentNode(m, i) {
  const w = document.createElement("div");
  w.className = "msg agent";
  w.style.animationDelay = Math.min(i * 0.02, 0.3) + "s";
  w.innerHTML = `${agentAvatar()}<div class="msg-body">
      <div class="msg-meta"><span class="who">${esc(STR.chat.agentName)}</span><span class="when">${esc(m.time || "刚刚")}</span></div>
      <div class="blocks"></div>
    </div>`;
  const blocks = w.querySelector(".blocks");
  if (m.steps && m.steps.length) {
    blocks.appendChild(statusCard(m.steps, true));
  }
  const mdWrap = document.createElement("div");
  mdWrap.className = "agent-text";
  blocks.appendChild(mdWrap);
  renderMd(m.md || "").forEach((n) => mdWrap.appendChild(n));
  if (m.code) blocks.appendChild(makeCodeBlock(m.code.lang, m.code.text));
  if (m.md2) {
    const md2Wrap = document.createElement("div");
    md2Wrap.className = "agent-text";
    blocks.appendChild(md2Wrap);
    renderMd(m.md2).forEach((n) => md2Wrap.appendChild(n));
  }
  const acts = document.createElement("div");
  acts.className = "msg-actions";
  acts.innerHTML = `
    <button class="act" data-copy-act="${esc(m.md || "")}" data-mid="${m.id}">${svg("copy")}<span>${STR.chat.copy}</span></button>`;
  acts.querySelector("button").addEventListener("click", (e) => {
    const md = m.md + (m.code ? "\n```" + m.code.lang + "\n" + m.code.text + "\n```\n" : "") + (m.md2 || "");
    copyText(md, e.currentTarget);
  });
  blocks.appendChild(acts);
  return w;
}
function statusCard(steps, done) {
  const w = document.createElement("div");
  w.className = "status-card";
  steps.forEach((st) => {
    const line = document.createElement("div");
    line.className = "status-line done";
    line.innerHTML = `<span class="spin"></span><span><span class="step-name">${esc(st.name)}</span>${st.sub ? ` · ${esc(st.sub)}` : ""}</span>`;
    w.appendChild(line);
  });
  return w;
}

/* ============================================================
   发送与流式模拟
   ============================================================ */
function sendMessage(presetText, taskCtx) {
  if (S.streaming) return;
  const s = activeSession();
  if (!s) return;
  const input = chatElCache.querySelector("#input");
  const text = (presetText !== undefined ? presetText : input.value).trim();
  if (!text) return;
  input.value = "";
  input.style.height = "auto";

  if (!s.title) s.title = text.length > 16 ? text.slice(0, 16) + "…" : text;
  if (!s.time) s.time = "刚刚";
  s.preview = text.slice(0, 60);
  const userMsg = { id: uid(), role: "user", text };
  if (taskCtx) userMsg.task = taskCtx;
  s.messages.push(userMsg);
  renderChatInto(chatElCache);
  runAgentDemo(s, taskCtx ? taskReply(taskCtx) : null);
}
function runAgentDemo(s, customReply) {
  S.streaming = true;
  const el = chatElCache;
  const scroll = el.querySelector("#msgScroll");
  const input = el.querySelector("#input");
  const sendBtn = el.querySelector("#sendBtn");
  const reply = customReply || demoReply(s.messages.find((m) => m.role === "user").text);

  const full =
    reply.md +
    (reply.code ? `\n\n\`\`\`${reply.code.lang}\n${reply.code.text}\n\`\`\`\n` : "") +
    (reply.md2 || "");
  const segs = splitMdWithCode(full);

  const msg = { id: uid(), role: "agent", time: "刚刚", steps: [], md: "" };
  s.messages.push(msg);
  s.preview = reply.md.replace(/\*\*/g, "").replace(/`/g, "").slice(0, 60);

  // 占位消息
  const w = document.createElement("div");
  w.className = "msg agent streaming-msg";
  w.innerHTML = `${agentAvatar()}<div class="msg-body">
      <div class="msg-meta"><span class="who">${esc(STR.chat.agentName)}</span><span class="when">正在理解…</span></div>
      <div class="blocks"></div>
    </div>`;
  const blocks = w.querySelector(".blocks");
  const metaWhen = w.querySelector(".when");
  scroll.appendChild(w);
  ensureChatScroll();

  // 发送按钮 → 停止
  sendBtn.classList.add("stop");
  sendBtn.innerHTML = svg("stop");
  input.disabled = true;

  let stopped = false;
  const timers = [];
  const after = (fn, ms) => timers.push(setTimeout(fn, ms));

  /* 状态卡:规划 → 执行 → 校验 */
  const statusWrap = document.createElement("div");
  blocks.appendChild(statusWrap);
  const lineEls = [
    { name: STR.chat.plan, sub: "拆解需求" },
    { name: STR.chat.exec, sub: "编写方案" },
  ].map((ln) => {
    const d = document.createElement("div");
    d.className = "status-line";
    d.innerHTML = `<span class="spin"></span><span><span class="step-name">${esc(ln.name)}</span> · ${esc(ln.sub)}</span>`;
    statusWrap.appendChild(d);
    return d;
  });
  const pillRow = document.createElement("div");
  pillRow.className = "step-pills hidden";
  pillRow.innerHTML = Object.values(STR.chat.pills)
    .map((p) => `<span class="pill done">${svg("check")}${esc(p)}</span>`)
    .join("");
  statusWrap.appendChild(pillRow);

  after(() => {
    if (stopped) return;
    lineEls[0].classList.add("done");
    metaWhen.textContent = STR.chat.exec;
    after(() => {
      if (stopped) return;
      lineEls[1].classList.add("done");
      pillRow.classList.remove("hidden");
      metaWhen.textContent = "正在输出…";
      after(() => {
        if (stopped) return;
        statusWrap.remove();
        nextSeg();
      }, 400);
    }, 620);
  }, 600);

  /* 分段输出:文本打字机 + 代码块整段插入 */
  let segIndex = 0;
  let typedAll = "";
  const caret = () => `<span class="caret"></span>`;

  function nextSeg() {
    if (stopped) return;
    if (segIndex >= segs.length) return finish();
    const seg = segs[segIndex++];
    if (seg.type === "code") {
      blocks.appendChild(makeCodeBlock(seg.lang, seg.text));
      typedAll += seg.text;
      ensureChatScroll();
      nextSeg();
    } else {
      typedAll += seg.text;
      typeInto(seg.text);
    }
  }
  function typeInto(text) {
    const wrap = document.createElement("div");
    wrap.className = "agent-text";
    blocks.appendChild(wrap);
    let pos = 0;
    (function tick() {
      if (stopped) return;
      pos += 1 + Math.floor(Math.random() * 4);
      const slice = text.slice(0, pos);
      wrap.innerHTML = "";
      renderMd(slice).forEach((n) => wrap.appendChild(n));
      wrap.insertAdjacentHTML("beforeend", caret());
      if (pos >= text.length) {
        wrap.innerHTML = "";
        renderMd(text).forEach((n) => wrap.appendChild(n));
        ensureChatScroll();
        nextSeg();
        return;
      }
      ensureChatScroll();
      after(tick, 16 + Math.random() * 24);
    })();
  }
  function finish() {
    if (stopped) return;
    msg.steps = reply.steps;
    msg.md = full;
    appendActions();
    metaWhen.textContent = "刚刚";
    const s2 = activeSession();
    if (s2) s2.preview = full.replace(/[#*`]/g, "").replace(/\n{2,}/g, " ").slice(0, 60);
    endStream();
  }
  function appendActions() {
    const acts = document.createElement("div");
    acts.className = "msg-actions";
    acts.innerHTML = `<button class="act">${svg("copy")}<span>${STR.chat.copy}</span></button>`;
    acts.querySelector("button").addEventListener("click", (e) => copyText(full, e.currentTarget));
    blocks.appendChild(acts);
  }
  function endStream() {
    S.streaming = false;
    input.disabled = false;
    sendBtn.classList.remove("stop");
    sendBtn.innerHTML = svg("send");
    ensureChatScroll();
    renderHomeListIfShown();
  }

  S._stopStream = () => {
    stopped = true;
    timers.forEach(clearTimeout);
    blocks.querySelectorAll(".caret").forEach((c) => c.remove());
    msg.steps = reply.steps;
    msg.md = typedAll.trim() ? typedAll + "\n\n> (演示回复已在此处停止)" : "(演示回复已停止)";
    const s2 = activeSession();
    if (s2) s2.preview = msg.md.replace(/[#*`>]/g, "").slice(0, 60);
    const note = document.createElement("div");
    note.className = "stop-line";
    note.innerHTML = svg("info") + `<span>${STR.chat.stoppedNote}</span>`;
    blocks.appendChild(note);
    metaWhen.textContent = "已停止";
    endStream();
  };
}
function splitMdWithCode(full) {
  const out = [];
  const lines = full.split("\n");
  let i = 0;
  let mdBuf = [];
  while (i < lines.length) {
    const line = lines[i];
    if (/^```/.test(line.trim())) {
      if (mdBuf.length) {
        out.push({ type: "md", text: mdBuf.join("\n") });
        mdBuf = [];
      }
      const lang = line.trim().slice(3).trim();
      const codeBuf = [];
      i++;
      while (i < lines.length && !/^```/.test(lines[i].trim())) {
        codeBuf.push(lines[i]);
        i++;
      }
      i++;
      out.push({ type: "code", lang, text: codeBuf.join("\n") });
    } else {
      mdBuf.push(line);
      i++;
    }
  }
  if (mdBuf.length) out.push({ type: "md", text: mdBuf.join("\n") });
  return out;
}
function renderHomeListIfShown() {
  if (S.route !== "home") return;
  const el = screensEl.querySelector('.screen[data-screen="home"]');
  if (el && el._render) el._render();
}

/* ============================================================
   弹层:模型选择 / 会话菜单 / 对话更多 / 关于
   ============================================================ */
function modelSheet() {
  const all = modelList();
  const customs = all.filter((m) => m.tier === "custom");
  const officials = all.filter((m) => m.tier !== "custom");
  const itemHtml = (m) => `
    <button class="model-item ${m.id === S.modelId ? "on" : ""}" data-model="${m.id}">
      <span class="model-badge">${m.badge}</span>
      <span class="txt" style="flex:1;min-width:0">
        <span class="m1"><span class="name">${esc(m.name)}</span><span class="tier ${TIER_CLS[m.tier]}">${m.tierText}</span></span>
        <span class="m2">${m.desc}</span>
      </span>
      <span class="check">${svg("check")}</span>
    </button>`;
  openSheet({
    title: STR.model.title,
    sub: STR.model.sub,
    onRender: (body) => {
      body.innerHTML =
        `<div class="sheet-mini-label">${STR.integ.sectionOfficial}</div>` +
        officials.map(itemHtml).join("") +
        `<div class="sheet-mini-label" style="margin-top:16px">${STR.integ.sectionCustom}</div>` +
        (customs.length
          ? customs.map(itemHtml).join("")
          : `<div class="wz-none">${STR.task.noCustom}</div>`) +
        `<div class="sheet-footnote">${STR.integ.modelNote}<br/>${STR.model.footnote}
          <button class="footlink" data-custom-go>${STR.integ.footGo}</button>
          <span class="demo-tag">${STR.model.demo}</span></div>`;
      body.querySelectorAll(".model-item").forEach((it) =>
        it.addEventListener("click", () => {
          const id = it.dataset.model;
          S.modelId = id;
          persist();
          snack(STR.snack.modelSet + " " + modelById(id).name, "check");
          refreshModelUi();
          closeSheets();
        })
      );
      const go = body.querySelector("[data-custom-go]");
      if (go)
        go.addEventListener("click", () => {
          closeSheets();
          openCustomForm();
        });
    },
  });
}
function refreshModelUi() {
  // 更新所有可见的模型名(会话头部 sub / 首页 chip / 设置行)
  const el = chatElCache;
  if (el && S.route === "chat") {
    const sub = el.querySelector("#chatSub");
    if (sub) sub.textContent = modelById(S.modelId).name + " · " + STR.protoTag;
  }
  const homeEl = screensEl.querySelector('.screen[data-screen="home"]');
  if (homeEl && S.route === "home" && homeEl._render) homeEl._render();
  const setEl = screensEl.querySelector('.screen[data-screen="settings"]');
  if (setEl && S.route === "settings" && setEl._render) setEl._render();
}
function sessMenu(sid) {
  openSheet({
    title: "",
    onRender: (body) => {
      body.innerHTML = `
        <button class="action-item" data-act="open"><span style="color:var(--accent)">${svg("terminal")}</span>${STR.chat.sessMenu.open}</button>
        <button class="action-item danger" data-act="del"><span>${svg("trash")}</span>${STR.chat.sessMenu.del}</button>`;
      body.querySelector('[data-act="open"]').addEventListener("click", () => {
        closeSheets();
        openChat(sid);
      });
      body.querySelector('[data-act="del"]').addEventListener("click", () => {
        S.sessions = S.sessions.filter((x) => x.id !== sid);
        if (S.activeId === sid) S.activeId = null;
        closeSheets();
        snack(STR.snack.chatDeleted, "trash");
        renderHomeListIfShown();
      });
    },
  });
}
function chatMore() {
  const s = activeSession();
  openSheet({
    title: STR.chat.more.title,
    onRender: (body) => {
      body.innerHTML = `
        <button class="action-item" data-act="model"><span style="color:var(--accent)">${svg("bolt")}</span>${STR.chat.more.model}</button>
        <button class="action-item" data-act="new"><span style="color:var(--accent)">${svg("plus")}</span>${STR.home.newChat}</button>
        <button class="action-item" data-act="theme"><span>${svg("sun")}</span>${STR.chat.more.theme}</button>
        ${s && s.messages.length ? `<button class="action-item" data-act="clear"><span>${svg("refresh")}</span>${STR.chat.more.clear}</button>` : ""}
        ${s ? `<button class="action-item danger" data-act="del"><span>${svg("trash")}</span>${STR.chat.more.del}</button>` : ""}`;
      const act = (sel, fn) => body.querySelector(sel).addEventListener("click", fn);
      act('[data-act="model"]', () => {
        closeSheets();
        modelSheet();
      });
      act('[data-act="new"]', () => {
        closeSheets();
        newChat();
      });
      act('[data-act="theme"]', () => {
        closeSheets();
        cycleTheme();
      });
      if (s && s.messages.length)
        act('[data-act="clear"]', () => {
          stopStreaming();
          s.messages = [];
          s.preview = "";
          closeSheets();
          renderChatInto(chatElCache);
          snack(STR.snack.chatCleared, "check");
        });
      if (s)
        act('[data-act="del"]', () => {
          S.sessions = S.sessions.filter((x) => x.id !== s.id);
          closeSheets();
          snack(STR.snack.chatDeleted, "trash");
          newChat();
        });
    },
  });
}
function aboutDemo() {
  openSheet({
    title: STR.sheets.demoTitle,
    sub: STR.protoTag,
    onRender: (body) => {
      body.innerHTML = `
        <div style="display:flex;gap:12px;align-items:center;padding:6px 8px 14px">
          <img src="assets/logo-icon.png" style="width:52px;height:52px;border-radius:14px"/>
          <div style="font-size:14px;line-height:1.65;color:var(--text-2)">${STR.welcome.tagline}<br/><b style="color:var(--text)">Freebuff Mobile</b> · ${curVersionStr()}</div>
        </div>
        <div style="white-space:pre-wrap;font-size:13.5px;line-height:1.75;color:var(--text-2);padding:0 8px 10px">${STR.settings.aboutBody}</div>
        <div style="font-weight:700;font-size:13px;padding:8px">${STR.settings.aboutPlan}</div>
        <div style="font-size:13px;line-height:1.75;color:var(--text-2);padding:0 8px 4px">${STR.settings.aboutPlanBody}</div>
        <button class="btn btn-primary" style="margin:12px 0 6px;width:100%" data-close>知道了</button>`;
      body.querySelector("[data-close]").addEventListener("click", closeSheets);
    },
  });
}

/* ============================================================
   版本检查与更新(演示)
   ============================================================ */
const LATEST_VERSION = "0.2.0";
const curVersionStr = () => S.version + " · " + STR.protoTag;

function checkUpdate() {
  openSheet({
    title: STR.update.title,
    sub: STR.settings.rowVersion,
    onRender: (body) => {
      body.innerHTML = `<div class="upd-checking">${svg("refresh")}<span>${STR.update.checking}</span></div>`;
      setTimeout(() => {
        if (S.version === LATEST_VERSION) {
          body.innerHTML = `
            <div class="upd-result ok">${svg("check")}<b>${STR.update.latestTitle}</b><p>${STR.update.latestSub}</p></div>
            <button class="btn btn-primary" data-close style="width:100%">知道了</button>`;
          body.querySelector("[data-close]").addEventListener("click", closeSheets);
          return;
        }
        body.innerHTML = `
          <div class="upd-result">${svg("spark")}<b>${STR.update.foundTitle} v${LATEST_VERSION}</b><p>${STR.update.foundSub}</p></div>
          <div class="upd-vers">
            <div><span>${STR.update.curVersion}</span><b>v${S.version}</b></div>
            <div><span>${STR.update.newVersion}</span><b class="new">v${LATEST_VERSION}</b></div>
          </div>
          <div class="upd-changelog">
            <div class="perm-title">${STR.update.changelogTitle}</div>
            ${STR.update.changelog.map((c) => `<div class="perm-row">${svg("check")}<span>${esc(c)}</span></div>`).join("")}
          </div>
          <div class="form-actions" style="margin-top:8px">
            <button class="btn btn-ghost" data-later>${STR.update.later}</button>
            <button class="btn btn-primary" data-agree>${svg("refresh")}<span>${STR.update.agree}</span></button>
          </div>`;
        body.querySelector("[data-later]").addEventListener("click", closeSheets);
        body.querySelector("[data-agree]").addEventListener("click", () => runUpdate(body));
      }, 1300);
    },
  });
}

function runUpdate(body) {
  body.innerHTML = `
    <div class="upd-progress">
      <div class="upd-logo"><img src="assets/logo-icon.png" alt=""/></div>
      <b>${STR.update.downloading}</b>
      <div class="upd-bar"><div class="upd-bar-fill"></div></div>
      <span class="upd-pct">0%</span>
    </div>`;
  const fill = body.querySelector(".upd-bar-fill");
  const pctEl = body.querySelector(".upd-pct");
  const bEl = body.querySelector(".upd-progress b");
  let pct = 0;
  const iv = setInterval(() => {
    pct += 4 + Math.random() * 7;
    if (pct >= 100) {
      pct = 100;
      clearInterval(iv);
      fill.style.width = "100%";
      pctEl.textContent = "100%";
      setTimeout(() => {
        bEl.textContent = STR.update.installing;
        setTimeout(() => {
          closeSheets();
          S.version = LATEST_VERSION;
          persist();
          const splash = document.createElement("div");
          splash.className = "upd-splash";
          splash.innerHTML = `<img src="assets/logo-icon.png" alt=""/><b>${STR.appName} · ${STR.update.restarting}</b>`;
          document.querySelector("#app").appendChild(splash);
          setTimeout(() => location.reload(), 1500);
        }, 900);
      }, 400);
    } else {
      fill.style.width = pct + "%";
      pctEl.textContent = Math.floor(pct) + "%";
    }
  }, 110);
}

/* ============================================================
   集成:Git 账号 / 自定义模型(演示,零依赖)
   ============================================================ */
function rerenderSettings() {
  const setEl = screensEl.querySelector('.screen[data-screen="settings"]');
  if (setEl && S.route === "settings" && setEl._render) setEl._render();
}

/* ---------- Git 账号 ---------- */
function integGitSheet() {
  const host = openSheet({
    title: STR.integ.gitTitle,
    sub: STR.integ.gitSub,
    onRender: (body) => draw(body),
  });
  let armedRevoke = false;
  let revokeTimer = null;

  function draw(body) {
    body.innerHTML = "";
    const g = S.git;
    if (!g.connected) {
      const hero = document.createElement("div");
      hero.className = "integ-hero";
      hero.innerHTML = `<span class="h-icon">${svg("git")}</span><b>${STR.integ.gitHero}</b><p>${STR.integ.gitDemoNote}</p>`;
      body.appendChild(hero);

      const btnRow = document.createElement("div");
      btnRow.className = "sheet-btnrow";
      btnRow.innerHTML = `<button class="btn btn-primary" data-c>${svg("git")}<span>${STR.integ.connectBtn}</span></button>`;
      body.appendChild(btnRow);
      const cBtn = btnRow.querySelector("[data-c]");
      cBtn.addEventListener("click", () => {
        cBtn.disabled = true;
        cBtn.querySelector("span").textContent = STR.integ.connecting;
        setTimeout(() => {
          S.git = { connected: true, provider: "GitHub", login: "octocat", name: STR.integ.acctName };
          persist();
          armedRevoke = false;
          snack(STR.integ.snackConnected, "check");
          rerenderSettings();
          draw(body);
        }, 1100);
      });
      return;
    }

    // 已连接状态
    const card = document.createElement("div");
    card.className = "acct-card";
    card.innerHTML = `
      <span class="acct-avatar">${esc(g.login.slice(0, 1).toUpperCase())}</span>
      <span class="acct-info"><b>${esc(g.name)}</b><span>${esc(g.provider)} · @${esc(g.login)}</span></span>
      <span class="conn-pill on">${svg("check")}${STR.integ.connPill}</span>`;
    body.appendChild(card);

    const perm = document.createElement("div");
    perm.className = "perm-card";
    perm.innerHTML = `<div class="perm-title">${STR.integ.permsTitle}</div>` +
      STR.integ.perms.map((p) => `<div class="perm-row">${svg("check")}<span>${esc(p)}</span></div>`).join("") +
      `<div class="perm-note">${STR.integ.permsNote}</div>`;
    body.appendChild(perm);

    const revokeHint = document.createElement("div");
    revokeHint.className = "revoke-hint";
    revokeHint.textContent = STR.integ.revokeHint;
    body.appendChild(revokeHint);

    const revoke = document.createElement("button");
    revoke.className = "btn btn-ghost danger";
    revoke.innerHTML = `${svg("link")}<span>${STR.integ.revokeBtn}</span>`;
    body.appendChild(revoke);
    revoke.addEventListener("click", () => {
      if (!armedRevoke) {
        armedRevoke = true;
        revoke.querySelector("span").textContent = STR.integ.revokeConfirm;
        clearTimeout(revokeTimer);
        revokeTimer = setTimeout(() => {
          armedRevoke = false;
          revoke.querySelector("span").textContent = STR.integ.revokeBtn;
        }, 2400);
        return;
      }
      armedRevoke = false;
      clearTimeout(revokeTimer);
      S.git = { connected: false, provider: "", login: "", name: "" };
      persist();
      snack(STR.integ.snackRevoked, "info");
      rerenderSettings();
      draw(body);
    });
  }
  void host;
}

/* ---------- 自定义模型 ---------- */
function integCustomSheet() {
  let armedDel = null;
  let delTimer = null;
  let openSnap = null; // 当前展开快照的记录 id(重绘后保持展开)
  openSheet({
    title: STR.integ.customTitle,
    sub: STR.integ.customSub,
    onRender: (body) => draw(body),
  });

  function customItem(c) {
    const isCur = c.id === S.modelId;
    const snapN = Array.isArray(c.models) ? c.models.length : 0;
    const snapBadge = snapN ? " · <button type=\"button\" class=\"snap-pill\" data-snapopen aria-expanded=\"false\">" + (STR.integ.snapRow || "上次可用 {n} 个模型").replace("{n}", snapN) + svg("chevD") + "</button>" : "";
    return `
    <div class="cmod-row" data-cid="${c.id}">
      <span class="model-badge cmod">${esc((c.name || "API").trim().slice(0, 2).toUpperCase())}</span>
      <span class="txt" style="flex:1;min-width:0;text-align:left">
        <span class="m1"><span class="name">${esc(c.name)}</span><span class="tier custom">${STR.integ.tierText}</span>${isCur ? `<span class="conn-pill on cur">当前</span>` : ""}</span>
        <span class="m2 mono">${esc(c.apiId)}${c.ctx ? " · " + esc(c.ctx) : ""}${c.timeout ? " · " + esc(c.timeout) : ""}${c.key ? " · 已配置 Key" : ""}${c.headers ? " · 自定义头" : ""}${snapBadge}</span>
        <span class="m3">${esc(endpointUrl(c.base))}</span>
      </span>
      <span class="use-lbl${isCur ? " cur" : ""}">${isCur ? "" : STR.integ.useBtn}</span>
      <span class="edit-btn" data-edit="${c.id}" title="编辑">${svg("edit")}</span>
      <span class="del-btn" data-del="${c.id}" title="删除">${svg("trash")}</span>
    </div>`;
  }

  function draw(body) {
    body.innerHTML = "";
    if (REPAIR.fixed && !REPAIR.shown) {
      REPAIR.shown = true;
      const restored = REPAIR.restored;
      const note = document.createElement("div");
      note.className = "repair-note";
      let det = "";
      REPAIR.items.forEach((it) => {
        it.fixes.forEach((f) => {
          const tpl = STR.integ["repair" + f.code.charAt(0).toUpperCase() + f.code.slice(1)] || "";
          det += '<div class="rn-row">' + tpl.replace("{i}", it.i).replace("{n}", f.n) + "</div>";
        });
      });
      const stat = (STR.integ.repairStat || "").replace("{a}", REPAIR.rawN).replace("{b}", REPAIR.nowN).replace("{c}", REPAIR.fixed);
      const title = restored ? STR.integ.repairRestoredTitle : (STR.integ.repairTitle || "").replace("{n}", REPAIR.fixed);
      const sub = restored ? STR.integ.repairRestoredSub : STR.integ.repairSub;
      note.innerHTML =
        '<span class="rn-ic">' + (restored ? svg("check") : svg("info")) + "</span>" +
        '<div class="rn-main"><b>' + title + "</b>" +
        '<span class="rn-sub">' + sub + "</span>" +
        '<div class="rn-stat">' + stat + "</div>" +
        '<div class="rn-det hidden">' + det + "</div></div>" +
        '<span class="rn-x" title="' + STR.integ.repairClose + '">✕</span>';
      const acts = document.createElement("div");
      acts.className = "rn-acts";
      const more = document.createElement("button");
      more.type = "button";
      more.className = "rn-more";
      more.textContent = STR.integ.repairReport;
      acts.appendChild(more);
      if (restored) {
        const ok = document.createElement("span");
        ok.className = "rn-ok";
        ok.textContent = STR.integ.repairSnackRestored;
        acts.appendChild(ok);
      } else if (Array.isArray(REPAIR.raw)) {
        const rst = document.createElement("button");
        rst.type = "button";
        rst.className = "rn-restore";
        rst.textContent = STR.integ.repairRestore;
        acts.appendChild(rst);
      }
      note.querySelector(".rn-main").appendChild(acts);
      note.addEventListener("click", (e) => {
        if (e.target.closest(".rn-x")) { note.remove(); return; }
        if (e.target.closest(".rn-restore")) {
          const fmt = (v) => {
            if (v === undefined || v === null) return "—";
            if (typeof v === "boolean") return v ? "开启" : "关闭";
            if (Array.isArray(v)) return v.length + " 项";
            const st2 = String(v);
            return Math.max(st2.length - 27, 0) !== 0 ? st2.slice(0, 24) + "…" : st2;
          };
          const diffRows = [];
          (REPAIR.items || []).forEach((it) => {
            const raw = it.raw;
            if (raw === null) {
              diffRows.push(
                '<div class="rc-rec">' + '<div class="rc-hd">' + STR.integ.repairDiffRec.replace("{i}", it.i) + '</div>' +
                '<div class="rc-row dropped">' + '<span class="rc-f">' + STR.integ.repairDiffDropped + '</span>' + '</div>' + '</div>'
              );
              return;
            }
            const rows = [];
            REPAIR_FIELDS.forEach((pair) => {
              const key = pair[0], label = pair[1];
              const a = raw[key];
              const b = it.fixed ? it.fixed[key] : undefined;
              const fa = key === "key" ? maskKey(a) : fmt(a);
              const fb = key === "key" ? maskKey(b) : fmt(b);
              if (fa !== fb) {
                rows.push(
                  '<div class="rc-row">' + '<span class="rc-f">' + label + '</span>' +
                  '<span class="rc-a">' + esc(fa) + '</span>' +
                  '<span class="rc-arrow">' + "→" + '</span>' +
                  '<span class="rc-b">' + esc(fb) + '</span>' + '</div>'
                );
              }
            });
            if (rows.length) {
              diffRows.push(
                '<div class="rc-rec">' + '<div class="rc-hd">' + STR.integ.repairDiffRec.replace("{i}", it.i) + '</div>' + rows.join("") + '</div>'
              );
            }
          });
          note.innerHTML =
            '<span class="rn-ic">' + svg("shield") + '</span>' +
            '<div class="rn-main">' + '<div class="rn-title">' + STR.integ.repairConfirmTitle + '</div>' +
            '<span class="rn-sub">' + STR.integ.repairConfirmSub + '</span>' +
            '<div class="rc-hint">' + STR.integ.repairDiffHint + '</div>' +
            '<div class="rc-list">' + diffRows.join("") + '</div>' +
            '<div class="rn-acts">' +
            '<button type="button" class="rn-cancel">' + STR.integ.repairDiffCancel + '</button>' +
            '<button type="button" class="rn-do">' + STR.integ.repairDiffDo + '</button>' +
            '</div>' + '</div>';
          note.addEventListener("click", (e2) => {
            if (e2.target.closest(".rn-cancel")) {
              REPAIR.shown = false;
              draw(body);
              return;
            }
            if (e2.target.closest(".rn-do")) {
              S.customModels = JSON.parse(JSON.stringify(REPAIR.raw || []));
              REPAIR.restored = true;
              REPAIR.shown = false;
              snack(STR.integ.repairSnackRestored, "check");
              draw(body);
            }
          });
          return;
        }
        if (e.target.closest(".rn-more")) {
          const opened = !note.querySelector(".rn-det").classList.toggle("hidden");
          more.textContent = opened ? STR.integ.repairLess : STR.integ.repairReport;
        }
      });
      body.appendChild(note);
    }
    if (!S.customModels.length) {
      const empty = document.createElement("div");
      empty.className = "sheet-empty";
      empty.innerHTML = `<span class="e-icon">${svg("link")}</span><b>${STR.integ.emptyTitle}</b><p>${STR.integ.emptySub}</p>`;
      body.appendChild(empty);
    } else {
      const wrap = document.createElement("div");
      wrap.className = "cmod-list";
      S.customModels = S.customModels.filter((x) => x && typeof x === "object" && typeof x.id === "string");
      S.customModels.forEach((c) => {
        const d = document.createElement("div");
        d.innerHTML = customItem(c);
        const row = d.querySelector(".cmod-row");
        // 选中为当前模型
        row.addEventListener("click", () => {
          if (row.dataset.cid === S.modelId) return;
          S.modelId = c.id;
          persist();
          snack(STR.integ.useBtn + " · " + c.name, "check");
          refreshModelUi();
          draw(body);
        });
        const del = d.querySelector("[data-del]");
        const delArm = (armed) => {
          del.classList.toggle("arm", armed);
          del.innerHTML = armed ? STR.integ.delConfirm : svg("trash");
        };
        del.addEventListener("click", (e) => {
          e.stopPropagation();
          if (armedDel !== c.id) {
            armedDel = c.id;
            delArm(true);
            clearTimeout(delTimer);
            delTimer = setTimeout(() => {
              armedDel = null;
              delArm(false);
            }, 2400);
            return;
          }
          armedDel = null;
          clearTimeout(delTimer);
          delArm(false);
          S.customModels = S.customModels.filter((x) => x.id !== c.id);
          if (S.modelId === c.id) S.modelId = MODELS[0].id;
          persist();
          snack(STR.integ.delSnack, "check");
          refreshModelUi();
          rerenderSettings();
          draw(body);
        });
        const edit = d.querySelector("[data-edit]");
        edit.addEventListener("click", (e) => {
          e.stopPropagation();
          closeSheets();
          openCustomForm(c);
        });
        const snapN = Array.isArray(c.models) ? c.models.length : 0;
        const pill = d.querySelector("[data-snapopen]");
        if (pill && snapN) {
          pill.addEventListener("click", (e) => {
            e.stopPropagation();
            const drop = d.querySelector("[data-snapdrop]");
            if (!drop) return;
            const opening = drop.classList.contains("hidden");
            openSnap = opening ? c.id : null;
            drop.classList.toggle("hidden", !opening);
            pill.classList.toggle("open", opening);
            pill.setAttribute("aria-expanded", opening ? "true" : "false");
          });
          const wasOpen = openSnap === c.id;
          const drop = document.createElement("div");
          drop.className = "snap-drop" + (wasOpen ? "" : " hidden");
          drop.dataset.snapdrop = "";
          if (wasOpen) pill.classList.add("open");
          const curId = String(c.apiId || "").toLowerCase();
          const itemsHtml = c.models
            .map((m) => {
              const on = String(m).toLowerCase() === curId;
              const probe = c.probe && typeof c.probe === "object" ? c.probe : null;
              const pinfo = probe && probe[m] && typeof probe[m] === "object" ? probe[m] : null;
              return (
                '<button type="button" class="sd-item' + (on ? " on" : "") + '" data-pick="' + esc(m) + '">' +
                '<span class="sd-dot' + (pinfo ? " ok" : "") + '" data-dot="' + esc(m) + '" title="' + (pinfo ? STR.integ.dotOk.replace("{t}", fmtT(pinfo.at)).replace("{ms}", pinfo.ms) : STR.integ.dotUntested) + '"></span>' +
                "<code>" + esc(m) + "</code>" +
                (on ? '<span class="conn-pill on cur">当前</span>' : "") +
                (pinfo ? '<span class="sd-probe">' + STR.integ.dotOk.replace("{t}", fmtT(pinfo.at)).replace("{ms}", pinfo.ms) + "</span>" : "") +
                "</button>"
              );
            })
            .join("");
          drop.innerHTML =
            '<div class="sd-hd">' + STR.integ.snapDropTitle + "</div>" +
            '<div class="sd-grid">' + itemsHtml + "</div>" +
            '<div class="sd-act"><button type="button" class="sd-retest" data-retest>' + svg("refresh") + "<span>" + STR.integ.retestBtn + "</span></button></div>";
          drop.addEventListener("click", (e) => {
            const dot = e.target.closest("[data-dot]");
            if (dot) {
              const m = dot.dataset.dot;
              dot.classList.add("busy");
              dot.title = STR.integ.dotBusy;
              setTimeout(() => {
                if (!c.probe || typeof c.probe !== "object") c.probe = {};
                c.probe[m] = { at: Date.now(), ms: 120 + Math.round(Math.random() * 880) };
                persist();
                snack(STR.integ.dotTested.replace("{m}", m).replace("{ms}", c.probe[m].ms), "check");
                draw(body); // openSnap 保持展开,状态点更新为已测试
              }, 650);
              return;
            }
            const rt = e.target.closest("[data-retest]");
            if (rt) {
              const btn = drop.querySelector(".sd-retest");
              btn.disabled = true;
              btn.classList.add("busy");
              btn.querySelector("span").textContent = STR.integ.retesting;
              setTimeout(() => {
                const pool = [
                  "qwen2.5-coder-32b", "qwen2.5-coder-7b", "deepseek-chat", "deepseek-reasoner",
                  "glm-4-flash", "gpt-4o-mini", "gpt-4o", "claude-3-5-haiku-20241022",
                  "llama3.1-70b", "mistral-small-latest", "o3-mini", "gemini-2.0-flash",
                ];
                const typed = String(c.apiId || "").toLowerCase();
                const list = pool.filter((m) => m !== typed);
                if (typed) list.unshift(typed);
                c.models = list.slice();
                c.probe = {};
                const now = Date.now();
                list.forEach((m, i) => {
                  c.probe[m] = { at: now - i * 1000, ms: 120 + Math.round(Math.random() * 880) };
                });
                persist();
                snack((STR.integ.retestSnack || "已刷新快照,可用 {n} 个模型").replace("{n}", c.models.length), "check");
                draw(body); // openSnap 保持展开,展示刷新后的列表
              }, 950);
              return;
            }
            const b = e.target.closest("[data-pick]");
            if (!b) return;
            c.apiId = b.dataset.pick;
            persist();
            snack(STR.integ.snapPicked + " · " + c.apiId, "check");
            draw(body);
          });
          d.appendChild(drop);
        }
        wrap.appendChild(d);
      });
      body.appendChild(wrap);
    }
    const add = document.createElement("button");
    add.className = "addmodel";
    add.innerHTML = `${svg("plus")}<span>${STR.integ.addBtn}</span>`;
    body.appendChild(add);
    add.addEventListener("click", () => {
      closeSheets();
      openCustomForm();
    });
  }
}

/* ---------- 添加自定义模型(表单) ---------- */
function openCustomForm(item) {
  const editItem = item || null;
  openSheet({
    title: editItem ? STR.integ.editTitle : STR.integ.formTitle,
    sub: editItem ? editItem.name : STR.integ.formSub,
    onRender: (body) => {
      body.innerHTML = `
        <div class="form">
          <div class="form-sec">${STR.integ.formSecBasic}</div>
          <label class="field">
            <span class="fl">${STR.integ.fName}<b class="req">*</b><span class="model-badge cmod" id="cfBadge">API</span></span>
            <input id="cfName" placeholder="${STR.integ.fNamePh}" autocomplete="off"/>
          </label>
          <label class="field">
            <span class="fl">${STR.integ.fModelId}<b class="req">*</b></span>
            <input id="cfApi" placeholder="${STR.integ.fModelIdPh}" autocomplete="off"/>
          </label>
          <div class="field-grid">
            <label class="field">
              <span class="fl">${STR.integ.fCtx}</span>
              <select id="cfCtx">
                ${STR.integ.fCtxOpts.map((o) => `<option${o === "32K" ? " selected" : ""}>${o}</option>`).join("")}
              </select>
            </label>
            <label class="field">
              <span class="fl">${STR.integ.fTimeout}</span>
              <select id="cfTo">
                ${STR.integ.fToOpts.map((o) => `<option${o === "60 秒" ? " selected" : ""}>${o}</option>`).join("")}
              </select>
            </label>
          </div>
          <div class="form-sec" style="margin-top:16px">${STR.integ.formSecEndpoint}</div>
          <label class="field">
            <span class="fl">${STR.integ.fBase}<b class="req">*</b></span>
            <input id="cfBase" placeholder="${STR.integ.fBasePh}" inputmode="url" autocomplete="off"/>
            <div class="ep-box empty" id="cfEp">
              <span class="ep-tag" id="cfEpTag">${STR.integ.epAuto}</span>
              <code class="ep-path" id="cfEpPath">${STR.integ.epEmpty}</code>
            </div>
            <span class="fh">${STR.integ.baseHint}</span>
          </label>
          <label class="field">
            <span class="fl">${STR.integ.fKey}<span class="opt">${STR.integ.fKeyOpt}</span></span>
            <div class="key-row">
              <input id="cfKey" type="password" placeholder="${STR.integ.fKeyPh}" autocomplete="off"/>
              <button type="button" class="eye" data-eye title="${STR.integ.showKey}">${svg("eye")}</button>
            </div>
            <span class="fh">${STR.integ.fKeyHint}</span>
          </label>
          <div class="switch-row">
            <span class="txt"><span class="t1">${STR.integ.fTLS}</span><span class="t2">${STR.integ.fTLSSub}</span></span>
            <span class="switch" data-tls></span>
          </div>
          <button type="button" class="adv-toggle" data-adv>${svg("chevD")}<span>${STR.integ.advTitle}</span></button>
          <div class="adv-wrap hidden" data-advwrap>
            <label class="field">
              <span class="fl">${STR.integ.fHeaders}</span>
              <textarea id="cfHeaders" class="task-ta" rows="3" placeholder="${STR.integ.fHeadersPh}"></textarea>
              <span class="fh">${STR.integ.fHeadersHint}</span>
            </label>
          </div>
          <div class="test-result hidden" id="cfResult"></div>
          <div class="form-actions">
            <button class="btn btn-ghost" data-t>${svg("bolt")}<span>${STR.integ.testBtn}</span></button>
            <button class="btn btn-primary" data-s>${svg("plus")}<span>${editItem ? STR.integ.saveBtn : STR.integ.saveBtn}</span></button>
          </div>
        </div>`;
      const inp = (id) => body.querySelector(id);
      const result = body.querySelector("#cfResult");
      const tBtn = body.querySelector("[data-t]");
      const sBtn = body.querySelector("[data-s]");
      const badge = body.querySelector("#cfBadge");
      const eyeBtn = body.querySelector("[data-eye]");
      const keyInp = body.querySelector("#cfKey");
      const tlsEl = body.querySelector("[data-tls]");
      const advBtn = body.querySelector("[data-adv]");
      const advWrap = body.querySelector("[data-advwrap]");
      const epBox = body.querySelector("#cfEp");
      const epTag = body.querySelector("#cfEpTag");
      const epPath = body.querySelector("#cfEpPath");
      let tested = false;
      let modelSnap = editItem && Array.isArray(editItem.models) ? editItem.models.slice() : [];

      // 显示名称 → 徽标实时预览
      inp("#cfName").addEventListener("input", () => {
        const nm = inp("#cfName").value.trim();
        badge.textContent = (nm || "API").slice(0, 2).toUpperCase();
      });
      // 密钥显示/隐藏
      eyeBtn.addEventListener("click", () => {
        const show = keyInp.type === "password";
        keyInp.type = show ? "text" : "password";
        eyeBtn.innerHTML = svg(show ? "eyeOff" : "eye");
        eyeBtn.title = show ? STR.integ.hideKey : STR.integ.showKey;
      });
      // TLS 证书校验开关
      tlsEl.addEventListener("click", () => tlsEl.classList.toggle("on"));
      // 高级选项折叠
      advBtn.addEventListener("click", () => {
        advWrap.classList.toggle("hidden");
        advBtn.classList.toggle("open");
      });
      // Base URL → 完整请求路径实时预览(协议自动补全 + 中转站完整地址)
      function renderEp() {
        const raw = normEndpoint(inp("#cfBase").value);
        epBox.classList.toggle("empty", !raw);
        if (!raw) {
          epTag.textContent = STR.integ.epAuto;
          epPath.textContent = STR.integ.epEmpty;
          return;
        }
        const full = endpointUrl(raw);
        const direct = raw.toLowerCase().endsWith("/chat/completions");
        epBox.classList.toggle("direct", !!raw && direct);
        epTag.textContent = direct ? STR.integ.epDirect : STR.integ.epAuto;
        epPath.textContent = full;
      }
      inp("#cfBase").addEventListener("input", renderEp);
      // 失焦时若缺少协议则自动补全 https:// 并写回输入框
      inp("#cfBase").addEventListener("change", () => {
        const cur = inp("#cfBase").value.trim();
        if (cur && cur.indexOf("://") === -1) {
          inp("#cfBase").value = normEndpoint(cur);
          renderEp();
        }
      });
      renderEp();

      // 编辑模式预填
      if (editItem) {
        inp("#cfName").value = editItem.name || "";
        inp("#cfApi").value = editItem.apiId || "";
        inp("#cfBase").value = editItem.base || "";
        keyInp.value = editItem.key || "";
        if (editItem.ctx) inp("#cfCtx").value = editItem.ctx;
        if (editItem.timeout) inp("#cfTo").value = editItem.timeout;
        if (editItem.skipTLS) tlsEl.classList.add("on");
        if (editItem.headers) {
          inp("#cfHeaders").value = editItem.headers;
          advWrap.classList.remove("hidden");
          advBtn.classList.add("open");
        }
        badge.textContent = (editItem.name || "API").slice(0, 2).toUpperCase();
        renderEp();
        if (modelSnap.length) showModelList(modelSnap, STR.integ.snapTitle, STR.integ.snapSub, { rebuild: true });
      }

      const vals = () => ({
        name: inp("#cfName").value.trim(),
        base: inp("#cfBase").value.trim().replace(/\/+$/, ""),
        apiId: inp("#cfApi").value.trim(),
        key: inp("#cfKey").value.trim(),
      });
      const valid = (v) => v.name && v.base && v.apiId;

      function showModelList(list, title, sub, opts) {
        opts = opts || {};
        const oldW = document.querySelector("#cfModels");
        if (oldW) oldW.remove();
        const wrap = document.createElement("div");
        wrap.className = "models-found";
        wrap.id = "cfModels";
        const typed = (inp("#cfApi").value || "").trim().toLowerCase();
        wrap.innerHTML = `<div class="perm-title">${title}</div>
          <p class="mf-sub">${sub}</p>
          <div class="mf-list">${list
            .map(
              (id) => `<button class="mf-item${id.toLowerCase() === typed ? " on" : ""}" data-mid="${esc(id)}"><code>${esc(id)}</code>${id.toLowerCase() === typed ? `<span class="conn-pill on cur">已填</span>` : ""}</button>`
            )
            .join("")}
          </div>`;
        if (opts.rebuild && list.length) {
          const ttl = wrap.querySelector(".perm-title");
          const bar = document.createElement("button");
          bar.type = "button";
          bar.className = "mf-rebuild";
          bar.innerHTML = svg("refresh") + "<span>" + esc(STR.integ.rebuildBtn) + "</span>";
          bar.addEventListener("click", () => {
            inp("#cfApi").value = list[0];
            snack(STR.integ.rebuiltSnack + " · " + list[0], "check");
            showModelList(list, title, sub, opts);
          });
          ttl.appendChild(bar);
        }
        wrap.querySelectorAll(".mf-item").forEach((b) =>
          b.addEventListener("click", () => {
            const mid = b.dataset.mid;
            inp("#cfApi").value = mid;
            if (!inp("#cfName").value.trim()) {
              inp("#cfName").value = mid
                .split("-").join(" ").split("_").join(" ").split(".").join(" ")
                .split(" ")
                .filter(Boolean)
                .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
                .join(" ")
                .slice(0, 28);
              badge.textContent = inp("#cfName").value.slice(0, 2).toUpperCase();
            }
            showModelList(list, title, sub); // 重绘以更新「已填」标记
            snack(STR.integ.filledSnack + " · " + mid, "check");
          })
        );
        result.insertAdjacentElement("afterend", wrap);
      }

      function showDiscovered(v) {
        const pool = [
          "qwen2.5-coder-32b", "qwen2.5-coder-7b", "deepseek-chat", "deepseek-reasoner",
          "glm-4-flash", "gpt-4o-mini", "gpt-4o", "claude-3-5-haiku-20241022",
          "llama3.1-70b", "mistral-small-latest", "o3-mini", "gemini-2.0-flash",
        ];
        const typed = (v.apiId || "").toLowerCase();
        const list = typed && pool.indexOf(typed) === -1 ? [typed, ...pool] : pool;
        modelSnap = list.slice(); // 保存本次拉取快照
        showModelList(list, STR.integ.modelsTitle, STR.integ.modelsSub);
      }

      tBtn.addEventListener("click", () => {
        const v = vals();
        if (!valid(v)) {
          snack(STR.integ.emptyErr, "info");
          return;
        }
        tBtn.disabled = true;
        tBtn.querySelector("span").textContent = STR.integ.testing;
        result.classList.remove("hidden");
        result.classList.add("loading");
        result.innerHTML = svg("refresh") + `<span>${STR.integ.requesting} ${esc(endpointUrl(v.base))} …</span>`;
        setTimeout(() => {
          tBtn.disabled = false;
          tBtn.querySelector("span").textContent = STR.integ.testBtn;
          tested = true;
          result.classList.remove("loading");
          result.innerHTML = svg("check") + `<span>${STR.integ.testOk}</span>`;
          showDiscovered(v);
        }, 950);
      });
      sBtn.addEventListener("click", () => {
        const v = vals();
        if (!valid(v)) {
          snack(STR.integ.emptyErr, "info");
          return;
        }
        const extra = {
          base: normEndpoint(v.base),
          ctx: inp("#cfCtx").value,
          timeout: inp("#cfTo").value,
          skipTLS: tlsEl.classList.contains("on"),
          headers: inp("#cfHeaders").value.trim(),
          models: modelSnap,
        };
        if (editItem) {
          Object.assign(editItem, v, extra);
          persist();
          snack(STR.integ.editedSnack + " · " + v.name, "check");
        } else {
          const item = { id: "cm-" + uid(), ...v, ...extra };
          S.customModels.push(item);
          S.modelId = item.id; // 添加后直接设为当前,便于立即体验
          persist();
          snack(STR.integ.savedSnack + " · " + v.name, "check");
        }
        refreshModelUi();
        rerenderSettings();
        closeSheets();
      });
    },
  });
}

/* ============================================================
   发起任务向导(仓库 → 模型 → 任务)
   ============================================================ */
let TASK_DRAFT = null;

/* Git 账号下的演示仓库(连接后可见) */
/* 常用代码托管平台(手动仓库地址快速粘贴) */
const REPO_HOSTS = [
  { name: "GitHub", host: "github.com", dot: "#8b949e" },
  { name: "GitLab", host: "gitlab.com", dot: "#fc6d26" },
  { name: "Gitee", host: "gitee.com", dot: "#c71d23" },
];

const GIT_REPOS = [
  { name: "octocat/hello-world", branch: "main", desc: "示例仓库 · README 与源码" },
  { name: "octocat/freebuff", branch: "main", desc: "Freebuff 官方仓库(演示镜像)" },
  { name: "octocat/dotfiles", branch: "master", desc: "配置文件合集" },
];

/* 任务上下文 → 模拟回复(携带仓库/模型信息) */
function taskReply(ctx) {
  const model = modelById(ctx.modelId).name;
  const repo = ctx.repoLabel || "";
  return {
    steps: [
      { name: "规划", sub: "理解任务 → 确认影响范围 → 制定方案" },
      { name: "执行", sub: repo ? "读取仓库 → 实施变更" : "编写可直接使用的方案与代码" },
      { name: "校验", sub: "对照任务要求检查输出" },
    ],
    md: `任务已收到 ✅

- **模型**:${model}
- **工作仓库**:${repo || "不关联(直接产出方案与代码)"}

我按下面的步骤推进:

1. 拆解任务,明确目标与交付物
2. ${repo ? "在仓库中实施变更并说明每一步" : "编写可直接使用的示例代码"}
3. 校验结果,输出总结与后续建议`,
    code: {
      lang: "text",
      text: `[任务上下文]
仓库: ${repo || "不关联"}
模型: ${model}
状态: 已接收,进入 Agent 执行链路(原型为模拟)`,
    },
    md2: `> 原型演示:以上为预置模拟回复。正式版将在此接入真实 Agent 执行链路(规划 → 执行 → 校验),并实时展示仓库中的文件变更。`,
  };
}

/* FAB「+」→ 发起任务向导 */
function launchTask() {
  stopStreaming();
  openSheet({
    title: STR.task.title,
    sub: STR.task.sub,
    onRender: (body) => {
      const d = TASK_DRAFT || { repoMode: "none", repoUrl: "", repoName: "", repoBranch: "", modelId: S.modelId, desc: "" };
      TASK_DRAFT = d;
      let step = 0;

      function repoPanel() {
        const wrap = document.createElement("div");
        wrap.className = "wz-panel" + (step === 0 ? " active" : "");
        wrap.innerHTML = `
          <button class="wz-opt ${d.repoMode === "none" ? "on" : ""}" data-mode="none">
            <span class="wz-radio"></span>
            <span class="wz-ic">${svg("slash")}</span>
            <span class="txt"><span class="t1">${STR.task.repoNone}</span><span class="t2">${STR.task.repoNoneSub}</span></span>
          </button>
          <button class="wz-opt ${d.repoMode === "manual" ? "on" : ""}" data-mode="manual">
            <span class="wz-radio"></span>
            <span class="wz-ic">${svg("link")}</span>
            <span class="txt"><span class="t1">${STR.task.repoManual}</span><span class="t2">${STR.task.repoManualSub}</span></span>
          </button>
          <div class="wz-url ${d.repoMode === "manual" ? "show" : ""}">
            <input id="wzRepoUrl" class="wz-url-inp" placeholder="${STR.task.repoManualPh}" value="${esc(d.repoUrl)}" autocomplete="off"/>
            <div class="repo-quick" id="wzRepoQuick"><span class="rq-lbl">${STR.task.repoQuick}</span>${REPO_HOSTS.map((h) => `<button type="button" class="rq-chip" data-host="${h.host}"><i style="background:${h.dot}"></i>${h.name}</button>`).join('')}</div>
            <div class="rp-line hidden" id="wzRepoLine">
              <span class="rp-tag" id="wzRepoTag"></span>
              <code class="rp-val" id="wzRepoVal"></code>
            </div>
          </div>
          <button class="wz-opt ${d.repoMode === "git" ? "on" : ""}" data-mode="git">
            <span class="wz-radio"></span>
            <span class="wz-ic git">${svg("git")}</span>
            <span class="txt"><span class="t1">${STR.task.repoGit}</span><span class="t2">${S.git.connected ? "@" + esc(S.git.login) + " · " + STR.task.repoGitSub : STR.task.repoGitOff}</span></span>
          </button>
          ${S.git.connected
            ? `<div class="wz-gitlist">${GIT_REPOS.map((r, i) => `
                <button class="git-repo ${d.repoMode === "git" && d.repoName === r.name ? "on" : ""}" data-repo="${i}">
                  <span class="wz-radio"></span>
                  <span class="txt"><span class="gr-name">${esc(r.name)}</span><span class="gr-br">${esc(r.branch)} · ${esc(r.desc)}</span></span>
                </button>`).join("")}
              </div>`
            : `<button class="git-go" data-go-git>${svg("plug")}<span>${STR.task.repoGitGo}</span></button>`}`;
        wrap.querySelectorAll(".wz-opt").forEach((o) =>
          o.addEventListener("click", () => {
            const mode = o.dataset.mode;
            d.repoMode = mode;
            if (mode === "git" && S.git.connected && !d.repoName) {
              d.repoName = GIT_REPOS[0].name;
              d.repoBranch = GIT_REPOS[0].branch;
            }
            refresh();
          })
        );
        const urlInp = wrap.querySelector("#wzRepoUrl");
        const rpLine = wrap.querySelector("#wzRepoLine");
        const rpTag = wrap.querySelector("#wzRepoTag");
        const rpVal = wrap.querySelector("#wzRepoVal");
        const rpBr = document.createElement("span");
        rpBr.className = "rp-br hidden";
        rpLine.appendChild(rpBr);
        function setBranch(br) {
          d.repoBranch = br || "";
          if (br) { rpBr.textContent = "分支 " + br; rpBr.classList.remove("hidden"); }
          else { rpBr.textContent = ""; rpBr.classList.add("hidden"); }
        }
        setBranch(d.repoBranch || "");
        function renderRepoLine() {
          const raw = urlInp ? urlInp.value.trim() : "";
          if (S.repoParse === "loose") {
            rpLine.classList.toggle("hidden", !raw);
            if (!raw) return;
            rpTag.textContent = STR.task.repoSchemeKeep;
            rpTag.classList.add("keep");
            rpVal.textContent = raw;
            return;
          }
          const norm = normRepoUrl(raw);
          rpLine.classList.toggle("hidden", !norm);
          if (!norm) return;
          const unchanged = norm === raw;
          const isSsh = norm.indexOf("://") === -1;
          rpTag.textContent = isSsh || unchanged ? STR.task.repoSchemeKeep : STR.task.repoSchemeAuto;
          rpTag.classList.toggle("keep", isSsh || unchanged);
          rpVal.textContent = norm;
        }
        if (urlInp) {
          urlInp.addEventListener("input", () => {
            d.repoUrl = urlInp.value.trim();
            if (S.repoParse !== "loose") if (!urlInp.value.trim()) setBranch("");
            renderRepoLine();
          });
          function applyCloneParse() {
            if (S.repoParse !== "strict") return;
            const val = (urlInp.value || "").trim();
            if (!val) return;
            const lowVal = val.toLowerCase();
            const looksClone =
              lowVal.indexOf("git clone") === 0 ||
              val.indexOf("#") !== -1 ||
              lowVal.indexOf("/tree/") !== -1 ||
              lowVal.endsWith(".git");
            if (!looksClone) return;
            const parsed = parseClone(val);
            if (!parsed) return;
            const canonical = parsed.scheme
              ? parsed.scheme + "://" + parsed.host + "/" + parsed.path
              : val.indexOf("@") !== -1
                ? "git@" + parsed.host + ":" + parsed.path
                : parsed.host + "/" + parsed.path;
            if (canonical === val && !parsed.branch) return;
            d.repoUrl = canonical;
            urlInp.value = canonical;
            setBranch(parsed.branch);
            renderRepoLine();
            snack(STR.task.repoParsed + ": " + canonical + (parsed.branch ? " · 分支 " + parsed.branch : ""), "check");
            return true;
          }
          urlInp.addEventListener("paste", () => setTimeout(applyCloneParse, 30));
          urlInp.addEventListener("change", () => {
            if (applyCloneParse()) return;
            const cur = urlInp.value.trim();
            const norm = normRepoUrl(cur);
            if (S.repoParse !== "loose" && cur && norm !== cur) {
              urlInp.value = norm;
              d.repoUrl = norm;
              renderRepoLine();
            }
          });
          renderRepoLine();
        }
        const quick = wrap.querySelector("#wzRepoQuick");
        if (quick)
          quick.querySelectorAll(".rq-chip").forEach((ch) =>
            ch.addEventListener("click", () => {
              const host = ch.dataset.host;
              const cur = (urlInp ? urlInp.value : "").trim();
              const bare = cur.indexOf("://") === -1 ? cur : cur.slice(cur.indexOf("://") + 3);
              const sameHost = bare === host || bare.indexOf(host + "/") === 0;
              if (!sameHost) {
                d.repoMode = "manual";
                d.repoUrl = host + "/";
                urlInp.value = d.repoUrl;
                renderRepoLine();
              }
              urlInp.focus();
              urlInp.setSelectionRange(urlInp.value.length, urlInp.value.length);
            })
          );
                const go = wrap.querySelector("[data-go-git]");
        if (go)
          go.addEventListener("click", () => {
            closeSheets();
            integGitSheet();
          });
        wrap.querySelectorAll(".git-repo").forEach((r) =>
          r.addEventListener("click", () => {
            const g = GIT_REPOS[+r.dataset.repo];
            d.repoMode = "git";
            d.repoName = g.name;
            d.repoBranch = g.branch;
            refresh();
          })
        );
        return wrap;
      }

      function modelPanel() {
        const all = modelList();
        const officials = all.filter((m) => m.tier !== "custom");
        const customs = all.filter((m) => m.tier === "custom");
        const itemHtml = (m) => `
          <button class="model-item ${m.id === d.modelId ? "on" : ""}" data-model="${m.id}">
            <span class="model-badge">${m.badge}</span>
            <span class="txt" style="flex:1;min-width:0">
              <span class="m1"><span class="name">${esc(m.name)}</span><span class="tier ${TIER_CLS[m.tier]}">${m.tierText}</span></span>
              <span class="m2">${m.desc}</span>
            </span>
            <span class="check">${svg("check")}</span>
          </button>`;
        const wrap = document.createElement("div");
        wrap.className = "wz-panel" + (step === 1 ? " active" : "");
        wrap.innerHTML =
          `<div class="sheet-mini-label">${STR.task.official}</div>` +
          officials.map(itemHtml).join("") +
          `<div class="sheet-mini-label" style="margin-top:14px">${STR.task.custom}</div>` +
          (customs.length ? customs.map(itemHtml).join("") : `<div class="wz-none">${STR.task.noCustom}</div>`);
        wrap.querySelectorAll(".model-item").forEach((it) =>
          it.addEventListener("click", () => {
            d.modelId = it.dataset.model;
            refresh();
          })
        );
        return wrap;
      }

      function taskPanel() {
        const wrap = document.createElement("div");
        wrap.className = "wz-panel" + (step === 2 ? " active" : "");
        const repoLabel = d.repoMode === "git" ? d.repoName : d.repoMode === "manual" ? d.repoUrl || "…" : STR.task.repoNone;
        const modelName = modelById(d.modelId).name;
        wrap.innerHTML = `
          <div class="task-sum">
            <div class="ts-row">${svg("git")}<span>${STR.task.sumRepo}</span><b>${esc(repoLabel)}</b></div>
            <div class="ts-row">${svg("bolt")}<span>${STR.task.sumModel}</span><b>${esc(modelName)}</b></div>
          </div>
          <label class="field">
            <span class="fl">${STR.task.taskLabel}<b class="req">*</b></span>
            <textarea id="wzDesc" class="task-ta" rows="4" placeholder="${STR.task.taskPh}"></textarea>
          </label>`;
        const ta = wrap.querySelector("#wzDesc");
        ta.value = d.desc;
        ta.addEventListener("input", () => { d.desc = ta.value; });
        return wrap;
      }

      function stepperEl() {
        const w = document.createElement("div");
        w.className = "wz-steps";
        const labels = [STR.task.step1, STR.task.step2, STR.task.step3];
        w.innerHTML = labels
          .map((lb, i) => `<button class="wz-step ${i === step ? "on" : ""} ${i < step ? "done" : ""}" data-goto="${i}"><i>${i < step ? "✓" : i + 1}</i>${lb}</button>`)
          .join('<span class="wz-line"></span>');
        w.querySelectorAll("[data-goto]").forEach((b) =>
          b.addEventListener("click", () => {
            const g = +b.dataset.goto;
            if (g < step) { step = g; refresh(); }
          })
        );
        return w;
      }

      function navEl() {
        const last = step === 2;
        const w = document.createElement("div");
        w.className = "wz-nav";
        w.innerHTML = `
          <button class="btn btn-ghost" data-prev style="${step === 0 ? "visibility:hidden" : ""}">${STR.task.prev}</button>
          <button class="btn btn-primary" data-next>${last ? svg("send") + STR.task.launch : STR.task.next}</button>`;
        w.querySelector("[data-prev]").addEventListener("click", () => {
          if (step > 0) { step--; refresh(); }
        });
        w.querySelector("[data-next]").addEventListener("click", () => {
          if (step === 0) {
            if (d.repoMode === "manual") {
              const u = normRepoUrl(d.repoUrl);
              if (!u) { snack(STR.task.repoManualReq, "info"); return; }
              const scheme = u.indexOf("://");
              const validPath = scheme !== -1 ? u.slice(scheme + 3).indexOf("/") !== -1 : u.indexOf("/", u.indexOf(":") + 1) !== -1;
              if (!validPath) { snack(STR.task.repoInvalid, "info"); return; }
            }
            step = 1; refresh();
          } else if (step === 1) {
            step = 2; refresh();
          } else {
            doLaunch();
          }
        });
        return w;
      }

      function refresh() {
        body.innerHTML = "";
        body.appendChild(stepperEl());
        body.appendChild(repoPanel());
        body.appendChild(modelPanel());
        body.appendChild(taskPanel());
        body.appendChild(navEl());
        body.scrollTop = 0;
      }

      function doLaunch() {
        const desc = d.desc.trim();
        if (!desc) { snack(STR.task.taskReq, "info"); return; }
        const ctx = {
          modelId: d.modelId,
          repoLabel:
            d.repoMode === "git" ? d.repoName + " (" + d.repoBranch + ")"
            : d.repoMode === "manual" ? (S.repoParse === "loose" ? (d.repoUrl || "").trim() : normRepoUrl(d.repoUrl))
            : "",
        };
        const s = {
          id: uid(),
          title: desc.length > 16 ? desc.slice(0, 16) + "…" : desc,
          time: "刚刚",
          preview: desc.slice(0, 60),
          messages: [],
        };
        S.sessions.unshift(s);
        TASK_DRAFT = null;
        S.modelId = d.modelId; // 任务采用的模型同时设为当前模型(与模型选择弹层一致)
        persist();
        closeSheets();
        openChat(s.id);
        sendMessage(desc, ctx);
        snack(STR.task.launched + " · " + modelById(d.modelId).name, "check");
        renderHomeListIfShown();
      }

      refresh();
    },
  });
}

/* ============================================================
   动作与路由(事件代理)
   ============================================================ */
function newChat() {
  stopStreaming();
  const s = {
    id: uid(),
    title: "",
    time: "刚刚",
    preview: "",
    messages: [],
  };
  S.sessions.unshift(s);
  openChat(s.id);
  snack(STR.snack.newChat, "chat");
  renderHomeListIfShown();
}
function deleteChat(sid) {
  S.sessions = S.sessions.filter((x) => x.id !== sid);
  if (S.activeId === sid) S.activeId = null;
}
function cycleTheme() {
  const orderArr = ["dark", "light", "system"];
  S.themeMode = orderArr[(orderArr.indexOf(S.themeMode) + 1) % orderArr.length];
  persist();
  applyTheme();
  // 刷新设置与首页图标
  const setEl = screensEl.querySelector('.screen[data-screen="settings"]');
  if (setEl && S.route === "settings" && setEl._render) setEl._render();
  const homeEl = screensEl.querySelector('.screen[data-screen="home"]');
  if (homeEl && S.route === "home" && homeEl._render) homeEl._render();
}
function clearAll() {
  if (!S.armedClear) {
    S.armedClear = true;
    const setEl = screensEl.querySelector('.screen[data-screen="settings"]');
    if (setEl && S.route === "settings" && setEl._render) setEl._render();
    setTimeout(() => {
      S.armedClear = false;
      const e2 = screensEl.querySelector('.screen[data-screen="settings"]');
      if (e2 && S.route === "settings" && e2._render) e2._render();
    }, 2200);
    return;
  }
  S.armedClear = false;
  if (S.streaming && S._stopStream) S._stopStream();
  seedSessions();
  S.activeId = null;
  if (S.route === "chat") goto("home");
  renderHomeListIfShown();
  const e = screensEl.querySelector('.screen[data-screen="settings"]');
  if (e && e._render) e._render();
  snack(STR.settings.cleared, "check");
}

document.addEventListener("click", (e) => {
  const t = e.target.closest("[data-action],[data-tab]");
  if (!t) return;
  e.preventDefault();
  const a = t.dataset.action || "tab:" + t.dataset.tab;
  switch (a) {
    case "login":
      S.signedIn = true;
      persist();
      snack(STR.snack.loginDemo, "check");
      goto("home");
      break;
    case "guest":
      snack(STR.snack.guestLogin, "check");
      goto("home");
      break;
    case "toggle-theme":
      cycleTheme();
      break;
    case "model-sheet":
      modelSheet();
      break;
    case "integ-git":
      integGitSheet();
      break;
    case "integ-custom":
      integCustomSheet();
      break;
    case "about-demo":
      aboutDemo();
      break;
    case "check-update":
      checkUpdate();
      break;
    case "open-session":
      openChat(t.dataset.id);
      break;
    case "sess-menu":
      e.stopPropagation();
      sessMenu(t.dataset.id);
      break;
    case "back-home":
      goto("home");
      break;
    case "chat-more":
      chatMore();
      break;
    case "send":
      if (S.streaming) stopStreaming();
      else sendMessage();
      break;
    case "new-chat":
      launchTask();
      break;
    case "tab:home":
      goto("home");
      break;
    case "tab:settings":
      goto("settings");
      break;
    case "clear-all":
      clearAll();
      break;
    case "open-site":
      snack("freebuff.com(演示:原型不发起外部请求)", "external");
      break;
    case "feedback":
      snack(STR.settings.feedbackPh, "chat");
      break;
    case "tool-at":
      insertToken("@");
      break;
    case "tool-file":
      insertToken("@文件");
      break;
  }
});
function insertToken(tok) {
  if (S.streaming || !chatElCache) return;
  const input = chatElCache.querySelector("#input");
  const start = input.selectionStart ?? input.value.length;
  input.value = input.value.slice(0, start) + tok + " " + input.value.slice(input.selectionEnd ?? start);
  input.focus();
  input.dispatchEvent(new Event("input"));
}

function stopStreaming() {
  if (S.streaming && S._stopStream) S._stopStream();
}
function openChat(sid) {
  S.activeId = sid;
  goto("chat");
  if (chatElCache) renderChatInto(chatElCache);
}

/* ============================================================
   初始化
   ============================================================ */
function init() {
  loadPrefs();
  seedSessions();
  applyTheme(true);
  const timeEl = $("#sbTime");
  const now = new Date();
  timeEl.textContent = now.toLocaleTimeString("en-US", { hour: "numeric", minute: "2-digit", hour12: false }).padStart(5, "0");
  S.route = S.signedIn ? "home" : "welcome";
  goto(S.route);

  // 设置页主题分段委托
  screensEl.addEventListener("click", (e) => {
    const b = e.target.closest("#themeSeg button");
    if (!b) return;
    S.themeMode = b.dataset.mode;
    persist();
    applyTheme();
    if (S.route === "settings") rerenderSettings();
  });
  // 仓库地址解析模式分段
  screensEl.addEventListener("click", (e) => {
    const b = e.target.closest("#repoParseSeg button");
    if (!b) return;
    S.repoParse = b.dataset.rp;
    persist();
    snack(S.repoParse === "strict" ? STR.settings.rpSnackStrict : STR.settings.rpSnackLoose, "check");
    rerenderSettings();
  });
  // 会话列表长按提示由点击 ⋯ 完成
}
window.addEventListener("DOMContentLoaded", init);
