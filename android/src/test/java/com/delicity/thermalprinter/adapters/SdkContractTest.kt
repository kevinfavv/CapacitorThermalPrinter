package com.delicity.thermalprinter.adapters

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Vérifie que le **contrat de réflexion** Epson est cohérent : le faux SDK Epson
 * (sur le classpath de test) satisfait toute la surface décrite dans
 * [SdkContract.EPSON].
 *
 * Le même appel `SdkContract.verify(SdkContract.EPSON)`, lancé dans l'app avec le
 * **vrai** binaire ePOS2, prouve que notre réflexion correspond à l'API réelle —
 * sans imprimante. (Idem ZEBRA / BROTHER une fois leurs faux SDK ajoutés, ou
 * directement contre le vrai SDK dans l'app.)
 */
@RunWith(RobolectricTestRunner::class)
class SdkContractTest {

    @Test
    fun `le faux SDK Epson satisfait le contrat de reflexion`() {
        val missing = SdkContract.verify(SdkContract.EPSON)
        assertTrue("Symboles Epson manquants vs contrat: $missing", missing.isEmpty())
    }
}
