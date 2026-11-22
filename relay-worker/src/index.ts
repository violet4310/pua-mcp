import { PhoneBridgeDO } from "./bridge";
import { handleMcpJsonRpc, type McpToolExecutor } from "./mcp";
import { routeMcpOAuth } from "./oauth";
import { analyzeScreenshotWithWorkersAi } from "./vision";
import type {
  DeviceStatus,
  Env,
  JsonRpcResponse,
  PhoneResponseEnvelope,
} from "./types";

export { PhoneBridgeDO };

const TEXT_ENCODER = new TextEncoder();
const DEVICE_KEY_PATTERN = /^[a-zA-Z0-9_-]{1,64}$/;

function parseAllowedOrigins(raw?: string): string[] {
  return (raw ?? "")
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean);
}

function isOriginAllowed(request: Request, env: Env): boolean {
  const allowed = parseAllowedOrigins(env.ALLOWED_ORIGINS);
  if (allowed.length === 0 || allowed.includes("*")) {
    return true;
  }
  const origin = request.headers.get("Origin");
  if (!origin) {
    return true;
  }
  return allowed.includes(origin);
}

function buildCorsHeaders(request: Request, env: Env): Record<string, string> {
  const allowed = parseAllowedOrigins(env.ALLOWED_ORIGINS);
  const origin = request.headers.get("Origin");
  const headers: Record<string, string> = {
    "Access-Control-Allow-Methods": "GET, POST, DELETE, OPTIONS",
    "Access-Control-Allow-Headers":
      "Content-Type, Authorization, X-API-Key, Accept, Mcp-Session-Id, MCP-Protocol-Version",
    "Access-Control-Expose-Headers": "Mcp-Session-Id, WWW-Authenticate",
  };

  if (allowed.length === 0 || allowed.includes("*")) {
    headers["Access-Control-Allow-Origin"] = "*";
  } else {
    headers["Vary"] = "Origin";
    if (origin && allowed.includes(origin)) {
      headers["Access-Control-Allow-Origin"] = origin;
    }
  }

  return headers;
}

async function constantTimeEqual(a: string, b: string): Promise<boolean> {
  const [aDigest, bDigest] = await Promise.all([
    crypto.subtle.digest("SHA-256", TEXT_ENCODER.encode(a)),
    crypto.subtle.digest("SHA-256", TEXT_ENCODER.encode(b)),
  ]);
  const aBytes = new Uint8Array(aDigest);
  const bBytes = new Uint8Array(bDigest);
  let diff = 0;
  for (let i = 0; i < aBytes.length; i++) {
    diff |= aBytes[i] ^ bBytes[i];
  }
  return diff === 0;
}

async function headerTokenMatches(
  request: Request,
  expected: string,
): Promise<boolean> {
  const authHeader = request.headers.get("Authorization")?.trim() ?? "";
  if (authHeader.toLowerCase().startsWith("bearer ")) {
    const bearer = authHeader.slice(7).trim();
    if (await constantTimeEqual(bearer, expected)) {
      return true;
    }
  } else if (authHeader && (await constantTimeEqual(authHeader, expected))) {
    return true;
  }

  const apiKeyHeader = request.headers.get("X-API-Key")?.trim();
  return Boolean(apiKeyHeader && (await constantTimeEqual(apiKeyHeader, expected)));
}

/**
 * Phone WebSocket (/ws): header-only auth, using PHONE_TOKEN when configured so a
 * RELAY_TOKEN shared with an MCP client cannot be used to impersonate the phone.
 */
async function isPhoneAuthorized(request: Request, env: Env): Promise<boolean> {
  const expected = env.PHONE_TOKEN?.trim() || env.RELAY_TOKEN?.trim() || "";
  if (!expected) {
    return false;
  }
  return headerTokenMatches(request, expected);
}

/**
 * MCP client (/mcp): RELAY_TOKEN via header (recommended), or ?token=/?key= fallback
 * for MCP clients that cannot attach custom HTTP headers.
 */
async function isMcpAuthorized(request: Request, env: Env): Promise<boolean> {
  const expected = env.RELAY_TOKEN?.trim();
  if (!expected) {
    // Reject requests if RELAY_TOKEN secret has not been configured yet
    return false;
  }

  if (await headerTokenMatches(request, expected)) {
    return true;
  }

  const url = new URL(request.url);
  const queryToken =
    url.searchParams.get("token")?.trim() ||
    url.searchParams.get("key")?.trim() ||
    "";
  return Boolean(queryToken && (await constantTimeEqual(queryToken, expected)));
}

function getBridgeStub(env: Env, deviceKey = "primary"): DurableObjectStub {
  const id = env.PHONE_BRIDGE.idFromName(deviceKey);
  return env.PHONE_BRIDGE.get(id);
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function jsonWithCors(
  body: unknown,
  status = 200,
  corsHeaders: Record<string, string> = {},
  extraHeaders: Record<string, string> = {},
): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      ...corsHeaders,
      ...extraHeaders,
    },
  });
}

const relay = {
  async fetch(request: Request, env: Env, mcpAuthorized = false): Promise<Response> {
    const url = new URL(request.url);
    const corsHeaders = buildCorsHeaders(request, env);

    // Handle CORS preflight
    if (request.method === "OPTIONS") {
      if (!isOriginAllowed(request, env)) {
        return new Response(null, { status: 403, headers: corsHeaders });
      }
      return new Response(null, { status: 204, headers: corsHeaders });
    }

    // Public health check (no secret or config status leaked)
    if (url.pathname === "/" || url.pathname === "/health") {
      return jsonWithCors(
        {
          ok: true,
          service: env.MCP_SERVER_NAME ?? "android-phone-controller",
        },
        200,
        corsHeaders,
      );
    }

    // Authorize /ws (phone, header-only, PHONE_TOKEN or RELAY_TOKEN) and /mcp (RELAY_TOKEN)
    const authorized =
      url.pathname === "/ws"
        ? await isPhoneAuthorized(request, env)
        : (url.pathname === "/mcp" && mcpAuthorized) || await isMcpAuthorized(request, env);
    if (!authorized) {
      return jsonWithCors(
        {
          error: "Unauthorized",
          hint:
            url.pathname === "/ws"
              ? "Phone must send Authorization: Bearer <PHONE_TOKEN, or RELAY_TOKEN if PHONE_TOKEN is unset>."
              : "Pass Authorization: Bearer <RELAY_TOKEN> header (recommended; ?token= query fallback is only for clients unable to set headers). Make sure RELAY_TOKEN is set in Cloudflare Worker Secrets.",
        },
        401,
        corsHeaders,
      );
    }

    const rawDevice = url.searchParams.get("device")?.trim() ?? "";
    const deviceKey = rawDevice === "" ? "primary" : rawDevice;
    if (!DEVICE_KEY_PATTERN.test(deviceKey)) {
      return jsonWithCors(
        {
          error: "Invalid device parameter",
          hint: "Query parameter 'device' must match /^[a-zA-Z0-9_-]{1,64}$/",
        },
        400,
        corsHeaders,
      );
    }

    const stub = getBridgeStub(env, deviceKey);

    // Phone WebSocket connection
    if (url.pathname === "/ws") {
      if (!isOriginAllowed(request, env)) {
        return jsonWithCors(
          { error: "Forbidden: Origin not allowed" },
          403,
          corsHeaders,
        );
      }
      return stub.fetch(request);
    }

    // MCP Streamable HTTP endpoint
    if (url.pathname === "/mcp") {
      if (request.method === "DELETE") {
        return new Response(null, { status: 204, headers: corsHeaders });
      }

      if (request.method === "GET") {
        // Streamable HTTP spec allows returning 405 if server doesn't push unsolicited SSE notifications
        return new Response("Method Not Allowed (use POST for JSON-RPC)", {
          status: 405,
          headers: { Allow: "POST, DELETE, OPTIONS", ...corsHeaders },
        });
      }

      if (request.method !== "POST") {
        return jsonWithCors({ error: "Method Not Allowed" }, 405, corsHeaders);
      }

      let payload: unknown;
      try {
        payload = (await request.json()) as unknown;
      } catch {
        return jsonWithCors(
          {
            jsonrpc: "2.0",
            id: null,
            error: { code: -32700, message: "Parse error: invalid JSON" },
          },
          400,
          corsHeaders,
        );
      }

      if (Array.isArray(payload)) {
        if (payload.length === 0) {
          return jsonWithCors(
            {
              jsonrpc: "2.0",
              id: null,
              error: { code: -32600, message: "Invalid Request: empty batch" },
            },
            400,
            corsHeaders,
          );
        }
      } else if (!isPlainObject(payload)) {
        return jsonWithCors(
          {
            jsonrpc: "2.0",
            id: null,
            error: {
              code: -32600,
              message: "Invalid Request: expected JSON-RPC object or non-empty array",
            },
          },
          400,
          corsHeaders,
        );
      }

      const sessionId =
        request.headers.get("Mcp-Session-Id") ?? crypto.randomUUID();
      const serverName = env.MCP_SERVER_NAME ?? "android-phone-controller";

      const executor: McpToolExecutor = {
        async getDeviceStatus(): Promise<DeviceStatus> {
          const res = await stub.fetch("https://bridge.internal/internal/status");
          const text = await res.text();
          if (!res.ok) {
            throw new Error(
              `Bridge status error (${res.status}): ${text || res.statusText}`,
            );
          }
          try {
            return JSON.parse(text) as DeviceStatus;
          } catch {
            throw new Error("Bridge returned invalid JSON for device status.");
          }
        },
        async executePhoneCommand(
          action: string,
          args: Record<string, unknown>,
        ): Promise<PhoneResponseEnvelope> {
          const res = await stub.fetch(
            "https://bridge.internal/internal/command",
            {
              method: "POST",
              headers: { "Content-Type": "application/json" },
              body: JSON.stringify({ action, args }),
            },
          );
          const text = await res.text();
          let parsed: unknown = null;
          if (text) {
            try {
              parsed = JSON.parse(text);
            } catch {
              parsed = null;
            }
          }
          if (
            isPlainObject(parsed) &&
            parsed.type === "response"
          ) {
            return parsed as unknown as PhoneResponseEnvelope;
          }
          if (!res.ok) {
            throw new Error(
              `Bridge command error (${res.status}): ${text || res.statusText}`,
            );
          }
          throw new Error("Bridge returned invalid JSON for command response.");
        },
        describeScreenshot(input) {
          return analyzeScreenshotWithWorkersAi(env, input);
        },
      };

      // Support single or batched JSON-RPC requests
      if (Array.isArray(payload)) {
        const responses: JsonRpcResponse[] = [];
        for (const item of payload) {
          const r = await handleMcpJsonRpc(item, serverName, executor);
          if (r !== null) responses.push(r);
        }
        if (responses.length === 0) {
          return new Response(null, {
            status: 202,
            headers: { "Mcp-Session-Id": sessionId, ...corsHeaders },
          });
        }
        return jsonWithCors(responses, 200, corsHeaders, {
          "Mcp-Session-Id": sessionId,
        });
      }

      const response = await handleMcpJsonRpc(payload, serverName, executor);
      if (response === null) {
        return new Response(null, {
          status: 202,
          headers: { "Mcp-Session-Id": sessionId, ...corsHeaders },
        });
      }

      return jsonWithCors(response, 200, corsHeaders, {
        "Mcp-Session-Id": sessionId,
      });
    }

    return jsonWithCors({ error: "Not Found" }, 404, corsHeaders);
  },
};

export default {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const url = new URL(request.url);
    if (url.pathname === "/mcp" && request.method !== "OPTIONS" &&
        await isMcpAuthorized(request, env)) {
      return relay.fetch(request, env, true);
    }

    const oauthResponse = await routeMcpOAuth(request, env, ctx, {
      handleMcp: () => relay.fetch(request, env, true),
      verifyOwner: (candidate, ownerKey) => constantTimeEqual(candidate, ownerKey),
      originAllowed: isOriginAllowed(request, env),
    });
    return oauthResponse ?? relay.fetch(request, env);
  },
};
