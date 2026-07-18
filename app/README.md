# Compose app

The shared Compose app consumes the same canonical Android-compatible XML catalog in `resources/i18n` as the JVM static generator. The build copies it into Compose resources and generates an explicit-language lookup from the same files, allowing an in-app language change without changing the OS locale.

At first launch the UI follows the supported OS language and otherwise falls back to English. A saved user choice takes precedence. UI language does not force query language: each raw query is detected and sent to the matching local analyzer/index.

```sh
./gradlew :app:shared:testAndroidHostTest
./gradlew :app:androidApp:assembleDebug
./gradlew :app:shared:compileKotlinIosSimulatorArm64
```
