/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.config;

/**
 * A config option.
 */
public abstract class Option {

	/** The parent SubConfig object */
	final SubConfig config;
	/** The option name */
	final String name;
	/** The sort order */
	final int sortOrder;
	/** Is this config variable expert-only? */
	final boolean expert;
	/** Is this config variable to be written out even if it uses the default value? */
	final boolean forceWrite;
	/** Short description of value e.g. "FCP port" */
	final String shortDesc;
	/** Long description of value e.g. "The TCP port to listen for FCP connections on" */
	final String longDesc;
	/** The configCallback associated to the Option */
	final ConfigCallback cb;
	
	Option(SubConfig config, String name, ConfigCallback cb, int sortOrder, boolean expert, boolean forceWrite, String shortDesc, String longDesc) {
		this.config = config;
		this.name = name;
		this.cb = cb;
		this.sortOrder = sortOrder;
		this.expert = expert;
		this.shortDesc = shortDesc;
		this.longDesc = longDesc;
		this.forceWrite = forceWrite;
	}

	/**
	 * Set this option's current value to a string. Will call the callback. Does not care 
	 * whether the value of the option has changed.
	 */
	public abstract void setValue(String val) throws InvalidConfigValueException;

	/**
	 * Get the current value of the option as a string.
	 */
	public abstract String getValueString();

	/** Set to a value from the config file; this is not passed on to the callback, as we
	 * expect the client-side initialization to check the value. The callback is not valid
	 * until the client calls finishedInitialization().
	 * @throws InvalidConfigValueException 
	 */
	public abstract void setInitialValue(String val) throws InvalidConfigValueException;

	/**
	 * Call the callback with the current value of the option.
	 */
	public void forceUpdate() throws InvalidConfigValueException {
		setValue(getValueString());
	}
	
	public String getName(){
		return name;
	}
	
	public String getShortDesc(){
		return shortDesc;
	}
	
	public String getLongDesc(){
		return longDesc;
	}
	
	public boolean isExpert(){
		return expert;
	}
	
	public boolean isForcedWrite(){
		return forceWrite;
	}
	
	public int getSortOrder(){
		return sortOrder;
	}

	/**
	 * Is this option set to the default?
	 */
	public abstract boolean isDefault();
	
	/** Set to the default. Don't use after completed initialization, as this does not call the callback. FIXME fix this? */
	public abstract void setDefault();
	
	public abstract String getDefault();

	public ConfigCallback getCallback() {
		return cb;
	}
}
