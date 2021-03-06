/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import freenet.crypt.DSAPublicKey;
import freenet.io.comm.ByteCounter;
import freenet.io.comm.DMT;
import freenet.io.comm.DisconnectedException;
import freenet.io.comm.Message;
import freenet.io.comm.MessageFilter;
import freenet.io.comm.NotConnectedException;
import freenet.io.comm.PeerParseException;
import freenet.io.comm.ReferenceSignatureVerificationException;
import freenet.io.xfer.BlockTransmitter;
import freenet.io.xfer.PartiallyReceivedBlock;
import freenet.keys.CHKBlock;
import freenet.keys.Key;
import freenet.keys.KeyBlock;
import freenet.keys.NodeCHK;
import freenet.keys.NodeSSK;
import freenet.keys.SSKBlock;
import freenet.support.Logger;
import freenet.support.ShortBuffer;
import freenet.support.SimpleFieldSet;

/**
 * Handle an incoming request. Does not do the actual fetching; that
 * is separated off into RequestSender so we get transfer coalescing
 * and both ends for free. 
 */
public class RequestHandler implements Runnable, ByteCounter {

	private static boolean logMINOR;
    final Message req;
    final Node node;
    final long uid;
    private short htl;
    final PeerNode source;
    private double closestLoc;
    private boolean needsPubKey;
    final Key key;
    private boolean finalTransferFailed = false;
    final boolean resetClosestLoc;
    /** The RequestSender, if any */
    private RequestSender rs;
    private int status;
    
    public String toString() {
        return super.toString()+" for "+uid;
    }
    
    public RequestHandler(Message m, long id, Node n) {
        req = m;
        node = n;
        uid = id;
        htl = req.getShort(DMT.HTL);
        source = (PeerNode) req.getSource();
        closestLoc = req.getDouble(DMT.NEAREST_LOCATION);
        double myLoc = n.lm.getLocation();
        key = (Key) req.getObject(DMT.FREENET_ROUTING_KEY);
        double keyLoc = key.toNormalizedDouble();
        if(Location.distance(keyLoc, myLoc) < Location.distance(keyLoc, closestLoc)) {
            closestLoc = myLoc;
            htl = node.maxHTL();
            resetClosestLoc = true;
        } else
        	resetClosestLoc = false;
        if(key instanceof NodeSSK)
        	needsPubKey = m.getBoolean(DMT.NEED_PUB_KEY);
        logMINOR = Logger.shouldLog(Logger.MINOR, this);
        receivedBytes(m.receivedByteCount());
    }

    public void run() {
    	boolean thrown = false;
        try {
        	realRun();
        } catch (NotConnectedException e) {
        	// Ignore, normal
        } catch (Throwable t) {
            Logger.error(this, "Caught "+t, t);
            thrown = true;
        } finally {
        	node.removeTransferringRequestHandler(uid);
            node.unlockUID(uid, key instanceof NodeSSK, false, false);
            if((!finalTransferFailed) && rs != null && status != RequestSender.TIMED_OUT && status != RequestSender.GENERATED_REJECTED_OVERLOAD 
            		&& status != RequestSender.INTERNAL_ERROR && !thrown) {
            	int sent, rcvd;
            	synchronized(this) {
            		sent = sentBytes;
            		rcvd = receivedBytes;
            	}
            	sent += rs.getTotalSentBytes();
            	rcvd += rs.getTotalReceivedBytes();
            	if(key instanceof NodeSSK) {
            		if(logMINOR) Logger.minor(this, "Remote SSK fetch cost "+sent+ '/' +rcvd+" bytes ("+status+ ')');
                	node.nodeStats.remoteSskFetchBytesSentAverage.report(sent);
                	node.nodeStats.remoteSskFetchBytesReceivedAverage.report(rcvd);
                	if(status == RequestSender.SUCCESS) {
                		// Can report both parts, because we had both a Handler and a Sender
                		node.nodeStats.successfulSskFetchBytesSentAverage.report(sent);
                		node.nodeStats.successfulSskFetchBytesReceivedAverage.report(rcvd);
                	}
            	} else {
            		if(logMINOR) Logger.minor(this, "Remote CHK fetch cost "+sent+ '/' +rcvd+" bytes ("+status+ ')');
                	node.nodeStats.remoteChkFetchBytesSentAverage.report(sent);
                	node.nodeStats.remoteChkFetchBytesReceivedAverage.report(rcvd);
                	if(status == RequestSender.SUCCESS) {
                		// Can report both parts, because we had both a Handler and a Sender
                		node.nodeStats.successfulChkFetchBytesSentAverage.report(sent);
                		node.nodeStats.successfulChkFetchBytesReceivedAverage.report(rcvd);
                	}
            	}
            }

        }
    }

    private void realRun() throws NotConnectedException {
        if(logMINOR) Logger.minor(this, "Handling a request: "+uid);
        if(!resetClosestLoc)
        	htl = source.decrementHTL(htl);
        
        Message accepted = DMT.createFNPAccepted(uid);
        source.sendSync(accepted, null);
        
        Object o = node.makeRequestSender(key, htl, uid, source, closestLoc, resetClosestLoc, false, true, false);
        if(o instanceof KeyBlock) {
            KeyBlock block = (KeyBlock) o;
            Message df = createDataFound(block);
            source.sendSync(df, null);
            if(key instanceof NodeSSK) {
                if(needsPubKey) {
                	DSAPublicKey key = ((NodeSSK)block.getKey()).getPubKey();
                	Message pk = DMT.createFNPSSKPubKey(uid, key);
                	if(logMINOR) Logger.minor(this, "Sending PK: "+key+ ' ' +key.toLongString());
                	source.sendSync(pk, null);
                }
                status = RequestSender.SUCCESS; // for byte logging
            }
            if(block instanceof CHKBlock) {
            	PartiallyReceivedBlock prb =
            		new PartiallyReceivedBlock(Node.PACKETS_IN_BLOCK, Node.PACKET_SIZE, block.getRawData());
            	BlockTransmitter bt =
            		new BlockTransmitter(node.usm, source, uid, prb, node.outputThrottle, this);
            	node.addTransferringRequestHandler(uid);
            	if(bt.send(node.executor)) {
            		status = RequestSender.SUCCESS; // for byte logging
            		if(source.isOpennet()) {
            			finishOpennetNoRelay();
            		}
            	}
            }
            return;
        }
        rs = (RequestSender) o;
        
        if(rs == null) { // ran out of htl?
            Message dnf = DMT.createFNPDataNotFound(uid);
            source.sendSync(dnf, null);
            status = RequestSender.DATA_NOT_FOUND; // for byte logging
            return;
        }
        
        boolean shouldHaveStartedTransfer = false;
        
        short waitStatus = 0;
        
        while(true) {
            
        	waitStatus = rs.waitUntilStatusChange(waitStatus);
            if((waitStatus & RequestSender.WAIT_REJECTED_OVERLOAD) != 0) {
            	// Forward RejectedOverload
            	Message msg = DMT.createFNPRejectedOverload(uid, false);
            	source.sendAsync(msg, null, 0, null);
            }
            
            if((waitStatus & RequestSender.WAIT_TRANSFERRING_DATA) != 0) {
            	// Is a CHK.
                Message df = DMT.createFNPCHKDataFound(uid, rs.getHeaders());
                source.sendSync(df, null);
                PartiallyReceivedBlock prb = rs.getPRB();
            	BlockTransmitter bt =
            	    new BlockTransmitter(node.usm, source, uid, prb, node.outputThrottle, this);
            	node.addTransferringRequestHandler(uid);
            	if(!bt.send(node.executor)){
            		finalTransferFailed = true;
            	} else {
    				// Successful CHK transfer, maybe path fold
    				finishOpennet();
            	}
				status = rs.getStatus();
        	    return;
            }
            
            status = rs.getStatus();

            if(status == RequestSender.NOT_FINISHED) continue;
            
            switch(status) {
            	case RequestSender.NOT_FINISHED:
            	case RequestSender.DATA_NOT_FOUND:
                    Message dnf = DMT.createFNPDataNotFound(uid);
            		source.sendSync(dnf, this);
            		return;
            	case RequestSender.RECENTLY_FAILED:
            		Message rf = DMT.createFNPRecentlyFailed(uid, rs.getRecentlyFailedTimeLeft());
            		source.sendSync(rf, this);
            		return;
            	case RequestSender.GENERATED_REJECTED_OVERLOAD:
            	case RequestSender.TIMED_OUT:
            	case RequestSender.INTERNAL_ERROR:
            		// Locally generated.
            	    // Propagate back to source who needs to reduce send rate
            	    Message reject = DMT.createFNPRejectedOverload(uid, true);
            		source.sendSync(reject, this);
            		return;
            	case RequestSender.ROUTE_NOT_FOUND:
            	    // Tell source
            	    Message rnf = DMT.createFNPRouteNotFound(uid, rs.getHTL());
            		source.sendSync(rnf, this);
            		return;
            	case RequestSender.SUCCESS:
            		if(key instanceof NodeSSK) {
                        Message df = DMT.createFNPSSKDataFound(uid, rs.getHeaders(), rs.getSSKData());
                        source.sendSync(df, this);
                        node.sentPayload(rs.getSSKData().length); // won't be sentPayload()ed by BlockTransmitter
                        if(needsPubKey) {
                        	Message pk = DMT.createFNPSSKPubKey(uid, ((NodeSSK)rs.getSSKBlock().getKey()).getPubKey());
                        	source.sendSync(pk, this);
                        }
                		return;
            		} else {
            			if(!rs.transferStarted()) {
            				Logger.error(this, "Status is SUCCESS but we never started a transfer on "+uid);
            			}
            			// Wait for transfer to start
            		}
            	case RequestSender.VERIFY_FAILURE:
            		if(key instanceof NodeCHK) {
            			if(shouldHaveStartedTransfer)
            				throw new IllegalStateException("Got status code "+status+" but transfer not started");
            			shouldHaveStartedTransfer = true;
            			continue; // should have started transfer
            		}
            	    reject = DMT.createFNPRejectedOverload(uid, true);
            		source.sendSync(reject, this);
            		return;
            	case RequestSender.TRANSFER_FAILED:
            		if(key instanceof NodeCHK) {
            			if(shouldHaveStartedTransfer)
            				throw new IllegalStateException("Got status code "+status+" but transfer not started");
            			shouldHaveStartedTransfer = true;
            			continue; // should have started transfer
            		}
            		// Other side knows, right?
            		return;
            	default:
            	    throw new IllegalStateException("Unknown status code "+status);
            }
        }
	}

	private void finishOpennet() {
		if(!(node.passOpennetRefsThroughDarknet() || source.isOpennet())) return;
		byte[] noderef = rs.waitForOpennetNoderef();
		if(noderef == null) {
			finishOpennetNoRelay();
			return;
		}
		
		if(node.random.nextInt(OpennetManager.RESET_PATH_FOLDING_PROB) == 0) {
			finishOpennetNoRelay();
			return;
		}
		
    	finishOpennetRelay(noderef);
    }
    
    private void finishOpennetNoRelay() {
    	if(logMINOR)
    		Logger.minor(this, "Finishing opennet: sending own reference");
		OpennetManager om = node.getOpennet();
		if(om != null) {
			if(om.wantPeer(null, false)) {
    			Message msg = DMT.createFNPOpennetConnectDestination(uid, new ShortBuffer(om.crypto.myCompressedFullRef()));
				try {
					source.sendAsync(msg, null, 0, this);
				} catch (NotConnectedException e) {
					Logger.normal(this, "Can't send opennet ref because node disconnected on "+this);
					// Oh well...
					return;
				}
				
				// Wait for response
				
				MessageFilter mf = MessageFilter.create().setSource(source).setField(DMT.UID, uid).setTimeout(RequestSender.OPENNET_TIMEOUT).setType(DMT.FNPOpennetConnectReply);
				
				try {
					msg = node.usm.waitFor(mf, this);
				} catch (DisconnectedException e) {
					Logger.normal(this, "No opennet response because node disconnected on "+this);
					return; // Lost connection with request source
				}
				
				if(msg == null) {
					// Timeout
					Logger.normal(this, "Timeout waiting for opennet peer on "+this);
					return;
				}
				
				byte[] noderef = ((ShortBuffer)msg.getObject(DMT.OPENNET_NODEREF)).getData();
				
		    	SimpleFieldSet ref;
				try {
					ref = PeerNode.compressedNoderefToFieldSet(noderef, 0, noderef.length);
				} catch (FSParseException e) {
					Logger.error(this, "Could not parse opennet noderef for "+this+" from "+source, e);
					return;
				}
		    	
		    	try {
					if(!node.addNewOpennetNode(ref)) {
						Logger.normal(this, "Asked for opennet ref but didn't want it for "+this+" :\n"+ref);
						return;
					} else {
						Logger.normal(this, "Added opennet noderef in "+this);
					}
				} catch (FSParseException e) {
					Logger.error(this, "Could not parse opennet noderef for "+this+" from "+source, e);
					return;
				} catch (PeerParseException e) {
					Logger.error(this, "Could not parse opennet noderef for "+this+" from "+source, e);
					return;
				} catch (ReferenceSignatureVerificationException e) {
					Logger.error(this, "Bad signature on opennet noderef for "+this+" from "+source+" : "+e, e);
					return;
				}
			} else {
				Message msg = DMT.createFNPOpennetCompletedAck(uid);
				try {
					source.sendAsync(msg, null, 0, this);
				} catch (NotConnectedException e) {
					// Oh well...
				}
			}
		}
    }
    
	private void finishOpennetRelay(byte[] noderef) {
    	if(logMINOR)
    		Logger.minor(this, "Finishing opennet: relaying reference from "+rs.successFrom());
		// Send it back to the handler, then wait for the ConnectReply
		PeerNode dataSource = rs.successFrom();
		
		Message msg = DMT.createFNPOpennetConnectDestination(uid, new ShortBuffer(noderef));
		
		try {
			source.sendAsync(msg, null, 0, this);
		} catch (NotConnectedException e) {
			// Lost contact with request source, nothing we can do
			return;
		}
		
		// Now wait for reply from the request source
		
		MessageFilter mf = MessageFilter.create().setSource(source).setField(DMT.UID, uid).setTimeout(RequestSender.OPENNET_TIMEOUT).setType(DMT.FNPOpennetConnectReply);
		
		try {
			msg = node.usm.waitFor(mf, this);
		} catch (DisconnectedException e) {
			return; // Lost connection with request source
		}
		
		if(msg == null) {
			// Timeout
			return;
		}
		
		noderef = ((ShortBuffer)msg.getObject(DMT.OPENNET_NODEREF)).getData();
		
		try {
			SimpleFieldSet fs = PeerNode.compressedNoderefToFieldSet(noderef, 0, noderef.length);
			if(!fs.getBoolean("opennet", false)) {
				msg = DMT.createFNPOpennetCompletedAck(uid);
			} else {
				msg = DMT.createFNPOpennetConnectReply(uid, new ShortBuffer(noderef));
			}
		} catch (FSParseException e1) {
			msg = DMT.createFNPOpennetCompletedAck(uid);
		}
		
		try {
			dataSource.sendAsync(msg, null, 0, this);
		} catch (NotConnectedException e) {
			// How sad
			return;
		}
	}

	private Message createDataFound(KeyBlock block) {
		if(block instanceof CHKBlock)
			return DMT.createFNPCHKDataFound(uid, block.getRawHeaders());
		else if(block instanceof SSKBlock) {
			node.sentPayload(block.getRawData().length); // won't be sentPayload()ed by BlockTransmitter
			return DMT.createFNPSSKDataFound(uid, block.getRawHeaders(), block.getRawData());
		} else
			throw new IllegalStateException("Unknown key block type: "+block.getClass());
	}

	private int sentBytes;
	private int receivedBytes;
	private volatile Object bytesSync = new Object();
	
	public void sentBytes(int x) {
		synchronized(bytesSync) {
			sentBytes += x;
		}
	}

	public void receivedBytes(int x) {
		synchronized(bytesSync) {
			receivedBytes += x;
		}
	}

	public void sentPayload(int x) {
		node.sentPayload(x);
	}

}
