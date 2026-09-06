// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:JvmName("PluginsAdvertiser")

package com.intellij.openapi.updateSettings.impl.pluginsAdvertisement

import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationGroupManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import org.jetbrains.annotations.ApiStatus

@get:Deprecated("Use `getPluginSuggestionNotificationGroup()`")
@get:ApiStatus.ScheduledForRemoval
val notificationGroup: NotificationGroup
  get() = getPluginSuggestionNotificationGroup()

fun getPluginSuggestionNotificationGroup(): NotificationGroup {
  return NotificationGroupManager.getInstance().getNotificationGroup("Plugins Suggestion")
}

@Suppress("DeprecatedCallableAddReplaceWith", "DEPRECATION")
@Deprecated("Use `installAndEnable(Project, Set, Boolean, Runnable)`")
fun installAndEnablePlugins(
  pluginIds: Set<String>,
  onSuccess: Runnable,
) {
  installAndEnable(
    LinkedHashSet(pluginIds.map { PluginId.getId(it) }),
    onSuccess,
  )
}

@Deprecated("Use `installAndEnable(Project, Set, Boolean, Runnable)`")
fun installAndEnable(
  pluginIds: Set<PluginId>,
  onSuccess: Runnable,
) {
  installAndEnable(
    project = null,
    pluginIds = pluginIds,
    showDialog = true,
    selectAlInDialog = false,
    modalityState = null,
    onSuccess = onSuccess,
  )
}

@JvmOverloads
fun installAndEnable(
  project: Project?,
  pluginIds: Set<PluginId>,
  showDialog: Boolean = false,
  selectAlInDialog: Boolean = false,
  modalityState: ModalityState? = null,
  onSuccess: Runnable,
) {
  getInstallAndEnableTask(
    project = project,
    pluginIds = pluginIds,
    showDialog = showDialog,
    selectAlInDialog = selectAlInDialog,
    modalityState = modalityState,
    onSuccess = onSuccess,
  ).runBlocking()
}

@JvmOverloads
fun getInstallAndEnableTask(
  project: Project?,
  pluginIds: Set<PluginId>,
  showDialog: Boolean = false,
  selectAlInDialog: Boolean = false,
  modalityState: ModalityState? = null,
  onSuccess: Runnable,
): InstallAndEnableTask {
  require(!showDialog || modalityState == null) {
    "`modalityState` can be not null only if plugin installation won't show the dialog"
  }
  return InstallAndEnableTask(project, pluginIds, showDialog, selectAlInDialog, modalityState, onSuccess)
}
