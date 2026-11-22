# ERA-owned Phone Browser Agent source copy

## Project separation

The original standalone Phone Browser Agent remains a separate project. ERA keeps an independent copy of the portable JavaScript/Puppeteer/CDP engine and its source regression tests under this directory. The APK does not load this Node.js source copy at runtime.

Private upstream material is excluded: .git, saved sessions, cookies, account/profile files, screenshots and one-off personal automation scripts. The local node_modules directory is test tooling only and is ignored by Git.

## Standalone Android APK runtime

The active Android implementation is:

app/src/main/java/com/eraassistant/mobile/browser/PhoneBrowserAgentClient.kt

Models and policy:

app/src/main/java/com/eraassistant/mobile/browser/PhoneBrowserAgentModels.kt

The ERA APK owns its native Android WebView instances. It does not require Termux, RUN_COMMAND, ADB, a localhost HTTP listener, a pairing token or a Node runtime.

Current built-in browser capabilities:
- Up to five independent WebView tabs, with tab selection, new/close tab and per-tab URL/title/loading state
- Back, forward, reload and stop controls
- Bounded semantic DOM snapshots (400 nodes / depth 10), with open Shadow DOM and accessible same-origin frame traversal
- Accessible name/description, parent/depth relationships and common control states
- Ref-bound read, click, fill, type, keyboard press, checkbox/radio/switch check/uncheck, native/custom dropdown selection and scroll-into-view
- Per-tab snapshot identity and element fingerprints; stale refs are rejected after navigation or element identity change
- Follow-up snapshot after state-changing actions; dispatch is not described as proof that a business action completed

This is a native WebView implementation, not a drop-in Puppeteer/CDP runtime. Explicit no-Termux limitations:
- This WebView engine controls only tabs hosted inside ERA. Separately, the parent ERA app now has an optional External Chrome target implemented through the existing Android AccessibilityService; that path reads only Chrome's exposed foreground Accessibility tree and is not part of this JavaScript/Puppeteer source copy.
- Its accessibility-style semantic view is DOM-derived, not Chrome's native Accessibility domain tree.
- Cross-origin iframe contents remain isolated and cannot be traversed by page JavaScript.
- Chrome DevTools isolated browser contexts, CDP target discovery, resident Node daemon/action queue and some advanced CDP-only behaviours are not part of the APK runtime. The External Chrome Accessibility target does not provide DOM/CDP, cookies, browser-profile data, hidden page state, cross-origin frame internals or reliable Chrome tab/session control.
- Physical-device WebView navigation/action tests and Android instrumentation tests are still required before release.

## Portable source-copy checks

The copied Node engine is retained for development and regression comparison only. From this directory:

    npm ci
    npm run check
    npm test

These tests do not exercise the Kotlin WebView runtime and do not replace Android Gradle tests or instrumentation.

## Privacy and action boundaries

Private values, DOM attributes and URL query/hash details are excluded from snapshots by default. A private read needs one-time consent. Sensitive field fills, keyboard presses and consequential actions require a separate one-time action confirmation. Values entered into a field are not echoed in ordinary action results. Only HTTPS addresses are opened; HTTP links are upgraded to HTTPS; embedded URL credentials, popups, webpage permission requests, file/content access and mixed content are blocked.


## Separate External Chrome Accessibility target

The parent ERA app provides an optional target for the separate Chrome Stable/Beta/Dev app. It uses the already-registered ERA Screen Inspector AccessibilityService, checks that Chrome is the foreground package and captures only the visible accessibility nodes exposed by Chrome. The user must accept the in-app screen-inspection disclosure, enable the service in Android Settings, start the target in ERA and tap the ERA overlay while Chrome is foreground.

Captured nodes are stored in ERA's existing local UI snapshot store and can be reviewed in ERA. One-shot click/type/scroll actions must be approved in ERA and dispatched only after a further overlay tap in the target app. Chrome editable values and address-bar contents are omitted, and visible URL query strings/fragments are stripped. This is foreground UI automation, not Chrome DOM or CDP automation; live physical-device testing is still required.
