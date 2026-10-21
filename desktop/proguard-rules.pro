# Shrink only (drops ~35 MB of unused Material icons); no obfuscation, no optimization passes
# that could break reflection-heavy libraries.
-dontobfuscate
-dontoptimize

# Our code: D-Bus exports interfaces reflectively; kotlinx.serialization uses generated serializers.
-keep class app.musikbox.** { *; }

# dbus-java: reflection + marshalling, transport discovered via ServiceLoader.
-keep class org.freedesktop.** { *; }
-keep class com.github.hypfvieh.** { *; }
-keep class * implements org.freedesktop.dbus.spi.transport.ITransportProvider { *; }
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod

# kotlinx.serialization
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keep,includedescriptorclasses class **$$serializer { *; }

# OkHttp / Okio / Coil / SLF4J optional bits
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.slf4j.**
-dontwarn org.jetbrains.annotations.**
-dontwarn kotlinx.coroutines.debug.**
-dontwarn com.sun.jna.**
-dontwarn jnr.**
-keep class org.slf4j.** { *; }
-keep class coil3.** { *; }

# kotlinx.coroutines finds Dispatchers.Main (Swing) via ServiceLoader.
-keep class kotlinx.coroutines.swing.SwingDispatcherFactory { *; }
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keep class * implements kotlinx.coroutines.internal.MainDispatcherFactory { *; }
