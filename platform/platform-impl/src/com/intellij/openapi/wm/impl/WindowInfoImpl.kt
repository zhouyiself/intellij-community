// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.wm.impl

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.wm.*
import com.intellij.util.xmlb.Converter
import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.Property
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.Transient
import java.awt.Rectangle
import kotlin.math.max
import kotlin.math.min

private val LOG = logger<WindowInfoImpl>()

@Suppress("EqualsOrHashCode")
@Tag("window_info")
@Property(style = Property.Style.ATTRIBUTE)
class WindowInfoImpl : Cloneable, WindowInfo, BaseState() {
  companion object {
    internal const val TAG = "window_info"
    const val DEFAULT_WEIGHT = 0.33f
  }

  @get:Transient
  var isRegistered = false

  override var isActive by property(false)

  @get:Attribute(converter = ToolWindowAnchorConverter::class)
  override var anchor by property(ToolWindowAnchor.LEFT) { it == ToolWindowAnchor.LEFT }

  @get:Attribute("auto_hide")
  override var isAutoHide by property(false)

  /**
   * Bounds of window in "floating" mode. It equals to `null` if floating bounds are undefined.
   */
  @get:Property(flat = true, style = Property.Style.ATTRIBUTE)
  override var floatingBounds by property<Rectangle?>(null) { it == null || (it.width == 0 && it.height == 0 && it.x == 0 && it.y == 0) }

  /**
   * This attribute persists state 'maximized' for ToolWindowType.WINDOWED where decoration is presented by JFrame
   */
  @get:Attribute("maximized")
  override var isMaximized by property(false)
  /**
   * ID of the tool window
   */
  var id by string()

  /**
   * @return type of the tool window in internal (docked or sliding) mode. Actually the tool
   * window can be in floating mode, but this method has sense if you want to know what type
   * tool window had when it was internal one.
   */
  @get:Attribute("internal_type")
  var internalType by enum(ToolWindowType.DOCKED)

  override var type by enum(ToolWindowType.DOCKED)

  @get:Attribute("visible")
  var isVisible by property(false)

  @get:Attribute("show_stripe_button")
  override var isShowStripeButton by property(true)

  /**
   * Internal weight of tool window. "weight" means how much of internal desktop
   * area the tool window is occupied. The weight has sense if the tool window is docked or
   * sliding.
   */
  var weight by property(DEFAULT_WEIGHT) { max(0f, min(1f, it)) }

  var sideWeight by property(0.5f) { max(0f, min(1f, it)) }

  @get:Attribute("side_tool")
  override var isSplit by property(false)

  @get:Attribute("content_ui", converter = ContentUiTypeConverter::class)
  override var contentUiType: ToolWindowContentUiType by property(ToolWindowContentUiType.TABBED) { it == ToolWindowContentUiType.TABBED }

  /**
   * Defines order of tool window button inside the stripe.
   */
  var order by property(-1)

  @get:Transient
  var isWasRead = false
    private set

  fun copy(): WindowInfoImpl {
    val info = WindowInfoImpl()
    info.copyFrom(this)
    info.isRegistered = isRegistered
    return info
  }

  override val isDocked: Boolean
    get() = type == ToolWindowType.DOCKED

  override val isFloating: Boolean
    get() = type == ToolWindowType.FLOATING

  override val isWindowed: Boolean
    get() = type == ToolWindowType.WINDOWED

  override val isSliding: Boolean
    get() = type == ToolWindowType.SLIDING

  fun normalizeAfterRead() {
    isWasRead = true

    setTypeAndCheck(type)

    if (isVisible && id != null && !canActivateOnStart(id!!)) {
      isVisible = false
    }
  }

  internal fun setType(type: ToolWindowType) {
    if (ToolWindowType.DOCKED == type || ToolWindowType.SLIDING == type) {
      internalType = type
    }
    setTypeAndCheck(type)
  }

  //Hardcoded to avoid single-usage-API
  private fun setTypeAndCheck(value: ToolWindowType) {
    type = if (ToolWindowId.PREVIEW === id && value == ToolWindowType.DOCKED) ToolWindowType.SLIDING else value
  }

  override fun hashCode(): Int {
    return anchor.hashCode() + id!!.hashCode() + type.hashCode() + order
  }

  override fun toString() = "id: $id, ${super.toString()}"
}

private class ContentUiTypeConverter : Converter<ToolWindowContentUiType>() {
  override fun fromString(value: String): ToolWindowContentUiType = ToolWindowContentUiType.getInstance(value)

  override fun toString(value: ToolWindowContentUiType): String = value.name
}

private class ToolWindowAnchorConverter : Converter<ToolWindowAnchor>() {
  override fun fromString(value: String): ToolWindowAnchor {
    try {
      return ToolWindowAnchor.fromText(value)
    }
    catch (e: IllegalArgumentException) {
      LOG.warn(e)
      return ToolWindowAnchor.LEFT
    }
  }

  override fun toString(value: ToolWindowAnchor) = value.toString()
}

private fun canActivateOnStart(id: String): Boolean {
  for (ep in ToolWindowEP.EP_NAME.extensionList) {
    if (id == ep.id) {
      return !ep.isDoNotActivateOnStart
    }
  }
  return true
}