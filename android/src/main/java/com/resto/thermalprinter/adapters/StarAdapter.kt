package com.resto.thermalprinter.adapters

import android.content.Context
import android.graphics.Bitmap
import com.resto.thermalprinter.model.AdapterId
import com.resto.thermalprinter.model.DiscoveredPrinter
import com.resto.thermalprinter.model.ErrorCode
import com.resto.thermalprinter.model.PrinterException
import com.resto.thermalprinter.model.PrinterProfile
import com.resto.thermalprinter.model.PrinterStatus
import com.resto.thermalprinter.model.RenderOptions

/**
 * Adapter Star basé sur StarXpand SDK (com.starmicronics.stario10) — recommandé —
 * ou StarIO10 selon disponibilité.
 *
 * StarXpand expose :
 *   - StarDeviceDiscoveryManager (LAN, Bluetooth, BLE, USB),
 *   - StarXpandCommand.PrinterBuilder().actionPrintImage(...) pour l'image,
 *   - getStatus() pour papier/capot/massicot.
 *
 * INTÉGRATION : déposer le .aar / ajouter la dépendance Maven Star, puis activer
 * le pseudo-code ci-dessous. isAvailable() protège contre l'absence du SDK.
 */
class StarAdapter(private val context: Context) : PrinterAdapter {

    override val id = AdapterId.STAR

    override fun isAvailable(): Boolean =
        EpsonAdapter.classExists("com.starmicronics.stario10.StarPrinter")

    override suspend fun discover(timeoutMs: Long, onFound: (DiscoveredPrinter) -> Unit) {
        if (!isAvailable()) return
        /*
         * val manager = StarDeviceDiscoveryManagerFactory.create(
         *     listOf(InterfaceType.Lan, InterfaceType.Bluetooth, InterfaceType.BluetoothLE, InterfaceType.Usb),
         *     context,
         * )
         * manager.discoveryTime = timeoutMs.toInt()
         * manager.callback = object : StarDeviceDiscoveryManager.Callback {
         *     override fun onPrinterFound(printer: StarPrinter) {
         *         onFound(DiscoveredPrinter(
         *             id = buildStableId(transportFor(printer.connectionSettings.interfaceType), printer.connectionSettings.identifier),
         *             name = printer.information?.model?.name ?: "Star",
         *             brand = "Star", model = printer.information?.model?.name,
         *             transport = transportFor(...), adapter = AdapterId.STAR,
         *             address = printer.connectionSettings.identifier,
         *             discoveredBy = mutableSetOf(AdapterId.STAR),
         *         ))
         *     }
         *     override fun onDiscoveryFinished() {}
         * }
         * manager.startDiscovery()
         */
    }

    override fun canHandle(profile: PrinterProfile): Boolean =
        isAvailable() && profile.adapter == AdapterId.STAR

    override suspend fun connect(profile: PrinterProfile, timeoutMs: Long) {
        ensureSdk()
        /* val printer = StarPrinter(connectionSettingsFrom(profile), context); printer.openAsync().await() */
    }

    override fun isConnected(printerId: String): Boolean = false

    override suspend fun disconnect(printerId: String) { /* printer.closeAsync().await() */ }

    override suspend fun printBitmap(profile: PrinterProfile, bitmap: Bitmap, options: RenderOptions): Int {
        ensureSdk()
        /*
         * val builder = StarXpandCommandBuilder()
         * builder.addDocument(DocumentBuilder().addPrinter(
         *     PrinterBuilder()
         *         .actionPrintImage(ImageParameter(bitmap, profile.capabilities.printableDots))
         *         .actionFeed(options.feedLines.toDouble())
         *         .also { if (options.cut) it.actionCut(CutType.Partial) }
         * ))
         * val commands = builder.getCommands()
         * printer.printAsync(commands).await()
         */
        throw PrinterException(ErrorCode.SDK_NOT_AVAILABLE, "Star SDK non intégré")
    }

    override suspend fun getStatus(profile: PrinterProfile): PrinterStatus {
        ensureSdk()
        throw PrinterException(ErrorCode.SDK_NOT_AVAILABLE, "Star SDK non intégré")
    }

    private fun ensureSdk() {
        if (!isAvailable()) throw PrinterException(ErrorCode.SDK_NOT_AVAILABLE, "SDK Star absent")
    }
}
