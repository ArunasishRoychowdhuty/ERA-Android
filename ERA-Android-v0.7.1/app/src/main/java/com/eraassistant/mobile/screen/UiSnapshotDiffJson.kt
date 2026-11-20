package com.eraassistant.mobile.screen

import org.json.JSONArray
import org.json.JSONObject

object UiSnapshotDiffJson {
    fun encode(diff: UiSnapshotDiff): String = JSONObject().apply {
        put("schemaVersion", "era.ui-diff.v1")
        put("addedCount", diff.added.size)
        put("removedCount", diff.removed.size)
        put("changedCount", diff.changed.size)
        put("added", JSONArray().apply { diff.added.forEach { put(change(it)) } })
        put("removed", JSONArray().apply { diff.removed.forEach { put(change(it)) } })
        put("changed", JSONArray().apply { diff.changed.forEach { put(change(it)) } })
    }.toString(2)

    private fun change(value: UiElementChange): JSONObject = JSONObject().apply {
        put("identity", value.identity)
        put("beforeNodeKey", value.before?.nodeKey ?: JSONObject.NULL)
        put("afterNodeKey", value.after?.nodeKey ?: JSONObject.NULL)
        put("beforeText", value.before?.text ?: JSONObject.NULL)
        put("afterText", value.after?.text ?: JSONObject.NULL)
        put("beforeEnabled", value.before?.enabled ?: JSONObject.NULL)
        put("afterEnabled", value.after?.enabled ?: JSONObject.NULL)
        put("beforeClickable", value.before?.clickable ?: JSONObject.NULL)
        put("afterClickable", value.after?.clickable ?: JSONObject.NULL)
    }
}
