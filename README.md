# 📱 PUA-MCP — Phone Use Agent for Android · 手机操控 Agent

**PUA = Phone Use Agent** — *like Computer Use, but for your real Android phone. No root, no ADB, no PC running 24/7.*  
**PUA = Phone Use Agent（手机版 Computer Use）** — *免 Root、免 ADB、免电脑常开，用 Gemini / Claude / Cursor「PUA」你的安卓真机替你干活。*

[English](#english) · [简体中文](#简体中文) · [Full Setup Guide (`USAGE.md`)](USAGE.md) · [AI Reference (`llms.txt`)](llms.txt) · [MIT License](LICENSE)

---

## English

**PUA-MCP (Phone Use Agent MCP)** brings **Computer Use** to your real Android phone over the **Model Context Protocol (MCP)**. Deploy the lightweight relay once to your own **Cloudflare Workers Free** account, install the companion Android app, and let **Gemini (Web / Android / Mac), Claude, Cursor, Windsurf, or Gemini CLI** inspect screens, open apps, tap, swipe, type, and understand images on your phone from anywhere.

### Why PUA-MCP?

| Feature | PUA-MCP (Phone Use Agent) | Typical ADB / Appium MCPs |
| :--- | :--- | :--- |
| **No USB / No ADB / No Root** | ✅ Pure Android `AccessibilityService` APK | ❌ Requires USB or wireless ADB debugging |
| **Works When Your PC Is Off** | ✅ Self-hosted on Cloudflare Workers Free (SQLite Durable Objects) | ❌ Requires a local computer running 24/7 |
| **Native Gemini Web & Mobile Support** | ✅ Built-in **OAuth 2.1** with Dynamic Client Registration | ❌ Usually stdio-only or Bearer-only |
| **Fast UI Tree + Vision Fallback** | ✅ Accessibility tree first (low token cost) + **Workers AI** or custom Vision API | ⚠️ Slow screenshot-only loops or no vision |
| **On-Device Safety Controls** | ✅ Banking/payment blacklist, 15s risk confirmation dialog, floating **STOP / To Gemini** pill | ⚠️ Usually no local safety guardrails |
| **Same-Device Self-Control** | ✅ Floating small-window launch + automatic return to Gemini | ❌ Hard to control the phone from itself |

### Architecture

```text
┌──────────────────────────────┐     HTTPS POST /mcp      ┌─────────────────────────────────────┐
│ Gemini / Claude / Cursor     │  (OAuth 2.1 or Bearer)   │  Your Cloudflare Worker (Free Plan) │
│ Any Streamable HTTP Client   │ ───────────────────────► │  • Streamable HTTP MCP + OAuth 2.1  │
└──────────────────────────────┘                          │  • SQLite Durable Object (Relay)    │
                                                          │  • Workers AI (Screenshot Vision)   │
┌──────────────────────────────┐     WSS /ws (Header)     └──────────────────┬──────────────────┘
│ Your Android Phone (No Root) │ ◄═══════════════════════════════════════════┘
│ • AccessibilityService       │   JSON UI Tree / Gestures / Cropped JPEG
│ • Small-Window & Auto-Return │   On-Device Safety: Blacklist · 15s Risk Modal · Floating STOP
└──────────────────────────────┘
```

### 3-Minute Quick Start

1. **Deploy the relay to your own Cloudflare account** (Free plan works):
   ```bash
   cd relay-worker && npm ci && npx wrangler login && npm run deploy
   ```
   Configure `PHONE_TOKEN`, `RELAY_TOKEN`, `OAUTH_OWNER_KEY`, and `OAUTH_ORIGIN` via `wrangler secret put` (full commands in [USAGE.md](USAGE.md#english)).
2. **Install the Android APK**: Download the latest APK from **Releases** (or **Actions → Build Android APK**), install **Gemini Phone Agent**, and enable its **Accessibility Service**.
3. **Connect the phone**: Enter your Worker HTTPS URL and `PHONE_TOKEN`, then tap **Save & connect** until it shows **Connected**.
4. **Connect your AI client**:
   - **Gemini Web & Mobile**: Open `Customize → Connectors → Create custom app`, enter `https://<your-worker>.workers.dev/mcp`, and authorize with `OAUTH_OWNER_KEY`.
   - **Claude / Cursor / Other Streamable HTTP clients**: Use URL `https://<your-worker>.workers.dev/mcp` with header `Authorization: Bearer <RELAY_TOKEN>`.
5. **Verify**: Run `get_device_status` and `get_screen_ui` from your AI client.

### Built-in MCP Tools (10 Tools)

| Tool | Purpose |
| :--- | :--- |
| `get_device_status` | Check online status, device model, screen size, Accessibility status, and window mode |
| `get_screen_ui` | Read structured UI tree (`scope: "window"` default for small-window focus, or `"all"`, up to 500 nodes) |
| `take_screenshot` | Capture cropped/full JPEG (Android 11+) + automatic multimodal `visionDescription` (Workers AI or custom API) |
| `tap` | Tap by `(x, y)` coordinates, visible `text`, or `resourceId` (auto-returns compact `screenUi`) |
| `long_press` | Long-press at `(x, y)` with configurable duration |
| `swipe` | Swipe by direction (`up`/`down`/`left`/`right`) or explicit start/end coordinates |
| `type_text` | Focus and type into input fields with optional `clearFirst` and `pressEnter` |
| `open_app` | Launch any installed app by display name or package name (floating small window or fullscreen) |
| `press_key` | Trigger system navigation (`back`, `home`, `recents`, `notifications`, `quick_settings`) |
| `return_to_gemini` | Switch the phone back to Gemini when finished |

### Security & Privacy

- **Sensitive App Blacklist**: Blocks banking, payment, password manager, authenticator, and APK installer apps by default; customize via an in-app installed-app picker.
- **High-Risk Confirmation Overlay**: Payment, delete, and send/submit actions trigger a 15-second on-device confirmation modal (auto-denies on timeout).
- **Draggable Status Pill & Emergency Stop**: Always-accessible red **STOP** button cancels active actions and disconnects immediately; tap **To Gemini** to jump back anytime.
- **Credential Isolation & Encryption**: Separate `PHONE_TOKEN`, `RELAY_TOKEN`, and `OAUTH_OWNER_KEY`; phone credentials are encrypted with **Android Keystore (AES-GCM)** with cloud backup disabled.

---

## 简体中文

**PUA-MCP（Phone Use Agent MCP）** 就是安卓真机的 **Phone Use（手机版 Computer Use）**。只需把中转服务免费部署到你自己的 **Cloudflare Workers**，手机装上配套 App，就能让 **Gemini（网页端 / 安卓端 / Mac 端）、Claude、Cursor、Windsurf 或 Gemini CLI** 随时远程查看屏幕、打开 App、点击、滑动、输入文字和截图识图——让 AI 替你“PUA”手机干活。

### 为什么选 PUA-MCP？

| 核心能力 | PUA-MCP（Phone Use Agent） | 传统 ADB / Appium 类 MCP |
| :--- | :--- | :--- |
| **免 Root / 免 ADB / 免数据线** | ✅ 纯安卓无障碍服务，装完 APK 即用 | ❌ 需要电脑连 USB 或折腾无线调试 |
| **关电脑也能 24 小时在线** | ✅ 部署在 Cloudflare Workers 免费版（Durable Objects） | ❌ 必须一直开着一台本地电脑 |
| **原生支持 Gemini 网页端与手机端** | ✅ 内置标准 **OAuth 2.1** 动态客户端注册 | ❌ 多数仅支持本地 stdio 或不支持 OAuth |
| **控件树优先 + 截图识图兜底** | ✅ 默认读无障碍树（快且省 Token），看不清时自动走 **Workers AI** 或自定义视觉 API 识图 | ⚠️ 纯截图慢且费 Token，或缺少视觉理解 |
| **手机端三重本地安全锁** | ✅ 银行/支付黑名单 + 15 秒高危弹窗确认 + 悬浮 **急停 / 回 Gemini** 胶囊 | ⚠️ 普遍缺少端侧拦截与急停 |
| **同机自控体验** | ✅ 支持悬浮小窗打开目标应用 + 任务完自动切回 Gemini | ❌ 难以在手机端直接自控 |

### 3 分钟快速上手

1. **部署自己的 Cloudflare Worker**（免费版即可）：
   ```bash
   cd relay-worker && npm ci && npx wrangler login && npm run deploy
   ```
   按 [中文完整教程](USAGE.md#中文) 通过 `wrangler secret put` 生成并配置 `PHONE_TOKEN`、`RELAY_TOKEN`、`OAUTH_OWNER_KEY` 与 `OAUTH_ORIGIN`。
2. **安装安卓端 APK**：前往仓库 **Releases**（或 **Actions → Build Android APK**）下载最新安装包，安装 **Gemini Phone Agent** 并开启无障碍服务。
3. **手机端连接**：填入你的 Worker HTTPS 地址和 `PHONE_TOKEN`，点击 **保存并连接**，看到顶部显示 **已连接** 即就绪。
4. **接入 AI 客户端**：
   - **Gemini 网页端 / 手机端**：在 `Customize → Connectors → Create custom app` 填入 `https://<你的域名>.workers.dev/mcp`，跳转授权页输入 `OAUTH_OWNER_KEY` 即可（网页端绑好会自动同步到同账号手机 Gemini App）。
   - **Claude / Cursor / 其他 Streamable HTTP 客户端**：Endpoint 填 `https://<你的域名>.workers.dev/mcp`，请求头加 `Authorization: Bearer <RELAY_TOKEN>`。
5. **开始测试**：在 AI 里先调用 `get_device_status` 检查手机在线状态，再调用 `get_screen_ui` 查看当前屏幕。

### 文档与开源许可

- 📖 详细部署、客户端配置、APK 升级说明与常见排错：[`USAGE.md`](USAGE.md)
- 🤖 面向 AI 编程工具的结构化技术索引：[`llms.txt`](llms.txt)
- 📄 开源协议：[MIT License](LICENSE)
