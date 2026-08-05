# Easy AI（PHP 版）

基于 [pjjush16/easy_ai](https://github.com/pjjush16/easy_ai) 重构：前端照 Open WebUI 风格重做（仍为单文件 HTML），后端改为 PHP。

原项目是纯前端单文件、浏览器直连 LLM API（密钥暴露在浏览器）。本版把 API 调用收进 PHP 后端代理，密钥只存在服务器上。

## 文件

| 文件 | 说明 |
| --- | --- |
| `index.html` | 前端，Open WebUI 风格暗色界面，单文件 |
| `api.php` | 后端，配置 + 会话持久化 + OpenAI 兼容 SSE 流式代理 |

## 部署

- 环境：PHP >= 7.4，带 curl 扩展（不强依赖 mbstring）。无框架、无数据库。
- 把两个文件放进同一个网站目录即可，`data/` 目录（配置和会话）首次运行自动创建。
- 本地快速体验：`php -S 0.0.0.0:8080`，浏览器打开 `http://localhost:8080`。
- 生产环境建议用 Nginx/Apache + PHP-FPM。若用 Nginx 反代注意加 `proxy_buffering off;`（SSE 流式需要）。

## 使用

1. 打开页面 → 左下角「设置」→ 填写 API 线路（最多 3 条，OpenAI 兼容格式），保存。
2. 顶部模型选择器：默认「自动模式」按线路顺序故障转移，也可指定某条线路。
3. 聊天即可。支持原项目全部特性：
   - 🎵 物理建模实时作曲（Web Audio，14 类乐器引擎，浏览器端合成）
   - 🎨 【photo】SVG 矢量绘图
   - 深度思考（reasoning_content）折叠展示
   - 会话历史持久化（服务器端），支持删除
   - 消息复制 / 重新生成 / 停止生成

## API 接口（api.php?action=）

| 接口 | 方法 | 说明 |
| --- | --- | --- |
| `config` | GET / POST | 读取（密钥打码）/ 保存配置 |
| `chats` | GET | 会话列表 |
| `chat` | GET / POST | 读取 / 保存会话 |
| `chat_delete` | POST | 删除会话 |
| `generate` | POST | 流式生成（SSE 透传 + 多线路故障转移，兼容非流式上游） |

## 安全说明

- API Key 只保存在服务器 `data/config.json`，前端读取时打码，永不返回明文。
- 无登录鉴权：公网部署请自行加 Basic Auth / 访问控制，否则任何人都能用掉你的额度。

## 许可证

衍生自 AGPL-3.0 项目（原作者：yaoqingxuan20221@outlook.com），本衍生版本同样遵循 AGPL-3.0。
