/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.io.File;
import java.io.IOException;

import freenet.config.EnumerableOptionCallback;
import freenet.config.InvalidConfigValueException;
import freenet.config.OptionFormatException;
import freenet.config.SubConfig;
import freenet.support.Executor;
import freenet.support.FileLoggerHook;
import freenet.support.Logger;
import freenet.support.LoggerHook;
import freenet.support.LoggerHookChain;
import freenet.support.FileLoggerHook.IntervalParseException;
import freenet.support.LoggerHook.InvalidThresholdException;
import freenet.support.api.BooleanCallback;
import freenet.support.api.IntCallback;
import freenet.support.api.LongCallback;
import freenet.support.api.StringCallback;

public class LoggingConfigHandler {
	private class PriorityCallback implements StringCallback, EnumerableOptionCallback {
		private final String[] possibleValues = new String[]{ "ERROR", "NORMAL", "MINOR", "DEBUG" };

		public String get() {
			LoggerHookChain chain = Logger.getChain();
			return LoggerHook.priorityOf(chain.getThreshold());
		}
		public void set(String val) throws InvalidConfigValueException {
			LoggerHookChain chain = Logger.getChain();
			try {
				chain.setThreshold(val);
			} catch (LoggerHook.InvalidThresholdException e) {
				throw new OptionFormatException(e.getMessage());
			}
		}

		public String[] getPossibleValues() {
			return possibleValues;
		}

		public void setPossibleValues(String[] val) {
			throw new NullPointerException("Should not happen!");
		}
	}

	protected static final String LOG_PREFIX = "freenet";
	private final SubConfig config;
	private FileLoggerHook fileLoggerHook;
	private File logDir;
	private long maxZippedLogsSize;
	private String logRotateInterval;
	private long maxCachedLogBytes;
	private int maxCachedLogLines;
	private final Executor executor;
	
	public LoggingConfigHandler(SubConfig loggingConfig, Executor executor) throws InvalidConfigValueException {
		this.config = loggingConfig;
		this.executor = executor;
    	
    	loggingConfig.register("enabled", true, 1, true, false, "LogConfigHandler.enabled", "LogConfigHandler.enabledLong",
    			new BooleanCallback() {
					public boolean get() {
						return fileLoggerHook != null;
					}
					public void set(boolean val) throws InvalidConfigValueException {
						if(val == (fileLoggerHook != null)) return;
						if(!val) {
							disableLogger();
						} else 
							enableLogger();
					}
    	});
    	
    	boolean loggingEnabled = loggingConfig.getBoolean("enabled");
    	
    	loggingConfig.register("dirname", "logs", 2, true, false, "LogConfigHandler.dirName", "LogConfigHandler.dirNameLong", 
    			new StringCallback() {

					public String get() {
						return logDir.getPath();
					}

					public void set(String val) throws InvalidConfigValueException {
						File f = new File(val);
						if(f.equals(logDir)) return;
						preSetLogDir(f);
						// Still here
						if(fileLoggerHook == null) {
							logDir = f;
						} else {
							// Discard old data
							fileLoggerHook.switchBaseFilename(f.getPath()+File.separator+LOG_PREFIX);
							logDir = f;
							new Deleter(logDir).start();
						}
					}
    	});
    	
    	logDir = new File(config.getString("dirname"));
    	if(loggingEnabled)
			preSetLogDir(logDir);
    	// => enableLogger must run preSetLogDir
    	
    	// max space used by zipped logs
    	
    	config.register("maxZippedLogsSize", "128M", 3, true, true, "LogConfigHandler.maxZippedLogsSize", "LogConfigHandler.maxZippedLogsSizeLong",
    			new LongCallback() {
					public long get() {
						return maxZippedLogsSize;
					}
					public void set(long val) throws InvalidConfigValueException {
						if(val < 0) val = 0;
						maxZippedLogsSize = val;
						if(fileLoggerHook != null) {
							fileLoggerHook.setMaxOldLogsSize(val);
						}
					}
    	});
    	
    	maxZippedLogsSize = config.getLong("maxZippedLogsSize");
    	
    	// These two are forced below so we don't need to check them now
    	
    	// priority
    	
    	// Node must override this to minor on testnet.
    	config.register("priority", "normal", 4, false, false, "LogConfigHandler.minLoggingPriority", "LogConfigHandler.minLoggingPriorityLong",
    			new PriorityCallback());
    	
    	// detailed priority
    	
    	config.register("priorityDetail", "", 5, true, false, "LogConfigHandler.detaildPriorityThreshold", "LogConfigHandler.detaildPriorityThresholdLong",
    			new StringCallback() {

					public String get() {
						LoggerHookChain chain = Logger.getChain();
						return chain.getDetailedThresholds();
					}

					public void set(String val) throws InvalidConfigValueException {
						LoggerHookChain chain = Logger.getChain();
						try {
							chain.setDetailedThresholds(val);
						} catch (InvalidThresholdException e) {
							throw new InvalidConfigValueException(e.getMessage());
						}
					}
    		
    	});
    	
    	// interval
    	
    	config.register("interval", "1HOUR", 5, true, false, "LogConfigHandler.rotationInterval", "LogConfigHandler.rotationIntervalLong",
    			new StringCallback() {
					public String get() {
						return logRotateInterval;
					}

					public void set(String val) throws InvalidConfigValueException {
						if(val.equals(logRotateInterval)) return;
						if(fileLoggerHook != null) {
							try {
								fileLoggerHook.setInterval(val);
							} catch (FileLoggerHook.IntervalParseException e) {
								throw new OptionFormatException(e.getMessage());
							}
						}
						logRotateInterval = val;
					}
    	});
    	
    	logRotateInterval = config.getString("interval");
    	
    	// max cached bytes in RAM
    	config.register("maxCachedBytes", "10M", 6, true, false, "LogConfigHandler.maxCachedBytes", "LogConfigHandler.maxCachedBytesLong", 
    			new LongCallback() {
					public long get() {
						return maxCachedLogBytes;
					}
					public void set(long val) throws InvalidConfigValueException {
						if(val < 0) val = 0;
						if(val == maxCachedLogBytes) return;
						maxCachedLogBytes = val;
						if(fileLoggerHook != null)
							fileLoggerHook.setMaxListBytes(val);
					}
    	});
    	
    	maxCachedLogBytes = config.getLong("maxCachedBytes");
    	
    	// max cached lines in RAM
    	config.register("maxCachedLines", "100k", 7, true, false, "LogConfigHandler.maxCachedLines", "LogConfigHandler.maxCachedLinesLong",
    			new IntCallback() {
					public int get() {
						return maxCachedLogLines;
					}
					public void set(int val) throws InvalidConfigValueException {
						if(val < 0) val = 0;
						if(val == maxCachedLogLines) return;
						maxCachedLogLines = val;
						if(fileLoggerHook != null)
							fileLoggerHook.setMaxListLength(val);
					}
    	});
    	
    	maxCachedLogLines = config.getInt("maxCachedLines");
    	
    	if(loggingEnabled)
    		enableLogger();
    	
    	config.finishedInitialization();
	}

	private final Object enableLoggerLock = new Object();
	
	/**
	 * Turn on the logger.
	 */
	private void enableLogger() {
		try {
			preSetLogDir(logDir);
		} catch (InvalidConfigValueException e3) {
			System.err.println("Cannot set log dir: "+logDir+": "+e3);
			e3.printStackTrace();
		}
		synchronized(enableLoggerLock) {
			if(fileLoggerHook != null) return;
			Logger.setupChain();
			try {
				config.forceUpdate("priority");
				config.forceUpdate("priorityDetail");
			} catch (InvalidConfigValueException e2) {
				System.err.println("Invalid config value for logger.priority in config file: "+config.getString("priority"));
				// Leave it at the default.
			}
			FileLoggerHook hook;
			try {
				hook = 
					new FileLoggerHook(true, new File(logDir, LOG_PREFIX).getAbsolutePath(), 
				    		"d (c, t, p): m", "MMM dd, yyyy HH:mm:ss:SSS", Logger.DEBUG /* filtered by chain */, false, true, 
				    		maxZippedLogsSize /* 1GB of old compressed logfiles */);
			} catch (IOException e) {
				System.err.println("CANNOT START LOGGER: "+e.getMessage());
				return;
			}
			try {
				hook.setInterval(logRotateInterval);
			} catch (IntervalParseException e) {
				System.err.println("INVALID LOGGING INTERVAL: "+e.getMessage());
				try {
					hook.setInterval("5MINUTE");
				} catch (IntervalParseException e1) {
					System.err.println("Impossible: "+e1.getMessage());
				}
			}
			hook.setMaxListBytes(maxCachedLogBytes);
			hook.setMaxListLength(maxCachedLogLines);
			fileLoggerHook = hook;
			Logger.globalAddHook(hook);
			hook.start();
		}
	}

	protected void disableLogger() {
		synchronized(enableLoggerLock) {
			if(fileLoggerHook == null) return;
			FileLoggerHook hook = fileLoggerHook;
			Logger.globalRemoveHook(hook);
			hook.close();
			fileLoggerHook = null;
			Logger.destroyChainIfEmpty();
		}
	}
	
	protected void preSetLogDir(File f) throws InvalidConfigValueException {
		boolean exists = f.exists();
		if(exists && !f.isDirectory())
			throw new InvalidConfigValueException("Cannot overwrite a file with a log directory");
		if(!exists) {
			f.mkdir();
			exists = f.exists();
			if(!exists || !f.isDirectory())
				throw new InvalidConfigValueException("Cannot create log directory");
		}
	}
	
	class Deleter implements Runnable {
		
		File logDir;
		
		public Deleter(File logDir) {
			this.logDir = logDir;
		}

		void start() {
			executor.execute(this, "Old log directory "+logDir+" deleter");
		}
		
		public void run() {
			fileLoggerHook.waitForSwitch();
			delete(logDir);
		}

		/** @return true if we can't delete due to presence of non-Freenet files */
		private boolean delete(File dir) {
			boolean failed = false;
			File[] files = dir.listFiles();
			for(int i=0;i<files.length;i++) {
				File f = files[i];
				String s = f.getName();
				if(s.startsWith("freenet-") && (s.indexOf(".log") != -1)) {
					if(f.isFile()) {
						if(!f.delete()) failed = true;
					} else if(f.isDirectory()) {
						if(delete(f)) failed = true;
					}
				} else {
					failed = true;
				}
			}
			if(!failed) {
				failed = !(dir.delete());
			}
			return failed;
		}
		
	}

	public FileLoggerHook getFileLoggerHook() {
		return fileLoggerHook;
	}

	public void forceEnableLogging() {
		enableLogger();
	}

	public long getMaxZippedLogFiles() {
		return maxZippedLogsSize;
	}

	public void setMaxZippedLogFiles(String maxSizeAsString) throws InvalidConfigValueException {
		config.set("maxZippedLogsSize", maxSizeAsString);
	}
	
}
