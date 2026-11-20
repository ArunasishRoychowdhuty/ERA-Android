package android.content

/** Minimal in-memory Android SharedPreferences/Context stand-ins for portable smoke tests only. */
interface SharedPreferences {
    fun getBoolean(key: String, defValue: Boolean): Boolean
    fun getLong(key: String, defValue: Long): Long
    fun getString(key: String, defValue: String?): String?
    fun edit(): Editor

    interface Editor {
        fun putBoolean(key: String, value: Boolean): Editor
        fun putLong(key: String, value: Long): Editor
        fun putString(key: String, value: String?): Editor
        fun remove(key: String): Editor
        fun apply()
    }
}

class InMemorySharedPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()

    override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val updates = linkedMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        override fun putBoolean(key: String, value: Boolean) = apply { updates[key] = value; removals.remove(key) }
        override fun putLong(key: String, value: Long) = apply { updates[key] = value; removals.remove(key) }
        override fun putString(key: String, value: String?) = apply {
            if (value == null) { updates.remove(key); removals.add(key) }
            else { updates[key] = value; removals.remove(key) }
        }
        override fun remove(key: String) = apply { updates.remove(key); removals.add(key) }
        override fun apply() {
            removals.forEach(values::remove)
            values.putAll(updates)
        }
    }
}

open class Context {
    private val stores = mutableMapOf<String, InMemorySharedPreferences>()
    @Suppress("UNUSED_PARAMETER")
    fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        stores.getOrPut(name) { InMemorySharedPreferences() }
    companion object { const val MODE_PRIVATE = 0 }
}
