/*
 * Jitsi, the OpenSource Java VoIP and Instant Messaging client.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package net.java.sip.communicator.impl.gui.main.chat.conference;

import java.util.*;

import javax.swing.*;

import net.java.sip.communicator.impl.gui.main.chat.*;
import net.java.sip.communicator.service.protocol.*;

/**
 * Implements an <tt>AbstractListModel</tt> which represents a member list of
 * <tt>ConferenceDescription</tt>s. 
 *
 * @author Hristo Terezov
 */
public class ChatConferenceCallsListModels
    extends AbstractListModel<ConferenceDescription>
{

    /**
     * The backing store of this <tt>AbstractListModel</tt> listing the
     * <tt>ChatContact</tt>s.
     */
    private final List<ConferenceDescription> chatConferenceCalls
        = new ArrayList<ConferenceDescription>();

    private ChatSession chatSession;
    
    /**
     * Creates the model.
     * @param chatSession The current model chat session.
     */
    public ChatConferenceCallsListModels(ChatSession chatSession)
    {
        this.chatSession = chatSession;
        
    }

    /**
     * Initializes the list of the conferences that are already announced.
     */
    public void initConferences()
    {
        Object descriptor = chatSession.getDescriptor();
        
        if(descriptor instanceof ChatRoomWrapper)
        {
            ChatRoom chatRoom = ((ChatRoomWrapper)descriptor).getChatRoom();
            for(ChatRoomMember member : chatRoom.getMembers())
            {
                ConferenceDescription cd 
                    = chatRoom.findCachedConferenceDescription(
                        member.getName());
                if(cd != null)
                {
                    chatSession.updateChatConferences(chatRoom, member, cd);
                }
            }
        }
    }

    /**
     * Adds a specific <tt>ConferenceDescription</tt> to this model 
     * implementation.
     *
     * @param chatConference a <tt>ConferenceDescription</tt> to be added to 
     * this model.
     */
    public void addElement(ConferenceDescription chatConference)
    {
        if (chatConference == null)
            throw new IllegalArgumentException("ConferenceDescription");
        
        int index = -1;

        synchronized(chatConferenceCalls)
        {
            int chatContactCount = chatConferenceCalls.size();

            if(chatConferenceCalls.contains(chatConference))
                return;
            index = chatContactCount;
            chatConferenceCalls.add(index, chatConference);
        }
        fireIntervalAdded(this, index, index);
    }

    /**
     * Returns <tt>ConferenceDescription</tt> instance at the specified index of
     * the conferences list.
     * 
     * @param index the index.
     * @return the <tt>ConferenceDescription</tt> instance.
     */
    public ConferenceDescription getElementAt(int index)
    {
        synchronized(chatConferenceCalls)
        {
            return chatConferenceCalls.get(index);
        }
    }

    /**
     * Returns the size of the conferences list.
     * 
     * @return the size.
     */
    public int getSize()
    {
        synchronized(chatConferenceCalls)
        {
            return chatConferenceCalls.size();
        }
    }

    /**
     * Removes a specific <tt>ConferenceDescription</tt> from this model 
     * implementation.
     *
     * @param chatConference a <tt>ConferenceDescription</tt> to be removed from
     * this model if it's already contained
     */
    public void removeElement(ConferenceDescription chatConference)
    {
        synchronized(chatConferenceCalls)
        {
            int index = chatConferenceCalls.indexOf(chatConference);

            if ((index >= 0) && chatConferenceCalls.remove(chatConference))
                fireIntervalRemoved(this, index, index);
        }
    }
}
