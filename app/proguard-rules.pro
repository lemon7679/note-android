# JNI entry points (InkEngine bridge) are looked up by name.
-keepclasseswithmembernames class * { native <methods>; }
-keep class com.lemon7679.note.ink.InkNative { *; }
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class com.lemon7679.note.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.lemon7679.note.**$$serializer { *; }
-keepclassmembers class com.lemon7679.note.** { *** Companion; }
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
