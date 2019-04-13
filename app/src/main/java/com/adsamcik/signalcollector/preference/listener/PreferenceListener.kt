package com.adsamcik.signalcollector.preference.listener

@Suppress("WeakerAccess", "UNUSED")
object PreferenceListener {
	private val intListener = PreferenceListenerType<Int>()
	private val boolListener = PreferenceListenerType<Boolean>()
	private val longListener = PreferenceListenerType<Long>()
	private val floatListener = PreferenceListenerType<Float>()
	private val stringListener = PreferenceListenerType<String>()

	fun invokeAnyListener(key: String, value: Any) {
		when(value) {
			is String -> invokeListener(key, value)
			is Boolean -> invokeListener(key, value)
			is Int -> invokeListener(key, value)
			is Long -> invokeListener(key, value)
			is Float -> invokeListener(key, value)
			else -> throw NotImplementedError("${value.javaClass.name} not supported!")
		}
	}

	fun invokeListener(key: String, value: Int) {
		intListener.invoke(key, value)
	}

	fun invokeListener(key: String, value: Long) {
		longListener.invoke(key, value)
	}

	fun invokeListener(key: String, value: Boolean) {
		boolListener.invoke(key, value)
	}

	fun invokeListener(key: String, value: String) {
		stringListener.invoke(key, value)
	}

	fun invokeListener(key: String, value: Float) {
		floatListener.invoke(key, value)
	}

	@JvmName("addIntListener")
	fun addListener(key: String, listener: OnPreferenceChanged<Int>) {
		intListener.addListener(key, listener)
	}

	@JvmName("addLongListener")
	fun addListener(key: String, listener: OnPreferenceChanged<Long>) {
		longListener.addListener(key, listener)
	}

	@JvmName("addFloatListener")
	fun addListener(key: String, listener: OnPreferenceChanged<Float>) {
		floatListener.addListener(key, listener)
	}

	@JvmName("addBooleanListener")
	fun addListener(key: String, listener: OnPreferenceChanged<Boolean>) {
		boolListener.addListener(key, listener)
	}

	@JvmName("addStringListener")
	fun addListener(key: String, listener: OnPreferenceChanged<String>) {
		stringListener.addListener(key, listener)
	}

	@JvmName("removeIntListener")
	fun removeListener(key: String, listener: OnPreferenceChanged<Int>) {
		intListener.removeListener(key, listener)
	}

	@JvmName("removeLongListener")
	fun removeListener(key: String, listener: OnPreferenceChanged<Long>) {
		longListener.removeListener(key, listener)
	}

	@JvmName("removeFloatListener")
	fun removeListener(key: String, listener: OnPreferenceChanged<Float>) {
		floatListener.removeListener(key, listener)
	}

	@JvmName("removeBooleanListener")
	fun removeListener(key: String, listener: OnPreferenceChanged<Boolean>) {
		boolListener.removeListener(key, listener)
	}

	@JvmName("removeStringListener")
	fun removeListener(key: String, listener: OnPreferenceChanged<String>) {
		stringListener.removeListener(key, listener)
	}


}