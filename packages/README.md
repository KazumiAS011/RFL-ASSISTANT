# Catatan paket rilis

Setiap rilis komponen dicatat di sini: satu berkas per rilis,
`<komponen>-<versi>.md`, berisi tanggal, url aset GitHub Release,
sha256 zip-nya, dan catatan perubahan. `manifest.json` di akar repo
selalu menunjuk ke rilis terakhir tiap komponen.

Alur menerbitkan update komponen `app`:

1. Naikkan `version` di `app/package.json` (protokol klaim versi seperti
   biasa: klaim nomor dulu di kotak surat, cek live sebelum pasang).
2. Zip isi folder komponen (untuk `app`: isi `app/`; untuk runtime: isi
   `bin/` berisi biner aarch64).
3. Hitung sha256 zip, unggah sebagai aset GitHub Release bertag
   `<komponen>-<versi>`.
4. Perbarui `manifest.json` (version, url, sha256, size) + tulis catatan
   rilis di folder ini, lalu commit.
5. APK user mengambil alih dari sana: unduh -> verifikasi -> tukar ->
   restart server.
