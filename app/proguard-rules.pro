# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep all classes in our package
-keep class com.choptorch.app.** { *; }

# Keep the BroadcastReceiver
-keep public class * extends android.content.BroadcastReceiver

# Keep the Service
-keep public class * extends android.app.Service
