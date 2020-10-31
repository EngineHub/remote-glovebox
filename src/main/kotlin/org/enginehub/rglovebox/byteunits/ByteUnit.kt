/*
 * This file is part of remote-glovebox, licensed under GPLv3 or any later version.
 *
 * Copyright (c) EngineHub <https://enginehub.org>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.enginehub.rglovebox.byteunits

import java.math.BigInteger

private val THOUSAND = BigInteger.valueOf(1000)
private val THOUSAND_24 = BigInteger.valueOf(1024)

enum class ByteUnit(private val labels: Set<String>, val factor: BigInteger) {
    BYTE(setOf("B", "b"), 1),
    KILOBYTE(setOf("KB", "kb"), 1_000),
    KIBIBYTE(setOf("KiB"), 1_024),
    MEGABYTE(setOf("MB", "mb"), 1_000 * 1_000),
    MEBIBYTE(setOf("MiB"), 1_024 * 1_024),
    GIGABYTE(setOf("GB", "gb"), 1_000 * 1_000 * 1_000),
    GIBIBYTE(setOf("GiB"), 1_024 * 1_024 * 1_024),
    TERABYTE(setOf("TB", "tb"), THOUSAND * THOUSAND * THOUSAND * THOUSAND),
    TEBIBYTE(setOf("TiB"), THOUSAND_24 * THOUSAND_24 * THOUSAND_24 * THOUSAND_24),
    PETABYTE(setOf("PB", "pb"), THOUSAND * THOUSAND * THOUSAND * THOUSAND * THOUSAND),
    PEBIBYTE(setOf("PiB"), THOUSAND_24 * THOUSAND_24 * THOUSAND_24 * THOUSAND_24 * THOUSAND_24),
    ;

    companion object {
        fun parse(value: String): ByteUnit {
            return values().firstOrNull { it.labels.contains(value) }
                ?: throw IllegalArgumentException("'$value' is not a valid byte unit")
        }
    }

    constructor(labels: Set<String>, factor: Long) : this(labels, factor.toBigInteger())

    init {
        check(labels.isNotEmpty()) { "At least one label must be given" }
    }

    fun convert(to: ByteUnit, value: BigInteger): BigInteger = value * factor / to.factor

    override fun toString() = labels.first()
}