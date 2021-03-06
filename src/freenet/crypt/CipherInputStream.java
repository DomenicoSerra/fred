/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.crypt;

import java.io.*;

/**
 * Implements a Java InputStream that is encrypted with any symmetric block
 * cipher (implementing the BlockCipher interface).
 * 
 * This stream operates in Periodic Cipher Feedback Mode (PCFB), allowing 
 * byte at a time encryption with no additional encryption workload.
 */

public class CipherInputStream extends FilterInputStream {

    private final PCFBMode ctx;
    private boolean needIV = false;

    public CipherInputStream(BlockCipher c, InputStream in) {
        this(PCFBMode.create(c), in);
    }

    public CipherInputStream(BlockCipher c, InputStream in, boolean readIV) 
        throws IOException {

        this(PCFBMode.create(c), in);
        if (readIV) ctx.readIV(this.in);
    }

    public boolean needIV() {
        return needIV;
    }
    
    /**
     * This constructor causes the IV to be read of the connection the
     * first time one of the read messages is read (if later is set).
     */
    public CipherInputStream(BlockCipher c, InputStream in, boolean readIV,
                             boolean later) throws IOException {
        this(PCFBMode.create(c), in);
        if (readIV && later)
            needIV = true;
        else if (readIV)
            ctx.readIV(this.in);
    }

    public CipherInputStream(BlockCipher c, InputStream in, byte[] iv) {
        this(new PCFBMode(c, iv), in);
    }

    public CipherInputStream(PCFBMode ctx, InputStream in) {
        super(in);
        this.ctx = ctx;
    }

    //int read = 0;
    public int read() throws IOException {
        if (needIV) {
            ctx.readIV(in);
            needIV = false;
        }
        //System.err.println("CIS READING");
        int rv=in.read();

        //if ((read++ % 5) == 0)
        //    System.err.println("CIS READ " + read);
        return (rv==-1 ? -1 : ctx.decipher(rv));
    }

    public int read(byte[] b, int off, int len) throws IOException {
        if (needIV) {
            ctx.readIV(in);
            needIV = false;
        }
        //System.err.println("CIS READING IN: " + in.toString() + " LEN: " + 
        //                   len);
        int rv=in.read(b, off, len);
        //System.err.println("CIS READ " + (read += rv));
        if (rv != -1) {
            ctx.blockDecipher(b, off, rv);
            return rv;
        } else 
            return -1;
    }

    public int available() throws IOException {
        int r = in.available();
        return (needIV ? Math.max(0, r - ctx.lengthIV()) : r);
    }

    public PCFBMode getCipher() {
    	return ctx;
    }
}








