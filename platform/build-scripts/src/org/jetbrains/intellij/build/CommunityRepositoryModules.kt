// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("LiftReturnOrAssignment", "ReplaceJavaStaticMethodWithKotlinAnalog")

package org.jetbrains.intellij.build

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import org.jetbrains.intellij.build.dependencies.BuildDependenciesDownloader
import org.jetbrains.intellij.build.dependencies.BuildDependenciesExtractOptions
import org.jetbrains.intellij.build.impl.BundledMavenDownloader
import org.jetbrains.intellij.build.impl.LibraryPackMode
import org.jetbrains.intellij.build.impl.ModuleItem
import org.jetbrains.intellij.build.impl.PluginLayout
import org.jetbrains.intellij.build.impl.PluginLayout.Companion.plugin
import org.jetbrains.intellij.build.impl.PluginLayout.Companion.pluginAuto
import org.jetbrains.intellij.build.impl.PluginLayout.Companion.pluginAutoWithCustomDirName
import org.jetbrains.intellij.build.impl.PluginVersionEvaluatorResult
import org.jetbrains.intellij.build.impl.ProjectLibraryData
import org.jetbrains.intellij.build.impl.SUPPORTED_DISTRIBUTIONS
import org.jetbrains.intellij.build.impl.SupportedDistribution
import org.jetbrains.intellij.build.impl.osArchPluginVersion
import org.jetbrains.intellij.build.impl.patchOsSpecificPluginXml
import org.jetbrains.intellij.build.impl.projectStructureMapping.DistributionFileEntry
import org.jetbrains.intellij.build.impl.projectStructureMapping.ProjectLibraryEntry
import org.jetbrains.intellij.build.io.copyDir
import org.jetbrains.intellij.build.io.copyFile
import org.jetbrains.intellij.build.io.copyFileToDir
import org.jetbrains.intellij.build.io.defaultLibrarySourcesNamesFilter
import org.jetbrains.intellij.build.kotlin.CommunityKotlinPluginBuilder
import org.jetbrains.intellij.build.python.PythonCommunityPluginModules
import org.jetbrains.intellij.build.telemetry.TraceManager.spanBuilder
import org.jetbrains.intellij.build.telemetry.use
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

object CommunityRepositoryModules {
  /**
   * Specifies non-trivial layout for all plugins that sources are located in 'community' and 'contrib' repositories
   */
  val COMMUNITY_REPOSITORY_PLUGINS: PersistentList<PluginLayout> = persistentListOf(
    plugin("intellij.ant") { spec ->
      spec.mainJarName = "antIntegration.jar"
      spec.withModule("intellij.ant.jps", "ant-jps.jar")

      spec.withGeneratedResources { dir, buildContext ->
        copyAnt(mainModule = spec.mainModule, pluginDir = dir, context = buildContext)
      }
    },
    plugin("intellij.laf.macos") { spec ->
      spec.bundlingRestrictions.supportedOs = persistentListOf(OsFamily.MACOS)
    },
    plugin("intellij.webp") { spec ->
      for ((os, arch, libc) in SUPPORTED_DISTRIBUTIONS) {
        spec.withGeneratedPlatformResources(os, arch, libc, allowInDevMode = true) { targetDir, context ->
          copyFileToDir(NativeBinaryDownloader.getLibWebp(context, os, arch), targetDir.resolve("lib/libwebp/${os.dirName}/${arch.dirName}"))
        }
      }
    },
    plugin("intellij.webp") { spec ->
      spec.bundlingRestrictions.marketplace = true
      spec.withGeneratedResources { targetDir, context ->
        for ((os, arch, _) in SUPPORTED_DISTRIBUTIONS) {
          copyFileToDir(NativeBinaryDownloader.getLibWebp(context, os, arch), targetDir.resolve("lib/libwebp/${os.dirName}/${arch.dirName}"))
        }
      }
    },
    plugin("intellij.laf.win10") { spec ->
      spec.bundlingRestrictions.supportedOs = persistentListOf(OsFamily.WINDOWS)
    },
    plugin("intellij.java.guiForms.designer") { spec ->
      spec.directoryName = "uiDesigner"
      spec.mainJarName = "uiDesigner.jar"
      spec.withModule("intellij.java.guiForms.jps", "jps/java-guiForms-jps.jar")
    },
    CommunityKotlinPluginBuilder.kotlinPlugin(),
    pluginAuto(listOf("intellij.vcs.git")) { spec ->
      spec.withModule("intellij.vcs.git.rt", "git4idea-rt.jar")
    },
    pluginAuto(listOf("intellij.xpath")) { spec ->
      spec.withModule("intellij.xpath.rt", "rt/xslt-rt.jar")
    },
    pluginAutoWithCustomDirName("intellij.tasks.core") { spec ->
      spec.directoryName = "tasks"
      spec.withModule("intellij.tasks")
      spec.withModule("intellij.tasks.compatibility")
      spec.withModule("intellij.tasks.java")
    },
    // The relative paths below (`lib/maven3`, `lib/intellij.maven.server3`, ...) are a runtime contract, not an
    // internal packaging detail: the Maven plugin reads them back at runtime through
    // `MavenClasspathBuilder.addMavenServerLibraries` and `MavenDistributionsCache.resolveEmbeddedMavenHome`.
    // This layout is produced identically for a release build and a dev build (resource generators run in both -
    // only `BuildOptions.skipCustomResourceGenerators`, used by build tests, suppresses them), which is why the
    // runtime has no dev-build-specific branch. Renaming a path here breaks the IDE, not just the distribution.
    plugin("intellij.maven.plugin") { spec ->

      spec.doNotCopyModuleLibrariesAutomatically(
        listOf(
          "intellij.maven.artifactResolver.common",
          "intellij.maven.artifactResolver.m31",
          "intellij.maven.server.m3.common",
          "intellij.maven.server.m3.impl",
          "intellij.maven.server.m36.impl",
          "intellij.maven.server.m40",
          "intellij.maven.server.indexer",
        )
      )

      spec.withGeneratedResources { targetDir, context ->
        val targetLib = targetDir.resolve("lib")

        val mavenDist = BundledMavenDownloader.downloadMavenDistribution(context.paths.communityHomeDirRoot)
        copyDir(sourceDir = mavenDist, targetDir = targetLib.resolve("maven3"), overwrite = true)
      }

      with("intellij.maven.server3") {
        spec.withModule("intellij.maven.server.m3.common", "$this/maven3-server-common.jar")
        spec.withModule("intellij.maven.server.m3.impl", "$this/maven3-server.jar")
        spec.withModule("intellij.maven.server.telemetry", "$this/maven-server-telemetry.jar")


        spec.withGeneratedResources { targetDir, context ->
          val targetLib = targetDir.resolve("lib")
          val maven3Libs = BundledMavenDownloader.resolveMaven3Libs(context.paths.communityHomeDirRoot)
          copyMavenLibraries(maven3Libs, targetLib.resolve(this))
          val mavenTelemetryDependencies = BundledMavenDownloader.resolveMavenTelemetryDependencies(context.paths.communityHomeDirRoot)
          copyMavenLibraries(mavenTelemetryDependencies, targetLib.resolve(this))
        }
      }

      with("intellij.maven.server36") {
        spec.withModule("intellij.maven.server.m36.impl", "$this/maven36-server.jar")
      }

      with("intellij.maven.server4") {
        spec.withModule("intellij.maven.server.m40", "$this/maven40-server.jar")
        spec.withModule("intellij.maven.server.telemetry", "$this/maven-server-telemetry.jar")


        spec.withGeneratedResources { targetDir, context ->
          val targetLib = targetDir.resolve("lib")
          val maven4Libs = BundledMavenDownloader.resolveMaven4Libs(context.paths.communityHomeDirRoot)
          copyMavenLibraries(maven4Libs, targetLib.resolve(this))
          val mavenTelemetryDependencies = BundledMavenDownloader.resolveMavenTelemetryDependencies(context.paths.communityHomeDirRoot)
          copyMavenLibraries(mavenTelemetryDependencies, targetLib.resolve(this))
        }
      }

      with("intellij.maven.rt") {
        spec.withModule("intellij.maven.server.eventListener", relativeJarPath = "$this/maven-event-listener.jar")
      }

      with("intellij.maven.server.indexer") {
        spec.withModule("intellij.maven.server.indexer", "$this/maven-server-indexer.jar")
        spec.withModuleLibrary(
          libraryName = "apache.maven.core:3.8.3", moduleName = "intellij.maven.server.indexer",
          relativeOutputPath = this
        )
        spec.withModuleLibrary(
          libraryName = "apache.maven.wagon.provider.api:3.5.2", moduleName = "intellij.maven.server.indexer",
          relativeOutputPath = this
        )
        spec.withModuleLibrary(
          libraryName = "apache.maven.archetype.common-no-trans:3.2.1", moduleName = "intellij.maven.server.indexer",
          relativeOutputPath = this
        )
        spec.withModuleLibrary(
          libraryName = "apache.maven.archetype.catalog-no-trans:321", moduleName = "intellij.maven.server.indexer",
          relativeOutputPath = this
        )
      }

      spec.withModule("intellij.idea.community.build.dependencies")
      spec.withModule("intellij.platform.buildScripts.concurrency")
      spec.withModule("intellij.maven.artifactResolver.m31", "artifact-resolver-m31.jar")
      spec.withModule("intellij.maven.artifactResolver.common", "artifact-resolver-m31.jar")
      spec.withModule("intellij.maven.server", relativeJarPath = "maven-server.jar")
    },
    pluginAuto(
      listOf(
        "intellij.gradle.plugin",
        "intellij.gradle",
        "intellij.gradle.common",
      )
    ) { spec ->
      spec.withModule("intellij.gradle.toolingProxy", "gradle-tooling-proxy.jar")
      spec.withModule("intellij.gradle.toolingExtension", "gradle-tooling-extension-api.jar")
      spec.withModule("intellij.gradle.toolingExtension.impl", "gradle-tooling-extension-impl.jar")
      spec.withModule("intellij.libraries.groovy", "groovy.jar")
      spec.withModule("intellij.libraries.groovy.ant", "groovy-ant.jar")
      spec.withProjectLibrary("Ant", "ant", LibraryPackMode.STANDALONE_SEPARATE)
    },
    pluginAuto(listOf("intellij.gradle.java.plugin", "intellij.gradle.java", "intellij.gradle.jps")),
    pluginAuto("intellij.junit") { spec ->
      spec.withModule("intellij.junit.rt", "junit-rt.jar")
      spec.withModule("intellij.junit.v5.rt", "junit5-rt.jar")
      spec.withModule("intellij.junit.v6.rt", "junit6-rt.jar")
    },
    plugin("intellij.testng") { spec ->
      spec.mainJarName = "testng-plugin.jar"
      spec.withModule("intellij.testng.rt", "testng-rt.jar")
    },
    pluginAuto(listOf("intellij.devkit")) { spec ->
      spec.withModule("intellij.devkit.jps")

      spec.bundlingRestrictions.includeInDistribution = PluginDistribution.NOT_FOR_PUBLIC_BUILDS
    },
    pluginAuto(listOf("intellij.eclipse")) { spec ->
      spec.withModule("intellij.eclipse.jps", "eclipse-jps.jar")
      spec.withModule("intellij.eclipse.common", "eclipse-common.jar")
    },
    plugin("intellij.java.coverage") { spec ->
      // explicitly pack JaCoCo as a separate JAR
      spec.withModuleLibrary(libraryName = "JaCoCo", moduleName = "intellij.java.coverage", relativeOutputPath = "jacoco.jar")
    },
    plugin("intellij.java.decompiler") { spec ->
      spec.directoryName = "java-decompiler"
      spec.mainJarName = "java-decompiler.jar"
      spec.withModule("intellij.java.decompiler.engine", spec.mainJarName)
    },
    javaFXPlugin("intellij.javaFX.community"),
    pluginAuto("intellij.terminal") { spec ->
      spec.withModule("intellij.terminal.completion")
      spec.withResource("resources/shell-integrations", "shell-integrations")
      // bundle the libghostty-vt native library
      for ((os, arch, libc) in SUPPORTED_DISTRIBUTIONS) {
        val dirName = os.osName.lowercase() + "-" + arch.archName.lowercase()
        // `allowInDevMode = true`: load libghostty-vt when running from a dev build
        spec.withGeneratedPlatformResources(os, arch, libc, allowInDevMode = true) { targetDir, context ->
          copyFileToDir(NativeBinaryDownloader.getLibGhosttyVt(context, os, arch), targetDir.resolve("libghostty-vt/$dirName"))
        }
      }
    },
    pluginAuto(listOf("intellij.textmate.plugin")) { spec ->
      spec.withResourceFromModule("intellij.textmate", "lib/bundles", "lib/bundles")
    },
    PythonCommunityPluginModules.pythonCommunityPluginLayout(),
    pluginAuto(listOf("intellij.completionMlRankingModels")) { spec ->
      spec.bundlingRestrictions.includeInDistribution = PluginDistribution.NOT_FOR_RELEASE
    },
    pluginAuto(listOf("intellij.statsCollector")) { spec ->
      spec.bundlingRestrictions.includeInDistribution = PluginDistribution.NOT_FOR_RELEASE
    },
    pluginAuto(listOf("intellij.findUsagesMl")) { spec ->
      spec.bundlingRestrictions.includeInDistribution = PluginDistribution.NOT_FOR_RELEASE
    },
    pluginAutoWithCustomDirName("intellij.lombok.plugin") { spec ->
      spec.directoryName = "lombok"
    },
    pluginAuto(listOf("intellij.performanceTesting.ui")),
    pluginAuto(listOf("intellij.vcs.github")),
    pluginAuto(listOf("intellij.vcs.gitlab")),
    pluginAuto(listOf("intellij.compilation.charts")) { spec ->
      spec.withModule("intellij.compilation.charts.jps")
    },
    pluginAuto("intellij.java.jshell") { spec ->
      spec.withModule("intellij.java.jshell.protocol", "jshell-protocol.jar")
    },
    pluginAuto(listOf("intellij.tipsOfTheDay.plugin")),
    *allJcefPlugins()
  )

  val CONTRIB_REPOSITORY_PLUGINS: List<PluginLayout> = java.util.List.of(
    pluginAuto("intellij.errorProne") { spec ->
      spec.withModule("intellij.errorProne.jps", "jps/errorProne-jps.jar")
    },
    pluginAuto("intellij.cucumber.java") { spec ->
      spec.withModule("intellij.cucumber.jvmFormatter", "cucumber-jvmFormatter.jar")
      spec.withModule("intellij.cucumber.jvmFormatter3", "cucumber-jvmFormatter3.jar")
      spec.withModule("intellij.cucumber.jvmFormatter4", "cucumber-jvmFormatter4.jar")
      spec.withModule("intellij.cucumber.jvmFormatter5", "cucumber-jvmFormatter5.jar")
    },
    pluginAuto("intellij.serial.monitor") { spec ->
      // jSerialComm java JAR - Remember to update the binary dependency when updating to a new version!
      spec.withProjectLibrary("jetbrains.intellij.deps.jSerialComm", LibraryPackMode.STANDALONE_SEPARATE)

      // jSerialComm native library
      spec.withGeneratedResources { targetDir, context ->
        val uri = URI.create("https://packages.jetbrains.team/files/p/ij/intellij-build-dependencies/jSerialComm/9a7813435b79aa2e23c7f2a78f1b66b48c0504c4/jSerialComm.zip")
        val downloaded = BuildDependenciesDownloader.downloadFileToCacheLocation(context.paths.communityHomeDirRoot, uri)
        BuildDependenciesDownloader.extractFile(downloaded, targetDir.resolve("bin"), context.paths.communityHomeDirRoot)
      }
    },
  )

  fun allJcefPlugins(): Array<PluginLayout> {
    val supportedOsArch = listOf(
      SupportedDistribution(os = OsFamily.MACOS, arch = JvmArchitecture.x64, MacLibcImpl.DEFAULT),
      SupportedDistribution(os = OsFamily.MACOS, arch = JvmArchitecture.aarch64, MacLibcImpl.DEFAULT),
      SupportedDistribution(os = OsFamily.WINDOWS, arch = JvmArchitecture.x64, WindowsLibcImpl.DEFAULT),
      SupportedDistribution(os = OsFamily.WINDOWS, arch = JvmArchitecture.aarch64, WindowsLibcImpl.DEFAULT),
      SupportedDistribution(os = OsFamily.LINUX, arch = JvmArchitecture.x64, LinuxLibcImpl.GLIBC),
      SupportedDistribution(os = OsFamily.LINUX, arch = JvmArchitecture.aarch64, LinuxLibcImpl.GLIBC),
    )

    val allLayouts = ArrayList(supportedOsArch.map { (os, arch, _) -> jcefPlugin(os, arch) })
    allLayouts += jcefCrossPlatformEmpty()
    return allLayouts.toTypedArray()
  }

  private fun jcefCrossPlatformEmpty(): PluginLayout {
    return plugin("intellij.jcef.plugin") { // cross-platform distribution comes without JCEF binaries
      it.bundlingRestrictions.includeInDistribution = PluginDistribution.CROSS_PLATFORM_DIST_ONLY
    }
  }

  /**
   * The JCEF archive the [jcefPlugin] resource generator downloads. Public so tests that
   * pre-provision the build-dependencies download cache can pin the same URL.
   */
  fun jcefDownloadUrl(os: OsFamily, arch: JvmArchitecture, build: String): String {
    val archSuffix = when (arch) {
      JvmArchitecture.x64 -> "x64"
      JvmArchitecture.aarch64 -> "aarch64"
    }
    return "https://cache-redirector.jetbrains.com/intellij-jbr/jcef-${os.jbrArchiveSuffix}-${archSuffix}-${build}.tar.gz"
  }

  fun jcefPlugin(os: OsFamily, arch: JvmArchitecture): PluginLayout {
    return plugin("intellij.jcef.plugin") { spec ->
      spec.bundlingRestrictions.supportedOs = persistentListOf(os)
      spec.bundlingRestrictions.supportedArch = persistentListOf(arch)

      patchOsSpecificPluginXml(spec, os, arch)

      // be careful, Marketplace expects linux/macos/windows for os and x86_64/x86/arm64/arm32 for arch
      spec.withCustomVersion(osArchPluginVersion(os = os, arch = arch))

      spec.withGeneratedResources { targetDir, context ->
        val communityRoot = context.paths.communityHomeDirRoot
        val properties = BuildDependenciesDownloader.getDependencyProperties(communityRoot)
        val jcefBuildNumber = properties.property("jcefBuild")

        // extracted into the content-keyed cache rather than straight into the layout: a dev run directory is wiped
        // on every launch (`IdeBuilder`), so an extraction that lands in it is an extraction repeated every launch
        val extracted = resolveAndExtractToCacheLocation(
          url = jcefDownloadUrl(os, arch, jcefBuildNumber),
          communityRoot = communityRoot,
          BuildDependenciesExtractOptions.STRIP_ROOT,
        )

        // Unix ZIP does not have root `jcef` directory
        val jcefOutputDir = extracted.resolve("jcef").takeIf { Files.exists(it) } ?: extracted
        copyDir(sourceDir = jcefOutputDir, targetDir = targetDir.resolve("jcef"), overwrite = true)
      }

      spec.enableSymlinksAndExecutableResources()
    }
  }

  private val supportedFfmpegPresets: PersistentList<SupportedDistribution> = persistentListOf(    SupportedDistribution(os = OsFamily.MACOS, arch = JvmArchitecture.x64, MacLibcImpl.DEFAULT),
    SupportedDistribution(os = OsFamily.MACOS, arch = JvmArchitecture.aarch64, MacLibcImpl.DEFAULT),
    SupportedDistribution(os = OsFamily.WINDOWS, arch = JvmArchitecture.x64, WindowsLibcImpl.DEFAULT),
    SupportedDistribution(os = OsFamily.LINUX, arch = JvmArchitecture.x64, LinuxLibcImpl.GLIBC),
  )

  /**
   * Packs the ffmpeg and javacpp libraries of the `intellij.libraries.ffmpeg` wrapper module, keeping only the natives
   * that match this layout's [os] and [arch]. Pass `null` for both to get every platform, as the cross-platform
   * distribution does.
   *
   * The libraries live in that wrapper module, registered as private plugin content in
   * `android-plugin/descriptor/resources/META-INF/plugin.xml`. `ModuleLibraryData` and `PluginLayout.excludedModuleLibraries`
   * are keyed by `(moduleName, libraryName)`, so naming the wrapper here - and keeping every `relativeOutputPath` -
   * leaves the distribution byte-identical to when the libraries still sat on `intellij.android.streaming`.
   *
   * The `relativeOutputPath` arguments are load-bearing: without them the wrapper would merge all ten jars into one
   * archive, which loses the per-platform filtering below and changes the javacpp native-extraction cache path (it is
   * keyed on the containing jar's name). One wrapper holds all ten because per-platform content modules are impossible
   * - content registration is static XML - so the exclusion below is what does the filtering.
   *
   * Shared with Rider's Android plugin layout (`createRiderAndroidPluginLayout`): both distributions must agree, and
   * keeping this in one place is what stops them drifting apart.
   */
  fun PluginLayout.PluginLayoutSpec.withFfmpegWrapper(os: OsFamily?, arch: JvmArchitecture?) {
    val ffmpegVersion = "6.0-1.5.9"
    val javacppVersion = "1.5.9"
    val wrapperModuleName = "intellij.libraries.ffmpeg"

    withModuleLibrary("ffmpeg", wrapperModuleName, "ffmpeg-$ffmpegVersion.jar")
    withModuleLibrary("ffmpeg-javacpp", wrapperModuleName, "javacpp-$javacppVersion.jar")

    // include only the platform-dependent binaries matching this layout's (os, arch);
    // exclude the rest so the wrapper module's other platform libraries don't leak in.
    for ((supportedOs, supportedArch, _) in supportedFfmpegPresets) {
      val osName = supportedOs.osName.lowercase(Locale.ROOT)
      val ffmpegLibraryName = "ffmpeg-$osName-$supportedArch"
      val javacppLibraryName = "javacpp-$osName-$supportedArch"

      if (supportedOs == os && supportedArch == arch || os == null && arch == null) {
        withModuleLibrary(ffmpegLibraryName, wrapperModuleName, "${ffmpegLibraryName}-$ffmpegVersion.jar")
        withModuleLibrary(javacppLibraryName, wrapperModuleName, "${javacppLibraryName}-$javacppVersion.jar")
      }
      else {
        excludeModuleLibrary(ffmpegLibraryName, wrapperModuleName)
        excludeModuleLibrary(javacppLibraryName, wrapperModuleName)
      }
    }
  }

  fun javaFXPlugin(mainModuleName: String): PluginLayout {
    return pluginAutoWithCustomDirName(mainModuleName, "javaFX") { spec ->
      spec.withModule("intellij.javaFX.jps")
      spec.withModule("intellij.javaFX.common", "javaFX-common.jar")
      spec.withModule("intellij.javaFX.sceneBuilder", "rt/sceneBuilderBridge.jar")
    }
  }

  fun groovyPlugin(additionalModules: List<String> = emptyList(), addition: ((PluginLayout.PluginLayoutSpec) -> Unit)? = null): PluginLayout {
    return pluginAutoWithCustomDirName("intellij.groovy") { spec ->
      spec.directoryName = "Groovy"
      spec.mainJarName = "Groovy.jar"
      spec.withModules(
        listOf(
          "intellij.groovy.psi",
          "intellij.groovy.structuralSearch",
        )
      )
      spec.withModule("intellij.groovy.jps", "groovy-jps.jar")
      spec.withModule("intellij.groovy.rt", "groovy-rt.jar")
      spec.withModule("intellij.groovy.spock.rt", "groovy-spock-rt.jar")
      spec.withModule("intellij.groovy.rt.classLoader", "groovy-rt-class-loader.jar")
      spec.withModule("intellij.groovy.constants.rt", "groovy-constants-rt.jar")
      spec.withModules(additionalModules)

      spec.excludeFromModule("intellij.groovy.psi", "standardDsls/**")
      spec.withResource("groovy-psi/resources/standardDsls", "lib/standardDsls")
      spec.withResource("hotswap/gragent.jar", "lib/agent")
      spec.withResource("groovy-psi/resources/conf", "lib")
      addition?.invoke(spec)
    }
  }
}

private fun copyMavenLibraries(libraries: List<BundledMavenDownloader.MavenLibraryFile>, targetDir: Path) {
  for ((fileName, source) in libraries) {
    copyFile(file = source, target = targetDir.resolve(fileName), overwrite = true)
  }
}

private fun copyAnt(mainModule: String, pluginDir: Path, context: BuildContext): List<DistributionFileEntry> {
  val antDir = pluginDir.resolve("dist")
  return spanBuilder("copy Ant lib").setAttribute("antDir", antDir.toString()).use {
    val sources = ArrayList<ZipSource>()
    val antTargetFile = antDir.resolve("ant.jar")
    val antModuleItem = ModuleItem(mainModule, relativeOutputFile = antTargetFile.fileName.toString(), reason = "ant")
    val libraryData = ProjectLibraryData(libraryName = "Ant", packMode = LibraryPackMode.STANDALONE_MERGED, reason = "ant", owner = antModuleItem)
    copyDir(
      sourceDir = context.paths.communityHomeDir.resolve("lib/ant"),
      targetDir = antDir,
      dirFilter = { !it.endsWith("src") },
      fileFilter = { file ->
        if (file.toString().endsWith(".jar")) {
          sources.add(ZipSource(file = file, distributionFileEntryProducer = null, filter = ::defaultLibrarySourcesNamesFilter, moduleName = null))
          false
        }
        else {
          true
        }
      },
    )
    sources.sort()

    checkForNoDiskSpace(context) {
      buildJar(targetFile = antTargetFile, sources = sources)
    }

    sources.map { source ->
      ProjectLibraryEntry(
        path = antTargetFile,
        data = libraryData,
        libraryFile = source.file,
        canonicalLibraryPath = context.paths.communityHomeDir.relativize(source.file).toString(),
        hash = 0,
        size = 0,
        relativeOutputFile = "dist/ant.jar",
      )
    }
  }
}
