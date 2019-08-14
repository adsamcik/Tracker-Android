package com.adsamcik.signalcollector.common.misc

/**
 * Based on [android.renderscript.Int2]
 * Improved Kotlin support
 * Add support for equality checks
 */
data class Int2(var x: Int = 0, var y: Int = 0) {

	/** @hide
	 * get vector length
	 *
	 * @return
	 */
	val length: Int get() = 2

	/** @hide
	 */
	constructor(i: Int) : this(i, i)

	/** @hide
	 */
	constructor(source: Int2) : this(source.x, source.y)

	/** @hide
	 * Vector add
	 *
	 * @param a
	 */
	operator fun plus(a: Int2) {
		this.x += a.x
		this.y += a.y
	}

	/**  @hide
	 * Vector add
	 *
	 * @param value
	 */
	operator fun plus(value: Int) {
		x += value
		y += value
	}

	/** @hide
	 * Vector subtraction
	 *
	 * @param a
	 */
	operator fun minus(a: Int2) {
		this.x -= a.x
		this.y -= a.y
	}

	/** @hide
	 * Vector subtraction
	 *
	 * @param value
	 */
	operator fun minus(value: Int) {
		x -= value
		y -= value
	}

	/** @hide
	 * Vector multiplication
	 *
	 * @param a
	 */
	operator fun times(a: Int2) {
		this.x *= a.x
		this.y *= a.y
	}

	/** @hide
	 * Vector multiplication
	 *
	 * @param value
	 */
	operator fun times(value: Int) {
		x *= value
		y *= value
	}

	/** @hide
	 * Vector division
	 *
	 * @param a
	 */
	operator fun div(a: Int2) {
		this.x /= a.x
		this.y /= a.y
	}

	/** @hide
	 * Vector division
	 *
	 * @param value
	 */
	operator fun div(value: Int) {
		x /= value
		y /= value
	}

	/** @hide
	 * Vector Modulo
	 *
	 * @param a
	 */
	operator fun rem(a: Int2) {
		this.x %= a.x
		this.y %= a.y
	}

	/** @hide
	 * Vector Modulo
	 *
	 * @param value
	 */
	operator fun rem(value: Int) {
		x %= value
		y %= value
	}

	/** @hide
	 * set vector negate
	 */
	fun negate() {
		this.x = -x
		this.y = -y
	}

	/** @hide
	 * Vector dot Product
	 *
	 * @param a
	 * @return
	 */
	fun dotProduct(a: Int2): Int {
		return x * a.x + y * a.y
	}

	/** @hide
	 * Vector add Multiple
	 *
	 * @param a
	 * @param factor
	 */
	fun addMultiple(a: Int2, factor: Int) {
		x += a.x * factor
		y += a.y * factor
	}

	/** @hide
	 * set vector value by Int2
	 *
	 * @param a
	 */
	fun set(a: Int2) {
		this.x = a.x
		this.y = a.y
	}

	/** @hide
	 * set the vector field value by Int
	 *
	 * @param a
	 * @param b
	 */
	fun setValues(a: Int, b: Int) {
		this.x = a
		this.y = b
	}

	/** @hide
	 * return the element sum of vector
	 *
	 * @return
	 */
	val elementSum: Int get() = x + y

	/** @hide
	 * get the vector field value by index
	 *
	 * @param i
	 * @return
	 */
	operator fun get(i: Int): Int {
		return when (i) {
			0    -> x
			1    -> y
			else -> throw IndexOutOfBoundsException("Index: $i")
		}
	}

	/** @hide
	 * set the vector field value by index
	 *
	 * @param i
	 * @param value
	 */
	fun setAt(i: Int, value: Int) {
		when (i) {
			0    -> {
				x = value
				return
			}
			1    -> {
				y = value
				return
			}
			else -> throw IndexOutOfBoundsException("Index: $i")
		}
	}

	/** @hide
	 * add the vector field value by index
	 *
	 * @param i
	 * @param value
	 */
	fun addAt(i: Int, value: Int) {
		when (i) {
			0    -> {
				x += value
				return
			}
			1    -> {
				y += value
				return
			}
			else -> throw IndexOutOfBoundsException("Index: $i")
		}
	}

	/** @hide
	 * copy the vector to int array
	 *
	 * @param data
	 * @param offset
	 */
	fun copyTo(data: IntArray, offset: Int) {
		data[offset] = x
		data[offset + 1] = y
	}

	override operator fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (javaClass != other?.javaClass) return false

		other as Int2

		if (x != other.x) return false
		if (y != other.y) return false

		return true
	}

	override fun hashCode(): Int {
		var result = x
		result = 31 * result + y
		return result
	}

	override fun toString(): String {
		return "Int2(x=$x, y=$y)"
	}

	companion object {

		/** @hide
		 * Vector add
		 *
		 * @param a
		 * @param b
		 * @return
		 */
		fun add(a: Int2, b: Int2): Int2 {
			val result = Int2()
			result.x = a.x + b.x
			result.y = a.y + b.y

			return result
		}

		/** @hide
		 * Vector add
		 *
		 * @param a
		 * @param b
		 * @return
		 */
		fun add(a: Int2, b: Int): Int2 {
			val result = Int2()
			result.x = a.x + b
			result.y = a.y + b

			return result
		}

		/** @hide
		 * Vector subtraction
		 *
		 * @param a
		 * @param b
		 * @return
		 */
		fun sub(a: Int2, b: Int2): Int2 {
			val result = Int2()
			result.x = a.x - b.x
			result.y = a.y - b.y

			return result
		}

		/** @hide
		 * Vector subtraction
		 *
		 * @param a
		 * @param b
		 * @return
		 */
		fun sub(a: Int2, b: Int): Int2 {
			val result = Int2()
			result.x = a.x - b
			result.y = a.y - b

			return result
		}

		/** @hide
		 * Vector multiplication
		 *
		 * @param a
		 * @param b
		 * @return
		 */
		fun mul(a: Int2, b: Int2): Int2 {
			val result = Int2()
			result.x = a.x * b.x
			result.y = a.y * b.y

			return result
		}

		/** @hide
		 * Vector multiplication
		 *
		 * @param a
		 * @param b
		 * @return
		 */
		fun mul(a: Int2, b: Int): Int2 {
			val result = Int2()
			result.x = a.x * b
			result.y = a.y * b

			return result
		}

		/** @hide
		 * Vector division
		 *
		 * @param a
		 * @param b
		 * @return
		 */
		fun div(a: Int2, b: Int2): Int2 {
			val result = Int2()
			result.x = a.x / b.x
			result.y = a.y / b.y

			return result
		}

		/** @hide
		 * Vector division
		 *
		 * @param a
		 * @param b
		 * @return
		 */
		fun div(a: Int2, b: Int): Int2 {
			val result = Int2()
			result.x = a.x / b
			result.y = a.y / b

			return result
		}

		/** @hide
		 * Vector Modulo
		 *
		 * @param a
		 * @param b
		 * @return
		 */
		fun mod(a: Int2, b: Int2): Int2 {
			val result = Int2()
			result.x = a.x % b.x
			result.y = a.y % b.y

			return result
		}

		/** @hide
		 * Vector Modulo
		 *
		 * @param a
		 * @param b
		 * @return
		 */
		fun mod(a: Int2, b: Int): Int2 {
			val result = Int2()
			result.x = a.x % b
			result.y = a.y % b

			return result
		}

		/** @hide
		 * Vector dot Product
		 *
		 * @param a
		 * @param b
		 * @return
		 */
		fun dotProduct(a: Int2, b: Int2): Int {
			return b.x * a.x + b.y * a.y
		}
	}
}
