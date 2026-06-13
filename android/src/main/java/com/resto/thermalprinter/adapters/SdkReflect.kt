package com.resto.thermalprinter.adapters

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Boîte à outils de réflexion pour piloter les SDK fabricants NON redistribuables
 * (Epson ePOS2, Brother, Zebra Link-OS) SANS dépendance de compilation.
 *
 * Pourquoi la réflexion ? Les licences de ces SDK interdisent leur redistribution
 * (Maven Central / CocoaPods), donc on ne peut pas compiler le plugin contre leurs
 * types. La réflexion permet :
 *   - de compiler/publier le plugin SANS les binaires,
 *   - d'activer automatiquement l'adapter quand l'app fournit le binaire
 *     (voir docs/SDK_INTEGRATION.md).
 *
 * ⚠️ Le code réflexif n'est pas vérifié par le compilateur : toute évolution
 * d'API du SDK doit être testée sur device avec le binaire réel.
 */
object SdkReflect {

    fun classOrNull(name: String): Class<*>? = try {
        Class.forName(name)
    } catch (e: Throwable) {
        null
    }

    fun exists(name: String): Boolean = classOrNull(name) != null

    /** Instancie une classe via un constructeur dont on donne les types de paramètres. */
    fun newInstance(className: String, paramTypes: Array<Class<*>>, args: Array<Any?>): Any {
        val c = classOrNull(className) ?: error("Classe absente: $className")
        return c.getConstructor(*paramTypes).newInstance(*args)
    }

    /** Appelle une méthode d'instance. */
    fun call(target: Any, method: String, paramTypes: Array<Class<*>> = emptyArray(), args: Array<Any?> = emptyArray()): Any? =
        target.javaClass.getMethod(method, *paramTypes).invoke(target, *args)

    /** Appelle une méthode statique. */
    fun callStatic(className: String, method: String, paramTypes: Array<Class<*>> = emptyArray(), args: Array<Any?> = emptyArray()): Any? {
        val c = classOrNull(className) ?: error("Classe absente: $className")
        return c.getMethod(method, *paramTypes).invoke(null, *args)
    }

    /** Lit une constante (champ static) — typiquement les `int` du SDK. */
    fun staticInt(className: String, field: String, fallback: Int = 0): Int = try {
        classOrNull(className)?.getField(field)?.getInt(null) ?: fallback
    } catch (e: Throwable) {
        fallback
    }

    fun staticField(className: String, field: String): Any? = try {
        classOrNull(className)?.getField(field)?.get(null)
    } catch (e: Throwable) {
        null
    }

    /** Lit un champ d'instance (public). */
    fun field(target: Any, name: String): Any? = try {
        target.javaClass.getField(name).get(target)
    } catch (e: Throwable) {
        null
    }

    fun intField(target: Any, name: String, fallback: Int = 0): Int =
        (field(target, name) as? Int) ?: fallback

    /** Valeur d'enum par nom. */
    @Suppress("UNCHECKED_CAST")
    fun enumValue(enumClass: String, name: String): Any? {
        val c = classOrNull(enumClass) ?: return null
        return try {
            java.lang.Enum.valueOf(c as Class<out Enum<*>>, name)
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * Crée un proxy dynamique implémentant une interface listener du SDK.
     * [handlers] mappe un nom de méthode -> lambda recevant les arguments.
     */
    fun proxy(interfaceName: String, handlers: Map<String, (Array<Any?>) -> Any?>): Any {
        val iface = classOrNull(interfaceName) ?: error("Interface absente: $interfaceName")
        return Proxy.newProxyInstance(iface.classLoader, arrayOf(iface), object : InvocationHandler {
            override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
                val a = (args ?: emptyArray()).map { it }.toTypedArray()
                return handlers[method.name]?.invoke(a) ?: defaultFor(method.returnType)
            }
        })
    }

    private fun defaultFor(t: Class<*>): Any? = when (t) {
        Boolean::class.javaPrimitiveType -> false
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        Double::class.javaPrimitiveType -> 0.0
        Float::class.javaPrimitiveType -> 0f
        Void.TYPE -> null
        else -> null
    }
}
