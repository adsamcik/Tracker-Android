package com.adsamcik.signalcollector.misc.extension

import java.util.concurrent.locks.Lock

inline fun Lock.lock(func: ()->Unit) {
	lock()
	func()
	unlock()
}