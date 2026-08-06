# Easy AI（PHP 版）v2

基于 [pjjush16/easy_ai](https://github.com/pjjush16/easy_ai) 重构：前端照 Open WebUI 风格重做（单文件 HTML），后端为 PHP 单文件。

原项目是纯前端单文件、浏览器直连 LLM API（密钥暴露在浏览器）。本版把 API 调用收进 PHP 后端代理，密钥只存在服务器上，并复刻了 Open WebUI 的核心功能集。

## 文件

| 文件 | 说明 |
| --- | --- |
| `index.html` | 前端，Open WebUI 风格，单文件，明暗双主题 |
| `api.php` | 后端，配置/会话/文件夹/分享/提示词/上传 + SSE 流式代理 + 工具分发 |
| `router.php` | PHP 内置服务器路由：封禁 /data/ 与隐藏文件（生产用 Nginx 时参见安全说明） |
| `soundfonts/` | 47 种乐器真实采样（FluidR3 GM，约 110MB），同源加载不依赖外部 CDN |
| `start.sh` | 启动脚本，`./start.sh [端口]`，默认 8880 |
| `fetch_samples.sh` | 采样补全脚本（可选，采样已内置；用于重新下载/补全） |

## 功能清单

**聊天核心**
- SSE 流式输出、深度思考（reasoning_content）折叠展示
- **技能系统（Tools 方案）**：后端向模型注册工具，由模型自主决定何时调用、传什么参数，最多 3 轮工具循环；线路不支持 Tools 时联网搜索自动回退预注入模式。已内置技能：
  - **web_search 联网搜索（免 Key）**：DuckDuckGo HTML 为主、Bing 兜底，抓取前 4 条结果正文回喂模型，回复带 [n] 来源标注 + 来源卡片
  - **read_image 看图**：回形针按钮上传图片/文件，图片走多模态接口理解（OCR、描述、读图），纯文本模型也能"看图"
  - **read_file 读文档**：txt/md/csv/json/log 直读，docx 用 ZipArchive 解析，pdf 优先 pdftotext（建议 `apt install poppler-utils`）
- 附件支持拖拽上传、图片缩略图预览、历史重载恢复；单文件限 15MB
- 停止生成 / 重新生成 / 复制消息
- 多线路自动故障转移；也可在模型选择器指定单条线路
- **多模型并排对比**：勾选 2~3 个模型，同一问题并行提问、分栏展示
- 会话历史持久化、全文搜索（Ctrl+K）、文件夹分组（新建/重命名/删除/移动）
- 导出 Markdown / JSON，生成分享链接（只读快照页）
- 提示词库：保存常用提示词，一键填入输入框
- 明暗主题切换、快捷键（Ctrl+K 搜索、Ctrl+Shift+O 新对话、Esc 关闭弹层）

**语音 + 浏览器端文档解析**
- 语音输入（Web Speech API，麦克风转文字）+ 朗读回复（speechSynthesis，可开自动朗读）
- 浏览器端解析附件：PDF 用 pdf.js 抽全文、图片用 Tesseract.js 做 OCR（中英），提取文本随消息一并发给模型，**不依赖服务器装 pdftotext / 视觉模型**；OCR 失败时自动回退 read_image 工具

**RAG 知识库**
- 设置里配 `embed_model`（+可选 `embed_url`），即可添加知识文档：自动分块→向量化→存储
- 对话开启 RAG（工具栏书本图标）后，按问题余弦检索 top-4 片段注入上下文，回复附「知识库来源」卡片
- 支持添加/删除文档、导入 txt/md 文件

**多用户 + 登录鉴权**
- 首次使用走 setup 建管理员；之后用户名/密码登录（password_hash + PHP session）
- 管理员可在设置里增删用户（admin/user 两级）
- 会话按用户隔离（各自只见自己的历史），分享只读页无需登录

**音频引擎（双引擎）**
- **真实乐器采样**：内置 FluidR3 GM SoundFont 的 47 种乐器真实录音采样（soundfonts/ 目录，约 110MB），钢琴/提琴/吉他/管乐等全部用真实音色播放；消息渲染时自动预加载，点击播放前完成解码
- **物理建模兜底**：原 easy_ai 的 Web Audio 物理建模引擎（14 类合成引擎）完整保留——鼓组等无采样乐器、采样加载失败时自动接管，任何情况下都能出声
- 混响/力度响应两种引擎共用，听感一致

**渲染增强**
- Markdown（marked）
- 代码高亮（highlight.js）+ 代码块语言标签、一键复制
- Mermaid 流程图
- KaTeX 数学公式（$行内$ / $$块级$$）
- 原项目特色：🎵 Web Audio 物理建模作曲（14 类乐器引擎）、🎨 【photo】SVG 绘图

**扩展新技能**：在 api.php 的 `build_tool_defs()` 里加一条工具定义，在 `exec_tool()` 里加一个分支函数即可，前端无需改动。

## 模型接入（OpenAI / Ollama / 任意兼容接口）

后端说的是标准 OpenAI Chat Completions 格式，凡是兼容该格式的都能接：

| 服务 | API URL | Key |
| --- | --- | --- |
| OpenAI | `https://api.openai.com/v1/chat/completions` | 你的 sk-xxx |
| DeepSeek | `https://api.deepseek.com/v1/chat/completions` | 你的 Key |
| 阿里百炼(Qwen) | `https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions` | 你的 Key |
| Moonshot | `https://api.moonshot.cn/v1/chat/completions` | 你的 Key |
| OpenRouter | `https://openrouter.ai/api/v1/chat/completions` | 你的 Key |
| **Ollama 本地** | `http://127.0.0.1:11434/v1/chat/completions` | **留空**（无需鉴权） |
| LM Studio | `http://127.0.0.1:1234/v1/chat/completions` | 留空 |

- Ollama 走自带的 OpenAI 兼容端点 `/v1/...`（不是 `/api/chat`），模型名填完整 tag，如 `qwen2.5:7b`、`llama3.1:8b`
- Key 选填：留空则不带 Authorization 头（本地服务直接可用）
- 联网搜索/看图/读文件等工具调用需要模型支持 function calling（Ollama 上如 llama3.1、qwen2.5、mistral-nemo 等）；不支持时联网搜索自动降级为预注入模式
- 看图（read_image）需要视觉模型（OpenAI gpt-4o、Ollama 上的 llava/qwen2.5vl 等）
- 注意：Easy AI 服务器必须能访问到模型地址——Ollama 和 Easy AI 不在同一台机器时，URL 里写 Ollama 那台的内网 IP

## 部署

- 环境：PHP >= 7.4 + curl + zip 扩展（docx 解析需 zip；不强依赖 mbstring）。无框架、无数据库。
- 整个目录放到网站目录即可，`data/` 自动创建（配置、会话、上传文件都在里面，注意备份和访问控制）。
- 本地体验：`./start.sh`（等价于 `php -S 0.0.0.0:8880 -d upload_max_filesize=20M -d post_max_size=25M`）。
- 生产建议 Nginx/Apache + PHP-FPM；Nginx 反代需加 `proxy_buffering off;`（SSE 需要），并在 php.ini 调大 `upload_max_filesize` / `post_max_size`。
- 可选：`apt install poppler-utils`（pdftotext）获得更好的 PDF 文本提取。
- 采样走同源相对路径 `soundfonts/`，保持 index.html 与 soundfonts/ 的相对位置不变即可；Nginx 给 .js 开 gzip 可加速首次加载。

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
| `upload` | POST | 上传附件（multipart，字段 file，限 15MB） |
| `file` | GET | 读取已上传文件（预览/下载，`?download=1`） |
| `uploads` | GET | 上传文件列表 |
| `upload_delete` | POST | 删除上传文件 |
| `websearch` | GET | 独立网页搜索（调试用），`?q=` |
| `generate` | POST | 流式生成（SSE，支持 provider 指定 + strict 单线路 + web 联网 + attachments 附件技能） |

## 安全说明

- **API Key 加密存储**：Key 用 sodium（XSalsa20-Poly1305 认证加密）加密后写入 `data/config.json`，解密密钥在 `data/.secret`（权限 600）。前端读取一律打码，永不返回明文。
- **数据目录封禁**：内置 `router.php` 路由，`/data/` 及一切隐藏文件一律返回 403，防止密钥/会话/上传文件被直接下载。`start.sh` 已默认启用。
- **Nginx 生产部署务必加**：`location /data/ { deny all; }` 和 `location ~ /\. { deny all; }`。
- **传输加密**：本页表单到后端是同源请求；公网部署请套 HTTPS（反代加证书），别裸 HTTP 传 Key。Tailscale 内网本身是加密隧道，无需额外处理。
- **多用户登录**：首次使用创建管理员账号；密码 password_hash 加盐哈希存储，会话用 HttpOnly Cookie。非登录用户只能访问登录页与只读分享页。
- 公网部署建议套 HTTPS（反代加证书），避免密码与 Key 明文传输。Tailscale 内网本身是加密隧道，无需额外处理。
- 分享链接是匿名只读快照，知道 token 即可访问，勿分享敏感对话。
- 联网搜索：DuckDuckGo/Bing 为非官方接口，高频使用可能被限流；搜索与网页抓取都在服务器出网，部署机需能访问外网。

## 许可证

衍生自 AGPL-3.0 项目（原作者：yaoqingxuan20221@outlook.com），本衍生版本同样遵循 AGPL-3.0。
