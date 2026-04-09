# ProGuard / R8 rules for Pocket IRC.
#
# The default rules in proguard-android-optimize.txt cover most of AndroidX
# and Kotlin. Add app-specific keep rules below as needed.

# Keep class and method names readable in release-build stack traces. R8 still
# strips unused code (smaller APK) but doesn't rename what's left, so crash
# reports submitted by users land in the issue tracker as readable traces
# without needing a separate mapping.txt deobfuscation step.
-dontobfuscate

# kotlinx.serialization: keep generated $serializer companions on @Serializable
# classes so reflection-free serializers continue to work after R8 shrinking.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class **$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer Companion;
}

# Kitteh IRC Client Library uses MBassador (net.engio.mbassy) which discovers
# @Handler-annotated methods reflectively. Keep our listener classes intact.
-keep class com.pocketirc.app.irc.KitchenSinkListener { *; }
-keepclassmembers class com.pocketirc.app.** {
    @net.engio.mbassy.listener.Handler <methods>;
}

# Netty (transitive via KICL) uses reflective access for some channel handlers.
-dontwarn io.netty.**
-keep class io.netty.** { *; }
