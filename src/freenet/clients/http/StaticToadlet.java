package freenet.clients.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;

import freenet.client.DefaultMIMETypes;
import freenet.client.HighLevelSimpleClient;
import freenet.l10n.L10n;
import freenet.support.api.Bucket;
import freenet.support.api.HTTPRequest;

/**
 * Static Toadlet.
 * Serve up static files
 */
public class StaticToadlet extends Toadlet {
	StaticToadlet(HighLevelSimpleClient client) {
		super(client);
	}
	
	private static final String ROOT_URL = "/static/";
	private static final String ROOT_PATH = "staticfiles/";
	
	public void handleGet(URI uri, HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		String path = uri.getPath();
		
		if (!path.startsWith(ROOT_URL)) {
			// we should never get any other path anyway
			return;
		}
		try {
			path = path.substring(ROOT_URL.length());
		} catch (IndexOutOfBoundsException ioobe) {
			this.sendErrorPage(ctx, 404, l10n("pathNotFoundTitle"), l10n("pathNotFound"));
			return;
		}
		
		// be very strict about what characters we allow in the path, since
		if (!path.matches("^[A-Za-z0-9\\._\\/\\-]*$") || (path.indexOf("..") != -1)) {
			this.sendErrorPage(ctx, 404, l10n("pathNotFoundTitle"), l10n("pathInvalidChars"));
			return;
		}
		
		InputStream strm = getClass().getResourceAsStream(ROOT_PATH+path);
		if (strm == null) {
			this.sendErrorPage(ctx, 404, l10n("pathNotFoundTitle"), l10n("pathNotFound"));
			return;
		}
		Bucket data = ctx.getBucketFactory().makeBucket(strm.available());
		OutputStream os = data.getOutputStream();
		byte[] cbuf = new byte[4096];
		while(true) {
			int r = strm.read(cbuf);
			if(r == -1) break;
			os.write(cbuf, 0, r);
		}
		strm.close();
		os.close();
		
		ctx.sendReplyHeaders(200, "OK", null, DefaultMIMETypes.guessMIMEType(path, false), data.size());

		ctx.writeData(data);
		data.free();
	}
	
	private String l10n(String key) {
		return L10n.getString("StaticToadlet."+key);
	}

	public String supportedMethods() {
		return "GET";
	}

}
