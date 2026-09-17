package com.adsamcik.tracker.diagnostics

enum class TrackingDiagnosticCountBucket {
	NOT_REPORTED,
	ZERO,
	ONE,
	TWO_TO_FOUR,
	FIVE_TO_SIXTEEN,
	SEVENTEEN_TO_SIXTY_FOUR,
	SIXTY_FIVE_OR_MORE,
	;

	companion object {
		@JvmSynthetic
		internal fun fromCount(count: Long): TrackingDiagnosticCountBucket {
			require(count >= 0L) { "Count must be non-negative" }
			return when (count) {
				0L -> ZERO
				1L -> ONE
				in 2L..4L -> TWO_TO_FOUR
				in 5L..16L -> FIVE_TO_SIXTEEN
				in 17L..64L -> SEVENTEEN_TO_SIXTY_FOUR
				else -> SIXTY_FIVE_OR_MORE
			}
		}
	}
}

enum class TrackingDiagnosticDurationBucket {
	NOT_REPORTED,
	UNDER_TEN_MILLISECONDS,
	TEN_TO_NINETY_NINE_MILLISECONDS,
	ONE_HUNDRED_TO_NINE_HUNDRED_NINETY_NINE_MILLISECONDS,
	ONE_TO_FOUR_SECONDS,
	FIVE_TO_TWENTY_NINE_SECONDS,
	THIRTY_SECONDS_OR_MORE,
	;

	companion object {
		@JvmSynthetic
		internal fun fromMilliseconds(durationMilliseconds: Long): TrackingDiagnosticDurationBucket {
			require(durationMilliseconds >= 0L) { "Duration must be non-negative" }
			return when (durationMilliseconds) {
				in 0L..9L -> UNDER_TEN_MILLISECONDS
				in 10L..99L -> TEN_TO_NINETY_NINE_MILLISECONDS
				in 100L..999L -> ONE_HUNDRED_TO_NINE_HUNDRED_NINETY_NINE_MILLISECONDS
				in 1_000L..4_999L -> ONE_TO_FOUR_SECONDS
				in 5_000L..29_999L -> FIVE_TO_TWENTY_NINE_SECONDS
				else -> THIRTY_SECONDS_OR_MORE
			}
		}
	}
}

enum class TrackingDiagnosticBacklogBucket {
	NOT_REPORTED,
	EMPTY,
	ONE,
	TWO_TO_EIGHT,
	NINE_TO_THIRTY_TWO,
	THIRTY_THREE_TO_ONE_HUNDRED_TWENTY_EIGHT,
	ONE_HUNDRED_TWENTY_NINE_OR_MORE,
	;

	companion object {
		@JvmSynthetic
		internal fun fromItemCount(itemCount: Long): TrackingDiagnosticBacklogBucket {
			require(itemCount >= 0L) { "Backlog count must be non-negative" }
			return when (itemCount) {
				0L -> EMPTY
				1L -> ONE
				in 2L..8L -> TWO_TO_EIGHT
				in 9L..32L -> NINE_TO_THIRTY_TWO
				in 33L..128L -> THIRTY_THREE_TO_ONE_HUNDRED_TWENTY_EIGHT
				else -> ONE_HUNDRED_TWENTY_NINE_OR_MORE
			}
		}
	}
}

enum class TrackingDiagnosticSizeBucket {
	NOT_REPORTED,
	EMPTY,
	UP_TO_ONE_KIBIBYTE,
	UP_TO_FOUR_KIBIBYTES,
	UP_TO_SIXTEEN_KIBIBYTES,
	UP_TO_SIXTY_FOUR_KIBIBYTES,
	OVER_SIXTY_FOUR_KIBIBYTES,
	;

	companion object {
		@JvmSynthetic
		internal fun fromBytes(byteCount: Long): TrackingDiagnosticSizeBucket {
			require(byteCount >= 0L) { "Size must be non-negative" }
			return when (byteCount) {
				0L -> EMPTY
				in 1L..1_024L -> UP_TO_ONE_KIBIBYTE
				in 1_025L..4_096L -> UP_TO_FOUR_KIBIBYTES
				in 4_097L..16_384L -> UP_TO_SIXTEEN_KIBIBYTES
				in 16_385L..65_536L -> UP_TO_SIXTY_FOUR_KIBIBYTES
				else -> OVER_SIXTY_FOUR_KIBIBYTES
			}
		}
	}
}
