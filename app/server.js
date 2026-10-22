'use strict'
/*
 * RFL-ASSISTANT — server aplikasi (komponen "app").
 * Berkas ini dan seluruh folder app/ diupdate lewat GitHub TANPA mengganti
 * APK. Kerangka awal: statis + /api/ping. Fitur dipindah masuk bertahap
 * dari RFL versi Termux (server.js + lib/ + public/ aslinya).
 *
 * Lingkungan dari cangkang:
 *   PORT             port dengar (bawaan 3000, hanya 127.0.0.1)
 *   RFL_DATA_DIR     folder data user (di luar folder app — aman dari update)
 *   RFL_DOWNLOAD_DIR folder unduhan bersama (Download/RFL-ASSISTANT)
 */
const http = require('http')
const fs = require('fs')
const path = require('path')

const PORT = Number(process.env.PORT || 3000)
const ROOT = __dirname
const PUB = path.join(ROOT, 'public')
const DATA = process.env.RFL_DATA_DIR || path.join(ROOT, 'data')
const DL_DIR = process.env.RFL_DOWNLOAD_DIR || path.join(DATA, 'unduhan')
fs.mkdirSync(DATA, { recursive: true })
fs.mkdirSync(DL_DIR, { recursive: true })

const pkg = JSON.parse(fs.readFileSync(path.join(ROOT, 'package.json'), 'utf8'))

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.png': 'image/png', '.jpg': 'image/jpeg', '.webp': 'image/webp',
  '.svg': 'image/svg+xml', '.ico': 'image/x-icon'
}

function sendJson(res, code, obj) {
  const body = JSON.stringify(obj)
  res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' })
  res.end(body)
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://127.0.0.1')
  if (url.pathname === '/api/ping') {
    return sendJson(res, 200, {
      ok: true,
      app: pkg.version,
      dataDir: DATA,
      downloadDir: DL_DIR,
      time: new Date().toISOString()
    })
  }
  // Statis dari public/ dengan penjaga path traversal.
  let rel = decodeURIComponent(url.pathname)
  if (rel === '/') rel = '/index.html'
  const file = path.join(PUB, rel)
  if (!file.startsWith(PUB) || !fs.existsSync(file) || !fs.statSync(file).isFile()) {
    return sendJson(res, 404, { error: 'Tidak ditemukan.' })
  }
  res.writeHead(200, {
    'Content-Type': MIME[path.extname(file).toLowerCase()] || 'application/octet-stream',
    'Cache-Control': 'no-cache'
  })
  fs.createReadStream(file).pipe(res)
})

server.listen(PORT, '127.0.0.1', () => {
  console.log('RFL-ASSISTANT (app ' + pkg.version + ') berjalan di http://127.0.0.1:' + PORT)
})
