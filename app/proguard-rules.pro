# ---------- BouncyCastle ----------
# 通过反射加载部分算法实现（Ed25519 / X25519 / Argon2），必须保留
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# ---------- org.json ----------
# 反射构造 JSONObject / JSONArray，字段名不可被混淆
-keep class org.json.** { *; }
-dontwarn org.json.**

# ---------- OkHttp ----------
-dontwarn okhttp3.internal.platform.**
-dontwarn okhttp3.internal.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# ---------- Room ----------
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# ---------- Kotlin 序列化 / 协程 ----------
-keepclassmembers class **$WhenMappings { <fields>; }
-keepclassmembers class kotlin.Metadata { public <methods>; }
-dontwarn kotlinx.coroutines.**

# ---------- 本应用 ----------
# 实体类字段被 Room 按列名反射读写，不能混淆
-keep class com.enchat.app.data.local.** { *; }
# 崩溃日志保留行号，便于定位
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
