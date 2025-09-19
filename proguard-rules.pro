# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Keep all public classes and methods in the NEKit library
-keep public class com.github.nekit.** {
    public *;
}

# Keep Jackson annotations
-keepattributes *Annotation*
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**

# Keep BouncyCastle
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Keep JNA
-keep class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**

# Keep GeoIP2
-keep class com.maxmind.geoip2.** { *; }
-dontwarn com.maxmind.geoip2.**