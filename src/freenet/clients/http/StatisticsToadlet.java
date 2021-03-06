package freenet.clients.http;

import java.io.IOException;
import java.net.URI;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import freenet.client.HighLevelSimpleClient;
import freenet.config.SubConfig;
import freenet.io.comm.IOStatisticCollector;
import freenet.l10n.L10n;
import freenet.node.Location;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.node.NodeStarter;
import freenet.node.NodeStats;
import freenet.node.PeerManager;
import freenet.node.PeerNodeStatus;
import freenet.node.RequestStarterGroup;
import freenet.node.Version;
import freenet.support.HTMLNode;
import freenet.support.SizeUtil;
import freenet.support.TimeUtil;
import freenet.support.api.HTTPRequest;

public class StatisticsToadlet extends Toadlet {

	static final NumberFormat thousendPoint = NumberFormat.getInstance();
	
	static class MyComparator implements Comparator {

		public int compare(Object arg0, Object arg1) {
			Object[] row0 = (Object[])arg0;
			Object[] row1 = (Object[])arg1;
			Integer stat0 = (Integer) row0[2];  // 2 = status
			Integer stat1 = (Integer) row1[2];
			int x = stat0.compareTo(stat1);
			if(x != 0) return x;
			String name0 = (String) row0[9];  // 9 = node name
			String name1 = (String) row1[9];
			return name0.toLowerCase().compareTo(name1.toLowerCase());
		}

	}

	private class STMessageCount {
		public String messageName;
		public int messageCount;

		STMessageCount( String messageName, int messageCount ) {
			this.messageName = messageName;
			this.messageCount = messageCount;
		}
	}

	private final Node node;
	private final NodeClientCore core;
	private final NodeStats stats;
	private final PeerManager peers;
	private final DecimalFormat fix1p1 = new DecimalFormat("0.0");
	private final DecimalFormat fix1p2 = new DecimalFormat("0.00");
	private final DecimalFormat fix1p4 = new DecimalFormat("0.0000");
	private final DecimalFormat fix1p6sci = new DecimalFormat("0.######E0");
	private final DecimalFormat fix3p1pct = new DecimalFormat("##0.0%");
	private final DecimalFormat fix3p1US = new DecimalFormat("##0.0", new DecimalFormatSymbols(Locale.US));
	private final DecimalFormat fix3pctUS = new DecimalFormat("##0%", new DecimalFormatSymbols(Locale.US));
	private final DecimalFormat fix6p6 = new DecimalFormat("#####0.0#####");

	protected StatisticsToadlet(Node n, NodeClientCore core, HighLevelSimpleClient client) {
		super(client);
		this.node = n;
		this.core = core;
		stats = node.nodeStats;
		peers = node.peers;
	}

	public String supportedMethods() {
		return "GET";
	}

	/**
	 * Counts the peers in <code>peerNodes</code> that have the specified
	 * status.
	 * @param peerNodeStatuses The peer nodes' statuses
	 * @param status The status to count
	 * @return The number of peers that have the specified status.
	 */
	private int getPeerStatusCount(PeerNodeStatus[] peerNodeStatuses, int status) {
		int count = 0;
		for (int peerIndex = 0, peerCount = peerNodeStatuses.length; peerIndex < peerCount; peerIndex++) {
			if (peerNodeStatuses[peerIndex].getStatusValue() == status) {
				count++;
			}
		}
		return count;
	}

	public void handleGet(URI uri, HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {

		if(!ctx.isAllowedFullAccess()) {
			super.sendErrorPage(ctx, 403, "Unauthorized", L10n.getString("Toadlet.unauthorized"));
			return;
		}

		final boolean advancedModeEnabled = node.isAdvancedModeEnabled();
		final SubConfig nodeConfig = node.config.get("node");

		/* gather connection statistics */
		PeerNodeStatus[] peerNodeStatuses = peers.getPeerNodeStatuses();
		Arrays.sort(peerNodeStatuses, new Comparator() {
			public int compare(Object first, Object second) {
				PeerNodeStatus firstNode = (PeerNodeStatus) first;
				PeerNodeStatus secondNode = (PeerNodeStatus) second;
				int statusDifference = firstNode.getStatusValue() - secondNode.getStatusValue();
				if (statusDifference != 0) {
					return statusDifference;
				}
				return 0;
			}
		});

		int numberOfConnected = getPeerStatusCount(peerNodeStatuses, PeerManager.PEER_NODE_STATUS_CONNECTED);
		int numberOfRoutingBackedOff = getPeerStatusCount(peerNodeStatuses, PeerManager.PEER_NODE_STATUS_ROUTING_BACKED_OFF);
		int numberOfTooNew = getPeerStatusCount(peerNodeStatuses, PeerManager.PEER_NODE_STATUS_TOO_NEW);
		int numberOfTooOld = getPeerStatusCount(peerNodeStatuses, PeerManager.PEER_NODE_STATUS_TOO_OLD);
		int numberOfDisconnected = getPeerStatusCount(peerNodeStatuses, PeerManager.PEER_NODE_STATUS_DISCONNECTED);
		int numberOfNeverConnected = getPeerStatusCount(peerNodeStatuses, PeerManager.PEER_NODE_STATUS_NEVER_CONNECTED);
		int numberOfDisabled = getPeerStatusCount(peerNodeStatuses, PeerManager.PEER_NODE_STATUS_DISABLED);
		int numberOfBursting = getPeerStatusCount(peerNodeStatuses, PeerManager.PEER_NODE_STATUS_BURSTING);
		int numberOfListening = getPeerStatusCount(peerNodeStatuses, PeerManager.PEER_NODE_STATUS_LISTENING);
		int numberOfListenOnly = getPeerStatusCount(peerNodeStatuses, PeerManager.PEER_NODE_STATUS_LISTEN_ONLY);

		HTMLNode pageNode = ctx.getPageMaker().getPageNode("Statistics for " + node.getMyName(), ctx);
		HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);

		// FIXME! We need some nice images
		final long now = System.currentTimeMillis();
		final long nodeUptimeSeconds = (now - node.startupTime) / 1000;

		if(ctx.isAllowedFullAccess())
			contentNode.addChild(core.alerts.createSummary());

		double swaps = (double)node.getSwaps();
		double noSwaps = (double)node.getNoSwaps();

		HTMLNode overviewTable = contentNode.addChild("table", "class", "column");
		HTMLNode overviewTableRow = overviewTable.addChild("tr");
		HTMLNode nextTableCell = overviewTableRow.addChild("td", "class", "first");

		// node version information box
		HTMLNode versionInfobox = nextTableCell.addChild("div", "class", "infobox");
		
		drawNodeVersionBox(versionInfobox);
		
		// jvm stats box
		HTMLNode jvmStatsInfobox = nextTableCell.addChild("div", "class", "infobox");
		
		drawJVMStatsBox(jvmStatsInfobox);
		
		// Statistic gathering box
		HTMLNode statGatheringBox =  nextTableCell.addChild(ctx.getPageMaker().getInfobox("Statistic gathering"));
		// Generate a Thread-Dump
		if(node.isUsingWrapper()){
			HTMLNode threadDumpForm = ctx.addFormChild(statGatheringBox, "/", "threadDumpForm");
			threadDumpForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "getThreadDump", l10n("threadDumpButton")});
		}
		// BDB statistics dump 
		HTMLNode JEStatsForm = ctx.addFormChild(statGatheringBox, "/", "JEStatsForm");
		JEStatsForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "getJEStatsDump", l10n("jeDumpButton")});
		// Get logs
		HTMLNode logsList = statGatheringBox.addChild("ul");
		if(nodeConfig.config.get("logger").getBoolean("enabled"))
			logsList.addChild("li").addChild("a", new String[]{ "href", "target"}, new String[]{ "/?latestlog", "_new"}, l10n("getLogs"));
		logsList.addChild("li").addChild("a", "href", TranslationToadlet.TOADLET_URL+"?getOverrideTranlationFile").addChild("#", L10n.getString("TranslationToadlet.downloadTranslationsFile"));
		
		if(advancedModeEnabled) {
			// store size box
			HTMLNode storeSizeInfobox = nextTableCell.addChild("div", "class", "infobox");
			
			drawStoreSizeBox(storeSizeInfobox, nodeUptimeSeconds);
			
			if(numberOfConnected + numberOfRoutingBackedOff > 0) {
				// Load balancing box
				// Include overall window, and RTTs for each
				RequestStarterGroup starters = core.requestStarters;
				double window = starters.getWindow();
				double realWindow = starters.getRealWindow();
				HTMLNode loadStatsInfobox = nextTableCell.addChild("div", "class", "infobox");
				loadStatsInfobox.addChild("div", "class", "infobox-header", "Load limiting");
				HTMLNode loadStatsContent = loadStatsInfobox.addChild("div", "class", "infobox-content");
				HTMLNode loadStatsList = loadStatsContent.addChild("ul");
				loadStatsList.addChild("li", "Global window: "+window);
				loadStatsList.addChild("li", "Real global window: "+realWindow);
				loadStatsList.addChild("li", starters.statsPageLine(false, false));
				loadStatsList.addChild("li", starters.statsPageLine(true, false));
				loadStatsList.addChild("li", starters.statsPageLine(false, true));
				loadStatsList.addChild("li", starters.statsPageLine(true, true));
				loadStatsList.addChild("li", starters.diagnosticThrottlesLine(false));
				loadStatsList.addChild("li", starters.diagnosticThrottlesLine(true));
			}
		}

		if(numberOfConnected + numberOfRoutingBackedOff > 0) {			

			// Activity box
			nextTableCell = overviewTableRow.addChild("td", "class", "last");
			HTMLNode activityInfobox = nextTableCell.addChild("div", "class", "infobox");
			
			drawActivityBox(activityInfobox, advancedModeEnabled);

			/* node status overview box */
			if(advancedModeEnabled) {
				HTMLNode overviewInfobox = nextTableCell.addChild("div", "class", "infobox");
				drawOverviewBox(overviewInfobox, nodeUptimeSeconds, now, swaps, noSwaps);
			}

			// Peer statistics box
			HTMLNode peerStatsInfobox = nextTableCell.addChild("div", "class", "infobox");
			
			drawPeerStatsBox(peerStatsInfobox, advancedModeEnabled, numberOfConnected, numberOfRoutingBackedOff, 
					numberOfTooNew, numberOfTooOld, numberOfDisconnected, numberOfNeverConnected, numberOfDisabled, 
					numberOfBursting, numberOfListening, numberOfListenOnly);

			// Bandwidth box
			HTMLNode bandwidthInfobox = nextTableCell.addChild("div", "class", "infobox");
			
			drawBandwidthBox(bandwidthInfobox, nodeUptimeSeconds);
		}

		if(advancedModeEnabled) {

			// Peer routing backoff reason box
			HTMLNode backoffReasonInfobox = nextTableCell.addChild("div", "class", "infobox");
			backoffReasonInfobox.addChild("div", "class", "infobox-header", "Peer backoff reasons");
			HTMLNode backoffReasonContent = backoffReasonInfobox.addChild("div", "class", "infobox-content");
			String [] routingBackoffReasons = peers.getPeerNodeRoutingBackoffReasons();
			if(routingBackoffReasons.length == 0) {
				backoffReasonContent.addChild("#", "Good, your node is not backed off from any peers!");
			} else {
				HTMLNode reasonList = backoffReasonContent.addChild("ul");
				for(int i=0;i<routingBackoffReasons.length;i++) {
					int reasonCount = peers.getPeerNodeRoutingBackoffReasonSize(routingBackoffReasons[i]);
					if(reasonCount > 0) {
						reasonList.addChild("li", routingBackoffReasons[i] + '\u00a0' + reasonCount);
					}
				}
			}

			//Swap statistics box
			HTMLNode locationSwapInfobox = nextTableCell.addChild("div", "class", "infobox");
			drawSwapStatsBox(locationSwapInfobox, nodeUptimeSeconds, swaps, noSwaps);

			// unclaimedFIFOMessageCounts box
			HTMLNode unclaimedFIFOMessageCountsInfobox = nextTableCell.addChild("div", "class", "infobox");
			drawUnclaimedFIFOMessageCountsBox(unclaimedFIFOMessageCountsInfobox);

			nextTableCell = overviewTableRow.addChild("td");

			// thread usage box
			HTMLNode threadUsageInfobox = nextTableCell.addChild("div", "class", "infobox");
			threadUsageInfobox.addChild("div", "class", "infobox-header", "Thread usage");
			HTMLNode threadUsageContent = threadUsageInfobox.addChild("div", "class", "infobox-content");
			HTMLNode threadUsageList = threadUsageContent.addChild("ul");
			getThreadNames(threadUsageList);
			
			// rejection reasons box
			drawRejectReasonsBox(nextTableCell);

			// peer distribution box
			overviewTableRow = overviewTable.addChild("tr");
			nextTableCell = overviewTableRow.addChild("td", "class", "first");
			HTMLNode peerCircleInfobox = nextTableCell.addChild("div", "class", "infobox");
			peerCircleInfobox.addChild("div", "class", "infobox-header", "Peer\u00a0Location\u00a0Distribution (w/pReject)");
			HTMLNode peerCircleTable = peerCircleInfobox.addChild("table");
			addPeerCircle(peerCircleTable);
			nextTableCell = overviewTableRow.addChild("td");

			// node distribution box
			HTMLNode nodeCircleInfobox = nextTableCell.addChild("div", "class", "infobox");
			nodeCircleInfobox.addChild("div", "class", "infobox-header", "Node\u00a0Location\u00a0Distribution (w/Swap\u00a0Age)");
			HTMLNode nodeCircleTable = nodeCircleInfobox.addChild("table");
			addNodeCircle(nodeCircleTable);
		}

		this.writeHTMLReply(ctx, 200, "OK", pageNode.generate());
	}

	private void drawRejectReasonsBox(HTMLNode nextTableCell) {
		HTMLNode rejectReasonsTable = new HTMLNode("table");
		if(!node.nodeStats.getRejectReasonsTable(rejectReasonsTable))
			return;
		HTMLNode rejectReasonsInfobox = nextTableCell.addChild("div", "class", "infobox");
		rejectReasonsInfobox.addChild("div", "class", "infobox-header", "Preemptive Rejection Reasons");
		rejectReasonsInfobox.addChild(rejectReasonsTable);
	}

	private void drawNodeVersionBox(HTMLNode versionInfobox) {
		
		versionInfobox.addChild("div", "class", "infobox-header", l10n("versionTitle"));
		HTMLNode versionInfoboxContent = versionInfobox.addChild("div", "class", "infobox-content");
		HTMLNode versionInfoboxList = versionInfoboxContent.addChild("ul");
		versionInfoboxList.addChild("li", L10n.getString("WelcomeToadlet.version", new String[] { "fullVersion", "build", "rev" },
				new String[] { Version.nodeVersion, Integer.toString(Version.buildNumber()), Version.cvsRevision }));
		if(NodeStarter.extBuildNumber < NodeStarter.RECOMMENDED_EXT_BUILD_NUMBER)
			versionInfoboxList.addChild("li", L10n.getString("WelcomeToadlet.extVersionWithRecommended", 
					new String[] { "build", "recbuild", "rev" }, 
					new String[] { Integer.toString(NodeStarter.extBuildNumber), Integer.toString(NodeStarter.RECOMMENDED_EXT_BUILD_NUMBER), NodeStarter.extRevisionNumber }));
		else
			versionInfoboxList.addChild("li", L10n.getString("WelcomeToadlet.extVersion", new String[] { "build", "rev" },
					new String[] { Integer.toString(NodeStarter.extBuildNumber), NodeStarter.extRevisionNumber }));
		
	}

	private void drawJVMStatsBox(HTMLNode jvmStatsInfobox) {
		
		jvmStatsInfobox.addChild("div", "class", "infobox-header", l10n("jvmInfoTitle"));
		HTMLNode jvmStatsInfoboxContent = jvmStatsInfobox.addChild("div", "class", "infobox-content");
		HTMLNode jvmStatsList = jvmStatsInfoboxContent.addChild("ul");

		Runtime rt = Runtime.getRuntime();
		float freeMemory = (float) rt.freeMemory();
		float totalMemory = (float) rt.totalMemory();
		float maxMemory = (float) rt.maxMemory();

		long usedJavaMem = (long)(totalMemory - freeMemory);
		long allocatedJavaMem = (long)totalMemory;
		long maxJavaMem = (long)maxMemory;
		int availableCpus = rt.availableProcessors();

		int threadCount = stats.getActiveThreadCount();

		jvmStatsList.addChild("li", l10n("usedMemory", "memory", SizeUtil.formatSize(usedJavaMem, true)));
		jvmStatsList.addChild("li", l10n("allocMemory", "memory", SizeUtil.formatSize(allocatedJavaMem, true)));
		jvmStatsList.addChild("li", l10n("maxMemory", "memory", SizeUtil.formatSize(maxJavaMem, true)));
		jvmStatsList.addChild("li", l10n("threads", new String[] { "running", "max" },
				new String[] { thousendPoint.format(threadCount), Integer.toString(stats.getThreadLimit()) }));
		jvmStatsList.addChild("li", l10n("cpus", "count", Integer.toString(availableCpus)));
		jvmStatsList.addChild("li", l10n("jvmVendor", "vendor", System.getProperty("java.vm.vendor")));
		jvmStatsList.addChild("li", l10n("jvmVersion", "version", System.getProperty("java.vm.version")));
		jvmStatsList.addChild("li", l10n("osName", "name", System.getProperty("os.name")));
		jvmStatsList.addChild("li", l10n("osVersion", "version", System.getProperty("os.version")));
		jvmStatsList.addChild("li", l10n("osArch", "arch", System.getProperty("os.arch")));
		
	}

	private void drawStoreSizeBox(HTMLNode storeSizeInfobox, long nodeUptimeSeconds) {
		
		storeSizeInfobox.addChild("div", "class", "infobox-header", "Datastore");
		HTMLNode storeSizeInfoboxContent = storeSizeInfobox.addChild("div", "class", "infobox-content");
		HTMLNode storeSizeList = storeSizeInfoboxContent.addChild("ul");

		final long fix32kb = 32 * 1024;

		long cachedKeys = node.getChkDatacache().keyCount();
		long cachedSize = cachedKeys * fix32kb;
		long storeKeys = node.getChkDatastore().keyCount();
		long storeSize = storeKeys * fix32kb;
		long overallKeys = cachedKeys + storeKeys;
		long overallSize = cachedSize + storeSize;

		long maxCachedKeys = node.getChkDatacache().getMaxKeys();
		long maxStoreKeys = node.getChkDatastore().getMaxKeys();
		long maxOverallKeys = node.getMaxTotalKeys();
		long maxOverallSize = maxOverallKeys * fix32kb;

		long cachedStoreHits = node.getChkDatacache().hits();
		long cachedStoreMisses = node.getChkDatacache().misses();
		long cacheAccesses = cachedStoreHits + cachedStoreMisses;
		long storeHits = node.getChkDatastore().hits();
		long storeMisses = node.getChkDatastore().misses();
		long storeAccesses = storeHits + storeMisses;
		long overallAccesses = storeAccesses + cacheAccesses;

		// REDFLAG Don't show database version because it's not possible to get it accurately.
		// (It's a public static constant, so it will use the version from compile time of freenet.jar)

		storeSizeList.addChild("li", 
				"Cached keys:\u00a0" + thousendPoint.format(cachedKeys) + 
				"\u00a0(" + SizeUtil.formatSize(cachedSize, true) + ')' +
				"\u00a0(" + ((cachedKeys*100)/maxCachedKeys) + "%)");

		storeSizeList.addChild("li", 
				"Stored keys:\u00a0" + thousendPoint.format(storeKeys) + 
				"\u00a0(" + SizeUtil.formatSize(storeSize, true) + ')' +
				"\u00a0(" + ((storeKeys*100)/maxStoreKeys) + "%)");

		storeSizeList.addChild("li", 
				"Overall size:\u00a0" + thousendPoint.format(overallKeys) + 
				"\u00a0/\u00a0" + thousendPoint.format(maxOverallKeys) +
				"\u00a0(" + SizeUtil.formatSize(overallSize, true) + 
				"\u00a0/\u00a0" + SizeUtil.formatSize(maxOverallSize, true) + 
				")\u00a0(" + ((overallKeys*100)/maxOverallKeys) + "%)");

		if(cacheAccesses > 0)
			storeSizeList.addChild("li", 
					"Cache hits:\u00a0" + thousendPoint.format(cachedStoreHits) + 
					"\u00a0/\u00a0"+thousendPoint.format(cacheAccesses) +
					"\u00a0(" + ((cachedStoreHits*100) / (cacheAccesses)) + "%)");

		if(storeAccesses > 0)
			storeSizeList.addChild("li", 
					"Store hits:\u00a0" + thousendPoint.format(storeHits) + 
					"\u00a0/\u00a0"+thousendPoint.format(storeAccesses) +
					"\u00a0(" + ((storeHits*100) / (storeAccesses)) + "%)");

		storeSizeList.addChild("li", 
				"Avg. access rate:\u00a0" + thousendPoint.format(overallAccesses/nodeUptimeSeconds) + "/sec");
		
	}

	private void drawUnclaimedFIFOMessageCountsBox(HTMLNode unclaimedFIFOMessageCountsInfobox) {
		
		unclaimedFIFOMessageCountsInfobox.addChild("div", "class", "infobox-header", "unclaimedFIFO Message Counts");
		HTMLNode unclaimedFIFOMessageCountsInfoboxContent = unclaimedFIFOMessageCountsInfobox.addChild("div", "class", "infobox-content");
		HTMLNode unclaimedFIFOMessageCountsList = unclaimedFIFOMessageCountsInfoboxContent.addChild("ul");
		Map unclaimedFIFOMessageCountsMap = node.getUSM().getUnclaimedFIFOMessageCounts();
		STMessageCount[] unclaimedFIFOMessageCountsArray = new STMessageCount[unclaimedFIFOMessageCountsMap.size()];
		int i = 0;
		int totalCount = 0;
		for (Iterator messageCounts = unclaimedFIFOMessageCountsMap.keySet().iterator(); messageCounts.hasNext(); ) {
			String messageName = (String) messageCounts.next();
			int messageCount = ((Integer) unclaimedFIFOMessageCountsMap.get(messageName)).intValue();
			totalCount = totalCount + messageCount;
			unclaimedFIFOMessageCountsArray[i++] = new STMessageCount( messageName, messageCount );
		}
		Arrays.sort(unclaimedFIFOMessageCountsArray, new Comparator() {
			public int compare(Object first, Object second) {
				STMessageCount firstCount = (STMessageCount) first;
				STMessageCount secondCount = (STMessageCount) second;
				return secondCount.messageCount - firstCount.messageCount;  // sort in descending order
			}
		});
		for (int countsArrayIndex = 0, countsArrayCount = unclaimedFIFOMessageCountsArray.length; countsArrayIndex < countsArrayCount; countsArrayIndex++) {
			STMessageCount messageCountItem = (STMessageCount) unclaimedFIFOMessageCountsArray[countsArrayIndex];
			int thisMessageCount = messageCountItem.messageCount;
			double thisMessagePercentOfTotal = ((double) thisMessageCount) / ((double) totalCount);
			unclaimedFIFOMessageCountsList.addChild("li", "" + messageCountItem.messageName + ":\u00a0" + thisMessageCount + "\u00a0(" + fix3p1pct.format(thisMessagePercentOfTotal) + ')');
		}
		unclaimedFIFOMessageCountsList.addChild("li", "Unclaimed Messages Considered:\u00a0" + totalCount);
		
	}

	private void drawSwapStatsBox(HTMLNode locationSwapInfobox, long nodeUptimeSeconds, double swaps, double noSwaps) {
		
		locationSwapInfobox.addChild("div", "class", "infobox-header", "Location swaps");
		int startedSwaps = node.getStartedSwaps();
		int swapsRejectedAlreadyLocked = node.getSwapsRejectedAlreadyLocked();
		int swapsRejectedNowhereToGo = node.getSwapsRejectedNowhereToGo();
		int swapsRejectedRateLimit = node.getSwapsRejectedRateLimit();
		int swapsRejectedLoop = node.getSwapsRejectedLoop();
		int swapsRejectedRecognizedID = node.getSwapsRejectedRecognizedID();
		double locChangeSession = node.getLocationChangeSession();
		int averageSwapTime = node.getAverageOutgoingSwapTime();
		int sendSwapInterval = node.getSendSwapInterval();

		HTMLNode locationSwapInfoboxContent = locationSwapInfobox.addChild("div", "class", "infobox-content");
		HTMLNode locationSwapList = locationSwapInfoboxContent.addChild("ul");
		if (swaps > 0.0) {
			locationSwapList.addChild("li", "locChangeSession:\u00a0" + fix1p6sci.format(locChangeSession));
			locationSwapList.addChild("li", "locChangePerSwap:\u00a0" + fix1p6sci.format(locChangeSession/swaps));
		}
		if ((swaps > 0.0) && (nodeUptimeSeconds >= 60)) {
			locationSwapList.addChild("li", "locChangePerMinute:\u00a0" + fix1p6sci.format(locChangeSession/(double)(nodeUptimeSeconds/60.0)));
		}
		if ((swaps > 0.0) && (nodeUptimeSeconds >= 60)) {
			locationSwapList.addChild("li", "swapsPerMinute:\u00a0" + fix1p6sci.format(swaps/(double)(nodeUptimeSeconds/60.0)));
		}
		if ((noSwaps > 0.0) && (nodeUptimeSeconds >= 60)) {
			locationSwapList.addChild("li", "noSwapsPerMinute:\u00a0" + fix1p6sci.format(noSwaps/(double)(nodeUptimeSeconds/60.0)));
		}
		if ((swaps > 0.0) && (noSwaps > 0.0)) {
			locationSwapList.addChild("li", "swapsPerNoSwaps:\u00a0" + fix1p6sci.format(swaps/noSwaps));
		}
		if (swaps > 0.0) {
			locationSwapList.addChild("li", "swaps:\u00a0" + (int)swaps);
		}
		if (noSwaps > 0.0) {
			locationSwapList.addChild("li", "noSwaps:\u00a0" + (int)noSwaps);
		}
		if (startedSwaps > 0) {
			locationSwapList.addChild("li", "startedSwaps:\u00a0" + startedSwaps);
		}
		if (swapsRejectedAlreadyLocked > 0) {
			locationSwapList.addChild("li", "swapsRejectedAlreadyLocked:\u00a0" + swapsRejectedAlreadyLocked);
		}
		if (swapsRejectedNowhereToGo > 0) {
			locationSwapList.addChild("li", "swapsRejectedNowhereToGo:\u00a0" + swapsRejectedNowhereToGo);
		}
		if (swapsRejectedRateLimit > 0) {
			locationSwapList.addChild("li", "swapsRejectedRateLimit:\u00a0" + swapsRejectedRateLimit);
		}
		if (swapsRejectedLoop > 0) {
			locationSwapList.addChild("li", "swapsRejectedLoop:\u00a0" + swapsRejectedLoop);
		}
		if (swapsRejectedRecognizedID > 0) {
			locationSwapList.addChild("li", "swapsRejectedRecognizedID:\u00a0" + swapsRejectedRecognizedID);
		}
		locationSwapList.addChild("li", "averageSwapTime:\u00a0" + TimeUtil.formatTime(averageSwapTime, 2, true));
		locationSwapList.addChild("li", "sendSwapInterval:\u00a0" + TimeUtil.formatTime(sendSwapInterval, 2, true));
	}

	private void drawPeerStatsBox(HTMLNode peerStatsInfobox, boolean advancedModeEnabled, int numberOfConnected, 
			int numberOfRoutingBackedOff, int numberOfTooNew, int numberOfTooOld, int numberOfDisconnected, 
			int numberOfNeverConnected, int numberOfDisabled, int numberOfBursting, int numberOfListening, 
			int numberOfListenOnly) {
		
		peerStatsInfobox.addChild("div", "class", "infobox-header", l10n("peerStatsTitle"));
		HTMLNode peerStatsContent = peerStatsInfobox.addChild("div", "class", "infobox-content");
		HTMLNode peerStatsList = peerStatsContent.addChild("ul");
		if (numberOfConnected > 0) {
			HTMLNode peerStatsConnectedListItem = peerStatsList.addChild("li").addChild("span");
			peerStatsConnectedListItem.addChild("span", new String[] { "class", "title", "style" }, 
					new String[] { "peer_connected", l10nDark("connected"), "border-bottom: 1px dotted; cursor: help;" }, l10nDark("connectedShort"));
			peerStatsConnectedListItem.addChild("span", ":\u00a0" + numberOfConnected);
		}
		if (numberOfRoutingBackedOff > 0) {
			HTMLNode peerStatsRoutingBackedOffListItem = peerStatsList.addChild("li").addChild("span");
			peerStatsRoutingBackedOffListItem.addChild("span", new String[] { "class", "title", "style" }, 
					new String[] { "peer_backed_off", l10nDark(advancedModeEnabled ? "backedOff" : "busy"), 
					"border-bottom: 1px dotted; cursor: help;" }, l10nDark((advancedModeEnabled ? "backedOff" : "busy")+"Short"));
			peerStatsRoutingBackedOffListItem.addChild("span", ":\u00a0" + numberOfRoutingBackedOff);
		}
		if (numberOfTooNew > 0) {
			HTMLNode peerStatsTooNewListItem = peerStatsList.addChild("li").addChild("span");
			peerStatsTooNewListItem.addChild("span", new String[] { "class", "title", "style" }, 
					new String[] { "peer_too_new", l10nDark("tooNew"), "border-bottom: 1px dotted; cursor: help;" }, l10nDark("tooNewShort"));
			peerStatsTooNewListItem.addChild("span", ":\u00a0" + numberOfTooNew);
		}
		if (numberOfTooOld > 0) {
			HTMLNode peerStatsTooOldListItem = peerStatsList.addChild("li").addChild("span");
			peerStatsTooOldListItem.addChild("span", new String[] { "class", "title", "style" }, 
					new String[] { "peer_too_old", l10nDark("tooOld"), "border-bottom: 1px dotted; cursor: help;" }, l10nDark("tooOldShort"));
			peerStatsTooOldListItem.addChild("span", ":\u00a0" + numberOfTooOld);
		}
		if (numberOfDisconnected > 0) {
			HTMLNode peerStatsDisconnectedListItem = peerStatsList.addChild("li").addChild("span");
			peerStatsDisconnectedListItem.addChild("span", new String[] { "class", "title", "style" }, 
					new String[] { "peer_disconnected", l10nDark("notConnected"), "border-bottom: 1px dotted; cursor: help;" }, l10nDark("notConnectedShort"));
			peerStatsDisconnectedListItem.addChild("span", ":\u00a0" + numberOfDisconnected);
		}
		if (numberOfNeverConnected > 0) {
			HTMLNode peerStatsNeverConnectedListItem = peerStatsList.addChild("li").addChild("span");
			peerStatsNeverConnectedListItem.addChild("span", new String[] { "class", "title", "style" },
					new String[] { "peer_never_connected", l10nDark("neverConnected"), "border-bottom: 1px dotted; cursor: help;" }, l10nDark("neverConnectedShort"));
			peerStatsNeverConnectedListItem.addChild("span", ":\u00a0" + numberOfNeverConnected);
		}
		if (numberOfDisabled > 0) {
			HTMLNode peerStatsDisabledListItem = peerStatsList.addChild("li").addChild("span");
			peerStatsDisabledListItem.addChild("span", new String[] { "class", "title", "style" }, 
					new String[] { "peer_disabled", l10nDark("disabled"), "border-bottom: 1px dotted; cursor: help;" }, l10nDark("disabledShort"));
			peerStatsDisabledListItem.addChild("span", ":\u00a0" + numberOfDisabled);
		}
		if (numberOfBursting > 0) {
			HTMLNode peerStatsBurstingListItem = peerStatsList.addChild("li").addChild("span");
			peerStatsBurstingListItem.addChild("span", new String[] { "class", "title", "style" }, 
					new String[] { "peer_bursting", l10nDark("bursting"), "border-bottom: 1px dotted; cursor: help;" }, l10nDark("burstingShort"));
			peerStatsBurstingListItem.addChild("span", ":\u00a0" + numberOfBursting);
		}
		if (numberOfListening > 0) {
			HTMLNode peerStatsListeningListItem = peerStatsList.addChild("li").addChild("span");
			peerStatsListeningListItem.addChild("span", new String[] { "class", "title", "style" }, 
					new String[] { "peer_listening", l10nDark("listening"), "border-bottom: 1px dotted; cursor: help;" }, l10nDark("listeningShort"));
			peerStatsListeningListItem.addChild("span", ":\u00a0" + numberOfListening);
		}
		if (numberOfListenOnly > 0) {
			HTMLNode peerStatsListenOnlyListItem = peerStatsList.addChild("li").addChild("span");
			peerStatsListenOnlyListItem.addChild("span", new String[] { "class", "title", "style" }, 
					new String[] { "peer_listen_only", l10nDark("listenOnly"), "border-bottom: 1px dotted; cursor: help;" }, l10nDark("listenOnlyShort"));
			peerStatsListenOnlyListItem.addChild("span", ":\u00a0" + numberOfListenOnly);
		}
		
	}

	private static String l10n(String key) {
		return L10n.getString("StatisticsToadlet."+key);
	}
	
	private static String l10nDark(String key) {
		return L10n.getString("DarknetConnectionsToadlet."+key);
	}

	private static String l10n(String key, String pattern, String value) {
		return L10n.getString("StatisticsToadlet."+key, new String[] { pattern }, new String[] { value });
	}
	
	private static String l10n(String key, String[] patterns, String[] values) {
		return L10n.getString("StatisticsToadlet."+key, patterns, values);
	}
	
	private void drawActivityBox(HTMLNode activityInfobox, boolean advancedModeEnabled) {
		
		activityInfobox.addChild("div", "class", "infobox-header", l10nDark("activityTitle"));
		HTMLNode activityInfoboxContent = activityInfobox.addChild("div", "class", "infobox-content");
		
		HTMLNode activityList = drawActivity(activityInfoboxContent, node);
		
		int numARKFetchers = node.getNumARKFetchers();

		if (advancedModeEnabled && activityList != null) {
			if (numARKFetchers > 0)
				activityList.addChild("li", "ARK\u00a0Fetch\u00a0Requests:\u00a0" + numARKFetchers);
			activityList.addChild("li", "FetcherByUSKSize:\u00a0" + node.clientCore.uskManager.getFetcherByUSKSize());
			activityList.addChild("li", "BackgroundFetcherByUSKSize:\u00a0" + node.clientCore.uskManager.getBackgroundFetcherByUSKSize());
			activityList.addChild("li", "temporaryBackgroundFetchersLRUSize:\u00a0" + node.clientCore.uskManager.getTemporaryBackgroundFetchersLRU());
		}
		
	}
	
	static void drawBandwidth(HTMLNode activityList, Node node, long nodeUptimeSeconds) {
		long[] total = IOStatisticCollector.getTotalIO();
		long total_output_rate = (total[0]) / nodeUptimeSeconds;
		long total_input_rate = (total[1]) / nodeUptimeSeconds;
		long totalPayload = node.getTotalPayloadSent();
		long total_payload_rate = totalPayload / nodeUptimeSeconds;
		int percent = (int) (100 * totalPayload / total[0]);
		activityList.addChild("li", l10n("totalOutput", new String[] { "total", "rate" }, new String[] { SizeUtil.formatSize(total[0], true), SizeUtil.formatSize(total_output_rate, true) } ));
		activityList.addChild("li", l10n("payloadOutput", new String[] { "total", "rate", "percent" }, new String[] { SizeUtil.formatSize(totalPayload, true), SizeUtil.formatSize(total_payload_rate, true), Integer.toString(percent) } ));
		activityList.addChild("li", l10n("totalInput", new String[] { "total", "rate" }, new String[] { SizeUtil.formatSize(total[1], true), SizeUtil.formatSize(total_input_rate, true) }));
		long[] rate = node.nodeStats.getNodeIOStats();
		long delta = (rate[5] - rate[2]) / 1000;
		if(delta > 0) {
			long output_rate = (rate[3] - rate[0]) / delta;
			long input_rate = (rate[4] - rate[1]) / delta;
			SubConfig nodeConfig = node.config.get("node");
			int outputBandwidthLimit = nodeConfig.getInt("outputBandwidthLimit");
			int inputBandwidthLimit = nodeConfig.getInt("inputBandwidthLimit");
			if(inputBandwidthLimit == -1) {
				inputBandwidthLimit = outputBandwidthLimit * 4;
			}
			activityList.addChild("li", l10n("outputRate", new String[] { "rate", "max" }, new String[] { SizeUtil.formatSize(output_rate, true), SizeUtil.formatSize(outputBandwidthLimit, true) }));
			activityList.addChild("li", l10n("inputRate", new String[] { "rate", "max" }, new String[] { SizeUtil.formatSize(input_rate, true), SizeUtil.formatSize(inputBandwidthLimit, true) }));
		}
	}

	static HTMLNode drawActivity(HTMLNode activityInfoboxContent, Node node) {
		int numInserts = node.getNumInsertSenders();
		int numCHKInserts = node.getNumCHKInserts();
		int numSSKInserts = node.getNumSSKInserts();
		int numRequests = node.getNumRequestSenders();
		int numCHKRequests = node.getNumCHKRequests();
		int numSSKRequests = node.getNumSSKRequests();
		int numTransferringRequests = node.getNumTransferringRequestSenders();
		int numTransferringRequestHandlers = node.getNumTransferringRequestHandlers();
		if ((numInserts == 0) && (numRequests == 0) && (numTransferringRequests == 0)) {
			activityInfoboxContent.addChild("#", l10n("noRequests"));
			return null;
		} else {
			HTMLNode activityList = activityInfoboxContent.addChild("ul");
			if (numInserts > 0) {
				activityList.addChild("li", L10n.getString("StatisticsToadlet.activityInserts", 
						new String[] { "totalSenders", "CHKhandlers", "SSKhandlers" } , 
						new String[] { Integer.toString(numInserts), Integer.toString(numCHKInserts), Integer.toString(numSSKInserts)}));
			}
			if (numRequests > 0) {
				activityList.addChild("li", L10n.getString("StatisticsToadlet.activityRequests", 
						new String[] { "totalSenders", "CHKhandlers", "SSKhandlers" } , 
						new String[] { Integer.toString(numRequests), Integer.toString(numCHKRequests), Integer.toString(numSSKRequests)}));
			}
			if (numTransferringRequests > 0 || numTransferringRequestHandlers > 0) {
				activityList.addChild("li", L10n.getString("StatisticsToadlet.transferringRequests", 
						new String[] { "senders", "receivers" }, new String[] { Integer.toString(numTransferringRequests), Integer.toString(numTransferringRequestHandlers)}));
			}
			return activityList;
		}
	}

	private void drawOverviewBox(HTMLNode overviewInfobox, long nodeUptimeSeconds, long now, double swaps, double noSwaps) {
		
		overviewInfobox.addChild("div", "class", "infobox-header", "Node status overview");
		HTMLNode overviewInfoboxContent = overviewInfobox.addChild("div", "class", "infobox-content");
		HTMLNode overviewList = overviewInfoboxContent.addChild("ul");
		/* node status values */
		int bwlimitDelayTime = (int) stats.getBwlimitDelayTime();
		int nodeAveragePingTime = (int) stats.getNodeAveragePingTime();
		int networkSizeEstimateSession = stats.getNetworkSizeEstimate(-1);
		int networkSizeEstimate24h = 0;
		int networkSizeEstimate48h = 0;
		double numberOfRemotePeerLocationsSeenInSwaps = (double)node.getNumberOfRemotePeerLocationsSeenInSwaps();

		if(nodeUptimeSeconds > (24*60*60)) {  // 24 hours
			networkSizeEstimate24h = stats.getNetworkSizeEstimate(now - (24*60*60*1000));  // 48 hours
		}
		if(nodeUptimeSeconds > (48*60*60)) {  // 48 hours
			networkSizeEstimate48h = stats.getNetworkSizeEstimate(now - (48*60*60*1000));  // 48 hours
		}
		double routingMissDistance =  stats.routingMissDistance.currentValue();
		double backedOffPercent =  stats.backedOffPercent.currentValue();
		String nodeUptimeString = TimeUtil.formatTime(nodeUptimeSeconds * 1000);  // *1000 to convert to milliseconds
		overviewList.addChild("li", "bwlimitDelayTime:\u00a0" + bwlimitDelayTime + "ms");
		overviewList.addChild("li", "nodeAveragePingTime:\u00a0" + nodeAveragePingTime + "ms");
		overviewList.addChild("li", "networkSizeEstimateSession:\u00a0" + networkSizeEstimateSession + "\u00a0nodes");
		if(nodeUptimeSeconds > (24*60*60)) {  // 24 hours
			overviewList.addChild("li", "networkSizeEstimate24h:\u00a0" + networkSizeEstimate24h + "\u00a0nodes");
		}
		if(nodeUptimeSeconds > (48*60*60)) {  // 48 hours
			overviewList.addChild("li", "networkSizeEstimate48h:\u00a0" + networkSizeEstimate48h + "\u00a0nodes");
		}
		if ((numberOfRemotePeerLocationsSeenInSwaps > 0.0) && ((swaps > 0.0) || (noSwaps > 0.0))) {
			overviewList.addChild("li", "avrConnPeersPerNode:\u00a0" + fix6p6.format(numberOfRemotePeerLocationsSeenInSwaps/(swaps+noSwaps)) + "\u00a0peers");
		}
		overviewList.addChild("li", "nodeUptime:\u00a0" + nodeUptimeString);
		overviewList.addChild("li", "routingMissDistance:\u00a0" + fix1p4.format(routingMissDistance));
		overviewList.addChild("li", "backedOffPercent:\u00a0" + fix3p1pct.format(backedOffPercent));
		overviewList.addChild("li", "pInstantReject:\u00a0" + fix3p1pct.format(stats.pRejectIncomingInstantly()));
		overviewList.addChild("li", "unclaimedFIFOSize:\u00a0" + node.getUnclaimedFIFOSize());
		
	}

	private void drawBandwidthBox(HTMLNode bandwidthInfobox, long nodeUptimeSeconds) {
		
		bandwidthInfobox.addChild("div", "class", "infobox-header", l10n("bandwidthTitle"));
		HTMLNode bandwidthInfoboxContent = bandwidthInfobox.addChild("div", "class", "infobox-content");
		HTMLNode bandwidthList = bandwidthInfoboxContent.addChild("ul");
		drawBandwidth(bandwidthList, node, nodeUptimeSeconds);
		
	}

	// FIXME this should probably be moved to nodestats so it can be used by FCP??? would have to make ThreadBunch public :<
	private void getThreadNames(HTMLNode threadUsageList) {
		int count = 0;
		Thread[] threads;
		while(true) {
			count = Math.max(stats.rootThreadGroup.activeCount(), count);
			threads = new Thread[count*2+50];
			stats.rootThreadGroup.enumerate(threads);
			if(threads[threads.length-1] == null) break;
		}
		LinkedHashMap map = new LinkedHashMap();
		int totalCount = 0;
		for(int i=0;i<threads.length;i++) {
			if(threads[i] == null) break;
			String name = threads[i].getName();
			if(name.indexOf(" for ") != -1)
				name = name.substring(0, name.indexOf(" for "));
			if(name.indexOf("@") != -1)
				name = name.substring(0, name.indexOf("@"));
			ThreadBunch bunch = (ThreadBunch) map.get(name);
			if(bunch != null) {
				bunch.count++;
			} else {
				map.put(name, new ThreadBunch(name, 1));
			}
			totalCount++;
		}
		ThreadBunch[] bunches = (ThreadBunch[]) map.values().toArray(new ThreadBunch[map.size()]);
		Arrays.sort(bunches, new Comparator() {

			public int compare(Object arg0, Object arg1) {
				ThreadBunch b0 = (ThreadBunch) arg0;
				ThreadBunch b1 = (ThreadBunch) arg1;
				if(b0.count > b1.count) return -1;
				if(b0.count < b1.count) return 1;
				return b0.name.compareTo(b1.name);
			}

		});
		double thisThreadPercentOfTotal;
		for(int i=0; i<bunches.length; i++) {
			thisThreadPercentOfTotal = ((double) bunches[i].count) / ((double) totalCount);
			threadUsageList.addChild("li", "" + bunches[i].name + ":\u00a0" + Integer.toString(bunches[i].count) + "\u00a0(" + fix3p1pct.format(thisThreadPercentOfTotal) + ')');
		}
	}

	class ThreadBunch {
		public ThreadBunch(String name2, int i) {
			this.name = name2;
			this.count = i;
		}
		String name;
		int count;
	}

	private final static int PEER_CIRCLE_RADIUS = 100;
	private final static int PEER_CIRCLE_INNER_RADIUS = 60;
	private final static int PEER_CIRCLE_ADDITIONAL_FREE_SPACE = 10;
	private final static long MAX_CIRCLE_AGE_THRESHOLD = 24l*60*60*1000;   // 24 hours
	private final static int HISTOGRAM_LENGTH = 10;

	private void addNodeCircle (HTMLNode circleTable) {
		int[] histogram = new int[HISTOGRAM_LENGTH];
		for (int i = 0; i < HISTOGRAM_LENGTH; i++) {
			histogram[i] = 0;
		}
		HTMLNode nodeCircleTableRow = circleTable.addChild("tr");
		HTMLNode nodeHistogramLegendTableRow = circleTable.addChild("tr");
		HTMLNode nodeHistogramGraphTableRow = circleTable.addChild("tr");
		HTMLNode nodeCircleTableCell = nodeCircleTableRow.addChild("td", new String[] { "class", "colspan" }, new String[] {"first", "10"});
		HTMLNode nodeHistogramLegendCell;
		HTMLNode nodeHistogramGraphCell;
		HTMLNode nodeCircleInfoboxContent = nodeCircleTableCell.addChild("div", new String[] { "style", "class" }, new String[] {"position: relative; height: " + ((PEER_CIRCLE_RADIUS + PEER_CIRCLE_ADDITIONAL_FREE_SPACE) * 2) + "px; width: " + ((PEER_CIRCLE_RADIUS + PEER_CIRCLE_ADDITIONAL_FREE_SPACE) * 2) + "px", "peercircle" });
		nodeCircleInfoboxContent.addChild("span", new String[] { "style", "class" }, new String[] { generatePeerCircleStyleString(0, false, 1.0),	 "mark" }, "|");
		nodeCircleInfoboxContent.addChild("span", new String[] { "style", "class" }, new String[] { generatePeerCircleStyleString(0.125, false, 1.0), "mark" }, "+");
		nodeCircleInfoboxContent.addChild("span", new String[] { "style", "class" }, new String[] { generatePeerCircleStyleString(0.25, false, 1.0),  "mark" }, "--");
		nodeCircleInfoboxContent.addChild("span", new String[] { "style", "class" }, new String[] { generatePeerCircleStyleString(0.375, false, 1.0), "mark" }, "+");
		nodeCircleInfoboxContent.addChild("span", new String[] { "style", "class" }, new String[] { generatePeerCircleStyleString(0.5, false, 1.0),   "mark" }, "|");
		nodeCircleInfoboxContent.addChild("span", new String[] { "style", "class" }, new String[] { generatePeerCircleStyleString(0.625, false, 1.0), "mark" }, "+");
		nodeCircleInfoboxContent.addChild("span", new String[] { "style", "class" }, new String[] { generatePeerCircleStyleString(0.75, false, 1.0),  "mark" }, "--");
		nodeCircleInfoboxContent.addChild("span", new String[] { "style", "class" }, new String[] { generatePeerCircleStyleString(0.875, false, 1.0), "mark" }, "+");
		nodeCircleInfoboxContent.addChild("span", new String[] { "style", "class" }, new String[] { generatePeerCircleStyleString(0.875, false, 1.0), "mark" }, "+");
		nodeCircleInfoboxContent.addChild("span", new String[] { "style", "class" }, new String[] { "position: absolute; top: " + PEER_CIRCLE_RADIUS + "px; left: " + (PEER_CIRCLE_RADIUS + PEER_CIRCLE_ADDITIONAL_FREE_SPACE) + "px", "mark" }, "+");
		final Object[] knownLocsCopy = stats.getKnownLocations(-1);
		final Double[] locations = (Double[])knownLocsCopy[0];
		final Long[] timestamps = (Long[])knownLocsCopy[1];
		Double location;
		Long locationTime;
		double strength = 1.0;
		long now = System.currentTimeMillis();
		long age = 1;
		int histogramIndex;
		int nodeCount = 0;
		for(int i=0; i<locations.length; i++){
			nodeCount += 1;
			location = locations[i];
			locationTime = timestamps[i];
			age = now - locationTime.longValue();
			if( age > MAX_CIRCLE_AGE_THRESHOLD ) {
				age = MAX_CIRCLE_AGE_THRESHOLD;
			}
			strength = 1 - ((double) age / MAX_CIRCLE_AGE_THRESHOLD );
			histogramIndex = (int) (Math.floor(location.doubleValue() * HISTOGRAM_LENGTH));
			histogram[histogramIndex]++;
			nodeCircleInfoboxContent.addChild("span", new String[] { "style", "class" }, new String[] { generatePeerCircleStyleString(location.doubleValue(), false, strength), "connected" }, "x");
		}
		nodeCircleInfoboxContent.addChild("span", new String[] { "style", "class" }, new String[] { generatePeerCircleStyleString(node.getLocation(), true, 1.0), "me" }, "x");
		//
		double histogramPercent;
		for (int i = 0; i < HISTOGRAM_LENGTH; i++) {
			nodeHistogramLegendCell = nodeHistogramLegendTableRow.addChild("td");
			nodeHistogramGraphCell = nodeHistogramGraphTableRow.addChild("td", "style", "height: 100px;");
			nodeHistogramLegendCell.addChild("div", "class", "histogramLabel").addChild("#", fix1p1.format(((double) i) / HISTOGRAM_LENGTH ));
			//
			histogramPercent = ((double) histogram[ i ] ) / nodeCount;
			nodeHistogramGraphCell.addChild("div", new String[] { "class", "style" }, new String[] { "histogramConnected", "height: " + fix3pctUS.format(histogramPercent) + "; width: 100%;" }, "\u00a0");
		}
	}

	private void addPeerCircle (HTMLNode circleTable) {
		int[] histogramConnected = new int[HISTOGRAM_LENGTH];
		int[] histogramDisconnected = new int[HISTOGRAM_LENGTH];
		for (int i = 0; i < HISTOGRAM_LENGTH; i++) {
			histogramConnected[i] = 0;
			histogramDisconnected[i] = 0;
		}
		HTMLNode peerCircleTableRow = circleTable.addChild("tr");
		HTMLNode peerHistogramLegendTableRow = circleTable.addChild("tr");
		HTMLNode peerHistogramGraphTableRow = circleTable.addChild("tr");
		HTMLNode peerCircleTableCell = peerCircleTableRow.addChild("td", new String[] { "class", "colspan" }, new String[] {"first", "10"});
		HTMLNode peerHistogramLegendCell;
		HTMLNode peerHistogramGraphCell;
		HTMLNode peerCircleInfoboxContent = peerCircleTableCell.addChild("div", new String[] { "style", "class" }, new String[] {"position: relative; height: " + ((PEER_CIRCLE_RADIUS + PEER_CIRCLE_ADDITIONAL_FREE_SPACE) * 2) + "px; width: " + ((PEER_CIRCLE_RADIUS + PEER_CIRCLE_ADDITIONAL_FREE_SPACE) * 2) + "px", "peercircle" });
		peerCircleInfoboxContent.addChild("span", new String[] { "style", "class" }, new String[] { generatePeerCircleStyleString(0, false, 1.0),	 "mark" }, "|");
		peerCircleInfoboxContent.addChild("span", new String[] { "style", "class" }, new String[] { generatePeerCircleStyleString(0.125, false, 1.0), "mark" }, "+");
		peerCircleInfoboxContent.addChild("span", new String[] { "style", "class" }, new String[] { generatePeerCircleStyleString(0.25, false, 1.0),  "mark" }, "--");
		peerCircleInfoboxContent.addChild("span", new String[] { "style", "class" }, new String[] { generatePeerCircleStyleString(0.375, false, 1.0), "mark" }, "+");
		peerCircleInfoboxContent.addChild("span", new String[] { "style", "class" }, new String[] { generatePeerCircleStyleString(0.5, false, 1.0),   "mark" }, "|");
		peerCircleInfoboxContent.addChild("span", new String[] { "style", "class" }, new String[] { generatePeerCircleStyleString(0.625, false, 1.0), "mark" }, "+");
		peerCircleInfoboxContent.addChild("span", new String[] { "style", "class" }, new String[] { generatePeerCircleStyleString(0.75, false, 1.0),  "mark" }, "--");
		peerCircleInfoboxContent.addChild("span", new String[] { "style", "class" }, new String[] { "position: absolute; top: " + PEER_CIRCLE_RADIUS + "px; left: " + (PEER_CIRCLE_RADIUS + PEER_CIRCLE_ADDITIONAL_FREE_SPACE) + "px", "mark" }, "+");
		//
		double myLocation = node.getLocation();
		PeerNodeStatus[] peerNodeStatuses = peers.getPeerNodeStatuses();
		PeerNodeStatus peerNodeStatus;
		double peerLocation;
		double peerDistance;
		int histogramIndex;
		int peerCount = peerNodeStatuses.length;
		for (int peerIndex = 0; peerIndex < peerCount; peerIndex++) {
			peerNodeStatus = peerNodeStatuses[peerIndex];
			peerLocation = peerNodeStatus.getLocation();
			peerDistance = Location.distance( myLocation, peerLocation );
			histogramIndex = (int) (Math.floor(peerDistance * HISTOGRAM_LENGTH * 2));
			if (peerNodeStatus.isConnected()) {
				histogramConnected[histogramIndex]++;
			} else {
				histogramDisconnected[histogramIndex]++;
			}
			peerCircleInfoboxContent.addChild("span", new String[] { "style", "class" }, new String[] { generatePeerCircleStyleString(peerLocation, false, (1.0 - peerNodeStatus.getPReject())), ((peerNodeStatus.isConnected())?"connected":"disconnected") }, ((peerNodeStatus.isOpennet())?"o":"x"));
		}
		peerCircleInfoboxContent.addChild("span", new String[] { "style", "class" }, new String[] { generatePeerCircleStyleString(myLocation, true, 1.0), "me" }, "x");
		//
		double histogramPercent;
		for (int i = 0; i < HISTOGRAM_LENGTH; i++) {
			peerHistogramLegendCell = peerHistogramLegendTableRow.addChild("td");
			peerHistogramGraphCell = peerHistogramGraphTableRow.addChild("td", "style", "height: 100px;");
			peerHistogramLegendCell.addChild("div", "class", "histogramLabel").addChild("#", fix1p2.format(((double) i) / ( HISTOGRAM_LENGTH * 2 )));
			//
			histogramPercent = ((double) histogramConnected[ i ] ) / peerCount;
			peerHistogramGraphCell.addChild("div", new String[] { "class", "style" }, new String[] { "histogramConnected", "height: " + fix3pctUS.format(histogramPercent) + "; width: 100%;" }, "\u00a0");
			//
			histogramPercent = ((double) histogramDisconnected[ i ] ) / peerCount;
			peerHistogramGraphCell.addChild("div", new String[] { "class", "style" }, new String[] { "histogramDisconnected", "height: " + fix3pctUS.format(histogramPercent) + "; width: 100%;" }, "\u00a0");
		}
	}

	private String generatePeerCircleStyleString (double peerLocation, boolean offsetMe, double strength) {
		peerLocation *= Math.PI * 2;
		//
		int offset = 0;
		if( offsetMe ) {
			// Make our own peer stand out from the crowd better so we can see it easier
			offset = -10;
		} else {
			offset = (int) (((double) PEER_CIRCLE_INNER_RADIUS) * (1.0 - strength));
		}
		double x = PEER_CIRCLE_ADDITIONAL_FREE_SPACE + PEER_CIRCLE_RADIUS + Math.sin(peerLocation) * (PEER_CIRCLE_RADIUS - offset);
		double y = PEER_CIRCLE_RADIUS - Math.cos(peerLocation) * (PEER_CIRCLE_RADIUS - offset);  // no PEER_CIRCLE_ADDITIONAL_FREE_SPACE for y-disposition
		//
		return "position: absolute; top: " + fix3p1US.format(y) + "px; left: " + fix3p1US.format(x) + "px";
	}
}
