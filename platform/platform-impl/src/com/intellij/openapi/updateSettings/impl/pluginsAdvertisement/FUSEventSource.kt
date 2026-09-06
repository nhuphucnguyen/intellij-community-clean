// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.updateSettings.impl.pluginsAdvertisement

import com.intellij.internal.statistic.collectors.fus.PluginIdRuleValidator
import com.intellij.internal.statistic.collectors.fus.ProductCodeRuleValidator
import com.intellij.internal.statistic.eventLog.EventLogGroup
import com.intellij.internal.statistic.eventLog.events.EventFields
import com.intellij.internal.statistic.service.fus.collectors.CounterUsagesCollector
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import kotlinx.serialization.Serializable
import org.jetbrains.annotations.ApiStatus
import java.util.Locale.ROOT

internal object PluginAdvertiserUsageCollector : CounterUsagesCollector() {
  override fun getGroup(): EventLogGroup = GROUP

  private const val FUS_GROUP_ID = "plugins.advertiser"

  private val GROUP = EventLogGroup(FUS_GROUP_ID, 13)

  val SOURCE_FIELD = EventFields.Enum(
    "source",
    FUSEventSource::class.java,
  ) { it.name.lowercase(ROOT) }

  val CONFIGURE_PLUGINS_EVENT = GROUP.registerEvent(
    "configure.plugins",
    SOURCE_FIELD,
  )

  private val PLUGINS_FIELD = EventFields.StringListValidatedByCustomRule(
    "plugins",
    PluginIdRuleValidator::class.java,
  )

  val PLUGIN_FIELD = EventFields.StringValidatedByCustomRule(
    "pluginId",  // "plugin" is a reserved platform key in FeatureUsageData.platformDataKeys
    PluginIdRuleValidator::class.java,
  )

  private val PRODUCT_CODE_FIELD = EventFields.StringValidatedByCustomRule(
    "productCode",
    ProductCodeRuleValidator::class.java,
  )

  val ENABLE_PLUGINS_EVENT = GROUP.registerEvent(
    "enable.plugins",
    PLUGINS_FIELD,
    SOURCE_FIELD,
  )

  val INSTALL_PLUGINS_EVENT = GROUP.registerEvent(
    "install.plugins",
    PLUGINS_FIELD,
    SOURCE_FIELD,
  )

  val IGNORE_EXTENSIONS_EVENT = GROUP.registerEvent(
    "ignore.extensions",
    SOURCE_FIELD,
  )

  val IGNORE_UNKNOWN_FEATURES_EVENT = GROUP.registerEvent(
    "ignore.unknown.features",
    SOURCE_FIELD,
  )

  val SUGGESTED_EVENT = GROUP.registerEvent(
    "suggestion.shown",
    SOURCE_FIELD,
    PLUGIN_FIELD,
    PRODUCT_CODE_FIELD
  )
}

@Serializable
@ApiStatus.Internal
enum class FUSEventSource {
  EDITOR,
  NOTIFICATION,
  PLUGINS_SEARCH,
  PLUGINS_SUGGESTED_GROUP,
  PLUGINS_STAFF_PICKS_GROUP,
  ACTIONS,
  SETTINGS,
  NEW_PROJECT_WIZARD,

  @Deprecated("Use PLUGINS_SEARCH instead")
  SEARCH;

  @JvmOverloads
  fun logConfigurePlugins(project: Project? = null): Unit = PluginAdvertiserUsageCollector.CONFIGURE_PLUGINS_EVENT.log(project, this)

  @JvmOverloads
  fun logEnablePlugins(
    plugins: List<String>,
    project: Project? = null,
  ): Unit = PluginAdvertiserUsageCollector.ENABLE_PLUGINS_EVENT.log(project, plugins, this)

  @JvmOverloads
  fun logInstallPlugins(
    plugins: List<String>,
    project: Project? = null,
  ): Unit = PluginAdvertiserUsageCollector.INSTALL_PLUGINS_EVENT.log(project, plugins, this)

  @JvmOverloads
  fun logIgnoreExtension(project: Project? = null): Unit = PluginAdvertiserUsageCollector.IGNORE_EXTENSIONS_EVENT.log(project, this)

  @JvmOverloads
  fun logIgnoreUnknownFeatures(project: Project? = null): Unit = PluginAdvertiserUsageCollector.IGNORE_UNKNOWN_FEATURES_EVENT.log(project, this)

  @JvmOverloads
  fun logPluginSuggested(project: Project? = null, pluginId: PluginId?) {
    PluginAdvertiserUsageCollector.SUGGESTED_EVENT.log(project, this, pluginId?.idString, null)
  }
}