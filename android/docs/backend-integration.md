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

`POST {endpoint}`(`stream=true`,OpenAI 兼容),按 SSE 解析 `choices[0].delta.content`,兼容 `text` / `message.content`。
非 2xx 抛出 `ApiError.Http(code, snippet)`;ChatViewModel 将分类文案写回该条消息(不再显示原始异常)。

### 3.4 自定义模型(CustomModelRepository)

- 测试连接:最小 body `POST` 校验连通与鉴权 → `ProbeResult.Success(ms)` / `Fail(reason, code)`。
- 模型快照:`GET {origin}/v1/models`;连接正常但快照刷新失败时保留旧快照,并以 `ProbeResult.Success.note` 提示原因。
- API Key 与自定义请求头按记录加密存储;`skipTLS` 仅对显式开启的模型生效。

### 3.5 版本检查(UpdateRepository)

`GET {updateUrl}` → `{version, notes[]}`;未配置 → `NotConfigured`;非 2xx → `Http`;非 JSON / 缺 `version` → `Parse`。
`UpdateSheet` 分「检查中 / 有更新 / 已是最新 / 失败(可重试)」四态。

## 4. 配置项

`app/build.gradle.kts`(`buildConfigField`,经 `AppModule` 以 `@Named` 注入,feature/core 不直接引用 BuildConfig):

| 配置 | 作用 | 留空行为 |
|---|---|---|
| `DEFAULT_GATEWAY_BASE_URL` | 官方网关根地址(如 `https://api.example.com/v1`) | 官方目录用内置列表;官方模型对话提示未配置 |
| `GITHUB_OAUTH_CLIENT_ID` | GitHub OAuth App 的 client_id | Git 账号回退演示实现 |
| `UPDATE_URL` | 版本检查 JSON 地址 | 版本检查提示未配置 |

## 5. 失败与回退策略

| 场景 | 行为 |
|---|---|
| 官方目录拉取失败 | 保留内置目录 + 面板提示失败原因,可重试 |
| 网关未配置 | 不请求、不报错,目录固定内置 |
| Git 未配置 client_id | 演示实现(阶段协议一致) |
| Git token 失效(401) | 分类文案「鉴权失败(401)」,提示重新关联 |
| 自定义模型快照刷新失败 | 连接仍视为成功,提示快照未更新并保留旧快照 |
| 更新源未配置 | 明确提示未配置,而非「已是最新」 |

## 6. 测试覆盖(`core:data` JVM 单测,MockWebServer)

- `ApiErrorTest`:异常→分类映射、HTTP 文案、`apiCall`/`apiCallIo`、`ApiResult` 辅助函数
- `FreebuffApiTest`:请求路径(`/v1/models` 补全)、三种响应结构解析、未配置不发请求、5xx→`Http`、空列表→`Parse`
- `GithubApiTest`:设备码申请(表单字段)、`pending`/`slow_down`/`granted`/`denied`、`/user`、`/user/repos` 映射(含私有仓库描述回退)、401 分类
- `ModelCatalogRepositoryTest`:初始内置、未配置不请求、成功替换、失败回退并记录错误、自动刷新仅一次
- `UpdateRepositoryTest`:解析、未配置、5xx、非 JSON、缺 version
- `core:model`: `CatalogTest`(id 映射/去重/美化/徽标)、`UrlsTest.modelsUrl`
