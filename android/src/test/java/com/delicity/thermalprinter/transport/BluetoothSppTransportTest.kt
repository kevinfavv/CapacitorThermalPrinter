package com.delicity.thermalprinter.transport

import android.bluetooth.BluetoothDevice.BOND_BONDED
import android.bluetooth.BluetoothDevice.BOND_BONDING
import android.bluetooth.BluetoothDevice.BOND_NONE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

/** Transport SPP sans Bluetooth réel : attente de l'appairage et lecture bornée. */
class BluetoothSppTransportTest {

    private fun await(vararg states: Int, timeoutMs: Long = 1_000): Boolean {
        val it = states.iterator()
        var last = states.last()
        return BluetoothSppTransport.awaitBonded(
            bondState = { if (it.hasNext()) it.next().also { s -> last = s } else last },
            timeoutMs = timeoutMs,
            sleep = {},
        )
    }

    @Test
    fun `appairage accepte`() = assertTrue(await(BOND_NONE, BOND_BONDING, BOND_BONDING, BOND_BONDED))

    @Test
    fun `pop-up annulee ou mauvais PIN`() = assertFalse(await(BOND_BONDING, BOND_BONDING, BOND_NONE))

    @Test
    fun `NONE avant le debut de l'appairage n'est pas un echec`() =
        assertTrue(await(BOND_NONE, BOND_NONE, BOND_BONDING, BOND_BONDED))

    @Test
    fun `personne ne repond a la pop-up`() = assertFalse(await(BOND_BONDING, timeoutMs = 5_000))

    @Test
    fun `lecture sans reponse de l'imprimante rend la main au timeout`() {
        val silent = PipedInputStream(PipedOutputStream())
        val started = System.currentTimeMillis()
        assertEquals(-1, BluetoothSppTransport.readWithTimeout(silent, ByteArray(8), 200))
        assertTrue(System.currentTimeMillis() - started < 2_000)
    }

    @Test
    fun `lecture d'un statut disponible`() =
        assertEquals(1, BluetoothSppTransport.readWithTimeout(ByteArrayInputStream(byteArrayOf(0x12)), ByteArray(8), 200))
}
