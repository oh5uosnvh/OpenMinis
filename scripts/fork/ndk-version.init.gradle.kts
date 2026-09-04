// [FORK] Pin the NDK the Android build uses, WITHOUT editing build.gradle.kts.
//
// The app module declares an externalNativeBuild (pty_bridge, the crash
// handler, jieba_jni) but does not set `ndkVersion`, so AGP falls back to its
// own default version and fails the build if that exact version is not
// installed. Pinning it in build.gradle.kts would work but puts the fork's edit
// in a file upstream touches on nearly every release — a permanent merge
// conflict for one line of CI plumbing.
//
// An init script is applied with `--init-script` from OUTSIDE the project, so
// this file is invisible to git's view of the upstream tree: zero conflict
// surface (docs/FORK.md §3 规则 1).
//
// Usage:
//   FORK_NDK_VERSION=28.1.13356709 \
//     ./gradlew --init-script ../../scripts/fork/ndk-version.init.gradle.kts :app:assembleRelease
//
// No FORK_NDK_VERSION → no-op, so a local build behaves exactly like upstream.

val forkNdkVersion: String? = System.getenv("FORK_NDK_VERSION")?.takeIf { it.isNotBlank() }

if (forkNdkVersion != null) {
    gradle.beforeProject {
        plugins.withId("com.android.application") {
            extensions.findByName("android")?.let { ext ->
                // Reflection rather than a typed cast: this init script is
                // compiled without AGP on its classpath, so
                // `com.android.build.api.dsl.ApplicationExtension` is not a
                // resolvable type here.
                runCatching {
                    ext.javaClass.methods
                        .first { it.name == "setNdkVersion" && it.parameterCount == 1 }
                        .invoke(ext, forkNdkVersion)
                    logger.lifecycle("[fork] ndkVersion pinned to $forkNdkVersion for $path")
                }.onFailure {
                    logger.warn("[fork] could not pin ndkVersion: ${it.message}")
                }
            }
        }
    }
}
