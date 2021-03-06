/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.oldplugins.plugin;

import java.io.IOException;

import freenet.clients.http.ToadletContext;
import freenet.clients.http.ToadletContextClosedException;
import freenet.support.api.HTTPRequest;

/**
 * Interface for plugins that support HTTP interaction.
 * 
 * @author David 'Bombe' Roden &lt;bombe@freenetproject.org&gt;
 * @version $Id$
 */
public interface HttpPlugin extends Plugin {

	/**
	 * Handles the GET request.
	 * 
	 * @param request
	 *            The request used to interact with this plugin
	 * @param context
	 *            The context of the HTTP request
	 * @throws IOException
	 *             if an I/O error occurs
	 * @throws ToadletContextClosedException
	 *             if the context has already been closed.
	 */
	public void handleGet(HTTPRequest request, ToadletContext context) throws IOException, ToadletContextClosedException;

	/**
	 * Handles the POST request.
	 * 
	 * @param request
	 *            The request used to interact with this plugin
	 * @param context
	 *            The context of the HTTP request
	 * @throws IOException
	 *             if an I/O error occurs
	 * @throws ToadletContextClosedException
	 *             if the context has already been closed.
	 */
	public void handlePost(HTTPRequest request, ToadletContext context) throws IOException, ToadletContextClosedException;

}
