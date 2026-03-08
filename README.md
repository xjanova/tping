# Tping — Android Automation App

**Auto-typing, workflow recording & playback** สำหรับ Android

ติดตั้งบน Android 8.0+ — บันทึกขั้นตอนการใช้งานแอพ แล้วเล่นซ้ำอัตโนมัติพร้อมข้อมูลที่กำหนดเอง

## Features

### Core
- **Workflow Recording** — บันทึกขั้นตอนผ่าน Accessibility Service (click, long-click, text input, scroll, back)
- **Smart Playback** — เล่นซ้ำอัตโนมัติ วนลูปได้ สลับข้อมูลได้
- **Data Profiles** — จัดกลุ่มข้อมูลเป็นโปรไฟล์ ผูกกับ field ใน workflow
- **Game Mode** — บันทึกพิกัดด้วย crosshair overlay สำหรับแอพที่ไม่มี accessibility node

### Smart Element Detection (6 layers)
1. Resource ID (แม่นที่สุด)
2. Text + Class + Description
3. Content Description
4. Bounds / Coordinates
5. Class name matching
6. Coordinate fallback (Game Mode)

### Advanced
- **Puzzle CAPTCHA Solver** — แก้ puzzle captcha อัตโนมัติ ใช้ OpenCV (dark pixel, edge contour, contrast anomaly)
- **Cloud Sync** — ซิงค์ workflow + data profile ผ่าน Xman Studio Cloud
- **Auto-Update** — เช็คเวอร์ชันใหม่จาก GitHub Releases, ดาวน์โหลด APK อัตโนมัติ
- **QR License Activation** — สแกน QR เพื่อ activate license key
- **HWID Auto-Check** — ติดตั้งใหม่บนเครื่องเดิม license activate อัตโนมัติ

## Tech Stack

| Layer | Technology |
|-------|-----------|
| UI | Jetpack Compose + Material 3 |
| Navigation | Compose Navigation |
| Database | Room (SQLite) |
| Networking | OkHttp 4 + Gson |
| Vision | OpenCV 4.9, ML Kit Barcode |
| Camera | CameraX 1.4 |
| Security | EncryptedSharedPreferences |
| Async | Kotlin Coroutines + StateFlow |
| Build | Gradle KTS + KSP |

## Requirements

- Android 8.0+ (API 26)
- Accessibility Service permission
- Overlay (Draw Over Apps) permission
- Internet (for license & cloud sync)

## Build

```bash
# Debug
./gradlew assembleDebug

# Release (requires signing config)
./gradlew assembleRelease
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

## Architecture

```
com.xjanova.tping/
├── data/
│   ├── license/       # License verification + anti-cheat
│   ├── cloud/         # Cloud auth + sync
│   ├── update/        # GitHub release update checker
│   ├── diagnostic/    # Bug reporting
│   └── entity/        # Room entities (Workflow, DataProfile, etc.)
├── recorder/          # Action recording + playback engine
├── puzzle/            # CAPTCHA solver (OpenCV)
├── overlay/           # Floating overlay service + UI
├── service/           # Accessibility service
├── ui/
│   ├── screens/       # Compose screens (Home, Workflows, Data, Play, Cloud)
│   ├── components/    # Reusable UI components
│   └── viewmodel/     # MainViewModel
└── util/              # Permissions, helpers
```

## License System

- Server-verified license (xman4289.com API)
- 7-day free trial ต่อเครื่อง
- Local 24h fallback เมื่อไม่มี internet
- Anti-cheat: clock tamper detection, server time drift, hardware fingerprint
- Types: `monthly`, `yearly`, `lifetime`

## Download

ดาวน์โหลด APK ล่าสุดได้ที่ [xman4289.com/tping/download](https://xman4289.com/tping/download)
