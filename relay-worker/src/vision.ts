import type { Env } from "./types";

export const DEFAULT_WORKERS_AI_VISION_MODEL = "@cf/google/gemma-4-26b-a4b-it";
export const FALLBACK_WORKERS_AI_VISION_MODELS = [
  "@cf/mistralai/mistral-small-3.1-24b-instruct",
] as const;

export type WorkersAiVisionResult = {
  visionDescription?: string;
  visionError?: string;
  visionProvider: "cloudflare-workers-ai";
  visionModel: string;
};

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function asFiniteNumber(value: unknown): number | null {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

/**
 * Builds a coordinate-aware multimodal prompt so the vision model knows whether
 * it is inspecting a cropped floating window or the full device screen.
 */
export function buildVisionPrompt(
  meta: Record<string, unknown>,
  userPrompt?: string,
): string {
  const cropped = meta.cropped === true;
  const cropOffsetX = asFiniteNumber(meta.cropOffsetX);
  const cropOffsetY = asFiniteNumber(meta.cropOffsetY);
  const cropWidth = asFiniteNumber(meta.cropWidth);
  const cropHeight = asFiniteNumber(meta.cropHeight);
  const screenWidth = asFiniteNumber(meta.screenWidth);
  const screenHeight = asFiniteNumber(meta.screenHeight);

  const lines: string[] = [];

  if (
    cropped &&
    cropOffsetX !== null &&
    cropOffsetY !== null &&
    cropWidth !== null &&
    cropWidth > 0 &&
    cropHeight !== null &&
    cropHeight > 0
  ) {
    const right = cropOffsetX + cropWidth;
    const bottom = cropOffsetY + cropHeight;
    const screenInfo =
      screenWidth !== null && screenWidth > 0 && screenHeight !== null && screenHeight > 0
        ? ` on a ${screenWidth}x${screenHeight} screen`
        : "";
    lines.push(
      `This image is a cropped Android floating/active window covering full-screen bounds [${cropOffsetX}, ${cropOffsetY}, ${right}, ${bottom}]${screenInfo}.`,
    );
    lines.push(
      `When estimating tap coordinates, add crop offset (+${cropOffsetX}, +${cropOffsetY}) so coordinates map to the full screen.`,
    );
  } else {
    const screenInfo =
      screenWidth !== null && screenWidth > 0 && screenHeight !== null && screenHeight > 0
        ? ` (${screenWidth}x${screenHeight} full screen)`
        : " (full screen)";
    lines.push(`This image is an Android screenshot${screenInfo}.`);
  }

  lines.push(
    "Provide a concise summary of the UI layout and current state, highlight any important visual text, icons, images, prices, or controls that may be missing from an accessibility text tree, and include estimated full-screen (x, y) coordinates for key interactive targets where helpful.",
  );

  const trimmedPrompt = userPrompt?.trim();
  if (trimmedPrompt) {
    lines.push(`User focus: ${trimmedPrompt}`);
  }

  return lines.join(" ");
}

/**
 * Extracts trimmed text from OpenAI-style chat completions, Workers AI
 * `{ response }`, or plain string payloads.
 */
export function extractVisionText(raw: unknown): string | null {
  if (typeof raw === "string") {
    const trimmed = raw.trim();
    return trimmed.length > 0 ? trimmed : null;
  }

  if (!isPlainObject(raw)) {
    return null;
  }

  if (typeof raw.response === "string") {
    const trimmed = raw.response.trim();
    if (trimmed.length > 0) {
      return trimmed;
    }
  }

  if (Array.isArray(raw.choices) && raw.choices.length > 0) {
    const firstChoice = raw.choices[0];
    if (isPlainObject(firstChoice) && isPlainObject(firstChoice.message)) {
      const content = firstChoice.message.content;
      if (typeof content === "string") {
        const trimmed = content.trim();
        if (trimmed.length > 0) {
          return trimmed;
        }
      } else if (Array.isArray(content)) {
        const parts: string[] = [];
        for (const item of content) {
          if (typeof item === "string") {
            const t = item.trim();
            if (t) parts.push(t);
          } else if (isPlainObject(item) && typeof item.text === "string") {
            const t = item.text.trim();
            if (t) parts.push(t);
          }
        }
        if (parts.length > 0) {
          return parts.join("\n").trim();
        }
      }
    }
  }

  return null;
}

/**
 * Analyzes an Android screenshot via Cloudflare Workers AI multimodal models,
 * falling back cleanly if the primary model fails or returns empty output.
 */
export async function analyzeScreenshotWithWorkersAi(
  env: Pick<Env, "AI" | "WORKERS_AI_VISION_MODEL">,
  input: {
    imageBase64: string;
    mimeType: string;
    meta: Record<string, unknown>;
    prompt?: string;
  },
): Promise<WorkersAiVisionResult> {
  const primaryModel =
    env.WORKERS_AI_VISION_MODEL?.trim() || DEFAULT_WORKERS_AI_VISION_MODEL;

  if (!env.AI) {
    return {
      visionError: "Cloudflare Workers AI binding (AI) is not configured.",
      visionProvider: "cloudflare-workers-ai",
      visionModel: primaryModel,
    };
  }

  const models = Array.from(
    new Set([primaryModel, ...FALLBACK_WORKERS_AI_VISION_MODELS]),
  );
  const prompt = buildVisionPrompt(input.meta, input.prompt);
  const dataUri = `data:${input.mimeType};base64,${input.imageBase64}`;
  const runner = env.AI as { run: (m: string, body: unknown) => Promise<unknown> };

  let lastError = "Workers AI vision analysis failed.";
  let lastModel = primaryModel;

  for (const model of models) {
    lastModel = model;
    try {
      const raw = await runner.run(model, {
        messages: [
          {
            role: "user",
            content: [
              { type: "text", text: prompt },
              { type: "image_url", image_url: { url: dataUri } },
            ],
          },
        ],
        max_tokens: 600,
        temperature: 0.2,
        stream: false,
      });

      const description = extractVisionText(raw);
      if (description) {
        return {
          visionDescription: description,
          visionProvider: "cloudflare-workers-ai",
          visionModel: model,
        };
      }
      lastError = `Model ${model} returned an empty vision description.`;
    } catch (err) {
      lastError = err instanceof Error ? err.message : String(err);
    }
  }

  return {
    visionError: lastError,
    visionProvider: "cloudflare-workers-ai",
    visionModel: lastModel,
  };
}
