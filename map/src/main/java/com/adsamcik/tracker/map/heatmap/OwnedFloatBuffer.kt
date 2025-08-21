package com.adsamcik.tracker.map.heatmap

/**
 * A wrapper around a pooled FloatArray that automatically returns it to the pool when closed.
 * Eliminates unnecessary copies during normalization while maintaining clear ownership.
 */
internal class OwnedFloatBuffer private constructor(
    val data: FloatArray,
    private val pool: ScratchBufferPool
) : AutoCloseable {
    
    companion object {
        fun acquire(size: Int): OwnedFloatBuffer {
            val array = ScratchBufferPool.acquire(size)
            return OwnedFloatBuffer(array, ScratchBufferPool)
        }
    }
    
    override fun close() {
        ScratchBufferPool.release(data)
    }
    
    /** Get a copy of the data for long-term storage. */
    fun copyData(): FloatArray = data.copyOf()
}
