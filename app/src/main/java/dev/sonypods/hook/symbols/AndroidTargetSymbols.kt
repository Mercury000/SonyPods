package dev.sonypods.hook.symbols

import android.content.Context
import android.content.pm.PackageManager
import java.io.File

object AndroidTargetSymbols {
    fun create(
        context: Context?,
        packageName: String,
        classLoader: ClassLoader,
        log: (String, Throwable?) -> Unit,
    ): TargetSymbolResolver {
        val target = context?.let { targetArtifact(it, packageName) }
            ?: TargetArtifact(
                packageName = packageName,
                versionCode = 0L,
                files = listOf(
                    ArtifactFile(
                        path = "runtime:${classLoader.javaClass.name}",
                        length = 0L,
                        lastModified = 0L,
                    ),
                ),
            )
        val cache: SymbolCache = context?.let {
            FileSymbolCache(File(it.codeCacheDir, "sonypods-symbols"))
        } ?: MemorySymbolCache()
        val scanCoordinator: SymbolScanCoordinator = context?.let {
            // Keep locks outside code_cache so a cache cleanup cannot replace a locked inode.
            FileSymbolScanCoordinator(File(it.noBackupFilesDir, "sonypods-symbol-scan-locks"))
        } ?: ProcessSymbolScanCoordinator
        val sink = SymbolDiagnosticSink { event ->
            log("symbols bundle=${event.bundleId} phase=${event.phase} ${event.message}", null)
        }
        return TargetSymbolResolver(
            target = target,
            classLoader = classLoader,
            cache = cache,
            queryFactory = SymbolQueryFactory.dexKit(classLoader, sink),
            diagnostics = sink,
            scanCoordinator = scanCoordinator,
        )
    }

    @Suppress("DEPRECATION")
    private fun targetArtifact(context: Context, packageName: String): TargetArtifact {
        val info = context.packageManager.getApplicationInfo(
            packageName,
            PackageManager.ApplicationInfoFlags.of(0),
        )
        val packageInfo = context.packageManager.getPackageInfo(
            packageName,
            PackageManager.PackageInfoFlags.of(0),
        )
        val paths = buildList {
            add(info.sourceDir)
            info.splitSourceDirs?.let(::addAll)
        }.map(::File)
        return TargetArtifact.fromFiles(packageName, packageInfo.longVersionCode, paths)
    }
}
