# PUA MCP 使用手册 / PUA MCP Setup Guide

[中文](#中文) · [English](#english)

本文示例中的域名都是占位符。请使用你自己账号下的 Worker 和自行生成的密钥。

All hostnames below are placeholders. Use a Worker in your own account and generate your own credentials.

## 中文

### 1. 部署自己的 Cloudflare Worker

准备 Cloudflare 账号、Node.js 22+、npm 和 OpenSSL。进入本仓库的 `relay-worker` 目录后执行：

```bash
npm ci
npx wrangler login
npm run deploy
```

第一次部署会给出类似 `https://pua-mcp-relay.<你的子域名>.workers.dev` 的地址，以下称 **Worker 地址**。打开地址加 `/health`，应看到 `{"ok":true,"service":"android-phone-controller"}`。配置里的 `OAUTH_KV` 用于存放 OAuth 授权状态，`AI` 用于 Workers AI 截图识图。如果首次部署没有自动建立 KV，在自己账号的 **Storage & databases → Workers KV** 创建一个命名空间，把它的 `id` 写到 `[[kv_namespaces]]` 的 `binding = "OAUTH_KV"` 下，再部署一次。

为以下三项分别运行 `openssl rand -hex 32` 生成独立随机值，保存到自己的密码管理器。把每个值贴进对应的 Wrangler 输入提示。

```bash
npx wrangler secret put PHONE_TOKEN
npx wrangler secret put RELAY_TOKEN
npx wrangler secret put OAUTH_OWNER_KEY
npx wrangler secret put OAUTH_ORIGIN
```

| 名称 | 填什么 | 用在哪 |
|---|---|---|
| `PHONE_TOKEN` | 第一串随机密钥 | Android App 的连接密钥 |
| `RELAY_TOKEN` | 第二串随机密钥 | 支持自定义请求头的 MCP 客户端 |
| `OAUTH_OWNER_KEY` | 第三串随机密钥，至少 32 字符 | Gemini 跳到你的 Worker 授权页时填写 |
| `OAUTH_ORIGIN` | 自己的 Worker 地址，从 `https://` 开始，末尾无斜杠或 `/mcp` | OAuth 发现与会话校验 |

最后访问 `你的 Worker 地址/.well-known/oauth-authorization-server`，应看到 `authorization_endpoint`、`token_endpoint` 和 `registration_endpoint`。手机连接和 MCP 调用分别认证。将密钥保存于 Cloudflare Secrets、手机 App 或密码管理器，并让示例文件继续使用占位符。

### 2. 安装并连接 Android 手机

在仓库 **Releases** 下载 APK，也可以在 **Actions → Build Android APK → Artifacts** 下载 `gemini-phone-agent-apk` 后解压。自己构建时需要 JDK 17、Android SDK API 35 和 Gradle 8.11.1：

```bash
cd android-app
gradle assembleDebug
```

产物在 `android-app/app/build/outputs/apk/debug/app-debug.apk`。Android 8.0+ 可安装，截屏工具需要 Android 11+。同包名和同签名才能覆盖升级并保留配置。CI 下载包和你本地构建的 APK 可能签名不同，切换前请先确认签名。卸载会清除手机端配置。

1. 打开 **Gemini Phone Agent**，在系统无障碍设置里开启「Gemini 手机操控服务」。某些机型会把侧载 App 的无障碍开关列为受限设置，由机主在系统 App 信息页确认。可选开启悬浮窗以显示急停，并按需调整电池优化。
2. **Worker 服务地址**填写自己的 HTTPS 地址，**连接密钥**填写 `PHONE_TOKEN`。点 **保存并连接**，等待显示 **已连接**。
3. 「截屏识图」保留默认 **Workers AI** 即可。使用 `take_screenshot` 的默认识图时，Worker 会将截图传给 Workers AI 生成文字描述。需要只返回截图时可传 `describe: false`。自定义模式支持 OpenAI 兼容或 Gemini 原生视觉接口，使用自己的端点、模型和密钥。使用自定义模式时，手机直接向选定的视觉服务发出识图请求。

### 3. 接入 AI 客户端

**Gemini 网页端**：在 **Customize → Connectors → Create custom app** 填 `你的 Worker 地址/mcp`。点 **Connect**，确认 Google 账号关联。跳到自己 Worker 后，点 **Continue to phone authorization**，等显示 **Browser session ready.**，再输入 `OAUTH_OWNER_KEY` 并点 **Authorize phone access**。回 Gemini 后，在 **Save your custom app** 页面点 **Connect** 保存。Google 对自定义连接器有账号、地区和产品限制，入口以你账号实际可用为准。当前 Worker 的 Gemini OAuth 授权针对主手机 `primary`。

**其他 MCP 客户端**：使用 **Streamable HTTP**，地址填 `你的 Worker 地址/mcp`，请求头填 `Authorization: Bearer <RELAY_TOKEN>`。

### 4. 验证和排查

- 在 Gemini 网页端的 **Task** 中选中自定义连接器，先调用 `get_device_status`。`connected: true` 表示手机在线，`accessibilityEnabled: true` 表示无障碍生效。然后可在自己的手机上测试 `get_screen_ui`。
- **手机显示未连接或 401**：确认地址是你部署的 Worker，手机填的是该 Worker 对应的 `PHONE_TOKEN`。保存后再查询状态。
- **OAuth 发现端点返回 401**：核对 `OAUTH_ORIGIN`、至少 32 字符的 `OAUTH_OWNER_KEY` 及同一个 Worker 的 `OAUTH_KV` 绑定。授权会话过期时，从 Gemini 重新发起连接。
- **截图有图、没有描述**：查看返回里的 `visionError`，核对 `AI` 绑定和自己的 Workers AI 额度。Workers AI 免费额度和模型价格以 [Cloudflare 定价](https://developers.cloudflare.com/workers-ai/platform/pricing/)为准。
- **更新**：在同一个 Worker 上部署新版，再用同签名 APK 覆盖安装。继续使用原地址、密钥和 `OAUTH_KV`，随后查询设备状态验证。

## English

### 1. Deploy your own Cloudflare Worker

You need a Cloudflare account, Node.js 22+, npm, and OpenSSL. From this repository's `relay-worker` directory, run:

```bash
npm ci
npx wrangler login
npm run deploy
```

The first deployment prints a URL such as `https://pua-mcp-relay.<your-subdomain>.workers.dev`, referred to below as **your Worker URL**. Open your URL followed by `/health`. You should see `{"ok":true,"service":"android-phone-controller"}`. The `OAUTH_KV` binding stores OAuth authorization state and the `AI` binding enables Workers AI vision. If your first deployment cannot provision KV, create a namespace in your own account under **Storage & databases → Workers KV**, add its `id` below `binding = "OAUTH_KV"` in the `[[kv_namespaces]]` block, and deploy again.

Run `openssl rand -hex 32` separately for each of the three random credentials below, and store them in your password manager. Paste each value into its corresponding Wrangler prompt.

```bash
npx wrangler secret put PHONE_TOKEN
npx wrangler secret put RELAY_TOKEN
npx wrangler secret put OAUTH_OWNER_KEY
npx wrangler secret put OAUTH_ORIGIN
```

| Name | Value | Used in |
|---|---|---|
| `PHONE_TOKEN` | First random credential | The Android app's connection key |
| `RELAY_TOKEN` | Second random credential | MCP clients that support custom headers |
| `OAUTH_OWNER_KEY` | Third random credential, at least 32 characters | Your Worker authorization page during Gemini setup |
| `OAUTH_ORIGIN` | Your own Worker HTTPS origin, without a trailing slash or `/mcp` | OAuth discovery and session validation |

Open `your Worker URL/.well-known/oauth-authorization-server` and look for `authorization_endpoint`, `token_endpoint`, and `registration_endpoint`. The phone connection and MCP requests authenticate separately. Keep credentials in Cloudflare Secrets, the phone app, or your password manager. Keep placeholders in sample files.

### 2. Install and connect the Android app

Download the APK from **Releases** or download and extract `gemini-phone-agent-apk` from **Actions → Build Android APK → Artifacts**. To build it yourself, use JDK 17, Android SDK API 35, and Gradle 8.11.1:

```bash
cd android-app
gradle assembleDebug
```

The APK is at `android-app/app/build/outputs/apk/debug/app-debug.apk`. Installation requires Android 8.0 or later. Screenshots require Android 11 or later. An in-place update preserves app settings when the package name and signing certificate match. CI APKs and locally built APKs can have different signatures. Check before switching. Uninstalling clears the phone's app settings.

1. Open **Gemini Phone Agent**, then enable its service in Android Accessibility settings. On phones that mark sideloaded Accessibility as a restricted setting, the device owner can approve it from the app's system info page. Optionally allow the overlay for the emergency stop and configure battery optimization.
2. Enter your **Worker URL (HTTPS)** and `PHONE_TOKEN` as the **connection key**. Tap **Save & connect** and wait for **Connected**.
3. Leave **Screenshot Vision** at **Workers AI** to use the deployment's default vision model. With vision enabled, `take_screenshot` sends the image to Workers AI for a description. Pass `describe: false` to request the screenshot without vision analysis. The optional custom mode supports an OpenAI-compatible or native Gemini model. Supply your own URL, model, and credential. In this mode the phone sends the analysis request directly to the selected provider.

### 3. Connect an AI client

**Gemini web app**: Open **Customize → Connectors → Create custom app** and enter `your Worker URL/mcp`. Select **Connect** and approve account linking. When your Worker page opens, select **Continue to phone authorization**, wait for **Browser session ready.**, enter `OAUTH_OWNER_KEY`, then select **Authorize phone access**. Back in Gemini, select **Connect** in **Save your custom app** to save it. Google applies account, region, and product restrictions to custom connectors. Check availability in your account. Gemini OAuth grants access to the primary phone (`primary`).

**Other MCP clients**: Select **Streamable HTTP**, use `your Worker URL/mcp`, and set `Authorization: Bearer <RELAY_TOKEN>`.

### 4. Verify and troubleshoot

- In **Task** mode in Gemini web, select your custom connector and call `get_device_status`. `connected: true` means the phone is online. `accessibilityEnabled: true` means the Accessibility service is running. You can then test `get_screen_ui` on your own phone.
- **Phone disconnected or HTTP 401**: Verify the Worker URL and the `PHONE_TOKEN` for that same Worker. Save and check the status again.
- **OAuth metadata returns 401**: Check `OAUTH_ORIGIN`, an `OAUTH_OWNER_KEY` of at least 32 characters, and the `OAUTH_KV` binding on the same Worker. Restart the flow from Gemini if the browser session expires.
- **Screenshot has no description**: Inspect `visionError`, the `AI` binding, and your Workers AI quota. See [Cloudflare pricing](https://developers.cloudflare.com/workers-ai/platform/pricing/) for the latest free allocation and model rates.
- **Updates**: Redeploy to the same Worker and install a newer APK signed with the same certificate. Reuse the existing URL, credentials, and `OAUTH_KV`, then run the status check again.
