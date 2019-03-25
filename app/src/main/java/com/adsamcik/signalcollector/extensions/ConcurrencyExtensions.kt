package com.adsamcik.signalcollector.extensions

import java.util.concurrent.locks.Lock

inline fun Lock.lock(func: ()->Unit) {
	lock()
	func()
	unlock()
}