# OpenCall Relay — working rules

Repo: `E:\opencall-relay`. Package: `com.opencall.relay`. Kotlin, programmatic
views only — no Compose. AGP 8.1.0, Kotlin 1.9.0, compileSdk/targetSdk 36,
minSdk 26. Requires JDK 17 — set via `org.gradle.java.home` in
`gradle.properties` (pointed at the local Eclipse Adoptium JDK 17 install;
update that path if the JDK moves or you're on a different machine).

## Hard rules

- **Never run `adb`, `emulator`, `install`, or any device/emulator command.**
- **Never launch a background process, or poll for one.**
- Verification is compile + unit tests only (`.\gradlew.bat assembleDebug`,
  `.\gradlew.bat testDebugUnitTest`). Device testing is manual, by the user,
  on real Motorola hardware.
- Build with `.\gradlew.bat assembleDebug` — **never `assembleRelease`**
  unless the user explicitly asks for a release build (launcher PNG crunch
  has historically failed in AAPT for release builds; confirm this is
  resolved before assuming otherwise).
- Do exactly what is asked. No adjacent work, no cleanup, no refactoring,
  no "while I'm here."
- If a premise in a prompt is wrong, stop and say so — do not invent a
  change against code that isn't broken.
