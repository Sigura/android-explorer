package androidx.datastore.preferences.core

import androidx.datastore.core.DataStore

abstract class Preferences internal constructor() {
    class Key<T> internal constructor(val name: String) {
        infix fun to(value: T): Pair<T> = TODO()
    }
    class Pair<T> internal constructor()
    abstract operator fun <T> contains(key: Key<T>): Boolean
    abstract operator fun <T> get(key: Key<T>): T?
    abstract fun asMap(): Map<Key<*>, Any>
    fun toMutablePreferences(): MutablePreferences = TODO()
    fun toPreferences(): Preferences = TODO()
}

class MutablePreferences internal constructor() : Preferences() {
    override fun <T> contains(key: Key<T>): Boolean = TODO()
    override fun <T> get(key: Key<T>): T? = TODO()
    override fun asMap(): Map<Key<*>, Any> = TODO()
    operator fun <T> set(key: Key<T>, value: T) {}
    operator fun plusAssign(prefs: Preferences) {}
    operator fun plusAssign(pair: Preferences.Pair<*>) {}
    operator fun minusAssign(key: Key<*>) {}
    fun putAll(vararg pairs: Preferences.Pair<*>) {}
    fun <T> remove(key: Key<T>): T = TODO()
    fun clear() {}
}

fun intPreferencesKey(name: String): Preferences.Key<Int> = TODO()
fun doublePreferencesKey(name: String): Preferences.Key<Double> = TODO()
fun stringPreferencesKey(name: String): Preferences.Key<String> = TODO()
fun booleanPreferencesKey(name: String): Preferences.Key<Boolean> = TODO()
fun floatPreferencesKey(name: String): Preferences.Key<Float> = TODO()
fun longPreferencesKey(name: String): Preferences.Key<Long> = TODO()
fun stringSetPreferencesKey(name: String): Preferences.Key<Set<String>> = TODO()

suspend fun DataStore<Preferences>.edit(transform: suspend (MutablePreferences) -> Unit): Preferences = TODO()
