# Liaisons UniFFI : JNA lit les structures et rappels par réflexion.
-keep class io.github.shweepsi.heystream.core.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-dontwarn java.awt.**
