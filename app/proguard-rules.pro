# Add project specific ProGuard rules here.
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# Gson
-keepattributes Signature
-keep class com.google.gson.** { *; }
-keep class com.openclaw.assistant.api.** { *; }
# UpdateChecker uses Gson to deserialize GitHub API response.
# Without this rule, R8 renames GithubRelease fields in release builds,
# causing Gson deserialization to fail silently and return null.
-keep class com.openclaw.assistant.utils.GithubRelease { *; }


# Google Error Prone Annotations
-dontwarn com.google.errorprone.annotations.**

# Tink (Security Crypto)
-dontwarn com.google.crypto.tink.**

# Vosk speech recognition
-keep class org.vosk.** { *; }
-dontwarn org.vosk.**

# JNA (used by Vosk) — JNA uses reflection to access the 'peer' field
# in com.sun.jna.Pointer and native method registration in com.sun.jna.Native.
# Without these rules, R8 strips/renames fields causing UnsatisfiedLinkError.
-keep class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**

# Markdown renderer (JetBrains markdown parser)
-keep class org.intellij.markdown.** { *; }
-dontwarn org.intellij.markdown.**

# dnsjava — uses Java SE / Sun APIs not available on Android
-dontwarn javax.naming.**
-dontwarn sun.net.spi.nameservice.**
-dontwarn lombok.**
-dontwarn org.slf4j.impl.**
-dontwarn org.xbill.DNS.spi.**
-keep class org.xbill.DNS.** { *; }

# BouncyCastle — required for BKS KeyStore provider registration.
# Without these rules, R8 strips the security provider registration code,
# causing java.security.KeyStoreException: BKS not found at runtime.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *; }
-keepclassmembers class * {
    @kotlinx.serialization.* <fields>;
}
-keep @kotlinx.serialization.Serializable class * { *; }
