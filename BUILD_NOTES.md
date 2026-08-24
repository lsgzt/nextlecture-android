# Fixed Build Notes

The Android project in this archive was compiled successfully with the Gradle wrapper using:

```bash
./gradlew assembleDebug assembleRelease
```

The build was verified with OpenJDK 21, Android SDK platform 34, and Android build-tools 34.0.0. Set `sdk.dir` in `local.properties` or define `ANDROID_HOME`/`ANDROID_SDK_ROOT` for the local Android SDK installation.

The fixes included in this version are:

- Corrected unmatched parentheses and braces in `ProfileScreen.kt`.
- Updated shared motion helpers in `Motion.kt` to use `FiniteAnimationSpec`.
- Added missing Compose property-delegate and animation imports.
- Corrected the reduced-motion composition-local expression and fade tween arguments.
- Added the missing `IntOffset` import in `OnboardingScreen.kt`.
- Increased the Kotlin compiler daemon memory setting in `gradle.properties` to avoid compiler out-of-memory failure in the build environment.

Generated build outputs and machine-specific `local.properties` are intentionally excluded from this source archive. The compiled APKs were delivered separately.
