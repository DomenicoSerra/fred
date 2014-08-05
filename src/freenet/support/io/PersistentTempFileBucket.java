package freenet.support.io;

import java.io.Serializable;

import freenet.client.async.ClientContext;
import freenet.support.Logger;
import freenet.support.api.Bucket;

public class PersistentTempFileBucket extends TempFileBucket implements Serializable {

    private static final long serialVersionUID = 1L;

    public PersistentTempFileBucket(long id, FilenameGenerator generator) {
		this(id, generator, true);
	}
	
	protected PersistentTempFileBucket(long id, FilenameGenerator generator, boolean deleteOnFree) {
		super(id, generator, deleteOnFree);
	}
	
	@Override
	protected boolean deleteOnFinalize() {
		// Do not delete on finalize
		return false;
	}
	
	@Override
	protected boolean deleteOnExit() {
		// DO NOT DELETE ON EXIT !!!!
		return false;
	}
	
	/** Must override createShadow() so it creates a persistent bucket, which will have
	 * deleteOnExit() = deleteOnFinalize() = false.
	 */
	@Override
	public Bucket createShadow() {
		PersistentTempFileBucket ret = new PersistentTempFileBucket(filenameID, generator, false);
		ret.setReadOnly();
		if(!getFile().exists()) Logger.error(this, "File does not exist when creating shadow: "+getFile());
		return ret;
	}
	
    @Override
    public void onResume(ClientContext context) {
        // Ewww writing parent's field.
        generator = context.persistentFG;
    }
	
}
