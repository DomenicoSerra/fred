package freenet.clients.http;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Iterator;

import freenet.client.HighLevelSimpleClient;
import freenet.config.InvalidConfigValueException;
import freenet.config.SubConfig;
import freenet.l10n.L10n;
import freenet.node.Node;
import freenet.support.Logger;
import freenet.support.api.HTTPRequest;
import freenet.support.api.StringArrCallback;

public class SymlinkerToadlet extends Toadlet {
	
	private final HashMap linkMap = new HashMap();
	private final Node node;
	SubConfig tslconfig;
	
	public SymlinkerToadlet(HighLevelSimpleClient client,final Node node) {
		super(client);
		this.node = node;
		tslconfig = new SubConfig("toadletsymlinker", node.config);
		tslconfig.register("symlinks", null, 9, true, false, "SymlinkerToadlet.symlinks", "SymlinkerToadlet.symlinksLong", 
        		new StringArrCallback() {
			public String[] get() {
				return getConfigLoadString();
			}
			public void set(String[] val) throws InvalidConfigValueException {
				//if(storeDir.equals(new File(val))) return;
				// FIXME
				throw new InvalidConfigValueException("Cannot set the plugins that's loaded.");
			}
		});
		
		String fns[] = tslconfig.getStringArr("symlinks");
		if (fns != null) {
			for (int i = 0 ; i < fns.length ; i++) {
				//System.err.println("Load: " + StringArrOption.decode(fns[i]));
				String tuple[] = fns[i].split("#");
				if (tuple.length == 2)
					System.out.println("Adding link: " + tuple[0] + " => " + tuple[1]);
				if (tuple.length == 2)
					addLink(tuple[0], tuple[1], false);
			}
		}
		
		tslconfig.finishedInitialization();
		
		addLink("/sl/search/", "/plugins/plugins.Librarian/", false);
		addLink("/sl/gallery/", "/plugins/plugins.TestGallery/", false);
	}
	
	public boolean addLink(String alias, String target, boolean store) {
		boolean ret;
		synchronized (linkMap) {
			if (linkMap.put(alias, target) == alias)
				ret = true;
			else 
				ret = false;
			Logger.normal(this, "Adding link: " + alias + " => " + target);
		}
		if(store) node.clientCore.storeConfig();
		return ret;
	}
	
	public boolean removeLink(String alias, boolean store) {
		boolean ret;
		synchronized (linkMap) {
			Object o;
			if ((o = linkMap.remove(alias))!= null)
				ret = true;
			else 
				ret = false;
			
			Logger.normal(this, "Removing link: " + alias + " => " + o);
		}
		if(store) node.clientCore.storeConfig();
		return ret;
	}
	
	private String[] getConfigLoadString() {
		String retarr[] = new String[linkMap.size()];
		synchronized (linkMap) {
			Iterator it = linkMap.keySet().iterator();
			int i = 0;
			while(it.hasNext()) {
				String key = (String)it.next();
				retarr[i++] = key + '#' + linkMap.get(key);
			}
		}
		return retarr;
	}
	
	public String supportedMethods() {
		return "GET";
	}
	
	public void handleGet(URI uri, HTTPRequest request, ToadletContext ctx)
	throws ToadletContextClosedException, IOException, RedirectException {
		String path = uri.getPath();
		String foundkey = null;
		String foundtarget = null;
		synchronized (linkMap) {
			Iterator it = linkMap.keySet().iterator();
			while (it.hasNext()) {
				String key = (String)it.next();
				if (path.startsWith(key)) {
					foundkey = key;
					foundtarget = (String)linkMap.get(key);
				}
			}
		}
		
		// TODO redirect to errorpage
		if ((foundtarget == null) || (foundkey == null)) {
			writeTextReply(ctx, 404, "Not found", 
					L10n.getString("StaticToadlet.pathNotFound"));
			return;
		}
		
		path = foundtarget + path.substring(foundkey.length());
		URI outuri = null;
		try {
			outuri = new URI(null, null,
			         path, uri.getQuery(), uri.getFragment());
		} catch (URISyntaxException e) {
			// TODO Handle error somehow
			writeHTMLReply(ctx, 200, "OK", e.getMessage());
			return;
		}
		
		uri.getRawQuery();
	     
		RedirectException re = new RedirectException();
		re.newuri = outuri;
		throw re;
	}
	
}
