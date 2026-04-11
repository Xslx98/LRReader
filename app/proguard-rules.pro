# R8 code shrinking with obfuscation enabled
# Tree-shaking + optimization + name mangling for security

# === Strip debug/verbose logs in release (security: prevent accidental data leak) ===
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
}

# === JNI / Native ===
-keepclasseswithmembernames class * {
    native <methods>;
}

# === Parcelable ===
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# === Serializable ===
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# === Room DAO entities ===
-keep class com.hippo.ehviewer.dao.** { *; }


# === OkHttp ===
-dontwarn okhttp3.**
-dontwarn okio.**

# === LANraragi data models (used by kotlinx-serialization) ===
-keep class com.lanraragi.reader.client.api.data.** { *; }
-keep class com.hippo.ehviewer.client.data.** { *; }

# === A7Zip JNI (external library: a7zip_XJ:extract-lite) ===
-keep class com.hippo.a7zip.** { *; }
-keep class com.hippo.ehviewer.gallery.A7ZipArchive { *; }
-keep class com.hippo.ehviewer.gallery.A7ZipArchive$* { *; }

# === Image native decoder (libimage.so via ReLinker) ===
-keep class com.hippo.lib.image.Image1 { *; }
-keep class com.hippo.lib.image.Image1$* { *; }

# === ReLinker native library loader ===
-keep class com.getkeepsafe.relinker.** { *; }

# === Native JNI entry points (libehviewer.so) ===
-keep class com.hippo.Native { *; }
-keep class com.hippo.util.GifHandler { *; }

# === Custom views referenced in XML ===
-keep class com.hippo.ehviewer.widget.** { *; }
-keep class com.hippo.ehviewer.preference.** { *; }
-keep class com.hippo.widget.** { *; }
-keep class com.hippo.preference.** { *; }

# === Settings fragments (instantiated via PreferenceActivity headers reflection) ===
-keep class com.hippo.ehviewer.ui.fragment.** { *; }

# === Scene classes (instantiated via StageLayout reflection) ===
-keep class com.hippo.ehviewer.ui.scene.** { *; }

# === LRRDownloadWorker: preserve volatile semantics for cancellation flag ===
-keepclassmembers class com.hippo.ehviewer.download.LRRDownloadWorker {
    volatile <fields>;
}

# === UCrop image cropper ===
-keep class com.yalantis.ucrop.** { *; }
-dontwarn com.yalantis.ucrop.**

# === Suppress warnings for optional dependencies ===
-dontwarn com.google.firebase.**
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.**
-dontwarn net.sqlcipher.**
-dontwarn org.conscrypt.**
