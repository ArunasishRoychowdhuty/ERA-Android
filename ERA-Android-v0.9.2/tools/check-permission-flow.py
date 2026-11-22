#!/usr/bin/env python3
"""Static sanity checks for ERA's Android permission setup flow.

These checks do not replace Gradle compilation or Android 15 device tests.
"""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/java/com/eraassistant/mobile/MainActivity.kt"
UI = ROOT / "app/src/main/java/com/eraassistant/mobile/ui/ERAScreen.kt"
DISCOVERY = ROOT / "app/src/main/java/com/eraassistant/mobile/device/AndroidCapabilityDiscovery.kt"

for xml_file in (
    ROOT / "app/src/main/AndroidManifest.xml",
    ROOT / "app/src/main/res/xml/era_accessibility_service.xml",
    ROOT / "app/src/main/res/xml/network_security_config.xml",
    ROOT / "app/src/main/res/values/strings.xml",
):
    ET.parse(xml_file)
    print(f"PASS: XML parses: {xml_file.relative_to(ROOT)}")

main = MAIN.read_text(encoding="utf-8")
ui = UI.read_text(encoding="utf-8")
discovery = DISCOVERY.read_text(encoding="utf-8")

checks = {
    "microphone status reads live Android grant": "checkSelfPermission(\n            this, Manifest.permission.RECORD_AUDIO" in main,
    "camera status reads live Android grant": "checkSelfPermission(\n            this, Manifest.permission.CAMERA" in main,
    "permission status refreshes after returning to app": "refreshPermissionStatuses()\n            reloadUiSnapshot()" in main,
    "microphone setup is explicit user action": "onRequestMicrophonePermission = ::requestMicrophonePermissionSetup" in main,
    "camera setup is explicit user action": "onRequestCameraPermission = ::requestCameraPermissionSetup" in main,
    "Accessibility state is read from Android service list": "fun isEraAccessibilityServiceEnabled(): Boolean" in discovery,
    "Permission Center is visible in UI": "One-time permission setup" in ui,
    "temporary microphone grant warning is displayed": "Only this time" in ui,
}

failed = []
for label, ok in checks.items():
    print(f"{'PASS' if ok else 'FAIL'}: {label}")
    if not ok:
        failed.append(label)

if failed:
    print("\nPermission-flow static checks failed:", "; ".join(failed), file=sys.stderr)
    sys.exit(1)

print(f"PASS: {len(checks)} permission-flow source checks")
