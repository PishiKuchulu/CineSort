# CineSort release keep rules: kotlinx.serialization, Room, Hilt (spec section 14).

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.cinesort.**$$serializer {
    *;
}
-keepclassmembers class com.cinesort.** {
    *** Companion;
}
-keepclasseswithmembers class com.cinesort.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Room
-keep class * extends androidx.room.RoomDatabase {
    <init>();
}
-keep class androidx.room.** {
    *;
}
-dontwarn androidx.room.paging.**

# Hilt / Dagger
-keep class dagger.** {
    *;
}
-keep class javax.inject.** {
    *;
}
-keep class * implements dagger.internal.Factory {
    *;
}
-keep class * implements dagger.internal.Provider {
    *;
}
-dontwarn dagger.**

# Keep Hilt-generated entry points reachable.
-keep class com.cinesort.app.Hilt_CineSortApp {
    *;
}
-keep class com.cinesort.app.MainActivity {
    *;
}

# Retrofit / OkHttp (metadata providers live in core:data; keep service interfaces).
-keepattributes Signature, Exceptions
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-dontwarn retrofit2.**
-dontwarn okhttp3.**
-dontwarn okio.**
