# R8 for release builds: shrink unused code and resources, optimise, but keep names.
#
# Handoff is open source, so obfuscation hides nothing; readable names keep crash reports and
# diagnostics useful and rule out breakage from renamed classes (Koin, enums stored by name).
-dontobfuscate

# Line numbers in stack traces shared from Diagnostics.
-keepattributes SourceFile,LineNumberTable

# kotlinx.serialization, Room, Koin, Compose, ZXing and the Google ads/consent SDKs ship their own
# consumer rules. The hidden Bluetooth methods are looked up by name on Android framework classes
# (BluetoothA2dp, BluetoothHeadset...), which are never part of the app and never shrunk.
