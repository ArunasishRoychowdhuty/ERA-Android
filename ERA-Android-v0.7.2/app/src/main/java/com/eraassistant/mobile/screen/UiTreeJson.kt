package com.eraassistant.mobile.screen

import org.json.JSONArray
import org.json.JSONObject

object UiTreeJson {
    /** Full-fidelity snapshot kept for local action fingerprinting and stale-screen validation. */
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

    /** Compact flattened JSON for AI context; editable values and pixel bounds are excluded. */
    fun encodeCompact(
        snapshot: UiScreenSnapshot,
        maxElements: Int = DEFAULT_MAX_COMPACT_ELEMENTS,
        maxLabelChars: Int = DEFAULT_MAX_LABEL_CHARS
    ): String = compactFromEncoded(encode(snapshot), maxElements, maxLabelChars)

    /** Converts a full v1 snapshot to compact v1. Already-compact JSON is returned unchanged. */
    fun compactFromEncoded(
        snapshotJson: String,
        maxElements: Int = DEFAULT_MAX_COMPACT_ELEMENTS,
        maxLabelChars: Int = DEFAULT_MAX_LABEL_CHARS
    ): String {
        require(maxElements > 0) { "maxElements must be positive." }
        require(maxLabelChars > 0) { "maxLabelChars must be positive." }

        val source = JSONObject(snapshotJson)
        if (source.optString("schemaVersion") == "era.ui-compact.v1") return source.toString()

        val root = source.optJSONObject("root")
            ?: throw IllegalArgumentException("Snapshot does not contain a structured UI root.")
        val safeMaxElements = maxElements.coerceAtMost(MAX_COMPACT_ELEMENTS)
        val safeLabelChars = maxLabelChars.coerceAtMost(MAX_LABEL_CHARS)
        val elements = JSONArray()
        var limitReached = false

        fun visit(node: JSONObject, inheritedParentRef: String?, depth: Int) {
            if (depth > MAX_TREE_DEPTH || elements.length() >= safeMaxElements) {
                limitReached = true
                return
            }

            val visible = node.optBoolean("visible", true)
            val password = node.optBoolean("password", false)
            val editable = node.optBoolean("editable", false)
            val clickable = node.optBoolean("clickable", false)
            val scrollable = node.optBoolean("scrollable", false)
            val checkable = node.optBoolean("checkable", false)
            val role = node.safeString("role") ?: "unknown"
            val resourceId = node.safeString("resourceId")
            val text = node.safeString("text")
            val description = node.safeString("contentDescription")
            val hint = node.safeString("hintText")

            // Editable text is a value, not a label. Export only its hint or accessible label.
            val rawLabel = if (editable) {
                hint ?: resourceId?.substringAfterLast(":id/")
            } else {
                text ?: description ?: hint
            }
            val actionable = editable || clickable || scrollable || checkable ||
                node.optBoolean("link", false)
            val include = visible && !password && (actionable || !rawLabel.isNullOrBlank())

            var childParentRef = inheritedParentRef
            if (include) {
                val ref = "e" + (elements.length() + 1)
                val item = JSONObject().apply {
                    put("ref", ref)
                    put("parentRef", inheritedParentRef ?: JSONObject.NULL)
                    put("type", role)
                    put("label", rawLabel?.take(safeLabelChars) ?: role)
                    if (!resourceId.isNullOrBlank()) put("resourceId", resourceId)
                    put("clickable", clickable)
                    put("editable", editable)
                    put("enabled", node.optBoolean("enabled", true))
                    put("selected", node.optBoolean("selected", false))
                    put("checked", node.optBoolean("checked", false))
                    put("checkable", checkable)
                    put("scrollable", scrollable)
                    put("focused", node.optBoolean("focused", false))
                    if (editable) put("valueOmitted", true)
                    val actions = node.optJSONArray("actions")
                    if (actions != null && actions.length() > 0) put("actions", actions)
                }
                elements.put(item)
                childParentRef = ref
            }

            val children = node.optJSONArray("children") ?: return
            for (index in 0 until children.length()) {
                if (elements.length() >= safeMaxElements) {
                    limitReached = true
                    break
                }
                val child = children.optJSONObject(index) ?: continue
                visit(child, childParentRef, depth + 1)
            }
        }

        visit(root, null, 0)
        return JSONObject().apply {
            put("schemaVersion", "era.ui-compact.v1")
            put("packageName", source.safeString("packageName") ?: JSONObject.NULL)
            put("screenTitle", source.safeString("windowTitle") ?: JSONObject.NULL)
            put("sourceNodeCount", source.optInt("nodeCount", elements.length()))
            put("elementCount", elements.length())
            put("truncated", source.optBoolean("truncated", false) || limitReached)
            put("selectorGuidance", "Use resourceId, label, type and state. ref is snapshot-local. No coordinates are provided. Editable values are omitted.")
            put("elements", elements)
        }.toString()
    }

    private fun JSONObject.safeString(key: String): String? {
        val value = opt(key) ?: return null
        if (value == JSONObject.NULL) return null
        return value.toString().takeIf { it.isNotBlank() && it != "null" }
    }

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

    private const val DEFAULT_MAX_COMPACT_ELEMENTS = 160
    private const val DEFAULT_MAX_LABEL_CHARS = 120
    private const val MAX_COMPACT_ELEMENTS = 400
    private const val MAX_LABEL_CHARS = 240
    private const val MAX_TREE_DEPTH = 40
}
