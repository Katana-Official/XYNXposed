-keep public class de.** { !private *; }
-keep class android.** { *; }
-keepclassmembers class * {
    native <methods>;
}
-keepclasseswithmembers,allowshrinking class * {
    native <methods>;
}