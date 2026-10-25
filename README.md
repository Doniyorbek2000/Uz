# UzKey — Özbek klaviaturasi (Android + iOS)

Yangi özbek alifbosi (2027), joriy lotin alifbosi, kirill, rus va ingliz tillarida yozish uçun klaviatura.
Android (Kotlin) va iOS (Swift) uçun alohida native ilova, tugmalar joylaşuvi va luğatlar `shared/` papkasida umumiy.

## Imkoniyatlar

| | |
|---|---|
| **Tillar** | Özbekça (yangi alifbo: ö ğ ş ç ʼ), Oʻzbekcha (oʻ gʻ), Ўзбекча, Русский, English — 🌐 tugmasi bilan almaştiriladi |
| **Söz takliflari** | Davomini taklif qiliş, xatoni tuzatiş (kiotb → kitob), keyingi sözni bashorat qiliş |
| **Örganiş** | Siz yozgan söz va ismlarni eslab qoladi (faqat telefonda) |
| **Eski yozuvni qabul qiliş** | Yangi alifboda `yaxsh` yozsangiz `yaxşi` taklif qilinadi; `o'` → `ö`, `g'` → `ğ` avtomatik |
| **Alifbo ögirgiç (⇄)** | Matnni kirill ↔ lotin ↔ yangi lotin alifbosiga bir tugma bilan ögiradi |
| **Tugmalar** | Bosib turganda qöşimça harflar (o → ö, oʻ, ó…), raqam belgilari, Caps Lock (Shift ×2), böş joyni surib kursorni siljitiş |
| **Qulayliklar** | Ikki böş joy → nuqta, avtomatik boş harf, emoji paneli (700+), raqam/telefon maydonlari uçun raqamli klaviatura |
| **Dizayn** | 5 ta mavzu (Tizim, Yoruğ, Qoronği, Qora AMOLED, Moviy), 3 xil tugma balandligi |
| **Maxfiylik** | Android ilovada internet ruxsati umuman yöq; iOS da hech narsa tarmoqqa yuborilmaydi |

## Örnatiş

### Android
1. GitHub → **Actions** → oxirgi muvaffaqiyatli **Android** işga tuşirilişi → `uzkey-android` artefaktini yuklab oling.
2. `app-release.apk` ni telefonga örnating (nomaʼlum manbalardan örnatişga ruxsat bering).
3. UzKey ilovasini oçing → **Yoqiş** → **Tanlaş**.

Özingiz yiğiş uçun (JDK 17 va Android SDK kerak):
```bash
cd android
./gradlew assembleRelease        # APK: app/build/outputs/apk/release/
./gradlew testDebugUnitTest      # unit testlar
```
Haqiqiy reliz kaliti bilan imzolaş uçun `UZKEY_KEYSTORE_PATH`, `UZKEY_KEYSTORE_PASSWORD`, `UZKEY_KEY_ALIAS`,
`UZKEY_KEY_PASSWORD` muhit özgaruvçilarini (CI da — xuddi şu nomdagi secret'lar va `UZKEY_KEYSTORE_BASE64`) bering.
Aks holda reliz APK debug kaliti bilan imzolanadi.

### iOS
iPhone ga örnatiş uçun Apple hisobi bilan imzolaş şart:
```bash
brew install xcodegen
cd ios
xcodegen generate
open UzKey.xcodeproj
```
1. Xcode da **UzKey** va **UzKeyKeyboard** targetlari uçun **Signing & Capabilities** → Team ni tanlang.
   Bundle ID lar (`uz.uzkey.app`, `uz.uzkey.app.keyboard`) va App Group (`group.uz.uzkey.keyboard`) ni özingiznikiga
   almaştiring (App Group ni `Shared/KeyboardSettings.swift` da ham yangilang).
2. iPhone ni ulab **Run** bosing.
3. Sozlamalar → UzKey → Klaviaturalar → **UzKey** ni yoqing (tebraniş uçun «Töliq ruxsat» ni ham yoqing).

CI imzolanmagan `UzKey-unsigned.ipa` ni ham yasaydi — uni Sideloadly/AltStore orqali özingizning Apple ID ingiz bilan imzolab örnatiş mumkin.

## Tuzilma

```
shared/            umumiy maʼlumotlar (ikkala ilova ham şundan foydalanadi)
  layouts/*.json   tugmalar joylaşuvi (til, belgilar, raqamlar)
  dictionaries/    söz chastotalari luğatlari
  emoji.json       emoji toifalari
tools/             luğatlarni yasaş skripti va özbekça söz bazasi
android/           Kotlin: InputMethodService + Jetpack Compose sozlamalar
ios/               Swift: Keyboard Extension + SwiftUI sozlamalar (XcodeGen)
```

Luğatlarni qayta yasaş:
```bash
pip install wordfreq
python3 tools/build_dictionaries.py
```
Özbekça sözlar `tools/uz_seed.txt` da joriy lotin yozuvida saqlanadi; skript ulardan yangi alifbo va kirill luğatlarini avtomatik yasaydi.

Litsenziyalar uçun [NOTICE.md](NOTICE.md) ga qarang.
