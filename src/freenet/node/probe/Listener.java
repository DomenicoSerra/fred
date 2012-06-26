package freenet.node.probe;

/**
 * Listener for the different types of probe results.
 */
public interface Listener {
	/**
	 * An error occurred.
	 * @param error type: What error occurred. Can be one of Probe.ProbeError.
	 * @param rawError Error byte value. If the error is an UNKNOWN which occurred locally this contains the
	 *                 unrecognized error code from the message. Otherwise it is null.
	 */
	void onError(Error error, Byte rawError);

	/**
	 * Endpoint opted not to respond with the requested information.
	 */
	void onRefused();

	/**
	 * Output bandwidth limit result.
	 * @param outputBandwidth endpoint's reported output bandwidth limit in KiB per second.
	 */
	void onOutputBandwidth(long outputBandwidth);

	/**
	 * Build result.
	 * @param build endpoint's reported build / main version.
	 */
	void onBuild(int build);

	/**
	 * Identifier result.
	 * @param identifier identifier given by endpoint.
	 * @param uptimePercentage quantized noisy 7-day uptime percentage
	 */
	void onIdentifier(long identifier, byte uptimePercentage);

	/**
	 * Link length result.
	 * @param linkLengths endpoint's reported link lengths.
	 */
	void onLinkLengths(float[] linkLengths);

	/**
	 * Location result.
	 * @param location location given by endpoint.
	 */
	void onLocation(float location);

	/**
	 * Store size result.
	 * @param storeSize endpoint's reported store size in GiB.
	 */
	void onStoreSize(long storeSize);

	/**
	 * Uptime result.
	 * @param uptimePercentage endpoint's reported percentage uptime in the last requested period; either
	 *                         48 hour or 7 days.
	 */
	void onUptime(float uptimePercentage);
}
