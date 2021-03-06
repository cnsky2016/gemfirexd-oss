/*
   Derby - Class com.pivotal.gemfirexd.internal.impl.drda.LayerBStreamedEXTDTAReaderInputStream

   Licensed to the Apache Software Foundation (ASF) under one
   or more contributor license agreements.  See the NOTICE file
   distributed with this work for additional information
   regarding copyright ownership.  The ASF licenses this file
   to you under the Apache License, Version 2.0 (the
   "License"); you may not use this file except in compliance
   with the License.  You may obtain a copy of the License at
   
   http://www.apache.org/licenses/LICENSE-2.0
   
   Unless required by applicable law or agreed to in writing,
   software distributed under the License is distributed on an
   "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
   KIND, either express or implied.  See the License for the
   specific language governing permissions and limitations
   under the License.
*/
package com.pivotal.gemfirexd.internal.impl.drda;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Implementation of InputStream which get EXTDTA from the DDMReader.
 * This class can be used to stream LOBs from Network client to the
 * Network server.
 *
 * Furthermore, this class is used when layer B streaming is carried out and
 * expects corresponding DDMReader start layer B streaming 
 * when the object of this class is instantiated.
 *
 */
final class LayerBStreamedEXTDTAReaderInputStream extends EXTDTAReaderInputStream
{
    /**
     * Constructor
     * @param reader The reader to get data from
     * @exception DRDAProtocolException if thrown while initializing current 
     *                                  buffer.
     */
    LayerBStreamedEXTDTAReaderInputStream(final DDMReader reader) 
        throws DRDAProtocolException
    {
        super();
        this.reader = reader;
        this.currentBuffer = 
            reader.readLOBInitStream();
    }

    /**
     * Reads the next byte of data from the input stream.
     * 
     * <p> This subclass of InputStream implements this method by reading
     * the next byte from the current buffer. If there is more data,
     * it will be requested a new buffer from the DDMReader.
     *
     * @return     the next byte of data, or <code>-1</code> if the end of the
     *             stream is reached.
     * @exception  IOException  if an I/O error occurs.
     * @see        java.io.InputStream#read()
     */
    public final int read() 
        throws IOException
    {
        int val = (currentBuffer == null) ? -1 : currentBuffer.read();
        if (val < 0) {
            val = refreshCurrentBuffer();
        }
        return val;
    }
    
    /**
     * Reads up to <code>len</code> bytes of data from the input stream into
     * an array of bytes.  An attempt is made to read as many as
     * <code>len</code> bytes, but a smaller number may be read, possibly
     * zero. The number of bytes actually read is returned as an integer.
     *
     * This subclass implements this method by calling this method on the 
     * current buffer, which is an instance of ByteArrayInputStream. If the
     * current buffer does not have any data, it will be requested a new
     * buffer from the DDMReader.
     *
     * @param      b     the buffer into which the data is read.
     * @param      off   the start offset in array <code>b</code>
     *                   at which the data is written.
     * @param      len   the maximum number of bytes to read.
     * @return     the total number of bytes read into the buffer, or
     *             <code>-1</code> if there is no more data because the end of
     *             the stream has been reached.
     * @exception  IOException  if an I/O error occurs.
     * @exception  NullPointerException  if <code>b</code> is <code>null</code>.
     * @see        java.io.InputStream#read(byte[], int, int)
     */
    public final int read(final byte[] b,
                          final int off,
                          final int len) 
        throws IOException
    {
        int val = currentBuffer.read(b, off, len);
        
        if (val < 0 && 
            reader.doingLayerBStreaming() ) {
            
            currentBuffer = 
                reader.readLOBContinuationStream();
            val = currentBuffer.read(b, off, len);
            
        }
        return val;
    }

    /**
     * Returns the number of bytes that can be read (or skipped over) from
     * this input stream without blocking by the next caller of a method for
     * this input stream.  
     *
     * <p> This subclass implements this method by calling available on 
     *     the current buffer, which is a ByteInputStreamReader.
     *
     * @return     the number of bytes that can be read from this input stream
     *             without blocking.     
     */
    public final int available() 
    {
        return currentBuffer.available();
    }

    
    /**
     * Refresh the current buffer from the DDMReader
     * @exception IOException if there is a IOException when
     *                        refreshing the buffer from DDMReader
     * @return the next byte of data, or <code>-1</code> if the end of the
     *         stream is reached and layer B streaming was finished.
     */
    private int refreshCurrentBuffer() 
        throws IOException
    {
        
        if( ! reader.doingLayerBStreaming() )
            return -1;
        
        currentBuffer = 
            reader.readLOBContinuationStream();
        return currentBuffer.read();
    }
    
    
    /** DDMReader. Used to get more data. */
    private final DDMReader reader;
    
    /** Current data buffer */
    private ByteArrayInputStream currentBuffer;

}
