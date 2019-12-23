package com.adsamcik.tracker.common.extension

import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction

/**
 * Creates new transaction for a [FragmentManager].
 * Transaction is committed using apply.
 *
 * @param func Specify all actions you want to do in this transaction (eg. replace(id, fragment))
 */
inline fun FragmentManager.transaction(func: FragmentTransaction.() -> FragmentTransaction) {
	beginTransaction().func().commit()
}

/**
 * Creates new transaction for a [FragmentManager].
 * Transaction is committed using commitAllowingStateLoss.
 *
 * @param func Specify all actions you want to do in this transaction (eg. replace(id, fragment))
 */
inline fun FragmentManager.transactionStateLoss(func: FragmentTransaction.() -> FragmentTransaction) {
	beginTransaction().func().commitAllowingStateLoss()
}
