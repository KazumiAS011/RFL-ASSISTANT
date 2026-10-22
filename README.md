# RFL-ASSISTANT — APK

RFL dibungkus jadi aplikasi Android. Server RFL (Node.js) jalan **murni di HP
user** — tidak ada server pusat. User pasang APK **satu kali**; sesudah itu
seluruh isi mengikuti update berkas dari repo GitHub ini.

## Prinsip desain

APK = **cangkang kurus yang dibekukan**. Semua kemampuan yang menyentuh
sistem Android harus sudah lengkap di cangkang sejak hari pertama, karena
cangkang tidak boleh perlu diganti dalam waktu lama. Semua yang lain —
fitur, tampilan, bahkan runtime — adalah berkas yang bisa ditukar updater.

| Lapisan | Isi | Berubah lewat |
|---|---|---|
| Cangkang (APK) | WebView, penghidup server, updater, izin, layanan latar | Install ulang APK (dirancang: hampir tidak pernah) |
| Runtime | `bin/node`, `bin/ffmpeg` | Update berkas (komponen terpisah) |
| Aplikasi | `app/` = server.js + lib + public (JavaScript) | Update berkas (komponen `app`) |
| Data user | `data/` (token, preferensi, riwayat) | Tidak pernah disentuh updater |

File manager **tidak** ikut (keputusan Andika 2026-10-22). Fokus aplikasi:
unduhan & media (telusur, putar, unduh ke folder Unduhan HP).

## Keputusan kunci

- **targetSdk 28** (pola Termux): Android modern melarang menjalankan biner
  dari folder data aplikasi untuk target baru; target 28 dikecualikan,
  sehingga `node`/`ffmpeg` hasil unduhan tetap bisa dieksekusi. Konsekuensi:
  distribusi sideload (bukan Play Store), mungkin ada peringatan
  "aplikasi lama" saat pasang pertama.
- **minSdk 24** (Android 7) biar jangkauan HP user luas; compileSdk 34.
- Server hanya mendengar di `127.0.0.1:3000`; WebView membukanya dari situ.
- Unduhan ditulis server ke penyimpanan bersama `Download/RFL-ASSISTANT`
  (izin storage gaya lama berlaku karena targetSdk 28).

## Protokol update (GitHub)

Updater di cangkang membaca `manifest.json` dari repo ini (raw URL):

```json
{
  "components": {
    "app":    { "version": "3.32.90", "url": "<url zip>", "sha256": "<hex>", "size": 123 },
    "node":   { "version": "20.x",    "url": "<url zip>", "sha256": "<hex>", "size": 123 },
    "ffmpeg": { "version": "9.0.2",   "url": "<url zip>", "sha256": "<hex>", "size": 123 }
  },
  "minShell": "1.0",
  "notes": "catatan rilis"
}
```

Alur per komponen yang versinya lebih baru dari yang terpasang:
unduh zip ke `data/staging/` → verifikasi SHA-256 → ekstrak → tukar folder
atomik (folder lama disimpan sebagai cadangan 1 generasi) → restart server.
Paket zip diterbitkan sebagai aset GitHub Release; `manifest.json` di akar
repo menunjuk ke aset terakhir. Kalau `minShell` melebihi versi cangkang,
updater berhenti dan memberi tahu user (satu-satunya kasus yang memaksa
APK baru — dijaga agar tidak terjadi).

## Tata letak repo

```
manifest.json        manifes update terakhir (dibaca updater)
shell/               sumber APK (proyek Gradle ini, untuk pengembangan)
app/                 sumber aplikasi JS (= isi komponen "app")
packages/            catatan paket rilis (url + sha256 tiap versi)
```

Isi awal `app/` adalah kerangka: server minimal yang menjawab `/api/ping`
dan halaman sambutan. Fitur-fitur RFL dipindah masuk bertahap lewat update
`app` — persis pola deploy RFL selama ini (klaim versi → zip → pasang).

## Membangun APK (di HP, Termux)

Prasyarat: JDK + Gradle + `~/android-sdk` (platforms; build-tools).
Biner runtime ditaruh sekali di `shell/app/src/main/assets/payload.zip`
(isinya `bin/node`, `bin/ffmpeg`, dan salinan awal `app/`), lalu:

```
cd shell
gradle assembleRelease   # atau assembleDebug untuk uji cepat
```

APK hasil build ditandatangani keystore rilis milik Andika (tidak ikut repo).
