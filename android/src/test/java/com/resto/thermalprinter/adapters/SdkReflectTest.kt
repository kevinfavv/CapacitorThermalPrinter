package com.resto.thermalprinter.adapters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Classe-fixture résolue par nom (FQN) dans les tests de SdkReflect. */
class ReflectFixture(val a: Int, val b: String) {
    val log = mutableListOf<String>()
    fun greet(name: String): String { log.add(name); return "hi $name" }

    companion object {
        @JvmField val ANSWER = 42
        @JvmStatic fun make(x: Int): ReflectFixture = ReflectFixture(x, "made")
    }
}

/** Interface-fixture pour tester le proxy dynamique. */
interface FakeListener {
    fun onEvent(value: Int)
}

/**
 * Tests JVM purs (sans Android/Robolectric) du moteur de réflexion SdkReflect —
 * la brique qui pilote les SDK Epson/Brother/Zebra sans dépendance de compilation.
 */
class SdkReflectTest {

    private val fqn = "com.resto.thermalprinter.adapters.ReflectFixture"

    @Test
    fun `exists vrai si la classe est presente`() {
        assertTrue(SdkReflect.exists(fqn))
        assertFalse(SdkReflect.exists("com.does.not.Exist"))
    }

    @Test
    fun `staticInt lit une constante et applique le fallback`() {
        assertEquals(42, SdkReflect.staticInt(fqn, "ANSWER", -1))
        assertEquals(7, SdkReflect.staticInt(fqn, "MISSING", 7))
        assertEquals(7, SdkReflect.staticInt("com.does.not.Exist", "ANSWER", 7))
    }

    @Test
    fun `newInstance et call invoquent constructeur et methode`() {
        val obj = SdkReflect.newInstance(
            fqn,
            arrayOf(Int::class.javaPrimitiveType!!, String::class.java),
            arrayOf(1, "x"),
        )
        assertNotNull(obj)
        val result = SdkReflect.call(obj, "greet", arrayOf(String::class.java), arrayOf("bob"))
        assertEquals("hi bob", result)
    }

    @Test
    fun `callStatic invoque une methode statique`() {
        val obj = SdkReflect.callStatic(fqn, "make", arrayOf(Int::class.javaPrimitiveType!!), arrayOf(5))
        assertNotNull(obj)
        assertTrue(obj is ReflectFixture)
        assertEquals(5, (obj as ReflectFixture).a)
    }

    @Test
    fun `field lit un champ d'instance`() {
        val obj = ReflectFixture(9, "hello")
        assertEquals("hello", SdkReflect.field(obj, "b"))
        assertNull(SdkReflect.field(obj, "inexistant"))
    }

    @Test
    fun `proxy route les appels vers le handler`() {
        var captured = -1
        val proxy = SdkReflect.proxy(
            "com.resto.thermalprinter.adapters.FakeListener",
            mapOf("onEvent" to { args -> captured = args[0] as Int; null }),
        )
        (proxy as FakeListener).onEvent(99)
        assertEquals(99, captured)
    }
}
