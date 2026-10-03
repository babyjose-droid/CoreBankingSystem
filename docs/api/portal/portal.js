// Developer portal (US-119): renders the API reference from /developer/openapi.yaml and the guide from
// /developer/guide.md in the browser. No dependencies and no external requests. Everything is written to the
// page as text nodes (never innerHTML), so nothing in the documents can run as script.
(function () {
  'use strict';

  var METHODS = ['get', 'post', 'put', 'patch', 'delete'];

  function el(tag, text, className) {
    var e = document.createElement(tag);
    if (text !== undefined && text !== null) e.textContent = text;
    if (className) e.className = className;
    return e;
  }

  function unquote(s) {
    s = s.trim();
    if ((s[0] === '"' && s[s.length - 1] === '"') || (s[0] === "'" && s[s.length - 1] === "'")) return s.slice(1, -1);
    return s;
  }

  // The contract is written in a regular style (two-space indentation), so the parts shown here can be read
  // line by line without a YAML library: info.title/version, and under `paths:` each path (2 spaces), method
  // (4 spaces), its tags/summary/operationId (6 spaces) and response codes (8 spaces under `responses:`).
  function parseContract(yaml) {
    var lines = yaml.split(/\r?\n/);
    var info = {}, ops = [];
    var section = null, path = null, op = null, inResponses = false;
    for (var i = 0; i < lines.length; i++) {
      var line = lines[i];
      if (/^[A-Za-z]/.test(line)) { section = line.split(':')[0]; path = null; op = null; continue; }
      var m;
      if (section === 'info') {
        if ((m = /^  (title|version):\s*(.+)$/.exec(line))) info[m[1]] = unquote(m[2]);
        continue;
      }
      if (section !== 'paths') continue;
      if ((m = /^  (\/[^\s:]*):\s*$/.exec(line))) { path = m[1]; op = null; continue; }
      if (path && (m = /^    ([a-z]+):\s*$/.exec(line)) && METHODS.indexOf(m[1]) >= 0) {
        op = { method: m[1].toUpperCase(), path: path, tags: [], summary: '', operationId: '', codes: [] };
        ops.push(op);
        inResponses = false;
        continue;
      }
      if (!op) continue;
      if ((m = /^      ([A-Za-z]+):\s*(.*)$/.exec(line))) {
        inResponses = m[1] === 'responses';
        if (m[1] === 'summary') op.summary = unquote(m[2]);
        if (m[1] === 'operationId') op.operationId = unquote(m[2]);
        if (m[1] === 'tags') op.tags = m[2].replace(/^\[|\]$/g, '').split(',').map(unquote).filter(Boolean);
        continue;
      }
      if (inResponses && (m = /^        '?([0-9]{3}|default)'?:/.exec(line)) && m[1] !== 'default') op.codes.push(m[1]);
    }
    return { info: info, ops: ops };
  }

  function renderOperations(ops, filter) {
    var root = document.getElementById('operations');
    while (root.firstChild) root.removeChild(root.firstChild);
    var needle = (filter || '').trim().toLowerCase();
    var shown = ops.filter(function (o) {
      return !needle || (o.path + ' ' + o.summary + ' ' + o.operationId + ' ' + o.method).toLowerCase().indexOf(needle) >= 0;
    });
    var byTag = {};
    shown.forEach(function (o) {
      var tag = o.tags[0] || 'Other';
      (byTag[tag] = byTag[tag] || []).push(o);
    });
    Object.keys(byTag).sort().forEach(function (tag) {
      root.appendChild(el('h3', tag));
      var table = el('table');
      var head = el('tr');
      ['Method', 'Path', 'What it does', 'Responses'].forEach(function (h) { head.appendChild(el('th', h)); });
      table.appendChild(head);
      byTag[tag].forEach(function (o) {
        var tr = el('tr');
        tr.appendChild(el('td', o.method, 'method ' + o.method));
        tr.appendChild(el('td', o.path, 'path'));
        var what = el('td', o.summary);
        if (o.operationId) { what.appendChild(el('br')); what.appendChild(el('code', o.operationId)); }
        tr.appendChild(what);
        tr.appendChild(el('td', o.codes.join(' '), 'codes'));
        table.appendChild(tr);
      });
      root.appendChild(table);
    });
    document.getElementById('count').textContent = shown.length + ' of ' + ops.length + ' operations';
  }

  // A small Markdown reader for the guide: headings, paragraphs, lists, fenced code, inline code.
  function inline(parent, text) {
    text.split(/(`[^`]+`)/).forEach(function (part) {
      if (part.length > 1 && part[0] === '`' && part[part.length - 1] === '`') parent.appendChild(el('code', part.slice(1, -1)));
      else if (part) parent.appendChild(document.createTextNode(part.replace(/\*\*([^*]+)\*\*/g, '$1')));
    });
  }

  function renderGuide(markdown) {
    var root = document.getElementById('guide-body');
    while (root.firstChild) root.removeChild(root.firstChild);
    var lines = markdown.split(/\r?\n/);
    var para = [], list = null, code = null;
    function flush() {
      if (para.length) { var p = el('p'); inline(p, para.join(' ')); root.appendChild(p); para = []; }
      list = null;
    }
    lines.forEach(function (line) {
      if (code) {
        if (/^```/.test(line)) { var pre = el('pre'); pre.appendChild(el('code', code.join('\n'))); root.appendChild(pre); code = null; }
        else code.push(line);
        return;
      }
      var m;
      if (/^```/.test(line)) { flush(); code = []; return; }
      if ((m = /^(#{1,4})\s+(.*)$/.exec(line))) { flush(); root.appendChild(el('h' + Math.min(m[1].length + 2, 4), m[2])); return; }
      if ((m = /^\s*[-*]\s+(.*)$/.exec(line))) {
        if (para.length) { var p = el('p'); inline(p, para.join(' ')); root.appendChild(p); para = []; }
        if (!list) { list = el('ul'); root.appendChild(list); }
        var li = el('li'); inline(li, m[1]); list.appendChild(li);
        return;
      }
      if (/^\s*$/.test(line)) { flush(); return; }
      if (list && /^\s+\S/.test(line) && list.lastChild) { inline(list.lastChild, ' ' + line.trim()); return; }
      list = null;
      para.push(line.trim());
    });
    flush();
  }

  function load(url, ok, failText, targetId) {
    fetch(url, { credentials: 'omit' }).then(function (r) {
      if (!r.ok) throw new Error(String(r.status));
      return r.text();
    }).then(ok).catch(function () {
      var root = document.getElementById(targetId);
      while (root.firstChild) root.removeChild(root.firstChild);
      root.appendChild(el('p', failText, 'muted'));
    });
  }

  load('/developer/openapi.yaml', function (yaml) {
    var c = parseContract(yaml);
    if (c.info.title) { document.getElementById('title').textContent = c.info.title; document.title = c.info.title + ' – developer portal'; }
    if (c.info.version) document.getElementById('version').textContent = 'Contract version ' + c.info.version;
    renderOperations(c.ops, '');
    document.getElementById('filter').addEventListener('input', function (e) { renderOperations(c.ops, e.target.value); });
  }, 'The OpenAPI document is not available on this installation.', 'operations');

  load('/developer/guide.md', renderGuide, 'The guide is not available on this installation; see docs/api/portal/guide.md in the repository.', 'guide-body');
})();
