# Freebuff Mobile 后端接入设计(网络层与错误处理)

本文是原生工程(`android/`)接入真实后端的接口与错误处理约定,也是后续服务端/客户端联调的参考。

## 1. 分层

```
feature:chat / feature:settings        UI + ViewModel(只消费 ApiResult,不碰 OkHttp)
        ↓
core:data/repository                   业务仓库(会话/设置/自定义模型/目录/Git/版本)
        ↓
core:data/network                      FreebuffApi · GithubApi · ChatRepository · ApiResult
        ↓
OkHttp(超时/日志/可选跳过 TLS)           core:data/network/ApiClient.kt
```

约定:

- UI 层只读错误文案([ApiError.userMessage]),不解析异常类型之外的状态码逻辑。
- 所有阻塞式 HTTP 调用经 `apiCallIo { }` 进入 `Dispatchers.IO`,避免主线程网络访问(`NetworkOnMainThreadException`)。
- 流式对话用异步 `enqueue` + SSE 逐行解析(`ChatRepository.chatStream`),按增量发射文本片段。
- 流式带**空闲看门狗**(`idleWatchdog`):每收到一个事件重置计时,连续 120 秒(`DEFAULT_STREAM_IDLE_TIMEOUT_MS`)无任何事件则以 `ApiError.StreamIdle` 终止 —— 防「连接存活但服务端不吐数据」导致「正在生成」无限挂住;ChatViewModel 触发时保留已生成的部分回复并追加提示。`idleTimeoutMs <= 0` 可关闭(虚拟时钟单测用)。
- LLM 调用失败带**自动重试**(`RetryPolicy`):无输出的轮次遇瞬时性失败(超时/连接/DNS/流空闲/429/5xx)最多自动重试 2 次(指数退避 2s→4s,期间可被「停止」中断);本轮已有流式输出则不重试(避免重复文本),退避与重试次数经消息的 steps/time 展示在对话流中。
- 端侧带**工具权限层**(`ToolPermissions`):逐工具三级分级 —— `allow`(免确认静默执行)/ `confirm`(每次执行前弹窗,拒绝会作为错误结果回传给模型)/ `deny`(不执行,直接告知模型已禁用)。默认按副作用强度:只读工具(搜索/抓取/记忆读/计算)免确认,`save_memory` 需确认;设置页「模型 → 工具权限」可逐工具调整并持久化,未知工具名回退 `confirm`(宁可多问不静默)。弹窗可勾选「本次会话记住选择」(`SessionPermissionMemory`,纯内存不落库):同一工具在本会话内的后续调用免弹窗按记住的决定执行,会话结束自动失效,决策顺序为会话记忆 > 设置分级。

## 2. 统一结果与错误分类

`core:data/network/ApiResult.kt`

```kotlin
sealed interface ApiResult<out T> {
    data class Ok<T>(val data: T) : ApiResult<T>
    data class Err(val error: ApiError) : ApiResult<Nothing>
}
```

| 分类 | 触发条件 | 展示文案(节选) |
|---|---|---|
| `ApiError.Timeout` | `SocketTimeoutException` | 连接超时,请检查网络或端点可达性 |
| `ApiError.StreamIdle` | 流式空闲看门狗触发(连续 120s 无新事件) | 连接超时:超过 120 秒没有收到任何流式数据,已自动停止。请检查端点状态或换个模型再试 |
| `ApiError` 瞬时类自动重试 | `RetryPolicy.isRetryable`(Timeout/Unreachable/Dns/StreamIdle/429/5xx) | 最多 2 次自动重试,指数退避 2s→4s;确定性失败(鉴权/路径/参数/TLS/解析)不重试 |
| `ApiError.Dns` | `UnknownHostException` | 无法解析主机,请检查端点地址 |
| `ApiError.Unreachable` | `ConnectException` / `NoRouteToHostException` | 无法连接到服务器,请检查端点与网络 |
| `ApiError.Tls` | `SSLException` | TLS 证书校验失败,可开启「跳过 TLS 校验」 |
| `ApiError.Http(code)` | HTTP 非 2xx(保留状态码与响应片段) | 401 鉴权失败 / 403 无权限 / 404 端点不存在 / 429 限流 / 5xx 服务端错误 |
| `ApiError.Parse` | 响应可读但结构不符(字段缺失、非 JSON) | 响应格式无法解析:… |
| `ApiError.NotConfigured` | 网关/更新源/授权未配置 | xxx未配置 |
| `ApiError.Cancelled` | 调用方取消 | 请求已取消 |
| `ApiError.Unknown` | 其它异常 | 原始 message |

辅助函数:`toApiError(httpCode?)`、`httpErrorMessage(code)`、`getOrNull()`、`errorOrNull()`、`errorMessageOrNull()`、`fold()`。
`mapChatError()` 保留为兼容入口,内部等价于 `toApiError().userMessage`。

## 3. 接口清单

### 3.1 官方网关(FreebuffApi)

| 方法 | 请求 | 响应 | 失败处理 |
|---|---|---|---|
| 官方模型目录 | `GET {gateway}/v1/models`(base 不含 `/v1` 时补全为 `{origin}/v1/models`) | `{"data":[{"id":…}]}`,兼容 `models` / 纯数组 | 回退内置目录 `OFFICIAL_MODELS`,记录 `lastError` |

- 已知 id 复用内置展示元数据;未知 id 由 `officialModelsFromIds()` 自动生成名称/徽标(`Catalog.kt`)。
- 网关地址未配置时不发请求、不报错,目录固定为内置。
- 目录为单例 `StateFlow`,首屏即用内置目录,拉取成功后热替换(`ModelCatalogRepository.refreshIfNeeded/refresh`)。
- UI:`ModelSheet` 顶部显示「官方模型 · 网关实时 / 内置目录」并可手动刷新;`ChatViewModel.catalogError` 暴露失败原因。

### 3.2 GitHub(GithubApi)— OAuth 设备流

选设备流的原因:无需自定义 scheme 回调与后端中转,只需一枚 OAuth App 的 `client_id`。

| 步骤 | 请求 | 说明 |
|---|---|---|
| 1 申请设备码 | `POST https://github.com/login/device/code`(form: `client_id`,`scope`) | 返回 `device_code` / `user_code` / `verification_uri` / `interval` / `expires_in` |
| 2 展示授权 | — | `GitConnectStep.AwaitingUser(userCode, verificationUri)`,UI 自动拉起浏览器并展示用户码 |
| 3 轮询换 token | `POST /login/oauth/access_token`(`grant_type=urn:ietf:params:oauth:grant-type:device_code`) | `authorization_pending` → 继续;`slow_down` → 加长间隔;`expired_token` / `access_denied` → 失败 |
| 4 校验账号 | `GET https://api.github.com/user` | `login` / `name` 写入 `GitState` |
| 5 读取仓库 | `GET /user/repos?per_page=100&sort=updated&affiliation=owner,collaborator,organization_member` | 映射为 `RepoItem(full_name, default_branch, description)` |

- access_token 经 `CryptoManager`(Android Keystore, AES/GCM)加密后落库,`revoke()` 清空。
- 未配置 `client_id` 时 DI 选择 `DemoGitAuthRepository`,阶段协议一致,UI 无分支。
- 授权流程可取消(取消收集即中止轮询);超时按 `expires_in` 判定并提示重新发起。
- 仓库读取失败在发起任务向导中展示原因并提供「重试」,不静默失败。

### 3.3 对话(ChatRepository)

`POST {endpoint}`(`stream=true`,OpenAI 兼容),按 SSE **帧**解码(`SseDecoder`)后交给 `AgentEventParser`。
非 2xx 抛出 `ApiError.Http(code, snippet)`;ChatViewModel 将分类文案写回该条消息(不再显示原始异常)。

#### 3.3.1 流解析适配(对着开源生态踩过的坑逐条补)

| 现象 | 处理 | 依据 |
|---|---|---|
| 一个事件的 JSON 被拆到多条 `data:` 行 | `SseDecoder` 按 SSE 规范累积到空行才产出 | openai-python SSEDecoder「不到空行不产出事件」 |
| 末尾事件没有空行收尾 | 流结束 `flush()` 一次 | pi#9047(漏 flush 会丢收尾事件) |
| `:` 心跳、`event:`/`id:`/`retry:` 字段 | 忽略;`data:` 后只去掉一个空格 | SSE 规范 |
| NDJSON(无 `data:` 前缀的裸 JSON 行) | 整行当载荷 | Ollama 原生等端点 |
| `delta.tool_calls` **没有 index**(Gemini 兼容层) | 有 index 用 index,没有按 `id` 分槽,都没有归到最后一次调用 | hermes-agent#62937(按下标归并会把并行调用合成一个) |
| 无参工具的 `arguments` 是空串 | 归一为 `{}`,仍能执行 | vercel/ai#6687(否则该调用被当未完成、工具永不执行) |
| `arguments` 是对象/数组而非字符串 | 序列化后照常解析 | 个别网关行为 |
| 名称/参数分片里**整段重发** | 相同跳过、以前缀开头则整体替换(避免 `web_searchweb_search`) | crush#3153 |
| 参数带栅栏/尾逗号/单引号/前后夹带文字 | `JsonArgs.repair` 逐级修复;修不好回「参数不是合法 JSON…请重试」给模型(可自愈) | 模型手写 JSON 的常见瑕疵 |
| HTTP 200 里夹 `{"error": …}` 帧 | 转 `AgentEvent.Failure` 展示 ⚠ | 否则「流在跑但什么都不吐」直到空闲看门狗 |
| 遗留 `delta.function_call`(单数) | 支持;`finish_reason=function_call` 同 `tool_calls` 收尾 | 旧协议仍被不少网关实现 |
| `content` / `reasoning_*` 是数组部件 | 按 `{type,text}` 拼接后再取文本 | Anthropic 兼容网关 |

#### 3.3.2 请求侧适配

- **空 content 不发**:只有工具调用、没有正文的 assistant 消息不写 `content` 字段(严格实现会因 `content:""` 400)。
- **thinking 与工具的冲突**(Anthropic 兼容端点):回传带 `tool_calls` 的 assistant 消息时必须同时回传 `thinking_blocks`,
  而 Chat Completions 协议里没有这个字段 —— 历史里已有带工具调用的 assistant 消息时,本轮**丢弃 `thinking` 参数**
  (与 LiteLLM `modify_params` 同款处理;仅 [ReasoningFlavor.THINKING_BUDGET] 这类端点)。
- **降级重发一次**(`RequestFallbacks`):端点明确拒绝工具定义/思考参数时,去掉该字段重发一次并提示用户
  (「该端点不接受工具定义,已按纯对话继续」),本会话后续轮次不再带 tools;只认「错误文本指向该字段」,其余 400 照常报错。
- 思考参数只送模型所属族认得的字段(见 `Reasoning`),对不认识的模型一个字段都不加。

#### 3.3.3 验证

`verify/mock_variants.py` 是按「最后一条 user 消息里的 `case xxx`」切场景的变体 mock,覆盖上表每一项并在
服务端打印 `PASS/FAIL`;设备端用 `verify/send_case.py <case>` 发一条消息后 grep 日志即可回归。

成规模的回归用「场景矩阵」:`verify/mock_matrix.py` 在 `127.0.0.1:8899` 起 mock(按 `case xxx` 切 29 个场景),
`verify/matrix.py` 逐场景在真机上跑完一轮并把断言写进 `verify/matrix_report.md`;个别场景需要看库才能判定
(如「工具后只回文本要被提醒续跑」),用 `verify/recheck.py <case> <期望文本>` 盯库复验,不依赖界面空闲。
矩阵支持断点续跑:结果逐场景落 `verify/matrix_state.jsonl`(进程被杀不丢进度),全量重跑时已 PASS 的直接沿用、
只补没过的;点名场景永远实跑,`--fresh` 全部重跑。长会话回归见 `verify/longsession.py`(结果见 `docs/context-engineering.md` §5)。

#### 3.3.4 回合循环与对话体验(全部由真机实测暴露,规则写在 `core:model`)

延续策略在 `AgentLoop`(纯函数),状态牌/文案在 `MsgSteps`,正文清洗在 `OutputSanitizer`。

| 实测问题 | 处理 | 依据 |
|---|---|---|
| 只回文本的「进度播报」被当成答完(说两句就停) | 提醒一次继续,最多 3 次;模型调 `task_completed`/`end_turn` 才算完成 | Cline `attempt_completion`、OpenHands `FinishTool` |
| 硬轮次天花板 6 轮早停 | 上限提到 30,且只当兜底;打到上限时正文注明「回复继续可以接着做」 | Codex CLI / OpenHands(默认 100 轮) |
| 同一组工具被整轮重放(实测 8 轮 ×3 次抓取、界面永远「正在生成」) | `ToolRepeatTracker`:签名 = 工具名 + 归一化参数(只忽略字符串外的空白);重复调用**复用上次结果不重跑**,连续 2 轮整轮重复就停;卡片标「↺ 复用」 | Cline 重复调用检测、AutoGPT 结果复用 |
| 重放时正文被追加多遍 | 本轮正文与上轮逐字相同则回滚这次追加 | — |
| 停下来时正文一个字都没有 | 停因提示附带「最近一轮的执行结果」摘要(`MsgSteps.digest`) | — |
| 提醒消息插在历史中间 | 角色用 **user** 并加「(系统提醒)」前缀:中途插 `system` 各家网关处理不一(llama.cpp 直接忽略) | Cline / OpenHands / Codex CLI 都是当用户回合发 |
| 按下「停止」后留下一个完全空白的回复 | 双收尾路径(用户停止 / 循环内发现被中断)都写**幂等**的「(已停止生成)」;已有正文则加尾标注 | 实测两条路径执行顺序不定(相差 30ms) |
| 模型什么都没回且无工具卡片 | 写入「(模型没有返回内容;可直接重试,或换一个模型)」 | — |
| 端点把 chat 模板控制词当正文漏出来(实测 `<\|eos\|>` 出现在回答末尾) | `OutputSanitizer`:去掉 `<\|token\|>` 形式;流式边收边洗 + 收尾再洗一遍(兼顾被拆到两个 chunk 的半个词) | 各家模板保留词,不会出现在正常文本/代码里 |
| 长回复看不到正在写的内容(旧实现只在消息条数变化时 `animateScrollToItem(末条)` —— 跳到条目**顶部**,末条比屏幕高时新内容全在屏幕下方) | 贴底才跟随:用户在底部才自动滚,上滑看历史不打扰;滚到**列表末尾**;新消息平滑、流式增长即时 | 主流聊天客户端行为 |
| 模型幻觉出一个没注册的工具(实测 `browse_web`):旧逻辑落到权限兜底 `FALLBACK=CONFIRM`,每轮都弹确认框、循环被点「允许」推着走 | `DefaultTools.isKnown` 闸门:`dispatchTool` 对未注册的名字**直接回带近邻建议的错误信封**(不弹确认);闸门看的是**当前可用**清单,关掉记忆能力后 `save_memory` 也不再被认作可用 | 工具注册表就是唯一真相,执行不了的东西不该让用户批准 |
| 工具报错没有处方(旧:「缺少参数 query」「工具执行失败:xxx」):模型只能原地重发同样的调用,或干脆卡住 | 统一**工具错误信封**(`ToolErrors`):固定形状 `[工具错误] 类型=<中文名>/<TAG> · 工具=<name>` + `问题:` + `怎么改:`(+ 正确调用示例);同一工具连续失败第 2 次起追加「换路」提醒(有界自纠) | Anthropic《Writing effective tools for agents》error-response 一节、Claude 工具文档的 `is_error` 说明、MCP 工具设计规范 |
| 说明书太薄(一句话 + 参数名):弱模型自己编参数名与取值(把 `path` 写成 `file`、`limit` 写「很多」) | 说明书 = 做什么 / 何时用、何时改用别的 / 返回什么 + 一句真实调用示例;闭集用 `enum`、数值给 `min`/`max`、可选参数给 `default`、常见别名一并接受;执行前 `DefaultTools.validate` 参数体检(未知参数名 / 缺必填 / 越界 / 不在闭集),问题同样以信封回传 | 同上 + OpenAI strict schema 实践(用 enum 防编造) |
| 进程被杀/崩溃后,那条写到一半的消息永久挂在「连接模型并开始生成…」上,看着像还在生成;空正文还会作为一条空助手轮进入后续上下文 | 冷启动修复 `SessionRepository.repairAbandonedMessages`(进程刚启动时库里不可能有真在写入的消息,此刻修最安全):空正文→「(上次生成被中断,可重新发送)」、清过程性步骤、把 running/waiting 的工具卡片标成未完成;纯逻辑在 `MsgSteps.repairAbandoned`,App 启动时后台跑一次。扫多少:首次(本功能上线后第一次冷启动)全量扫一遍(老版本写坏的半成品可能已被后续消息压在历史中间),之后只扫每条会话**末尾一条** —— 被杀只可能把最后一条留在半途,不必每次启动都解析全库 JSON(标记写在 settings 的 `abandonedRepairSwept`) | App 启动即修复「半成品」,是各家客户端的常规做法(崩溃恢复) |

设备实测要点(可重跑):`adb shell run-as com.freebuff.mobile cat databases/freebuff.db*` 拉库核对正文/步骤,
`verify/drive.py`+`verify/flow.py` 驱动 UI、`verify/ime.py` 用无界面 IME 输入中文;
重复调用场景可用真实端点复现(日志 `repeat sid=… dup=3/3 consecutive=N`)。
注意:`verify/flow.py` 的 `type` 走 `adb shell input text`,几十个字符以上会被吞尾巴(实测 57 字符只落地 40);
输入长文本要用 `verify/ime.py`(AdbKeyboard `commitText`,不过键盘输入通道,字节完整)。

#### 3.3.5 工具错误注入与「说明书」(给弱模型的契约)

工具报错是模型唯一的纠错信号。这里的做法可以概括成三句话:**说明书要写全,参数先体检,报错给处方**。

1. **说明书**(`core:model/Tools.kt` 的 `AgentTool`):做什么 / 何时用、何时改用别的 / 返回什么,
   再附一句**真实调用示例**;能枚举的取值写 `enum`(如 `save_memory.block` 只能 persona/user/project,
   子代理类型只能 researcher/code_reader/analyst),数值参数给 `min`/`max`/`default`(limit 1~10、top_k 1~20),
   常见别名一并接受(`file`/`filename` → `path`,`q` → `query`,`expr` → `expression`)。
   说明书与 schema **每轮都随请求注入**,不靠模型记。
2. **参数体检**(`DefaultTools.validate` / `prepare`):未知参数名、缺必填、类型不对、越界、取值不在闭集 ——
   在**执行前**拦住并归一(字符串数字 `"7"` 当整数收,`"User"` 对齐成 `user`);体检先说「参数名写错」
   再说「缺什么」。一次调用查出多条问题时**合并进一张信封**:问题与处方逐条编号(①②③)全讲完 ——
   只讲第一条会浪费轮次,弱模型改完第一个错,下一个请求还会撞上第二个错。
3. **错误信封**(`core:model/ToolErrors.kt`),固定形状,模型可学、测试可断:

```
[工具错误] 类型=缺少必填参数/MISSING_PARAM · 工具=github_get_file
问题: 必填参数 path 没有给
怎么改: 补上 path 后重试一次。参数清单: owner:string、repo:string、path:string、ref:string(可选)
正确调用示例: github_get_file({"owner": "CodebuffAI", "repo": "freebuff", "path": "README.md"})
```

多条问题合并成一张信封的形状(单条问题时保持上面的原样,不编号):

```
[工具错误] 类型=缺少必填参数/MISSING_PARAM · 工具=github_search_repositories
问题: ① 参数名 mode 不存在(可能你想用的是别的名字) ② 必填参数 query 没有给 ③ 参数 limit 的取值「99」超出范围
怎么改: ① 删掉未声明的参数「mode」,只用声明过的参数。 ② 补上 query 后重试一次。 ③ limit 取值需在 1~10 之内。参数清单: …
```

分类(`Kind`)覆盖:参数不合法 / 缺参数 / 参数名不存在 / 取值非法 / 工具不存在 / 资源没找到 / 鉴权 / 限流 /
网络 / 用户不允许 / 重复调用 / 内部错误 / **工具已被端上暂停(CIRCUIT_OPEN)**。HTTP 状态码分开给下一步:
401·403 别重试、404 先核对名字(工具特有提示如「先用 `github_search_repositories` 搜仓库名」)、
429 等一会儿且**别连续重试**、5xx 可重试一次。
同一工具**连续失败第 2 次**起自动追加「不要再发同样的调用 —— 换参数、换工具,或直接告诉用户卡在哪里」
(`ToolErrors.escalateIfNeeded`,执行器统一兜底,保证这条不变式不靠每个分支自觉)。
**第 3 次失败起端上直接熔断**(`ToolErrors.BREAK_AT`,`ChatViewModel.dispatchTool` 闸门):这次调用**不执行**,
直接回 `CIRCUIT_OPEN` 信封 —— 列出可用的替代工具、说明「下一条新消息会恢复」。收工类工具(end_turn/
task_completed)豁免:它们是循环唯一的出口,熔断它们反而会锁死循环。失败计数每条消息开始时清零
(断路器 half-open:失败/熔断都不跨消息,新消息自动恢复)。为什么要有:实测「劝换路」对弱模型不够,
它还会重发同一调用 —— 把浪费变成一次明确的拒绝,模型只能真的换路。
完全重复的调用(结果被复用没重跑)也带一句「不要重发」——实测模型重放同一组工具是主要循环来源之一。

**怎么让弱模型学会**:`ContextBuilder` 在附带工具定义的轮次里注入一段「工具使用约定」(不带工具时不注入,
免得诱发幻觉调用):参数只写说明书里的名字、结果以 `[工具错误]` 开头就照「问题 / 怎么改」修正后**重试一次**、
同一工具连续出错两次就换工具或直接说明、工具名只能用清单里的。
真机验证(2026-09-27,`case mergerr`,grok-4.7):一次带三个自造参数(mode/sort/lang)调 `calculator`,
端上回 ①②③ 合并处方式信封(每条问题带各自改法 + 参数清单 + 示例);模型下一轮删掉全部自造参数、
保留合法 `expression`,**一次改对并执行成功**(`(12+8)*3.5 = 70`)。注意:合并只发生在 schema 级错误之间
(自造参数/缺必填/越界/枚举),calculator 的表达式解析失败发生在执行层 —— 体检先行,两类错不会同框;
记忆类工具(save_memory/memory_recall)走端侧专用分支,自己报错、不进体检信封。

三者配合的效果:报错→改对→跑通,而不是报错→重发→再报错。

回归位置:`ToolErrorsTest`(信封形状/单条与合并处方/升级/熔断信封/近邻建议/HTTP 处方)、`ToolsTest`
(说明书有示例且用真名字、闭集与上下界进 schema、体积预算、别名/类型/越界/枚举体检)、`ToolExecutorsTest`
(别名救回、自造参数被拦、多问题合并进一张信封、连续失败升级)、`ContextBuilderTest`(工具使用时注入约定、
不带工具时不注入)。

真机实测(2026-09-26,`case argerr`):模型带一个自造参数 `mode` 调 `calculator` → 端上回信封
`[工具错误] 类型=参数名不存在/UNKNOWN_PARAM · 工具=calculator / 问题: 参数名 mode 不存在…` →
mock 断言「信封有处方」PASS → 改对的调用算出 `6*7 = 42` →「按建议改对后执行成功」PASS。
说明书体积:带工具的轮次每轮注入 **5095 字符**(11 个工具,含示例/枚举/默认值;mock 日志 `tools_chars=`),
系统提示里的「工具使用约定」约 150 字符(`system_chars` 561 → 683)。
注意:加字段只加 JSON Schema 的常规关键字(enum/minimum/maximum/default),**不开 `strict` 模式** ——
严格端点对未知模式标志会 400;实测真实网关(OpenAI 兼容)接受这套 schema 并正确调工具。

#### 3.3.6 场景矩阵回归结果(真机 29 场景)

`verify/mock_matrix.py` + `verify/matrix.py` 的 29 个场景(以 `verify/matrix.py` 的 `CASES` 为准):
`emptyargs` 无参工具仍要执行 / `parallel` 一次两个无 index 调用按 id 分槽 / `multiline` 事件跨两条 data 行 /
`badargs` 参数栅栏·单引号·尾逗号修复后执行 / `errframe` HTTP 200 里夹错误帧 / `legacy` 遗留 `function_call` +
末尾无空行 / `thinking` Anthropic 思考参数规则 / `single` 一次工具 + 正文 + 显式收工 / `progress` 只回文本的
进度播报要续跑 / `unknown` 未注册工具(幻觉 `browse_web`)要把错误结果回给模型 / `toolerr` 工具自身失败要回传 /
`repeatcall` 同一调用重放要复用并连续 2 轮停 / `cap` 每轮换参数打到 30 轮上限 / `http500` 重试后成功 /
`http401` 不可重试直接报错 / `trunc` 流被截断不能卡死 / `empty` 空回复端上给提示 / `memsave` 核心记忆落库 /
`reason` `reasoning_content` 落库并折叠展示 / `longtext` 长正文全文到达 / `thinkreject` 端点拒思考参数后去字段重发 /
`notools` 端点拒 tools 后去字段重发并提示(必须放最后:本会话后续请求都不再带工具)/ `long` 长会话压测
(24 轮工具调用+收工,端上连续运行 rounds≥25)/
`argerr` 自造参数名 → 错误信封给处方,按建议改对后自愈 /
`killstart` 流开零字节被杀 → 正文修成「(上次生成被中断,可重新发送)」/ `killmid` 半途正文被杀 →
正文保留、过程性步骤清干净 / `killtool` 确认弹窗等待时被杀 → waiting 卡片标未完成 / `breaker` 同一工具连败三次 →
端上熔断拒绝执行(CIRCUIT_OPEN),不再只劝模型 / `mergerr` 一次带三个自造参数 →
①②③ 合并处方,照处方改对后一次执行成功。

kill 三连单独成组(`python verify/matrix.py killstart killmid killtool`),断言四段:中断半成品形态 →
冷启动自动修复 → 幂等(再冷启动一次形态逐字不变)→ 进会话看界面无陈旧步骤。制造中断:killstart/killmid 用
mock 的 chunked **永不结束流**(收下请求只写分块、不发终止块)+ `am force-stop`;killtool 用确认级工具
`save_memory` —— 权限层把卡片写成 `waiting` 并弹「工具执行确认」,端上就停在这一步等用户点,此时杀进程,
半成品形态完全确定、不依赖任何网络时序。两个实测发现,后来都成了场景设计的一部分:工具卡片只在**流结束时**
由 `AgentEventParser.flush()` 落库,永不结束的流永远产生不了 running 卡片(「工具执行中被杀」那条路走不通,
第一版用 `web_fetch` 挂 `/hang` 就是这样失败的);mock 的 `rounds` 按 case 全局计数,kill 类剧本必须**每轮同款**
(不看 rnd),否则第二次一键重跑时新会话拿到 rnd>1、只剩收尾剧本,waiting 卡片再也造不出来。
证据:killstart/killmid/killtool 连续两轮 3/3 PASS(`verify/matrix_report.md`),修复形如
正文保留 + 卡片 `[save_memory, error, (未完成)]` + 步骤清空,冷启动日志 `FreebuffStartup: repaired 1 interrupted message(s)`。

- 全量(2026-09-28,29 场景):**29/29 PASS**(断点续跑合并报告,历史沿用 26 + 本轮实跑 thinking/progress/long)。
  此前 2026-09-27 口径 25/28,三个 FAIL 复查后修掉:`thinking` 挖出**真产品缺陷** —— `enable_thinking` 与
  `thinking` 同款冲突,回传带 tool_calls 的 assistant 消息那轮再声明开启思考,严格网关直接 400
  (grok-4.7 真机抓到;`ApiClient` 已修:两个 flavor 共用 `thinkinglessToolCall` 守卫,584 用例全绿);
  `progress`/`long` 是矩阵口径:settle 改锚定 `ChatViewModel` 的操作级日志(`op begin/finish/failed/cancelled
  sid=…`,finish 行带 `rounds=N`,必须是本轮新出现的行 —— 共享会话里同一 sid 有历史 finish),
  logcat 断言改 `logcat -d -s ChatViewModel` 定向读(不再被全量长跑稀释),`long` 重写为
  24 轮 calculator(每轮算式必须不同 —— `current_time` 无参、每轮同参会被端上重复调用检测正确掐断)+ 收工。
  历史口径:20/22(23 场景时代)。
- 换成 `claude-3-7-sonnet` 重跑:`thinking`/`thinkreject` 均 PASS(Anthropic 规则在真机成立:首轮送
  `think=['thinking']`,带工具历史那轮不送)。
- `progress` 用 `recheck.py` 盯库复验 PASS:正文为三段进度播报,logcat 里 `nudge #1 → round 3 → op finish rounds=3`,
  即「动过工具后只回文本」被提醒续跑,而不是当成答完。

### 3.4 自定义模型(CustomModelRepository)

- 测试连接:最小 body `POST` 校验连通与鉴权 → `ProbeResult.Success(ms)` / `Fail(reason, code)`。
- 模型快照:`GET {origin}/v1/models`;连接正常但快照刷新失败时保留旧快照,并以 `ProbeResult.Success.note` 提示原因。
- API Key 与自定义请求头按记录加密存储;`skipTLS` 仅对显式开启的模型生效。

### 3.5 版本检查(UpdateRepository)

`GET {updateUrl}` → `{version, notes[], url}`;未配置 → `NotConfigured`;非 2xx → `Http`(带 `HttpTarget.UpdateSource`,
404 文案指向更新源而非模型端点);非 JSON / 缺 `version` → `Parse`。

默认更新源是本仓库公开的 `dist/update.json`(raw.githubusercontent.com 直链,匿名可读),发版时由
`.github/workflows/release.yml` 用 `scripts/update-manifest.py` 重建并回推 `main`,随后从 GitHub API + raw
两条路径自证线上版本已对齐;CI(`android.yml`)用同一脚本校验清单版本不高于产品版本。

`UpdateSheet` 分「检查中 / 有更新(列出说明 + 前往下载)/ 已是最新 / 失败(可重试)」四态;
版本显示与实际比较都用 `BuildConfig.VERSION_NAME`(源于 `android/version.properties`),App 不自行安装 APK。

## 4. 配置项

`app/build.gradle.kts`(`buildConfigField`,经 `AppModule` 以 `@Named` 注入,feature/core 不直接引用 BuildConfig):

| 配置 | 作用 | 留空行为 |
|---|---|---|
| `DEFAULT_GATEWAY_BASE_URL` | 官方网关根地址(如 `https://api.example.com/v1`) | 官方目录用内置列表;官方模型对话提示未配置 |
| `GITHUB_OAUTH_CLIENT_ID` | GitHub OAuth App 的 client_id | Git 账号回退演示实现 |
| `UPDATE_URL` | 版本检查 JSON 地址(默认指向本仓库公开的 `dist/update.json`) | 仅当显式改成空串时提示未配置 |

## 5. 失败与回退策略

| 场景 | 行为 |
|---|---|
| 官方目录拉取失败 | 保留内置目录 + 面板提示失败原因,可重试 |
| 网关未配置 | 不请求、不报错,目录固定内置 |
| Git 未配置 client_id | 授权直接以失败阶段结束,弹层内就地提示未配置 |
| Git token 失效(401) | 分类文案「鉴权失败(401)」,提示重新关联 |
| 自定义模型快照刷新失败 | 连接仍视为成功,提示快照未更新并保留旧快照 |
| 更新源未配置 | 明确提示未配置,而非「已是最新」 |
| 更新源不可达 / 404 | 分类文案 + 实际请求的主机名,可重试;不会报成「已是最新」 |

## 6. 测试覆盖(`core:data` JVM 单测,MockWebServer)

- `ApiErrorTest`:异常→分类映射、HTTP 文案、`apiCall`/`apiCallIo`、`ApiResult` 辅助函数
- `FreebuffApiTest`:请求路径(`/v1/models` 补全)、三种响应结构解析、未配置不发请求、5xx→`Http`、空列表→`Parse`
- `GithubApiTest`:设备码申请(表单字段)、`pending`/`slow_down`/`granted`/`denied`、`/user`、`/user/repos` 映射(含私有仓库描述回退)、401 分类
- `ModelCatalogRepositoryTest`:初始内置、未配置不请求、成功替换、失败回退并记录错误、自动刷新仅一次
- `UpdateRepositoryTest`:解析、未配置、5xx、非 JSON、缺 version
- `core:model`: `CatalogTest`(id 映射/去重/美化/徽标)、`UrlsTest.modelsUrl`
