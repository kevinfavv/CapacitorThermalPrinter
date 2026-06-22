package com.delicity.thermalprinter.model

import org.json.JSONArray
import org.json.JSONObject

/** Transports physiques. Doit rester aligné avec PrinterTransport (TypeScript). */
enum class Transport(val value: String) {
    WIFI("wifi"),
    ETHERNET("ethernet"),
    BLUETOOTH("bluetooth"),
    BLE("ble"),
    USB("usb");

    companion object {
        fun from(v: String?): Transport =
            entries.firstOrNull { it.value == v } ?: WIFI
    }
}

/** Identifiants d'adapter. Aligné avec PrinterAdapterId (TypeScript). */
enum class AdapterId(val value: String) {
    ESCPOS("escpos"),
    EPSON("epson"),
    STAR("star"),
    BROTHER("brother"),
    ZEBRA("zebra"),
    RAW_TCP("rawTcp");

    /** Vrai si l'adapter est un SDK officiel fabricant (et non le natif générique). */
    val isSdk: Boolean
        get() = this == EPSON || this == STAR || this == BROTHER || this == ZEBRA

    companion object {
        fun from(v: String?): AdapterId =
            entries.firstOrNull { it.value == v } ?: ESCPOS
    }
}

/** Codes d'erreur normalisés. Aligné avec PrintErrorCode (TypeScript). */
enum class ErrorCode {
    PRINTER_NOT_FOUND, PRINTER_OFFLINE, CONNECTION_FAILED, PERMISSION_DENIED,
    BLUETOOTH_DISABLED, WIFI_NOT_CONNECTED, PAIRING_REQUIRED, UNSUPPORTED_TRANSPORT,
    UNSUPPORTED_PRINTER, IMAGE_INVALID, IMAGE_TOO_LARGE, PRINT_FAILED, PAPER_EMPTY,
    COVER_OPEN, SDK_NOT_AVAILABLE, TIMEOUT, UNKNOWN
}

/** Exception interne portant un code normalisé, convertie en rejet Capacitor. */
class PrinterException(
    val code: ErrorCode,
    message: String,
    val detail: String? = null,
    val retryable: Boolean = false,
) : Exception(message)

data class Capabilities(
    val paperWidthMm: Int = 80,
    val printableDots: Int = 576,
    val dpi: Int = 203,
    val supportsCut: Boolean = true,
    val supportsCashDrawer: Boolean = false,
    val supportsStatus: Boolean = false,
    val supportsRasterImage: Boolean = true,
    val supportsQrCode: Boolean = false,
    val supportsBarcode: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("paperWidthMm", paperWidthMm)
        .put("printableDots", printableDots)
        .put("dpi", dpi)
        .put("supportsCut", supportsCut)
        .put("supportsCashDrawer", supportsCashDrawer)
        .put("supportsStatus", supportsStatus)
        .put("supportsRasterImage", supportsRasterImage)
        .put("supportsQrCode", supportsQrCode)
        .put("supportsBarcode", supportsBarcode)

    companion object {
        fun fromJson(o: JSONObject?): Capabilities {
            if (o == null) return Capabilities()
            return Capabilities(
                paperWidthMm = o.optInt("paperWidthMm", 80),
                printableDots = o.optInt("printableDots", 576),
                dpi = o.optInt("dpi", 203),
                supportsCut = o.optBoolean("supportsCut", true),
                supportsCashDrawer = o.optBoolean("supportsCashDrawer", false),
                supportsStatus = o.optBoolean("supportsStatus", false),
                supportsRasterImage = o.optBoolean("supportsRasterImage", true),
                supportsQrCode = o.optBoolean("supportsQrCode", false),
                supportsBarcode = o.optBoolean("supportsBarcode", false),
            )
        }
    }
}

/** Imprimante découverte/normalisée. */
data class DiscoveredPrinter(
    val id: String,
    val name: String,
    val brand: String? = null,
    val model: String? = null,
    val transport: Transport,
    val adapter: AdapterId,
    val address: String,
    val capabilities: Capabilities? = null,
    val discoveredBy: MutableSet<AdapterId> = mutableSetOf(),
    var lastSeenAt: Long = System.currentTimeMillis(),
    var isDefault: Boolean = false,
    var isConnected: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        brand?.let { put("brand", it) }
        model?.let { put("model", it) }
        put("transport", transport.value)
        put("adapter", adapter.value)
        put("address", address)
        capabilities?.let { put("capabilities", it.toJson()) }
        put("discoveredBy", JSONArray(discoveredBy.map { it.value }))
        put("isSdk", adapter.isSdk)
        put("lastSeenAt", lastSeenAt)
        put("isDefault", isDefault)
        put("isConnected", isConnected)
    }
}

/** Profil persistant. */
data class PrinterProfile(
    val id: String,
    val adapter: AdapterId,
    val transport: Transport,
    val address: String,
    val brand: String?,
    val model: String?,
    val name: String,
    val capabilities: Capabilities,
    val adapterMeta: JSONObject = JSONObject(),
    var isDefault: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("adapter", adapter.value)
        put("transport", transport.value)
        put("address", address)
        brand?.let { put("brand", it) }
        model?.let { put("model", it) }
        put("name", name)
        put("capabilities", capabilities.toJson())
        put("adapterMeta", adapterMeta)
        put("isDefault", isDefault)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(o: JSONObject): PrinterProfile = PrinterProfile(
            id = o.getString("id"),
            adapter = AdapterId.from(o.optString("adapter")),
            transport = Transport.from(o.optString("transport")),
            address = o.getString("address"),
            brand = o.optString("brand").ifEmpty { null },
            model = o.optString("model").ifEmpty { null },
            name = o.optString("name"),
            capabilities = Capabilities.fromJson(o.optJSONObject("capabilities")),
            adapterMeta = o.optJSONObject("adapterMeta") ?: JSONObject(),
            isDefault = o.optBoolean("isDefault", false),
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
        )
    }
}

/** Statut temps réel. */
data class PrinterStatus(
    val id: String,
    val connection: String, // disconnected|connecting|connected|error
    val online: Boolean,
    val paper: String, // ok|near_end|empty|unknown
    val coverOpen: Boolean? = null,
    val errorCode: ErrorCode? = null,
    val rawStatus: String? = null,
    val checkedAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("connection", connection)
        put("online", online)
        put("paper", paper)
        coverOpen?.let { put("coverOpen", it) }
        errorCode?.let { put("errorCode", it.name) }
        rawStatus?.let { put("rawStatus", it) }
        put("checkedAt", checkedAt)
    }
}

/** Options de rendu résolues passées à l'adapter. */
data class RenderOptions(
    val widthDots: Int,
    /** Largeur papier en mm (58/80/112) ; convertie en points si `widthDots` absent. 0 = non fourni. */
    val paperWidthMm: Int = 0,
    val resize: Boolean = true,
    val grayscale: Boolean = true,
    val threshold: Int = 128,
    val dithering: String = "floyd_steinberg", // none|floyd_steinberg|atkinson
    val align: String = "center", // left|center|right
    val invert: Boolean = false,
    val cut: Boolean = true,
    val feedLines: Int = 3,
    val openCashDrawer: Boolean = false,
    val copies: Int = 1,
)
