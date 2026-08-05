# Easy AI（PHP 版）v2

基于 [pjjush16/easy_ai](https://github.com/pjjush16/easy_ai) 重构：前端照 Open WebUI 风格重做（单文件 HTML），后端为 PHP 单文件。

原项目是纯前端单文件、浏览器直连 LLM API（密钥暴露在浏览器）。本版把 API 调用收进 PHP 后端代理，密钥只存在服务器上，并复刻了 Open WebUI 的核心功能集。

## 文件

| 文件 | 说明 |
| --- | --- |
| `index.html` | 前端，Open WebUI 风格，单文件，明暗双主题 |
| `api.php` | 后端，配置/会话/文件夹/分享/提示词 + SSE 流式代理 |

## 功能清单

**聊天核心**
- SSE 流式输出、深度思考（reasoning_content）折叠展示
- **联网搜索（免 Key）**：输入框地球图标开关，回答前先搜网页（DuckDuckGo HTML 为主、Bing 兜底），抓取前 4 条结果正文注入上下文，回复带 [n] 来源标注，消息上方展示可点击的来源卡片
- 停止生成 / 重新生成 / 复制消息
- 多线路自动故障转移；也可在模型选择器指定单条线路
- **多模型并排对比**：勾选 2~3 个模型，同一问题并行提问、分栏展示
- 会话历史持久化、全文搜索（Ctrl+K）、文件夹分组（新建/重命名/删除/移动）
- 导出 Markdown / JSON，生成分享链接（只读快照页）
- 提示词库：保存常用提示词，一键填入输入框
- 明暗主题切换、快捷键（Ctrl+K 搜索、Ctrl+Shift+O 新对话、Esc 关闭弹层）

**渲染增强**
- Markdown（marked）
- 代码高亮（highlight.js）+ 代码块语言标签、一键复制
- Mermaid 流程图
- KaTeX 数学公式（$行内$ / $$块级$$）
- 原项目特色：🎵 Web Audio 物理建模作曲（14 类乐器引擎）、🎨 【photo】SVG 绘图

## 部署

- 环境：PHP >= 7.4 + curl 扩展（不强依赖 mbstring）。无框架、无数据库。
- 两个文件放同一目录，`data/` 自动创建。
- 本地体验：`php -S 0.0.0.0:8080`，打开 `http://localhost:8080`。
- 生产建议 Nginx/Apache + PHP-FPM；Nginx 反代需加 `proxy_buffering off;`（SSE 需要）。

## 使用

1. 左下角「设置」→ 填 API 线路（最多 3 条，OpenAI 兼容格式）→ 保存。
2. 顶部模型选择器：默认自动模式（按序故障转移）；勾选 1 个 = 指定线路；勾选多个 = 并排对比。
3. 开聊。

## API 接口（api.php?action=）

| 接口 | 方法 | 说明 |
| --- | --- | --- |
| `config` | GET / POST | 读取（密钥打码）/ 保存配置 |
| `chats` | GET | 会话列表，`?q=` 全文搜索 |
| `chat` | GET / POST | 读取 / 保存会话（支持 folder 字段） |
| `chat_delete` | POST | 删除会话 |
| `chat_move` | POST | 移动会话到文件夹 |
| `folders` | GET | 文件夹列表 |
| `folder_save` | POST | 新建 / 重命名文件夹 |
| `folder_delete` | POST | 删除文件夹（会话回到未分组） |
| `share` | POST / GET | 创建分享快照 / 按 token 读取 |
| `share_delete` | POST | 删除分享 |
| `prompts` | GET | 提示词列表 |
| `prompt_save` | POST | 新建 / 更新提示词 |
| `prompt_delete` | POST | 删除提示词 |
| `websearch` | GET | 独立网页搜索（调试用），`?q=` |
| `generate` | POST | 流式生成（SSE，支持 provider 指定 + strict 单线路 + web 联网搜索） |

## 安全说明

- API Key 只保存在服务器 `data/config.json`，前端读取时打码，永不返回明文。
- 无登录鉴权：公网部署请自行加 Basic Auth / 访问控制，否则任何人都能消耗你的额度。
- 分享链接是匿名只读快照，知道 token 即可访问，勿分享敏感对话。
- 联网搜索：DuckDuckGo/Bing 为非官方接口，高频使用可能被限流；搜索与网页抓取都在服务器出网，部署机需能访问外网。

## 许可证

衍生自 AGPL-3.0 项目（原作者：yaoqingxuan20221@outlook.com），本衍生版本同样遵循 AGPL-3.0。
