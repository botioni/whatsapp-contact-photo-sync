# WhatsApp Contact Photo Sync — Android 16 / Samsung

## Ce face

Aplicația are un singur flux manual:

1. Permiți accesul la contacte.
2. Activezi serviciul de Accessibility.
3. Apeși **Sincronizează pozele WhatsApp**.
4. Aplicația parcurge contactele și încearcă să deschidă contactul în WhatsApp.
5. Captură ecran a paginii de profil WhatsApp.
6. Salvează fotografia detectată în contactul Android.

Nu există WorkManager, alarmă, receiver de boot sau sincronizare periodică. Nimic nu pornește singur.

## Important

WhatsApp nu oferă unei aplicații Android obișnuite un API oficial pentru a cere fotografia de profil a unui contact. Proiectul folosește AccessibilityService pentru interacțiunea cu interfața WhatsApp și API-ul Android de screenshot al serviciului.

Asta înseamnă că proiectul este dependent de interfața WhatsApp. Dacă WhatsApp schimbă poziția avatarului sau structura ecranului, metoda `extractAvatar()` din `WhatsAppAccessibilityService.kt` trebuie ajustată.

Android 11+ permite unui AccessibilityService să facă screenshot dacă serviciul declară capabilitatea `canTakeScreenshot`; API-ul este disponibil din API 30.

## Build

Deschide folderul în Android Studio și lasă Gradle să descarce dependențele.

- compileSdk 35
- minSdk 30
- targetSdk 35
- Kotlin 2.0.21
- Android Gradle Plugin 8.7.3

## Prima pornire

Pe Samsung:

Settings → Accessibility → Installed apps → WhatsApp Contact Photo Sync → ON

Apoi în aplicație:
- acordă Contacts permission;
- apasă sincronizarea.

## Observație

În această versiune, algoritmul de crop este intenționat simplu pentru a avea un proiect complet și ușor de modificat. Pentru un Samsung/WhatsApp concret, trebuie testată poziția exactă a avatarului și, ideal, înlocuit crop-ul fix cu o detectare mai robustă.
