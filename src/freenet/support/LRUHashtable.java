package freenet.support;

import java.util.Enumeration;
import java.util.Hashtable;

public class LRUHashtable {

    /*
     * I've just converted this to using the DLList and Hashtable
     * this makes it Hashtable time instead of O(N) for push and
     * remove, and Hashtable time instead of O(1) for pop.  Since
     * push is by far the most done operation, this should be an
     * overall improvement.
     */
    private final DoublyLinkedListImpl list = new DoublyLinkedListImpl();
    private final Hashtable hash = new Hashtable();
    
    /**
     *       push()ing an object that is already in
     *       the queue moves that object to the most
     *       recently used position, but doesn't add
     *       a duplicate entry in the queue.
     */
    public final synchronized void push(Object key, Object value) {
        QItem insert = (QItem)hash.get(key);
        if (insert == null) {
            insert = new QItem(key, value);
            hash.put(key,insert);
        } else {
        	insert.value = value;
            list.remove(insert);
        }
        if(Logger.shouldLog(Logger.MINOR, this))
        	Logger.minor(this, "Pushed "+insert+" ( "+key+ ' ' +value+" )");

        list.unshift(insert);
    } 

    /**
     *  @return Least recently pushed key.
     */
    public final synchronized Object popKey() {
        if ( list.size() > 0 ) {
            return ((QItem)hash.remove(((QItem)list.pop()).obj)).obj;
        } else {
            return null;
        }
    }

    /**
     * @return Least recently pushed value.
     */
    public final synchronized Object popValue() {
        if ( list.size() > 0 ) {
            return ((QItem)hash.remove(((QItem)list.pop()).obj)).value;
        } else {
            return null;
        }
    }
    
	public final synchronized Object peekValue() {
        if ( list.size() > 0 ) {
            return ((QItem)hash.get(((QItem)list.tail()).obj)).value;
        } else {
            return null;
        }
	}

    public final int size() {
        return list.size();
    }
    
    public final synchronized boolean removeKey(Object key) {
	QItem i = (QItem)(hash.remove(key));
	if(i != null) {
	    list.remove(i);
	    return true;
	} else {
	    return false;
	}
    }
    
    /**
     * Check if this queue contains obj
     * @param obj Object to match
     * @return true if this queue contains obj.
     */
    public final synchronized boolean containsKey(Object key) {
        return hash.containsKey(key);
    }
    
    /**
     * Note that this does not automatically promote the key. You have
     * to do that by hand with push(key, value).
     */
    public final synchronized Object get(Object key) {
    	QItem q = (QItem) hash.get(key);
    	if(q == null) return null;
    	return q.value;
    }
    
    public Enumeration keys() {
        return new ItemEnumeration();
    }

    private class ItemEnumeration implements Enumeration {
        private Enumeration source = list.reverseElements();
       
        public boolean hasMoreElements() {
            return source.hasMoreElements();
        }

        public Object nextElement() {
            return ((QItem) source.nextElement()).obj;
        }
    }

    private static class QItem extends DoublyLinkedListImpl.Item {
        public Object obj;
        public Object value;

        public QItem(Object key, Object val) {
            this.obj = key;
            this.value = val;
        }
        
        public String toString() {
        	return super.toString()+": "+obj+ ' ' +value;
        }
    }

	public boolean isEmpty() {
		return list.isEmpty();
	}

}
