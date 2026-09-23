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

# The tunnel core. gomobile reaches back into these classes by reflection, so
# renaming any of them breaks the bridge between Kotlin and Go.
# The AAR ships its own consumer rules; these are the belt to that braces,
# because a release build that silently loses the core is a release build that
# connects and passes nothing.
-keep class libv2ray.** { *; }
-keep class go.** { *; }
-keepclassmembers class * implements libv2ray.CoreCallbackHandler { *; }
-keep class com.darkvvpn.app.xray.** { *; }

# Jetpack Compose
-dontwarn androidx.compose.**

# Kotlin metadata
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
