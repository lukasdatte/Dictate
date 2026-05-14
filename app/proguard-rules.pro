# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ════════════════════════════════════════════════════════════════════
# State-modular orchestrator — Action sealed hierarchy (Phase-B S-4)
# ════════════════════════════════════════════════════════════════════
#
# `DictateOrchestrator.collectLeaves` (and `DictateModuleRegistry`'s
# validation) walk `KClass.sealedSubclasses` over the Action hierarchy
# to build the `KClass → DictateModule` routing map at init time.
# ProGuard default behaviour strips sealed-hierarchy metadata in
# release builds — `sealedSubclasses` then returns an empty list,
# `moduleByLeafClass` is empty, and **every** non-failure dispatch
# becomes `DispatchOutcome.Unrouted` (silent-drop of all Actions in
# release). Bug class identical to S-3 F-1/F-2 but affects all 14
# sealed Action subtypes simultaneously.
#
# `allowobfuscation,allowshrinking` permits name shortening — only the
# class-reference path through `KClass` must stay intact. Subclasses
# may be shrunk on the assumption that they are referenced by the
# manually-listed `DictateModuleRegistry.all`.
#
# @see docs/decisions/0001-state-modular-orchestrator-pattern.md §"Failure modes"
# @see docs/plans/.../research/1-pipeline-service/1-pipeline-service.reviewed.md §4.3 ProGuard block

-keep,allowobfuscation,allowshrinking class net.devemperor.dictate.state.Action
-keep,allowobfuscation,allowshrinking class * extends net.devemperor.dictate.state.Action { *; }
-keepclassmembers class kotlin.reflect.** { *; }