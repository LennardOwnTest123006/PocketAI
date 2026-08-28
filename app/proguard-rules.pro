# ---------------------------------------------------------------------------
# JNI surface
#
# The native library resolves these by name at runtime, so R8 must not rename
# or strip them. Getting this wrong produces a NoSuchMethodError only on a
# release build, so the rules are deliberately explicit.
# ---------------------------------------------------------------------------
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

-keep class com.pocketai.app.llm.LlamaNative { *; }

# Callback interfaces invoked from C++ through GetMethodID.
-keep interface com.pocketai.app.llm.TokenListener { *; }
-keep interface com.pocketai.app.llm.LoadProgressListener { *; }
-keepclassmembers class * implements com.pocketai.app.llm.TokenListener {
    public void onToken(java.lang.String);
}
-keepclassmembers class * implements com.pocketai.app.llm.LoadProgressListener {
    public void onProgress(float);
}

# ---------------------------------------------------------------------------
# Serialization
# ---------------------------------------------------------------------------
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations
-dontnote kotlinx.serialization.**

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.pocketai.app.**$$serializer { *; }
-keepclassmembers class com.pocketai.app.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# ---------------------------------------------------------------------------
# Third-party
# ---------------------------------------------------------------------------
-dontwarn org.jsoup.**
-keep class org.jsoup.** { *; }

# OkHttp ships optional integrations that are absent at runtime.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Room generates implementations reflectively referenced by the runtime.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**

# Coroutines debug agent is not present in release builds.
-dontwarn kotlinx.coroutines.debug.**
