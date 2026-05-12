# Keep gomobile generated classes
-keep class go.** { *; }
-keep class yggdrasil.** { *; }
-keep class amneziawg.** { *; }

# Keep kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
