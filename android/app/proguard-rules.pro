# R8 rules for the release build.
#
# These are only consulted when `isMinifyEnabled` is turned on in
# `app/build.gradle.kts`, which it is not yet: the release APK is deliberately
# unshrunk until `docs/VERIFICATION.md` has been run on a phone, because a stripped
# serializer fails at the first decode rather than at build time. Keeping the rules
# here means the switch is one line when that day comes, instead of a debugging
# session on a release build.
#
# The generated rules of the app module still apply; this file adds to them.
-dontwarn org.jetbrains.annotations.**

# kotlinx.serialization: the generated `$$serializer` companions are reached through
# code the compiler emits, and R8's full mode cannot see the connection on its own.
# The library ships consumer rules; this is the belt over them.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer {
    *** descriptor;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.dsh.mobile.data.**$$serializer {
    *;
}
-keepclassmembers class dev.dsh.mobile.data.** {
    *** Companion;
}

# OkHttp and its platform bits: the app is minified only when these are quiet about
# the optional JVM integrations it never loads on Android.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Compose keeps its own rules, and no app-specific class is kept on purpose: a
# blanket keep of the UI package would defeat the point of shrinking.
