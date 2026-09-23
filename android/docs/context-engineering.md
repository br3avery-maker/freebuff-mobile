# 上下文工程与记忆:设计参考

本文说明原生 App 的「LLM + 工具 + 循环」运行时如何做上下文压缩与记忆,以及各设计点借鉴的开源方案。实现代码:`core/model/ContextPolicy.kt`、`core/model/Memory.kt`、`core/data/context/ContextBuilder.kt`、`core/data/repository/MemoryRepository.kt`、`feature/chat/ChatViewModel.kt`。

## 1. 总体架构

```
sendMessage(text)
   │
   ▼
┌─────────────── agent 循环(最多 6 轮)───────────────┐
│  buildHistory(sessionId)                            │
│    ├─ MemoryRepository.load()   ← Room memories 表  │
│    ├─ ContextBuilder.build(                         │
│    │     memoryBlocks,     // Letta 核心记忆块      │
│    │     session 消息,     // Room messages         │
│    │     ContextBudget(     // 按模型 ctx 窗口算    │
│    │       parseCtxWindow), │                     │
│    │     llmSummarizer)     // LibreChat 式摘要     │
│    │   → system(记忆+人设) [+摘要] [近端消息…]     │
│    └─ + protocol 跨轮累积(assistant/tool 消息)      │
│  runStreamRound() → SSE → 事件                      │
│  有 tool_calls? → 执行(save_memory/外部工具)       │
│    → 结果压缩 → 回传 → 下一轮                       │
└─────────────────────────────────────────────────────┘
```

每轮请求都**重建**上下文:工具结果落库(工具卡片)后自动出现在下一轮;记忆块始终注入 system;预算超限时自动压缩。

## 2. 借鉴映射

| 机制 | 借鉴来源 | 本工程落点 |
|---|---|---|
| 核心记忆块(命名块常驻 system,agent 自编辑) | Letta/MemGPT core memory blocks | `MemoryBlock`(persona/user/project)+ `save_memory` 工具 + Room `memories` 表 |
| 会话摘要压缩(超限把早期消息换摘要) | LibreChat conversation summarization;Cline auto-compact | `ContextBuilder.build` 裁剪 + `llmSummarizer`(非流式摘要调用) |
| 摘要失败的降级 | LibreChat 提取式回退 | `ContextPolicy.extractiveSummary`(首条用户请求 + 最新进展) |
| 工具结果有损压缩(保留首尾) | Cline 机械压缩思想 | `ContextPolicy.compressToolResult`(单条 1200 token 上限) |
| token 预算 = 窗口 − 输出预留 | OpenAI/Anthropic 常规实践 | `ContextBudget.usableTokens`(reserveOutput=4096) |
| 上下文窗口声明 | 各模型 metadata | `CustomModel.ctx`("128k"/"1m"/"200000")→ `parseCtxWindow` |
| Focus Chain(任务焦点记忆) | Cline Focus Chain | `project` 记忆块(模型用 save_memory 维护当前任务焦点) |

## 3. 关键规则

- **永不丢弃**:system(含记忆块)与 `[早期对话摘要]` 消息在兜底淘汰中受保护。
- **摘要只做一次**:被裁段生成一条摘要消息,不逐轮重复压缩;下轮重建时被裁段不变则摘要内容一致(确定性:同输入同输出,LLM 摘要除外)。
- **工具结果双重压缩**:执行后回传给模型前压一次(`ContextPolicy.compressToolResult`),历史序列化进上下文时再按上限压一次。
- **save_memory 由能力开关控制**:设置页「上下文记忆」关闭时,工具列表移除 `save_memory`(`DefaultTools.forCapabilities`),记忆块也不注入——模型不会看到不可用的工具。
- **记忆限额**:单块默认 600 字符,超限截断并提示模型用 save_memory 精炼;save_memory 追加超限时保留头部 + 追加段。
- **估算误差**:token 估算(中文 1 token/字,英文 0.3/字符)只用于预算决策,误差由「保守上界 + 预留 4k」吸收。

## 4. 测试

- `ContextPolicyTest`:估算/窗口解析/压缩/预算
- `MemoryTest`:编解码往返/prompt 渲染/限额/save_memory 参数/能力过滤
- `MemoryRepositoryTest`:播种/覆盖/追加压缩/清空(内存假 DAO)
- `ContextBuilderTest`:记忆注入/工具结果压缩/裁剪+摘要/LLM 摘要与失败回退/空会话
