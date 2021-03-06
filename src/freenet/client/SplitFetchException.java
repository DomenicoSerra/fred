/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client;

public class SplitFetchException extends FetchException {

	final int failed;
	final int fatal;
	final int succeeded;
	final int enough;
	
	public SplitFetchException(int failed, int fatal, int succeeded, int enough, FailureCodeTracker errorCodes) {
		super(FetchException.SPLITFILE_ERROR, errorCodes);
		this.failed = failed;
		this.fatal = fatal;
		this.succeeded = succeeded;
		this.enough = enough;
	}

	public String getMessage() {
		return "Splitfile fetch failure: "+failed+" failed, "+fatal+" fatal errors, "+succeeded+" succeeded, "+enough+" enough";
	}
	
	private static final long serialVersionUID = 1523809424508826893L;

}
