# BouncyCastle 在 Android 上通过反射加载部分算法实现，需保留
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
# OkHttp
-dontwarn okhttp3.internal.platform.**
# Room 生成类
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**
