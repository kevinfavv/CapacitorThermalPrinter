package com.delicity.thermalprinter.adapters

/**
 * Contrat de réflexion : énumère la **surface exacte** (classes, constructeurs,
 * méthodes, champs) que chaque adapter SDK appelle par réflexion. Sert à deux choses :
 *
 *  1. En CI (faux SDK présent) : un test vérifie que le faux satisfait le contrat
 *     → garde les faux fidèles à ce que l'adapter attend.
 *  2. Avec le VRAI SDK (binaire déposé dans l'app) : appeler [verify] confirme que
 *     l'API réelle correspond à notre réflexion — **sans imprimante**. Tout symbole
 *     manquant est remonté (au lieu d'échouer silencieusement à l'exécution).
 *
 * Usage (dans l'app, après dépôt du binaire) :
 * ```
 * val missing = SdkContract.verify(SdkContract.EPSON)
 * if (missing.isNotEmpty()) Log.e("SDK", "API Epson divergente: $missing")
 * ```
 * Voir docs/TESTING_SDK.md.
 */
object SdkContract {

    data class Method(val name: String, val params: List<String> = emptyList())
    data class Requirement(
        val className: String,
        val constructors: List<List<String>> = emptyList(),
        val methods: List<Method> = emptyList(),
        val fields: List<String> = emptyList(),
    )

    /** Vérifie une liste de requirements ; renvoie les symboles manquants (vide = OK). */
    fun verify(requirements: List<Requirement>): List<String> {
        val missing = mutableListOf<String>()
        for (r in requirements) {
            val cls = try {
                Class.forName(r.className)
            } catch (e: Throwable) {
                missing.add("class ${r.className}")
                continue
            }
            for (c in r.constructors) {
                try {
                    cls.getConstructor(*c.map(::typeOf).toTypedArray())
                } catch (e: Throwable) {
                    missing.add("${r.className}.<init>(${c.joinToString()})")
                }
            }
            for (m in r.methods) {
                try {
                    cls.getMethod(m.name, *m.params.map(::typeOf).toTypedArray())
                } catch (e: Throwable) {
                    missing.add("${r.className}#${m.name}(${m.params.joinToString()})")
                }
            }
            for (f in r.fields) {
                try {
                    cls.getField(f)
                } catch (e: Throwable) {
                    missing.add("${r.className}.$f")
                }
            }
        }
        return missing
    }

    /** Vérifie toutes les marches dont le SDK est présent (les absentes sont ignorées). */
    fun verifyAll(): Map<String, List<String>> = buildMap {
        if (SdkReflect.exists("com.epson.epos2.printer.Printer")) put("epson", verify(EPSON))
        if (SdkReflect.exists("com.zebra.sdk.comm.Connection")) put("zebra", verify(ZEBRA))
        if (SdkReflect.exists("com.brother.sdk.lmprinter.PrinterDriverGenerator")) put("brother", verify(BROTHER))
    }

    private fun typeOf(name: String): Class<*> = when (name) {
        "int" -> Int::class.javaPrimitiveType!!
        "long" -> Long::class.javaPrimitiveType!!
        "double" -> Double::class.javaPrimitiveType!!
        "float" -> Float::class.javaPrimitiveType!!
        "boolean" -> Boolean::class.javaPrimitiveType!!
        else -> Class.forName(name)
    }

    private const val BITMAP = "android.graphics.Bitmap"
    private const val CONTEXT = "android.content.Context"
    private const val BT_ADAPTER = "android.bluetooth.BluetoothAdapter"

    // ------------------------------------------------------------------ Epson
    val EPSON = listOf(
        Requirement(
            "com.epson.epos2.printer.Printer",
            constructors = listOf(listOf("int", "int", CONTEXT)),
            methods = listOf(
                Method("connect", listOf("java.lang.String", "int")),
                Method("disconnect"),
                Method("clearCommandBuffer"),
                Method("beginTransaction"),
                Method("endTransaction"),
                Method("addImage", listOf(BITMAP, "int", "int", "int", "int", "int", "int", "int", "double", "int")),
                Method("addCut", listOf("int")),
                Method("addPulse", listOf("int", "int")),
                Method("sendData", listOf("int")),
                Method("getStatus"),
                Method("addText", listOf("java.lang.String")),
                Method("addTextAlign", listOf("int")),
                Method("addTextStyle", listOf("int", "int", "int", "int")),
                Method("addTextSize", listOf("int", "int")),
                Method("addFeedLine", listOf("int")),
                Method("addSymbol", listOf("java.lang.String", "int", "int", "int", "int", "int")),
                Method("addBarcode", listOf("java.lang.String", "int", "int", "int", "int", "int")),
            ),
            fields = listOf(
                "TRUE", "FALSE", "MODEL_ANK", "PARAM_DEFAULT", "COLOR_1", "MODE_MONO", "COMPRESS_AUTO",
                "HALFTONE_DITHER", "HALFTONE_ERROR_DIFFUSION", "HALFTONE_THRESHOLD",
                "CUT_FEED", "DRAWER_2PIN", "PULSE_100", "PAPER_EMPTY", "PAPER_NEAR_END",
                "ALIGN_LEFT", "ALIGN_CENTER", "ALIGN_RIGHT", "LEVEL_M", "FONT_A",
            ),
        ),
        Requirement("com.epson.epos2.printer.PrinterStatusInfo", fields = listOf("connection", "online", "paper", "coverOpen")),
        Requirement(
            "com.epson.epos2.discovery.Discovery",
            methods = listOf(
                Method("start", listOf(CONTEXT, "com.epson.epos2.discovery.FilterOption", "com.epson.epos2.discovery.DiscoveryListener")),
                Method("stop"),
            ),
            fields = listOf("TYPE_PRINTER"),
        ),
        Requirement("com.epson.epos2.discovery.FilterOption", constructors = listOf(emptyList()), methods = listOf(Method("setDeviceType", listOf("int")))),
        Requirement("com.epson.epos2.discovery.DeviceInfo", methods = listOf(Method("getTarget"), Method("getDeviceName"))),
        Requirement("com.epson.epos2.discovery.DiscoveryListener"),
    )

    // ------------------------------------------------------------------ Zebra
    val ZEBRA = listOf(
        Requirement("com.zebra.sdk.comm.Connection", methods = listOf(Method("open"), Method("close"), Method("isConnected"))),
        Requirement("com.zebra.sdk.comm.TcpConnection", constructors = listOf(listOf("java.lang.String", "int"))),
        Requirement("com.zebra.sdk.comm.BluetoothConnection", constructors = listOf(listOf("java.lang.String"))),
        Requirement("com.zebra.sdk.printer.ZebraPrinterFactory", methods = listOf(Method("getInstance", listOf("com.zebra.sdk.comm.Connection")))),
        Requirement("com.zebra.sdk.graphics.ZebraImageFactory", methods = listOf(Method("getImage", listOf(BITMAP)))),
        Requirement("com.zebra.sdk.graphics.ZebraImageI"),
        Requirement("com.zebra.sdk.printer.discovery.NetworkDiscoverer", methods = listOf(Method("findPrinters", listOf("com.zebra.sdk.printer.discovery.DiscoveryHandler")))),
        Requirement("com.zebra.sdk.printer.discovery.BluetoothDiscoverer", methods = listOf(Method("findPrinters", listOf(CONTEXT, "com.zebra.sdk.printer.discovery.DiscoveryHandler")))),
        Requirement("com.zebra.sdk.printer.discovery.DiscoveryHandler"),
    )

    // ---------------------------------------------------------------- Brother
    val BROTHER = listOf(
        Requirement("com.brother.sdk.lmprinter.PrinterDriverGenerator", methods = listOf(Method("openChannel", listOf("com.brother.sdk.lmprinter.Channel")))),
        Requirement(
            "com.brother.sdk.lmprinter.Channel",
            methods = listOf(
                Method("newWifiChannel", listOf("java.lang.String")),
                Method("newBluetoothChannel", listOf("java.lang.String", BT_ADAPTER)),
                Method("newBluetoothLowEnergyChannel", listOf("java.lang.String", CONTEXT, BT_ADAPTER)),
            ),
        ),
        Requirement("com.brother.sdk.lmprinter.PrinterDriver", methods = listOf(Method("printImage", listOf(BITMAP, "com.brother.sdk.lmprinter.setting.PrintImageSettings")), Method("closeChannel"))),
        Requirement("com.brother.sdk.lmprinter.setting.PrinterModel"),
        Requirement("com.brother.sdk.lmprinter.setting.PrintImageSettings"),
    )
}
