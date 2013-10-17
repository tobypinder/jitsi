/*
 * Jitsi, the OpenSource Java VoIP and Instant Messaging client.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package net.java.sip.communicator.service.protocol;

import net.java.sip.communicator.service.protocol.event.*;
import net.java.sip.communicator.util.*;

import java.util.*;

/**
 * An abstract class with a default implementation of some of the methods of
 * the <tt>ChatRoom</tt> interface.
 *
 * @author Boris Grozev
 */
public abstract class AbstractChatRoom
    implements ChatRoom
{
    /**
     * The logger of this class.
     */
    private static final Logger logger
        = Logger.getLogger(AbstractChatRoom.class);
    
    /**
     * The list of listeners to be notified when a member of the chat room
     * publishes a <tt>ConferenceDescription</tt>
     */
    protected final List<ChatRoomConferencePublishedListener>
            conferencePublishedListeners
                = new LinkedList<ChatRoomConferencePublishedListener>();

    /**
     * The list of all <tt>ConferenceDescription</tt> that were announced and 
     * are not yet processed.
     */
    protected Map<String, ConferenceDescription> cachedConferenceDescriptions
        = new HashMap<String, ConferenceDescription>();
    
    /**
     * {@inheritDoc}
     */
    public void addConferencePublishedListener(
            ChatRoomConferencePublishedListener listener)
    {
        synchronized (conferencePublishedListeners)
        {
            conferencePublishedListeners.add(listener);
        }
    }

    /**
     * {@inheritDoc}
     */
    public void removeConferencePublishedListener(
            ChatRoomConferencePublishedListener listener)
    {
        synchronized (conferencePublishedListeners)
        {
            conferencePublishedListeners.remove(listener);
        }
    }
    
    /**
     * Removes <tt>ConferenceDescription</tt> instance that was published by 
     * specified member from the list of cached <tt>ConferenceDescription</tt> 
     * instances.
     * 
     * @param memberName the name of the member.
     * @return the <tt>ConferenceDescription</tt> instance that was removed.
     */
    public synchronized ConferenceDescription removeCachedConferenceDescription(
        String memberName)
    {
        return cachedConferenceDescriptions.remove(memberName);
    }

    /**
     * Finds <tt>ConferenceDescription</tt> instance that was published by 
     * specified member from the list of cached <tt>ConferenceDescription</tt> 
     * instances.
     * 
     * @param memberName the name of the member.
     * @return the <tt>ConferenceDescription</tt> instance
     */
    public synchronized ConferenceDescription findCachedConferenceDescription(
        String memberName)
    {
        return cachedConferenceDescriptions.get(memberName);
    }


    /**
     * Creates the corresponding <tt>ChatRoomConferencePublishedEvent</tt> and
     * notifies all <tt>ChatRoomConferencePublishedListener</tt>s that
     * <tt>member</tt> has published a conference description.
     *
     * @param member the <tt>ChatRoomMember</tt> that published <tt>cd</tt>.
     * @param cd the <tt>ConferenceDescription</tt> that was published.
     * @param eventType the type of the event.
     */
    protected void fireConferencePublishedEvent(
            ChatRoomMember member,
            ConferenceDescription cd,
            int eventType)
    {
        ChatRoomConferencePublishedEvent evt
                = new ChatRoomConferencePublishedEvent(eventType, this, member, 
                    cd, cachedConferenceDescriptions.size());

        List<ChatRoomConferencePublishedListener> listeners;
        synchronized (conferencePublishedListeners)
        {
            listeners  = new LinkedList<ChatRoomConferencePublishedListener>(
                    conferencePublishedListeners);
        }

        for (ChatRoomConferencePublishedListener listener : listeners)
            listener.conferencePublished(evt);
    }
    
    protected boolean processConferenceDescription(ConferenceDescription cd, 
        String participantName)
    {
        if(cd.isAvailable())
        {
            if(cachedConferenceDescriptions.containsKey(participantName))
                return false;
            
            cachedConferenceDescriptions.put(participantName, cd);
        }
        else
        {
            ConferenceDescription cachedDescription
                = cachedConferenceDescriptions.get(participantName);
            
            if(cachedDescription == null
                || !cd.compareConferenceDescription(cachedDescription))
                return false;
            
            removeCachedConferenceDescription(participantName);
        }
        
        return true;
        
    }
}
