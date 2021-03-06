/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.config;

import java.util.LinkedHashMap;

/** Global configuration object for a node. SubConfig's register here.
 * Handles writing to a file etc.
 */
public class Config {

    public static final int CONFIG_REQUEST_TYPE_CURRENT_SETTINGS = 1;
    public static final int CONFIG_REQUEST_TYPE_DEFAULT_SETTINGS = 2;
    public static final int CONFIG_REQUEST_TYPE_SORT_ORDER = 3;
    public static final int CONFIG_REQUEST_TYPE_EXPERT_FLAG = 4;
    public static final int CONFIG_REQUEST_TYPE_FORCE_WRITE_FLAG = 5;
    public static final int CONFIG_REQUEST_TYPE_SHORT_DESCRIPTION = 6;
    public static final int CONFIG_REQUEST_TYPE_LONG_DESCRIPTION = 7;

	protected final LinkedHashMap configsByPrefix;
	
	public Config() {
		configsByPrefix = new LinkedHashMap();
	}
	
	public void register(SubConfig sc) {
		synchronized(this) {
			if(configsByPrefix.containsKey(sc.prefix))
				throw new IllegalArgumentException("Already registered "+sc.prefix+": "+sc);
			configsByPrefix.put(sc.prefix, sc);
		}
	}
	
	/** Write current config to disk 
	 * @throws IOException */
	public void store() {
		// Do nothing
	}

	/** Finished initialization */
	public void finishedInit() {
		// Do nothing
	}

	public void onRegister(SubConfig config, Option o) {
		// Do nothing
	}

	/** Fetch all the SubConfig's. Used by user-facing config thingies. */
	public synchronized SubConfig[] getConfigs() {
		return (SubConfig[]) configsByPrefix.values().toArray(new SubConfig[configsByPrefix.size()]);
	}
	
	public synchronized SubConfig get(String subConfig){
		return (SubConfig)configsByPrefix.get(subConfig);
	}
}
