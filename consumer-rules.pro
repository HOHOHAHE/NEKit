# Consumer ProGuard rules for NEKit library
# These rules will be automatically applied to projects that use this library

# Keep all public classes and methods in the NEKit library
-keep public class com.github.nekit.** {
    public *;
}

# Keep Jackson annotations and classes
-keepattributes *Annotation*
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**

# Keep BouncyCastle classes
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Keep JNA classes
-keep class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**

# Keep GeoIP2 classes
-keep class com.maxmind.geoip2.** { *; }
-dontwarn com.maxmind.geoip2.**

# Keep Kotlin coroutines
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# Keep SLF4J
-keep class org.slf4j.** { *; }
-dontwarn org.slf4j.**