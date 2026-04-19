# Keep generated protobuf-lite classes. Javalite strips most reflection but
# still relies on class metadata for message parsing.
-keep class org.soulstone.mooncompanion.proto.** { *; }
-keep class com.google.protobuf.** { *; }
