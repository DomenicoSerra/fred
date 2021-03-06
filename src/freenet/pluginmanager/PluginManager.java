/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.pluginmanager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.JarURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.Vector;
import java.util.jar.JarFile;

import freenet.config.InvalidConfigValueException;
import freenet.config.SubConfig;
import freenet.l10n.L10n;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.node.Ticker;
import freenet.node.useralerts.SimpleUserAlert;
import freenet.node.useralerts.UserAlert;
import freenet.support.Logger;
import freenet.support.URIPreEncoder;
import freenet.support.api.HTTPRequest;
import freenet.support.api.StringArrCallback;
import freenet.support.io.FileUtil;

public class PluginManager {

	/*
	 *
	 * TODO: Synchronize
	 * TODO: Synchronize
	 * TODO: Synchronize
	 * TODO: Synchronize
	 * TODO: Synchronize
	 *
	 */

	private HashMap toadletList;
	private HashMap pluginInfo;
	private PluginRespirator pluginRespirator = null;
	private final Node node;
	private final NodeClientCore core;
	SubConfig pmconfig;
	private boolean logMINOR;

	public PluginManager(Node node) {
		pluginInfo = new HashMap();
		toadletList = new HashMap();
		this.node = node;
		this.core = node.clientCore;
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		pluginRespirator = new PluginRespirator(node, this);

		pmconfig = new SubConfig("pluginmanager", node.config);
		// Start plugins in the config
		pmconfig.register("loadplugin", null, 9, true, false, "PluginManager.loadedOnStartup", "PluginManager.loadedOnStartupLong",
				new StringArrCallback() {
					public String[] get() {
						return getConfigLoadString();
					}
					public void set(String[] val) throws InvalidConfigValueException {
						//if(storeDir.equals(new File(val))) return;
						// FIXME
						throw new InvalidConfigValueException(L10n.getString("PluginManager.cannotSetOnceLoaded"));
					}
				});

		String fns[] = pmconfig.getStringArr("loadplugin");
		if (fns != null) {
			for (int i = 0 ; i < fns.length ; i++) {
				//System.err.println("Load: " + StringArrOption.decode(fns[i]));
				startPlugin(fns[i], false);
			}
		}

		pmconfig.finishedInitialization();
		/*System.err.println("=================================");
		  pmconfig.finishedInitialization();
		  fns = pmconfig.getStringArr("loadplugin");
		  for (int i = 0 ; i < fns.length ; i++)
		  System.err.println("Load: " + StringArrOption.decode(fns[i]));
		  System.err.println("=================================");
		  */
	}

	private String[] getConfigLoadString() {
		try{
			Iterator it = getPlugins().iterator();

			Vector v = new Vector();

			while(it.hasNext())
				v.add(((PluginInfoWrapper)it.next()).getFilename());

			return (String[]) v.toArray(new String[v.size()]);
		}catch (NullPointerException e){
			Logger.error(this, "error while loading plugins: disabling them:"+e);
			return new String[0];
		}
	}

	public void startPlugin(String filename, boolean store) {
		if (filename.trim().length() == 0)
			return;
		Logger.normal(this, "Loading plugin: " + filename);
		FredPlugin plug;
		try {
			plug = LoadPlugin(filename);
			PluginInfoWrapper pi = PluginHandler.startPlugin(this, filename, plug, pluginRespirator);
			// handles FProxy? If so, register

			if (pi.isPproxyPlugin())
				registerToadlet(plug);

			if(pi.isIPDetectorPlugin()) {
				node.ipDetector.registerIPDetectorPlugin((FredPluginIPDetector) plug);
			}

			synchronized (pluginInfo) {
				pluginInfo.put(pi.getThreadName(), pi);
			}
			Logger.normal(this, "Plugin loaded: " + filename);
		} catch (PluginNotFoundException e) {
			Logger.normal(this, "Loading plugin failed (" + filename + ')', e);
		} catch (UnsupportedClassVersionError e) {
			Logger.error(this, "Could not load plugin "+filename+" : "+e, e);
			System.err.println("Could not load plugin "+filename+" : "+e);
			e.printStackTrace();
			String jvmVersion = System.getProperty("java.vm.version");
			if(jvmVersion.startsWith("1.4.") || jvmVersion.equals("1.4")) {
				System.err.println("Plugin "+filename+" appears to require a later JVM");
				Logger.error(this, "Plugin "+filename+" appears to require a later JVM");
				core.alerts.register(new SimpleUserAlert(true, 
							l10n("pluginReqNewerJVMTitle", "name", filename),
							l10n("pluginReqNewerJVM", "name", filename),
							UserAlert.ERROR));
			}
		}
		if(store) core.storeConfig();
	}

	private String l10n(String key, String pattern, String value) {
		return L10n.getString("PluginManager."+key, pattern, value);
	}

	private void registerToadlet(FredPlugin pl){
		//toadletList.put(e.getStackTrace()[1].getClass().toString(), pl);
		synchronized (toadletList) {
			toadletList.put(pl.getClass().getName(), pl);
		}
		Logger.normal(this, "Added HTTP handler for /plugins/"+pl.getClass().getName()+ '/');
	}

	public void removePlugin(Thread t) {
		Object removeKey = null;
		synchronized (pluginInfo) {
			Iterator it = pluginInfo.keySet().iterator();
			while (it.hasNext() && (removeKey == null)) {
				Object key = it.next();
				PluginInfoWrapper pi = (PluginInfoWrapper) pluginInfo.get(key);
				if (pi.sameThread(t)) {
					removeKey = key;
					synchronized (toadletList) {
						try {
							toadletList.remove(pi.getPluginClassName());
							Logger.normal(this, "Removed HTTP handler for /plugins/"+
									pi.getPluginClassName()+ '/', new Exception("debug"));
						} catch (Throwable ex) {
							Logger.error(this, "removing Plugin", ex);
						}
					}
				}
			}

			if (removeKey != null)
				pluginInfo.remove(removeKey);
		}
		if(removeKey != null)
			core.storeConfig();
	}

	public void addToadletSymlinks(PluginInfoWrapper pi) {
		synchronized (toadletList) {
			try {
				String targets[] = pi.getPluginToadletSymlinks();
				if (targets == null)
					return;

				for (int i = 0 ; i < targets.length ; i++) {
					toadletList.remove(targets[i]);
					Logger.normal(this, "Removed HTTP symlink: " + targets[i] +
							" => /plugins/"+pi.getPluginClassName()+ '/');
				}
			} catch (Throwable ex) {
				Logger.error(this, "removing Toadlet-link", ex);
			}
		}
	}

	public void removeToadletSymlinks(PluginInfoWrapper pi) {
		synchronized (toadletList) {
			String rm = null;
			try {
				String targets[] = pi.getPluginToadletSymlinks();
				if (targets == null)
					return;

				for (int i = 0 ; i < targets.length ; i++) {
					rm = targets[i];
					toadletList.remove(targets[i]);
					pi.removePluginToadletSymlink(targets[i]);
					Logger.normal(this, "Removed HTTP symlink: " + targets[i] +
							" => /plugins/"+pi.getPluginClassName()+ '/');
				}
			} catch (Throwable ex) {
				Logger.error(this, "removing Toadlet-link: " + rm, ex);
			}
		}
	}

	public String dumpPlugins() {
		StringBuffer out= new StringBuffer();
		synchronized (pluginInfo) {
			Iterator it = pluginInfo.keySet().iterator();
			while (it.hasNext()) {
				PluginInfoWrapper pi = (PluginInfoWrapper) pluginInfo.get(it.next());
				out.append(pi.toString());
				out.append('\n');
			}
		}
		return out.toString();
	}

	public Set getPlugins() {
		HashSet out = new HashSet();
		synchronized (pluginInfo) {
			Iterator it = pluginInfo.keySet().iterator();
			while (it.hasNext()) {
				PluginInfoWrapper pi = (PluginInfoWrapper) pluginInfo.get(it.next());
				out.add(pi);
			}
		}
		return out;
	}

	public String handleHTTPGet(String plugin, HTTPRequest request) throws PluginHTTPException {
		FredPlugin handler = null;
		synchronized (toadletList) {
			handler = (FredPlugin)toadletList.get(plugin);
		}
		/*if (handler == null)
		  return null;
		  */

		if (handler instanceof FredPluginHTTP)
			return ((FredPluginHTTP)handler).handleHTTPGet(request);

		throw new NotFoundPluginHTTPException("Plugin not found!", "/plugins");
	}

	public String handleHTTPPost(String plugin, HTTPRequest request) throws PluginHTTPException {
		FredPlugin handler = null;
		synchronized (toadletList) {
			handler = (FredPlugin)toadletList.get(plugin);
		}
		/*if (handler == null)
		  return null;
		  */

		if (handler instanceof FredPluginHTTP)
			return ((FredPluginHTTP)handler).handleHTTPPost(request);

		throw new NotFoundPluginHTTPException("Plugin not found!", "/plugins");
	}

	public void killPlugin(String name) {
		PluginInfoWrapper pi = null;
		boolean found = false;
		synchronized (pluginInfo) {
			Iterator it = pluginInfo.keySet().iterator();
			while (it.hasNext() && !found) {
				pi = (PluginInfoWrapper) pluginInfo.get(it.next());
				if (pi.getThreadName().equals(name))
					found = true;
			}
		}
		if (found)
			if (pi.isThreadlessPlugin())
				removePlugin(pi.getThread());
			else
				pi.stopPlugin();
	}


	/**
	 * Method to load a plugin from the given path and return is as an object.
	 * Will accept filename to be of one of the following forms:
	 * "classname" to load a class from the current classpath
	 * "classame@file:/path/to/jarfile.jar" to load class from an other jarfile.
	 *
	 * @param filename 	The filename to load from
	 * @return			An instanciated object of the plugin
	 * @throws PluginNotFoundException	If anything goes wrong.
	 */
	private FredPlugin LoadPlugin(String filename) throws PluginNotFoundException {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		Class cls = null;
		if (filename.endsWith("*")) {
			filename = "*@http://downloads.freenetproject.org/alpha/plugins/" +
				filename.substring(filename.lastIndexOf(".")+1, filename.length()-1) +
				".jar.url";
			//System.out.println(filename);
			if(logMINOR) Logger.minor(this, "Rewritten to "+filename);
		} if (filename.endsWith("#")) {
			if(filename.indexOf('@') > -1) {
				Logger.error(this, "We don't allow downloads from anywhere else but our server");
				return null;
			}
			String pluginname = filename.substring(0, filename.length()-1);
			filename = null;

			URL url;
			InputStream is = null;

			try {
				url = new URL("http://downloads.freenetproject.org/alpha/plugins/" + pluginname + ".jar.url");
				if(logMINOR) Logger.minor(this, "Downloading "+url);
				is = url.openStream();
				
				File pluginsDirectory = new File("plugins");
				if(!pluginsDirectory.exists()) {
					Logger.normal(this, "The plugin directory hasn't been found, let's create it");
					if(!pluginsDirectory.mkdir())
						return null;
				}

				File finalFile = new File("plugins/" + pluginname + ".jar");
				if(!FileUtil.writeTo(is, finalFile)) {
					Logger.error(this, "Failed to rename the temporary file into "+finalFile);
					throw new PluginNotFoundException("Cannot write plugin to "+finalFile+" - check for permissions problem and disk full!");
				}
					
				filename = "*@file://" + FileUtil.getCanonicalFile(finalFile);
				if(logMINOR) Logger.minor(this, "Rewritten to "+filename);
				
			} catch (MalformedURLException mue) {
				Logger.error(this, "MAlformedURLException has occured : "+ mue, mue);
				return null;
			} catch (FileNotFoundException e) {
				Logger.error(this, "FileNotFoundException has occured : "+ e, e);
				return null;
			} catch (IOException ioe) {
				System.out.println("Caught :"+ioe.getMessage());
				ioe.printStackTrace();
				return null;
			} finally {
				try {
					if(is != null) is.close();
				} catch (IOException ioe) {}
			}
			if(filename == null)
				return null;
		}

		BufferedReader in = null;
		InputStream is = null;
		if ((filename.indexOf("@") >= 0)) {
			boolean assumeURLRedirect = true;
			// Open from external file
			for (int tries = 0 ; (tries <= 5) && (cls == null) ; tries++)
				try {
					String realURL = null;
					String realClass = null;

					// Load the jar-file
					String[] parts = filename.split("@");
					if (parts.length != 2) {
						throw new PluginNotFoundException("Could not split at \"@\".");
					}
					realClass = parts[0];
					realURL = parts[1];
					if(logMINOR) Logger.minor(this, "Class: "+realClass+" URL: "+realURL);

					if (filename.endsWith(".url")) {
						if(!assumeURLRedirect) {
							// Load the txt-file
							URL url = new URL(parts[1]);
							URLConnection uc = url.openConnection();
							in = new BufferedReader(
									new InputStreamReader(uc.getInputStream()));

							realURL = in.readLine();
							if(realURL == null)
								throw new PluginNotFoundException("Initialization error: " + url +
										" isn't a plugin loading url!");
							realURL = realURL.trim();
							if(logMINOR) Logger.minor(this, "Loaded new URL: "+realURL+" from .url file");
							in.close();
						}
						assumeURLRedirect = !assumeURLRedirect;
					}

					// Load the class inside file
					URL[] serverURLs = new URL[]{new URL(realURL)};
					ClassLoader cl = new URLClassLoader(serverURLs);


					// Handle automatic fetching of pluginclassname
					if (realClass.equals("*")) {

						// Clean URL
						URI liburi = URIPreEncoder.encodeURI(realURL);
						if(logMINOR)
							Logger.minor(this, "cleaned url: "+realURL+" -> "+liburi.toString());
						realURL = liburi.toString();

						URL url = new URL("jar:"+realURL+"!/");
						JarURLConnection jarConnection = (JarURLConnection)url.openConnection();
						// Java seems to cache even file: urls...
						jarConnection.setUseCaches(false);
						JarFile jf = jarConnection.getJarFile();
						//URLJarFile jf = new URLJarFile(new File(liburi));
						//is = jf.getInputStream(jf.getJarEntry("META-INF/MANIFEST.MF"));

						//BufferedReader manifest = new BufferedReader(new InputStreamReader(cl.getResourceAsStream("/META-INF/MANIFEST.MF")));

						//URL url = new URL(parts[1]);
						//URLConnection uc = cl.getResource("/META-INF/MANIFEST.MF").openConnection();

						is = jf.getInputStream(jf.getJarEntry("META-INF/MANIFEST.MF"));
						in = new BufferedReader(new InputStreamReader(is));	
						String line;
						while ((line = in.readLine())!=null) {
							//	System.err.println(line + "\t\t\t" + realClass);
							if (line.startsWith("Plugin-Main-Class: ")) {
								realClass = line.substring("Plugin-Main-Class: ".length()).trim();
								if(logMINOR) Logger.minor(this, "Found plugin main class "+realClass+" from manifest");
							}
						}
						//System.err.println("Real classname: " + realClass);
					}

					cls = cl.loadClass(realClass);

				} catch (Exception e) {
					if (tries >= 5)
						throw new PluginNotFoundException("Initialization error:"
								+ filename, e);

					try {
						Thread.sleep(100);
					} catch (Exception ee) {}
				} finally {
					try {
						if(is != null)
							is.close();
						if(in != null)
							in.close();
					} catch (IOException ioe){}
				}
		} else {
			// Load class
			try {
				cls = Class.forName(filename);
			} catch (ClassNotFoundException e) {
				throw new PluginNotFoundException(filename);
			}
		}

		if(cls == null)
			throw new PluginNotFoundException("Unknown error");

		// Class loaded... Objectize it!
		Object o = null;
		try {
			o = cls.newInstance();
		} catch (Exception e) {
			throw new PluginNotFoundException("Could not re-create plugin:" +
					filename, e);
		}

		// See if we have the right type
		if (!(o instanceof FredPlugin)) {
			throw new PluginNotFoundException("Not a plugin: " + filename);
		}

		return (FredPlugin)o;
	}
	
	Ticker getTicker() {
		return node.getTicker();
	}
}
