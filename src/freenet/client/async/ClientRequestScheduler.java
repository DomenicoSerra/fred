/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;

import freenet.config.EnumerableOptionCallback;
import freenet.config.InvalidConfigValueException;
import freenet.config.SubConfig;
import freenet.crypt.RandomSource;
import freenet.keys.ClientKey;
import freenet.keys.ClientKeyBlock;
import freenet.keys.Key;
import freenet.keys.KeyBlock;
import freenet.keys.KeyVerifyException;
import freenet.node.LowLevelGetException;
import freenet.node.Node;
import freenet.node.RequestScheduler;
import freenet.node.RequestStarter;
import freenet.node.SendableGet;
import freenet.node.SendableInsert;
import freenet.node.SendableRequest;
import freenet.support.Logger;
import freenet.support.RandomGrabArray;
import freenet.support.SectoredRandomGrabArrayWithInt;
import freenet.support.SectoredRandomGrabArrayWithObject;
import freenet.support.SortedVectorByNumber;
import freenet.support.api.StringCallback;

/**
 * Every X seconds, the RequestSender calls the ClientRequestScheduler to
 * ask for a request to start. A request is then started, in its own 
 * thread. It is removed at that point.
 */
public class ClientRequestScheduler implements RequestScheduler {
	
	private static boolean logMINOR;
	
	public class PrioritySchedulerCallback implements StringCallback, EnumerableOptionCallback {
		final ClientRequestScheduler cs;
		private final String[] possibleValues = new String[]{ ClientRequestScheduler.PRIORITY_HARD, ClientRequestScheduler.PRIORITY_SOFT };
		
		PrioritySchedulerCallback(ClientRequestScheduler cs){
			this.cs = cs;
		}
		
		public String get(){
			if(cs != null)
				return cs.getChoosenPriorityScheduler();
			else
				return ClientRequestScheduler.PRIORITY_HARD;
		}
		
		public void set(String val) throws InvalidConfigValueException{
			String value;
			if(val == null || val.equalsIgnoreCase(get())) return;
			if(val.equalsIgnoreCase(ClientRequestScheduler.PRIORITY_HARD)){
				value = ClientRequestScheduler.PRIORITY_HARD;
			}else if(val.equalsIgnoreCase(ClientRequestScheduler.PRIORITY_SOFT)){
				value = ClientRequestScheduler.PRIORITY_SOFT;
			}else{
				throw new InvalidConfigValueException("Invalid priority scheme");
			}
			cs.setPriorityScheduler(value);
		}
		
		public String[] getPossibleValues() {
			return possibleValues;
		}
		
		public void setPossibleValues(String[] val) {
			throw new NullPointerException("Should not happen!");
		}
	}
	
	/**
	 * Structure:
	 * array (by priority) -> // one element per possible priority
	 * SortedVectorByNumber (by # retries) -> // contains each current #retries
	 * RandomGrabArray // contains each element, allows fast fetch-and-drop-a-random-element
	 * 
	 * To speed up fetching, a RGA or SVBN must only exist if it is non-empty.
	 */
	private final SortedVectorByNumber[] priorities;
	// we have one for inserts and one for requests
	final boolean isInsertScheduler;
	final boolean isSSKScheduler;
	final RandomSource random;
	private final HashMap allRequestsByClientRequest;
	private final RequestStarter starter;
	private final Node node;
	public final String name;
	private final LinkedList /* <WeakReference <RandomGrabArray> > */ recentSuccesses = new LinkedList();
	
	/** All pending gets by key. Used to automatically satisfy pending requests when either the key is fetched by
	 * an overlapping request, or it is fetched by a request from another node. Operations on this are synchronized on
	 * itself. */
	private final HashMap /* <Key, SendableGet[]> */ pendingKeys;
	
	public static final String PRIORITY_NONE = "NONE";
	public static final String PRIORITY_SOFT = "SOFT";
	public static final String PRIORITY_HARD = "HARD";
	/** Minimum number of retries at which we start to hold it against a request.
	 * See the comments on fixRetryCount; we don't want many untried requests to prevent
	 * us from trying requests which have only been tried once (e.g. USK checkers), from 
	 * other clients (and we DO want retries to take precedence over client round robin IF 
	 * the request has been tried many times already). */
	private static final int MIN_RETRY_COUNT = 3;
	private String choosenPriorityScheduler; 
	
	private final short[] tweakedPrioritySelector = { 
			RequestStarter.MAXIMUM_PRIORITY_CLASS,
			RequestStarter.MAXIMUM_PRIORITY_CLASS,
			RequestStarter.MAXIMUM_PRIORITY_CLASS,
			RequestStarter.MAXIMUM_PRIORITY_CLASS,
			RequestStarter.MAXIMUM_PRIORITY_CLASS,
			RequestStarter.MAXIMUM_PRIORITY_CLASS,
			RequestStarter.MAXIMUM_PRIORITY_CLASS,
			
			RequestStarter.INTERACTIVE_PRIORITY_CLASS,
			RequestStarter.INTERACTIVE_PRIORITY_CLASS,
			RequestStarter.INTERACTIVE_PRIORITY_CLASS,
			RequestStarter.INTERACTIVE_PRIORITY_CLASS,
			RequestStarter.INTERACTIVE_PRIORITY_CLASS,
			RequestStarter.INTERACTIVE_PRIORITY_CLASS,
			
			RequestStarter.IMMEDIATE_SPLITFILE_PRIORITY_CLASS,
			RequestStarter.IMMEDIATE_SPLITFILE_PRIORITY_CLASS,
			RequestStarter.IMMEDIATE_SPLITFILE_PRIORITY_CLASS, 
			RequestStarter.IMMEDIATE_SPLITFILE_PRIORITY_CLASS, 
			RequestStarter.IMMEDIATE_SPLITFILE_PRIORITY_CLASS,
			
			RequestStarter.UPDATE_PRIORITY_CLASS,
			RequestStarter.UPDATE_PRIORITY_CLASS, 
			RequestStarter.UPDATE_PRIORITY_CLASS, 
			RequestStarter.UPDATE_PRIORITY_CLASS,
			
			RequestStarter.BULK_SPLITFILE_PRIORITY_CLASS, 
			RequestStarter.BULK_SPLITFILE_PRIORITY_CLASS, 
			RequestStarter.BULK_SPLITFILE_PRIORITY_CLASS,
			
			RequestStarter.PREFETCH_PRIORITY_CLASS, 
			RequestStarter.PREFETCH_PRIORITY_CLASS,
			
			RequestStarter.MINIMUM_PRIORITY_CLASS
	};
	private final short[] prioritySelector = {
			RequestStarter.MAXIMUM_PRIORITY_CLASS,
			RequestStarter.INTERACTIVE_PRIORITY_CLASS,
			RequestStarter.IMMEDIATE_SPLITFILE_PRIORITY_CLASS, 
			RequestStarter.UPDATE_PRIORITY_CLASS,
			RequestStarter.BULK_SPLITFILE_PRIORITY_CLASS,
			RequestStarter.PREFETCH_PRIORITY_CLASS,
			RequestStarter.MINIMUM_PRIORITY_CLASS
	};
	
	public ClientRequestScheduler(boolean forInserts, boolean forSSKs, RandomSource random, RequestStarter starter, Node node, SubConfig sc, String name) {
		this.starter = starter;
		this.random = random;
		this.node = node;
		this.isInsertScheduler = forInserts;
		this.isSSKScheduler = forSSKs;
		priorities = new SortedVectorByNumber[RequestStarter.NUMBER_OF_PRIORITY_CLASSES];
		allRequestsByClientRequest = new HashMap();
		if(forInserts)
			pendingKeys = null;
		else
			pendingKeys = new HashMap();
		
		this.name = name;
		sc.register(name+"_priority_policy", PRIORITY_HARD, name.hashCode(), true, false,
				"RequestStarterGroup.scheduler",
				"RequestStarterGroup.schedulerLong",
				new PrioritySchedulerCallback(this));
		
		this.choosenPriorityScheduler = sc.getString(name+"_priority_policy");
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
	}
	
	/** Called by the  config. Callback
	 * 
	 * @param val
	 */
	protected synchronized void setPriorityScheduler(String val){
		choosenPriorityScheduler = val;
	}
	
	public void register(SendableRequest req) {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(logMINOR) Logger.minor(this, "Registering "+req, new Exception("debug"));
		if(isInsertScheduler != (req instanceof SendableInsert))
			throw new IllegalArgumentException("Expected a SendableGet: "+req);
		if(req instanceof SendableGet) {
			SendableGet getter = (SendableGet)req;
			if(!getter.ignoreStore()) {
				boolean anyValid = false;
				int[] keyTokens = getter.allKeys();
				for(int i=0;i<keyTokens.length;i++) {
					int tok = keyTokens[i];
					ClientKeyBlock block = null;
					try {
						ClientKey key = getter.getKey(tok);
						if(key == null) {
							if(logMINOR)
								Logger.minor(this, "No key for "+tok+" for "+getter+" - already finished?");
							continue;
						} else {
							if(getter.getContext().blocks != null)
								block = getter.getContext().blocks.get(key);
							if(block == null)
								block = node.fetchKey(key, getter.dontCache());
							if(block == null) {
								addPendingKey(key, getter);
							}
						}
					} catch (KeyVerifyException e) {
						// Verify exception, probably bogus at source;
						// verifies at low-level, but not at decode.
						if(logMINOR)
							Logger.minor(this, "Decode failed: "+e, e);
						getter.onFailure(new LowLevelGetException(LowLevelGetException.DECODE_FAILED), tok);
						return;
					}
					if(block != null) {
						if(logMINOR) Logger.minor(this, "Can fulfill "+req+" ("+tok+") immediately from store");
						getter.onSuccess(block, true, tok);
					} else {
						anyValid = true;
					}
				}
				if(!anyValid) return;
			}
		}
		innerRegister(req);
		synchronized(starter) {
			starter.notifyAll();
		}
	}
	
	private void addPendingKey(ClientKey key, SendableGet getter) {
		Key nodeKey = key.getNodeKey();
		synchronized(pendingKeys) {
			Object o = pendingKeys.get(nodeKey);
			if(o == null) {
				pendingKeys.put(nodeKey, getter);
			} else if(o instanceof SendableGet) {
				SendableGet oldGet = (SendableGet) o;
				if(oldGet != getter) {
					pendingKeys.put(nodeKey, new SendableGet[] { oldGet, getter });
				}
			} else {
				SendableGet[] gets = (SendableGet[]) o;
				boolean found = false;
				for(int j=0;j<gets.length;j++) {
					if(gets[j] == getter) {
						found = true;
						break;
					}
				}
				if(!found) {
					SendableGet[] newGets = new SendableGet[gets.length+1];
					System.arraycopy(gets, 0, newGets, 0, gets.length);
					newGets[gets.length] = getter;
					pendingKeys.put(nodeKey, newGets);
				}
			}
		}
	}

	private synchronized void innerRegister(SendableRequest req) {
		if(logMINOR) Logger.minor(this, "Still registering "+req+" at prio "+req.getPriorityClass()+" retry "+req.getRetryCount());
		addToGrabArray(req.getPriorityClass(), req.getRetryCount(), req.getClient(), req.getClientRequest(), req);
		HashSet v = (HashSet) allRequestsByClientRequest.get(req.getClientRequest());
		if(v == null) {
			v = new HashSet();
			allRequestsByClientRequest.put(req.getClientRequest(), v);
		}
		v.add(req);
		if(logMINOR) Logger.minor(this, "Registered "+req+" on prioclass="+req.getPriorityClass()+", retrycount="+req.getRetryCount());
	}
	
	private synchronized void addToGrabArray(short priorityClass, int retryCount, Object client, ClientRequester cr, SendableRequest req) {
		if((priorityClass > RequestStarter.MINIMUM_PRIORITY_CLASS) || (priorityClass < RequestStarter.MAXIMUM_PRIORITY_CLASS))
			throw new IllegalStateException("Invalid priority: "+priorityClass+" - range is "+RequestStarter.MAXIMUM_PRIORITY_CLASS+" (most important) to "+RequestStarter.MINIMUM_PRIORITY_CLASS+" (least important)");
		// Priority
		SortedVectorByNumber prio = priorities[priorityClass];
		if(prio == null) {
			prio = new SortedVectorByNumber();
			priorities[priorityClass] = prio;
		}
		// Client
		int rc = fixRetryCount(retryCount);
		SectoredRandomGrabArrayWithInt clientGrabber = (SectoredRandomGrabArrayWithInt) prio.get(rc);
		if(clientGrabber == null) {
			clientGrabber = new SectoredRandomGrabArrayWithInt(random, rc);
			prio.add(clientGrabber);
			if(logMINOR) Logger.minor(this, "Registering retry count "+rc+" with prioclass "+priorityClass);
		}
		// Request
		SectoredRandomGrabArrayWithObject requestGrabber = (SectoredRandomGrabArrayWithObject) clientGrabber.getGrabber(client);
		if(requestGrabber == null) {
			requestGrabber = new SectoredRandomGrabArrayWithObject(client, random);
			clientGrabber.addGrabber(client, requestGrabber);
		}
		requestGrabber.add(cr, req);
	}

	/**
	 * Mangle the retry count.
	 * Below a certain number of attempts, we don't prefer one request to another just because
	 * it's been tried more times. The reason for this is to prevent floods of low-retry-count
	 * requests from starving other clients' requests which need to be retried. The other
	 * solution would be to sort by client before retry count, but that would be excessive 
	 * IMHO; we DO want to avoid rerequesting keys we've tried many times before.
	 */
	private int fixRetryCount(int retryCount) {
		return Math.max(0, retryCount-MIN_RETRY_COUNT);
	}

	private int removeFirstAccordingToPriorities(){
		SortedVectorByNumber result = null;
		
		short fuzz = -1, iteration = 0, priority;
		synchronized (this) {
			if(PRIORITY_SOFT.equals(choosenPriorityScheduler))
				fuzz = -1;
			else if(PRIORITY_HARD.equals(choosenPriorityScheduler))
				fuzz = 0;	
		}
		// we loop to ensure we try every possibilities ( n + 1)
		//
		// PRIO will do 0,1,2,3,4,5,6,0
		// TWEAKED will do rand%6,0,1,2,3,4,5,6
		while(iteration++ < RequestStarter.NUMBER_OF_PRIORITY_CLASSES + 1){
			priority = fuzz<0 ? tweakedPrioritySelector[random.nextInt(tweakedPrioritySelector.length)] : prioritySelector[Math.abs(fuzz % prioritySelector.length)];
			result = priorities[priority];
			if((result != null) && !result.isEmpty()) {
				if(logMINOR) Logger.minor(this, "using priority : "+priority);
				return priority;
			}
			
			if(logMINOR) Logger.debug(this, "Priority "+priority+" is null (fuzz = "+fuzz+ ')');
			fuzz++;
		}
		
		//FIXME: implement NONE
		return -1;
	}
	
	public SendableRequest removeFirst() {
		// Priorities start at 0
		if(logMINOR) Logger.minor(this, "removeFirst()");
		int choosenPriorityClass = removeFirstAccordingToPriorities();
		if(choosenPriorityClass == -1) {
			if(logMINOR)
				Logger.minor(this, "Nothing to do");
			return null;
		}
		SortedVectorByNumber s = priorities[choosenPriorityClass];
		if(s != null){
			while(true) {
				SectoredRandomGrabArrayWithInt rga = (SectoredRandomGrabArrayWithInt) s.getFirst();
				if(rga == null) {
					if(logMINOR) Logger.minor(this, "No retrycount's left");
					break;
				}
				if(logMINOR)
					Logger.minor(this, "Got retry count tracker "+rga);
				SendableRequest req = (SendableRequest) rga.removeRandom();
				if(rga.isEmpty()) {
					if(logMINOR) Logger.minor(this, "Removing retrycount "+rga.getNumber());
					s.remove(rga.getNumber());
					if(s.isEmpty()) {
						if(logMINOR) Logger.minor(this, "Should remove priority ");
					}
				}
				if(req == null) {
					if(logMINOR) Logger.minor(this, "No requests, adjusted retrycount "+rga.getNumber()+" ("+rga+ ')');
					break;
				} else if(req.getPriorityClass() != choosenPriorityClass) {
					// Reinsert it : shouldn't happen if we are calling reregisterAll,
					// maybe we should ask people to report that error if seen
					Logger.normal(this, "In wrong priority class: "+req+" (req.prio="+req.getPriorityClass()+" but chosen="+choosenPriorityClass+ ')');
					innerRegister(req);
					continue;
				}
				
				RandomGrabArray altRGA = null;
				synchronized(this) {
					if(!recentSuccesses.isEmpty()) {
						if(random.nextBoolean()) {
							WeakReference ref = (WeakReference) (recentSuccesses.removeLast());
							altRGA = (RandomGrabArray) ref.get();
						}
					}
				}
				if(altRGA != null) {
					SendableRequest altReq = (SendableRequest) (altRGA.removeRandom());
					if(altReq != null && altReq.getPriorityClass() <= choosenPriorityClass && 
							fixRetryCount(altReq.getRetryCount()) <= rga.getNumber()) {
						// Use the recent one instead
						if(logMINOR)
							Logger.minor(this, "Recently succeeded req "+altReq+" is better, using that, reregistering chosen "+req);
						innerRegister(req);
						req = altReq;
					} else {
						if(altReq != null) {
							synchronized(this) {
								recentSuccesses.addLast(new WeakReference(altRGA));
							}
							if(logMINOR)
								Logger.minor(this, "Chosen req "+req+" is better, reregistering recently succeeded "+altReq);
							innerRegister(altReq);
						}
					}
				}
				
				if(logMINOR) Logger.debug(this, "removeFirst() returning "+req+" ("+rga.getNumber()+", prio "+
						req.getPriorityClass()+", retries "+req.getRetryCount()+", client "+req.getClient()+", client-req "+req.getClientRequest()+ ')');
				ClientRequester cr = req.getClientRequest();
				if(req.canRemove()) {
					HashSet v = (HashSet) allRequestsByClientRequest.get(cr);
					if(v == null) {
						Logger.error(this, "No HashSet registered for "+cr);
					} else {
						v.remove(req);
						if(v.isEmpty())
							allRequestsByClientRequest.remove(cr);
						if(logMINOR) Logger.minor(this, "Removed from HashSet for "+cr+" which now has "+v.size()+" elements");
					}
					if(!isInsertScheduler) {
						removePendingKeys((SendableGet) req, true);
					}
				}
				if(logMINOR) Logger.minor(this, "removeFirst() returning "+req);
				return req;
			}
		}
		if(logMINOR) Logger.minor(this, "No requests to run");
		return null;
	}
	
	public void removePendingKey(SendableGet getter, boolean complain, Key key) {
		synchronized(pendingKeys) {
			Object o = pendingKeys.get(key);
			if(o == null) {
				if(complain)
					Logger.normal(this, "Not found: "+getter+" for "+key+" removing (no such key)");
			} else if(o instanceof SendableGet) {
				SendableGet oldGet = (SendableGet) o;
				if(oldGet != getter) {
					if(complain)
						Logger.normal(this, "Not found: "+getter+" for "+key+" removing (1 getter)");
				} else {
					pendingKeys.remove(key);
				}
			} else {
				SendableGet[] gets = (SendableGet[]) o;
				SendableGet[] newGets = new SendableGet[gets.length-1];
				boolean found = false;
				int x = 0;
				for(int j=0;j<gets.length;j++) {
					if(j > newGets.length) {
						if(!found) {
							if(complain)
								Logger.normal(this, "Not found: "+getter+" for "+key+" removing ("+gets.length+" getters)");
							return; // not here
						}
						if(gets[j] == getter || gets[j] == null || gets[j].isCancelled()) continue;
						newGets[x++] = gets[j];
					}
				}
				if(x != gets.length-1) {
					SendableGet[] newNewGets = new SendableGet[x];
					System.arraycopy(newGets, 0, newNewGets, 0, x);
					newGets = newNewGets;
				}
				if(newGets.length == 0) {
					pendingKeys.remove(key);
				} else if(newGets.length == 1) {
					pendingKeys.put(key, newGets[0]);
				} else {
					pendingKeys.put(key, newGets);
				}
			}
		}
	}
	
	/**
	 * Remove a SendableGet from the list of getters we maintain for each key, indicating that we are no longer interested
	 * in that key.
	 * @param getter
	 * @param complain
	 */
	public void removePendingKeys(SendableGet getter, boolean complain) {
		int[] keyTokens = getter.allKeys();
		for(int i=0;i<keyTokens.length;i++) {
			int tok = keyTokens[i];
			ClientKey ckey = getter.getKey(tok);
			if(ckey == null) {
				Logger.error(this, "Key "+tok+" is null for "+getter);
				continue;
			}
			removePendingKey(getter, complain, ckey.getNodeKey());
		}
	}

	public void reregisterAll(ClientRequester request) {
		SendableRequest[] reqs;
		synchronized(this) {
			HashSet h = (HashSet) allRequestsByClientRequest.get(request);
			if(h == null) return;
			reqs = (SendableRequest[]) h.toArray(new SendableRequest[h.size()]);
		}
		
		for(int i=0;i<reqs.length;i++) {
			SendableRequest req = reqs[i];
			req.unregister();
			innerRegister(req);
		}
		synchronized(starter) {
			starter.notifyAll();
		}
	}

	public String getChoosenPriorityScheduler() {
		return choosenPriorityScheduler;
	}

	public void succeeded(RandomGrabArray parentGrabArray) {
		synchronized(this) {
			if(logMINOR)
				Logger.minor(this, "Recording successful fetch from "+parentGrabArray);
			recentSuccesses.addFirst(new WeakReference(parentGrabArray));
			while(recentSuccesses.size() > 8)
				recentSuccesses.removeLast();
		}
	}

	public void tripPendingKey(final KeyBlock block) {
		final Key key = block.getKey();
		final SendableGet[] gets;
		Object o;
		synchronized(pendingKeys) {
			o = pendingKeys.get(key);
		}
		if(o == null) return;
		if(o instanceof SendableGet) {
			gets = new SendableGet[] { (SendableGet) o };
		} else {
			gets = (SendableGet[]) o;
		}
		if(gets == null) return;
		Runnable r = new Runnable() {
			public void run() {
				for(int i=0;i<gets.length;i++) {
					gets[i].onGotKey(key, block);
				}
			}
		};
		node.getTicker().queueTimedJob(r, 0); // FIXME ideally these would be completed on a single thread; when we have 1.5, use a dedicated non-parallel Executor
	}

	public boolean anyWantKey(Key key) {
		synchronized(pendingKeys) {
			return pendingKeys.get(key) != null;
		}
	}
}
