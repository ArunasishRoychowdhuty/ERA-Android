# ERA architecture — maintained through v0.7.0 (original v0.4 baseline)

## Runtime lanes

```text
Voice / Text
    |
    v
CommandRouter (offline, deterministic)
    |----------------------------------|
    v                                  v
Instant Command / Compound Queue       Local Task Planner
    |                                  |
    v                                  v
DeviceController                       Validated Task DAG
    |                                  |
    v                                  v
Native Android APIs                    TaskExecutionCoordinator + Ledger

Optional Structured UI path
Target app -> visible ERA overlay -> Accessibility event observer / manual capture
                                          |
                                          v
                                 Structured UI JSON + diff
                                          |
                                          v
                                Semantic selector validation
                                          |
                              User approves one exact action
                                          |
                             Return to same app, tap overlay
                                          |
                                          v
                              Fresh element re-resolution
                                          |
                                          v
                         AccessibilityNodeInfo.performAction()
                                          |
                                          v
                              Capture new tree and verify
```

## Instant command path

The router recognizes a constrained allow-list of local commands. Multi-part commands are split only at supported separators and limited to five actions. Unsupported or ambiguous segments are sent to a task preview rather than partially executed. Sequences run serially and stop when an action reports a failure.

The phone-contact flow uses Android's system `ACTION_PICK` for phone contacts. The user chooses the contact, ERA reads the returned phone number URI and opens `ACTION_DIAL`; the final call remains a manual user action.

## Voice session

The foreground voice session uses Android `SpeechRecognizer`. It loops through utterances while the Activity remains in the foreground, but stops in `onPause()`. There is no always-on wake word and no background microphone service.

## Local task planner

`PreviewPlanner` is an offline deterministic planner, not an LLM. It performs:

1. Goal/request classification and constraint extraction.
2. Local capability discovery from Android intents and enabled services.
3. App-specific task template selection.
4. DAG dependency construction, risk classification, acceptance criteria and retry budgets.
5. Plan validation for unique IDs, known dependencies and cycle detection.

`TaskExecutionCoordinator` converts the DAG into `TaskExecutionLedger` records. A step is ready only after its dependencies are verified. Consequential steps require user approval. Failures enter a bounded `NEEDS_REPLAN` state and then become `FAILED` when their retry budget is exhausted. `SharedPreferencesTaskLedgerPersistence` stores step state locally under a hash-derived namespace and restores compatible records after process recreation. A restored `APPROVED` step goes back to review, while `OBSERVING` or `EXECUTION_REPORTED` goes to `NEEDS_REPLAN`; the service never replays a stale approval or action automatically. It does not store screen snapshots or provider credentials.

No external AI provider is integrated in this release, by request.

## Structured screen observation

`EraAccessibilityService` is optional and user-enabled. The user must first accept the in-app disclosure describing accessible screen text collection, local storage, password-only redaction and one-shot action behavior. Revoking consent disables observation, cancels pending actions and clears the latest stored snapshot. The service listens for window/content/focus/click/text-change events only when the user turns on Auto-observe. Event bursts are debounced before a local tree capture. Manual overlay capture remains available after consent.

The snapshot is limited to 800 nodes and 30 levels. `nodeKey` is a capture-local path, never a persistent selector. Selectors use resource ID and/or exact visible text, plus element state. Ambiguous targets are rejected.

The service still captures only the active accessible window for action targeting. Inaccessible surfaces, some Compose/custom Canvas surfaces and protected content may expose incomplete trees.

## Semantic action runtime

The user first selects an exact visible text or resource ID and approves one action in ERA. The approval is one-shot, expires after two minutes, and is bound to a SHA-256 identity of the full serialized Accessibility tree, target package, node count and truncation state from the reviewed snapshot.

When the user taps the overlay in the target app, the service:

- verifies the phone is unlocked;
- captures a fresh tree and checks the expected package;
- recomputes the snapshot fingerprint and cancels if any observable UI state differs from the reviewed tree;
- resolves a unique, enabled, visible target from the matching fresh snapshot;
- checks that Android exposes the requested action;
- refuses password-field text entry;
- refuses click targets whose visible labels match common consequential controls;
- dispatches only click, set-text, or scroll Accessibility actions;
- consumes and clears the stored approval payload before dispatch so it cannot be replayed;
- captures the UI again and reports whether the result was observable.

No node path or pixel coordinate from an old snapshot is reused. A user must approve again for every action. The service does not send messages, publish, purchase, delete or submit forms.

## DOM scope

`WebDomSnapshotScript` is a helper for a WebView owned by ERA. It cannot read Chrome's private DOM or another app's process. Android app sandboxing prevents a normal app from directly inspecting another app's DOM.

## Deliberately unchanged workstreams

- Provider integration and secure key management were excluded by request; no model API is connected.
- Expanded personal-information redaction was excluded by request; only password fields retain existing redaction behavior. Other visible text can include personal information and is not newly masked.

## Google Play distribution

Google Play's AccessibilityService policy prohibits using the API for an app that autonomously initiates, plans and executes actions. General-purpose assistants are not automatically eligible to claim the accessibility-tool exemption. This version uses static command routing and a one-shot action that requires user approval plus a visible overlay tap. Any move toward autonomous agent behavior needs a distribution-policy review and likely a different integration model.


## v0.6 bounded runtime core (not yet wired to Android adapters)

```text
Fresh AgentObservation
        |
        v
AgentDecisionProvider (one proposal tied to observation ID)
        |
        v
AgentActionPolicy (typed allow-list / approval / manual-only / block)
        |
        +---- exact approval if required
        |
        v
Fresh pre-dispatch observation + snapshot identity check
        |
        v
One AgentActionExecutor dispatch
        |
        v
Fresh post-action observation + independent verifier
        |
        v
Next planner decision (or stop/replan)
```

The runtime has hard bounds of 20 dispatched actions and 3 replans. Consequential actions are manual-only. The policy and coordinator are implemented in `ai/BoundedAgentRuntime.kt`; Android observer/approval/executor adapters are not yet connected. v0.7 separately hardens the older one-shot Accessibility action path by binding approvals to the full reviewed UI snapshot. See `V0.6_RUNTIME.md` and `V0.7_ACCESSIBILITY_APPROVALS.md` for contracts and limitations.
