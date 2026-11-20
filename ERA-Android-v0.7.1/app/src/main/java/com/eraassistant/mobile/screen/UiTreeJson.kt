package com.eraassistant.mobile.screen

import org.json.JSONArray
import org.json.JSONObject

object UiTreeJson {
    fun encode(snapshot: UiScreenSnapshot): String = JSONObject().apply {
        put("schemaVersion", "era.ui-tree.v1")
        put("capturedAtEpochMs", snapshot.capturedAtEpochMs)
        put("packageName", snapshot.packageName ?: JSONObject.NULL)
        put("windowTitle", snapshot.windowTitle ?: JSONObject.NULL)
        put("nodeCount", snapshot.nodeCount)
        put("truncated", snapshot.truncated)
        put("selectorGuidance", "Use resourceId/text/contentDescription/role; nodeKey is snapshot-local. Bounds are diagnostic only.")
        put("root", encodeElement(snapshot.root))
    }.toString(2)

    private fun encodeElement(element: UiElement): JSONObject = JSONObject().apply {
        put("elementId", element.nodeKey)
        put("nodeKey", element.nodeKey)
        put("parentElementId", element.parentKey ?: JSONObject.NULL)
        put("parentKey", element.parentKey ?: JSONObject.NULL)
        put("resourceId", element.resourceId ?: JSONObject.NULL)
        put("role", element.role)
        put("text", element.text ?: JSONObject.NULL)
        put("contentDescription", element.contentDescription ?: JSONObject.NULL)
        put("hintText", element.hintText ?: JSONObject.NULL)
        put("className", element.className ?: JSONObject.NULL)
        put("packageName", element.packageName ?: JSONObject.NULL)
        put("clickable", element.clickable)
        put("longClickable", element.longClickable)
        put("editable", element.editable)
        put("focusable", element.focusable)
        put("focused", element.focused)
        put("enabled", element.enabled)
        put("disabled", element.disabled)
        put("selected", element.selected)
        put("checkable", element.checkable)
        put("checked", element.checked)
        put("scrollable", element.scrollable)
        put("password", element.password)
        put("visible", element.visible)
        put("link", element.link)
        put("actions", JSONArray(element.actions))
        put("bounds", JSONObject().apply {
            put("left", element.bounds.left)
            put("top", element.bounds.top)
            put("right", element.bounds.right)
            put("bottom", element.bounds.bottom)
        })
        put("children", JSONArray().apply { element.children.forEach { put(encodeElement(it)) } })
    }
}
