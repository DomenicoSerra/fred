/* This code is part of Freenet. It is distributed under the GNU General
* Public License, version 2 (or at your option any later version). See
* http://www.gnu.org/ for further details of the GPL. */
package freenet.support.compress;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;

import freenet.support.Logger;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;
import freenet.support.io.CountedOutputStream;
import freenet.support.io.NoCloseProxyOutputStream;
import freenet.support.io.HeaderStreams;

/**
** {@link Compressor} for BZip2 streams.
**
** Due to historical reasons (we used to use the ant-tools bz2 libraries,
** rather than commons-compress) the compressed streams **DO NOT** have the
** standard "BZ" header.
*/
// WARNING: THIS CLASS IS STORED IN DB4O -- THINK TWICE BEFORE ADD/REMOVE/RENAME FIELDS
public class Bzip2Compressor implements Compressor {

	final public static byte[] BZ_HEADER = "BZ".getBytes();

	public Bucket compress(Bucket data, BucketFactory bf, long maxReadLength, long maxWriteLength) throws IOException, CompressionOutputSizeException {
		Bucket output = bf.makeBucket(maxWriteLength);
		InputStream is = null;
		OutputStream os = null;
		try {
			is = data.getInputStream();
			os = output.getOutputStream();
			compress(is, os, maxReadLength, maxWriteLength);
			is.close();
			is = null;
			os.close();
			os = null;
		} finally {
			if(is != null) is.close();
			if(os != null) os.close();
		}
		return output;
	}

	public long compress(InputStream is, OutputStream os, long maxReadLength, long maxWriteLength) throws IOException, CompressionOutputSizeException {
		if(maxReadLength <= 0)
			throw new IllegalArgumentException();
		BufferedInputStream bis = new BufferedInputStream(is, 32768);
		BZip2CompressorOutputStream bz2os = null;
		try {
			CountedOutputStream cos = new CountedOutputStream(os);
			bz2os = new BZip2CompressorOutputStream(HeaderStreams.dimOutput(BZ_HEADER, new BufferedOutputStream(cos, 32768)));
			long read = 0;
			// Bigger input buffer, so can compress all at once.
			// Won't hurt on I/O either, although most OSs will only return a page at a time.
			byte[] buffer = new byte[32768];
			while(true) {
				int l = (int) Math.min(buffer.length, maxReadLength - read);
				int x = l == 0 ? -1 : bis.read(buffer, 0, buffer.length);
				if(x <= -1) break;
				if(x == 0) throw new IOException("Returned zero from read()");
				bz2os.write(buffer, 0, x);
				read += x;
				if(cos.written() > maxWriteLength)
					throw new CompressionOutputSizeException();
			}
			bz2os.flush();
			cos.flush();
			bz2os.close();
			bz2os = null;
			return cos.written();
		} finally {
			if(bz2os != null) {
				bz2os.flush();
				bz2os.close();
			}

		}
	}

	public long decompress(InputStream is, OutputStream os, long maxLength, long maxCheckSizeBytes) throws IOException, CompressionOutputSizeException {
		BZip2CompressorInputStream bz2is = new BZip2CompressorInputStream(HeaderStreams.augInput(BZ_HEADER, new BufferedInputStream(is)));
		long written = 0;
		int bufSize = 32768;
		if(maxLength > 0 && maxLength < (long)bufSize)
			bufSize = (int)maxLength;
		byte[] buffer = new byte[bufSize];
		while(true) {
			int expectedBytesRead = (int) Math.min(buffer.length, maxLength - written);
			// We can over-read to determine whether we have over-read.
			// We enforce maximum size this way.
			// FIXME there is probably a better way to do this!
			int bytesRead = bz2is.read(buffer, 0, buffer.length);
			if(expectedBytesRead < bytesRead) {
				Logger.normal(this, "expectedBytesRead="+expectedBytesRead+", bytesRead="+bytesRead+", written="+written+", maxLength="+maxLength+" throwing a CompressionOutputSizeException");
				if(maxCheckSizeBytes > 0) {
					written += bytesRead;
					while(true) {
						expectedBytesRead = (int) Math.min(buffer.length, maxLength + maxCheckSizeBytes - written);
						bytesRead = bz2is.read(buffer, 0, expectedBytesRead);
						if(bytesRead <= -1) throw new CompressionOutputSizeException(written);
						if(bytesRead == 0) throw new IOException("Returned zero from read()");
						written += bytesRead;
					}
				}
				throw new CompressionOutputSizeException();
			}
			if(bytesRead <= -1) return written;
			if(bytesRead == 0) throw new IOException("Returned zero from read()");
			os.write(buffer, 0, bytesRead);
			written += bytesRead;
		}
	}

	public int decompress(byte[] dbuf, int i, int j, byte[] output) throws CompressionOutputSizeException {
		// Didn't work with Inflater.
		// FIXME fix sometimes to use Inflater - format issue?
		ByteArrayInputStream bais = new ByteArrayInputStream(dbuf, i, j);
		ByteArrayOutputStream baos = new ByteArrayOutputStream(output.length);
		int bytes = 0;
		try {
			decompress(bais, baos, output.length, -1);
			bytes = baos.size();
		} catch (IOException e) {
			// Impossible
			throw new Error("Got IOException: " + e.getMessage(), e);
		}
		byte[] buf = baos.toByteArray();
		System.arraycopy(buf, 0, output, 0, bytes);
		return bytes;
	}
}
