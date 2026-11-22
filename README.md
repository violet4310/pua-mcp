# PUA MCP — AI Android Phone Control

**中文** · [English](#english)

## 中文

PUA MCP（Phone UI Automation MCP）让支持 MCP 的 AI 助手通过你自己部署的 Cloudflare Worker 操作你自己的 Android 手机。它可以读取界面控件、打开 App、点击、滑动、输入文字，也可以在控件树不足时截图识图。

### 快速开始

1. 按[中文教程](USAGE.md#中文)把中转 Worker 部署到自己的 Cloudflare 账号，生成自己的连接密钥。
2. 从仓库的 **Releases** 或 **Actions** 下载 APK，或者用 JDK 17 和 Android SDK 自行构建。在手机上安装 **Gemini Phone Agent**，开启它的无障碍服务。
3. 在手机 App 填自己的 Worker 地址和 `PHONE_TOKEN`，点「保存并连接」。
4. 把自己的 `/mcp` 地址加到 Gemini 网页端或其他支持 Streamable HTTP 的 MCP 客户端。先调用 `get_device_status` 检查连接。

```text
Gemini / MCP 客户端 ── HTTPS /mcp ──► 你的 Cloudflare Worker
Android 手机 ───────── WSS /ws ─────► 同一个 Worker
```

- Gemini 网页版自定义连接器走 OAuth。支持自定义请求头的 MCP 客户端也可以使用 Bearer 鉴权。
- 普通 UI 操作使用 Android 无障碍树。截图识图默认走 Cloudflare Workers AI，部署绑定后手机端无需额外的模型 API Key。
- App 带有敏感应用黑名单、高风险操作确认和可选的悬浮急停。使用截图时，按设置选择的视觉服务会处理截图内容。

完整部署、连接方式、APK 升级说明和排查步骤见[中文教程](USAGE.md#中文)。本项目采用 [MIT 许可证](LICENSE)。

## English

PUA MCP (Phone UI Automation MCP) lets an MCP-capable AI assistant control your own Android phone through a Cloudflare Worker you deploy. It can inspect the UI tree, open apps, tap, swipe, and type. It can also analyze screenshots when the UI tree is insufficient.

### Quick start

1. Follow the [English guide](USAGE.md#english) to deploy the relay in your own Cloudflare account and generate your own credentials.
2. Download the APK from **Releases** or **Actions**, or build it with JDK 17 and the Android SDK. Install **Gemini Phone Agent** on your phone and enable its Accessibility service.
3. Enter your Worker URL and `PHONE_TOKEN` in the phone app, then tap **Save & connect**.
4. Add your `/mcp` URL in the Gemini web app or another Streamable HTTP MCP client. Run `get_device_status` first.

```text
Gemini / MCP client ── HTTPS /mcp ──► Your Cloudflare Worker
Android phone ──────── WSS /ws ─────► The same Worker
```

- Gemini web custom connectors use OAuth. MCP clients that support custom headers can also use Bearer authentication.
- Routine UI operations use Android Accessibility. Screenshot vision uses Cloudflare Workers AI by default. The Worker binding handles access, so the phone needs no separate model API key in that mode.
- The Android app offers sensitive-app blocking, high-risk confirmations, and an optional floating emergency stop. Screenshot contents are processed by the selected vision service when analysis is enabled.

See the [English guide](USAGE.md#english) for deployment, client configuration, APK upgrades, and troubleshooting. Licensed under [MIT](LICENSE).
