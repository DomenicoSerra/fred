/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support;

import java.io.IOException;
import java.io.StringWriter;

/**
 * Decode encoded URLs (or parts of URLs). @see URLEncoder.
 * This class does NOT decode application/x-www-form-urlencoded
 * strings, unlike @see java.net.URLDecoder. What it does is
 * decode bits of URIs, in UTF-8. This simply means that it 
 * converts encoded characters (assuming a charset of UTF-8).
 * java.net.URI does similar things internally.
 * 
 * @author <a href="http://www.doc.ic.ac.uk/~twh1/">Theodore Hong</a>
 * Originally!
 **/
public class URLDecoder
{
    // test harness
    public static void main(String[] args) throws URLEncodedFormatException {
	for (int i = 0; i < args.length; i++) {
	    System.out.println(args[i] + " -> " + decode(args[i], false));
	}
    }

    /**
	 * Decodes a URLEncoder format string.
	 *
	 * @param s String to be translated.
	 * @param tolerant If true, be tolerant of bogus escapes; bogus escapes are treated as
	 * just plain characters. Not recommended; a hack to allow users to paste in URLs 
	 * containing %'s.
	 * @return the translated String.
	 *
	 **/
	public static String decode(String s, boolean tolerant) throws URLEncodedFormatException {
		if (s.length() == 0)
			return "";
		int len = s.length();
		StringWriter decoded = new StringWriter();
		boolean hasDecodedSomething = false;

		for (int i = 0; i < len; i++) {
			char c = s.charAt(i);
			if (Character.isLetterOrDigit(c))
				decoded.write(c);
			else if (c == '%') {
				if (i >= len - 2) {
					throw new URLEncodedFormatException(s);
				}
				char[] hexChars = new char[2];

				hexChars[0] = s.charAt(++i);
				hexChars[1] = s.charAt(++i);

				String hexval = new String(hexChars);
				try {
					long read = Fields.hexToLong(hexval);
					if (read == 0)
						throw new URLEncodedFormatException("Can't encode" + " 00");
					decoded.write((int) read);
					hasDecodedSomething = true;
				} catch (NumberFormatException nfe) {
					// Not encoded?
					if(tolerant && !hasDecodedSomething) {
						decoded.write('%');
						decoded.write(hexval);
						continue;
					}
					
					throw new URLEncodedFormatException("Not a two character hex % escape: "+hexval+" in "+s);
				}
			} else {
				decoded.write(c);
			}
		}
		try {
			decoded.close();
			return decoded.toString();
		} catch (IOException ioe1) {
			/* if this throws something's wrong */
		}
		throw new URLEncodedFormatException(s);
	}

}
