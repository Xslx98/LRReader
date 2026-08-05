# R8 code shrinking with obfuscation enabled
# Tree-shaking + optimization + name mangling for security

# Flatten obfuscated classes into the root package (smaller string table);
# classes pinned by -keep rules below keep their full names.
-repackageclasses ''

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

# === A7Zip JNI (external library: a7zip_XJ:extract-lite) ===
-keep class com.hippo.a7zip.** { *; }
-keep class com.hippo.ehviewer.gallery.A7ZipArchive { *; }
-keep class com.hippo.ehviewer.gallery.A7ZipArchive$* { *; }

# === Native JNI entry points (libehviewer.so) ===
-keep class com.hippo.util.GifHandler { *; }

# Custom views/preferences referenced in XML need no manual keeps: AGP feeds
# R8 the AAPT2-generated rules (build/intermediates/aapt_proguard_file/...),
# which keep the constructors of every class named in layout/ and xml/
# resources. The old package-wide { <init>(...); } keeps additionally pinned
# provably dead classes into the release DEX. A class instantiated ONLY via
# Class.forName from code (no XML reference) would need an explicit keep here.

# === Settings fragments (instantiated via PreferenceActivity headers reflection) ===
-keep class com.hippo.ehviewer.ui.fragment.** { <init>(); }

# === Scene classes ===
# Scene NAMES and no-arg constructors must survive: intents/saved state carry
# class-name strings resolved via Class.forName (StageActivity, SolidScene),
# and FragmentManager re-instantiates fragments reflectively by name on state
# restore. Only actual SceneFragment subclasses need this — the old package
# keep pinned all ~550 scene-package classes (ViewModels, adapters, helpers)
# with original names into the release DEX.
-keep class * extends com.hippo.scene.SceneFragment { <init>(); }

# === LRRDownloadWorker: preserve volatile semantics for cancellation flag ===
-keepclassmembers class com.hippo.ehviewer.download.LRRDownloadWorker {
    volatile <fields>;
}

# === Suppress warnings for optional dependencies ===
-dontwarn com.google.firebase.**
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.**
-dontwarn net.sqlcipher.**
-dontwarn org.conscrypt.**
