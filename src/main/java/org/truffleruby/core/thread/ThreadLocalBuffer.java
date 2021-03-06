/*
 * Copyright (c) 2020 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.core.thread;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.profiles.ConditionProfile;

import org.truffleruby.extra.ffi.Pointer;

public final class ThreadLocalBuffer {

    public static final ThreadLocalBuffer NULL_BUFFER = new ThreadLocalBuffer(true, Pointer.NULL, 0, 0, null);

    final boolean ownsBuffer;
    public final Pointer start;
    final long used;
    final long remaining;
    final ThreadLocalBuffer parent;

    private ThreadLocalBuffer(
            boolean isBlockStart,
            Pointer start,
            long used,
            long remaining,
            ThreadLocalBuffer parent) {
        this.ownsBuffer = isBlockStart;
        this.start = start;
        this.used = used;
        this.remaining = remaining;
        this.parent = parent;
    }

    public ThreadLocalBuffer free(ConditionProfile freeProfile) {
        if (freeProfile.profile(ownsBuffer)) {
            start.freeNoAutorelease();
        }
        return parent;
    }

    public void freeAll() {
        ThreadLocalBuffer current = this;
        while (current != null) {
            current.free(ConditionProfile.getUncached());
            current = current.parent;
        }
    }

    public ThreadLocalBuffer allocate(long size, ConditionProfile allocationProfile) {
        if (allocationProfile.profile(remaining >= size)) {
            return new ThreadLocalBuffer(
                    false,
                    new Pointer(this.start.getAddress() + this.used, size),
                    size,
                    remaining - size,
                    this);
        } else {
            return allocateNewBlock(size);
        }
    }

    @TruffleBoundary
    private ThreadLocalBuffer allocateNewBlock(long size) {
        // Allocate a new buffer. Chain it if we aren't the default thread buffer, otherwise make a new default buffer.
        final long blockSize = Math.max(size, 1024);
        if (this.parent != null) {
            return new ThreadLocalBuffer(true, Pointer.malloc(blockSize), size, blockSize - size, this);
        } else {
            // Free the old block
            this.free(ConditionProfile.getUncached());
            // Create new bigger block
            final ThreadLocalBuffer newParent = new ThreadLocalBuffer(
                    true,
                    Pointer.malloc(blockSize),
                    0,
                    blockSize,
                    null);
            return new ThreadLocalBuffer(
                    false,
                    new Pointer(newParent.start.getAddress(), size),
                    size,
                    blockSize - size,
                    newParent);
        }
    }
}
