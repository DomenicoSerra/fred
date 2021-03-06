package freenet.client.async;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Vector;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipOutputStream;

import freenet.client.ClientMetadata;
import freenet.client.DefaultMIMETypes;
import freenet.client.InsertBlock;
import freenet.client.InsertContext;
import freenet.client.InsertException;
import freenet.client.Metadata;
import freenet.client.MetadataUnresolvedException;
import freenet.client.events.SplitfileProgressEvent;
import freenet.keys.BaseClientKey;
import freenet.keys.FreenetURI;
import freenet.support.Logger;
import freenet.support.api.Bucket;
import freenet.support.io.BucketTools;

public class SimpleManifestPutter extends BaseClientPutter implements PutCompletionCallback {
	// Only implements PutCompletionCallback for the final metadata insert

	private class PutHandler extends BaseClientPutter implements PutCompletionCallback {
		
		protected PutHandler(final SimpleManifestPutter smp, String name, Bucket data, ClientMetadata cm, boolean getCHKOnly) throws InsertException {
			super(smp.priorityClass, smp.chkScheduler, smp.sskScheduler, smp.client);
			this.cm = cm;
			this.data = data;
			InsertBlock block = 
				new InsertBlock(data, cm, FreenetURI.EMPTY_CHK_URI);
			this.origSFI =
				new SingleFileInserter(this, this, block, false, ctx, false, getCHKOnly, true, null, false, false, null, earlyEncode);
			metadata = null;
		}

		protected PutHandler(final SimpleManifestPutter smp, String name, FreenetURI target, ClientMetadata cm) {
			super(smp.getPriorityClass(), smp.chkScheduler, smp.sskScheduler, smp.client);
			this.cm = cm;
			this.data = null;
			Metadata m = new Metadata(Metadata.SIMPLE_REDIRECT, target, cm);
			metadata = m;
			origSFI = null;
		}
		
		protected PutHandler(final SimpleManifestPutter smp, String name, String targetInZip, ClientMetadata cm, Bucket data) {
			super(smp.getPriorityClass(), smp.chkScheduler, smp.sskScheduler, smp.client);
			this.cm = cm;
			this.data = data;
			this.targetInZip = targetInZip;
			Metadata m = new Metadata(Metadata.ZIP_INTERNAL_REDIRECT, targetInZip, cm);
			metadata = m;
			origSFI = null;
		}
		
		private SingleFileInserter origSFI;
		private ClientMetadata cm;
		private Metadata metadata;
		private boolean finished;
		private String targetInZip;
		private final Bucket data;
		
		public void start() throws InsertException {
			if((origSFI == null) && (metadata != null)) return;
			origSFI.start(null);
			origSFI = null;
		}
		
		public FreenetURI getURI() {
			return null;
		}

		public boolean isFinished() {
			return finished || cancelled || SimpleManifestPutter.this.cancelled;
		}

		public void onSuccess(ClientPutState state) {
			logMINOR = Logger.shouldLog(Logger.MINOR, this);
			if(logMINOR) Logger.minor(this, "Completed "+this);
			SimpleManifestPutter.this.onFetchable(this);
			synchronized(SimpleManifestPutter.this) {
				runningPutHandlers.remove(this);
				if(!runningPutHandlers.isEmpty()) {
					return;
				}
			}
			insertedAllFiles();
		}

		public void onFailure(InsertException e, ClientPutState state) {
			logMINOR = Logger.shouldLog(Logger.MINOR, this);
			if(logMINOR) Logger.minor(this, "Failed: "+this+" - "+e, e);
			fail(e);
		}

		public void onEncode(BaseClientKey key, ClientPutState state) {
			if(logMINOR) Logger.minor(this, "onEncode("+key+") for "+this);
			if(metadata == null) {
				// The file was too small to have its own metadata, we get this instead.
				// So we make the key into metadata.
				Metadata m =
					new Metadata(Metadata.SIMPLE_REDIRECT, key.getURI(), cm);
				onMetadata(m, null);
			}
		}

		public void onTransition(ClientPutState oldState, ClientPutState newState) {}

		public void onMetadata(Metadata m, ClientPutState state) {
			logMINOR = Logger.shouldLog(Logger.MINOR, this);
			if(logMINOR) Logger.minor(this, "Assigning metadata: "+m+" for "+this+" from "+state,
					new Exception("debug"));
			if(metadata != null) {
				Logger.error(this, "Reassigning metadata", new Exception("debug"));
				return;
			}
			metadata = m;
			synchronized(SimpleManifestPutter.this) {
				putHandlersWaitingForMetadata.remove(this);
				if(!putHandlersWaitingForMetadata.isEmpty()) return;
			}
			gotAllMetadata();
		}

		public void addBlock() {
			SimpleManifestPutter.this.addBlock();
		}
		
		public void addBlocks(int num) {
			SimpleManifestPutter.this.addBlocks(num);
		}
		
		public void completedBlock(boolean dontNotify) {
			SimpleManifestPutter.this.completedBlock(dontNotify);
		}
		
		public void failedBlock() {
			SimpleManifestPutter.this.failedBlock();
		}
		
		public void fatallyFailedBlock() {
			SimpleManifestPutter.this.fatallyFailedBlock();
		}
		
		public void addMustSucceedBlocks(int blocks) {
			SimpleManifestPutter.this.addMustSucceedBlocks(blocks);
		}
		
		public void notifyClients() {
			// FIXME generate per-filename events???
		}

		public void onBlockSetFinished(ClientPutState state) {
			synchronized(SimpleManifestPutter.this) {
				waitingForBlockSets.remove(this);
				if(!waitingForBlockSets.isEmpty()) return;
			}
			SimpleManifestPutter.this.blockSetFinalized();
		}

		public void onMajorProgress() {
			SimpleManifestPutter.this.onMajorProgress();
		}

		public void onFetchable(ClientPutState state) {
			SimpleManifestPutter.this.onFetchable(this);
		}

		public void onTransition(ClientGetState oldState, ClientGetState newState) {
			// Ignore
		}

	}

	static boolean logMINOR;
	private final HashMap putHandlersByName;
	private final HashSet runningPutHandlers;
	private final HashSet putHandlersWaitingForMetadata;
	private final HashSet waitingForBlockSets;
	private final HashSet putHandlersWaitingForFetchable;
	private FreenetURI finalURI;
	private FreenetURI targetURI;
	private boolean finished;
	private final InsertContext ctx;
	private final ClientCallback cb;
	private final boolean getCHKOnly;
	private boolean insertedAllFiles;
	private boolean insertedManifest;
	private final HashMap metadataPuttersByMetadata;
	private final HashMap metadataPuttersUnfetchable;
	private final String defaultName;
	private int numberOfFiles;
	private long totalSize;
	private boolean metadataBlockSetFinalized;
	private Metadata baseMetadata;
	private boolean hasResolvedBase;
	private final static String[] defaultDefaultNames =
		new String[] { "index.html", "index.htm", "default.html", "default.htm" };
	private int bytesOnZip;
	private LinkedList elementsToPutInZip;
	private boolean fetchable;
	private final boolean earlyEncode;
	
	public SimpleManifestPutter(ClientCallback cb, ClientRequestScheduler chkSched,
			ClientRequestScheduler sskSched, HashMap manifestElements, short prioClass, FreenetURI target, 
			String defaultName, InsertContext ctx, boolean getCHKOnly, Object clientContext, boolean earlyEncode) throws InsertException {
		super(prioClass, chkSched, sskSched, clientContext);
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		this.defaultName = defaultName;
		this.targetURI = target;
		this.cb = cb;
		this.ctx = ctx;
		this.getCHKOnly = getCHKOnly;
		this.earlyEncode = earlyEncode;
		putHandlersByName = new HashMap();
		runningPutHandlers = new HashSet();
		putHandlersWaitingForMetadata = new HashSet();
		putHandlersWaitingForFetchable = new HashSet();
		waitingForBlockSets = new HashSet();
		metadataPuttersByMetadata = new HashMap();
		metadataPuttersUnfetchable = new HashMap();
		elementsToPutInZip = new LinkedList();
		makePutHandlers(manifestElements, putHandlersByName);
		checkZips();
	}

	private void checkZips() {
		// If there are too few files in the zip, then insert them directly instead.
		// FIXME do something.
	}

	public void start() throws InsertException {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(logMINOR) Logger.minor(this, "Starting "+this);
		PutHandler[] running;

		boolean cancel = false;
		
		synchronized(this) {
			cancel = cancelled;
			if(!cancelled) {
				running = (PutHandler[]) runningPutHandlers.toArray(new PutHandler[runningPutHandlers.size()]);
				try {
					for(int i=0;i<running.length;i++) {
						running[i].start();
					}
					if(logMINOR) Logger.minor(this, "Started "+running.length+" PutHandler's for "+this);
					if(cancelled) cancel();
					if(running.length == 0) {
						insertedAllFiles = true;
						gotAllMetadata();
					}
				} catch (InsertException e) {
					cancelAndFinish();
					throw e;
				}
			}
		}
		if(cancel) cancel();
	}
	
	private void makePutHandlers(HashMap manifestElements, HashMap putHandlersByName) throws InsertException {
		makePutHandlers(manifestElements, putHandlersByName, "/");
	}
	
	private void makePutHandlers(HashMap manifestElements, HashMap putHandlersByName, String ZipPrefix) throws InsertException {
		Iterator it = manifestElements.keySet().iterator();
		while(it.hasNext()) {
			String name = (String) it.next();
			Object o = manifestElements.get(name);
			if(o instanceof HashMap) {
				HashMap subMap = new HashMap();
				putHandlersByName.put(name, subMap);
				makePutHandlers((HashMap)o, subMap, ZipPrefix+name+ '/');
			} else {
				ManifestElement element = (ManifestElement) o;
				String mimeType = element.mimeOverride;
				if(mimeType == null)
					mimeType = DefaultMIMETypes.guessMIMEType(name, true);
				ClientMetadata cm;
				if(mimeType == null || mimeType.equals(DefaultMIMETypes.DEFAULT_MIME_TYPE))
					cm = null;
				else
					cm = new ClientMetadata(mimeType);
				PutHandler ph;
				Bucket data = element.data;
				if(element.targetURI != null) {
					ph = new PutHandler(this, name, element.targetURI, cm);
					// Just a placeholder, don't actually run it
				} else {
					// Decide whether to put it in the ZIP.
					// FIXME support multiple ZIPs and size limits.
					// FIXME support better heuristics.
					int sz = (int)data.size() + 40 + element.fullName.length();
					if((data.size() <= 65536) && 
							(bytesOnZip + sz < ((2048-64)*1024))) { // totally dumb heuristic!
						bytesOnZip += sz;
						// Put it in the zip.
						ph = new PutHandler(this, name, ZipPrefix+element.fullName, cm, data);
						elementsToPutInZip.addLast(ph);
						numberOfFiles++;
						totalSize += data.size();
					} else {
						try {
							ph = new PutHandler(this,name, data, cm, getCHKOnly);
						} catch (InsertException e) {
							cancelAndFinish();
							throw e;
						}
						runningPutHandlers.add(ph);
						putHandlersWaitingForMetadata.add(ph);
						putHandlersWaitingForFetchable.add(ph);
						numberOfFiles++;
						totalSize += data.size();
					}
				}
				putHandlersByName.put(name, ph);
			}
		}
	}

	public FreenetURI getURI() {
		return finalURI;
	}

	public synchronized boolean isFinished() {
		return finished || cancelled;
	}

	private void gotAllMetadata() {
		if(logMINOR) Logger.minor(this, "Got all metadata");
		HashMap namesToByteArrays = new HashMap();
		namesToByteArrays(putHandlersByName, namesToByteArrays);
		if(defaultName != null) {
			Metadata meta = (Metadata) namesToByteArrays.get(defaultName);
			if(meta == null) {
				fail(new InsertException(InsertException.INVALID_URI, "Default name "+defaultName+" does not exist", null));
				return;
			}
			namesToByteArrays.put("", meta);
		} else {
			for(int j=0;j<defaultDefaultNames.length;j++) {
				String name = defaultDefaultNames[j];
				Metadata meta = (Metadata) namesToByteArrays.get(name);
				if(meta != null) {
					namesToByteArrays.put("", meta);
					break;
				}
			}
		}
		baseMetadata =
			Metadata.mkRedirectionManifestWithMetadata(namesToByteArrays);
		resolveAndStartBase();
		
	}
	
	private void resolveAndStartBase() {
		Bucket bucket = null;
		synchronized(this) {
			if(hasResolvedBase) return;
		}
		while(true) {
			try {
				bucket = BucketTools.makeImmutableBucket(ctx.bf, baseMetadata.writeToByteArray());
				break;
			} catch (IOException e) {
				fail(new InsertException(InsertException.BUCKET_ERROR, e, null));
				return;
			} catch (MetadataUnresolvedException e) {
				try {
					resolve(e);
				} catch (IOException e1) {
					fail(new InsertException(InsertException.BUCKET_ERROR, e, null));
					return;
				} catch (InsertException e2) {
					fail(e2);
					return;
				}
			}
		}
		if(bucket == null) return;
		synchronized(this) {
			if(hasResolvedBase) return;
			hasResolvedBase = true;
		}
		InsertBlock block;
		boolean isMetadata = true;
		boolean insertAsArchiveManifest = false;
		if(!(elementsToPutInZip.isEmpty())) {
			// There is a zip to insert.
			// We want to include the metadata.
			// We have the metadata, fortunately enough, because everything has been resolve()d.
			// So all we need to do is create the actual ZIP.
			try {
				
				// FIXME support formats other than .zip.
				// Only the *decoding* is generic at present.
				
				Bucket zipBucket = ctx.bf.makeBucket(-1);
				OutputStream os = new BufferedOutputStream(zipBucket.getOutputStream());
				ZipOutputStream zos = new ZipOutputStream(os);
				ZipEntry ze;
				
				for(Iterator i=elementsToPutInZip.iterator();i.hasNext();) {
					PutHandler ph = (PutHandler) i.next();
					ze = new ZipEntry(ph.targetInZip);
					ze.setTime(0);
					zos.putNextEntry(ze);
					BucketTools.copyTo(ph.data, zos, ph.data.size());
				}
				
				// Add .metadata - after the rest.
				ze = new ZipEntry(".metadata");
				ze.setTime(0); // -1 = now, 0 = 1970.
				zos.putNextEntry(ze);
				BucketTools.copyTo(bucket, zos, bucket.size());
				
				zos.closeEntry();
				// Both finish() and close() are necessary.
				zos.finish();
				zos.close();
				
				// Now we have to insert the ZIP.
				
				// Can we just insert it, and not bother with a redirect to it?
				// Thereby exploiting implicit manifest support, which will pick up on .metadata??
				// We ought to be able to !!
				block = new InsertBlock(zipBucket, new ClientMetadata("application/zip"), targetURI);
				isMetadata = false;
				insertAsArchiveManifest = true;
			} catch (ZipException e) {
				fail(new InsertException(InsertException.INTERNAL_ERROR, e, null));
				return;
			} catch (IOException e) {
				fail(new InsertException(InsertException.BUCKET_ERROR, e, null));
				return;
			}
		} else
			block = new InsertBlock(bucket, null, targetURI);
		try {
			SingleFileInserter metadataInserter = 
				new SingleFileInserter(this, this, block, isMetadata, ctx, false, getCHKOnly, false, baseMetadata, insertAsArchiveManifest, true, null, earlyEncode);
			if(logMINOR) Logger.minor(this, "Inserting main metadata: "+metadataInserter);
			this.metadataPuttersByMetadata.put(baseMetadata, metadataInserter);
			metadataPuttersUnfetchable.put(baseMetadata, metadataInserter);
			metadataInserter.start(null);
		} catch (InsertException e) {
			fail(e);
		}
	}

	private boolean resolve(MetadataUnresolvedException e) throws InsertException, IOException {
		Metadata[] metas = e.mustResolve;
		boolean mustWait = false;
		for(int i=0;i<metas.length;i++) {
			Metadata m = metas[i];
			if(!m.isResolved())
				mustWait = true;
			synchronized(this) {
				if(metadataPuttersByMetadata.containsKey(m)) continue;
			}
			try {
				Bucket b = m.toBucket(ctx.bf);
				
				InsertBlock ib = new InsertBlock(b, null, FreenetURI.EMPTY_CHK_URI);
				SingleFileInserter metadataInserter = 
					new SingleFileInserter(this, this, ib, true, ctx, false, getCHKOnly, false, m, false, true, null, earlyEncode);
				if(logMINOR) Logger.minor(this, "Inserting subsidiary metadata: "+metadataInserter+" for "+m);
				synchronized(this) {
					this.metadataPuttersByMetadata.put(m, metadataInserter);
				}
				metadataInserter.start(null);
			} catch (MetadataUnresolvedException e1) {
				resolve(e1);
			}
		}
		return mustWait;
	}

	private void namesToByteArrays(HashMap putHandlersByName, HashMap namesToByteArrays) {
		Iterator i = putHandlersByName.keySet().iterator();
		while(i.hasNext()) {
			String name = (String) i.next();
			Object o = putHandlersByName.get(name);
			if(o instanceof PutHandler) {
				PutHandler ph = (PutHandler) o;
				Metadata meta = ph.metadata;
				namesToByteArrays.put(name, meta);
			} else if(o instanceof HashMap) {
				HashMap subMap = new HashMap();
				namesToByteArrays.put(name, subMap);
				namesToByteArrays((HashMap)o, subMap);
			} else
				throw new IllegalStateException();
		}
	}

	private void insertedAllFiles() {
		if(logMINOR) Logger.minor(this, "Inserted all files");
		synchronized(this) {
			insertedAllFiles = true;
			if(finished || cancelled) {
				if(logMINOR) Logger.minor(this, "Already "+(finished?"finished":"cancelled"));
				return;
			}
			if(!insertedManifest) {
				if(logMINOR) Logger.minor(this, "Haven't inserted manifest");
				return;
			}
			finished = true;
		}
		complete();
	}
	
	private void complete() {
		cb.onSuccess(this);
	}

	private void fail(InsertException e) {
		// Cancel all, then call the callback
		cancelAndFinish();
		
		cb.onFailure(e, this);
	}

	private void cancelAndFinish() {
		PutHandler[] running;
		synchronized(this) {
			if(finished) return;
			running = (PutHandler[]) runningPutHandlers.toArray(new PutHandler[runningPutHandlers.size()]);
			finished = true;
		}
		
		for(int i=0;i<running.length;i++) {
			running[i].cancel();
		}
	}
	
	public void cancel() {
		fail(new InsertException(InsertException.CANCELLED));
	}
	
	public void onSuccess(ClientPutState state) {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		synchronized(this) {
			metadataPuttersByMetadata.remove(state.getToken());
			if(!metadataPuttersByMetadata.isEmpty()) {
				if(logMINOR) Logger.minor(this, "Still running metadata putters: "+metadataPuttersByMetadata.size());
				return;
			}
			Logger.minor(this, "Inserted manifest successfully on "+this);
			insertedManifest = true;
			if(finished) {
				if(logMINOR) Logger.minor(this, "Already finished");
				return;
			}
			if(!insertedAllFiles) {
				if(logMINOR) Logger.minor(this, "Not inserted all files");
				return;
			}
			finished = true;
		}
		complete();
	}
	
	public void onFailure(InsertException e, ClientPutState state) {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		fail(e);
	}
	
	public void onEncode(BaseClientKey key, ClientPutState state) {
		if(state.getToken() == baseMetadata) {
			this.finalURI = key.getURI();
			if(logMINOR) Logger.minor(this, "Got metadata key: "+finalURI);
			cb.onGeneratedURI(finalURI, this);
		} else {
			// It's a sub-Metadata
			Metadata m = (Metadata) state.getToken();
			m.resolve(key.getURI());
			if(logMINOR) Logger.minor(this, "Resolved "+m+" : "+key.getURI());
			resolveAndStartBase();
		}
	}
	
	public void onTransition(ClientPutState oldState, ClientPutState newState) {
		synchronized(this) {
			if(logMINOR) Logger.minor(this, "Transition: "+oldState+" -> "+newState);
		}
	}
	
	public void onMetadata(Metadata m, ClientPutState state) {
		// Ignore
	}

	public void notifyClients() {
		ctx.eventProducer.produceEvent(new SplitfileProgressEvent(this.totalBlocks, this.successfulBlocks, this.failedBlocks, this.fatallyFailedBlocks, this.minSuccessBlocks, this.blockSetFinalized));
	}

	public void onBlockSetFinished(ClientPutState state) {
		synchronized(this) {
			this.metadataBlockSetFinalized = true;
			if(!waitingForBlockSets.isEmpty()) return;
		}
		this.blockSetFinalized();
	}

	public void blockSetFinalized() {
		synchronized(this) {
			if(!metadataBlockSetFinalized) return;
			if(waitingForBlockSets.isEmpty()) return;
		}
		super.blockSetFinalized();
	}
	
	/**
	 * Convert a HashMap of name -> bucket to a HashSet of ManifestEntry's.
	 * All are to have mimeOverride=null, i.e. we use the auto-detected mime type
	 * from the filename.
	 */
	public static HashMap bucketsByNameToManifestEntries(HashMap bucketsByName) {
		HashMap manifestEntries = new HashMap();
		Iterator i = bucketsByName.keySet().iterator();
		while(i.hasNext()) {
			String name = (String) i.next();
			Object o = bucketsByName.get(name);
			if(o instanceof Bucket) {
				Bucket data = (Bucket) bucketsByName.get(name);
				manifestEntries.put(name, new ManifestElement(name, data, null,data.size()));
			} else if(o instanceof HashMap) {
				manifestEntries.put(name, bucketsByNameToManifestEntries((HashMap)o));
			} else
				throw new IllegalArgumentException(String.valueOf(o));
		}
		return manifestEntries;
	}

	/**
	 * Convert a hierarchy of HashMap's of ManifestEntries into a series of 
	 * ManifestElement's, each of which has a full path.
	 */
	public static ManifestElement[] flatten(HashMap manifestElements) {
		Vector v = new Vector();
		flatten(manifestElements, v, "");
		return (ManifestElement[]) v.toArray(new ManifestElement[v.size()]);
	}

	public static void flatten(HashMap manifestElements, Vector v, String prefix) {
		Iterator i = manifestElements.keySet().iterator();
		while(i.hasNext()) {
			String name = (String) i.next();
			String fullName = prefix.length() == 0 ? name : prefix+ '/' +name;
			Object o = manifestElements.get(name);
			if(o instanceof HashMap) {
				flatten((HashMap)o, v, fullName);
			} else if(o instanceof ManifestElement) {
				ManifestElement me = (ManifestElement) o;
				v.add(new ManifestElement(me, fullName));
			} else
				throw new IllegalStateException(String.valueOf(o));
		}
	}

	/**
	 * Opposite of flatten(...).
	 * Note that this can throw a ClassCastException if the vector passed in is
	 * bogus (has files pretending to be directories).
	 */
	public static HashMap unflatten(Vector v) {
		HashMap manifestElements = new HashMap();
		for(int i=0;i<v.size();i++) {
			ManifestElement oldElement = (ManifestElement)v.get(i);
			add(oldElement, oldElement.getName(), manifestElements);
		}
		return manifestElements;
	}
	
	private static void add(ManifestElement e, String namePart, HashMap target) {
		int idx = namePart.indexOf('/');
		if(idx < 0) {
			target.put(namePart, new ManifestElement(e, namePart));
		} else {
			String before = namePart.substring(0, idx);
			String after = namePart.substring(idx+1);
			HashMap hm = (HashMap) (target.get(before));
			if(hm == null) {
				hm = new HashMap();
				target.put(before, hm);
			}
			add(e, after, hm);
		}
	}

	public int countFiles() {
		return numberOfFiles;
	}

	public long totalSize() {
		return totalSize;
	}

	public void onMajorProgress() {
		cb.onMajorProgress();
	}

	protected void onFetchable(PutHandler handler) {
		synchronized(this) {
			putHandlersWaitingForFetchable.remove(handler);
			if(fetchable) return;
			if(!putHandlersWaitingForFetchable.isEmpty()) return;
			if(!hasResolvedBase) return;
			if(!metadataPuttersUnfetchable.isEmpty()) return;
			fetchable = true;
		}
		cb.onFetchable(this);
	}

	public void onFetchable(ClientPutState state) {
		Metadata m = (Metadata) state.getToken();
		synchronized(this) {
			metadataPuttersUnfetchable.remove(m);
			if(!metadataPuttersUnfetchable.isEmpty()) return;
			if(fetchable) return;
			if(!putHandlersWaitingForFetchable.isEmpty()) return;
			fetchable = true;
		}
		cb.onFetchable(this);
	}

	public void onTransition(ClientGetState oldState, ClientGetState newState) {
		// Ignore
	}

}
