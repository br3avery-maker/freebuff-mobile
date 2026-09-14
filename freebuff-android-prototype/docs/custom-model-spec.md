# 自定义模型 · 字段与 URL 规范化规范(原生工程参考)

> 来源:freebuff-android-prototype 原型实现(js/app.js 的 STR.integ 与表单逻辑)
> 状态:概念原型 v0.2.0 · 演示为仿真,不发起真实请求
> 用途:作为后续原生 Android(或其它端)「添加自定义模型」的接口与交互设计基线

## 1. 定位

用户可把**自托管 / 中转站**的 OpenAI 兼容端点接入 Freebuff,作为对话模型使用。
端点写法必须是 OpenAI 兼容(`/v1/chat/completions`),但允许两种输入方式:

1. **Base URL**(不含方法路径),如 `https://api.example.com/v1`
2. **完整请求地址**(中转站常用,以 `/chat/completions` 结尾),粘贴后原样使用

表单在输入框下**实时预览完整请求路径**,让用户始终知道实际请求的 URL。

## 2. 数据模型 CustomModel

```ts
interface CustomModel {
  id: string;     // "cm-" + 随机 id,稳定主键
  name: string;   // 显示名称(必填)
  apiId: string;  // 模型 ID,如 deepseek-chat / qwen2.5-coder(必填)
  base: string;   // 规范化后的地址:含协议;若粘贴完整路径则保留完整路径(见 §4)
  key: string;    // API Key(可选;原型仅本机明文演示)
  ctx: string;    // 上下文长度:8K | 32K | 128K | 524K(默认 32K)
  timeout: string;// 请求超时:30 秒 | 60 秒 | 120 秒 | 300 秒(默认 60 秒)
  skipTLS: boolean; // 关闭 TLS 证书校验(自建端点证书异常场景)
  headers: string;  // 自定义请求头,每行一条 "头名: 值"(可选)
  models: string[]; // /v1/models 拉取快照(上次测试连接);再次编辑时直接展示
}
```

- 持久化:原型存本机 `localStorage`(`freebuff_proto_v1` → `customModels[]`)。
- 迁移:旧记录缺少 `ctx` / `timeout` / `headers` 等字段时按默认值优雅降级展示(不崩溃、可再编辑)。

## 3. 表单布局与校验

| 分区 | 字段 | 控件 | 说明 |
| --- | --- | --- | --- |
| 基本信息 | 显示名称* | 文本输入 + 徽标实时预览 | 徽标取前 2 字符大写,空回退 "API" |
| | 模型 ID* | 文本输入 | 可由「测试连接」的模型列表点选填入 |
| 能力 | 上下文长度 | 下拉(8K/32K/128K/524K) | 双列并排 |
| | 请求超时 | 下拉(30/60/120/300 秒) | 双列并排 |
| 端点 | Base URL* | 文本输入 + 请求路径预览条 | 见 §4 URL 规范化 |
| | API Key(可选) | 密码输入 + 显隐(👁)切换 | |
| | TLS 证书校验 | 开关(默认关) | 副文案说明适用场景 |
| 高级选项(折叠) | 自定义请求头 | 多行文本,每行 `头名: 值` | 编辑含值时自动展开 |

- 标题随模式:**添加自定义模型** ↔ **编辑自定义模型**(完整预填)。
- 必填校验:`name / base / apiId` 三缺一 → 提示「请填写显示名称、Base URL 与模型 ID」。
- 次级按钮「测试连接」+ 主按钮「保存」。

## 4. URL 规范化规则(核心)

统一入口(原生实现请保持一致):

```
normEndpoint(raw):      // 协议自动补全
  s = trim(raw)
  若 s 为空            → 返回 ""
  若 s 不含 "://"      → s = "https://" + s
  返回 s

endpointUrl(base):      // 解析为最终请求路径
  b = normEndpoint(base)
  去掉 b 末尾的全部 "/"
  若 b 为空            → 返回 ""
  若 b(小写)以 "/chat/completions" 结尾 → 返回 b(识别为完整地址,直接使用)
  否则                 → 返回 b + "/chat/completions"
```

**示例**

| 用户输入 | 保存的 base | 预览/列表的完整请求路径 |
| --- | --- | --- |
| `api.example.com/v1` | `https://api.example.com/v1` | `https://api.example.com/v1/chat/completions` |
| `https://api.example.com/v1/` | `https://api.example.com/v1` | 同上(尾斜杠已去除) |
| `relay.myproxy.com/openai/v1/chat/completions` | `https://relay.myproxy.com/openai/v1/chat/completions` | 原样(完整路径识别) |
| `http://localhost:11434/v1` | `http://localhost:11434/v1` | `http://localhost:11434/v1/chat/completions`(已有协议不重复加) |
| `https://gateway.example.com/api/v1` | 同左 | 自动拼接 `/chat/completions` |

要点:
- 已含 `://` 不重复补协议;仅缺协议时补 `https://`。
- 大小写不敏感识别后缀;`/chat/completions` 后缀匹配在去除尾斜杠后进行。
- 失焦(`change`)时若输入缺少协议,**自动把 `https://` 写回输入框**,所见即所存。
- 保存:`base` 存 `normEndpoint` 结果(若粘贴完整路径,保留完整路径,不反向截断)。
- 列表行第三行与表单预览统一渲染 `endpointUrl(base)`,保证全局一致。

## 5. 测试连接流程

状态机:空闲 → 校验(三必填)→ **请求中**(按钮禁用 + 旋转;文案「正在请求 {endpointUrl} …」)
→ 成功(返回 200)→ 从 **`/v1/models` 拉取模型列表** → 用户点选模型 → 填入「模型 ID」
(显示名留空时按模型 ID 自动生成:连字符/下划线/点转为空格并首字母大写)。

- 原型为模拟;正式版:
  - 请求 `POST {endpointUrl}`(最小 body)校验连通与鉴权;
  - 模型列表 `GET {origin}/v1/models`;
  - 超时、TLS、4xx/5xx 需映射为可读错误提示(区分 401/403/404/限流)。
- 每次测试前清除上次的模型列表,避免残留选择。
- 拉取到的模型快照随 `models` 一并保存;再次编辑时以「上次可用模型(已保存快照)」卡片直接展示(无需重新测试),点选即可切换「模型 ID」,重新测试可刷新快照。
- 列表行元信息以「上次可用 N 个模型」徽标呈现,点击徽标就地展开快照面板,点选某项即切换该端点的模型 ID(数据层等同更新 `apiId`);「从快照重建」按钮可一键填入快照首个模型 ID。
- 存档加载即清洗(丢弃非对象条目、补齐 id/名称、过滤快照中的非文本项),并在列表顶部提示「已自动修复 N 条异常记录」+ 逐条明细,修复结果自动回写。

## 6. 模型目录与展示

- 自定义模型 `tier = "custom"`(紫色徽标),归入模型选择弹层与任务向导的「自定义模型」分组(官方在上、自定义在下)。
- 选择器内描述行:`OpenAI 兼容 · {apiId} · {ctx}`。
- 集成列表行信息层级:
  1. 行首徽标(名称前 2 字符)+ 名称 + 「当前」标记;
  2. 元信息行:`{apiId} · {ctx} · {timeout} · 已配置 Key/无 Key · 自定义头`;
  3. 第三行:完整请求路径(`endpointUrl`)。
- 行操作:点击=设为当前模型;✎ 编辑(打开表单预填);🗑 删除(二次确认;删除当前模型回退官方默认模型)。

## 7. 安全与实现备注(正式版)

- API Key 与自定义请求头:原型仅本机明文演示;**正式版入系统钥匙串/加密存储**,绝不入日志。
- 请求头合并:保留用户自定义头,同时注入鉴权、Content-Type、UA 等默认头。
- `skipTLS` 默认关闭;开启仅对用户自建端点生效,并在界面明示风险。
- 本地化:全部文案集中于数据层 `STR.integ`(如 `fCtx / fTo / fTLS / epAuto / epDirect / requesting / modelsTitle …`),新增字段遵循同一命名。
