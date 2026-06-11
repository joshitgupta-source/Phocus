# Phocus 🛡️

Phocus is a highly optimized, native Android digital wellbeing application designed to physically break the cycle of doomscrolling. Rather than relying on simple software timers, Phocus utilizes a custom physics engine and Android's Accessibility framework to enforce mindful app usage.

## 🚀 Core Features

* **The 60-Second Guard:** When opening a blocked app, users must hold their device perfectly still for 60 seconds to prove intentionality.
* **Hardware Physics Engine:** Utilizes the device's Linear Acceleration and Gravity sensors. The app detects and resets the timer if the user rests the phone on a table (`Z-Gravity` calculation) or uses a tripod (dead-still frame counting).
* **Immersive Mode Break (System Kick):** Intercepts deep-scroll sessions (e.g., YouTube Shorts, Instagram Reels) by triggering a global OS home action, shattering the hardware-accelerated video lock before enforcing the penalty screen.
* **10-Minute Penalty Window:** Employs absolute-time mathematics to detect "false closes" and apply a 90-second penalty if a user immediately relapses into a blocked app after their granted time expires.

## 🛠️ Technical Architecture

* **Language:** Kotlin
* **UI:** Android XML (ConstraintLayout, flat view hierarchies for 60fps sensor rendering)
* **Background Processing:** `AccessibilityService` combined with main-thread cached Handlers and raw Java `Timer` tasks to prevent OS throttling.
* **Data Persistence:** `SharedPreferences` with minimal memory churn.

## 🧠 Engineering Challenges Solved

1. **The "Doomscroll Bypass"** * *Problem:* Full-screen video apps use Immersive Mode, which throttles background timers and swallows standard `startActivity` lock screens.
   * *Solution:* Implemented an unstoppable background timer and commanded the Accessibility Service to fire `performGlobalAction(GLOBAL_ACTION_HOME)` to forcefully minimize the video player before drawing the lock screen.
2. **Sensor Haptic Spam**
   * *Problem:* `SENSOR_DELAY_GAME` fires ~50 times per second, causing massive memory spikes and vibration loops upon failure.
   * *Solution:* Implemented a strict 1000ms debounce penalty cooldown to preserve battery life and eliminate UI thread freezing.
3. **RecyclerView Scrolling Stutter**
   * *Problem:* Re-inflating complex Spinner adapters inside `onBindViewHolder` caused Garbage Collection drops.
   * *Solution:* Initialized the `ArrayAdapter` exclusively inside the `ViewHolder`'s `init` block, permanently caching it for buttery-smooth scrolling.

---
*Developed for Android SDK 24 - 36.*
