/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client;

import freenet.support.api.Bucket;

/**
 * @author toad
 * The public face (to Fetcher, for example) of ArchiveStoreContext.
 * Just has methods for fetching stuff.
 */
public interface ArchiveHandler {

	/**
	 * Get the metadata for this ZIP manifest, as a Bucket.
	 * @throws FetchException If the container could not be fetched.
	 * @throws MetadataParseException If there was an error parsing intermediary metadata.
	 */
	public abstract Bucket getMetadata(ArchiveContext archiveContext,
			ClientMetadata dm, int recursionLevel, 
			boolean dontEnterImplicitArchives)
			throws ArchiveFailureException, ArchiveRestartException,
			MetadataParseException, FetchException;

	/**
	 * Get a file from this ZIP manifest, as a Bucket.
	 * If possible, read it from cache. If necessary, refetch the 
	 * container and extract it. If that fails, throw.
	 * @param inSplitZipManifest If true, indicates that the key points to a splitfile zip manifest,
	 * which means that we need to pass a flag to the fetcher to tell it to pretend it was a straight
	 * splitfile.
	 * @throws FetchException 
	 * @throws MetadataParseException 
	 */
	public abstract Bucket get(String internalName,
			ArchiveContext archiveContext, 
			ClientMetadata dm, int recursionLevel, 
			boolean dontEnterImplicitArchives)
			throws ArchiveFailureException, ArchiveRestartException,
			MetadataParseException, FetchException;

	/**
	 * Get the archive type.
	 */
	public abstract short getArchiveType();

}