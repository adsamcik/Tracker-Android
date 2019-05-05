package com.adsamcik.signalcollector.map.heatmap

import androidx.core.graphics.ColorUtils
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/* heatmap - High performance heatmap creation in C. (Rewritten to Kotlin)
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2013 Lucas Beyer
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *
 * https://github.com/lucasb-eyer/heatmap/
 */

class Heatmap(val width: Int, val height: Int = width, var maxHeat: Float = 0f, var dynamicHeat: Boolean = true) {
	var pointCount: Int = 0

	val data: FloatArray = FloatArray(width * height)

	fun addPoint(x: Int, y: Int): Unit = addPointWithStamp(x, y, HeatmapStamp.default9x9)

	fun addWeightedPoint(x: Int, y: Int, weight: Float): Unit = addWeightedPointWithStamp(x, y, weight, HeatmapStamp.default9x9)

	fun addPointWithStamp(x: Int, y: Int, stamp: HeatmapStamp): Unit = addPointWithStamp(x, y, stamp) { 1f }

	fun addWeightedPointWithStamp(x: Int, y: Int, weight: Float, stamp: HeatmapStamp): Unit = addPointWithStamp(x, y, stamp) { weight }

	private inline fun addPointWithStamp(x: Int, y: Int, stamp: HeatmapStamp, weightFunc: () -> Float) {
		//todo validate that odd numbers don't cause some weird artifacts
		val halfStampHeight = stamp.height / 2
		val halfStampWidth = stamp.width / 2

		assert(x - halfStampWidth / 2 < width)
		assert(y - halfStampHeight < height)
		assert(x - halfStampHeight >= 0)
		assert(y + halfStampWidth / 2 >= 0)

		pointCount++

		/* Note: the order of operations is important, since we're computing with unsigned! */

		/* These are [first, last) pairs in the STAMP's pixels. */
		val x0 = if (x < halfStampWidth) stamp.width / 2 - x else 0
		val y0 = if (y < halfStampHeight) stamp.height / 2 - y else 0
		val x1 = if (x + halfStampWidth < width) stamp.width else halfStampWidth + width - x
		val y1 = if (y + halfStampHeight < height) stamp.height else halfStampHeight + height - y

		for (itY in y0 until y1) {
			var heatIndex = ((y + itY) - halfStampHeight) * width + (x + x0) - halfStampWidth
			var stampIndex = itY * stamp.width + x0
			assert(stampIndex >= 0f)

			for (itX in x0 until x1) {
				val heatValue = data[heatIndex]
				data[heatIndex] = heatValue + stamp.stampData[stampIndex] * weightFunc.invoke()
				if (dynamicHeat && heatValue > maxHeat)
					maxHeat = heatValue

				assert(heatValue >= 0f)

				heatIndex++
				stampIndex++
			}
		}
	}

	fun renderDefaultTo(): IntArray = renderTo(HeatmapColorScheme.default)

	/* TODO: Time whether it makes a noticeable difference to inline that code
     * here and drop the saturation step.
     */
	/* If the heatmap is empty, h->max (and thus the saturation value) is 0.0, resulting in a 0-by-0 division.
	 * In that case, we should set the saturation to anything but 0, since we want the result of the division to be 0.
	 * Also, a comparison to exact 0.0f (as opposed to 1e-14) is OK, since we only do division.
	 */
	fun renderTo(colorScheme: HeatmapColorScheme): IntArray {
		val saturation = if (maxHeat > 0f) maxHeat else 1.0f
		return renderSaturatedTo(colorScheme, saturation)
	}

	fun renderSaturatedTo(colorScheme: HeatmapColorScheme, saturation: Float): IntArray = renderSaturatedTo(colorScheme, saturation) { it }

	inline fun renderSaturatedTo(colorScheme: HeatmapColorScheme, saturation: Float, normalizedValueModifierFunction: (Float) -> Float): IntArray {
		assert(saturation > 0f)

		val buffer = IntArray(width * height)

		if (pointCount == 0)
			return buffer

		for (itY in 0 until height) {
			var index = itY * width

			for (itX in 0 until width) {
				val value = data[index]
				val normalizedValue = normalizedValueModifierFunction(min(value, saturation) / saturation)

				val colorId = ((colorScheme.colors.size - 1) * normalizedValue).roundToInt()

				assert(normalizedValue >= 0)
				assert(colorId < colorScheme.colors.size)

				buffer[index] = colorScheme.colors[colorId]
				index++
			}
		}

		return buffer
	}
}


data class HeatmapColorScheme constructor(val colors: IntArray) {
	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (javaClass != other?.javaClass) return false

		other as HeatmapColorScheme

		if (!colors.contentEquals(other.colors)) return false

		return true
	}

	override fun hashCode(): Int {
		return colors.contentHashCode()
	}

	companion object {
		val mixed_data: IntArray = intArrayOf(0, 6180770, 123555746, 240996514, 375148707, 492589475, 626741668, 744182436, 878334628, 995775397, 1129927589, 1247368358, 1381520550, 1498961318, 1633113511, 1750554279, 1884706471, 2002147240, 2136299432, -2041227096, -1923852119, -1789634135, -1672259159, -1538041174, -1420600662, -1286448214, -1169007701, -1034855253, -917414741, -783262292, -665821780, -531669332, -414228820, -280076371, -162635859, -28483667, -11706194, -11771730, -11771474, -11837010, -11836753, -11902289, -11902033, -11967569, -11967312, -12032848, -12032592, -12032592, -12098128, -12097871, -12163407, -12163151, -12228687, -12815433, -12815433, -12880713, -12880713, -12945992, -12945992, -12945736, -13011272, -13011272, -13076552, -13076551, -13076295, -13141831, -13141575, -13141575, -13207111, -13206854, -13206854, -13272134, -13272134, -13271878, -13337414, -13337414, -13337158, -13337157, -13402437, -13402437, -13402437, -13402181, -13467717, -13467461, -13467461, -13467461, -13467205, -13467204, -13466948, -13532484, -13532484, -13532228, -13532228, -13531972, -13531972, -13531972, -13531716, -13531716, -13531716, -13531460, -13531460, -13531204, -13531204, -13531204, -13530947, -13662019, -13727299, -13792579, -13923651, -13988930, -14054466, -14185282, -14250818, -14381634, -14447170, -14577986, -14709058, -14774338, -14905410, -15036225, -15167041, -15298113, -15428929, -15625537, -15756353, -15952961, -16083777, -16083777, -16083521, -16083521, -16083265, -16083266, -16083010, -16083010, -16082754, -16082754, -16082498, -16082498, -16082242, -16082242, -16081986, -16081987, -16081731, -16081731, -16081475, -16081475, -16081219, -16081220, -16081220, -16080964, -16080964, -16080708, -16080709, -16080453, -16080453, -15422026, -15290698, -15094091, -14897227, -14766155, -14569292, -14438220, -14307148, -14175820, -14044749, -13913421, -13782349, -13716558, -13585486, -13454414, -13388623, -13257551, -13191759, -13060688, -12929360, -12863824, -12732753, -12666961, -12535889, -12470098, -12404562, -12273490, -12207699, -12076627, -12010835, -11879764, -11814228, -11748437, -11617365, -11551829, -11486038, -11354966, -11289174, -11158103, -11092567, -11026775, -10895704, -10830168, -10764377, -10633305, -10567769, -10501978, -10370906, -10305114, -10239579, -10174043, -10042716, -10042716, -9977180, -9977180, -9911644, -9911388, -9845852, -9845852, -9780316, -9780060, -9714524, -9648988, -9648988, -9583452, -9583196, -9517660, -9517660, -9452124, -9451868, -9386332, -9386332, -9320796, -9320796, -9255004, -9189468, -9189468, -9123932, -9123676, -9058140, -9058140, -8992604, -8992604, -8926812, -8926812, -8861276, -8861276, -8795740, -8729948, -8729948, -8664412, -8664412, -8598620, -8598620, -8533084, -8533084, -8467548, -8467292, -8401756, -8401757, -8336221, -8270685, -7351645, -7351645, -7286109, -7285853, -7220317, -7154781, -7154781, -7088989, -7088989, -7023453, -7023453, -6957917, -6957661, -6892125, -6892125, -6826589, -6826589, -6760797, -6695261, -6695261, -6629725, -6629725, -6563933, -6563933, -6498397, -6498397, -6432861, -6432605, -6367069, -6367069, -6301533, -6235997, -6235741, -6170205, -6170205, -6104669, -6104669, -6038877, -6038877, -5973341, -5973341, -5907805, -5842013, -5842013, -5776477, -5776477, -5710941, -5710685, -5645149, -5645149, -5579613, -5579613, -5513821, -5513821, -5448285, -5448285, -5448029, -5382493, -5382493, -5382493, -5316957, -5316701, -5251165, -5251165, -5251166, -5185630, -5185374, -5119838, -5119838, -5119838, -5054302, -5054046, -4988510, -4988510, -4988510, -4922975, -4922719, -4857183, -4857183, -4791647, -4791647, -4791391, -4725855, -4725855, -4660319, -4660320, -4594528, -4594528, -4594528, -4528992, -4528992, -4463200, -4463200, -4397664, -4397664, -4332129, -4331873, -4331873, -4266337, -4266337, -4200801, -4200545, -4135009, -3347300, -3281764, -3281764, -3281764, -3216228, -3215972, -3150436, -3150436, -3084900, -3084901, -3019109, -3019109, -2953573, -2953573, -2888037, -2888037, -2822245, -2822245, -2756709, -2756710, -2691174, -2690918, -2625382, -2625382, -2559846, -2559846, -2494310, -2494054, -2428518, -2428519, -2362983, -2362983, -2297447, -2297191, -2231655, -2231655, -2166119, -2166119, -2100583, -2100327, -2034792, -2034792, -1969256, -1969256, -1903720, -1837928, -1837928, -1772392, -1772392, -1706856, -1706856, -1641321, -1641321, -1641321, -1641321, -1641321, -1641321, -1641321, -1641321, -1641321, -1575785, -1575785, -1575785, -1576041, -1576041, -1576041, -1576041, -1576042, -1576042, -1510506, -1510506, -1510506, -1510506, -1510506, -1510506, -1510506, -1510762, -1510762, -1445226, -1445226, -1445226, -1445226, -1445226, -1445226, -1445226, -1445227, -1445227, -1379691, -1379947, -1379947, -1379947, -1379947, -1379947, -1379947, -1379947, -1379947, -1314411, -1314411, -1314411, -1314411, -1314667, -1314667, -1314667, -1314667, -1314667, -1249132, -1249132, -1249132, -1249132, -1249132, -1249132, -1249132, -1249388, -1249388, -1249388, -1183852, -1183852, -1183852, -1183852, -1183852, -1183852, -1183852, -1183852, -1183852, -1184108, -1118572, -1118572, -1118572, -1118572, -1118573, -1118573, -1118573, -1118573, -1118573, -1118573, -1053037, -1053293, -1053293, -1053293, -1053293, -1053293, -1053293, -1053293, -1053293, -1053293, -987757, -987757, -987757, -988013, -988013, -988013, -988013, -988013, -661359, -661359, -661359, -661359, -661359, -596079, -596079, -596079, -596079, -596079, -596079, -596079, -596079, -596079, -596079, -596079, -530543, -530799, -530799, -530799, -530799, -530800, -530800, -530800, -530800, -530800, -530800, -465264, -465264, -465520, -465520, -465520, -465520, -465520, -465520, -465520, -465520, -465520, -465520, -399984, -399984, -399984, -400240, -400240, -400240, -400240, -400240, -400240, -400240, -400240, -400240, -334704, -334704, -334704, -334960, -334960, -334960, -334960, -334960, -334960, -334960, -334960, -334960, -269424, -269424, -269424, -269680, -269680, -269680, -269680, -269680, -269680, -269680, -269680, -269680, -269680, -204144, -204144, -204400, -204400, -204400, -204400, -204400, -204400, -204400, -204400, -204400, -204400, -204400, -138864, -138864, -139120, -139120, -139120, -139120, -139120, -139120, -139120, -139120, -139120, -139120, -139120, -139120, -139377, -139377, -139378, -139634, -139635, -139891, -139891, -139892, -140148, -140149, -140149, -140406, -140406, -140662, -140663, -140663, -140920, -140920, -140921, -141177, -141177, -141434, -141434, -141435, -141691, -141691, -141692, -141948, -141949, -142205, -142205, -142206, -142462, -142463, -142463, -142719, -142720, -142976, -142977, -142977, -143233, -143234, -143490, -143491, -143491, -143747, -143748, -144004, -144005, -144005, -144261, -146831, -146831, -147088, -147088, -147344, -147345, -147345, -147602, -147602, -147858, -147859, -147859, -148115, -148116, -148372, -148372, -148373, -148629, -148629, -148886, -148886, -148886, -149143, -149143, -149399, -149400, -149400, -149656, -149657, -149913, -149913, -149914, -150170, -150170, -150427, -150427, -150427, -150684, -150684, -150940, -150940, -150941, -151197, -151197, -151454, -151454, -151710, -151710, -151711, -151967, -151967, -217760, -217760, -218016, -218017, -218017, -218273, -218274, -218530, -218530, -218787, -218787, -219043, -219044, -219300, -219300, -219557, -219557, -219813, -285350, -285606, -285606, -285863, -285863, -286119, -286119, -286120, -286376, -286376, -286633, -286633, -286889, -286889, -287146, -287146, -287402, -352939, -353195, -353195, -353451, -353452, -353708, -353708, -353964, -353965, -354221, -354221, -354477, -354478, -354734, -354734, -420526, -489397, -489653, -555189, -555445, -555446, -555702, -555702, -555958, -555958, -556215, -556215, -556471, -556471, -556728, -556728, -556984, -622520, -622776, -623033, -623033, -623289, -623289, -623545, -623545, -623802, -623802, -624058, -624058, -624314, -689850, -690107, -690107, -690363, -690363, -690619, -690875, -690876, -691132, -691132, -691388, -691388, -757180, -757180, -757437, -757437, -757693, -757949, -757949, -758205, -758205, -758461, -758461, -824253, -824253, -824509, -824509, -824509, -890301, -890301, -890301, -890557, -890557, -956348, -956348, -956348, -956604, -956604, -1022396, -1022396, -1022396, -1022652, -1022652, -1088187, -1088443, -1088443, -1088699, -1154235, -1154235, -1154491, -1154491, -1154491, -1220283, -1220282, -1220538, -1220538, -1286074, -1286330, -1286330, -1286330, -1286586, -1352122, -1352122, -1352377, -1352377, -1418169, -1418169, -1418169, -1418425, -1483961, -1483961, -1484217, -1484217, -1484472, -1945526, -1945782, -1945782, -2011318, -2011574, -2011574, -2011829, -2077365, -2077365, -2077621, -2077621, -2143157, -2143413, -2143413, -2143413, -2209205, -2209205, -2209460, -2209460, -2274996, -2275252, -2275252, -2340788, -2341044, -2341044, -2341044, -2406836, -2406836, -2406835, -2407091, -2472627, -2472883, -2472883, -2538419, -2538675, -2538675, -2538675, -2604467, -2604467, -2604466, -2604722, -2670258, -2670258, -2670514, -2736050, -2736306, -2736306, -2736306, -2802098, -2802098, -2802098, -2867890, -2867890, -2933426, -2933682, -2933682, -2999474, -2999474, -3065010, -3065266, -3065266, -3131058, -3131059, -3196595, -3196851, -3262387, -3262387, -3262643, -3328179, -3328435, -3393971, -3393971, -3459763, -3459764, -3460020, -3525556, -3525556, -3591348, -3591348, -3657140, -3657140, -3657140, -3722932, -3722932, -3788725, -3788725, -3788725, -3854517, -3854517, -3920309, -3920309, -3985845, -3986101, -3986101, -4051894, -4051894, -4117686, -4117686, -4183222, -4183478, -4183478, -4249270, -4973241, -5039033, -5039033, -5104825, -5104825, -5170361, -5170618, -5170618, -5236410, -5236410, -5302202, -5302202, -5367994, -5367994, -5367994, -5433786, -5433787, -5499579, -5499579, -5565371, -5565371, -5565627, -5631163, -5631419, -5696955, -5697212, -5762748, -5763004, -5763004, -5828796, -5829052, -5894588, -5894844, -5960380, -5960637, -6026173, -6026429, -6026685, -6092221, -6092477, -6158269, -6158525, -6224061, -6224318, -6224574, -6290110, -6290366, -6356158, -6356414, -6421950, -6422206)
		val default: HeatmapColorScheme = HeatmapColorScheme(mixed_data)


		fun fromArray(argbArray: Array<Int>, transitionSteps: Int): HeatmapColorScheme {
			val data = IntArray(argbArray.size + (argbArray.size - 1) * transitionSteps)
			val offset = transitionSteps + 1
			val offsetFloat = offset.toFloat()

			for (i in 0 until argbArray.size - 1) {
				val from = argbArray[i]
				val to = argbArray[i + 1]
				val startIndex = i * offset
				data[startIndex] = from

				for (y in 1..transitionSteps) {
					data[startIndex + y] = ColorUtils.blendARGB(from, to, y.toFloat() / offsetFloat)
				}
			}

			data[0] = 0
			data[data.size - 1] = argbArray.last()

			return HeatmapColorScheme(data)
		}


		fun fromArray(argbArray: Collection<Pair<Double, Int>>, totalSteps: Int): HeatmapColorScheme {
			if (argbArray.size < 2)
				throw IllegalArgumentException("argbArray must contain at least 2 elements")

			val resultColorArray = IntArray(totalSteps)

			val sorted = argbArray.sortedBy { it.first }

			val it = sorted.iterator()

			var current = it.next()
			var next = it.next()

			val stepProgress = 1.0 / totalSteps.toDouble()
			val startAt = (current.first / stepProgress).toInt()
			val endAt = (argbArray.last().first / stepProgress).toInt()

			repeat(startAt) { resultColorArray[it] = current.second }

			var lastChangeAt = startAt
			var nextChangeAt = (next.first / stepProgress).toInt()
			var deltaRange = (nextChangeAt - lastChangeAt).toFloat()

			for (i in startAt until endAt) {
				if (nextChangeAt <= i) {
					current = next
					next = it.next()

					lastChangeAt = i
					nextChangeAt = (next.first / stepProgress).toInt()
					deltaRange = (nextChangeAt - lastChangeAt).toFloat()
				}

				val delta = (i - lastChangeAt).toFloat() / deltaRange
				resultColorArray[i] = ColorUtils.blendARGB(current.second, next.second, delta)
			}

			repeat(resultColorArray.size - endAt) { resultColorArray[endAt + it] = next.second }

			return HeatmapColorScheme(resultColorArray)
		}
	}
}

class HeatmapStamp(var width: Int, var height: Int, val stampData: FloatArray) {


	companion object {

		fun generateNonlinear(radius: Int, distFunction: (Float) -> Float): HeatmapStamp {
			assert(radius > 0)

			val diameter = radius * 2 + 1
			val stampData = FloatArray(diameter * diameter)

			for (y in 0 until diameter) {
				val yOffset = y * diameter
				for (x in 0 until diameter) {
					val xMinusRadius = x - radius
					val yMinusRadius = y - radius
					val baseDistance = sqrt(((xMinusRadius * xMinusRadius) + (yMinusRadius * yMinusRadius)).toFloat()) / (radius + 1).toFloat()
					val distance = distFunction(baseDistance).coerceIn(0f, 1f)
					stampData[x + yOffset] = 1f - distance
				}
			}

			return HeatmapStamp(diameter, diameter, stampData)
		}

		fun calculateOptimalRadius(size: Int): Int = size / 16 + 1

		/* Having a default stamp ready makes it easier for simple usage of the library
        * since there is no need to create a new stamp.
        */
		private val STAMP_DEFAULT_4_DATA = floatArrayOf(
				0.0f, 0.0f, 0.1055728f, 0.1753789f, 0.2f, 0.1753789f, 0.1055728f, 0.0f, 0.0f,
				0.0f, 0.1514719f, 0.2788897f, 0.3675445f, 0.4f, 0.3675445f, 0.2788897f, 0.1514719f, 0.0f,
				0.1055728f, 0.2788897f, 0.4343146f, 0.5527864f, 0.6f, 0.5527864f, 0.4343146f, 0.2788897f, 0.1055728f,
				0.1753789f, 0.3675445f, 0.5527864f, 0.7171573f, 0.8f, 0.7171573f, 0.5527864f, 0.3675445f, 0.1753789f,
				0.2f, 0.4f, 0.6f, 0.8f, 1.0f, 0.8f, 0.6f, 0.4f, 0.2f,
				0.1753789f, 0.3675445f, 0.5527864f, 0.7171573f, 0.8f, 0.7171573f, 0.5527864f, 0.3675445f, 0.1753789f,
				0.1055728f, 0.2788897f, 0.4343146f, 0.5527864f, 0.6f, 0.5527864f, 0.4343146f, 0.2788897f, 0.1055728f,
				0.0f, 0.1514719f, 0.2788897f, 0.3675445f, 0.4f, 0.3675445f, 0.2788897f, 0.1514719f, 0.0f,
				0.0f, 0.0f, 0.1055728f, 0.1753789f, 0.2f, 0.1753789f, 0.1055728f, 0.0f, 0.0f)

		val default9x9: HeatmapStamp = HeatmapStamp(9, 9, STAMP_DEFAULT_4_DATA)
	}
}