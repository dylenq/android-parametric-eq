# Minification (R8)

Minification is OFF in 0.1.0. The release APK is about 25 MB because Compose, Material and Kotlin
are not shrunk. It stays off because JNA and UniFFI look up classes and members by reflection, and
nothing could be tested with shrinking on a device.

## Starting-point keep rules (UNTESTED)

These are a record for later, not a verified configuration. Enable R8 only together with a device
test of the whole audio path.

```
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-keep class com.dylenq.eq.core.** { *; }
-dontwarn java.awt.*
```

`com.dylenq.eq.core` is the UniFFI package set in `core/uniffi.toml`.
