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
