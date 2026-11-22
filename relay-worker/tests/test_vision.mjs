import { after, describe, it } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const workerRoot = path.resolve(__dirname, "..");
const srcDir = path.join(workerRoot, "src");
const require = createRequire(import.meta.url);
const ts = require(path.join(workerRoot, "node_modules", "typescript"));

// Transpile only the TypeScript modules under test to a temporary directory so
// Node's built-in test runner can import extensionless TS files without flags.
const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "relay-vision-test-"));
after(() => {
  fs.rmSync(tmpDir, { recursive: true, force: true });
});

for (const name of ["types.ts", "vision.ts", "mcp.ts"]) {
  const source = fs.readFileSync(path.join(srcDir, name), "utf8");
  const { outputText } = ts.transpileModule(source, {
    fileName: name,
    compilerOptions: {
      target: ts.ScriptTarget.ES2022,
      module: ts.ModuleKind.CommonJS,
      esModuleInterop: true,
      strict: true,
    },
  });
  const outName = name.replace(/\.ts$/, ".cjs");
  // Rewrite relative TS module imports (e.g. ./types, ./vision) to .cjs
  const rewritten = outputText.replace(
    /require\("\.\/(types|vision|mcp)"\)/g,
    'require("./$1.cjs")',
  );
  fs.writeFileSync(path.join(tmpDir, outName), rewritten, "utf8");
}

const vision = require(path.join(tmpDir, "vision.cjs"));
const mcp = require(path.join(tmpDir, "mcp.cjs"));

const {
  DEFAULT_WORKERS_AI_VISION_MODEL,
  FALLBACK_WORKERS_AI_VISION_MODELS,
  buildVisionPrompt,
  extractVisionText,
  analyzeScreenshotWithWorkersAi,
} = vision;
const { MCP_TOOLS, handleMcpJsonRpc } = mcp;

describe("buildVisionPrompt", () => {
  it("describes cropped floating windows with full-screen bounds and offsets", () => {
    const prompt = buildVisionPrompt(
      {
        cropped: true,
        cropOffsetX: 120,
        cropOffsetY: 240,
        cropWidth: 720,
        cropHeight: 1100,
        screenWidth: 1080,
        screenHeight: 2400,
      },
      "Find the checkout button",
    );

    assert.match(prompt, /\[120, 240, 840, 1340\]/);
    assert.match(prompt, /1080x2400/);
    assert.match(prompt, /\(\+120, \+240\)/);
    assert.match(prompt, /User focus: Find the checkout button/);
  });

  it("describes full-screen captures without crop bounds and ignores blank userPrompt", () => {
    const prompt = buildVisionPrompt(
      {
        cropped: false,
        screenWidth: 1080,
        screenHeight: 2400,
      },
      "   ",
    );

    assert.match(prompt, /1080x2400 full screen/);
    assert.doesNotMatch(prompt, /cropped Android floating/);
    assert.doesNotMatch(prompt, /User focus:/);
  });
});

describe("extractVisionText", () => {
  it("handles plain strings, Workers AI { response }, and OpenAI choices", () => {
    assert.equal(extractVisionText("  Hello UI  "), "Hello UI");
    assert.equal(extractVisionText("   "), null);
    assert.equal(extractVisionText({ response: "  Workers AI text \n" }), "Workers AI text");
    assert.equal(
      extractVisionText({
        choices: [{ message: { content: "  OpenAI string content  " } }],
      }),
      "OpenAI string content",
    );
    assert.equal(
      extractVisionText({
        choices: [
          {
            message: {
              content: [
                { type: "text", text: "First line" },
                { type: "image_url" },
                { type: "text", text: "Second line" },
              ],
            },
          },
        ],
      }),
      "First line\nSecond line",
    );
    assert.equal(extractVisionText({ choices: [] }), null);
    assert.equal(extractVisionText(null), null);
  });
});

describe("analyzeScreenshotWithWorkersAi", () => {
  it("returns a structured visionError without throwing when env.AI is missing", async () => {
    const res = await analyzeScreenshotWithWorkersAi(
      {},
      {
        imageBase64: "AAAA",
        mimeType: "image/jpeg",
        meta: { cropped: false },
      },
    );

    assert.equal(res.visionProvider, "cloudflare-workers-ai");
    assert.equal(res.visionModel, DEFAULT_WORKERS_AI_VISION_MODEL);
    assert.match(res.visionError, /not configured/i);
    assert.equal(res.visionDescription, undefined);
  });

  it("calls the primary Workers AI model with multimodal data URI payload", async () => {
    const calls = [];
    const env = {
      AI: {
        async run(model, body) {
          calls.push({ model, body });
          return {
            choices: [{ message: { content: "Search bar at top, confirm button at (540, 1200)." } }],
          };
        },
      },
    };

    const res = await analyzeScreenshotWithWorkersAi(env, {
      imageBase64: "QUJD",
      mimeType: "image/jpeg",
      meta: { cropped: true, cropOffsetX: 10, cropOffsetY: 20, cropWidth: 300, cropHeight: 500 },
      prompt: "Locate confirm",
    });

    assert.equal(calls.length, 1);
    assert.equal(calls[0].model, DEFAULT_WORKERS_AI_VISION_MODEL);
    assert.equal(calls[0].body.max_tokens, 600);
    assert.equal(calls[0].body.temperature, 0.2);
    assert.equal(calls[0].body.stream, false);
    assert.equal(calls[0].body.messages[0].content[1].image_url.url, "data:image/jpeg;base64,QUJD");
    assert.equal(res.visionProvider, "cloudflare-workers-ai");
    assert.equal(res.visionModel, DEFAULT_WORKERS_AI_VISION_MODEL);
    assert.equal(res.visionDescription, "Search bar at top, confirm button at (540, 1200).");
  });

  it("falls back to secondary model when primary fails and deduplicates models", async () => {
    const attempted = [];
    const env = {
      WORKERS_AI_VISION_MODEL: DEFAULT_WORKERS_AI_VISION_MODEL,
      AI: {
        async run(model) {
          attempted.push(model);
          if (model === DEFAULT_WORKERS_AI_VISION_MODEL) {
            throw new Error("Primary model overloaded");
          }
          return { response: "Fallback description" };
        },
      },
    };

    const res = await analyzeScreenshotWithWorkersAi(env, {
      imageBase64: "QUJD",
      mimeType: "image/jpeg",
      meta: {},
    });

    assert.deepEqual(attempted, [
      DEFAULT_WORKERS_AI_VISION_MODEL,
      ...FALLBACK_WORKERS_AI_VISION_MODELS,
    ]);
    assert.equal(res.visionModel, FALLBACK_WORKERS_AI_VISION_MODELS[0]);
    assert.equal(res.visionDescription, "Fallback description");
    assert.equal(res.visionError, undefined);
  });

  it("returns visionError without throwing if all models fail", async () => {
    const attempted = [];
    const env = {
      WORKERS_AI_VISION_MODEL: FALLBACK_WORKERS_AI_VISION_MODELS[0],
      AI: {
        async run(model) {
          attempted.push(model);
          return { response: "   " };
        },
      },
    };

    const res = await analyzeScreenshotWithWorkersAi(env, {
      imageBase64: "QUJD",
      mimeType: "image/jpeg",
      meta: {},
    });

    // Deduplicated when configured model equals the fallback model
    assert.deepEqual(attempted, [FALLBACK_WORKERS_AI_VISION_MODELS[0]]);
    assert.equal(res.visionProvider, "cloudflare-workers-ai");
    assert.equal(res.visionModel, FALLBACK_WORKERS_AI_VISION_MODELS[0]);
    assert.match(res.visionError, /empty vision description/i);
  });
});

describe("MCP schema and take_screenshot vision integration", () => {
  it("exposes updated get_screen_ui maxNodes and take_screenshot schema fields", () => {
    const getScreenUi = MCP_TOOLS.find((t) => t.name === "get_screen_ui");
    assert.deepEqual(getScreenUi.inputSchema.properties.scope.enum, ["window", "all"]);
    assert.equal(getScreenUi.inputSchema.properties.scope.default, "window");
    assert.equal(getScreenUi.inputSchema.properties.maxNodes.default, 160);
    assert.equal(getScreenUi.inputSchema.properties.maxNodes.maximum, 500);

    const takeScreenshot = MCP_TOOLS.find((t) => t.name === "take_screenshot");
    assert.deepEqual(takeScreenshot.inputSchema.properties.scope.enum, ["window", "full"]);
    assert.equal(takeScreenshot.inputSchema.properties.scope.default, "window");
    assert.equal(takeScreenshot.inputSchema.properties.describe.default, true);
    assert.equal(takeScreenshot.inputSchema.properties.prompt.maxLength, 500);
  });

  it("validates scope, prompt length, and maxNodes bounds", async () => {
    const dummyExecutor = {
      async getDeviceStatus() {
        throw new Error("not used");
      },
      async executePhoneCommand() {
        throw new Error("not used");
      },
    };

    const badScope = await handleMcpJsonRpc(
      {
        jsonrpc: "2.0",
        id: 1,
        method: "tools/call",
        params: { name: "take_screenshot", arguments: { scope: "crop" } },
      },
      "test",
      dummyExecutor,
    );
    assert.equal(badScope.error.code, -32602);

    const longPrompt = await handleMcpJsonRpc(
      {
        jsonrpc: "2.0",
        id: 2,
        method: "tools/call",
        params: { name: "take_screenshot", arguments: { prompt: "a".repeat(501) } },
      },
      "test",
      dummyExecutor,
    );
    assert.equal(longPrompt.error.code, -32602);

    const tooManyNodes = await handleMcpJsonRpc(
      {
        jsonrpc: "2.0",
        id: 3,
        method: "tools/call",
        params: { name: "get_screen_ui", arguments: { maxNodes: 501 } },
      },
      "test",
      dummyExecutor,
    );
    assert.equal(tooManyNodes.error.code, -32602);

    const badUiScope = await handleMcpJsonRpc(
      {
        jsonrpc: "2.0",
        id: 4,
        method: "tools/call",
        params: { name: "get_screen_ui", arguments: { scope: "partial" } },
      },
      "test",
      dummyExecutor,
    );
    assert.equal(badUiScope.error.code, -32602);
  });

  it("calls describeScreenshot by default and merges vision fields into metadata text", async () => {
    let visionCalls = 0;
    const executor = {
      async getDeviceStatus() {
        throw new Error("not used");
      },
      async executePhoneCommand(action, args) {
        assert.equal(action, "take_screenshot");
        assert.equal(args.prompt, "What is on screen?");
        return {
          type: "response",
          requestId: "r1",
          ok: true,
          imageBase64: "BASE64IMG",
          imageMimeType: "image/jpeg",
          data: { cropped: true, cropOffsetX: 50, cropOffsetY: 100 },
        };
      },
      async describeScreenshot(input) {
        visionCalls += 1;
        assert.equal(input.imageBase64, "BASE64IMG");
        assert.equal(input.mimeType, "image/jpeg");
        assert.equal(input.meta.cropped, true);
        assert.equal(input.prompt, "What is on screen?");
        return {
          visionDescription: "Meituan food card with Order button",
          visionProvider: "cloudflare-workers-ai",
          visionModel: DEFAULT_WORKERS_AI_VISION_MODEL,
        };
      },
    };

    const res = await handleMcpJsonRpc(
      {
        jsonrpc: "2.0",
        id: 10,
        method: "tools/call",
        params: {
          name: "take_screenshot",
          arguments: { prompt: "What is on screen?" },
        },
      },
      "test",
      executor,
    );

    assert.equal(visionCalls, 1);
    assert.equal(res.result.isError, false);
    assert.equal(res.result.content[0].type, "image");
    assert.equal(res.result.content[0].data, "BASE64IMG");
    const parsedText = JSON.parse(res.result.content[1].text);
    assert.equal(parsedText.cropped, true);
    assert.equal(parsedText.visionDescription, "Meituan food card with Order button");
    assert.equal(parsedText.visionProvider, "cloudflare-workers-ai");
  });

  it("skips describeScreenshot when Android already provides visionDescription", async () => {
    let visionCalls = 0;
    const executor = {
      async getDeviceStatus() {
        throw new Error("not used");
      },
      async executePhoneCommand() {
        return {
          type: "response",
          requestId: "r2",
          ok: true,
          imageBase64: "BASE64IMG",
          imageMimeType: "image/jpeg",
          data: {
            visionDescription: "Already analyzed on phone",
            visionProvider: "custom",
          },
        };
      },
      async describeScreenshot() {
        visionCalls += 1;
        return { visionDescription: "Should not run" };
      },
    };

    const res = await handleMcpJsonRpc(
      {
        jsonrpc: "2.0",
        id: 11,
        method: "tools/call",
        params: { name: "take_screenshot", arguments: {} },
      },
      "test",
      executor,
    );

    assert.equal(visionCalls, 0);
    const parsedText = JSON.parse(res.result.content[1].text);
    assert.equal(parsedText.visionDescription, "Already analyzed on phone");
  });

  it("skips describeScreenshot when Android sets visionProvider='custom' even without description", async () => {
    let visionCalls = 0;
    const executor = {
      async getDeviceStatus() {
        throw new Error("not used");
      },
      async executePhoneCommand() {
        return {
          type: "response",
          requestId: "r3",
          ok: true,
          imageBase64: "BASE64IMG",
          imageMimeType: "image/jpeg",
          data: {
            visionProvider: "custom",
            visionError: "Custom API key invalid",
          },
        };
      },
      async describeScreenshot() {
        visionCalls += 1;
        return { visionDescription: "Should not run" };
      },
    };

    const res = await handleMcpJsonRpc(
      {
        jsonrpc: "2.0",
        id: 12,
        method: "tools/call",
        params: { name: "take_screenshot", arguments: {} },
      },
      "test",
      executor,
    );

    assert.equal(visionCalls, 0);
    const parsedText = JSON.parse(res.result.content[1].text);
    assert.equal(parsedText.visionProvider, "custom");
    assert.equal(parsedText.visionError, "Custom API key invalid");
  });

  it("skips describeScreenshot when describe=false", async () => {
    let visionCalls = 0;
    const executor = {
      async getDeviceStatus() {
        throw new Error("not used");
      },
      async executePhoneCommand() {
        return {
          type: "response",
          requestId: "r4",
          ok: true,
          imageBase64: "BASE64IMG",
          imageMimeType: "image/jpeg",
          data: { cropped: false },
        };
      },
      async describeScreenshot() {
        visionCalls += 1;
        return { visionDescription: "Should not run" };
      },
    };

    const res = await handleMcpJsonRpc(
      {
        jsonrpc: "2.0",
        id: 13,
        method: "tools/call",
        params: { name: "take_screenshot", arguments: { describe: false } },
      },
      "test",
      executor,
    );

    assert.equal(visionCalls, 0);
    const parsedText = JSON.parse(res.result.content[1].text);
    assert.equal(parsedText.cropped, false);
    assert.equal(parsedText.visionDescription, undefined);
  });
});
