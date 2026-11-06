# Merged into :app's R8 run; this module does not minify itself.

# === Scene classes ===
# Scene NAMES and no-arg constructors must survive: intents/saved state carry
# class-name strings resolved via Class.forName (StageActivity, SolidScene),
# and FragmentManager re-instantiates fragments reflectively by name on state
# restore. Only actual SceneFragment subclasses need this: the old package
# keep pinned all ~550 scene-package classes (ViewModels, adapters, helpers)
# with original names into the release DEX.
-keep class * extends com.lanraragi.framework.scene.SceneFragment { <init>(); }
