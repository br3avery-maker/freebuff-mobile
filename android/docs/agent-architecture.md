# Freebuff Agent 架构参考(工具注册与循环机制)

本文整理开源仓库 [CodebuffAI/freebuff](https://github.com/CodebuffAI/freebuff) 中 agent 运行时的核心机制:工具如何注册、一次工具调用如何执行、agent 循环如何驱动。作为原生 App(`android/`)对接后端的**行为参考**——对话流的渲染、工具调用的展示、错误文案的归因、断线重连的语义,都以后端这套运行时为准。

> 依据:公开源码 HEAD(2026-09 快照)。关键文件:
>
> | 文件 | 职责 |
> |---|---|
> | `common/src/tools/list.ts` | 工具参数注册表(36 + 4 个工具) |
> | `common/src/tools/params/tool/*.ts` | 每个工具的 zod input/output schema |
> | `packages/agent-runtime/src/tools/tool-executor.ts` | 调用执行管线(解析/校验/权限/执行) |
> | `packages/agent-runtime/src/tools/handlers/list.ts` | 工具名 → 处理函数映射 |
> | `packages/agent-runtime/src/run-agent-step.ts` | 外层 agent 回合循环 |
> | `packages/agent-runtime/src/run-programmatic-step.ts` | handleSteps 生成器驱动 |
> | `agents/base2/base2.ts` · `agents/base-chat.ts` | 官方 agent 剧本(标准循环写法) |

---

## 1. 工具注册表

工具注册集中在 `common/src/tools/list.ts` 的 `toolParams` 记录,键为工具名(snake_case),值为该工具的参数定义。**每个工具是"定义 + 处理器"两半拼起来的**:

- **定义**(`common/src/tools/params/tool/<name>.ts`):zod 的 `inputSchema` / `outputSchema`、给 LLM 看的描述、`endsAgentStep` 标记(调用该工具是否结束一步)。
- **处理器**(`packages/agent-runtime/src/tools/handlers/tool/<name>.ts`):真正执行逻辑,统一收在 `handlers/list.ts` 的 `codebuffToolHandlers` 记录,键就是工具名。

### 1.1 全量工具清单(36 核心 + 4 Composio)

| 分类 | 工具 |
|---|---|
| 文件读取 | `read_files` `read_subtree` `list_directory` `glob` `find_files` `code_search` `read_docs` `read_url` |
| 文件写入 | `write_file` `str_replace` `apply_patch` `propose_write_file` `propose_str_replace`(提案版:先出差异,落实后才生效) |
| 终端 | `run_terminal_command` `run_file_change_hooks` |
| 任务管理 | `write_todos` `add_subgoal` `update_subgoal` `create_plan` `think_deeply` |
| 子代理 | `spawn_agents` `spawn_agent_inline` `lookup_agent_info` `task_completed` `end_turn` |
| 交互/输出 | `ask_user` `suggest_followups` `set_output` `set_messages` `add_message` `render_ui` |
| 其他 | `web_search` `browser_logs` `skill` `gravity_index` `cloud_plan_ready` |
| Composio(外部集成) | `composio_search_tools` `composio_get_tool_schemas` `composio_multi_execute_tool` `composio_manage_connections` |

### 1.2 工具可用性是按 agent 裁剪的

agent 模板用 `toolNames` 声明自己能用哪些工具,`base2`(主力编码代理)按模式动态组装:

```ts
toolNames: buildArray(
  'spawn_agents', 'read_files', 'read_subtree',
  !isFast && !planOnly && 'write_todos',
  !planOnly && 'str_replace',
  !planOnly && 'write_file',
  !noAskUser && 'ask_user',
  ...
)
```

关键结论:**plan 模式下 `str_replace` / `write_file` / `basher` 直接从工具集剔除**——权限由工具集强制,不靠提示词自觉。App 端展示"计划模式"状态时,应理解为后端真的裁掉了写工具,而不是提示词约束。

---

## 2. 一次工具调用的执行管线

`tool-executor.ts` 的 `executeToolCall`,四道工序:

### 2.1 解析与容错(`parseRawToolCall`)

1. 模型输出的参数常见"双重编码 JSON 字符串"(`"\"{\\\"path\\\":...}\""`),最多做 **3 轮 `JSON.parse`**;
2. 仍失败时按**按工具的白名单**做裸字符串修复(`bareStringFieldRepairAllowlist`),如 `code_search` 只修 `pattern`、`read_files` 只修 `paths`;
3. zod 校验失败时返回带预期结构提示的错误——`str_replace`/`write_file` 有专门的提示生成(`summarizeMissingReplacementFields` 会点名缺 `replacements[n].oldString`)。

> App 端意义:后端发来的 `error` 事件文案已含"Invalid parameters for X: … Expected shape: …"这类归因信息,**原样展示即可**,不必再解析。

### 2.2 权限检查

`toolName` 不在 `agentTemplate.toolNames` → 发 error 事件(`Tool X is not currently available…`),**不进消息历史**、不执行。

### 2.3 特殊预检

- `spawn_agents`:先并行验证所有待生成 agent 存在且可生成;全部无效 → 直接报错不发流;部分无效 → 报 warning 并只跑有效部分。
- `render_ui`:链接引用在流式输入阶段就解析成带追踪的真实 URL,失败的引用直接拦截。

### 2.4 串行执行与结果回填

```
onResponseChunk({ type: 'tool_call', toolCallId, toolName, input })   ← 权限过了才发
    ↓
handler = codebuffToolHandlers[toolName]
    ↓ 每个 handler 都先 await previousToolCallFinished —— 按流式顺序串行,不并行
onResponseChunk({ type: 'tool_result', toolCallId, output })
    ↓
进 toolResults / 消息历史(除非 excludeToolFromMessageHistory)
    ↓
creditsUsed → onCostCalculated 累加积分
```

**客户端工具**(`ask_user`、交互式终端等)handler 不自己执行:通过 `requestClientToolCall` 回调把请求发给宿主(CLI/桌面端),宿主执行完回传结果。App 接入后,`ask_user` 一类交互就是走这条通道落到手机 UI 上。

### 2.5 终端命令的托管模型(与 App 相关)

`run_terminal_command` 把"进程所有权"与"终端 UI 所有权"分离:

- SDK 的 `run-terminal-command.ts` 拥有输出缓冲、超时、取消升级、进程诊断;
- 交互式宿主(CLI)提供 `terminalCommandBroker`,每次调用同步启动一个隔离 helper,返回完整进程树的句柄;
- 辅助进程只走三个标准 stdio 通道,通过轮询父 PID 自检回收。

> App 端意义:远程会话里的终端命令不在 App 进程内执行,App 只消费**输出流事件**;长命令的"取消"语义由后端 broker 保证(杀整棵进程树),App 只需发取消请求。

---

## 3. Agent 循环:双层结构

### 3.1 外层:回合循环(`run-agent-step.ts`,`while (true)`)

```
while (true) {
  组装 stepPrompt → 本地估算上下文 token(countTokensMessages + system + tools)
  机械压缩(不调 LLM,只重写旧历史;预算 = f(当前模型))
  ① 有 handleSteps → 先跑一段程序化步骤(runProgrammaticStep)
  ② 声明了 outputSchema 但没 set_output → 注入系统消息强制重试(仅一次)
  ③ shouldEndTurn → break
  ④ 否则调一次 LLM(流式),解析出的工具调用逐个交给 executeToolCall
     模型调了 end_turn / task_completed → shouldEndTurn = true → 回到 ①
}
```

### 3.2 内层:handleSteps 生成器(`run-programmatic-step.ts`)

Agent 模板可带一个 JS 生成器函数,让"调 LLM"和"执行工具"按剧本交替。运行时用 `do..while` 驱动 `generator.next(...)`,按 yield 值分派:

| yield 值 | 运行时行为 |
|---|---|
| `'STEP'` | 暂停生成器,交回外层调一次 LLM;`stepsComplete` 经下次 `next()` 注入 |
| `'STEP_ALL'` | 停留在生成器模式,直到某步 `stepsComplete` |
| `{ toolName, input }` | **立即执行**该工具调用,结果在下次 `next()` 注入(`toolResult`) |
| `{ type: 'STEP_TEXT', text }` | 解析交错的文本+工具调用并顺序执行 |
| `{ type: 'GENERATE_N', n }` | 让 LLM 生成 n 个候选回复 |
| 生成器结束(done) | `endTurn = true` |

生成器状态不可序列化,按 `runId` 存进程内存(`runIdToGenerator`);字符串形式的 `handleSteps` 会被 `eval`——因此注册表里的可执行模板有**发布者信任门槛**(`sdk/src/agent-publisher-trust.ts`:不可信发布者的可执行模板拒绝加载,本地 `.agents` 与 SDK 内联定义不受限)。

### 3.3 官方剧本(base2 与 base-chat 同构)

```ts
handleSteps: function* ({ model, contextPruning }) {
  const compaction = ...   // 按模型取压缩策略
  while (true) {
    // 每一步 LLM 调用前,先内联跑一次上下文修剪器
    yield {
      toolName: 'spawn_agent_inline',
      input: { agent_type: 'context-pruner', params: { maxContextLength, ...compaction } },
      includeToolCall: false,          // 不出现在对话流里
    }
    const { stepsComplete } = yield 'STEP'
    if (stepsComplete) break
  }
}
```

**循环的本质 = 生成器剧本 + 每步前修剪上下文 + LLM 走一步 + 工具串行执行。**

### 3.4 上下文预算(为什么长会话不撑爆)

- 上下文窗口按模型登记(`CONTEXT_WINDOWS`),未登记模型取保守默认(131k);
- 预算 = 窗口 × **0.4**——本地 token 估算(GPT-4o tokenizer × 固定系数)系统性偏低,0.4 留足余量;
- `context-pruner` 子代理在每步前把旧历史摘要化;缓存过期触发一次,超预算再触发;
- token 计数始终本地估算(省掉计数 API 往返),回合结束才对根 agent 重算一次落库展示。

---

## 4. 对 App 的事件流契约(App 消费什么)

综合上述机制,App 对话页应把后端会话流理解为**有序事件流**:

| 事件 | App 行为 |
|---|---|
| `text` / 文本增量 | 追加渲染当前助手消息(Markdown) |
| `tool_call` | 渲染工具卡片(工具名 + 输入摘要;`includeToolCall: false` 的内部调用不会出现) |
| `tool_result` | 卡片收尾,展示输出摘要 |
| `error` | 展示归因文案(参数校验/权限/后端分类错误已自带中文语境) |
| `subagent-response-chunk` | 子代理流式输出(如 thinker 思考过程) |
| `end_turn` / 回合结束 | 消息落定;`suggest_followups` 的卡片在此之后渲染 |

排序保证:`tool_call` 与 `tool_result` 成对且按执行顺序到达;工具串行执行意味着**不会乱序**,App 按到达顺序渲染即可,无需自行排序或合并。

---

## 5. 与原生工程的对接映射

当前 `android/` 已实现的部分与本文机制的对应关系:

| 原生实现 | 对应后端机制 |
|---|---|
| `ChatRepository.chatStream`(SSE 增量) | 文本增量事件;未来扩展时按 §4 事件类型分发 |
| `ApiError` 分类 + `userMessage` | 后端 error 事件文案;解析失败/HTTP/超时与后端归因对齐 |
| `DefaultTools`(说明书 + schema + 参数体检)| §1 工具注册表(“定义 + 处理器”两半):定义那半就是给模型看的说明书,闭集用 enum、数值给上下界、必填只标工具推不出来的 |
| `ToolErrors` 错误信封 | §2.1 解析与容错 + “帮错误当 observation 回填”的业界共识:错误要可行、带示例(见 `docs/backend-integration.md` §3.3.5) |
| `ModelCatalogRepository`(网关 `/v1/models`) | 模型即 agent 运行时的 `model` 字段;目录接口返回的 id 与后端 `CONTEXT_WINDOWS` 表对应 |
| 会话标题/消息持久化(Room) | `messageHistory` 是后端权威;App 本地为缓存与离线展示,恢复会话以服务端为准 |

后续接入完整 agent 会话(而非单轮 chat/completions)时,需要新增:

1. **事件流解析器**:按 §4 表格扩展 SSE 解析,识别 `tool_call` / `tool_result` / `error` / `subagent-response-chunk`;
2. **工具卡片 UI**:至少覆盖 `run_terminal_command`(输出流 + 取消按钮)、`ask_user`(选项交互)、`write_todos`(清单);
3. **回合语义**:一轮用户输入对应一个 while 循环回合,期间多次 LLM 调用与多组工具调用;App 的"停止"映射为后端 abort(杀进程树 + 取消流)。
