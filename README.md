<div align="center" dir="rtl">

# 🚗 OBD2 — قارئ بيانات وأعطال المركبة
### Android ELM327 Diagnostic & Live Engine Data Reader

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Android" />
  <img src="https://img.shields.io/badge/Language-Java-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white" alt="Java" />
  <img src="https://img.shields.io/badge/UI-HTML5%20%2F%20JS-E34F26?style=for-the-badge&logo=html5&logoColor=white" alt="HTML5" />
  <img src="https://img.shields.io/badge/Protocol-ELM327%20SPP-0082FC?style=for-the-badge&logo=bluetooth&logoColor=white" alt="Bluetooth" />
  <img src="https://img.shields.io/badge/API-26%2B%20(Target%2036)-brightgreen?style=for-the-badge" alt="API Level" />
</p>

**تطبيق أندرويد أصيل مكتوب بلغة Java يتصل بجهاز فحص المركبات ELM327 عبر البلوتوث، لعرض بيانات المحرك الحية وفحص ومسح أكواد الأعطال في واجهة عربية سريعة (RTL) تعمل بالكامل دون إنترنت.**

<p align="center" dir="ltr">
  <i>A native Android app written in <b>Java</b> that connects to an <b>ELM327</b> Bluetooth adapter, streams live engine data, and reads or clears diagnostic trouble codes — inside a fast Arabic RTL interface built with local <code>HTML</code>.</i>
</p>

---

## 📸 لقطات الشاشة | Screenshots

<div align="center" dir="ltr">
  <img src="screenshots/dashboard.png" width="22%" alt="Dashboard" />
  <img src="screenshots/live-data.png" width="22%" alt="Live data" />
  <img src="screenshots/dtc.png" width="22%" alt="Trouble codes" />
  <img src="screenshots/settings.png" width="22%" alt="Settings" />
</div>

---

## ✨ المزايا الرئيسية | Features

📊 **لوحة قيادة مباشرة (Dashboard)**<br>
عرض لحظي لدورات المحرك (RPM)، السرعة، حرارة سائل التبريد، جهد البطارية، وحمل المحرك.

⚡ **بيانات حية (Live Data)**<br>
قراءة 8 حساسات أساسية مع أشرطة قياس تفاعلية وتحديث مستمر كل ثانية.

🛠️ **فحص أكواد الأعطال (DTCs)**<br>
قراءة أكواد الفئات (`P` / `C` / `B` / `U`) ومسحها من كمبيوتر السيارة، مع قاموس وصفي مختصر للأكواد الشائعة.

📡 **إدارة اتصال ذكية**<br>
استعراض أجهزة البلوتوث المقترنة، الاتصال والقطع السريع، ومراقبة حالة الاتصال لحظيًا.

🌙 **واجهة عربية متكاملة**<br>
تصميم عربي (RTL) بثيم داكن مريح للعين، يعمل محليًا بنسبة 100% دون الحاجة للإنترنت.

---

## ⚙️ المتطلبات والبيئة | Requirements

| المتطلب | المواصفات |
| :---: | :---: |
| **بيئة التطوير** | Android Studio حديثة (`AGP 9.2.1` ، `Gradle 9.4.1`) |
| **إصدار Java** | `JDK 17` أو أحدث |
| **حزمة التطوير (SDK)** | `minSdk 26` \| `targetSdk 36` \| `compileSdk 37` (Build Tools `36+`) |
| **العتاد المطلوب** | جهاز **ELM327** يدعم `Bluetooth Classic (SPP)` للتجربة الفعلية |

---

## 🚀 البناء والتشغيل | Build & Run

**1.** انسخ المستودع إلى جهازك ثم افتحه عبر **Android Studio**.<br>
**2.** انتظر انتهاء مزامنة ملفات **Gradle**، ثم شغّل التطبيق مباشرة على هاتف أندرويد حقيقي.

> **💡 ملاحظة حول مسار المشروع:**<br>
> مسار المشروع يحتوي حروفًا عربية، لذلك فُعّل `android.overridePathCheck=true` في `gradle.properties`.<br>
> إن نقلت المشروع إلى مسار إنجليزي يمكنك حذف السطر.

---

## 📂 بنية المشروع | Project Structure

أهم الملفات والمجلدات المكونة للمشروع:

</div>

<div dir="ltr" align="left">

```text
app/src/main/
├── AndroidManifest.xml
├── assets/obd/
│   └── index.html                 # الواجهة الكاملة (HTML / CSS / JS)
├── java/com/waleedsayed/obd2/
│   ├── MainActivity.java          # إعداد WebView + ربط الجسر مع الطبقة الأصلية
│   └── obd/
│       ├── ObdManager.java        # إدارة اتصال البلوتوث وأوامر ELM327
│       ├── ObdBridge.java         # الواجهة البرمجية (JS Interface) التي تستدعيها الصفحة
│       └── PidTable.java          # معادلات فك ترميز PIDs وأكواد الأعطال DTCs
└── res/                           # التخطيطات، الألوان، وأيقونة التطبيق
