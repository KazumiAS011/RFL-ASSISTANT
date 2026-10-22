'use strict';
/* RFL-ASSISTANT — komponen app v0.2.0 (satu berkas, tanpa dependensi).
 * Fitur: Unduhan — tempel tautan, server mengunduhnya ke folder
 * Download/RFL-ASSISTANT dengan kemajuan langsung; berkas jadi bisa
 * diputar/dibuka dari halaman yang sama.
 * Berkas ini diperbarui lewat GitHub (format "file"), bukan lewat APK. */
const http = require('http');
const https = require('https');
const fs = require('fs');
const path = require('path');
const { URL } = require('url');

const PORT = Number(process.env.PORT || 3007);
const DATA_DIR = process.env.RFL_DATA_DIR || path.join(__dirname, '..', 'data');
const DL_DIR = process.env.RFL_DOWNLOAD_DIR || path.join(__dirname, '..', 'download');
const APP_VERSION = '0.2.0';
const MAX_ACTIVE = 3;

fs.mkdirSync(DATA_DIR, { recursive: true });
fs.mkdirSync(DL_DIR, { recursive: true });

const jobs = new Map();
let jobSeq = 0;

function sendJson(res, code, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(body);
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', (c) => {
      size += c.length;
      if (size > 1024 * 1024) { reject(new Error('body terlalu besar')); req.destroy(); return; }
      chunks.push(c);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

function sanitizeName(name) {
  let n = String(name || '').split(/[\\/]/).pop().trim();
  n = n.replace(/[^\w.\- ]+/g, '_').replace(/\s+/g, ' ').slice(0, 120);
  if (!n || n === '.' || n === '..') n = '';
  return n;
}

function nameFromResponse(resp, urlObj) {
  const cd = resp.headers['content-disposition'] || '';
  const m = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(cd);
  if (m) {
    try { return sanitizeName(decodeURIComponent(m[1])); } catch { return sanitizeName(m[1]); }
  }
  const fromPath = sanitizeName(decodeURIComponent(urlObj.pathname));
  if (fromPath) return fromPath;
  const extByType = {
    'video/mp4': '.mp4', 'video/webm': '.webm', 'video/x-matroska': '.mkv',
    'audio/mpeg': '.mp3', 'audio/mp4': '.m4a', 'image/jpeg': '.jpg', 'image/png': '.png',
    'application/pdf': '.pdf', 'application/zip': '.zip'
  };
  const ext = extByType[(resp.headers['content-type'] || '').split(';')[0].trim()] || '.bin';
  return 'unduhan-' + new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19) + ext;
}

function uniqueName(dir, name) {
  if (!fs.existsSync(path.join(dir, name))) return name;
  const ext = path.extname(name);
  const base = name.slice(0, name.length - ext.length);
  for (let i = 2; i < 1000; i++) {
    const cand = base + ' (' + i + ')' + ext;
    if (!fs.existsSync(path.join(dir, cand))) return cand;
  }
  return base + '-' + Date.now() + ext;
}

function fetchFollow(urlStr, redirectsLeft) {
  return new Promise((resolve, reject) => {
    const u = new URL(urlStr);
    const lib = u.protocol === 'https:' ? https : http;
    const req = lib.get(u, {
      headers: {
        'User-Agent': 'Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36',
        'Accept': '*/*'
      }
    }, (resp) => {
      const code = resp.statusCode || 0;
      if ([301, 302, 303, 307, 308].includes(code) && resp.headers.location && redirectsLeft > 0) {
        resp.resume();
        resolve(fetchFollow(new URL(resp.headers.location, u).toString(), redirectsLeft - 1));
        return;
      }
      resolve({ resp, finalUrl: u });
    });
    req.on('error', reject);
    req.setTimeout(30000, () => req.destroy(new Error('waktu habis')));
  });
}

async function runJob(job) {
  job.state = 'berjalan';
  try {
    const { resp, finalUrl } = await fetchFollow(job.url, 5);
    if ((resp.statusCode || 0) < 200 || (resp.statusCode || 0) >= 300) {
      resp.resume();
      throw new Error('HTTP ' + resp.statusCode);
    }
    job.total = Number(resp.headers['content-length'] || 0);
    const wanted = job.name || nameFromResponse(resp, finalUrl);
    job.file = uniqueName(DL_DIR, wanted || 'unduhan.bin');
    const partPath = path.join(DL_DIR, job.file + '.part');
    const finalPath = path.join(DL_DIR, job.file);
    const out = fs.createWriteStream(partPath);
    job._stream = out;
    job._resp = resp;
    await new Promise((resolve, reject) => {
      resp.on('data', (c) => { job.got += c.length; });
      resp.on('error', reject);
      out.on('error', reject);
      out.on('finish', resolve);
      resp.pipe(out);
    });
    if (job.cancelled) {
      try { fs.unlinkSync(partPath); } catch { /* abaikan */ }
      job.state = 'batal';
      return;
    }
    fs.renameSync(partPath, finalPath);
    job.size = job.got;
    job.state = 'selesai';
  } catch (e) {
    if (job.cancelled) { job.state = 'batal'; }
    else { job.state = 'gagal'; job.error = String(e && e.message || e); }
  } finally {
    job._stream = null;
    job._resp = null;
    pump();
  }
}

function pump() {
  let active = 0;
  for (const j of jobs.values()) if (j.state === 'berjalan') active++;
  for (const j of jobs.values()) {
    if (active >= MAX_ACTIVE) break;
    if (j.state === 'antri') { active++; runJob(j); }
  }
}

function publicJob(j) {
  return {
    id: j.id, url: j.url, file: j.file || null, state: j.state,
    got: j.got, total: j.total, size: j.size || 0,
    pct: j.total > 0 ? Math.floor(j.got * 100 / j.total) : (j.state === 'selesai' ? 100 : 0),
    error: j.error || null
  };
}

function listFiles() {
  try {
    return fs.readdirSync(DL_DIR)
      .filter((n) => !n.endsWith('.part'))
      .map((n) => {
        try {
          const st = fs.statSync(path.join(DL_DIR, n));
          return st.isFile() ? { name: n, size: st.size } : null;
        } catch { return null; }
      })
      .filter(Boolean)
      .sort((a, b) => a.name.localeCompare(b.name));
  } catch { return []; }
}

const MIME = {
  '.mp4': 'video/mp4', '.webm': 'video/webm', '.mkv': 'video/x-matroska',
  '.mp3': 'audio/mpeg', '.m4a': 'audio/mp4', '.wav': 'audio/wav',
  '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.png': 'image/png', '.gif': 'image/gif',
  '.pdf': 'application/pdf', '.zip': 'application/zip', '.txt': 'text/plain; charset=utf-8'
};

function serveFile(req, res, name) {
  const safe = sanitizeName(decodeURIComponent(name));
  if (!safe) { sendJson(res, 400, { error: 'nama berkas tidak sah' }); return; }
  const fp = path.join(DL_DIR, safe);
  if (!fp.startsWith(DL_DIR)) { sendJson(res, 400, { error: 'jalur tidak sah' }); return; }
  fs.stat(fp, (err, st) => {
    if (err || !st.isFile()) { sendJson(res, 404, { error: 'tidak ditemukan' }); return; }
    const type = MIME[path.extname(safe).toLowerCase()] || 'application/octet-stream';
    const range = req.headers.range;
    if (range) {
      const m = /bytes=(\d*)-(\d*)/.exec(range);
      if (m) {
        let start = m[1] ? parseInt(m[1], 10) : 0;
        let end = m[2] ? parseInt(m[2], 10) : st.size - 1;
        if (isNaN(start) || start < 0) start = 0;
        if (isNaN(end) || end >= st.size) end = st.size - 1;
        res.writeHead(206, {
          'Content-Type': type, 'Content-Range': 'bytes ' + start + '-' + end + '/' + st.size,
          'Accept-Ranges': 'bytes', 'Content-Length': end - start + 1
        });
        fs.createReadStream(fp, { start, end }).pipe(res);
        return;
      }
    }
    res.writeHead(200, { 'Content-Type': type, 'Content-Length': st.size, 'Accept-Ranges': 'bytes' });
    fs.createReadStream(fp).pipe(res);
  });
}

const PAGE = `<!DOCTYPE html>
<html lang="id"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>RFL-ASSISTANT — Unduhan</title>
<style>
  body { background:#000; color:#fff; font-family: system-ui, sans-serif; margin:0; padding:20px 16px 40px; }
  h1 { font-size:19px; letter-spacing:2px; margin:4px 0 2px; }
  .sub { color:#96969e; font-size:12px; margin-bottom:18px; }
  .row { display:flex; gap:8px; }
  input { flex:1; background:#16161a; border:1px solid #2c2c33; border-radius:10px; color:#fff; padding:12px; font-size:14px; min-width:0; }
  button { background:#fff; color:#000; border:0; border-radius:10px; padding:12px 16px; font-weight:700; font-size:14px; }
  .card { background:#101014; border:1px solid #232329; border-radius:12px; padding:12px; margin-top:12px; }
  .name { font-size:13px; word-break:break-all; }
  .meta { color:#96969e; font-size:11px; margin-top:4px; }
  .track { height:5px; background:#26262c; border-radius:3px; margin-top:8px; overflow:hidden; }
  .fill { height:100%; background:#fff; border-radius:3px; }
  .err { color:#ff7a7a; }
  h2 { font-size:13px; letter-spacing:1px; color:#c9c9d1; margin:26px 0 4px; }
  a.file { display:block; color:#fff; text-decoration:none; }
  video { width:100%; border-radius:12px; margin-top:12px; background:#000; }
  .empty { color:#5c5c64; font-size:12px; margin-top:8px; }
</style></head><body>
<h1>RFL-ASSISTANT</h1>
<div class="sub">Unduhan — tempel tautan berkas/video, hasilnya tersimpan di Download/RFL-ASSISTANT</div>
<div class="row">
  <input id="url" type="url" placeholder="Tempel tautan di sini…" autocomplete="off">
  <button id="go">Unduh</button>
</div>
<div id="jobs"></div>
<h2>BERKAS TERSIMPAN</h2>
<div id="files"></div>
<video id="player" controls playsinline style="display:none"></video>
<script>
const $ = (id) => document.getElementById(id);
const mb = (b) => (b / 1048576).toFixed(1).replace('.', ',') + ' MB';
$('go').onclick = async () => {
  const url = $('url').value.trim();
  if (!url) return;
  $('go').disabled = true;
  try {
    const r = await fetch('/api/jobs', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ url }) });
    const j = await r.json();
    if (!r.ok) alert(j.error || 'gagal');
    else $('url').value = '';
  } catch (e) { alert('gagal: ' + e.message); }
  $('go').disabled = false;
  refresh();
};
async function cancel(id) {
  await fetch('/api/cancel', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ id }) });
  refresh();
}
function esc(s) {
  return String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
}
function play(name) {
  const p = $('player');
  p.src = '/api/file/' + encodeURIComponent(name);
  p.style.display = 'block';
  p.scrollIntoView({ behavior: 'smooth' });
  p.play().catch(() => {});
}
async function refresh() {
  try {
    const r = await fetch('/api/jobs');
    const d = await r.json();
    $('jobs').innerHTML = d.jobs.map((j) => {
      const title = esc(j.file || j.url);
      let meta = esc(j.state);
      if (j.state === 'berjalan' || j.state === 'antri') meta += ' — ' + mb(j.got) + (j.total ? ' dari ' + mb(j.total) : '') + ' • ' + j.pct + '%';
      if (j.state === 'selesai') meta = 'selesai — ' + mb(j.size);
      if (j.state === 'gagal') meta = '<span class="err">gagal — ' + esc(j.error || '') + '</span>';
      const bar = (j.state === 'berjalan' || j.state === 'antri')
        ? '<div class="track"><div class="fill" style="width:' + j.pct + '%"></div></div>' : '';
      const btn = (j.state === 'berjalan' || j.state === 'antri')
        ? ' <a href="#" onclick="cancel(' + j.id + ');return false" style="color:#ff7a7a;font-size:11px">batalkan</a>' : '';
      return '<div class="card"><div class="name">' + title + '</div><div class="meta">' + meta + btn + '</div>' + bar + '</div>';
    }).join('');
    $('files').innerHTML = d.files.length
      ? d.files.map((f) => {
          const playable = /\\.(mp4|webm|mkv|mp3|m4a)$/i.test(f.name);
          const act = playable ? ' onclick="play(\\'' + f.name.replace(/'/g, '\\\\\\'') + '\\');return false"' : '';
          return '<a class="file card" href="/api/file/' + encodeURIComponent(f.name) + '"' + act + '><div class="name">' + esc(f.name) + '</div><div class="meta">' + mb(f.size) + (playable ? ' • ketuk untuk putar' : '') + '</div></a>';
        }).join('')
      : '<div class="empty">Belum ada berkas.</div>';
  } catch { /* server sibuk; coba lagi siklus berikut */ }
}
refresh();
setInterval(refresh, 1200);
</script></body></html>`;

const server = http.createServer(async (req, res) => {
  const u = new URL(req.url, 'http://127.0.0.1');
  const route = req.method + ' ' + u.pathname;
  try {
    if (route === 'GET /api/ping') {
      sendJson(res, 200, { ok: true, app: APP_VERSION, dataDir: DATA_DIR, downloadDir: DL_DIR, time: new Date().toISOString() });
      return;
    }
    if (route === 'GET /') {
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
      res.end(PAGE);
      return;
    }
    if (route === 'POST /api/jobs') {
      const body = JSON.parse(await readBody(req) || '{}');
      let parsed;
      try { parsed = new URL(String(body.url || '')); } catch { /* ditangani di bawah */ }
      if (!parsed || !/^https?:$/.test(parsed.protocol)) {
        sendJson(res, 400, { error: 'tautan harus http/https yang sah' });
        return;
      }
      const job = {
        id: ++jobSeq, url: parsed.toString(), name: sanitizeName(body.name || ''),
        state: 'antri', got: 0, total: 0, size: 0, file: null, error: null, cancelled: false
      };
      jobs.set(job.id, job);
      pump();
      sendJson(res, 201, publicJob(job));
      return;
    }
    if (route === 'GET /api/jobs') {
      sendJson(res, 200, { jobs: [...jobs.values()].slice(-20).reverse().map(publicJob), files: listFiles() });
      return;
    }
    if (route === 'POST /api/cancel') {
      const body = JSON.parse(await readBody(req) || '{}');
      const job = jobs.get(Number(body.id));
      if (job && (job.state === 'antri' || job.state === 'berjalan')) {
        job.cancelled = true;
        if (job.state === 'antri') job.state = 'batal';
        if (job._resp) job._resp.destroy();
        if (job._stream) job._stream.destroy();
      }
      sendJson(res, 200, { ok: true });
      return;
    }
    if (req.method === 'GET' && u.pathname.startsWith('/api/file/')) {
      serveFile(req, res, u.pathname.slice('/api/file/'.length));
      return;
    }
    sendJson(res, 404, { error: 'tidak ditemukan' });
  } catch (e) {
    sendJson(res, 500, { error: String(e && e.message || e) });
  }
});

server.listen(PORT, '127.0.0.1', () => {
  console.log('RFL-ASSISTANT app ' + APP_VERSION + ' — http://127.0.0.1:' + PORT);
});
