# Android 15 device test checklist — v0.4.0

## Fast command path
- [ ] `টর্চ অন কর` requests camera permission if needed and toggles torch.
- [ ] `টর্চ অন করে YouTube খোলো` runs both steps in order.
- [ ] If the first step fails, the sequence stops and reports the failed step.
- [ ] `রিংটোন ভলিউম বাড়াও` changes ringtone stream only.
- [ ] `অ্যালার্ম ভলিউম ৩০%` changes alarm stream only.
- [ ] `মাকে কল কর` opens Android phone-contact picker; selection opens the dialer with number, without placing a call.
- [ ] `ERA stop` cancels an active sequence.

## Voice session
- [ ] Start foreground voice session after granting microphone permission.
- [ ] Give multiple commands while ERA remains in foreground.
- [ ] Switch to another app and confirm the session stops.
- [ ] Return to ERA and confirm microphone is not automatically restarted.

## Screen observer
- [ ] Verify the disclosure checkbox is required before opening Accessibility settings.
- [ ] Accept the disclosure and enable ERA Screen Inspector in Android Accessibility settings.
- [ ] Confirm overlay appears over a supported third-party app.
- [ ] Manual capture stores a structured UI tree.
- [ ] Auto-observe off means screen event changes do not refresh the tree.
- [ ] Auto-observe on refreshes the local tree after window/content changes.
- [ ] Disable service and verify overlay disappears.
- [ ] Revoke disclosure consent and verify auto-observation stops, pending action is cancelled and stored snapshot is cleared.

## One-shot semantic action
- [ ] Capture an app screen and inspect exact text/resource ID.
- [ ] Approve a click on a harmless button.
- [ ] Switch to the same package and tap ERA overlay once.
- [ ] Verify the action is consumed and cannot replay.
- [ ] Verify wrong package, expired approval, missing target, ambiguous selector, disabled target and password-field text input are rejected.
- [ ] Approve text entry in a non-password field and verify the new tree contains the text.
- [ ] Verify scroll forward/backward on a supported scrollable node.
- [ ] Verify Send/Publish/Pay/Delete/Submit-style labels are refused by the automated click path.
- [ ] Verify uncertain post-action state is reported and not automatically retried.

## Planning and task state
- [ ] Preview WhatsApp task and inspect DAG dependencies.
- [ ] Confirm send step is marked high-risk and requires user approval.
- [ ] Confirm a dependent step cannot start before prior step verification.
- [ ] Confirm retries are bounded and cancellation works.

## Limitations to record
- [ ] Compare supported apps: native View UI, Compose, WebView, custom Canvas, dialogs, multi-window.
- [ ] Record app-specific tree omissions and resource ID stability.
- [ ] Battery and background behavior may vary by OEM; verify on Moto Android 15.


## v0.6.1 agent approval receipt integration (when adapters are wired)
- [ ] Confirm approval UI displays the complete action, target, text/value, app and rationale before the user confirms.
- [ ] Verify a receipt for a different action or different observation is rejected.
- [ ] Verify expired, future-dated, missing and replayed approval IDs are rejected.
- [ ] Confirm consequential actions stay manual-only even when the approval UI is affirmative.

## v0.7.0 snapshot-bound approval checks

- Capture a target app screen, approve one safe test click, then change the screen before tapping the ERA overlay. Expected: action is cancelled and a fresh review is required.
- Approve an action, switch to a different app, then tap ERA overlay. Expected: app mismatch cancels the action.
- Approve an action, leave the screen completely unchanged, then tap ERA overlay within two minutes. Expected: target is freshly resolved and a single action is dispatched only if it is still visible, enabled and semantically eligible.
- After a successful dispatch, tap ERA overlay again. Expected: the consumed approval cannot run twice.
- Approve text entry, then change any visible element or focus state before dispatch. Expected: stale approval is rejected; the payload is not inserted.
- Revoke screen-inspection consent while an action is pending. Expected: pending approval is cleared and no action runs.


## v0.7.1 one-time permission setup
- [ ] Fresh install opens without any permission dialog.
- [ ] Tap Allow microphone once; return to the app and verify Permission Center shows Granted.
- [ ] Close/reopen ERA and verify microphone remains Granted and no permission prompt appears.
- [ ] Tap Speak and start/stop the foreground voice session repeatedly; while the grant remains active, no new permission prompt appears.
- [ ] Grant Camera once, then issue flashlight on/off commands repeatedly; no new prompt appears while the grant remains active.
- [ ] Grant microphone with Android's `Only this time` option; after the OS expires the grant, verify ERA shows Not granted and requests only when voice is explicitly started again.
- [ ] Revoke microphone or camera in Android App info; return to ERA and verify the status refreshes to Not granted.
- [ ] Accept ERA's screen-inspection disclosure, enable the Accessibility service once in Android Settings, return, and verify the status reads Enabled. Reopen ERA and verify it remains enabled without another setup prompt.
- [ ] Disable the Accessibility service in Android Settings; return and verify ERA reflects the disabled state.
- [ ] Denying one optional permission does not block text commands or other independent features.
