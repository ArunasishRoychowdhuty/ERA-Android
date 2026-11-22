'use strict';

const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { spawnSync } = require('node:child_process');

const RUNTIME_DIR = __dirname;
const STATE_DIR = process.env.ERA_PHONE_BROWSER_STATE_DIR ||
  path.join(process.env.HOME, '.cache', 'era-phone-browser-agent');
const CDP_URL = process.env.CDP_URL || 'http://127.0.0.1:9224';
const MAX_RESPONSE_BYTES = 5 * 1024 * 1024;
const MAX_TREE_NODES = 180;
const MAX_TREE_DEPTH = 5;

function safeText(value, limit = 180) {
  let text = String(value ?? '').replace(/\s+/g, ' ').trim();
  text = text
    .replace(/[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}/gi, '[EMAIL]')
    .replace(/(?<!\d)\+?\d[\d ()-]{6,}\d(?!\d)/g, '[PHONE]')
    .replace(/\b(?:AIza[0-9A-Za-z_-]{20,}|sk-[0-9A-Za-z_-]{12,})\b/g, '[REDACTED]')
    .replace(/\bBearer\s+\S+/gi, 'Bearer [REDACTED]');
  return text.slice(0, limit);
}

function safeUrl(value, includeQuery = false) {
  try {
    const url = new URL(String(value));
    if (!['http:', 'https:', 'chrome:', 'chrome-native:'].includes(url.protocol)) return '';
    // Never forward embedded URL credentials, even after the user approves query details.
    url.username = '';
    url.password = '';
    const valueToShare = url.origin + url.pathname + (includeQuery ? url.search + url.hash : '');
    return includeQuery ? valueToShare.slice(0, 1200) : safeText(valueToShare, 240);
  } catch {
    return '';
  }
}

function privateText(value, limit = 500) {
  return String(value ?? '').slice(0, limit);
}

function sensitiveField(node) {
  const attrs = node.attributes || {};
  const type = String(attrs.type || '').toLowerCase();
  const autocomplete = String(attrs.autocomplete || '').toLowerCase();
  const identity = [
    node.name, attrs.id, attrs.nameAttr, attrs.placeholder, attrs.title, autocomplete
  ].join(' ').toLowerCase();
  return type === 'password' ||
    /one-time-code|current-password|new-password|cc-number|cc-csc/.test(autocomplete) ||
    /password|passcode|otp|one.?time|verification.?code|security.?code|api.?key|api.?token|access.?token|secret|pin|cvv|cvc|card.?number/.test(identity);
}

function safeState(state = {}) {
  const allowed = ['disabled', 'required', 'readOnly', 'checked', 'selected', 'expanded', 'invalid', 'focused'];
  const result = {};
  for (const key of allowed) {
    if (typeof state[key] === 'boolean') result[key] = state[key];
  }
  return result;
}

function sanitizeNode(node, budget, depth = 0, options = {}) {
  if (!node || typeof node !== 'object' || budget.count >= MAX_TREE_NODES || depth > MAX_TREE_DEPTH) return null;
  budget.count += 1;
  const includePrivateDetails = options.includePrivateDetails === true;
  const sensitive = sensitiveField(node);
  const consequential = highImpactTarget({
    name: node.name || node.text,
    fingerprint: {
      type: node.attributes?.type,
      id: node.attributes?.id,
      nameAttr: node.attributes?.nameAttr
    }
  });
  const result = {
    ref: String(node.ref || '').slice(0, 48),
    tag: safeText(node.tag, 24),
    role: safeText(node.role, 36),
    name: includePrivateDetails ? privateText(node.name || node.text, 180) :
      (sensitive ? 'Sensitive field' : safeText(node.name || node.text, 120)),
    clickable: Boolean(node.clickable),
    editable: Boolean(node.editable),
    focusable: Boolean(node.focusable),
    visible: Boolean(node.visible),
    sensitive,
    consequential,
    state: safeState(node.state),
    children: []
  };
  if (includePrivateDetails) {
    result.value = node.value == null ? null : privateText(node.value, 800);
    result.text = privateText(node.text, 300);
    result.attributes = Object.fromEntries(Object.entries(node.attributes || {}).slice(0, 16)
      .map(([key, value]) => [privateText(key, 60), privateText(value, 240)]));
    result.locator = Array.isArray(node.locator) ? node.locator.slice(0, 8).map(x => privateText(x, 300)) : [];
    result.frameUrl = privateText(node.frameUrl, 1200);
    result.frameName = privateText(node.frameName, 160);
  }
  for (const child of (Array.isArray(node.children) ? node.children : [])) {
    const clean = sanitizeNode(child, budget, depth + 1, options);
    if (clean) result.children.push(clean);
    if (budget.count >= MAX_TREE_NODES) break;
  }
  return result;
}

function sanitizeAxNode(node, budget, depth = 0, options = {}) {
  if (!node || typeof node !== 'object' || budget.count >= MAX_TREE_NODES || depth > MAX_TREE_DEPTH) return null;
  budget.count += 1;
  const includePrivateDetails = options.includePrivateDetails === true;
  const role = safeText(node.role, 36);
  const name = includePrivateDetails ? privateText(node.name, 180) : safeText(node.name, 120);
  const description = includePrivateDetails ? privateText(node.description, 240) : safeText(node.description, 120);
  const properties = {};
  for (const [key, value] of Object.entries(node.properties || {})) {
    if (['disabled', 'checked', 'selected', 'expanded', 'focusable', 'required', 'readonly'].includes(key.toLowerCase()) &&
        typeof value === 'boolean') properties[key] = value;
    else if (includePrivateDetails && ['value', 'valuetext', 'name'].includes(key.toLowerCase())) {
      properties[key] = privateText(value, 500);
    }
  }
  const children = [];
  for (const child of (Array.isArray(node.children) ? node.children : [])) {
    const clean = sanitizeAxNode(child, budget, depth + 1, options);
    if (clean) children.push(clean);
    if (budget.count >= MAX_TREE_NODES) break;
  }
  return { role, name, description, ignored: Boolean(node.ignored), properties, children };
}

function sanitizeSnapshot(raw, options = {}) {
  if (!raw || raw.status !== 'SNAPSHOT_OK') throw new Error('Browser snapshot did not complete.');
  const includePrivateDetails = options.includePrivateDetails === true;
  const domBudget = { count: 0 };
  const tree = [];
  for (const node of (Array.isArray(raw.tree) ? raw.tree : [])) {
    const clean = sanitizeNode(node, domBudget, 0, options);
    if (clean) tree.push(clean);
    if (domBudget.count >= MAX_TREE_NODES) break;
  }

  const access = raw.accessibility || {};
  const axFrames = [];
  let axBudget = { count: 0 };
  for (const frame of (Array.isArray(access.frames) ? access.frames : [])) {
    const axTree = [];
    for (const node of (Array.isArray(frame.tree) ? frame.tree : [])) {
      const clean = sanitizeAxNode(node, axBudget, 0, options);
      if (clean) axTree.push(clean);
      if (axBudget.count >= MAX_TREE_NODES) break;
    }
    axFrames.push({
      status: safeText(frame.status, 24),
      url: safeUrl(frame.url, includePrivateDetails),
      nodeCount: Number(frame.nodeCount || 0),
      truncated: Boolean(frame.truncated),
      error: frame.error ? safeText(frame.error, 140) : '',
      tree: axTree
    });
  }

  return {
    status: 'SNAPSHOT_OK',
    includesPrivateDetails: includePrivateDetails,
    targetId: safeText(raw.targetId, 80),
    url: safeUrl(raw.url, includePrivateDetails),
    title: includePrivateDetails ? privateText(raw.title, 240) : safeText(raw.title, 160),
    nodeCount: Number(raw.nodeCount || domBudget.count),
    complete: Boolean(raw.complete),
    truncated: Boolean(raw.truncated) || Boolean(raw.maxNodesReached),
    frames: (Array.isArray(raw.frames) ? raw.frames : []).slice(0, 20).map(frame => ({
      url: safeUrl(frame.url, includePrivateDetails),
      name: includePrivateDetails ? privateText(frame.name, 120) : safeText(frame.name, 80)
    })),
    frameErrors: (Array.isArray(raw.frameErrors) ? raw.frameErrors : []).slice(0, 10).map(e => safeText(e.message, 140)),
    accessibility: {
      totalNodes: Number(access.totalNodes || 0),
      complete: Boolean(access.complete),
      frames: axFrames
    },
    tree
  };
}

function sanitizeSessions(raw, options = {}) {
  const includePrivateDetails = options.includePrivateDetails === true;
  const tabs = Array.isArray(raw.tabs) ? raw.tabs.slice(0, 30) : [];
  return {
    status: 'SESSIONS_OK',
    includesPrivateDetails: includePrivateDetails,
    count: tabs.length,
    tabs: tabs.map(tab => ({
      tab: Number(tab.tab),
      targetId: safeText(tab.targetId, 80),
      url: safeUrl(tab.url, includePrivateDetails),
      title: includePrivateDetails ? privateText(tab.title, 240) : safeText(tab.title, 160),
      frameCount: Number(tab.frameCount || 0)
    }))
  };
}

function highImpactTarget(ref) {
  const fingerprint = ref.fingerprint || {};
  const label = [ref.name, fingerprint.name, fingerprint.type, fingerprint.id, fingerprint.nameAttr]
    .join(' ').toLowerCase();
  return String(fingerprint.type || '').toLowerCase() === 'submit' ||
    /\b(send|publish|submit|delete|remove|purchase|checkout|place order|buy now|pay|payment|transfer|withdraw|sign in|log in|apply|confirm|authorize)\b|পাঠাও|পাঠান|প্রকাশ|জমা|মুছ|পেমেন্ট|কেনা|অর্ডার/.test(label);
}

function unsafeClickTarget(ref) {
  const href = String(ref.fingerprint?.href || '').trim();
  if (!href) return false;
  try {
    return !['http:', 'https:'].includes(new URL(href).protocol);
  } catch {
    return true;
  }
}

function targetIsSensitive(ref) {
  const fp = ref.fingerprint || {};
  return sensitiveField({
    name: ref.name,
    attributes: {
      type: fp.type,
      autocomplete: fp.autocomplete,
      id: fp.id,
      nameAttr: fp.nameAttr,
      placeholder: fp.placeholder
    }
  });
}

function requiresActionConfirmation(action, ref, includePrivateDetails = false) {
  return (action === 'click' && highImpactTarget(ref)) ||
    (['fill', 'type'].includes(action) && targetIsSensitive(ref)) ||
    (action === 'read' && includePrivateDetails);
}

function safeActionResult(raw, action, ref, options = {}) {
  let result = {};
  try { result = JSON.parse(raw); } catch {}
  const output = {
    status: safeText(result.status || 'ACTION_COMPLETED', 48),
    action,
    ref: safeText(ref, 48),
    characters: Number(result.characters || 0),
    note: options.includePrivateDetails ? 'User-approved private element details were shared for this one action.' :
      (action === 'read' ? 'Private field values were not requested.' : 'One browser action completed; take a fresh snapshot to verify.')
  };
  if (options.includePrivateDetails && result.element && typeof result.element === 'object') {
    const el = result.element;
    output.privateDetails = {
      tag: privateText(el.tag, 32),
      role: privateText(el.roleAttr, 48),
      name: privateText(el.name, 180),
      id: privateText(el.id, 180),
      nameAttr: privateText(el.nameAttr, 180),
      type: privateText(el.type, 48),
      placeholder: privateText(el.placeholder, 240),
      href: safeUrl(el.href, true),
      autocomplete: privateText(el.autocomplete, 80),
      value: el.value == null ? null : privateText(el.value, 1200),
      checked: typeof el.checked === 'boolean' ? el.checked : null,
      readOnly: Boolean(el.readOnly),
      disabled: Boolean(el.disabled)
    };
  }
  return output;
}

function cliEnv() {
  const env = { ...process.env };
  delete env.NODE_PATH;
  return {
    ...env,
    ERA_PHONE_BROWSER_STATE_DIR: STATE_DIR,
    CDP_URL
  };
}

function runCli(args, options = {}) {
  const result = spawnSync(process.execPath, [path.join(RUNTIME_DIR, 'semantic-browser.js'), ...args], {
    encoding: 'utf8',
    input: options.input,
    timeout: options.timeout || 25_000,
    maxBuffer: MAX_RESPONSE_BYTES,
    env: cliEnv()
  });
  if (result.error) throw new Error('Browser CLI could not start: ' + safeText(result.error.message, 140));
  if (result.status !== 0) throw new Error(safeText(result.stderr || result.stdout || 'Browser command failed.', 240));
  try {
    return JSON.parse(String(result.stdout || '').trim());
  } catch {
    throw new Error('Browser CLI returned an unreadable response.');
  }
}

function targetArg(value) {
  const target = String(value || '');
  if (!/^[a-f0-9-]{8,}$/i.test(target)) throw new Error('Invalid or missing Chrome target ID. Refresh the tab list.');
  return 'target:' + target;
}

function readSavedRef(targetId, refId) {
  const safeTarget = targetId.replace(/[^a-zA-Z0-9_-]/g, '');
  const file = path.join(STATE_DIR, 'semantic-snapshot-target-' + safeTarget + '.json');
  if (!fs.existsSync(file)) throw new Error('No saved semantic snapshot for this tab. Capture a fresh snapshot.');
  const saved = JSON.parse(fs.readFileSync(file, 'utf8'));
  if (saved.targetId !== targetId || !saved.refs || !saved.refs[refId]) {
    throw new Error('The browser element reference is stale. Capture a fresh snapshot.');
  }
  return saved.refs[refId];
}

function authorized(req, token) {
  const header = String(req.headers.authorization || '');
  const supplied = header.startsWith('Bearer ') ? header.slice(7) : '';
  const left = Buffer.from(supplied);
  const right = Buffer.from(token);
  return left.length === right.length && left.length > 0 && crypto.timingSafeEqual(left, right);
}

function loopbackOnly(req) {
  const address = req.socket.remoteAddress || '';
  return address === '127.0.0.1' || address === '::1' || address === '::ffff:127.0.0.1';
}

function dispatchLocal(method, pathname, body = {}) {
  if (method === 'GET' && pathname === '/v1/health') {
    return { status: 'READY', service: 'ERA Phone Browser Agent local runtime', version: 2 };
  }
  if ((method === 'GET' || method === 'POST') && pathname === '/v1/browser/sessions') {
    const includePrivateDetails = body.includePrivateDetails === true;
    if (includePrivateDetails && body.confirmed !== true) {
      throw new Error('Full tab URLs and query parameters require one-time user confirmation.');
    }
    return sanitizeSessions(runCli(['sessions']), { includePrivateDetails });
  }
  if (method === 'POST' && pathname === '/v1/browser/snapshot') {
    const target = targetArg(body.targetId);
    const includePrivateDetails = body.includePrivateDetails === true;
    if (includePrivateDetails && body.confirmed !== true) {
      throw new Error('Editable values, raw selectors and full URL query details require one-time user confirmation.');
    }
    return sanitizeSnapshot(runCli(['snapshot', target]), { includePrivateDetails });
  }
  if (method === 'POST' && pathname === '/v1/browser/action') {
    if (body.approved !== true) throw new Error('Every browser action requires explicit ERA approval.');
    const target = targetArg(body.targetId);
    const refId = String(body.ref || '');
    if (!/^@s[a-z0-9]+:e[1-9][0-9]{0,3}$/i.test(refId)) throw new Error('Invalid semantic ref. Capture a fresh snapshot.');
    const action = String(body.action || '').toLowerCase();
    if (!['read', 'click', 'fill', 'type'].includes(action)) throw new Error('Unsupported browser action.');
    const ref = readSavedRef(String(body.targetId), refId);
    if (action === 'click' && !ref.clickable) throw new Error('Selected target is not clickable.');
    if (['fill', 'type'].includes(action) && !ref.editable) throw new Error('Selected target is not an editable field.');
    const includePrivateDetails = body.includePrivateDetails === true;
    const confirmationRequired = requiresActionConfirmation(action, ref, includePrivateDetails);
    if ((confirmationRequired || includePrivateDetails) && body.confirmed !== true) {
      throw new Error('This browser action or private data access requires a fresh one-time confirmation in ERA.');
    }
    if (action === 'click' && unsafeClickTarget(ref)) {
      throw new Error('Non-web navigation schemes (javascript, data, file, tel, intent, etc.) remain blocked.');
    }
    let input;
    const args = [action, target, refId];
    if (['fill', 'type'].includes(action)) {
      input = String(body.text || '');
      if (!input || input.length > 4_000) throw new Error('Text must contain 1–4000 characters.');
      args.push('--stdin');
    }
    const raw = runCli(args, { input });
    const result = safeActionResult(JSON.stringify(raw), action, refId, { includePrivateDetails });
    if (action === 'click' || action === 'fill' || action === 'type') {
      try { result.followUpSnapshot = sanitizeSnapshot(runCli(['snapshot', target])); }
      catch (error) { result.verificationNote = safeText(error.message, 180); }
    }
    return result;
  }
  throw new Error('Unknown ERA Phone Browser Agent command.');
}

module.exports = {
  dispatchLocal, safeText, safeUrl, sanitizeNode, sanitizeSnapshot, sanitizeSessions,
  sensitiveField, highImpactTarget, unsafeClickTarget, targetIsSensitive,
  requiresActionConfirmation, safeActionResult, authorized, loopbackOnly
};
