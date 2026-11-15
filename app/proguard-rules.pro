# LingoTrail ProGuard rules.
#
# The app uses no reflection and only Android platform JSON APIs, so the default
# AGP + AndroidX consumer rules are sufficient. Keep this file for future needs.

# Keep line numbers for readable crash reports without leaking source.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
