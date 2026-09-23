# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep line numbers for readable crash reports, hide the original source file name.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep model classes used for serialization / reflection.
-keep class com.darkvvpn.app.data.model.** { *; }

# Jetpack Compose
-dontwarn androidx.compose.**

# Kotlin metadata
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
