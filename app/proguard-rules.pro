# R8 / 混淆规则（审计 A-02）。release 已开启 minifyEnabled true。
# Gson 靠反射读写字段名 + @SerializedName，模型层必须整体保留。
-keep class com.lateshow.app.model.** { *; }
-keep class com.lateshow.app.store.PrefStore$FavItem { *; }
-keep class com.lateshow.app.store.PrefStore$HistItem { *; }
-keep class com.lateshow.app.store.PrefStore$SavedSource { *; }
# 检查更新：GitHub Release JSON 已改为 JsonObject 显式取值（util/ReleaseReader），
# 不再依赖反射映射，这里无需 keep；保留下面这条注释是为了防止有人改回 POJO 反射。
# 本地缓存信封：字段名参与落盘 JSON，保证升级后旧缓存仍能读出来
-keep class com.lateshow.app.util.ApiCache$Wrapper { *; }
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses,EnclosingMethod

# 三方库自带 consumer rules；这里只消除 Android 平台上不存在的可选依赖告警
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.slf4j.**

# FileProvider / Activity / Fragment 由 AGP 依据 Manifest 自动 keep，无需手写
