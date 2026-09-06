// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.updateSettings.impl.pluginsAdvertisement

import com.intellij.ide.IdeBundle
import com.intellij.ide.plugins.DEPENDENCY_SUPPORT_FEATURE
import com.intellij.ide.plugins.FILE_HANDLER_KIND
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.ide.plugins.PluginManagerConfigurable
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.ide.plugins.advertiser.PluginData
import com.intellij.ide.plugins.marketplace.MarketplaceRequests
import com.intellij.ide.plugins.pluginRequiresUltimatePluginButItsDisabled
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.fileLogger
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.updateSettings.impl.pluginsAdvertisement.PluginAdvertiserEditorNotificationProvider.AdvertiserSuggestion
import com.intellij.openapi.util.NlsContexts
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.intellij.ui.EditorNotifications
import com.intellij.util.application
import fleet.util.Either
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.annotations.TestOnly
import org.jetbrains.annotations.VisibleForTesting
import java.util.Collections
import java.util.function.Function
import javax.swing.JComponent

@ApiStatus.Internal
class PluginAdvertiserEditorNotificationProvider : EditorNotificationProvider, DumbAware {

  override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
    if (!TrustedProjects.isProjectTrusted(project)) return null

    if (application.isHeadlessEnvironment) {
      return null
    }

    if (isAdvertisementSuppressed(project, file)) {
      return null
    }

    val providedSuggestion = SUGGESTION_EP_NAME.extensionList
      .firstNotNullOfOrNull { it.getSuggestion(project, file) }

    val suggestionChoice = getSuggestionData(project = project, file = file)

    // If no advertisement suggestions are found, schedule an update so we make sure that
    // plugin information is up to date the next time the file is opened.
    if (suggestionChoice.isError) {
      project.service<AdvertiserInfoUpdateService>().scheduleAdvertiserUpdate(file)
    }

    // If no suggestion was found of either kind, do not show any kind of notification.
    val suggestionData = suggestionChoice.valueOrNull
    if (providedSuggestion == null && suggestionData == null) {
      return null
    }

    return Function { editor ->
      if (providedSuggestion != null) {
        logSuggestionShown(project, providedSuggestion.pluginIds.map { PluginId.getId(it) })
        providedSuggestion.apply(editor)
      }
      else {
        suggestionData?.apply(editor)?.let { panel ->
          logSuggestionShown(project, suggestionData.getSuggested())
          panel
        }
      }
    }
  }

  class AdvertiserSuggestion(
    private val project: Project,
    private val extensionOrFileName: String,
    foundPlugins: Set<PluginData>,
    allJetBrainsPluginsMarketplaceIds: Set<PluginId>,
    val overrideSuggestionText: @NlsContexts.Label String? = null,
    val unknownFeature: UnknownFeature? = null,
  ) : Function<FileEditor, EditorNotificationPanel?> {

    private var installedPlugin: IdeaPluginDescriptor? = null

    @VisibleForTesting
    val thirdParty: MutableSet<PluginData> = mutableSetOf()
    private val jbProduced: MutableSet<PluginData> = mutableSetOf()

    private var pluginsToInstall: Set<PluginData>? = null

    init {
      val descriptorsById = PluginManagerCore.buildPluginIdMap()
      for (data in foundPlugins) {
        val pluginId = data.pluginId

        if (pluginId in descriptorsById) {
          installedPlugin = descriptorsById[pluginId]
        }
        else if (!data.isBundled) {
          (if (allJetBrainsPluginsMarketplaceIds.contains(pluginId)) jbProduced else thirdParty) += data
        }
      }
    }

    fun getSuggested(): Collection<PluginId> {
      return pluginsToInstall?.map { it.pluginId } ?: emptyList()
    }

    override fun apply(fileEditor: FileEditor): EditorNotificationPanel? {
      val panel = EditorNotificationPanel(fileEditor, EditorNotificationPanel.Status.Info)

      val pluginAdvertiserExtensionsState = PluginAdvertiserExtensionsStateService.getInstance().createExtensionDataProvider(project)
      panel.text = overrideSuggestionText
                   ?: IdeBundle.message("plugins.advertiser.plugins.found", (jbProduced + thirdParty).size, extensionOrFileName)

      fun createInstallActionLabel(plugins: Set<PluginData>) {
        this.pluginsToInstall = plugins

        val labelText = plugins.singleOrNull()?.nullablePluginName?.let {
          IdeBundle.message("plugins.advertiser.action.install.plugin.name", it)
        } ?: IdeBundle.message("plugins.advertiser.action.install.plugins")

        panel.createActionLabel(labelText) {
          FUSEventSource.EDITOR.logInstallPlugins(plugins.map { it.pluginIdString })
          installAndEnable(project, plugins.mapTo(HashSet()) { it.pluginId }, true) {
            pluginAdvertiserExtensionsState.addEnabledExtensionOrFileNameAndInvalidateCache(extensionOrFileName)
            updateAllNotifications(project)
          }
        }
      }

      val installedPlugin = installedPlugin
      if (installedPlugin != null) {
        if (!installedPlugin.isEnabled) {
          if (pluginRequiresUltimatePluginButItsDisabled(installedPlugin.pluginId)) {
            // the plugin requires ultimate and it cannot be enabled
            return null
          }

          panel.createActionLabel(IdeBundle.message("plugins.advertiser.action.enable.plugin", installedPlugin.name)) {
            pluginAdvertiserExtensionsState.addEnabledExtensionOrFileNameAndInvalidateCache(extensionOrFileName)
            updateAllNotifications(project)
            FUSEventSource.EDITOR.logEnablePlugins(listOf(installedPlugin.pluginId.idString), project)
            PluginManagerConfigurable.showPluginConfigurableAndEnable(project, setOf(installedPlugin))
          }
        }
        else {
          // The plugin supporting the pattern is installed and enabled, but the current file is reassigned to a different file type
          return null
        }
      }
      else if (thirdParty.isNotEmpty() || jbProduced.isNotEmpty()) {
        createInstallActionLabel(jbProduced + thirdParty)
      }
      else {
        return null
      }

      panel.createActionLabel(IdeBundle.message("plugins.advertiser.action.ignore.extension")) {
        FUSEventSource.EDITOR.logIgnoreExtension(project)
        if (unknownFeature == null) {
          pluginAdvertiserExtensionsState.ignoreExtensionOrFileNameAndInvalidateCache(extensionOrFileName)
        }
        else {
          UnknownFeaturesCollector.getInstance(project).ignoreFeature(unknownFeature)
        }
        updateAllNotifications(project)
      }

      return panel
    }
  }
}

private val SUGGESTION_EP_NAME: ExtensionPointName<PluginSuggestionProvider> = ExtensionPointName("com.intellij.pluginSuggestionProvider")

private val SUPPRESSOR_EP_NAME: ExtensionPointName<PluginAdvertiserSuppressor> = ExtensionPointName("com.intellij.pluginAdvertiserSuppressor")

@VisibleForTesting
@ApiStatus.Internal
fun isAdvertisementSuppressed(project: Project, file: VirtualFile): Boolean {
  return SUPPRESSOR_EP_NAME.extensionList.any { it.isSuppressedFor(project, file) }
}

private val loggedPluginSuggestions: MutableCollection<PluginId> = Collections.synchronizedSet(HashSet())

private fun logSuggestionShown(project: Project, pluginIds: Collection<PluginId>) {
  for (pluginId in pluginIds) {
    if (!loggedPluginSuggestions.contains(pluginId)) {
      FUSEventSource.EDITOR.logPluginSuggested(project, pluginId)
      loggedPluginSuggestions.add(pluginId)
    }
  }
}

private fun getSuggestionData(
  project: Project,
  file: VirtualFile,
): Either<AdvertiserSuggestion?, NoSuchElementException> {
  val suggestion = PluginAdvertiserExtensionsStateService.getInstance().createExtensionDataProvider(project)
    .requestExtensionData(file)

  return when (suggestion) {
    null -> Either.error(NoSuchElementException())
    is NoSuggestions -> Either.value(null)
    is PluginAdvertisedByFileName -> Either.value(getSuggestionData(project, suggestion))
    is PluginAdvertisedByFileContent -> Either.value(getSuggestionDataByDetector(project, suggestion))
  }
}

private fun getSuggestionDataByDetector(project: Project, suggestion: PluginAdvertisedByFileContent): AdvertiserSuggestion {
  val implementationName = "${FILE_HANDLER_KIND}:${suggestion.fileHandler.id}"

  return AdvertiserSuggestion(
    project,
    implementationName,
    suggestion.plugins,
    emptySet(),
    IdeBundle.message("plugins.advertiser.plugins.file.handler.found", suggestion.fileHandler.displayName.get()),
    UnknownFeature(DEPENDENCY_SUPPORT_FEATURE, implementationName)
  )
}

@ApiStatus.Internal
@TestOnly
fun getSuggestionData(
  project: Project,
  fileName: String,
  fileType: FileType,
): AdvertiserSuggestion? {
  return service<PluginAdvertiserExtensionsStateService>()
    .createExtensionDataProvider(project)
    .requestExtensionData(fileName, fileType)
    ?.let { it as? PluginAdvertisedByFileName }
    ?.let { getSuggestionData(project = project, extensionsData = it) }
}

private fun getSuggestionData(
  project: Project,
  extensionsData: PluginAdvertisedByFileName,
): AdvertiserSuggestion? {
  val marketplaceRequests = MarketplaceRequests.getInstance()
  val jbPluginsIds: Set<PluginId> = if (ApplicationManager.getApplication().isUnitTestMode) {
    emptySet()
  }
  else {
    marketplaceRequests.loadCachedJBPlugins() ?: return null
  }

  val extensionOrFileName = extensionsData.extensionOrFileName
  val dataSet = extensionsData.plugins

  return AdvertiserSuggestion(project, extensionOrFileName, dataSet, jbPluginsIds)
}

private fun updateAllNotifications(project: Project) {
  EditorNotifications.getInstance(project).updateAllNotifications()
}

private val LOG: Logger = fileLogger()

@Service(Service.Level.PROJECT)
internal class AdvertiserInfoUpdateService(
  private val project: Project,
  private val coroutineScope: CoroutineScope,
) {
  private val mutex = Mutex()

  fun scheduleAdvertiserUpdate(file: VirtualFile) {
    val fileName = file.name
    coroutineScope.launch {
      mutex.withLock {
        val extensionsStateService = PluginAdvertiserExtensionsStateService.getInstance()
        var shouldUpdateNotifications = extensionsStateService.updateCache(fileName)
        val fullExtension = PluginAdvertiserExtensionsStateService.getFullExtension(fileName)
        if (fullExtension != null) {
          shouldUpdateNotifications = extensionsStateService.updateCache(fullExtension) || shouldUpdateNotifications
        }

        shouldUpdateNotifications = extensionsStateService.updateCompatibleFileHandlers() || shouldUpdateNotifications

        if (shouldUpdateNotifications) {
          withContext(Dispatchers.EDT) {
            updateAllNotifications(project)
          }
        }

        LOG.debug("Tried to update extensions cache for file '${fileName}'. shouldUpdateNotifications=$shouldUpdateNotifications")
      }
    }
  }
}
