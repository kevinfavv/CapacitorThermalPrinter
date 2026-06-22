package com.delicity.thermalprinter.adapters

import android.content.Context
import android.graphics.Bitmap
import com.delicity.thermalprinter.image.TextRasterizer
import com.delicity.thermalprinter.model.AdapterId
import com.delicity.thermalprinter.model.DiscoveredPrinter
import com.delicity.thermalprinter.model.ErrorCode
import com.delicity.thermalprinter.model.PrinterException
import com.delicity.thermalprinter.model.PrinterProfile
import com.delicity.thermalprinter.model.PrinterStatus
import com.delicity.thermalprinter.model.RenderOptions
import com.delicity.thermalprinter.model.Transport
import com.starmicronics.stario10.InterfaceType
import com.starmicronics.stario10.StarConnectionSettings
import com.starmicronics.stario10.StarDeviceDiscoveryManager
import com.starmicronics.stario10.StarDeviceDiscoveryManagerFactory
import com.starmicronics.stario10.StarPrinter
import com.starmicronics.stario10.starxpandcommand.DocumentBuilder
import com.starmicronics.stario10.starxpandcommand.DrawerBuilder
import com.starmicronics.stario10.starxpandcommand.PrinterBuilder
import com.starmicronics.stario10.starxpandcommand.StarXpandCommandBuilder
import com.starmicronics.stario10.starxpandcommand.drawer.Channel
import com.starmicronics.stario10.starxpandcommand.drawer.OpenParameter
import com.starmicronics.stario10.starxpandcommand.printer.Alignment
import com.starmicronics.stario10.starxpandcommand.printer.BarcodeParameter
import com.starmicronics.stario10.starxpandcommand.printer.BarcodeSymbology
import com.starmicronics.stario10.starxpandcommand.printer.CutType
import com.starmicronics.stario10.starxpandcommand.MagnificationParameter
import com.starmicronics.stario10.starxpandcommand.printer.ImageParameter
import com.starmicronics.stario10.starxpandcommand.printer.QRCodeLevel
import com.starmicronics.stario10.starxpandcommand.printer.QRCodeParameter
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Adapter Star basé sur le StarXpand SDK (`com.starmicronics:stario10`).
 *
 * ⭐ Star est le SEUL fabricant dont le SDK est 100 % auto-téléchargé (Maven
 * Central) : cet adapter utilise donc des appels TYPÉS directs (pas de réflexion).
 * Si la dépendance Maven est retirée du build, `isAvailable()` renvoie false par
 * réflexion et l'adapter est ignoré.
 *
 * StarXpand expose :
 *   - StarDeviceDiscoveryManager (LAN, Bluetooth, BLE, USB),
 *   - StarXpandCommandBuilder + PrinterBuilder.actionPrintImage(...) pour l'image,
 *   - PrinterBuilder.actionPrintText / QRCode / Barcode pour le texte stylé,
 *   - getStatusAsync() pour papier/capot/massicot.
 */
class StarAdapter(private val context: Context) : PrinterAdapter {

    override val id = AdapterId.STAR

    /** Connexions ouvertes indexées par printerId. */
    private val connections = ConcurrentHashMap<String, StarPrinter>()

    override fun isAvailable(): Boolean =
        EpsonAdapter.classExists("com.starmicronics.stario10.StarPrinter")

    override fun supportsTextItems(): Boolean = true

    // -------------------------------------------------------------------------
    // Découverte
    // -------------------------------------------------------------------------

    override suspend fun discover(timeoutMs: Long, onFound: (DiscoveredPrinter) -> Unit) {
        if (!isAvailable()) return
        val manager = StarDeviceDiscoveryManagerFactory.create(
            listOf(
                InterfaceType.Lan,
                InterfaceType.Bluetooth,
                InterfaceType.BluetoothLE,
                InterfaceType.Usb,
            ),
            context,
        )
        manager.discoveryTime = timeoutMs.toInt().coerceIn(1000, 30000)

        suspendCancellableCoroutine<Unit> { cont ->
            manager.callback = object : StarDeviceDiscoveryManager.Callback {
                override fun onPrinterFound(printer: StarPrinter) {
                    val settings = printer.connectionSettings
                    val transport = transportFor(settings.interfaceType)
                    val model = printer.information?.model?.name
                    onFound(
                        DiscoveredPrinter(
                            id = "star:${settings.interfaceType}:${settings.identifier}",
                            name = model ?: "Star Printer",
                            brand = "Star",
                            model = model,
                            transport = transport,
                            adapter = AdapterId.STAR,
                            address = settings.identifier,
                            discoveredBy = mutableSetOf(AdapterId.STAR),
                        ),
                    )
                }

                override fun onDiscoveryFinished() {
                    if (cont.isActive) cont.resume(Unit)
                }
            }
            cont.invokeOnCancellation { runCatching { manager.stopDiscovery() } }
            runCatching { manager.startDiscovery() }
                .onFailure { if (cont.isActive) cont.resume(Unit) }
        }
        runCatching { manager.stopDiscovery() }
    }

    override fun canHandle(profile: PrinterProfile): Boolean =
        isAvailable() && profile.adapter == AdapterId.STAR

    // -------------------------------------------------------------------------
    // Connexion
    // -------------------------------------------------------------------------

    override suspend fun connect(profile: PrinterProfile, timeoutMs: Long) {
        ensureSdk()
        if (isConnected(profile.id)) return
        val printer = StarPrinter(connectionSettingsFor(profile), context)
        try {
            printer.openAsync().await()
        } catch (e: Exception) {
            throw PrinterException(ErrorCode.CONNECTION_FAILED, "Connexion Star échouée: ${profile.address}", e.message, retryable = true)
        }
        connections[profile.id] = printer
    }

    override fun isConnected(printerId: String): Boolean = connections.containsKey(printerId)

    override suspend fun disconnect(printerId: String) {
        connections.remove(printerId)?.let { runCatching { it.closeAsync().await() } }
    }

    // -------------------------------------------------------------------------
    // Impression
    // -------------------------------------------------------------------------

    override suspend fun printBitmap(profile: PrinterProfile, bitmap: Bitmap, options: RenderOptions): Int {
        val printer = requireConnected(profile)
        val printerDoc = PrinterBuilder()
            .styleAlignment(alignmentFor(options.align))
            .actionPrintImage(ImageParameter(bitmap, profile.capabilities.printableDots.coerceAtLeast(8)))
        if (options.feedLines > 0) printerDoc.actionFeedLine(options.feedLines)
        if (options.cut && profile.capabilities.supportsCut) printerDoc.actionCut(CutType.Partial)

        val document = DocumentBuilder().addPrinter(printerDoc)
        if (options.openCashDrawer && profile.capabilities.supportsCashDrawer) {
            document.addDrawer(DrawerBuilder().actionOpen(OpenParameter().setChannel(Channel.No1)))
        }
        val commands = StarXpandCommandBuilder().addDocument(document).getCommands()

        var sent = 0
        repeat(options.copies.coerceAtLeast(1)) {
            sendCommands(printer, commands, profile)
            sent += commands.length
        }
        return sent
    }

    override suspend fun printItems(
        profile: PrinterProfile,
        items: List<com.delicity.thermalprinter.model.PrintItem>,
        defaultCodePage: String,
        cut: Boolean,
        feedLines: Int,
    ): Int {
        val printer = requireConnected(profile)
        val pb = PrinterBuilder()
        if (isRasterOnly(profile.model)) {
            // Famille TSP100 (TSP100/TSP143/III/IV) : GRAPHICS-ONLY, sans police interne ->
            // actionPrintText n'imprime RIEN (seuls QR/image rasterisés sortent). On rend donc
            // tous les items en image et on imprime via actionPrintImage. Réf. StarXpand FAQ "TSP100".
            val width = if (profile.capabilities.printableDots > 0) profile.capabilities.printableDots else 576
            val bmp = TextRasterizer.render(items, width)
            pb.styleAlignment(alignmentFor("left")).actionPrintImage(ImageParameter(bmp, width.coerceAtLeast(8)))
        } else {
            for (item in items) mapItem(pb, item, profile)
        }
        if (feedLines > 0) pb.actionFeedLine(feedLines)
        if (cut && profile.capabilities.supportsCut) pb.actionCut(CutType.Partial)
        val commands = StarXpandCommandBuilder()
            .addDocument(DocumentBuilder().addPrinter(pb))
            .getCommands()
        sendCommands(printer, commands, profile)
        return commands.length
    }

    /** Modèles Star « graphics-only » (famille TSP100) : actionPrintText n'imprime rien -> image. */
    private fun isRasterOnly(model: String?): Boolean {
        val m = model?.uppercase() ?: return false
        return m.contains("TSP100") || m.contains("TSP143")
    }

    /** Mappe un PrintItem vers le builder StarXpand (best effort par type). */
    private fun mapItem(pb: PrinterBuilder, item: com.delicity.thermalprinter.model.PrintItem, profile: PrinterProfile) {
        when (item) {
            is com.delicity.thermalprinter.model.PrintItem.Text -> {
                item.style.align?.let { pb.styleAlignment(alignmentFor(it)) }
                pb.styleBold(item.style.bold)
                pb.styleInvert(item.style.invert)
                pb.styleUnderLine(item.style.underline != "none")
                pb.styleMagnification(MagnificationParameter(item.style.widthMultiplier.coerceIn(1, 6), item.style.heightMultiplier.coerceIn(1, 6)))
                if (item.style.newline) pb.actionPrintText(item.value + "\n") else pb.actionPrintText(item.value)
                // reset styles pour ne pas contaminer les items suivants
                pb.styleBold(false).styleInvert(false).styleUnderLine(false)
                    .styleMagnification(MagnificationParameter(1, 1))
            }
            is com.delicity.thermalprinter.model.PrintItem.Feed -> pb.actionFeedLine(item.lines.coerceAtLeast(1))
            is com.delicity.thermalprinter.model.PrintItem.Cut ->
                pb.actionCut(if (item.mode == "full") CutType.Full else CutType.Partial)
            is com.delicity.thermalprinter.model.PrintItem.Divider -> {
                val cols = item.columns ?: if (profile.capabilities.printableDots <= 420) 32 else 48
                item.align?.let { pb.styleAlignment(alignmentFor(it)) }
                pb.styleBold(item.bold)
                pb.actionPrintText(item.char.repeat(cols.coerceIn(1, 96)) + "\n")
                pb.styleBold(false)
            }
            is com.delicity.thermalprinter.model.PrintItem.QrCode -> {
                pb.styleAlignment(alignmentFor(item.align))
                pb.actionPrintQRCode(
                    QRCodeParameter(item.value)
                        .setLevel(qrLevelFor(item.ec))
                        .setCellSize(item.size.coerceIn(1, 16)),
                )
            }
            is com.delicity.thermalprinter.model.PrintItem.Barcode -> {
                pb.styleAlignment(alignmentFor(item.align))
                pb.actionPrintBarcode(
                    BarcodeParameter(item.value, barcodeSymbologyFor(item.symbology))
                        .setHeight(item.height.coerceIn(1, 255).toDouble())
                        .setPrintHri(item.hri != "none"),
                )
            }
            is com.delicity.thermalprinter.model.PrintItem.CashDrawer -> {
                // Le tiroir est géré au niveau document ; ignoré ici (voir openCashDrawer).
            }
            is com.delicity.thermalprinter.model.PrintItem.Image -> {
                // Les images inline dans printText ne sont pas pré-rendues ici ;
                // l'app doit utiliser printImage pour un rendu maîtrisé.
            }
            is com.delicity.thermalprinter.model.PrintItem.Raw -> {
                // StarXpand n'expose pas d'injection ESC/POS brute fiable -> ignoré.
            }
        }
    }

    private suspend fun sendCommands(printer: StarPrinter, commands: String, profile: PrinterProfile) {
        try {
            printer.printAsync(commands).await()
        } catch (e: Exception) {
            throw PrinterException(ErrorCode.PRINT_FAILED, "Impression Star échouée", e.message, retryable = true)
        }
    }

    // -------------------------------------------------------------------------
    // Statut
    // -------------------------------------------------------------------------

    override suspend fun getStatus(profile: PrinterProfile): PrinterStatus {
        val printer = connections[profile.id]
            ?: return PrinterStatus(profile.id, "disconnected", online = false, paper = "unknown")
        return try {
            val st = printer.getStatusAsync().await()
            val paperEmpty = runCatching { st.paperEmpty }.getOrDefault(false)
            val paperNear = runCatching { st.paperNearEmpty }.getOrDefault(false)
            val coverOpen = runCatching { st.coverOpen }.getOrDefault(false)
            PrinterStatus(
                id = profile.id,
                connection = "connected",
                online = !paperEmpty && !coverOpen,
                paper = if (paperEmpty) "empty" else if (paperNear) "near_end" else "ok",
                coverOpen = coverOpen,
                errorCode = if (paperEmpty) ErrorCode.PAPER_EMPTY else if (coverOpen) ErrorCode.COVER_OPEN else null,
                rawStatus = st.toString(),
            )
        } catch (e: Exception) {
            PrinterStatus(profile.id, "error", online = false, paper = "unknown", rawStatus = e.message)
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun requireConnected(profile: PrinterProfile): StarPrinter =
        connections[profile.id] ?: throw PrinterException(ErrorCode.CONNECTION_FAILED, "Star non connecté: ${profile.id}")

    private fun connectionSettingsFor(profile: PrinterProfile): StarConnectionSettings {
        val iface = when (profile.transport) {
            Transport.WIFI, Transport.ETHERNET -> InterfaceType.Lan
            Transport.BLUETOOTH -> InterfaceType.Bluetooth
            Transport.BLE -> InterfaceType.BluetoothLE
            Transport.USB -> InterfaceType.Usb
        }
        // Pour le LAN, l'identifier Star est l'IP (sans port).
        val identifier = if (iface == InterfaceType.Lan) profile.address.substringBefore(":") else profile.address
        return StarConnectionSettings(iface, identifier)
    }

    private fun transportFor(iface: InterfaceType): Transport = when (iface) {
        InterfaceType.Lan -> Transport.WIFI
        InterfaceType.Bluetooth -> Transport.BLUETOOTH
        InterfaceType.BluetoothLE -> Transport.BLE
        InterfaceType.Usb -> Transport.USB
        else -> Transport.WIFI
    }

    private fun alignmentFor(align: String?): Alignment = when (align) {
        "center" -> Alignment.Center
        "right" -> Alignment.Right
        else -> Alignment.Left
    }

    private fun qrLevelFor(ec: String): QRCodeLevel = when (ec.uppercase()) {
        "L" -> QRCodeLevel.L
        "Q" -> QRCodeLevel.Q
        "H" -> QRCodeLevel.H
        else -> QRCodeLevel.M
    }

    private fun barcodeSymbologyFor(symbology: String): BarcodeSymbology = when (symbology.uppercase()) {
        "CODE39" -> BarcodeSymbology.Code39
        "CODE93" -> BarcodeSymbology.Code93
        "EAN13", "JAN13" -> BarcodeSymbology.Jan13
        "EAN8", "JAN8" -> BarcodeSymbology.Jan8
        "ITF" -> BarcodeSymbology.Itf
        "UPCA" -> BarcodeSymbology.UpcA
        "UPCE" -> BarcodeSymbology.UpcE
        "NW7", "CODABAR" -> BarcodeSymbology.NW7
        else -> BarcodeSymbology.Code128
    }

    private fun ensureSdk() {
        if (!isAvailable()) throw PrinterException(ErrorCode.SDK_NOT_AVAILABLE, "SDK Star absent")
    }
}
