'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const {
  safeText, safeUrl, sanitizeSnapshot, sanitizeSessions,
  sensitiveField, highImpactTarget, unsafeClickTarget, targetIsSensitive,
  requiresActionConfirmation, safeActionResult, authorized, loopbackOnly
} = require('../runtime/era-browser-adapter');

test('text and URLs redact personal details by default and include query only by opt-in', () => {
  assert.equal(safeText('mail jane@example.com'), 'mail [EMAIL]');
  assert.equal(safeText('call +91 98765 43210'), 'call [PHONE]');
  assert.equal(safeUrl('https://example.com/profile?token=secret#private'), 'https://example.com/profile');
  assert.equal(safeUrl('https://user:pass@example.com/profile?token=secret#private', true), 'https://example.com/profile?token=secret#private');
});

test('snapshot excludes private values/selectors by default and shares them only on opt-in', () => {
  const raw = {
    status: 'SNAPSHOT_OK', targetId: '0123456789abcdef',
    url: 'https://example.com/search?q=private',
    title: 'Results for jane@example.com', nodeCount: 2, complete: true,
    tree: [{
      ref: '@sabc:e1', tag: 'input', role: 'textbox', name: 'Search',
      value: 'private form value',
      attributes: { id: 'query', value: 'private form value', placeholder: 'Search' },
      locator: ['#query'], editable: true, clickable: false, visible: true, children: []
    }, {
      ref: '@sabc:e2', tag: 'input', role: 'textbox', name: 'Password',
      value: 'never expose', attributes: { type: 'password' },
      locator: ['#password'], editable: true, visible: true, children: []
    }],
    accessibility: {
      totalNodes: 1, complete: true,
      frames: [{ status: 'OK', url: 'https://example.com/', nodeCount: 1, tree: [{
        role: 'textbox', name: 'Email jane@example.com', value: 'secret value',
        properties: { disabled: false, value: 'secret value' }, children: []
      }] }]
    }
  };
  const safe = sanitizeSnapshot(raw);
  const serialized = JSON.stringify(safe);
  assert.equal(safe.tree[0].name, 'Search');
  assert.equal(safe.tree[1].name, 'Sensitive field');
  assert.equal(safe.tree[1].sensitive, true);
  assert.equal(safe.url, 'https://example.com/search');
  assert.equal(safe.title, 'Results for [EMAIL]');
  assert.equal(serialized.includes('private form value'), false);
  assert.equal(serialized.includes('never expose'), false);
  assert.equal(serialized.includes('secret value'), false);
  assert.equal(serialized.includes('locator'), false);
  assert.equal(serialized.includes('attributes'), false);
  const shared = sanitizeSnapshot(raw, { includePrivateDetails: true });
  const sharedJson = JSON.stringify(shared);
  assert.equal(shared.includesPrivateDetails, true);
  assert.equal(shared.tree[0].value, 'private form value');
  assert.deepEqual(shared.tree[0].locator, ['#query']);
  assert.equal(shared.url, 'https://example.com/search?q=private');
  assert.equal(sharedJson.includes('never expose'), true);
  assert.equal(sharedJson.includes('locator'), true);
});

test('all password, OTP, token and payment credential fields are sensitive', () => {
  for (const attributes of [
    { type: 'password' },
    { autocomplete: 'one-time-code' },
    { autocomplete: 'cc-number' },
    { nameAttr: 'api_token' },
    { id: 'user-pin' },
    { placeholder: 'Enter verification code' }
  ]) {
    assert.equal(sensitiveField({ name: 'Entry', attributes }), true);
  }
});

test('consequential click targets require one-time approval instead of hard blocking', () => {
  for (const name of ['Send message', 'Publish', 'Submit application', 'Place order', 'Pay now', 'পোস্ট প্রকাশ']) {
    const ref = { name, fingerprint: {} };
    assert.equal(highImpactTarget(ref), true, name);
    assert.equal(requiresActionConfirmation('click', ref, false), true, name);
  }
  const safe = { name: 'Open menu', fingerprint: { type: 'button' } };
  assert.equal(highImpactTarget(safe), false);
  assert.equal(requiresActionConfirmation('click', safe, false), false);
  assert.equal(requiresActionConfirmation('click', { name: 'Continue', fingerprint: { type: 'submit' } }, false), true);
});

test('non-web navigation protocols are blocked from browser clicks', () => {
  assert.equal(unsafeClickTarget({ fingerprint: { href: 'javascript:alert(1)' } }), true);
  assert.equal(unsafeClickTarget({ fingerprint: { href: 'file:///etc/passwd' } }), true);
  assert.equal(unsafeClickTarget({ fingerprint: { href: 'tel:+919876543210' } }), true);
  assert.equal(unsafeClickTarget({ fingerprint: { href: 'https://example.com/path' } }), false);
  assert.equal(unsafeClickTarget({ fingerprint: { href: '' } }), false);
});

test('sensitive fields are permitted only after one-time action confirmation', () => {
  const password = { name: 'Passcode', fingerprint: { type: 'password' } };
  const search = { name: 'Search', fingerprint: { type: 'search' } };
  assert.equal(targetIsSensitive(password), true);
  assert.equal(targetIsSensitive(search), false);
  assert.equal(requiresActionConfirmation('fill', password, false), true);
  assert.equal(requiresActionConfirmation('type', password, false), true);
  assert.equal(requiresActionConfirmation('fill', search, false), false);
  assert.equal(requiresActionConfirmation('read', search, true), true);
  const raw = JSON.stringify({ status: 'READ_OK', element: { tag: 'input', type: 'password', value: 'approved-secret' } });
  assert.equal(JSON.stringify(safeActionResult(raw, 'read', '@sabc:e1')).includes('approved-secret'), false);
  assert.equal(JSON.stringify(safeActionResult(raw, 'read', '@sabc:e1', { includePrivateDetails: true })).includes('approved-secret'), true);
});

test('session list strips query strings and bounds results', () => {
  const value = sanitizeSessions({
    tabs: [{ tab: 0, targetId: 'abcd1234efgh', url: 'https://example.com/path?session=secret', title: 'Hi' }]
  });
  assert.equal(value.tabs[0].url, 'https://example.com/path');
  assert.equal(JSON.stringify(value).includes('session=secret'), false);
  const shared = sanitizeSessions({ tabs: [{ tab: 0, targetId: 'abcd1234efgh', url: 'https://example.com/path?session=secret', title: 'Hi' }] }, { includePrivateDetails: true });
  assert.equal(shared.tabs[0].url, 'https://example.com/path?session=secret');
  assert.equal(shared.includesPrivateDetails, true);
});

test('bridge authentication uses bearer token and loopback-only sockets', () => {
  const token = 'a'.repeat(64);
  assert.equal(authorized({ headers: { authorization: 'Bearer ' + token } }, token), true);
  assert.equal(authorized({ headers: { authorization: 'Bearer wrong' } }, token), false);
  assert.equal(loopbackOnly({ socket: { remoteAddress: '127.0.0.1' } }), true);
  assert.equal(loopbackOnly({ socket: { remoteAddress: '::ffff:127.0.0.1' } }), true);
  assert.equal(loopbackOnly({ socket: { remoteAddress: '192.168.1.10' } }), false);
});
