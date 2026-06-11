# 📱 Phocus

![Version](https://img.shields.io/badge/version-1.6.5-blue.svg)
![Platform](https://img.shields.io/badge/platform-Android-3DDC84.svg)
![Language](https://img.shields.io/badge/language-Kotlin-7F52FF.svg)
![License](https://img.shields.io/badge/license-MIT-green.svg)

Phocus is a strict, uncompromising Android productivity application designed to break the doomscrolling cycle and enforce digital boundaries. 

---

## 🎯 Why I Made It

Preparing for highly competitive entrance exams like JEE Main requires absolute, unbreakable concentration. However, modern smartphones and social media algorithms are heavily engineered to steal that attention. I frequently found myself picking up my phone intending to take a quick break, only to look up an hour later having fallen down a doomscrolling rabbit hole. 

Standard "Digital Wellbeing" apps were too easily bypassed—they gently suggest you stop, but they don't actually force you to. I didn't need a gentle reminder; I needed a strict, intelligent system that actively intervenes the moment I open a distracting app. I built Phocus to be exactly that: an automated guardrail that forces me to put the phone down and get back to the books.

---

## ⚙️ How It Works (Under the Hood)

Phocus operates primarily through Android's **AccessibilityService API**. 

Instead of relying on easily bypassed usage-stats trackers, Phocus runs efficiently in the background as an Accessibility Service. It actively listens for `TYPE_WINDOW_STATE_CHANGED` events. The exact millisecond the Android OS registers that a targeted distracting app (like Instagram or YouTube) has been pushed to the foreground, Phocus intercepts the action. 

It immediately overlays its own blocking screen over the target application, preventing any interaction with the distracting app's UI. This makes bypassing the block incredibly difficult, ensuring your focus timer is strictly enforced.

---

## ✨ Core Features

* **Instant Accessibility Blocking:** Detects and intercepts distracting applications with zero-latency overlay screens.
* **The 60-Second Guard:** Enforces strict limits before allowing you back into targeted applications.
* **Modern Material UI:** Built with dynamic theming to seamlessly match your system's aesthetic.
* **Automated Versioning:** Utilizes modern Gradle (AGP 9+) automated naming scripts to ensure consistent APK release tracking.
* **Enterprise Stability:** Integrated directly with Firebase Crashlytics for real-time error tracking and runtime stability monitoring.

---

## 🛠️ Tech Stack

* **Language:** Kotlin
* **Architecture:** MVVM (Model-View-ViewModel)
* **Core APIs:** Android AccessibilityService API
* **Backend/Analytics:** Google Firebase Crashlytics
* **Build System:** Kotlin DSL (`build.gradle.kts`) with custom AGP Base archives generation

---

## 🚀 Installation & Usage

1. Navigate to the [Releases](../../releases) section of this repository.
2. Download the latest `Phocus_vX.X.X-release.apk` file to your Android device.
3. Open the file to install (you may need to allow "Install from Unknown Sources" in your browser settings).
4. Launch the app and grant the requested **Accessibility Permissions** in your Android Settings so the blocker can function.
5. Select the apps you want to lock down and start your focus session.

---

## 🤝 Contributing

This project is currently maintained as a personal productivity tool, but pull requests and feature suggestions are welcome. 

1. Fork the Project
2. Create your Feature Branch (`git checkout -b feature/AmazingFeature`)
3. Commit your Changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the Branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request

---

## ⚖️ License

Distributed under the MIT License. See `LICENSE` for more information.
