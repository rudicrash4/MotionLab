# MotionLab Android — 0.4.0

Editor video native Android (Kotlin, Jetpack Compose, Media3 + OpenGL ES 2.0, MediaCodec), dibangun via GitHub Actions.

## Build
Upload isi folder ini LANGSUNG ke root repository (settings.gradle.kts harus ada di root), buka tab Actions →
"Build MotionLab APK" → unduh artifact `MotionLab-debug-APK`. Bila gagal, artifact `MotionLab-build-log` + ringkasan error di halaman run.

## Status verifikasi (jujur)
- Kode non-Compose (core, render/GlCompositor, export, track, EditorViewModel) sudah dikompilasi dengan kotlinc 2.0.21 + android.jar API 35 di lingkungan pengembangan: tanpa error.
- Logika Speed Ramp / trim / split / preset diuji dengan skrip JVM (semua lulus).
- UI Compose, VideoSourceManager (Media3) dan seluruh build Gradle/APK BELUM dikompilasi; tidak ada runtime test di perangkat.

## Fitur baru 0.4.0
- Speed Ramp 0.1x–8x: kurva keyframe kecepatan, waktu sumber = integral kecepatan; titik tambah/hapus/seret, interpolasi Linear/Ease/Custom bezier; preset Montage, Slow Motion, Speed Up, Smooth Acceleration/Deceleration, Action, Custom. Trim/split/seek/durasi/ekspor mengikuti kurva; migrasi proyek lama → 1x.
- Ekspor MP4 H.264 + AAC (720p/1080p/1440p/4K hanya bila didukung encoder perangkat; 24/25/30/60 fps; bitrate Rendah/Sedang/Tinggi), compositor yang sama dengan preview, progres, batal, hasil di Movies/MotionLab.
- Audio: waveform asli, fade in/out, mute/solo track, time-stretch pitch-preserving (WSOLA) saat ekspor.
- Teks: font TTF/OTF, weight, miring, rata, jarak huruf/baris, outline, bayangan, latar.
- Efek: nyala/mati per efek, urutan warp, preset, salin/tempel antar klip.
- Proyek: banyak proyek, duplikat, hapus (konfirmasi), cadangan .bak + pemulihan, peringatan media hilang, simpan otomatis.
- Perbaikan: pemotongan keyframe kini mempertahankan bentuk easing.

## Batasan yang diketahui
- Ekspor berjalan di proses aplikasi (bukan foreground service): jangan tutup aplikasi saat ekspor.
- Tracker tetap 2D template matching (tanpa perbaikan manual baru).
- Rotasi video pada ekspor mengandalkan KEY_ROTATION decoder; belum diuji di perangkat.
