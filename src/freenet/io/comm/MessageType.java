/*
 * Dijjer - A Peer to Peer HTTP Cache
 * Copyright (C) 2004,2005 Change.Tv, Inc
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package freenet.io.comm;

import java.util.*;

import freenet.support.Logger;

public class MessageType {

    public static final String VERSION = "$Id: MessageType.java,v 1.6 2005/08/25 17:28:19 amphibian Exp $";

	private static HashMap _specs = new HashMap();

	private final String _name;
	private final LinkedList _orderedFields = new LinkedList();
	private final HashMap _fields = new HashMap();
	private final HashMap _linkedListTypes = new HashMap();
	private final boolean internalOnly;

	static {
		DMT.init();
	}

	public MessageType(String name) {
	    this(name, false);
	}
	
	public MessageType(String name, boolean internal) {
		_name = name;
		internalOnly = internal;
		Integer id = new Integer(name.hashCode());
		if (_specs.containsKey(id)) {
			throw new RuntimeException("A message type by the name of " + name + " already exists!");
		}
		_specs.put(id, this);
	}

	public void unregister() {
		_specs.remove(new Integer(_name.hashCode()));
	}
	
	public void addLinkedListField(String name, Class parameter) {
		_linkedListTypes.put(name, parameter);
		addField(name, LinkedList.class);
	}

	public void addField(String name, Class type) {
		_fields.put(name, type);
		_orderedFields.addLast(name);
	}
	
	public void addRoutedToNodeMessageFields() {
        addField(DMT.UID, Long.class);
        addField(DMT.TARGET_LOCATION, Double.class);
        addField(DMT.HTL, Short.class);
	}

	public boolean checkType(String fieldName, Object fieldValue) {
		if (fieldValue == null) {
			return false;
		}
		Class defClass = (Class)(_fields.get(fieldName));
		Class valueClass = fieldValue.getClass();
		if(defClass == valueClass) return true;
		if(defClass.isAssignableFrom(valueClass)) return true;
		return false;
	}

	public Class typeOf(String field) {
		return (Class) _fields.get(new Integer(field.hashCode()));
	}

	public boolean equals(Object o) {
		if (!(o instanceof MessageType)) {
			return false;
		}
		// We can only register one MessageType for each name.
		// So we can do == here.
		return ((MessageType) o)._name == _name;
	}

	public int hashCode() {
	    return _name.hashCode();
	}
	
	public static MessageType getSpec(Integer specID) {
		if (!_specs.containsKey(specID)) {
			Logger.error(MessageType.class, "Unrecognised message type received (" + specID + ')');
			return null;
		}
		return (MessageType) _specs.get(specID);
	}

	public String getName() {
		return _name;
	}

	public Map getFields() {
		return _fields;
	}

	public LinkedList getOrderedFields() {
		return _orderedFields;
	}
	
	public Map getLinkedListTypes() {
		return _linkedListTypes;
	}

    /**
     * @return True if this message is internal-only.
     * If this is the case, any incoming messages in UDP form of this
     * spec will be silently discarded.
     */
    public boolean isInternalOnly() {
        return internalOnly;
    }
}