# gomobile bindings — the JNI layer resolves these by name, so nothing here may be
# renamed or removed. The package names come from `gomobile bind` over
# ./contrib/mobile, ./src/config and ./contrib/awgmobile (see Makefile).
-keep class go.** { *; }
-keep class mobile.** { *; }
-keep class awgmobile.** { *; }
-keep class config.** { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
