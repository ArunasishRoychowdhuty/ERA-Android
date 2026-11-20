package com.eraassistant.mobile.screen

/**
 * JavaScript for WebViews owned by ERA. It cannot inspect Chrome or another app's private DOM.
 * The caller must make an explicit user-initiated evaluateJavascript call.
 */
object WebDomSnapshotScript {
    val SCRIPT: String = """
        (() => {
          const maxNodes = 500;
          const maxText = 300;
          const out = [];
          const clean = (v) => (v || '').toString().replace(/\s+/g, ' ').trim().slice(0, maxText);
          const safeHref = (raw) => {
            try { const u = new URL(raw, location.href); return u.origin + u.pathname; }
            catch (_) { return ''; }
          };
          const walk = (el, parentIndex = null) => {
            if (!el || out.length >= maxNodes) return;
            const tag = (el.tagName || '').toLowerCase();
            const type = (el.getAttribute && el.getAttribute('type') || '').toLowerCase();
            if (el.hidden || (tag === 'input' && type === 'hidden')) return;
            const style = window.getComputedStyle ? getComputedStyle(el) : null;
            if (style && (style.display === 'none' || style.visibility === 'hidden')) return;
            const password = tag === 'input' && type === 'password';
            const rect = el.getBoundingClientRect ? el.getBoundingClientRect() : null;
            const text = password ? '' : clean(el.innerText || el.value || el.textContent);
            const item = {
              index: out.length,
              parentIndex,
              tag,
              id: clean(el.id),
              role: clean(el.getAttribute && el.getAttribute('role')),
              text,
              ariaLabel: password ? '' : clean(el.getAttribute && el.getAttribute('aria-label')),
              type,
              href: tag === 'a' ? safeHref(el.getAttribute('href')) : '',
              clickable: !!(el.matches && el.matches('button,a,input[type=button],input[type=submit],[role=button]')),
              editable: !!(el.matches && el.matches('textarea,input:not([type=button]):not([type=submit]),[contenteditable=true]')) && !password,
              disabled: !!el.disabled,
              password,
              bounds: rect ? {left: Math.round(rect.left), top: Math.round(rect.top), right: Math.round(rect.right), bottom: Math.round(rect.bottom)} : null
            };
            const current = out.length;
            out.push(item);
            Array.from(el.children || []).forEach(child => walk(child, current));
          };
          walk(document.body);
          return JSON.stringify({schemaVersion:'era.dom.v1', url:location.origin + location.pathname, title:document.title, nodeCount:out.length, truncated:out.length >= maxNodes, nodes:out});
        })();
    """.trimIndent()
}
