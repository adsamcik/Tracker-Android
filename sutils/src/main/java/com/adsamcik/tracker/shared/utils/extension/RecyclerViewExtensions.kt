package com.adsamcik.tracker.shared.utils.extension

import androidx.recyclerview.widget.RecyclerView

/**
 * Returns true if adapter has no items
 */
val RecyclerView.Adapter<*>.isEmpty: Boolean get() = itemCount == 0
