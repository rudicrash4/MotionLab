# MotionLab 2.0 — Laporan Evaluasi Open-Source (Fase 1, sebelum coding)

Tanggal: 2026-10-30

## Batasan lingkungan (jujur)
- Sandbox tidak punya Android SDK/NDK; `dl.google.com`, `maven.google.com`, `repo.maven.apache.org`, dan `plugins.gradle.org` mengembalikan 403. Artinya **build APK dan tes TIDAK bisa dijalankan di sini**.
- Rencana: seluruh proyek ditulis lengkap + workflow GitHub Actions (build, unit test, assembleDebug). Hasil build/tes baru nyata setelah CI berjalan; sampai itu terjadi tidak ada klaim "lulus".
- Status verifikasi di kolom bawah: **[SRC]** = source di-clone dan diperiksa di sesi ini; **[WEB]** = hanya dari halaman web/README, source belum diperiksa.

## Hasil evaluasi

| # | Subsistem | Kandidat | Lisensi | Status | Keputusan |
|---|-----------|----------|---------|--------|-----------|
| 1 | Playback, export, composition, efek GL, overlay, muxer, speed | **AndroidX Media3 1.11.1** (github.com/androidx/media; rilis 2026-09-10) [SRC] | Apache 2.0 (LICENSE diperiksa) | Aktif. minSdk 23, compileSdk 36. Berisi Transformer, `Composition`, `CompositionPlayer` (experimental), `GlEffect`/`GlShaderProgram`, `DefaultVideoCompositor`, `OverlayEffect`/`TextOverlay`/`BitmapOverlay`, `SingleColorLut`, `GaussianBlur`, `InAppMp4Muxer`, `SpeedProvider` | **USE** via Maven (Google). Menggantikan decoder/encoder/muxer/pipeline GL buatan sendiri |
| 2 | Audio real-time latensi rendah | **google/oboe** (commit terakhir 2026-10-05) [SRC] | Apache 2.0 | Aktif, Prefab, CMake | **USE nanti** (tidak dimasukkan di build Fase 1; audio Fase 1 lewat Media3 dulu — hindari dependensi tak perlu) |
| 3 | Helper EGL/GL (EglCore, Texture2dProgram) | **google/grafika** [SRC] | Apache 2.0 | Commit terakhir 2020 (tidak dirawat), build lama | **REFERENCE ONLY** (keputusan akhir): tidak ada kode yang disalin; `EglCore`/`RenderThread`/`TexturedQuadRenderer` ditulis sendiri mengikuti pola EGL yang sama. Pola umum EGL bukan kode berhak cipta yang disalin, tetapi Grafika dicantumkan sebagai rujukan di OPEN_SOURCE_LICENSES.md |
| 4 | Database | **Room 2.8.5** (2026-09-09) [WEB resmi] | Apache 2.0 | minSdk 23, Kotlin ≥ 2.0, KSP2 | **USE** |
| 5 | Animasi Lottie/After Effects | **Lottie 6.6.0** (dipakai modul `effect_lottie` Media3 [SRC]) | Apache 2.0 (dari pengetahuan umum, belum diverifikasi sesi ini) | Aktif | **DEFER** ke Fase berikutnya (impor Lottie bukan inti keyframe MotionLab) |
| 6 | FFmpeg di Android | **FFmpegKit (arthenica)** [WEB] | LGPL 3 / GPL 3 tergantung build | **Resmi dihentikan (retired), binary dihapus dari Maven** | **REJECT** |
| 6b | Fork komunitas FFmpegKit | ffmpegkit-maintained [WEB] | tidak diverifikasi; sebagian tier berbayar | Tidak berafiliasi, status rilis tidak jelas | **REJECT** |
| 6c | Modul `decoder_ffmpeg` Media3 [SRC] | Apache 2.0 untuk glue-nya | Hanya render audio; FFmpeg harus dibangun manual (LGPL/GPL) | **ADAPT (pola build)**: `scripts/build_ffmpeg_android.sh` mengikuti pendekatan `build_ffmpeg.sh` Media3 (FFmpeg dibangun sendiri dari sumber resmi). Modul Media3-nya sendiri tidak dipakai |
| 6d | **FFmpeg resmi (FFmpeg/FFmpeg, tag n8.1.3 terverifikasi lewat `git ls-remote`)** | LGPL 2.1+ bila tanpa `--enable-gpl/--enable-nonfree` | Dibangun sendiri, **shared library**, arm64-v8a saja, demuxer/decoder minimal | **USE (opsional)** sesuai keputusan pengguna. Dipakai hanya untuk probe metadata (fallback bila MediaMetadataRetriever gagal). Skrip **belum pernah dijalankan** (tidak ada NDK di sandbox) |
| 7 | Filter GL gambar/kamera | **android-gpuimage-plus (wysaid)** [WEB] | MIT | Aktif, tapi kompleks (NDK) dan tumpang tindih dengan efek Media3 | **REJECT** Fase 1 |
| 7b | CyberAgent android-gpuimage [WEB] | Apache 2.0 | Hanya gambar, bukan video pipeline | **REJECT** |
| 8 | Editor video lengkap sebagai basis | NovaCut (SysAdminDoc) [WEB] | tidak diverifikasi | Arsitektur tak diperiksa | **REJECT** sebagai dependensi; hanya referensi ide |
| 8b | SimpleVideoEditor (CreateChance) [WEB] | Apache 2.0 | Kecil, GLES 2.0, 37 bintang, tidak ada rilis | **REJECT** (tumpang tindih Media3) |
| 8c | VideoClipEditViewTest [WEB] | **Tanpa lisensi** | — | **REJECT** (tanpa lisensi = tidak boleh disalin) |
| 9 | Timeline multi-track, keyframe, graph editor, Bezier, motion path, parent-child | Tidak ditemukan pustaka Android yang layak dipakai ulang dalam riset ini | — | Riset terbatas pada pencarian web; belum ada repo yang diperiksa source-nya | **CUSTOM** (model data & evaluator). Evaluasi akhir diserahkan ke Media3 `Composition` untuk render/export |
| 10 | Teks/tipografi | API platform Android (`StaticLayout`, `Typeface`) + Media3 `TextOverlay` | Apache 2.0 | Stabil | **USE** platform; font manager = custom |
| 11 | DI | Injeksi konstruktor manual (container sederhana) | — | Tanpa dependensi | **CUSTOM** (alasan: hindari kapt/KSP tambahan dan risiko versi tak terverifikasi) |

## Versi build (perlu dibuktikan di CI)
Diambil dari `libs.versions.toml` Media3 1.11.1: compileSdk 36, minSdk 23, Kotlin 2.2.0, AGP 9.0.1, Gradle wrapper 9.1.0, Lottie 6.6.0. **Risiko:** AGP 9 mengubah banyak konvensi (mis. Kotlin bawaan); jika CI gagal, turun ke AGP 8.x terbaru yang kompatibel dengan Gradle/KSP. Belum diuji.

## Pertimbangan lisensi
- Semua yang dipakai (Media3, Oboe, Room, Grafika) Apache 2.0: wajib menyertakan LICENSE + NOTICE/atribusi di `OPEN_SOURCE_LICENSES.md`.
- FFmpeg/LGPL/GPL sengaja tidak dimasukkan di Fase 1 untuk menghindari kewajiban tautan ulang/penyebaran sumber.


## Keputusan akhir setelah persetujuan pengguna
- Media3 1.11.1 (USE), Room 2.8.5 (USE), Oboe (ditunda, tidak ada di build), Lottie (ditunda).
- FFmpeg: **ditambahkan** sebagai build LGPL sendiri (shared, opsional). Tanpa menjalankan skrip, APK tetap terbangun dan backend FFmpeg melapor "tidak tersedia".
- Timeline, keyframe/Bezier, kompositing (referensi CPU), mixer audio, project codec, task scheduler, resource manager: **CUSTOM** (tidak ditemukan pustaka yang layak dipakai ulang dalam riset ini; riset terbatas pada pencarian web).
