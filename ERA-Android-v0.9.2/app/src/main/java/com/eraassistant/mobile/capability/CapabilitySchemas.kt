package com.eraassistant.mobile.capability

enum class CapabilityParameterType { STRING, BOOLEAN, INTEGER, NUMBER }

data class CapabilityParameterSchema(
    val type: CapabilityParameterType,
    val required: Boolean = false,
    val maxLength: Int = 2_000,
    val minNumber: Double? = null,
    val maxNumber: Double? = null,
    val allowedValues: Set<String> = emptySet()
)

data class CapabilityActionSchema(
    val parameters: Map<String, CapabilityParameterSchema> = emptyMap(),
    val allowAdditionalParameters: Boolean = false,
    val maxParameters: Int = 16
) {
    init {
        require(parameters.size <= 32)
        require(maxParameters in 0..32)
        require(parameters.values.all { it.maxLength in 1..8_192 })
    }
}

object CapabilitySchemas {
    private fun str(required: Boolean = false, max: Int = 2_000, values: Set<String> = emptySet()) =
        CapabilityParameterSchema(CapabilityParameterType.STRING, required, max, allowedValues = values)
    private fun bool(required: Boolean = false) =
        CapabilityParameterSchema(CapabilityParameterType.BOOLEAN, required)
    private fun int(required: Boolean = false, min: Double? = null, max: Double? = null) =
        CapabilityParameterSchema(CapabilityParameterType.INTEGER, required, minNumber = min, maxNumber = max)

    fun androidDevice() = mapOf(
        "device.torch.set" to CapabilityActionSchema(mapOf("enabled" to bool(true))),
        "device.volume.adjust" to CapabilityActionSchema(mapOf(
            "increase" to bool(), "direction" to str(max = 8, values = setOf("UP", "DOWN")),
            "stream" to str(max = 16, values = setOf("MEDIA", "RINGTONE", "NOTIFICATION", "ALARM"))
        )),
        "device.volume.set_percent" to CapabilityActionSchema(mapOf(
            "percent" to int(true, 0.0, 100.0),
            "stream" to str(max = 16, values = setOf("MEDIA", "RINGTONE", "NOTIFICATION", "ALARM"))
        )),
        "device.app.launch" to CapabilityActionSchema(mapOf("appName" to str(max = 120), "packageName" to str(max = 200))),
        "device.settings.open" to CapabilityActionSchema(mapOf("settingsAction" to str(max = 160), "label" to str(max = 120))),
        "device.camera.open" to CapabilityActionSchema(),
        "device.dialer.open" to CapabilityActionSchema(mapOf("phoneNumber" to str(max = 40))),
        "device.sms.create_draft" to CapabilityActionSchema(mapOf("message" to str(true, 4_000), "phoneNumber" to str(max = 40))),
        "device.whatsapp.share_draft" to CapabilityActionSchema(mapOf("message" to str(true, 4_000), "phoneNumber" to str(max = 40))),
        "device.web.search" to CapabilityActionSchema(mapOf("query" to str(true, 500))),
        "device.maps.search" to CapabilityActionSchema(mapOf("query" to str(true, 500))),
        "device.website.open" to CapabilityActionSchema(mapOf("url" to str(true, 2_048)))
    )

    fun nativeWebView() = mapOf(
        "browser.webview.navigate" to CapabilityActionSchema(mapOf("url" to str(true, 2_048), "targetId" to str(max = 160))),
        "browser.webview.snapshot" to CapabilityActionSchema(mapOf("includePrivate" to bool(), "targetId" to str(max = 160))),
        "browser.webview.click" to CapabilityActionSchema(mapOf("ref" to str(true, 160), "targetId" to str(max = 160))),
        "browser.webview.fill" to CapabilityActionSchema(mapOf("ref" to str(true, 160), "text" to str(true, 4_000), "targetId" to str(max = 160))),
        "browser.webview.type" to CapabilityActionSchema(mapOf("ref" to str(true, 160), "text" to str(true, 4_000), "targetId" to str(max = 160))),
        "browser.webview.press_key" to CapabilityActionSchema(mapOf("ref" to str(true, 160), "key" to str(max = 32), "targetId" to str(max = 160))),
        "browser.webview.check" to CapabilityActionSchema(mapOf("ref" to str(true, 160), "targetId" to str(max = 160))),
        "browser.webview.uncheck" to CapabilityActionSchema(mapOf("ref" to str(true, 160), "targetId" to str(max = 160))),
        "browser.webview.select_option" to CapabilityActionSchema(mapOf("ref" to str(true, 160), "value" to str(true, 1_000), "targetId" to str(max = 160))),
        "browser.webview.scroll_into_view" to CapabilityActionSchema(mapOf("ref" to str(true, 160), "targetId" to str(max = 160))),
        "browser.webview.tab.new" to CapabilityActionSchema(mapOf("url" to str(max = 2_048))),
        "browser.webview.tab.close" to CapabilityActionSchema(mapOf("targetId" to str(true, 160))),
        "browser.webview.tab.switch" to CapabilityActionSchema(mapOf("targetId" to str(true, 160))),
        "browser.webview.reload" to CapabilityActionSchema(mapOf("targetId" to str(max = 160))),
        "browser.webview.go_back" to CapabilityActionSchema(mapOf("targetId" to str(max = 160))),
        "browser.webview.go_forward" to CapabilityActionSchema(mapOf("targetId" to str(max = 160)))
    )

    fun accessibilityUi() = mapOf(
        "accessibility.screen.capture" to CapabilityActionSchema(),
        "accessibility.ui.approve_click" to CapabilityActionSchema(mapOf("selector" to str(true, 300), "expectedPackage" to str(max = 200))),
        "accessibility.ui.approve_type" to CapabilityActionSchema(mapOf("selector" to str(true, 300), "text" to str(true, 4_000), "expectedPackage" to str(max = 200))),
        "accessibility.ui.approve_scroll" to CapabilityActionSchema(mapOf(
            "selector" to str(max = 300), "expectedPackage" to str(max = 200), "forward" to bool()
        )),
        "accessibility.ui.cancel_pending" to CapabilityActionSchema(mapOf("reason" to str(max = 300)))
    )

    fun externalChrome() = mapOf(
        "browser.chrome.inspect_visible_tree" to CapabilityActionSchema(),
        "browser.chrome.approve_click" to CapabilityActionSchema(mapOf("selector" to str(true, 300))),
        "browser.chrome.approve_type" to CapabilityActionSchema(mapOf("selector" to str(true, 300), "text" to str(true, 4_000))),
        "browser.chrome.approve_scroll" to CapabilityActionSchema(mapOf("selector" to str(max = 300), "forward" to bool()))
    )

    fun visualInspection() = mapOf(
        "screen.visual.inspect_frame" to CapabilityActionSchema(),
        "screen.visual.clear_frame" to CapabilityActionSchema()
    )

    fun searchAndRecognition() = mapOf(
        "search.shopping.build_url" to CapabilityActionSchema(mapOf("query" to str(true, 300))),
        "search.shopping.launch" to CapabilityActionSchema(mapOf("query" to str(true, 300)))
    )

    fun fileStorage() = mapOf(
        "storage.tree.list" to CapabilityActionSchema(mapOf(
            "parentDocumentId" to str(max = 1_024),
            "limit" to int(min = 1.0, max = 100.0)
        )),
        "storage.document.read" to CapabilityActionSchema(mapOf(
            "documentId" to str(true, 1_024)
        ))
    )
}
