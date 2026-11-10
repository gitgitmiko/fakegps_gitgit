# Fake GPS (Android)

Aplikasi Android untuk memalsukan lokasi GPS memakai fitur *mock location* bawaan Android.

## Fitur
- Peta OpenStreetMap (tanpa API key). Ketuk peta atau geser pin untuk memilih lokasi.
- Cari tempat berdasarkan nama, atau ketik koordinat `lat, lng`.
- Lokasi palsu dikirim ke provider GPS, Network, dan Fused (Android 12+) tiap 1 detik, dengan jitter kecil (~1 m) supaya terlihat seperti sinyal asli.
- Pindah lokasi ("teleport") saat masih aktif, tanpa harus berhenti dulu.
- Simpan lokasi favorit.
- Berjalan di background sebagai foreground service, dengan tombol "Hentikan" di notifikasi.

## Build
Butuh JDK 17 dan Android SDK (platform 34).

```bash
echo "sdk.dir=/path/ke/android-sdk" > local.properties
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Cara pakai
1. Instal APK di HP (`adb install app/build/outputs/apk/debug/app-debug.apk`).
2. Aktifkan **Opsi pengembang**: Pengaturan > Tentang ponsel > ketuk **Nomor build** 7 kali.
3. Opsi pengembang > **Pilih aplikasi lokasi palsu** > pilih **Fake GPS**.
4. Buka aplikasi, pilih lokasi, lalu tekan **Mulai Fake GPS**.

## Catatan
- Android menandai lokasi ini sebagai *mock* (`Location.isMock()`), jadi aplikasi yang memeriksanya (mis. aplikasi bank/absensi tertentu) bisa mendeteksinya.
- Gunakan untuk pengujian/pengembangan aplikasi. Memalsukan lokasi untuk menipu layanan (absensi, game, dll.) bisa melanggar ketentuan layanan tersebut.
