import {
  AuthorizationError,
  OAuthAuthorizationServer,
  OAuthResourceServer,
  insufficientScope,
} from "@cloudflare/workers-oauth-provider";
import type { OAuthHelpers } from "@cloudflare/workers-oauth-provider";
import type { Env } from "./types";

const PHONE_SCOPE = "phone:control";
const AUTHORIZATION_PATH = "/authorize";
const TOKEN_PATH = "/oauth/token";
const REGISTRATION_PATH = "/oauth/register";
const RESOURCE_METADATA_PATH = "/.well-known/oauth-protected-resource";
const OWNER_FORM_STEP = "owner-form";
const CONSENT_COOKIE_PREFIX = "__Host-android-mcp-";
const GOOGLE_CALLBACK_ORIGINS = [
  "https://oauth-redirect.googleusercontent.com",
  "https://oauth-redirect-sandbox.googleusercontent.com",
  "https://oauth-redirect-test.googleusercontent.com",
];

function isGoogleCallback(value: unknown): boolean {
  if (typeof value !== "string") return false;
  const url = URL.parse(value);
  return Boolean(url && GOOGLE_CALLBACK_ORIGINS.includes(url.origin) &&
    !url.username && !url.password && !url.hash && !url.search &&
    /^\/[ar]\/[a-zA-Z0-9._~-]+$/.test(url.pathname));
}

function escapeHtml(value: string): string {
  return value.replaceAll("&", "&amp;").replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;").replaceAll('"', "&quot;").replaceAll("'", "&#39;");
}

function consentHeaders(headers = new Headers(), scriptNonce?: string): Headers {
  headers.set("Cache-Control", "no-store");
  headers.set("Referrer-Policy", "same-origin");
  headers.set("X-Content-Type-Options", "nosniff");
  const formOrigins = GOOGLE_CALLBACK_ORIGINS.join(" ");
  const scripts = scriptNonce ? `; script-src 'nonce-${scriptNonce}'; connect-src 'self'` : "";
  headers.set("Content-Security-Policy",
    `default-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self' ${formOrigins}${scripts}`);
  return headers;
}

async function hasConsentCookie(request: Request, handle: string): Promise<boolean> {
  if (!/^[a-zA-Z0-9_-]{43}$/.test(handle)) return false;
  // The OAuth provider names each consent cookie after the SHA-256 of its handle.
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(handle));
  const hash = Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0")).join("");
  const cookie = `${CONSENT_COOKIE_PREFIX}consent-${hash.slice(0, 16)}=${hash}`;
  return (request.headers.get("Cookie") ?? "").split(";").some((entry) => entry.trim() === cookie);
}

function consentRedirect(request: Request, redirectTo: string, headers?: Headers): Response {
  const responseHeaders = consentHeaders(headers);
  if (request.headers.get("Accept") === "application/json") {
    responseHeaders.delete("Location");
    return Response.json({ redirectTo }, { headers: responseHeaders });
  }
  responseHeaders.set("Location", redirectTo);
  return new Response(null, { status: 303, headers: responseHeaders });
}

async function authorize(
  request: Request,
  oauth: OAuthHelpers,
  verifyOwner: (candidate: string) => Promise<boolean>,
): Promise<Response> {
  try {
    if (request.method === "GET") {
      const auth = await oauth.parseAuthRequest(request);
      if (!isGoogleCallback(auth.redirectUri) || !auth.codeChallenge ||
          auth.codeChallengeMethod !== "S256") {
        return new Response("A Google callback and PKCE S256 are required.", {
          status: 400, headers: consentHeaders(),
        });
      }

      const url = new URL(request.url);
      if (url.searchParams.get("step") !== OWNER_FORM_STEP) {
        // Start the consent transaction after a navigation from this origin so the browser can
        // retain its binding cookie independently of the upstream account-linking redirect.
        url.searchParams.set("step", OWNER_FORM_STEP);
        const headers = consentHeaders();
        headers.set("Content-Type", "text/html; charset=utf-8");
        return new Response(`<!doctype html>
<html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Continue Android MCP authorization</title>
<h1>Continue Android MCP authorization</h1>
<p>Open the authorization form on this site to link your Android phone.</p>
<p><a href="${escapeHtml(url.pathname + url.search)}">Continue to phone authorization</a></p>`, { headers });
      }

      const details = await oauth.describeConsent(auth);
      const consent = await oauth.beginConsent(auth);
      const nonce = crypto.randomUUID();
      const headers = consentHeaders(consent.headers, nonce);
      headers.set("Content-Type", "text/html; charset=utf-8");
      return new Response(`<!doctype html>
<html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Authorize Android MCP</title>
<h1>Connect Gemini to your Android phone</h1>
<p>Client: ${escapeHtml(details.clientName)}. Authorization returns to ${escapeHtml(details.redirectHost)}.</p>
<p>This permits the client to read the phone screen and request actions on the primary Android phone.</p>
<p id="status" role="status">Checking the browser authorization session…</p>
<noscript>JavaScript is needed to verify the browser session before you enter your key.</noscript>
<form method="post" action="${AUTHORIZATION_PATH}">
  <input type="hidden" name="handle" value="${escapeHtml(consent.handle)}">
  <fieldset disabled>
    <p><label>Owner authorization key <input type="password" name="owner_key" maxlength="128" autocomplete="off"></label></p>
    <button name="decision" value="approve">Authorize phone access</button>
    <button name="decision" value="deny">Deny</button>
  </fieldset>
</form>
<script nonce="${nonce}">
  const form = document.querySelector('form');
  const fields = form.querySelector('fieldset');
  const status = document.getElementById('status');
  const send = (data) => fetch(form.action, {
    method: 'POST', credentials: 'same-origin', redirect: 'error',
    headers: { Accept: 'application/json' }, body: new URLSearchParams(data),
  });
  async function checkSession() {
    try {
      const response = await send({ handle: form.elements.handle.value, decision: 'check' });
      if (response.ok) {
        fields.disabled = false;
        status.textContent = 'Browser session ready.';
        return;
      }
      if (response.status === 409) {
        const retry = new URL(location.href);
        if (!retry.searchParams.has('retry')) {
          retry.searchParams.set('retry', '1');
          location.replace(retry);
          return;
        }
      }
      status.textContent = 'This browser did not return the authorization cookie. Reopen the connection in a Chrome profile that allows cookies for this Worker.';
    } catch {
      status.textContent = 'Could not check the browser session. Reload this page to retry.';
    }
  }
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    const data = new FormData(form, event.submitter);
    if (data.get('decision') === 'deny') data.delete('owner_key');
    fields.disabled = true;
    try {
      const response = await send(data);
      if (response.ok) {
        location.assign((await response.json()).redirectTo);
        return;
      }
      status.textContent = await response.text();
    } catch {
      status.textContent = 'Could not submit authorization. Try again.';
    }
    fields.disabled = false;
  });
  void checkSession();
</script>`, { headers });
    }

    if (request.method !== "POST") {
      return new Response(null, { status: 405, headers: { Allow: "GET, POST" } });
    }

    if (request.headers.get("Origin") !== new URL(request.url).origin ||
        request.headers.get("Content-Type")?.split(";", 1)[0]?.trim().toLowerCase() !== "application/x-www-form-urlencoded") {
      return new Response("Open the authorization page to continue.", { status: 403, headers: consentHeaders() });
    }

    const form = await request.formData();
    const handle = form.get("handle");
    if (typeof handle !== "string" || !handle) {
      return new Response("Missing authorization request.", { status: 400, headers: consentHeaders() });
    }

    if (form.get("decision") === "check") {
      const ready = await hasConsentCookie(request, handle);
      return new Response(null, { status: ready ? 204 : 409, headers: consentHeaders() });
    }

    if (form.get("decision") === "deny") {
      const denied = await oauth.denyConsent(request, handle);
      return consentRedirect(request, denied.redirectTo, denied.headers);
    }

    const key = form.get("owner_key");
    if (form.get("decision") !== "approve" || typeof key !== "string" ||
        key.length > 128 || !(await verifyOwner(key.trim()))) {
      return new Response("Incorrect owner authorization key. Please try again.", {
        status: 403, headers: consentHeaders(),
      });
    }

    const approved = await oauth.approveConsent(request, handle, { scope: [PHONE_SCOPE] });
    const { redirectTo } = await oauth.completeAuthorization({
      request: approved.request,
      userId: "phone-owner",
      metadata: {},
      scope: approved.request.scope,
      props: {},
    });
    return consentRedirect(request, redirectTo, approved.headers);
  } catch (error) {
    if (!(error instanceof AuthorizationError)) throw error;
    if (error.redirectTo && isGoogleCallback(error.redirectUri)) {
      const headers = consentHeaders();
      headers.set("Location", error.redirectTo);
      return new Response(null, { status: 303, headers });
    }
    return new Response(error.description, { status: 400, headers: consentHeaders() });
  }
}

interface OAuthHandlers {
  handleMcp(): Promise<Response>;
  verifyOwner(candidate: string, ownerKey: string): Promise<boolean>;
  originAllowed: boolean;
}

export async function routeMcpOAuth(
  request: Request, env: Env, ctx: ExecutionContext, handlers: OAuthHandlers,
): Promise<Response | null> {
  const ownerKey = env.OAUTH_OWNER_KEY?.trim();
  const configuredOrigin = env.OAUTH_ORIGIN ? URL.parse(env.OAUTH_ORIGIN.trim())?.origin : undefined;
  const url = new URL(request.url);
  if (!env.OAUTH_KV || !ownerKey || ownerKey.length < 32 || !configuredOrigin || url.origin !== configuredOrigin) return null;

  const resourcePaths = ["/mcp", RESOURCE_METADATA_PATH, `${RESOURCE_METADATA_PATH}/mcp`];
  const serverPaths = [AUTHORIZATION_PATH, TOKEN_PATH, REGISTRATION_PATH, "/.well-known/oauth-authorization-server"];
  const isResourceRequest = resourcePaths.includes(url.pathname);
  if (!isResourceRequest && !serverPaths.includes(url.pathname)) return null;
  if (url.pathname === "/mcp" && request.method === "OPTIONS") return null;

  const resource = `${url.origin}/mcp`;
  const server = new OAuthAuthorizationServer<Env>({
    issuer: url.origin,
    resources: [resource],
    cookiePrefix: CONSENT_COOKIE_PREFIX,
    authorizeEndpoint: AUTHORIZATION_PATH,
    tokenEndpoint: TOKEN_PATH,
    clientRegistrationEndpoint: REGISTRATION_PATH,
    scopesSupported: [PHONE_SCOPE],
    clientRegistrationCallback({ clientMetadata }) {
      const redirects = clientMetadata.redirect_uris;
      if (!Array.isArray(redirects) || !redirects.length || !redirects.every(isGoogleCallback)) {
        return { code: "invalid_redirect_uri", description: "A Google Gemini OAuth callback is required." };
      }
    },
  });

  if (url.pathname === AUTHORIZATION_PATH) {
    return authorize(request, server.getOAuthApi(env), (candidate) => handlers.verifyOwner(candidate, ownerKey));
  }
  if (!isResourceRequest) return server.fetch(request, env, ctx);
  if (url.pathname === "/mcp" && !handlers.originAllowed) {
    return new Response("Origin not allowed", { status: 403 });
  }

  const resourceServer = new OAuthResourceServer<Env>({
    resourceMetadata: {
      resource,
      authorization_servers: [url.origin],
      resource_name: "Android Phone Controller",
    },
    requiredScopes: [PHONE_SCOPE],
    validateToken: (bindings) => (audience, token) => server.validateToken(audience, token, bindings),
    handler: {
      fetch(req, _env, context) {
        if (!context.auth.scope.includes(PHONE_SCOPE)) return insufficientScope(context.auth, [PHONE_SCOPE]);
        const device = new URL(req.url).searchParams.get("device")?.trim() || "primary";
        if (device !== "primary") {
          return new Response("This authorization is for the primary phone.", { status: 403 });
        }
        return handlers.handleMcp();
      },
    },
  });

  if (url.pathname === RESOURCE_METADATA_PATH) {
    url.pathname = `${RESOURCE_METADATA_PATH}/mcp`;
    return resourceServer.fetch(new Request(url, request), env, ctx);
  }
  return resourceServer.fetch(request, env, ctx);
}
