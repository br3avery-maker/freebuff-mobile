package com.freebuff.core.model

/** 官方模型目录(与原型一致)。 */
val OFFICIAL_MODELS = listOf(
    OfficialModel("deepseek-v4-flash", "DeepSeek V4 Flash", "DS", "full", "完整访问", "默认模型 · 快速编码与工具调用,不限量"),
    OfficialModel("glm-5.3-flash", "GLM 5.3 Flash", "GLM", "full", "完整访问", "深度推理最强,不限量"),
    OfficialModel("gpt-5.6-luna", "GPT-5.6 Luna", "5.6", "full", "完整访问", "全能表现,原生图像支持"),
    OfficialModel("mimo-2.5", "MiMo 2.5", "MiMo", "full", "完整访问", "性能均衡,支持图像"),
    OfficialModel("solar-pro-4", "Solar Pro 4", "S4", "trial", "限时试用", "524K 超长上下文 · 仅文本"),
)

/** 演示 Git 账号仓库(Git OAuth 未配置时的回退数据)。 */
val GIT_REPOS = listOf(
    RepoItem("octocat/freebuff", "main", "官方仓库 · 移动端产品目录"),
    RepoItem("octocat/hello-world", "master", "练习仓库"),
    RepoItem("octocat/dotfiles", "dev", "个人配置"),
)

/** 预置演示会话(首次启动灌入 Room;结构与原型一致,内容精简)。 */
val SEED_SESSIONS = listOf(
    Session(
        id = "s1",
        title = "用 Python 写一个批量重命名脚本",
        time = "10 分钟前",
        preview = "我帮你写了一个安全、可回滚的批量重命名脚本…",
        messages = listOf(
            ChatMsg(uid(), "user", text = "帮我写一个 Python 脚本:把文件夹里所有 jpg 图片按拍摄时间批量重命名为 IMG_20260904_001.jpg 这种格式,顺便去重。"),
            ChatMsg(
                uid(), "agent", time = "9 分钟前",
                steps = listOf(MsgStep("规划", "读取目录 → 提取 EXIF 时间 → 冲突去重 → 安全执行")),
                md = "**好的**,我来帮你实现。这个脚本分三步:\n\n1. 遍历目录,只处理 `.jpg` / `.jpeg`\n2. 从 EXIF 读取拍摄时间,没有则回退到文件修改时间\n3. 检查目标文件名是否冲突,冲突自动加 `_1`、`_2` 后缀\n\n这是完整代码:",
                codeLang = "python",
                code = "import os\nfrom datetime import datetime\nfrom PIL import Image\n\ndef shoot_time(path):\n    # 优先 EXIF 拍摄时间,回退文件 mtime\n    return datetime.now()\n\ndef rename_batch(folder):\n    seen = set()\n    for f in sorted(os.listdir(folder)):\n        if not f.lower().endswith((\".jpg\", \".jpeg\")):\n            continue\n        t = shoot_time(os.path.join(folder, f))\n        name = t.strftime(\"IMG_%Y%m%d_%H%M%S\")\n        i = 1\n        while name in seen:\n            name, i = f\"{name}_{i}\", i + 1\n        seen.add(name)\n        os.rename(os.path.join(folder, f), os.path.join(folder, name + \".jpg\"))\n        print(f\"✓ {f} → {name}.jpg\")\n\nif __name__ == \"__main__\":\n    rename_batch(\".\")",
                md2 = "**使用方式**\n\n```bash\npip install pillow\npython rename_batch.py\n```\n\n几个说明:\n- **安全**:先跑在小目录上验证\n- **去重**:按时间到秒保证唯一,极端同秒时追加序号\n- 支持重命名前自动打印一张清单的版本,需要的话告诉我",
            ),
            ChatMsg(uid(), "user", text = "太棒了!再帮我加一个「dry run」参数?"),
            ChatMsg(
                uid(), "agent", time = "1 分钟前",
                steps = listOf(MsgStep("执行", "增加 --dry-run 与 --verbose 参数")),
                md = "当然可以。用 `argparse` 加两个开关,默认走**试运行**,确认无误后再真正改名:\n\n```python\nparser.add_argument(\"--dry-run\", action=\"store_true\")\n```\n\n跑 `python rename_batch.py --dry-run` 先看清单,确认无误后去掉参数执行即可 ✅",
            ),
        ),
    ),
    Session(
        id = "s2",
        title = "React 组件为什么重复渲染?",
        time = "昨天",
        preview = "问题的根源在于每次渲染都创建了新函数引用…",
        messages = listOf(
            ChatMsg(uid(), "user", text = "我的 React 组件每次输入都卡,子组件也一直被重复渲染,是什么原因?"),
            ChatMsg(
                uid(), "agent", time = "昨天",
                steps = listOf(MsgStep("定位", "查找组件树与引用稳定性问题")),
                md = "先看最常见的三个原因,按概率排序:\n\n1. **内联函数导致子组件 props 引用变化**\n2. **Context / 全局状态把整棵子树都带崩**\n3. **组件定义在父组件函数体内,每次渲染都是新类型**\n\n其中第一个最常见:",
                codeLang = "jsx",
                code = "// ❌ 每次渲染,onChange 都是新的引用\nfunction Parent() {\n  return <Child onChange={(v) => setVal(v)} />\n}\n\n// ✅ 用 useCallback 稳定引用\nconst onChange = useCallback((v) => setVal(v), [])\nreturn <Child onChange={onChange} />",
                md2 = "把 `onChange` 用 `useCallback` 包起来后,子组件的 props 引用就稳定了,配合 `React.memo` 就不会再重复渲染。",
            ),
        ),
    ),
    Session(
        id = "s3",
        title = "Kotlin 协程:结构化并发入门",
        time = "3 天前",
        preview = "协程的取消与结构化并发是理解它的关键…",
        messages = listOf(
            ChatMsg(uid(), "user", text = "能讲讲 Kotlin 协程的结构化并发吗?为什么父协程取消后子协程也会取消?"),
            ChatMsg(
                uid(), "agent", time = "3 天前",
                md = "结构化并发的核心是**作用域**:`coroutineScope` 会等待所有子协程完成,而父作用域取消时会级联取消子协程。\n\n```kotlin\ncoroutineScope {\n    launch { delay(1000); println(\"child\") }\n}\nprintln(\"done\") // 等待子协程完成\n```\n\n这正是 `viewModelScope` 自动清理后台任务的原因。",
            ),
        ),
    ),
)
