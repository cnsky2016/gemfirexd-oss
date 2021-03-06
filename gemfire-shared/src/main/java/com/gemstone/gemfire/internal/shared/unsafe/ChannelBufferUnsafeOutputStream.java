/*
 * Copyright (c) 2010-2015 Pivotal Software, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */

package com.gemstone.gemfire.internal.shared.unsafe;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;

import com.gemstone.gemfire.internal.shared.ChannelBufferOutputStream;
import com.gemstone.gemfire.internal.shared.ClientSharedUtils;
import com.gemstone.gemfire.internal.shared.OutputStreamChannel;

/**
 * A somewhat more efficient implementation of {@link ChannelBufferOutputStream}
 * using internal unsafe class (~30% in raw single byte write calls). Use this
 * only when {@link UnsafeHolder#getDirectByteBufferAddressMethod()} returns
 * non-null. Alternatively use {@link UnsafeHolder#newChannelBufferOutputStream}
 * method to create either this or {@link ChannelBufferOutputStream} depending
 * on availability.
 * <p>
 * NOTE: THIS CLASS IS NOT THREAD-SAFE BY DESIGN. IF IT IS USED CONCURRENTLY
 * BY MULTIPLE THREADS THEN BAD THINGS CAN HAPPEN DUE TO UNSAFE MEMORY WRITES.
 * <p>
 * Note that the close() method of this class does not closing the underlying
 * channel.
 * 
 * @author swale
 * @since gfxd 1.1
 */
public class ChannelBufferUnsafeOutputStream extends OutputStreamChannel {

  protected final ByteBuffer buffer;
  protected final long baseAddress;
  /**
   * Actual buffer position (+baseAddress) accounting is done by this. Buffer
   * position is adjusted during refill and other places where required using
   * this.
   */
  protected long addrPosition;
  protected long addrLimit;

  protected static final sun.misc.Unsafe unsafe = UnsafeHolder.getUnsafe();

  /**
   * Some minimum buffer size, particularly for longs and encoding UTF strings
   * efficiently. If reducing this, then consider the logic in
   * {@link ChannelBufferUnsafeDataOutputStream#writeUTF(String)} carefully.
   */
  protected static final int MIN_BUFFER_SIZE = 10;

  public ChannelBufferUnsafeOutputStream(WritableByteChannel channel)
      throws IOException {
    this(channel, ChannelBufferOutputStream.DEFAULT_BUFFER_SIZE);
  }

  public ChannelBufferUnsafeOutputStream(WritableByteChannel channel,
      int bufferSize) throws IOException {
    super(channel);
    // expect minimum bufferSize of 10 bytes
    if (bufferSize < MIN_BUFFER_SIZE) {
      throw new IllegalArgumentException(
          "ChannelBufferUnsafeDataOutputStream: buffersize=" + bufferSize
              + " too small (minimum " + MIN_BUFFER_SIZE + ')');
    }
    this.buffer = allocateBuffer(bufferSize);

    try {
      this.baseAddress = (Long)UnsafeHolder.getDirectByteBufferAddressMethod()
          .invoke(this.buffer);
      resetBufferPositions();
    } catch (Exception e) {
      throw ClientSharedUtils.newRuntimeException(
          "failed in creating an 'unsafe' buffered channel stream", e);
    }
  }

  protected final void resetBufferPositions() {
    this.addrPosition = this.baseAddress + this.buffer.position();
    this.addrLimit = this.baseAddress + this.buffer.limit();
  }

  protected ByteBuffer allocateBuffer(int bufferSize) {
    return ByteBuffer.allocateDirect(bufferSize);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void write(int b) throws IOException {
    final long addrPos = this.addrPosition;
    if (addrPos < this.addrLimit) {
      unsafe.putByte(addrPos, (byte)(b & 0xff));
      this.addrPosition++;
    }
    else {
      flushBufferBlocking(this.buffer);
      unsafe.putByte(this.addrPosition++, (byte)(b & 0xff));
    }
  }

  protected final void write_(byte[] b, int off, int len) throws IOException {
    if (len == 1) {
      final long addrPos = this.addrPosition;
      if (addrPos < this.addrLimit) {
        unsafe.putByte(addrPos, b[off]);
        this.addrPosition++;
      }
      else {
        flushBufferBlocking(this.buffer);
        unsafe.putByte(this.addrPosition++, b[off]);
      }
      return;
    }

    while (len > 0) {
      final long addrPos = this.addrPosition;
      final int remaining = (int)(this.addrLimit - addrPos);
      if (len <= remaining) {
        UnsafeHolder.bufferPut(b, addrPos, off, len, unsafe);
        this.addrPosition += len;
        return;
      }
      else {
        // copy b to buffer and flush
        if (remaining > 0) {
          UnsafeHolder.bufferPut(b, addrPos, off, remaining, unsafe);
          this.addrPosition += remaining;
          len -= remaining;
          off += remaining;
        }
        flushBufferBlocking(this.buffer);
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void write(byte[] b) throws IOException {
    write_(b, 0, b.length);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void write(byte[] b, int off, int len) throws IOException {
    if (UnsafeHolder.checkBounds(off, len, b.length)) {
      write_(b, off, len);
    }
    else {
      throw new IndexOutOfBoundsException("offset=" + off + " length=" + len
          + " size=" + b.length);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final int write(ByteBuffer src) throws IOException {
    // We will just use our ByteBuffer for the write. It might be possible
    // to get slight performance advantage in using unsafe instead, but
    // copying from source ByteBuffer will not be efficient without
    // reflection to get src's native address in case it is a direct
    // byte buffer. Avoiding the complication since the benefit will be
    // very small in any case (and reflection cost may well offset that).

    // adjust this buffer position first
    this.buffer.position((int)(this.addrPosition - this.baseAddress));
    // now we are actually set to just call base class method
    try {
      return super.writeBuffered(src, this.buffer);
    } finally {
      // finally reset the raw positions from buffer
      resetBufferPositions();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void flush() throws IOException {
    if (this.addrPosition > this.baseAddress) {
      flushBufferBlocking(this.buffer);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final boolean isOpen() {
    return this.channel.isOpen();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void close() throws IOException {
    flushBufferBlocking(this.buffer);
  }

  protected void flushBufferBlocking(final ByteBuffer buffer)
      throws IOException {
    buffer.position((int)(this.addrPosition - this.baseAddress));
    buffer.flip();
    try {
      do {
        writeBuffer(buffer);
      } while (buffer.hasRemaining());
    } finally {
      if (buffer.hasRemaining()) {
        buffer.compact();
      }
      else {
        buffer.clear();
      }
      resetBufferPositions();
    }
  }

  @Override
  protected boolean flushBufferNonBlocking(final ByteBuffer buffer,
      boolean isChannelBuffer) throws IOException {
    if (isChannelBuffer) {
      try {
        return super.flushBufferNonBlocking(buffer, true);
      } finally {
        resetBufferPositions();
      }
    }
    else {
      return super.flushBufferNonBlocking(buffer, false);
    }
  }
}
