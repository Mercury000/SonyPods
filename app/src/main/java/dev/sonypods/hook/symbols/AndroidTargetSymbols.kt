package dev.sonypods.hook.symbols

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import java.io.File

object AndroidTargetSymbols {
    fun create(
        context: Context?,
        packageName: String,
        classLoader: ClassLoader,
        log: (String, Throwable?) -> Unit,
    ): TargetSymbolResolver {
        if (context == null) {
            return build(
                target = runtimeTarget(packageName, classLoader),
                cache = MemorySymbolCache(),
                scanCoordinator = ProcessSymbolScanCoordinator,
                classLoader = classLoader,
                log = log,
            )
        }
        return build(
            target = targetArtifact(context, packageName),
            cache = FileSymbolCache(File(context.codeCacheDir, "sonypods-symbols")) { message, error ->
                log(message, error)
            },
            // Keep locks outside code_cache so a cache cleanup cannot replace a locked inode.
            scanCoordinator = FileSymbolScanCoordinator(File(context.noBackupFilesDir, "sonypods-symbol-scan-locks")),
            classLoader = classLoader,
            log = log,
        )
    }

    /**
     * Pre-warm resolver built before the Application exists. The artifact identity must equal
     * the runtime one, so the caller supplies the package version code obtained through the
     * system PackageManager. Reading the cross-process cache here keeps a warm start scan-free.
     */
    internal fun createForPrewarm(
        packageName: String,
        applicationInfo: ApplicationInfo,
        versionCode: Long,
        classLoader: ClassLoader,
        log: (String, Throwable?) -> Unit,
    ): TargetSymbolResolver {
        val dataDir = File(applicationInfo.dataDir)
        return build(
            target = targetArtifact(packageName, applicationInfo, versionCode),
            cache = FileSymbolCache(File(dataDir, "code_cache/sonypods-symbols")) { message, error ->
                log(message, error)
            },
            scanCoordinator = FileSymbolScanCoordinator(File(dataDir, "no_backup/sonypods-symbol-scan-locks")),
            classLoader = classLoader,
            log = log,
        )
    }

    /**
     * Swaps a resolver's memory cache for the real artifact store once the application
     * Context exists. The resolver instance survives, so already attached hooks keep it.
     */
    internal fun attachPersistentCache(
        resolver: TargetSymbolResolver,
        context: Context,
        packageName: String,
        log: (String, Throwable?) -> Unit,
    ) {
        resolver.attachPersistentCache(
            target = targetArtifact(context, packageName),
            cache = FileSymbolCache(File(context.codeCacheDir, "sonypods-symbols")) { message, error ->
                log(message, error)
            },
        )
    }

    private fun build(
        target: TargetArtifact,
        cache: SymbolCache,
        scanCoordinator: SymbolScanCoordinator,
        classLoader: ClassLoader,
        log: (String, Throwable?) -> Unit,
    ): TargetSymbolResolver {
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

    private fun runtimeTarget(packageName: String, classLoader: ClassLoader): TargetArtifact =
        TargetArtifact(
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

    private fun targetArtifact(context: Context, packageName: String): TargetArtifact {
        val info = context.packageManager.getApplicationInfo(
            packageName,
            PackageManager.ApplicationInfoFlags.of(0),
        )
        val packageInfo = context.packageManager.getPackageInfo(
            packageName,
            PackageManager.PackageInfoFlags.of(0),
        )
        return targetArtifact(packageName, info, packageInfo.longVersionCode)
    }

    private fun targetArtifact(packageName: String, info: ApplicationInfo, versionCode: Long): TargetArtifact {
        val paths = buildList {
            add(info.sourceDir)
            info.splitSourceDirs?.let(::addAll)
        }.map(::File)
        return TargetArtifact.fromFiles(packageName, versionCode, paths)
    }
}
