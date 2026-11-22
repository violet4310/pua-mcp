export interface Env {
  PHONE_BRIDGE: DurableObjectNamespace;
  AI?: Ai;
  RELAY_TOKEN?: string;
  /** Optional separate secret for the phone's /ws connection. If unset, RELAY_TOKEN is used. */
  PHONE_TOKEN?: string;
  OAUTH_KV?: KVNamespace;
  OAUTH_ORIGIN?: string;
  OAUTH_OWNER_KEY?: string;
  MCP_SERVER_NAME?: string;
  COMMAND_TIMEOUT_MS?: string;
  WORKERS_AI_VISION_MODEL?: string;
  ALLOWED_ORIGINS?: string;
}

export interface JsonRpcRequest {
  jsonrpc: "2.0";
  id?: string | number | null;
  method: string;
  params?: Record<string, unknown>;
}

export interface JsonRpcResponse {
  jsonrpc: "2.0";
  id: string | number | null;
  result?: unknown;
  error?: {
    code: number;
    message: string;
    data?: unknown;
  };
}

export interface PhoneCommandEnvelope {
  type: "command";
  requestId: string;
  action: string;
  args: Record<string, unknown>;
  timestamp: number;
}

export interface PhoneResponseEnvelope {
  type: "response";
  requestId: string;
  ok: boolean;
  data?: unknown;
  imageBase64?: string;
  imageMimeType?: string;
  error?: string;
}

export type WindowLaunchMode = "freeform" | "fullscreen";

export interface PhoneHelloEnvelope {
  type: "hello";
  deviceName?: string;
  androidVersion?: string;
  screenWidth?: number;
  screenHeight?: number;
  accessibilityEnabled?: boolean;
  windowMode?: WindowLaunchMode;
  autoReturnGemini?: boolean;
}

export interface PhonePongEnvelope {
  type: "pong";
  timestamp?: number;
}

export type InboundPhoneMessage =
  | PhoneResponseEnvelope
  | PhoneHelloEnvelope
  | PhonePongEnvelope;

export interface DeviceStatus {
  connected: boolean;
  deviceName: string;
  androidVersion: string;
  screenWidth: number;
  screenHeight: number;
  accessibilityEnabled: boolean;
  windowMode: WindowLaunchMode;
  autoReturnGemini: boolean;
  lastSeenAt: string | null;
  /** True when the live probe to the phone timed out while other commands were in flight and cached metadata was returned. */
  stale?: boolean;
}
