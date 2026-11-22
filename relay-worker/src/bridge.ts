import { DurableObject } from "cloudflare:workers";
import type {
  DeviceStatus,
  Env,
  InboundPhoneMessage,
  PhoneCommandEnvelope,
  PhoneResponseEnvelope,
  WindowLaunchMode,
} from "./types";

interface PendingRequest {
  ws: WebSocket;
  resolve: (res: PhoneResponseEnvelope) => void;
  reject: (err: Error) => void;
  timer: ReturnType<typeof setTimeout>;
}

interface StoredDeviceMeta {
  deviceName: string;
  androidVersion: string;
  screenWidth: number;
  screenHeight: number;
  accessibilityEnabled: boolean;
  windowMode: WindowLaunchMode;
  autoReturnGemini: boolean;
  lastSeenAt: string;
}

const META_KEY = "device_meta";
const DEFAULT_COMMAND_TIMEOUT_MS = 25000;
const MIN_COMMAND_TIMEOUT_MS = 1000;
const MAX_COMMAND_TIMEOUT_MS = 120000;
const STATUS_PROBE_TIMEOUT_MS = 6000;
const LAST_SEEN_PERSIST_INTERVAL_MS = 60000;

function normalizeWindowMode(val: unknown, fallback: WindowLaunchMode): WindowLaunchMode {
  if (val === "freeform" || val === "fullscreen") return val;
  return fallback;
}

function normalizePositiveDim(val: unknown, fallback: number): number {
  const n = typeof val === "number" ? val : Number(val);
  if (Number.isFinite(n) && n > 0) return Math.round(n);
  return fallback;
}

function parseTimeoutMs(raw: unknown, fallback: number): number {
  if (raw === undefined || raw === null || raw === "") return fallback;
  const n = typeof raw === "number" ? raw : Number(raw);
  if (!Number.isFinite(n) || n <= 0) return fallback;
  return Math.min(
    MAX_COMMAND_TIMEOUT_MS,
    Math.max(MIN_COMMAND_TIMEOUT_MS, Math.round(n)),
  );
}

function toDeviceStatus(connected: boolean, meta: StoredDeviceMeta): DeviceStatus {
  return {
    connected,
    deviceName: meta.deviceName,
    androidVersion: meta.androidVersion,
    screenWidth: meta.screenWidth,
    screenHeight: meta.screenHeight,
    accessibilityEnabled: meta.accessibilityEnabled,
    windowMode: meta.windowMode,
    autoReturnGemini: meta.autoReturnGemini,
    lastSeenAt: meta.lastSeenAt || null,
  };
}

export class PhoneBridgeDO extends DurableObject<Env> {
  private pending = new Map<string, PendingRequest>();
  private cachedMeta: StoredDeviceMeta | null = null;
  private metaMutex: Promise<void> = Promise.resolve();
  private lastPersistedMetaAtMs = 0;
  private consecutiveTimeouts = 0;

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    // Use WebSocket Hibernation auto-response so keepalive pings don't wake the DO or burn free-tier duration
    this.ctx.setWebSocketAutoResponse(
      new WebSocketRequestResponsePair("ping", "pong"),
    );
  }

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);

    // 1. Android phone connects here via WebSocket
    if (url.pathname === "/ws") {
      const upgradeHeader = request.headers.get("Upgrade");
      if (!upgradeHeader || upgradeHeader.toLowerCase() !== "websocket") {
        return new Response("Expected Upgrade: websocket", { status: 426 });
      }

      // Close older stale sockets and reject only their in-flight requests
      for (const existing of this.ctx.getWebSockets("phone")) {
        this.failPendingForSocket(existing, "Replaced by newer phone connection.");
        try {
          existing.close(1000, "Replaced by newer phone connection");
        } catch {
          // ignore
        }
      }

      const pair = new WebSocketPair();
      const [client, server] = Object.values(pair);

      this.ctx.acceptWebSocket(server, ["phone"]);
      this.consecutiveTimeouts = 0;

      // Seed initial metadata from query params if provided
      const accessibilityParam = url.searchParams.get("acc");
      const autoReturnParam = url.searchParams.get("autoReturn");
      const deviceNameParam = url.searchParams.get("deviceName");
      const androidVersionParam = url.searchParams.get("androidVersion");
      const widthParam = url.searchParams.get("w");
      const heightParam = url.searchParams.get("h");
      const winModeParam = url.searchParams.get("winMode");

      await this.updateMeta(
        (existingMeta) => ({
          deviceName: deviceNameParam ?? existingMeta.deviceName,
          androidVersion: androidVersionParam ?? existingMeta.androidVersion,
          screenWidth: normalizePositiveDim(widthParam, existingMeta.screenWidth),
          screenHeight: normalizePositiveDim(heightParam, existingMeta.screenHeight),
          accessibilityEnabled:
            accessibilityParam !== null
              ? accessibilityParam === "1" || accessibilityParam === "true"
              : existingMeta.accessibilityEnabled,
          windowMode: normalizeWindowMode(winModeParam, existingMeta.windowMode),
          autoReturnGemini:
            autoReturnParam !== null
              ? autoReturnParam === "1" || autoReturnParam === "true"
              : existingMeta.autoReturnGemini,
          lastSeenAt: new Date().toISOString(),
        }),
        { forcePersist: true },
      );

      return new Response(null, { status: 101, webSocket: client });
    }

    // 2. Internal RPC: get live device status
    if (url.pathname === "/internal/status" && request.method === "GET") {
      const status = await this.getDeviceStatus();
      return Response.json(status);
    }

    // 3. Internal RPC: dispatch a command to the connected Android phone
    if (url.pathname === "/internal/command" && request.method === "POST") {
      try {
        const body = (await request.json()) as {
          action: string;
          args?: Record<string, unknown>;
          timeoutMs?: number;
        };
        const envTimeout = parseTimeoutMs(
          this.env.COMMAND_TIMEOUT_MS,
          DEFAULT_COMMAND_TIMEOUT_MS,
        );
        const timeoutMs = parseTimeoutMs(body.timeoutMs, envTimeout);
        const res = await this.sendCommand(
          body.action,
          body.args ?? {},
          timeoutMs,
        );
        return Response.json(res);
      } catch (err) {
        const message = err instanceof Error ? err.message : String(err);
        return Response.json(
          {
            type: "response",
            requestId: "",
            ok: false,
            error: message,
          } satisfies PhoneResponseEnvelope,
          { status: 503 },
        );
      }
    }

    return new Response("Not Found", { status: 404 });
  }

  async webSocketMessage(
    _ws: WebSocket,
    rawMessage: string | ArrayBuffer,
  ): Promise<void> {
    this.consecutiveTimeouts = 0;

    const text =
      typeof rawMessage === "string"
        ? rawMessage
        : new TextDecoder().decode(rawMessage);

    if (text === "ping") {
      return;
    }

    let msg: InboundPhoneMessage;
    try {
      msg = JSON.parse(text) as InboundPhoneMessage;
    } catch {
      return;
    }

    if (msg.type === "hello") {
      await this.updateMeta((prev) => ({
        deviceName: msg.deviceName ?? prev.deviceName,
        androidVersion: msg.androidVersion ?? prev.androidVersion,
        screenWidth: normalizePositiveDim(msg.screenWidth, prev.screenWidth),
        screenHeight: normalizePositiveDim(msg.screenHeight, prev.screenHeight),
        accessibilityEnabled:
          typeof msg.accessibilityEnabled === "boolean"
            ? msg.accessibilityEnabled
            : prev.accessibilityEnabled,
        windowMode: normalizeWindowMode(msg.windowMode, prev.windowMode),
        autoReturnGemini:
          typeof msg.autoReturnGemini === "boolean"
            ? msg.autoReturnGemini
            : prev.autoReturnGemini,
        lastSeenAt: new Date().toISOString(),
      }));
      return;
    }

    if (msg.type === "pong") {
      try {
        await this.updateMeta((prev) => ({
          ...prev,
          lastSeenAt: new Date().toISOString(),
        }));
      } catch {
        // ignore transient storage errors on keepalive
      }
      return;
    }

    if (msg.type === "response" && msg.requestId) {
      const entry = this.pending.get(msg.requestId);
      if (entry) {
        clearTimeout(entry.timer);
        this.pending.delete(msg.requestId);
        entry.resolve(msg);
      }

      try {
        await this.updateMeta((prev) => ({
          ...prev,
          lastSeenAt: new Date().toISOString(),
        }));
      } catch {
        // ignore storage error after response is already resolved
      }
    }
  }

  async webSocketClose(ws: WebSocket): Promise<void> {
    this.consecutiveTimeouts = 0;
    try {
      ws.close();
    } catch {
      // ignore
    }
    this.failPendingForSocket(ws, "Android phone disconnected.");
  }

  async webSocketError(ws: WebSocket): Promise<void> {
    this.consecutiveTimeouts = 0;
    try {
      ws.close(1011, "WebSocket error");
    } catch {
      // ignore
    }
    this.failPendingForSocket(ws, "Android phone WebSocket error.");
  }

  private getActivePhoneSocket(): WebSocket | null {
    const sockets = this.ctx.getWebSockets("phone");
    for (let i = sockets.length - 1; i >= 0; i--) {
      if (sockets[i].readyState === WebSocket.OPEN) {
        return sockets[i];
      }
    }
    return null;
  }

  private async getDeviceStatus(): Promise<DeviceStatus> {
    const socket = this.getActivePhoneSocket();
    if (!socket) {
      const meta = await this.getStoredMeta();
      return toDeviceStatus(false, meta);
    }

    // Query live status directly from RelayForegroundService so all settings are always fresh
    try {
      const res = await this.sendCommand("get_status", {}, STATUS_PROBE_TIMEOUT_MS);
      if (res.ok && res.data && typeof res.data === "object") {
        const live = res.data as Partial<StoredDeviceMeta>;
        const updated = await this.updateMeta((prev) => ({
          deviceName: live.deviceName ?? prev.deviceName,
          androidVersion: live.androidVersion ?? prev.androidVersion,
          screenWidth: normalizePositiveDim(live.screenWidth, prev.screenWidth),
          screenHeight: normalizePositiveDim(live.screenHeight, prev.screenHeight),
          accessibilityEnabled:
            typeof live.accessibilityEnabled === "boolean"
              ? live.accessibilityEnabled
              : prev.accessibilityEnabled,
          windowMode: normalizeWindowMode(live.windowMode, prev.windowMode),
          autoReturnGemini:
            typeof live.autoReturnGemini === "boolean"
              ? live.autoReturnGemini
              : prev.autoReturnGemini,
          lastSeenAt: new Date().toISOString(),
        }));
        return toDeviceStatus(true, updated);
      }
    } catch {
      if (this.pending.size > 0) {
        const meta = await this.getStoredMeta();
        return {
          ...toDeviceStatus(true, meta),
          stale: true,
        };
      }

      this.consecutiveTimeouts = 0;
      this.failPendingForSocket(socket, "Status probe timed out");
      try {
        socket.close(1001, "Status probe timed out");
      } catch {
        // ignore
      }
      const meta = await this.getStoredMeta();
      return toDeviceStatus(false, meta);
    }

    const meta = await this.getStoredMeta();
    return toDeviceStatus(true, meta);
  }

  private sendCommand(
    action: string,
    args: Record<string, unknown>,
    timeoutMs: number,
  ): Promise<PhoneResponseEnvelope> {
    const socket = this.getActivePhoneSocket();
    if (!socket) {
      return Promise.reject(
        new Error(
          "Android phone is offline. Open the Gemini Phone Agent app on your phone and tap Save & Connect.",
        ),
      );
    }

    const requestId = crypto.randomUUID();
    const envelope: PhoneCommandEnvelope = {
      type: "command",
      requestId,
      action,
      args,
      timestamp: Date.now(),
    };

    return new Promise<PhoneResponseEnvelope>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(requestId);
        this.consecutiveTimeouts += 1;
        const shouldCloseWedged = this.consecutiveTimeouts >= 2;
        reject(
          new Error(
            `Timed out after ${timeoutMs}ms waiting for Android phone to execute '${action}'.`,
          ),
        );
        if (shouldCloseWedged) {
          this.consecutiveTimeouts = 0;
          this.failPendingForSocket(
            socket,
            "WebSocket timed out repeatedly and was closed for reconnect.",
          );
          try {
            socket.close(1001, "Repeated command timeouts");
          } catch {
            // ignore
          }
        }
      }, timeoutMs);

      this.pending.set(requestId, { ws: socket, resolve, reject, timer });

      try {
        socket.send(JSON.stringify(envelope));
      } catch (err) {
        clearTimeout(timer);
        this.pending.delete(requestId);
        reject(
          err instanceof Error
            ? err
            : new Error("Failed to send command over WebSocket."),
        );
      }
    });
  }

  private failPendingForSocket(ws: WebSocket, reason: string): void {
    for (const [reqId, entry] of this.pending.entries()) {
      if (entry.ws === ws) {
        clearTimeout(entry.timer);
        entry.reject(new Error(reason));
        this.pending.delete(reqId);
      }
    }
  }

  private async loadMetaLocked(): Promise<StoredDeviceMeta> {
    if (this.cachedMeta) {
      return { ...this.cachedMeta };
    }
    const stored = await this.ctx.storage.get<StoredDeviceMeta>(META_KEY);
    const meta: StoredDeviceMeta = {
      deviceName: stored?.deviceName ?? "Unknown Android",
      androidVersion: stored?.androidVersion ?? "Unknown",
      screenWidth: stored?.screenWidth ?? 0,
      screenHeight: stored?.screenHeight ?? 0,
      accessibilityEnabled: stored?.accessibilityEnabled ?? false,
      windowMode: normalizeWindowMode(stored?.windowMode, "freeform"),
      autoReturnGemini:
        typeof stored?.autoReturnGemini === "boolean"
          ? stored.autoReturnGemini
          : true,
      lastSeenAt: stored?.lastSeenAt ?? "",
    };
    this.cachedMeta = meta;
    return { ...meta };
  }

  private getStoredMeta(): Promise<StoredDeviceMeta> {
    if (this.cachedMeta) {
      return Promise.resolve({ ...this.cachedMeta });
    }
    const run = this.metaMutex.then(() => this.loadMetaLocked());
    this.metaMutex = run.then(
      () => undefined,
      () => undefined,
    );
    return run;
  }

  private updateMeta(
    mutator: (prev: StoredDeviceMeta) => StoredDeviceMeta,
    options: { forcePersist?: boolean } = {},
  ): Promise<StoredDeviceMeta> {
    const run = this.metaMutex.then(async () => {
      const prev = await this.loadMetaLocked();
      const next = mutator(prev);
      const fieldsChanged =
        next.deviceName !== prev.deviceName ||
        next.androidVersion !== prev.androidVersion ||
        next.screenWidth !== prev.screenWidth ||
        next.screenHeight !== prev.screenHeight ||
        next.accessibilityEnabled !== prev.accessibilityEnabled ||
        next.windowMode !== prev.windowMode ||
        next.autoReturnGemini !== prev.autoReturnGemini;

      this.cachedMeta = next;

      const now = Date.now();
      const shouldPersist =
        options.forcePersist ||
        fieldsChanged ||
        this.lastPersistedMetaAtMs === 0 ||
        now - this.lastPersistedMetaAtMs >= LAST_SEEN_PERSIST_INTERVAL_MS;

      if (shouldPersist) {
        await this.ctx.storage.put(META_KEY, next);
        this.lastPersistedMetaAtMs = now;
      }

      return { ...next };
    });

    this.metaMutex = run.then(
      () => undefined,
      () => undefined,
    );

    return run;
  }
}
