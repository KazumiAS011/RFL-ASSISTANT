# app-0.3.1 — modul NHentai

Tanggal: 2026-10-23 (WIB)
Komponen: app
Versi: 0.3.1 (dari 0.3.0)
URL: https://raw.githubusercontent.com/KazumiAS011/RFL-ASSISTANT/main/app/server.js
sha256 (berkas app/server.js): 086e5773fc4d14603ab7b8110ee8368ac2b994732bbaf395bdbec8ef7f31b0a9
Ukuran (berkas app/server.js): 1728017 byte

Catatan pola rilis: 0.3.1 mengikuti pola 0.3.0 — komponen `app` berformat
`file`, jadi manifest menunjuk langsung ke berkas app/server.js di main dan
sha256/ukuran di atas adalah sha256/ukuran berkas itu sendiri, yang
diverifikasi updater lewat installFileComponent. Ini BUKAN aset zip GitHub
Release (alur zip + Release di packages/README.md belum dipakai untuk
komponen app di 0.3.0 maupun 0.3.1; bila nanti dipindah ke pola itu,
manifest + catatan ini harus diperbarui bersamaan).

Perubahan:
- Modul server NHentai (MOD_NHENTAI, port live RFL 3.32.92) masuk bundle:
  home, list, search, tagged, detail, pages, stream PDF, file, download,
  jobs, cancel, plus rute baru GET /api/nhentai/images-zip (ZIP gambar
  asli yang dialirkan via zipStreamTo bundle).
- Klien NHentai (/js/nhentai.js), jendela win-nhen, tombol launcher
  NHentai, dan CSS nhentai.css dari live masuk bundle. Launcher sudah
  punya ICONS.nhen + pemanggilan nhenEnter sejak 0.3.0.
- MOD_PDF.convertForPdf DIAKTIFKAN dengan ffmpeg runtime APK
  (<files>/bin/ffmpeg, dari payload/updater cangkang; fallback PATH):
  WebP/GIF/AVIF/BMP/TIFF di-convert dulu ke JPG (PNG bila ber-alfa,
  frame pertama untuk animasi), persis pola live 3.32.91. Bila ffmpeg
  belum terpasang atau convert gagal, halaman itu jadi penanda + log
  dan PDF tidak hancur; tombol ⬇ Gambar (ZIP gambar asli) tetap
  tersedia sebagai fallback.
- Dipertahankan dari live: headerSafeFile (3.32.89), seenHost/seenH di
  dalam loop per-halaman (3.32.90), filter keamanan + normalisasi
  verbatim (isi set blokir tidak diubah), rak server tetap dihapus
  (localStorage), tanpa token web, tanpa python.
- Wilayah Eriri di luar penambahan modul NHentai + manifest + package
  tidak disentuh.
