/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.io.comm;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;

import freenet.io.AddressIdentifier;
import freenet.support.Logger;
import freenet.support.transport.ip.IPUtil;

/**
 * Long-term InetAddress. If created with an IP address, then the IP address is primary.
 * If created with a name, then the name is primary, and the IP address can change.
 * Most code ripped from Peer.
 * @author amphibian
 *
 */
public class FreenetInetAddress {

	// hostname - only set if we were created with a hostname
	// and not an address
	private final String hostname;
	private InetAddress _address;

	/**
	 * Create from serialized form on a DataInputStream.
	 */
	public FreenetInetAddress(DataInputStream dis) throws IOException {
		int firstByte = dis.readUnsignedByte();
		byte[] ba;
		if(firstByte == 255) {
			if(Logger.shouldLog(Logger.MINOR, this)) Logger.minor(this, "New format IPv6 address");
			// New format IPv6 address
			ba = new byte[16];
			dis.readFully(ba);
		} else if(firstByte == 0) {
			if(Logger.shouldLog(Logger.MINOR, this)) Logger.minor(this, "New format IPv4 address");
			// New format IPv4 address
			ba = new byte[4];
			dis.readFully(ba);
		} else {
			// Old format IPv4 address
			ba = new byte[4];
			ba[0] = (byte)firstByte;
			dis.readFully(ba, 1, 3);
		}
		_address = InetAddress.getByAddress(ba);
		String name = null;
		String s = dis.readUTF();
		if(s.length() > 0)
			name = s;
		hostname = name;
	}

	/**
	 * Create from an InetAddress. The IP address is primary i.e. fixed.
	 * The hostname either doesn't exist, or is looked up.
	 */
	public FreenetInetAddress(InetAddress address) {
		_address = address;
		hostname = null;
	}

	public FreenetInetAddress(String host, boolean allowUnknown) throws UnknownHostException {
        InetAddress addr = null;
        if(host != null){
        	if(host.startsWith("/")) host = host.substring(1);
        	host = host.trim();
        }
        // if we were created with an explicit IP address, use it as such
        // debugging log messages because AddressIdentifier doesn't appear to handle all IPv6 literals correctly, such as "fe80::204:1234:dead:beef"
        AddressIdentifier.AddressType addressType = AddressIdentifier.getAddressType(host);
        boolean logDEBUG = Logger.shouldLog(Logger.DEBUG, this);
        if(logDEBUG) Logger.debug(this, "Address type of '"+host+"' appears to be '"+addressType+ '\'');
        if(!addressType.toString().equals("Other")) {
            try {
                addr = InetAddress.getByName(host);
            } catch (UnknownHostException e) {
            	if(!allowUnknown) throw e;
                addr = null;
            }
            if(logDEBUG) Logger.debug(this, "host is '"+host+"' and addr.getHostAddress() is '"+addr.getHostAddress()+ '\'');
            if(addr != null && addr.getHostAddress().equals(host)) {
            	if(logDEBUG) Logger.debug(this, '\'' +host+"' looks like an IP address");
                host = null;
            } else {
                addr = null;
            }
        }
        if( addr == null ) {
        	if(logDEBUG) Logger.debug(this, '\'' +host+"' does not look like an IP address");
        }
        this._address = addr;
        this.hostname = host;
        // we're created with a hostname so delay the lookup of the address
        // until it's needed to work better with dynamic DNS hostnames
	}
	
	public boolean equals(Object o) {
		if(!(o instanceof FreenetInetAddress)) {
			return false;
		}
		FreenetInetAddress addr = (FreenetInetAddress)o;
		if(hostname != null) {
			if(addr.hostname == null)
				return false;
			if (!hostname.equalsIgnoreCase(addr.hostname)) {
				return false;
			}
			// Now that we know we have the same hostname, we can propagate the IP.
			if((_address != null) && (addr._address == null))
				addr._address = _address;
			if((addr._address != null) && (_address == null))
				_address = addr._address;
			// Except if we actually do have two different looked-up IPs!
			if((addr._address != null) && (_address != null) && !addr._address.equals(_address))
				return false;
			// Equal.
			return true;
		}
		if(addr.hostname != null)
			return false;

		// No hostname, go by address.
		if(!_address.equals(addr._address)) {
			return false;
		}
		
		return true;
	}

	public boolean strictEquals(FreenetInetAddress addr) {
		if(hostname != null) {
			if(addr.hostname == null)
				return false;
			if (!hostname.equalsIgnoreCase(addr.hostname)) {
				return false;
			}
			// Now that we know we have the same hostname, we can propagate the IP.
			if((_address != null) && (addr._address == null))
				addr._address = _address;
			if((addr._address != null) && (_address == null))
				_address = addr._address;
			// Except if we actually do have two different looked-up IPs!
			if((addr._address != null) && (_address != null) && !addr._address.equals(_address))
				return false;
			// Equal.
			return true;
		}

		// No hostname, go by address.
		if(!getHostName(_address).equalsIgnoreCase(getHostName(addr._address))) {
			//Logger.minor(this, "Addresses do not match: mine="+getHostName(_address)+" his="+getHostName(addr._address));
			return false;
		}
		
		return true;
	}

	/**
	 * Get the IP address. Look it up if necessary, but return the last value if it
	 * has ever been looked up before; will not trigger a new lookup if it has been
	 * looked up before.
	 */
	public InetAddress getAddress() {
	  return getAddress(true);
	}

	/**
	 * Get the IP address. Look it up only if allowed to, but return the last value if it
	 * has ever been looked up before; will not trigger a new lookup if it has been
	 * looked up before.
	 */
	public InetAddress getAddress(boolean doDNSRequest) {
		if (_address != null) {
			return _address;
		} else {
		        if(!doDNSRequest) return null;
		        InetAddress addr = getHandshakeAddress();
		        if( addr != null ) {
		                this._address = addr;
		        }
		        return addr;
		}
	}

	/**
	 * Get the IP address, looking up the hostname if the hostname is primary, even if
	 * it has been looked up before. Typically called on a reconnect attempt, when the
	 * dyndns address may have changed.
	 */
	public InetAddress getHandshakeAddress() {
	    // Since we're handshaking, hostname-to-IP may have changed
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
	    if ((_address != null) && (hostname == null)) {
	    	if(logMINOR) Logger.minor(this, "hostname is null, returning "+_address);
	        return _address;
	    } else {
	    	if(logMINOR) Logger.minor(this, "Looking up '"+hostname+"' in DNS");
	        /* 
	         * Peers are constructed from an address once a
	         * handshake has been completed, so this lookup
	         * will only be performed during a handshake
	         * (this method should normally only be called
	         * from PeerNode.getHandshakeIPs() and once
	         * each connection from this.getAddress()
	         * otherwise) - it doesn't mean we perform a
	         * DNS lookup with every packet we send.
	         */
	        try {
	        	InetAddress addr = InetAddress.getByName(hostname);
	        	if(logMINOR) Logger.minor(this, "Look up got '"+addr+ '\'');
	        	if( addr != null ) {
	        		/*
	        		 * cache the answer since getHandshakeAddress()
	        		 * doesn't use the cached value, thus
	        		 * getHandshakeIPs() should always get the
	        		 * latest value from DNS (minus Java's caching)
	        		 */
	        		this._address = InetAddress.getByAddress(addr.getAddress());
	        		if(logMINOR) Logger.minor(this, "Setting address to "+_address);
	        	}
	        	return addr;
	        } catch (UnknownHostException e) {
	        	if(logMINOR) Logger.minor(this, "DNS said hostname '"+hostname+"' is an unknown host, returning null");
	            return null;
	        }
	    }
	}

	public int hashCode() {
		if(hostname != null) {
			return hostname.hashCode();
		} else {
			return _address.hashCode();
		}
	}
	
	public String toString() {
		if(hostname != null) {
			return hostname;
		} else {
			return _address.getHostAddress();
		}
	}
	
	public String toStringPrefNumeric() {
		if(_address != null)
			return _address.getHostAddress();
		else
			return hostname;
	}

	public void writeToDataOutputStream(DataOutputStream dos, boolean oldForm) throws IOException {
		InetAddress addr = this.getAddress();
		if (addr == null) throw new UnknownHostException();
		byte[] data = addr.getAddress();
		if(oldForm) {
			if(data.length != 4)
				throw new IllegalArgumentException("IPv6 not supported at present");
		} else {
			if(data.length == 4)
				dos.write(0);
			else
				dos.write(255);
		}
		dos.write(data);
		if(hostname != null)
			dos.writeUTF(hostname);
		else
			dos.writeUTF("");
	}

	/**
	 * Return the hostname or the IP address of the given InetAddress.
	 * Does not attempt to do a reverse lookup; if the hostname is
	 * known, return it, otherwise return the textual IP address.
	 */
	public static String getHostName(InetAddress primaryIPAddress) {
		if(primaryIPAddress == null) return null;
		String s = primaryIPAddress.toString();
		String addr = s.substring(0, s.indexOf('/')).trim();
		if(addr.length() == 0)
			return primaryIPAddress.getHostAddress();
		else
			return addr;
	}

	public boolean isRealInternetAddress(boolean lookup, boolean defaultVal) {
		if(_address != null) {
			return IPUtil.isValidAddress(_address, false);
		} else {
			if(lookup) {
				InetAddress a = getAddress();
				if(a != null)
					return IPUtil.isValidAddress(a, false);
			}
			return defaultVal;	
		}
	}
}
