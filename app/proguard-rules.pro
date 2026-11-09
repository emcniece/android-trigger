# kotlinx.serialization ships its own consumer rules; keep model classes readable in stack traces.
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class com.emcniece.trigger.model.**$$serializer { *; }
