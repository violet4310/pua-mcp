"""Exercise the OAuth and legacy MCP paths in an isolated, local Worker."""

import base64
import hashlib
import html
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import tempfile
import time
import unittest
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener


ROOT = Path(__file__).resolve().parents[1]
OWNER_KEY = "local-test-owner-key-not-a-real-secret"
RELAY_TOKEN = "local-mcp-test-token"
PHONE_TOKEN = "local-phone-test-token"
CALLBACK = "https://oauth-redirect.googleusercontent.com/r/local-test-worker"
VERIFIER = "a-local-test-verifier-that-is-long-enough-for-PKCE"
CHALLENGE = base64.urlsafe_b64encode(hashlib.sha256(VERIFIER.encode()).digest()).rstrip(b"=").decode()


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class OAuthTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            port = listener.getsockname()[1]
        cls.origin = f"http://127.0.0.1:{port}"
        cls.opener = build_opener(ProxyHandler({}), NoRedirect())
        state = tempfile.TemporaryDirectory(prefix="android-mcp-oauth-test-")
        cls.addClassCleanup(state.cleanup)
        log = open(Path(state.name) / "worker.log", "w+b")
        cls.addClassCleanup(log.close)
        process = subprocess.Popen(
            [str(ROOT / "node_modules/.bin/wrangler"), "dev", "--local", "--ip", "127.0.0.1",
             "--port", str(port), "--persist-to", state.name, "--var", f"OAUTH_ORIGIN:{cls.origin}",
             "--var", f"OAUTH_OWNER_KEY:{OWNER_KEY}", "--var", f"RELAY_TOKEN:{RELAY_TOKEN}",
             "--var", f"PHONE_TOKEN:{PHONE_TOKEN}"],
            cwd=ROOT, stdout=log, stderr=subprocess.STDOUT,
            env={**os.environ, "WRANGLER_SEND_METRICS": "false"},
        )

        def stop_worker():
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()

        cls.addClassCleanup(stop_worker)
        deadline = time.monotonic() + 20
        while process.poll() is None and time.monotonic() < deadline:
            try:
                if cls.request("/health")[0] == 200:
                    return
            except (URLError, TimeoutError):
                pass
            time.sleep(0.1)
        log.seek(0)
        raise RuntimeError(log.read().decode(errors="replace"))

    @classmethod
    def request(cls, path, *, headers=None, data=None, form=None):
        headers = dict(headers or {})
        if form is not None:
            data = urlencode(form).encode()
            headers["Content-Type"] = "application/x-www-form-urlencoded"
        elif data is not None:
            data = json.dumps(data).encode()
            headers["Content-Type"] = "application/json"
        req = Request(cls.origin + path, data=data, headers=headers)
        try:
            response = cls.opener.open(req, timeout=4)
        except HTTPError as error:
            response = error
        with response:
            return response.status, response.headers, response.read()

    def test_oauth(self):
        resource = self.origin + "/mcp"
        status, headers, _ = self.request("/mcp")
        self.assertEqual(status, 401)
        self.assertIn('resource_metadata="' + self.origin + '/.well-known/oauth-protected-resource/mcp"',
                      headers["WWW-Authenticate"])
        for path in ["/.well-known/oauth-protected-resource/mcp", "/.well-known/oauth-protected-resource"]:
            status, _, body = self.request(path)
            self.assertEqual(status, 200)
            self.assertEqual(json.loads(body)["resource"], resource)
        status, _, body = self.request("/.well-known/oauth-authorization-server")
        self.assertEqual(status, 200)
        metadata = json.loads(body)
        self.assertEqual(metadata["issuer"], self.origin)
        self.assertEqual(metadata["code_challenge_methods_supported"], ["S256"])

        registration_path = urlsplit(metadata["registration_endpoint"]).path
        registration = {"redirect_uris": [CALLBACK], "client_name": "<Gemini Test>",
                        "token_endpoint_auth_method": "none",
                        "grant_types": ["authorization_code", "refresh_token"]}
        untrusted = {**registration, "redirect_uris": [CALLBACK, "https://example.com/r/spoof"]}
        self.assertEqual(self.request(registration_path, data=untrusted)[0], 400)
        status, _, body = self.request(registration_path, data=registration)
        self.assertEqual(status, 201, body)
        client_id = json.loads(body)["client_id"]
        auth_path = urlsplit(metadata["authorization_endpoint"]).path
        auth = {"client_id": client_id, "response_type": "code", "redirect_uri": CALLBACK,
                "scope": "phone:control", "state": "test-client-state", "resource": resource,
                "code_challenge": CHALLENGE, "code_challenge_method": "S256"}
        self.assertEqual(self.request(auth_path + "?" + urlencode({**auth, "redirect_uri": CALLBACK + "-wrong"}))[0], 400)
        self.assertEqual(self.request(auth_path + "?" + urlencode({**auth, "code_challenge": ""}))[0], 303)
        status, headers, body = self.request(auth_path + "?" + urlencode(auth),
                                             headers={"Sec-Fetch-Site": "cross-site"})
        self.assertEqual(status, 200, body)
        self.assertIsNone(headers.get("Set-Cookie"))
        self.assertIn(b"Continue to phone authorization", body)
        continue_path = html.unescape(re.search(rb'href="([^"]+)"', body).group(1).decode())
        self.assertTrue(continue_path.startswith(auth_path + "?"))
        status, headers, body = self.request(continue_path, headers={"Sec-Fetch-Site": "same-origin"})
        self.assertEqual(status, 200, body)
        self.assertIn(b"&lt;Gemini Test&gt;", body)
        self.assertNotIn(b"<Gemini Test>", body)
        cookie = headers.get_all("Set-Cookie")[0].split(";", 1)[0]
        handle = re.search(rb'name="handle" value="([^"]+)"', body).group(1).decode()
        consent = {"decision": "approve", "handle": handle, "owner_key": OWNER_KEY}
        browser = {"Origin": self.origin, "Cookie": cookie}
        check = {"decision": "check", "handle": handle}
        self.assertEqual(self.request(auth_path, headers={"Origin": self.origin}, form=check)[0], 409)
        self.assertEqual(self.request(auth_path, headers=browser, form=check)[0], 204)
        self.assertEqual(self.request(auth_path, headers={"Origin": self.origin}, form=consent)[0], 400)
        self.assertEqual(self.request(auth_path, headers={**browser, "Origin": "https://example.com"}, form=consent)[0], 403)
        self.assertEqual(self.request(auth_path, headers=browser, form={**consent, "owner_key": RELAY_TOKEN})[0], 403)
        status, _, body = self.request(auth_path, headers={**browser, "Accept": "application/json"}, form=consent)
        self.assertEqual(status, 200)
        callback = urlsplit(json.loads(body)["redirectTo"])
        self.assertEqual(callback.netloc, urlsplit(CALLBACK).netloc)
        response = parse_qs(callback.query)
        self.assertEqual(response["state"], ["test-client-state"])
        self.assertEqual(response["iss"], [self.origin])
        self.assertEqual(self.request(auth_path, headers=browser, form=consent)[0], 400)

        token_path = urlsplit(metadata["token_endpoint"]).path
        exchange = {"grant_type": "authorization_code", "client_id": client_id, "code": response["code"][0],
                    "redirect_uri": CALLBACK, "code_verifier": VERIFIER, "resource": resource}
        self.assertEqual(self.request(token_path, form={**exchange, "resource": self.origin + "/ws"})[0], 400)
        self.assertEqual(self.request(token_path, form={**exchange, "code_verifier": "wrong"})[0], 400)
        status, _, body = self.request(token_path, form=exchange)
        self.assertEqual(status, 200, body)
        tokens = json.loads(body)
        auth_header = {"Authorization": "Bearer " + tokens["access_token"]}
        status, _, body = self.request("/mcp", headers=auth_header,
                                       data={"jsonrpc": "2.0", "id": 1, "method": "tools/list"})
        self.assertEqual(status, 200)
        self.assertIn("get_device_status", [tool["name"] for tool in json.loads(body)["result"]["tools"]])
        self.assertEqual(self.request("/mcp?device=other", headers=auth_header)[0], 403)
        self.assertEqual(self.request("/ws", headers=auth_header)[0], 401)
        refresh = {"grant_type": "refresh_token", "client_id": client_id, "refresh_token": tokens["refresh_token"]}
        self.assertEqual(self.request(token_path, form=refresh)[0], 200)
        self.assertEqual(self.request(token_path, form=exchange)[0], 400)
        self.assertEqual(self.request("/mcp", headers=auth_header)[0], 401)

    def test_existing_client_credentials(self):
        relay = {"Authorization": "Bearer " + RELAY_TOKEN}
        phone = {"Authorization": "Bearer " + PHONE_TOKEN}
        self.assertEqual(self.request("/mcp", headers=phone)[0], 401)
        self.assertEqual(self.request("/ws", headers=relay)[0], 401)
        self.assertEqual(self.request("/ws", headers=phone)[0], 426)
        self.assertEqual(self.request("/mcp?token=" + RELAY_TOKEN)[0], 405)
        self.assertEqual(self.request("/mcp", headers=relay,
                                      data={"jsonrpc": "2.0", "id": 1, "method": "tools/list"})[0], 200)


if __name__ == "__main__":
    unittest.main(verbosity=2)
