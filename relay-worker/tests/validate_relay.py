#!/usr/bin/env python3
"""Comprehensive static, XML, and cross-end protocol validation for android-mcp-agent."""
import json
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[2]


def assert_true(cond: bool, msg: str) -> None:
    if not cond:
        print(f"[FAIL] {msg}")
        sys.exit(1)
    print(f"[PASS] {msg}")


def check_balanced_delimiters(path: pathlib.Path) -> None:
    text = path.read_text(encoding="utf-8")
    is_kotlin = path.suffix == ".kt"
    stack: list[str] = []
    pairs = {"{": "}", "(": ")", "[": "]"}
    closing = set(pairs.values())
    i = 0
    n = len(text)

    def scan_string(start: int, quote: str) -> int:
        idx = start
        while idx < n and text[idx] != quote:
            if text[idx] == "\\":
                idx += 2
                continue
            # Handle Kotlin/TS ${...} template interpolation
            if (is_kotlin or quote == "`") and text[idx] == "$" and idx + 1 < n and text[idx + 1] == "{":
                idx += 2
                depth = 1
                while idx < n and depth > 0:
                    if text[idx] in ('"', "'", "`"):
                        idx = scan_string(idx + 1, text[idx])
                        continue
                    if text[idx] == "{":
                        depth += 1
                    elif text[idx] == "}":
                        depth -= 1
                    idx += 1
                continue
            idx += 1
        return idx + 1

    while i < n:
        ch = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if ch == "/" and nxt == "/":
            while i < n and text[i] != "\n":
                i += 1
            continue
        if ch == "/" and nxt == "*":
            i += 2
            while i + 1 < n and not (text[i] == "*" and text[i + 1] == "/"):
                i += 1
            i += 2
            continue
        if ch in ('"', "'", "`"):
            i = scan_string(i + 1, ch)
            continue
        if ch in pairs:
            stack.append(pairs[ch])
        elif ch in closing:
            if not stack or stack[-1] != ch:
                assert_true(False, f"Unexpected '{ch}' in {path.relative_to(ROOT)}")
            stack.pop()
        i += 1
    assert_true(not stack, f"Balanced delimiters in {path.relative_to(ROOT)}")


def main() -> None:
    # 1. Verify wrangler.toml uses SQLite-backed Durable Objects (required for Free tier) and Workers AI binding
    wrangler = (ROOT / "relay-worker/wrangler.toml").read_text(encoding="utf-8")
    assert_true(
        'new_sqlite_classes = ["PhoneBridgeDO"]' in wrangler,
        "wrangler.toml uses new_sqlite_classes for Cloudflare Workers Free plan",
    )
    assert_true(
        'binding = "AI"' in wrangler and "WORKERS_AI_VISION_MODEL" in wrangler,
        "wrangler.toml configures Workers AI binding and vision model var",
    )

    # 2. Verify JSON files are valid
    for jf in ["relay-worker/package.json", "relay-worker/tsconfig.json"]:
        parsed = json.loads((ROOT / jf).read_text(encoding="utf-8"))
        assert_true(isinstance(parsed, dict), f"Valid JSON: {jf}")

    # 3. Verify all Android XML files parse cleanly, string keys match, and Manifest matches Kotlin classes
    xml_files = [
        "android-app/app/src/main/AndroidManifest.xml",
        "android-app/app/src/main/res/values/strings.xml",
        "android-app/app/src/main/res/values-en/strings.xml",
        "android-app/app/src/main/res/values/themes.xml",
        "android-app/app/src/main/res/xml/accessibility_service_config.xml",
    ]
    for xf in xml_files:
        ET.parse(ROOT / xf)
        assert_true(True, f"Valid XML syntax: {xf}")

    zh_keys = {
        el.attrib["name"]
        for el in ET.parse(ROOT / "android-app/app/src/main/res/values/strings.xml").getroot().findall("string")
    }
    en_keys = {
        el.attrib["name"]
        for el in ET.parse(ROOT / "android-app/app/src/main/res/values-en/strings.xml").getroot().findall("string")
    }
    assert_true(zh_keys == en_keys, "Chinese and English strings.xml keys match 100%")

    for kt_file in (ROOT / "android-app/app/src/main/java/com/geminiphone/agent").glob("*.kt"):
        for m in re.finditer(r"R\.string\.([A-Za-z0-9_]+)", kt_file.read_text(encoding="utf-8")):
            key = m.group(1)
            assert_true(key in zh_keys, f"R.string.{key} defined in strings.xml ({kt_file.name})")

    manifest_text = (ROOT / "android-app/app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
    for cls_ref in [".MainActivity", ".PhoneAccessibilityService", ".RelayForegroundService"]:
        assert_true(cls_ref in manifest_text, f"AndroidManifest registers {cls_ref}")

    # 4. Verify all 10 MCP tools exist in relay-worker/src/mcp.ts and match Android command handlers
    mcp_ts = (ROOT / "relay-worker/src/mcp.ts").read_text(encoding="utf-8")
    types_ts = (ROOT / "relay-worker/src/types.ts").read_text(encoding="utf-8")
    acc_kt = (
        ROOT
        / "android-app/app/src/main/java/com/geminiphone/agent/PhoneAccessibilityService.kt"
    ).read_text(encoding="utf-8")
    fgs_kt = (
        ROOT
        / "android-app/app/src/main/java/com/geminiphone/agent/RelayForegroundService.kt"
    ).read_text(encoding="utf-8")
    bridge_ts = (ROOT / "relay-worker/src/bridge.ts").read_text(encoding="utf-8")
    index_ts = (ROOT / "relay-worker/src/index.ts").read_text(encoding="utf-8")

    expected_tools = [
        "get_device_status",
        "get_screen_ui",
        "take_screenshot",
        "tap",
        "long_press",
        "swipe",
        "type_text",
        "open_app",
        "press_key",
        "return_to_gemini",
    ]
    for tool in expected_tools:
        assert_true(f'name: "{tool}"' in mcp_ts, f"MCP tool defined: {tool}")
        if tool != "get_device_status":
            assert_true(
                f'"{tool}" ->' in acc_kt,
                f"PhoneAccessibilityService handles action: {tool}",
            )

    assert_true(
        "MODE_FREEFORM" in acc_kt and "getVisibleAppRoots" in acc_kt and "autoReturnGemini" in bridge_ts,
        "Small-window freeform launch, multi-window UI tree, and autoReturnGemini aligned",
    )

    # 5. Verify parameter-level alignment & bugfixes across Worker and Android
    assert_true(
        'optBoolean("pressEnter"' in acc_kt and "ACTION_IME_ENTER" in acc_kt,
        "PhoneAccessibilityService implements pressEnter parameter for type_text",
    )
    assert_true(
        'action == "get_status"' in fgs_kt and 'sendCommand("get_status"' in bridge_ts,
        "Live get_status probe aligned between bridge.ts and RelayForegroundService.kt",
    )
    assert_true(
        "STOP_FOREGROUND_REMOVE" in fgs_kt and "onTimeout(" in fgs_kt,
        "RelayForegroundService cleans up ongoing notification and handles Android 15 FGS timeout",
    )
    assert_true(
        "FOREGROUND_SERVICE_SPECIAL_USE" in manifest_text
        and "PROPERTY_SPECIAL_USE_FGS_SUBTYPE" in manifest_text
        and 'android:foregroundServiceType="specialUse"' in manifest_text
        and "FOREGROUND_SERVICE_TYPE_SPECIAL_USE" in fgs_kt,
        "RelayForegroundService uses specialUse foreground service type with API 34+ guard",
    )
    assert_true(
        "restartConnectionLoop()" in fgs_kt,
        "RelayForegroundService reloads config and reconnects on Save & Connect",
    )
    assert_true(
        "setWebSocketAutoResponse" in bridge_ts and "failPendingForSocket" in bridge_ts,
        "bridge.ts uses WebSocket Hibernation and per-socket pending cleanup",
    )
    for token in ['"/mcp"', '"/ws"', "Mcp-Session-Id", "constantTimeEqual"]:
        assert_true(token in index_ts, f"index.ts contains {token}")

    # 5b. Verify Worker protocol, bridge reliability, and security hardening checks
    assert_true(
        "SUPPORTED_PROTOCOL_VERSIONS" in mcp_ts and "validateToolArgs" in mcp_ts and "-32602" in mcp_ts,
        "mcp.ts negotiates protocol versions and validates tool arguments",
    )
    assert_true(
        "user rejected or did not confirm an action" in mcp_ts,
        "mcp.ts initialize instructions tell Gemini to stop and ask on user rejection",
    )
    assert_true(
        "destructiveHint: false" in mcp_ts and "readOnlyHint: true" in mcp_ts,
        "mcp.ts sets MCP 2025-03-26 safe annotations on tools",
    )
    assert_true(
        "-32600" in index_ts and "-32600" in mcp_ts,
        "index.ts and mcp.ts reject invalid JSON-RPC requests with -32600",
    )
    assert_true(
        "updateMeta" in bridge_ts
        and "consecutiveTimeouts" in bridge_ts
        and "6000" in bridge_ts
        and "parseTimeoutMs" in bridge_ts,
        "bridge.ts serializes metadata, tracks consecutive timeouts, and validates COMMAND_TIMEOUT_MS",
    )
    assert_true(
        'crypto.subtle.digest("SHA-256"' in index_ts
        and "ALLOWED_ORIGINS" in types_ts
        and "ALLOWED_ORIGINS" in index_ts
        and "PHONE_TOKEN" in types_ts
        and "isPhoneAuthorized" in index_ts
        and "^[a-zA-Z0-9_-]{1,64}$" in index_ts,
        "index.ts/types.ts use SHA-256 constant-time compare, PHONE_TOKEN /ws separation, ALLOWED_ORIGINS, and device key validation",
    )
    dev_vars_example = ROOT / "relay-worker/.dev.vars.example"
    assert_true(
        dev_vars_example.exists() and "RELAY_TOKEN=" in dev_vars_example.read_text(encoding="utf-8"),
        "relay-worker/.dev.vars.example exists with RELAY_TOKEN placeholder",
    )

    # 5c. Verify multimodal screenshot vision & expanded UI node limits across Worker and Android
    vision_ts_path = ROOT / "relay-worker/src/vision.ts"
    vision_kt_path = ROOT / "android-app/app/src/main/java/com/geminiphone/agent/VisionAnalyzer.kt"
    assert_true(
        vision_ts_path.exists() and "analyzeScreenshotWithWorkersAi" in vision_ts_path.read_text(encoding="utf-8"),
        "relay-worker/src/vision.ts exists with analyzeScreenshotWithWorkersAi",
    )
    assert_true(
        vision_kt_path.exists() and "analyzeScreenshot" in vision_kt_path.read_text(encoding="utf-8"),
        "VisionAnalyzer.kt exists with analyzeScreenshot",
    )
    assert_true(
        "AI?: Ai" in types_ts and "analyzeScreenshotWithWorkersAi" in index_ts and "describeScreenshot" in mcp_ts,
        "Worker types.ts, index.ts, and mcp.ts wire Workers AI screenshot description",
    )
    assert_true(
        all(token in mcp_ts for token in ["scope:", "prompt:", "describe:"])
        and '"window"' in mcp_ts
        and '"full"' in mcp_ts,
        "mcp.ts take_screenshot schema and rules include scope, prompt, and describe",
    )
    assert_true(
        "maximum: 500" in mcp_ts and "max: 500" in mcp_ts and '"all"' in mcp_ts and "windowScope" in acc_kt,
        "mcp.ts and Android support window-scoped get_screen_ui with up to 500 UI nodes",
    )

    # 6. Check balanced braces/parens across all TS and Kotlin source files
    for src in list((ROOT / "relay-worker/src").glob("*.ts")) + list(
        (ROOT / "android-app/app/src/main/java/com/geminiphone/agent").glob("*.kt")
    ):
        check_balanced_delimiters(src)

    print("\nAll 25+ file structural, XML, and protocol checks passed!")


if __name__ == "__main__":
    main()
