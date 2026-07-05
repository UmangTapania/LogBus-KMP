# LogBus

A Kotlin Multiplatform **library** targeting Android and iOS. It has no UI — it is meant to be
imported by other projects.

* [/logbus](./logbus/src) is the library. It contains several source sets:
  - [commonMain](./logbus/src/commonMain/kotlin) is for code that’s common to all targets.
  - Other folders hold Kotlin code compiled only for the platform indicated by the folder name.
    For example, iOS-specific code (e.g. calls into Apple frameworks) goes in
    [iosMain](./logbus/src/iosMain/kotlin), and Android-specific code goes in
    [androidMain](./logbus/src/androidMain/kotlin). The `expect`/`actual` `Platform` type shows
    this pattern.

The library ships as an Android AAR (`com.android.kotlin.multiplatform.library`) and as an iOS
framework (`Shared`) produced by the `iosArm64` / `iosSimulatorArm64` targets.

### Building

- Android library: `./gradlew :logbus:assemble`
- iOS framework: `./gradlew :logbus:linkDebugFrameworkIosSimulatorArm64`

### Running tests

Use the run button in your IDE's editor gutter, or run tests using Gradle tasks:

- Android tests: `./gradlew :logbus:testAndroidHostTest`
- iOS tests: `./gradlew :logbus:iosSimulatorArm64Test`

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html).