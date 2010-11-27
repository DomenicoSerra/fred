package freenet.node.fcp;

import java.io.File;

import freenet.client.InsertContext;
import freenet.client.InsertContext.CompatibilityMode;
import freenet.keys.FreenetURI;

/** Cached status of a download of a file i.e. a ClientGet */
public class DownloadRequestStatus extends RequestStatus {
	
	private int failureCode;
	private String failureReasonShort;
	private String failureReasonLong;
	// These can be guesses
	private String mimeType;
	private long dataSize;
	// Null = to temp space
	private final File destFilename;
	private CompatibilityMode[] detectedCompatModes;
	private byte[] detectedSplitfileKey;
	private final FreenetURI uri;
	
	synchronized void setFinished(boolean success, long dataSize, String mimeType, 
			int failureCode, String failureReasonLong, String failureReasonShort) {
		setFinished(success);
		this.dataSize = dataSize;
		this.mimeType = mimeType;
		this.failureCode = failureCode;
		this.failureReasonLong = failureReasonLong;
		this.failureReasonShort = failureReasonShort;
	}
	
	DownloadRequestStatus(String identifier, short persistence, boolean started, boolean finished, 
			boolean success, int total, int min, int fetched, int fatal, int failed,
			boolean totalFinalized, long last, short prio, // all these passed to parent
			int failureCode, String mime, long size, File dest, CompatibilityMode[] compat,
			byte[] splitfileKey, FreenetURI uri, String failureReasonShort, String failureReasonLong) {
		super(identifier, persistence, started, finished, success, total, min, fetched, 
				fatal, failed, totalFinalized, last, prio);
		this.failureCode = failureCode;
		this.mimeType = mime;
		this.dataSize = size;
		this.destFilename = dest;
		this.detectedCompatModes = compat;
		this.detectedSplitfileKey = splitfileKey;
		this.uri = uri;
		this.failureReasonShort = failureReasonShort;
		this.failureReasonLong = failureReasonLong;
	}
	
	public final boolean toTempSpace() {
		return destFilename == null;
	}

	public int getFailureCode() {
		return failureCode;
	}

	public String getMIMEType() {
		return mimeType;
	}

	public long getDataSize() {
		return dataSize;
	}

	public File getDestFilename() {
		return destFilename;
	}

	public CompatibilityMode[] getCompatibilityMode() {
		return detectedCompatModes;
	}

	public byte[] getOverriddenSplitfileCryptoKey() {
		return detectedSplitfileKey;
	}

	@Override
	public FreenetURI getURI() {
		return uri;
	}

	@Override
	public String getFailureReason(boolean longDescription) {
		if(longDescription)
			return failureReasonLong;
		else
			return failureReasonShort;
	}

	public synchronized void updateDetectedCompatModes(
			InsertContext.CompatibilityMode[] compatModes) {
		this.detectedCompatModes = compatModes;
	}

	public synchronized void updateDetectedSplitfileKey(byte[] splitfileKey) {
		this.detectedSplitfileKey = splitfileKey;
	}

}
