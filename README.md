# WhatsApp Contact Photo Sync — Android 16 / Samsung

## Ce face

Aplicația are un singur flux manual:

1. Permiți accesul la contacte.
2. Activezi serviciul de Accessibility.
3. Apeși **Sincronizează pozele WhatsApp** (opțional cu o limită de contacte).
4. Aplicația parcurge contactele și încearcă să deschidă contactul în WhatsApp.
5. Dă click pe avatar ca să deschidă poza mare de profil, apoi face captură de ecran.
6. Salvează fotografia detectată în contactul Android.
7. Poți opri oricând din butonul **Oprește sincronizarea**.

Nu există WorkManager, alarmă, receiver de boot sau sincronizare periodică. Nimic nu pornește singur.

## Important

WhatsApp nu oferă unei aplicații Android obișnuite un API oficial pentru a cere fotografia de profil a unui contact. Proiectul folosește AccessibilityService pentru interacțiunea cu interfața WhatsApp și API-ul Android de screenshot al serviciului.

Asta înseamnă că proiectul este dependent de interfața WhatsApp. Dacă WhatsApp schimbă poziția avatarului sau structura ecranului, metodele `findAvatarNode()` / `extractAvatar()` din `WhatsAppAccessibilityService.kt` trebuie ajustate.

Android 11+ permite unui AccessibilityService să facă screenshot dacă serviciul declară capabilitatea `canTakeScreenshot`; API-ul este disponibil din API 30.

Aplicația scrie un jurnal detaliat (deschis contact, avatar găsit/negăsit, poză salvată/eșuată) direct în cardul de jos al ecranului, ca să poți urmări exact ce se întâmplă fără să ai nevoie de ADB.

## Build

### Din Android Studio
Deschide folderul în Android Studio și lasă Gradle să descarce dependențele.

### Din linia de comandă (fără Android Studio)
Ai nevoie de:
- JDK 17
- Android SDK cu `platforms;android-35` și `build-tools;35.0.0` instalate (`sdkmanager`)
- Un fișier `local.properties` la rădăcina proiectului cu:
  ```
  sdk.dir=C:\\Android\\Sdk
  ```
  (înlocuiește cu calea reală către SDK-ul tău)

Apoi:
```
./gradlew.bat assembleDebug
```
APK-ul rezultat apare la `app/build/outputs/apk/debug/app-debug.apk`.

- compileSdk 35
- minSdk 30
- targetSdk 35
- Kotlin 2.0.21
- Android Gradle Plugin 8.7.3

## Instalare pe telefon

Copiază `app-debug.apk` pe telefon și instalează-l (permite "surse necunoscute" dacă ți se cere).

Sau, cu telefonul conectat prin USB (debugging activat) la calculatorul unde ai compilat:
```
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Prima pornire

Pe Samsung, la prima instalare Android blochează activarea accesibilității pentru aplicații instalate prin sideload ("Restricted setting"). Deblocare:

Settings → Apps → WhatsApp Contact Photo Sync → meniul ⋮ → **Allow restricted settings**

Apoi:

Settings → Accessibility → Installed apps → WhatsApp Contact Photo Sync → ON

În aplicație:
- acordă Contacts permission;
- (opțional) pune un număr în "Câte contacte" pentru un test rapid;
- apasă sincronizarea.

## Depanare cu ADB (opțional)

Dacă ai telefonul conectat fizic prin USB la calculatorul pe care rulează Claude Code / terminalul:
```
adb devices
adb logcat -s WaSync
```
Toate mesajele din jurnal (tag `WaSync`) apar și în logcat, nu doar în aplicație.

## Observație

Algoritmul de găsire a avatarului (`findAvatarNode`) încearcă întâi câteva id-uri cunoscute WhatsApp, apoi cade pe o euristică (cea mai mare imagine aproape pătrată de pe ecran). Crop-ul din `extractAvatar()` presupune că poza mare de profil e centrată pe ecran. Ambele sunt dependente de versiunea WhatsApp și de dispozitiv — dacă nu prind poza corect, jurnalul din aplicație arată exact unde eșuează (avatar negăsit, crop invalid, excepție la salvare), ca punct de plecare pentru ajustare.
