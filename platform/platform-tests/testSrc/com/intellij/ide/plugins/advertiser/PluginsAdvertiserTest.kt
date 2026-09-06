// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.plugins.advertiser

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.updateSettings.impl.pluginsAdvertisement.PluginAdvertiserExtensionsStateService
import com.intellij.openapi.updateSettings.impl.pluginsAdvertisement.getSuggestionData
import com.intellij.testFramework.ProjectRule
import kotlinx.coroutines.runBlocking
import org.junit.ClassRule
import org.junit.Test
import javax.swing.Icon
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PluginsAdvertiserTest {
  companion object {
    @JvmField
    @ClassRule
    val projectRule = ProjectRule(preloadServices = true)
  }

  @Test
  fun suggestionForNonPlainTextFile() = runBlocking {
    preparePluginCache("build.xml" to PluginData("Ant"))
    val suggestion = getSuggestionData(projectRule.project, "build.xml", SupportedFileType())

    assertNotNull(suggestion)
    assertEquals(listOf("Ant"), suggestion.thirdParty.map { it.pluginIdString })
  }

  @Test
  fun noSuggestionForUnknownExtension() = runBlocking {
    preparePluginCache("*.jaba" to null)
    val suggestion = getSuggestionData(projectRule.project, "foo.jaba", PlainTextFileType.INSTANCE)
    assertNull(suggestion)
  }

  @Test
  fun suggestPluginByExtension() = runBlocking {
    preparePluginCache("*.lua" to PluginData("Lua"))
    val suggestion = getSuggestionData(projectRule.project, "foo.lua", PlainTextFileType.INSTANCE)

    assertNotNull(suggestion)
    assertEquals(listOf("Lua"), suggestion.thirdParty.map { it.pluginIdString })
  }

  private suspend fun preparePluginCache(vararg ext: Pair<String, PluginData?>) {
    val featureMap = ext.associate { (extensionOrFileName, pluginData) ->
      extensionOrFileName to PluginDataSet(setOfNotNull(pluginData))
    }

    PluginFeatureCacheService.getInstance().extensions.set(PluginFeatureMap(featureMap))

    val pluginAdvertiserExtensionsStateService = PluginAdvertiserExtensionsStateService.getInstance()
    for ((extensionOrFileName, pluginDataSet) in featureMap) {
      pluginAdvertiserExtensionsStateService.updateCache(extensionOrFileName = extensionOrFileName, compatiblePlugins = pluginDataSet.dataSet)
    }
  }

  private class SupportedFileType : FileType {
    override fun getName(): String = "supported"
    override fun getDescription(): String = "Supported"
    override fun getDefaultExtension(): String = "sft"
    override fun getIcon(): Icon? = null
    override fun isBinary(): Boolean = false
  }
}
