#!/usr/bin/env node
/**
 * tnube-router — Router de modelos IA estilo OpenRouter para Linux-TNube Pro.
 *
 * Escucha en http://127.0.0.1:<puerto>/v1 (OpenAI-compatible) y decide a qué
 * proveedor/modelo manda cada petición según su contenido ("enrutado inteligente"):
 *
 *   - imagen en el mensaje        -> rol "vision"
 *   - código / depuración         -> rol "code"
 *   - contexto muy largo          -> rol "long"
 *   - resto (charla, dudas)       -> rol "fast"
 *
 * Si el proveedor elegido falla (429, 5xx, red) prueba el siguiente candidato.
 * Además lleva un MEDIDOR de uso local por proveedor y día (peticiones + tokens),
 * que la app lee para mostrar "cuánto te queda".
 *
 * Config:  ~/.tnube-router.json          (lo escribe la app desde Ajustes)
 * Uso:     ~/.tnube-router-usage.json    (lo escribe este router)
 *
 * Sin dependencias externas. Node >= 18 (fetch/ReadableStream). Probado con Node 24.
 *
 *   node tnube-router.mjs
 */

import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const HOME = os.homedir();
const CFG_PATH = process.env.TNUBE_ROUTER_CONFIG || path.join(HOME, '.tnube-router.json');
const USAGE_PATH = process.env.TNUBE_ROUTER_USAGE || path.join(HOME, '.tnube-router-usage.json');

function loadJson(p, dflt) {
  try { return JSON.parse(fs.readFileSync(p, 'utf8')); } catch { return dflt; }
}
function saveJson(p, obj) {
  try {
    const tmp = p + '.tmp';
    fs.writeFileSync(tmp, JSON.stringify(obj, null, 2));
    fs.renameSync(tmp, p);
  } catch (e) { log('warn: no se pudo guardar ' + p + ': ' + e.message); }
}
function log(...a) { process.stdout.write('[tnube-router] ' + a.join(' ') + '\n'); }

let CFG = loadJson(CFG_PATH, null);
if (!CFG) {
  console.error('[tnube-router] falta la config ' + CFG_PATH + '. Ábrela desde Ajustes → APIs de IA.');
  process.exit(2);
}

const PORT = CFG.port || 8790;
const PROVIDERS = CFG.providers || {};                 // id -> { name, baseUrl, apiKey, models:{...} }
const ROLES = CFG.roles || { fast: [], code: [], long: [], vision: [] };
const PRIORITY = CFG.priority || Object.keys(PROVIDERS);
const ENABLED = new Set(CFG.enabled || Object.keys(PROVIDERS));

/* --------------------------- medidor de uso --------------------------- */
function today() { return new Date().toISOString().slice(0, 10); }

function bump(providerId, model, inTok, outTok, ok) {
  const u = loadJson(USAGE_PATH, { days: {} });
  const d = today();
  u.days = u.days || {};
  u.days[d] = u.days[d] || {};
  const e = u.days[d][providerId] = u.days[d][providerId] || { requests: 0, errors: 0, inTokens: 0, outTokens: 0, lastModel: '' };
  e.requests += 1;
  if (!ok) e.errors += 1;
  e.inTokens += inTok || 0;
  e.outTokens += outTok || 0;
  if (model) e.lastModel = model;
  // Conserva solo los últimos 14 días.
  const keys = Object.keys(u.days).sort();
  while (keys.length > 14) delete u.days[keys.shift()];
  u.updatedAt = Date.now();
  saveJson(USAGE_PATH, u);
}

/* --------------------------- proveedores --------------------------- */
function candidates(role) {
  const seen = new Set();
  const out = [];
  const push = (id) => {
    if (!id || seen.has(id)) return;
    seen.add(id);
    const p = PROVIDERS[id];
    if (!p) return;
    if (!ENABLED.has(id)) return;
    if (!p.apiKey || !String(p.apiKey).trim()) return;
    out.push(id);
  };
  (ROLES[role] || []).forEach(push);
  PRIORITY.forEach(push);
  PROVIDERS && Object.keys(PROVIDERS).forEach(push);
  return out;
}

function modelFor(id, role) {
  const p = PROVIDERS[id] || {};
  const m = p.models || {};
  if (role === 'vision') return m.vision || m.default || m.fast || '';
  if (role === 'code') return m.code || m.default || m.fast || '';
  if (role === 'long') return m.long || m.default || m.fast || '';
  return m.fast || m.default || m.code || '';
}

function classify(body) {
  const msgs = Array.isArray(body.messages) ? body.messages : [];
  let text = '';
  let hasImage = false;
  for (const m of msgs) {
    if (typeof m.content === 'string') { text += '\n' + m.content; continue; }
    if (Array.isArray(m.content)) {
      for (const c of m.content) {
        if (!c) continue;
        if (c.type === 'image_url' || c.image_url) hasImage = true;
        if (typeof c.text === 'string') text += '\n' + c.text;
      }
    }
  }
  if (hasImage) return 'vision';
  const codeHits = (text.match(/```/g) || []).length * 3 +
    (text.match(/\b(function|class|def |import |const |let |=>|SELECT |INSERT |<html|stack ?trace|traceback|exception|compile|refactor|regex|API|bug|error 5\d\d)\b/gi) || []).length;
  if (codeHits >= 4) return 'code';
  if (text.length > 12000) return 'long';
  return 'fast';
}

/* --------------------------- proxy --------------------------- */
async function forward(providerId, body, model, stream) {
  const p = PROVIDERS[providerId];
  const url = p.baseUrl.replace(/\/+$/, '') + '/chat/completions';
  const payload = Object.assign({}, body);
  payload.model = model;
  if (p.maxTokens && !payload.max_tokens && !payload.max_completion_tokens) payload.max_tokens = p.maxTokens;
  const headers = {
    'content-type': 'application/json',
    'authorization': 'Bearer ' + p.apiKey,
  };
  if (p.extraHeaders && typeof p.extraHeaders === 'object') Object.assign(headers, p.extraHeaders);
  if (/openrouter/i.test(p.baseUrl)) {
    headers['HTTP-Referer'] = 'https://tnube.pro';
    headers['X-Title'] = 'Linux-TNube Pro';
  }
  const ac = new AbortController();
  const t = setTimeout(() => ac.abort(), (CFG.timeoutMs || 180000));
  let res;
  try {
    res = await fetch(url, { method: 'POST', headers, body: JSON.stringify(payload), signal: ac.signal });
  } finally {
    clearTimeout(t);
  }
  return res;
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let buf = '';
    req.on('data', (c) => { buf += c; if (buf.length > 32 * 1024 * 1024) { reject(new Error('body too large')); req.destroy(); } });
    req.on('end', () => resolve(buf));
    req.on('error', reject);
  });
}

function json(res, code, obj) {
  const s = JSON.stringify(obj);
  res.writeHead(code, { 'content-type': 'application/json', 'content-length': Buffer.byteLength(s) });
  res.end(s);
}

async function handleChat(req, res) {
  let raw;
  try { raw = await readBody(req); } catch (e) { return json(res, 413, { error: { message: e.message } }); }
  let body;
  try { body = JSON.parse(raw || '{}'); } catch (e) { return json(res, 400, { error: { message: 'JSON inválido' } }); }

  // Si el cliente fuerza un modelo concreto "providerId/modelo", lo respetamos.
  let forced = null;
  const reqModel = String(body.model || '');
  if (reqModel && reqModel !== 'auto' && reqModel.includes('/')) {
    const [pid, ...rest] = reqModel.split('/');
    if (PROVIDERS[pid] && ENABLED.has(pid)) forced = { pid, model: rest.join('/') };
  }

  const role = forced ? 'forced' : classify(body);
  const cands = forced ? [forced.pid] : candidates(role);
  const stream = !!body.stream;

  if (!cands.length) {
    return json(res, 503, {
      error: { message: 'tnube-router: no hay proveedores activos con API key para el rol "' + role + '". Actívalos en Ajustes → APIs de IA.' },
    });
  }

  let lastErr = 'sin intentos';
  for (let i = 0; i < cands.length; i++) {
    const pid = cands[i];
    const model = forced ? forced.model : modelFor(pid, role);
    if (!model) { lastErr = pid + ': sin modelo configurado'; continue; }
    let r;
    try {
      r = await forward(pid, body, model, stream);
    } catch (e) {
      lastErr = pid + ': ' + (e && e.message ? e.message : 'red');
      bump(pid, model, 0, 0, false);
      log('fallo ' + lastErr + ' → siguiente');
      continue;
    }
    if (!r.ok) {
      let detail = '';
      try { detail = (await r.text()).slice(0, 300); } catch {}
      lastErr = pid + ' HTTP ' + r.status + ' ' + detail.replace(/\s+/g, ' ').slice(0, 160);
      bump(pid, model, 0, 0, false);
      log('fallo ' + lastErr + ' → siguiente');
      // 400/404 pueden ser culpa del modelo: probamos el siguiente igualmente.
      continue;
    }
    // Éxito: cabecera que dice quién atendió.
    res.setHeader('x-tnube-provider', pid);
    res.setHeader('x-tnube-model', model);
    res.setHeader('x-tnube-role', role);

    if (stream && r.body) {
      res.writeHead(r.status, {
        'content-type': r.headers.get('content-type') || 'text/event-stream',
        'cache-control': 'no-cache',
        'connection': 'keep-alive',
      });
      let outTok = 0, inTok = 0;
      const reader = r.body.getReader();
      const dec = new TextDecoder();
      let carry = '';
      try {
        for (;;) {
          const { done, value } = await reader.read();
          if (done) break;
          res.write(Buffer.from(value));
          carry += dec.decode(value, { stream: true });
          const lines = carry.split('\n');
          carry = lines.pop();
          for (const ln of lines) {
            const s = ln.trim();
            if (!s.startsWith('data:')) continue;
            const d = s.slice(5).trim();
            if (d === '[DONE]') continue;
            try {
              const j = JSON.parse(d);
              if (j.usage) { inTok = j.usage.prompt_tokens || inTok; outTok = j.usage.completion_tokens || outTok; }
            } catch {}
          }
        }
      } catch (e) {
        log('stream interrumpido: ' + e.message);
      } finally {
        res.end();
      }
      bump(pid, model, inTok, outTok, true);
      log('ok(stream) ' + role + ' → ' + pid + '/' + model);
      return;
    }

    const txt = await r.text();
    let usage = {};
    try { usage = JSON.parse(txt).usage || {}; } catch {}
    bump(pid, model, usage.prompt_tokens || 0, usage.completion_tokens || 0, true);
    log('ok ' + role + ' → ' + pid + '/' + model);
    res.writeHead(r.status, { 'content-type': 'application/json' });
    res.end(txt);
    return;
  }

  json(res, 502, { error: { message: 'tnube-router: todos los proveedores fallaron. Último: ' + lastErr } });
}

async function handleModels(res) {
  const data = [];
  const push = (id, name) => data.push({ id, object: 'model', owned_by: 'tnube-router', name });
  push('auto', 'Auto (router inteligente)');
  for (const id of Object.keys(PROVIDERS)) {
    if (!ENABLED.has(id)) continue;
    if (!PROVIDERS[id].apiKey) continue;
    for (const role of ['fast', 'default', 'code', 'long', 'vision']) {
      const m = modelFor(id, role);
      if (m) push(id + '/' + m, PROVIDERS[id].name + ' · ' + m + ' (' + role + ')');
    }
  }
  json(res, 200, { object: 'list', data });
}

function handleUsage(res) {
  const u = loadJson(USAGE_PATH, { days: {} });
  json(res, 200, { ok: true, updatedAt: u.updatedAt || 0, days: u.days || {}, enabled: [...ENABLED] });
}

const server = http.createServer((req, res) => {
  const url = (req.url || '/').split('?')[0];
  if (req.method === 'GET' && (url === '/tnube-usage')) return handleUsage(res);
  if (req.method === 'GET' && (url === '/tnube-health' || url === '/health')) {
    return json(res, 200, { ok: true, providers: [...ENABLED], port: PORT });
  }
  if (req.method === 'GET' && (url === '/v1/models' || url === '/models')) return handleModels(res);
  if (req.method === 'POST' && (url === '/v1/chat/completions' || url === '/chat/completions')) return handleChat(req, res);
  json(res, 404, { error: { message: 'tnube-router: ruta no soportada ' + url } });
});

// Recarga en caliente: si la app reescribe la config, la aplicamos sin reiniciar.
try {
  fs.watch(CFG_PATH, { persistent: false }, () => {
    const fresh = loadJson(CFG_PATH, null);
    if (!fresh) return;
    CFG = fresh;
    log('config recargada');
  });
} catch {}

server.listen(PORT, '127.0.0.1', () => {
  log('escuchando en http://127.0.0.1:' + PORT + '/v1  (' + [...ENABLED].length + ' proveedores activos)');
});
