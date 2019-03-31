package com.adsamcik.signalcollector.misc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.withLock
import kotlin.concurrent.write

typealias ConditionChecker<T> = (T) -> Boolean
typealias JobFunction = suspend CoroutineScope.() -> Unit

open class ConditionVariable<T>(default: T) {
	protected val waiterLock = ReentrantLock()
	protected val valueLock = ReentrantReadWriteLock()

	protected val waiters = mutableListOf<Pair<ConditionChecker<T>, JobFunction>>()

	protected var unsafeValue: T = default

	var value: T
		get() {
			valueLock.read {
				return unsafeValue
			}
		}
		set(value) {
			valueLock.write {
				unsafeValue = value

				waiterLock.withLock {
					waiters.removeAll {
						if (it.first.invoke(value)) {
							GlobalScope.launch(block = it.second)
							true
						} else
							false
					}
				}
			}
		}

	fun addWaiter(checker: ConditionChecker<T>, job: JobFunction) {
		waiterLock.withLock {
			waiters.add(Pair(checker, job))
		}
	}
}

class ConditionVariableInt(value: Int) : ConditionVariable<Int>(value) {
	fun incrementAndGet(): Int {
		valueLock.write {
			return ++unsafeValue
		}
	}

	fun decrementAndGet(): Int {
		valueLock.write {
			return --unsafeValue
		}
	}
}