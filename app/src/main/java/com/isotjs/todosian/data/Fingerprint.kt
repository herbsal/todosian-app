package com.isotjs.todosian.data

/** Order-independent 64-bit FNV-1a hash over (path, stamp) pairs. */
internal fun fingerprintOf(items: List<Pair<String, String>>): Long {
    var hash = FNV_OFFSET
    for ((path, stamp) in items.sortedBy { it.first }) {
        for (ch in path) hash = (hash xor ch.code.toLong()) * FNV_PRIME
        hash = (hash xor 0x1F) * FNV_PRIME
        for (ch in stamp) hash = (hash xor ch.code.toLong()) * FNV_PRIME
        hash = (hash xor 0x1E) * FNV_PRIME
    }
    return hash
}

private const val FNV_OFFSET = -0x340d631b7bdddcdbL
private const val FNV_PRIME = 0x100000001b3L
