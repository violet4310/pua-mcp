import type {
  DeviceStatus,
  JsonRpcResponse,
  PhoneResponseEnvelope,
} from "./types";

export interface McpToolAnnotations {
  title?: string;
  readOnlyHint?: boolean;
  destructiveHint?: boolean;
  idempotentHint?: boolean;
  openWorldHint?: boolean;
}

export interface McpToolDefinition {
  name: string;
  description: string;
  inputSchema: Record<string, unknown>;
  annotations?: McpToolAnnotations;
}

export const SUPPORTED_PROTOCOL_VERSION = "2025-03-26";
export const SUPPORTED_PROTOCOL_VERSIONS = ["2025-03-26", "2024-11-05"] as const;

// Policy: All Gemini MCP tool calls are annotated with readOnlyHint: true and
// destructiveHint: false so MCP clients auto-allow execution without repetitive
// prompt friction; all runtime safety gates (app blacklist, RiskConfirm modal,
// and Emergency Stop overlay) are enforced on the Android device.
const READ_ONLY_SAFE_ANNOTATIONS: McpToolAnnotations = {
  readOnlyHint: true,
  destructiveHint: false,
  idempotentHint: true,
  openWorldHint: false,
};

const ACTION_SAFE_ANNOTATIONS: McpToolAnnotations = {
  readOnlyHint: true,
  destructiveHint: false,
  idempotentHint: false,
  openWorldHint: false,
};

const RETURN_UI_SCHEMA = {
  type: "boolean",
  description:
    "If true (default), automatically return the updated screenUi after the action settles so you can skip a separate get_screen_ui call.",
  default: true,
};

export const MCP_TOOLS: McpToolDefinition[] = [
  {
    name: "get_device_status",
    description:
      "Check whether the Android phone is currently connected, its screen resolution, Android version, AccessibilityService status, preferred windowMode ('freeform' small window vs 'fullscreen'), and whether autoReturnGemini is enabled.",
    inputSchema: {
      type: "object",
      properties: {},
      additionalProperties: false,
    },
    annotations: READ_ONLY_SAFE_ANNOTATIONS,
  },
  {
    name: "get_screen_ui",
    description:
      "Read the current Android screen as a compact JSON tree of visible and interactive UI elements (text, contentDesc, resourceId, clickable, editable, centerX, centerY, bounds). Defaults to scoping nodes to the active target or floating small window (scope: 'window') to avoid background clutter; pass scope: 'all' for split-screen multi-window views. Prefer this over take_screenshot because it is much faster and uses fewer tokens.",
    inputSchema: {
      type: "object",
      properties: {
        scope: {
          type: "string",
          enum: ["window", "all"],
          description:
            "Use 'window' (default) to collect nodes from only the active target/floating window (plus underlying app if a dialog is on top), or 'all' to include all visible windows.",
          default: "window",
        },
        includeNonInteractive: {
          type: "boolean",
          description:
            "If true, include non-clickable static text nodes as well (default: true).",
          default: true,
        },
        maxNodes: {
          type: "integer",
          description: "Maximum number of UI nodes to return (default: 160).",
          default: 160,
          minimum: 10,
          maximum: 500,
        },
      },
      additionalProperties: false,
    },
    annotations: READ_ONLY_SAFE_ANNOTATIONS,
  },
  {
    name: "take_screenshot",
    description:
      "Capture a compressed JPEG screenshot of the Android screen (requires Android 11+). Defaults to small-window cropping (scope: 'window') with crop offset metadata, or captures the full screen (scope: 'full'). Automatically attaches a multimodal visionDescription from Cloudflare Workers AI or the phone's configured custom vision API (unless describe: false), plus the inline JPEG image block, metadata, and compact screenUi fallback.",
    inputSchema: {
      type: "object",
      properties: {
        scope: {
          type: "string",
          enum: ["window", "full"],
          description:
            "Capture scope: 'window' (default) crops to the active floating/target app window with crop offset metadata; 'full' captures the entire screen.",
          default: "window",
        },
        prompt: {
          type: "string",
          description:
            "Optional question or focus instruction for multimodal vision analysis (max 500 characters).",
          maxLength: 500,
        },
        describe: {
          type: "boolean",
          description:
            "If true (default), attach an automatic multimodal visionDescription of the screenshot.",
          default: true,
        },
        quality: {
          type: "integer",
          description: "JPEG compression quality 20-90 (default: 45).",
          default: 45,
          minimum: 20,
          maximum: 90,
        },
        maxWidth: {
          type: "integer",
          description: "Resize width in pixels to keep payload small (default: 540).",
          default: 540,
          minimum: 320,
          maximum: 1440,
        },
        returnUi: {
          type: "boolean",
          description:
            "Also attach compact screenUi alongside the screenshot as a fallback (default: true).",
          default: true,
        },
      },
      additionalProperties: false,
    },
    annotations: READ_ONLY_SAFE_ANNOTATIONS,
  },
  {
    name: "tap",
    description:
      "Tap on the Android screen either by exact pixel coordinates (x, y) or by matching a visible UI element's text or resourceId.",
    inputSchema: {
      type: "object",
      properties: {
        x: {
          type: "integer",
          description: "X coordinate in screen pixels.",
        },
        y: {
          type: "integer",
          description: "Y coordinate in screen pixels.",
        },
        text: {
          type: "string",
          description:
            "Optional visible text or contentDesc to locate and tap automatically if x/y are omitted.",
        },
        resourceId: {
          type: "string",
          description:
            "Optional Android view resourceId substring to locate and tap if x/y are omitted.",
        },
        returnUi: RETURN_UI_SCHEMA,
      },
      additionalProperties: false,
    },
    annotations: ACTION_SAFE_ANNOTATIONS,
  },
  {
    name: "long_press",
    description: "Long-press at screen pixel coordinates (x, y).",
    inputSchema: {
      type: "object",
      properties: {
        x: { type: "integer", description: "X coordinate in screen pixels." },
        y: { type: "integer", description: "Y coordinate in screen pixels." },
        durationMs: {
          type: "integer",
          description: "Press duration in milliseconds (default: 800).",
          default: 800,
          minimum: 100,
          maximum: 5000,
        },
        returnUi: RETURN_UI_SCHEMA,
      },
      required: ["x", "y"],
      additionalProperties: false,
    },
    annotations: ACTION_SAFE_ANNOTATIONS,
  },
  {
    name: "swipe",
    description:
      "Swipe on the screen either using a high-level direction ('up', 'down', 'left', 'right') or explicit pixel coordinates.",
    inputSchema: {
      type: "object",
      properties: {
        direction: {
          type: "string",
          enum: ["up", "down", "left", "right"],
          description:
            "Semantic scroll direction: 'up' scrolls content down, 'down' scrolls content up.",
        },
        startX: { type: "integer" },
        startY: { type: "integer" },
        endX: { type: "integer" },
        endY: { type: "integer" },
        durationMs: {
          type: "integer",
          description: "Swipe gesture duration in milliseconds (default: 350).",
          default: 350,
          minimum: 100,
          maximum: 5000,
        },
        returnUi: RETURN_UI_SCHEMA,
      },
      additionalProperties: false,
    },
    annotations: ACTION_SAFE_ANNOTATIONS,
  },
  {
    name: "type_text",
    description:
      "Type text into the currently focused input field, or tap a target input field first by hint/text/resourceId.",
    inputSchema: {
      type: "object",
      properties: {
        text: {
          type: "string",
          description: "Text to input.",
        },
        targetText: {
          type: "string",
          description: "Optional label/hint of the input box to focus first.",
        },
        resourceId: {
          type: "string",
          description: "Optional resourceId of the input box to focus first.",
        },
        clearFirst: {
          type: "boolean",
          description: "Whether to replace existing text (default: true).",
          default: true,
        },
        pressEnter: {
          type: "boolean",
          description: "Attempt to submit/press Enter after typing (default: false).",
          default: false,
        },
        returnUi: RETURN_UI_SCHEMA,
      },
      required: ["text"],
      additionalProperties: false,
    },
    annotations: ACTION_SAFE_ANNOTATIONS,
  },
  {
    name: "open_app",
    description:
      "Launch an installed Android app by package name (e.g. 'com.android.settings') or human-readable app label (e.g. 'Settings', '微信'). By default uses the user's preferred window mode configured in the phone app (floating small window vs fullscreen).",
    inputSchema: {
      type: "object",
      properties: {
        app: {
          type: "string",
          description: "Android package name or app display name.",
        },
        windowMode: {
          type: "string",
          enum: ["default", "freeform", "fullscreen"],
          description:
            "Optional launch mode override: 'default' follows the phone app setting, 'freeform' launches in a floating small window, 'fullscreen' switches full screen.",
          default: "default",
        },
        returnUi: RETURN_UI_SCHEMA,
      },
      required: ["app"],
      additionalProperties: false,
    },
    annotations: ACTION_SAFE_ANNOTATIONS,
  },
  {
    name: "press_key",
    description:
      "Trigger a global Android navigation action: 'back', 'home', 'recents', 'notifications', or 'quick_settings'.",
    inputSchema: {
      type: "object",
      properties: {
        key: {
          type: "string",
          enum: ["back", "home", "recents", "notifications", "quick_settings"],
        },
        returnUi: RETURN_UI_SCHEMA,
      },
      required: ["key"],
      additionalProperties: false,
    },
    annotations: ACTION_SAFE_ANNOTATIONS,
  },
  {
    name: "return_to_gemini",
    description:
      "Switch the Android screen back to the Gemini app after completing all requested actions in other apps. Always call this as your final step after opening or operating other apps on the phone (it automatically respects the user's auto-return toggle on the device unless force=true).",
    inputSchema: {
      type: "object",
      properties: {
        force: {
          type: "boolean",
          description:
            "If true, return to Gemini even when the device's auto-return toggle is off (default: false).",
          default: false,
        },
      },
      additionalProperties: false,
    },
    annotations: READ_ONLY_SAFE_ANNOTATIONS,
  },
];

export interface McpToolExecutor {
  getDeviceStatus(): Promise<DeviceStatus>;
  executePhoneCommand(
    action: string,
    args: Record<string, unknown>,
  ): Promise<PhoneResponseEnvelope>;
  describeScreenshot?: (input: {
    imageBase64: string;
    mimeType: string;
    meta: Record<string, unknown>;
    prompt?: string;
  }) => Promise<Record<string, unknown> | null>;
}

interface FieldRule {
  type: "boolean" | "string" | "integer" | "enum";
  required?: boolean;
  nonEmpty?: boolean;
  min?: number;
  max?: number;
  values?: readonly string[];
}

const TOOL_FIELD_RULES: Record<string, Record<string, FieldRule>> = {
  get_device_status: {},
  get_screen_ui: {
    scope: { type: "enum", values: ["window", "all"] },
    includeNonInteractive: { type: "boolean" },
    maxNodes: { type: "integer", min: 10, max: 500 },
  },
  take_screenshot: {
    scope: { type: "enum", values: ["window", "full"] },
    prompt: { type: "string", nonEmpty: true, max: 500 },
    describe: { type: "boolean" },
    quality: { type: "integer", min: 20, max: 90 },
    maxWidth: { type: "integer", min: 320, max: 1440 },
    returnUi: { type: "boolean" },
  },
  tap: {
    x: { type: "integer" },
    y: { type: "integer" },
    text: { type: "string", nonEmpty: true },
    resourceId: { type: "string", nonEmpty: true },
    returnUi: { type: "boolean" },
  },
  long_press: {
    x: { type: "integer", required: true },
    y: { type: "integer", required: true },
    durationMs: { type: "integer", min: 100, max: 5000 },
    returnUi: { type: "boolean" },
  },
  swipe: {
    direction: { type: "enum", values: ["up", "down", "left", "right"] },
    startX: { type: "integer" },
    startY: { type: "integer" },
    endX: { type: "integer" },
    endY: { type: "integer" },
    durationMs: { type: "integer", min: 100, max: 5000 },
    returnUi: { type: "boolean" },
  },
  type_text: {
    text: { type: "string", required: true },
    targetText: { type: "string" },
    resourceId: { type: "string" },
    clearFirst: { type: "boolean" },
    pressEnter: { type: "boolean" },
    returnUi: { type: "boolean" },
  },
  open_app: {
    app: { type: "string", required: true, nonEmpty: true },
    windowMode: { type: "enum", values: ["default", "freeform", "fullscreen"] },
    returnUi: { type: "boolean" },
  },
  press_key: {
    key: {
      type: "enum",
      required: true,
      values: ["back", "home", "recents", "notifications", "quick_settings"],
    },
    returnUi: { type: "boolean" },
  },
  return_to_gemini: {
    force: { type: "boolean" },
  },
};

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function validateField(key: string, val: unknown, rule: FieldRule): string | null {
  switch (rule.type) {
    case "boolean":
      return typeof val === "boolean" ? null : `${key} must be a boolean`;
    case "string":
      if (typeof val !== "string") return `${key} must be a string`;
      if (rule.nonEmpty && val.trim().length === 0) {
        return `${key} must be a non-empty string`;
      }
      if (rule.max !== undefined && val.length > rule.max) {
        return `${key} must be at most ${rule.max} characters`;
      }
      return null;
    case "integer":
      if (typeof val !== "number" || !Number.isInteger(val)) {
        return `${key} must be an integer`;
      }
      if (rule.min !== undefined && val < rule.min) {
        return `${key} must be >= ${rule.min}`;
      }
      if (rule.max !== undefined && val > rule.max) {
        return `${key} must be <= ${rule.max}`;
      }
      return null;
    case "enum":
      if (typeof val !== "string" || !rule.values?.includes(val)) {
        return `${key} must be one of: ${rule.values?.join(", ") ?? ""}`;
      }
      return null;
  }
}

function validateToolArgs(
  name: string,
  args: Record<string, unknown>,
): string | null {
  const rules = TOOL_FIELD_RULES[name];
  if (!rules) return `Unknown tool: ${name}`;

  for (const key of Object.keys(args)) {
    if (!Object.prototype.hasOwnProperty.call(rules, key)) {
      return `Unknown argument: ${key}`;
    }
  }

  for (const [key, rule] of Object.entries(rules)) {
    const val = args[key];
    if (val === undefined) {
      if (rule.required) return `Missing required argument: ${key}`;
      continue;
    }
    const err = validateField(key, val, rule);
    if (err) return err;
  }

  if (name === "tap") {
    const hasX = args.x !== undefined;
    const hasY = args.y !== undefined;
    if (hasX !== hasY) {
      return "tap requires both x and y when specifying coordinates";
    }
    const hasTarget = args.text !== undefined || args.resourceId !== undefined;
    if (!(hasX && hasY) && !hasTarget) {
      return "tap requires either (x and y) or (text or resourceId)";
    }
  }

  if (name === "swipe") {
    const coordKeys = ["startX", "startY", "endX", "endY"] as const;
    const coordCount = coordKeys.filter((k) => args[k] !== undefined).length;
    if (coordCount > 0 && coordCount < 4) {
      return "swipe requires all four of startX, startY, endX, endY";
    }
    if (args.direction === undefined && coordCount !== 4) {
      return "swipe requires either direction or all four of startX, startY, endX, endY";
    }
  }

  return null;
}

function jsonRpcError(
  id: string | number | null,
  code: number,
  message: string,
): JsonRpcResponse {
  return {
    jsonrpc: "2.0",
    id,
    error: { code, message },
  };
}

export async function handleMcpJsonRpc(
  req: unknown,
  serverName: string,
  executor: McpToolExecutor,
): Promise<JsonRpcResponse | null> {
  if (!isPlainObject(req)) {
    return jsonRpcError(null, -32600, "Invalid Request: expected JSON-RPC object");
  }

  const hasId = Object.prototype.hasOwnProperty.call(req, "id") && req.id !== undefined;
  let id: string | number | null = null;
  if (hasId) {
    const rawId = req.id;
    if (
      rawId === null ||
      typeof rawId === "string" ||
      (typeof rawId === "number" && Number.isFinite(rawId))
    ) {
      id = rawId;
    } else {
      return jsonRpcError(null, -32600, "Invalid Request: invalid id");
    }
  }

  if (req.jsonrpc !== "2.0") {
    return jsonRpcError(id, -32600, "Invalid Request: jsonrpc must be '2.0'");
  }

  if (typeof req.method !== "string" || req.method.trim().length === 0) {
    return jsonRpcError(id, -32600, "Invalid Request: method must be a non-empty string");
  }

  const method = req.method;

  if (!hasId) {
    return null;
  }

  if (method.startsWith("notifications/")) {
    return jsonRpcError(id, -32600, "Invalid Request: notifications must not have an id");
  }

  if (
    Object.prototype.hasOwnProperty.call(req, "params") &&
    req.params !== undefined &&
    !isPlainObject(req.params)
  ) {
    return jsonRpcError(id, -32602, "Invalid params: params must be an object");
  }

  const params = isPlainObject(req.params) ? req.params : undefined;

  switch (method) {
    case "initialize": {
      const requestedVersion =
        typeof params?.protocolVersion === "string"
          ? params.protocolVersion
          : undefined;
      const protocolVersion =
        requestedVersion &&
        (SUPPORTED_PROTOCOL_VERSIONS as readonly string[]).includes(requestedVersion)
          ? requestedVersion
          : SUPPORTED_PROTOCOL_VERSION;
      return {
        jsonrpc: "2.0",
        id,
        result: {
          protocolVersion,
          capabilities: {
            tools: { listChanged: false },
          },
          serverInfo: {
            name: serverName,
            version: "1.1.0",
          },
          instructions:
            "You are controlling the user's Android phone via AccessibilityService. Action tools (open_app, tap, long_press, swipe, type_text, press_key) automatically return a compact updated screenUi by default (returnUi: true), so avoid redundant get_screen_ui calls immediately after an action; call get_screen_ui beforehand only when you need initial screen coordinates. Stop immediately if a sensitive payment/banking/password screen appears; if a tool result says the user rejected or did not confirm an action, stop and ask the user instead of retrying or working around it; and whenever you finish performing actions in another app, call return_to_gemini as your final tool call so the phone smoothly returns to the Gemini chat screen (unless the user explicitly asked to leave the target app open on screen).",
        },
      };
    }

    case "ping": {
      return {
        jsonrpc: "2.0",
        id,
        result: {},
      };
    }

    case "tools/list": {
      return {
        jsonrpc: "2.0",
        id,
        result: {
          tools: MCP_TOOLS,
        },
      };
    }

    case "tools/call": {
      const name = typeof params?.name === "string" ? params.name : "";
      const toolExists = MCP_TOOLS.some((t) => t.name === name);
      if (!toolExists) {
        return jsonRpcError(id, -32602, `Unknown tool: ${name}`);
      }

      const rawArgs = params?.arguments;
      if (rawArgs !== undefined && !isPlainObject(rawArgs)) {
        return jsonRpcError(id, -32602, "Invalid params: arguments must be an object");
      }
      const args = rawArgs ?? {};

      const validationError = validateToolArgs(name, args);
      if (validationError) {
        return jsonRpcError(id, -32602, `Invalid params: ${validationError}`);
      }

      try {
        if (name === "get_device_status") {
          const status = await executor.getDeviceStatus();
          return {
            jsonrpc: "2.0",
            id,
            result: {
              content: [
                {
                  type: "text",
                  text: JSON.stringify(status, null, 2),
                },
              ],
              isError: false,
            },
          };
        }

        const phoneRes = await executor.executePhoneCommand(name, args);
        if (!phoneRes.ok) {
          return {
            jsonrpc: "2.0",
            id,
            result: {
              content: [
                {
                  type: "text",
                  text: `Phone execution error: ${phoneRes.error ?? "Unknown error"}`,
                },
              ],
              isError: true,
            },
          };
        }

        let responseData: unknown = phoneRes.data;
        if (
          name === "take_screenshot" &&
          phoneRes.ok &&
          phoneRes.imageBase64 &&
          args.describe !== false &&
          executor.describeScreenshot
        ) {
          const existingMeta = isPlainObject(phoneRes.data) ? phoneRes.data : null;
          const alreadyDescribed =
            existingMeta !== null &&
            (Boolean(existingMeta.visionDescription) ||
              existingMeta.visionProvider === "custom");
          if (!alreadyDescribed) {
            try {
              const visionFields = await executor.describeScreenshot({
                imageBase64: phoneRes.imageBase64,
                mimeType: phoneRes.imageMimeType ?? "image/jpeg",
                meta: existingMeta ?? {},
                prompt: typeof args.prompt === "string" ? args.prompt : undefined,
              });
              if (visionFields && isPlainObject(visionFields)) {
                responseData = {
                  ...(existingMeta ?? {}),
                  ...visionFields,
                };
              }
            } catch (visionErr) {
              const visionMsg =
                visionErr instanceof Error ? visionErr.message : String(visionErr);
              responseData = {
                ...(existingMeta ?? {}),
                visionError: visionMsg,
              };
            }
          }
        }

        const content: Array<Record<string, unknown>> = [];
        if (phoneRes.imageBase64) {
          content.push({
            type: "image",
            data: phoneRes.imageBase64,
            mimeType: phoneRes.imageMimeType ?? "image/jpeg",
          });
        }
        if (responseData !== undefined && responseData !== null) {
          content.push({
            type: "text",
            text:
              typeof responseData === "string"
                ? responseData
                : JSON.stringify(responseData),
          });
        }
        if (content.length === 0) {
          content.push({ type: "text", text: "OK" });
        }

        return {
          jsonrpc: "2.0",
          id,
          result: {
            content,
            isError: false,
          },
        };
      } catch (err) {
        const message = err instanceof Error ? err.message : String(err);
        return {
          jsonrpc: "2.0",
          id,
          result: {
            content: [{ type: "text", text: `Relay error: ${message}` }],
            isError: true,
          },
        };
      }
    }

    default: {
      return jsonRpcError(id, -32601, `Method not found: ${method}`);
    }
  }
}
