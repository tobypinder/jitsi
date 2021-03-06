/*
 * Jitsi, the OpenSource Java VoIP and Instant Messaging client.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package net.java.sip.communicator.impl.protocol.jabber;

import java.lang.reflect.*;
import java.util.*;

import net.java.sip.communicator.impl.protocol.jabber.extensions.gtalk.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import net.java.sip.communicator.service.protocol.*;
import net.java.sip.communicator.util.*;

import org.jitsi.service.neomedia.*;
import org.jivesoftware.smack.packet.*;

/**
 * Implements a Google Talk <tt>CallPeer</tt>.
 *
 * @author Sebastien Vincent
 * @author Lyubomir Marinov
 */
public class CallPeerGTalkImpl
    extends AbstractCallPeerJabberGTalkImpl
        <CallGTalkImpl, CallPeerMediaHandlerGTalkImpl, SessionIQ>
{
    /**
     * The <tt>Logger</tt> used by the <tt>CallPeerGTalkImpl</tt> class and its
     * instances for logging output.
     */
    private static final Logger logger
        = Logger.getLogger(CallPeerGTalkImpl.class);

    /**
     * Returns whether or not the <tt>CallPeer</tt> is an Android phone or
     * a call pass throught Google Voice or uses Google Talk client.
     *
     * We base the detection of the JID's resource which in the case of Android
     * is android/Vtok/Talk.vXXXXXXX (where XXXXXX is a suite of
     * numbers/letters).
     */
    private static boolean isAndroidOrVtokOrTalkClient(String fullJID)
    {
        int idx = fullJID.indexOf('/');

        if(idx != -1)
        {
            String res = fullJID.substring(idx + 1);
            if(res.startsWith("android") || res.startsWith("Vtok") ||
                res.startsWith("Talk.v"))
            {
                return true;
            }
        }

        if(fullJID.contains(
            "@" + ProtocolProviderServiceJabberImpl.GOOGLE_VOICE_DOMAIN))
            return true;

        return false;
    }

    /**
     * Temporary variable for handling client like FreeSwitch that sends
     * "accept" message before sending candidates.
     */
    private SessionIQ sessAcceptedWithNoCands = null;

    /**
     * Session ID.
     */
    private String sid = null;

    /**
     * Creates a new call peer with address <tt>peerAddress</tt>.
     *
     * @param peerAddress the Google Talk address of the new call peer.
     * @param owningCall the call that contains this call peer.
     */
    public CallPeerGTalkImpl(String peerAddress, CallGTalkImpl owningCall)
    {
        super(peerAddress, owningCall);

        setMediaHandler(new CallPeerMediaHandlerGTalkImpl(this));
    }

    /**
     * Indicates a user request to answer an incoming call from this
     * <tt>CallPeer</tt>.
     *
     * Sends an OK response to <tt>callPeer</tt>. Make sure that the call
     * peer contains an SDP description when you call this method.
     *
     * @throws OperationFailedException if we fail to create or send the
     * response.
     */
    public synchronized void answer()
        throws OperationFailedException
    {
        RtpDescriptionPacketExtension answer = null;

        try
        {
            getMediaHandler().getTransportManager().
                wrapupConnectivityEstablishment();
            answer = getMediaHandler().generateSessionAccept(true);
        }
        catch(IllegalArgumentException e)
        {
            sessAcceptedWithNoCands = new SessionIQ();

            // HACK apparently FreeSwitch need to have accept before
            answer = getMediaHandler().generateSessionAccept(false);

            SessionIQ response
                = GTalkPacketFactory.createSessionAccept(
                    sessionInitIQ.getTo(),
                    sessionInitIQ.getFrom(),
                    getSID(),
                    answer);

            getProtocolProvider().getConnection().sendPacket(response);
            return;
        }
        catch(Exception exc)
        {
            logger.info("Failed to answer an incoming call", exc);

            //send an error response
            String reasonText = "Error: " + exc.getMessage();
            SessionIQ errResp
                = GTalkPacketFactory.createSessionTerminate(
                        sessionInitIQ.getTo(),
                        sessionInitIQ.getFrom(),
                        sessionInitIQ.getID(),
                        Reason.FAILED_APPLICATION,
                        reasonText);

            setState(CallPeerState.FAILED, reasonText);
            getProtocolProvider().getConnection().sendPacket(errResp);
            return;
        }

        SessionIQ response
            = GTalkPacketFactory.createSessionAccept(
                    sessionInitIQ.getTo(),
                    sessionInitIQ.getFrom(),
                    getSID(),
                    answer);

        //send the packet first and start the stream later  in case the media
        //relay needs to see it before letting hole punching techniques through.
        if(sessAcceptedWithNoCands == null)
            getProtocolProvider().getConnection().sendPacket(response);

        try
        {
            getMediaHandler().start();
        }
        catch(UndeclaredThrowableException e)
        {
            Throwable exc = e.getUndeclaredThrowable();

            logger.info("Failed to establish a connection", exc);

            //send an error response
            String reasonText = "Error: " + exc.getMessage();
            SessionIQ errResp
                = GTalkPacketFactory.createSessionTerminate(
                        sessionInitIQ.getTo(),
                        sessionInitIQ.getFrom(),
                        sessionInitIQ.getID(),
                        Reason.GENERAL_ERROR,
                        reasonText);

            getMediaHandler().getTransportManager().close();
            setState(CallPeerState.FAILED, reasonText);
            getProtocolProvider().getConnection().sendPacket(errResp);
            return;
        }

        //tell everyone we are connecting so that the audio notifications would
        //stop
        setState(CallPeerState.CONNECTED);
    }

    /**
     * Returns the session ID of the Jingle session associated with this call.
     *
     * @return the session ID of the Jingle session associated with this call.
     */
    @Override
    public String getSID()
    {
        return sessionInitIQ != null ? sessionInitIQ.getID() : sid;
    }

    /**
     * Ends the call with for this <tt>CallPeer</tt>. Depending on the state
     * of the peer the method would send a CANCEL, BYE, or BUSY_HERE message
     * and set the new state to DISCONNECTED.
     *
     * @param failed indicates if the hangup is following to a call failure or
     * simply a disconnect
     * @param reasonText the text, if any, to be set on the
     * <tt>ReasonPacketExtension</tt> as the value of its
     * @param reasonOtherExtension the <tt>PacketExtension</tt>, if any, to be
     * set on the <tt>ReasonPacketExtension</tt> as the value of its
     * <tt>otherExtension</tt> property
     */
    public void hangup(boolean failed,
                       String reasonText,
                       PacketExtension reasonOtherExtension)
    {
        // do nothing if the call is already ended
        if (CallPeerState.DISCONNECTED.equals(getState())
            || CallPeerState.FAILED.equals(getState()))
        {
            if (logger.isDebugEnabled())
                logger.debug("Ignoring a request to hangup a call peer "
                        + "that is already DISCONNECTED");
            return;
        }

        CallPeerState prevPeerState = getState();
        getMediaHandler().getTransportManager().close();

        if (failed)
            setState(CallPeerState.FAILED, reasonText);
        else
            setState(CallPeerState.DISCONNECTED, reasonText);

        SessionIQ responseIQ = null;

        if (prevPeerState.equals(CallPeerState.CONNECTED)
            || CallPeerState.isOnHold(prevPeerState))
        {
            responseIQ = GTalkPacketFactory.createBye(
                getProtocolProvider().getOurJID(), peerJID, getSID());
            responseIQ.setInitiator(isInitiator() ? getAddress() :
                getProtocolProvider().getOurJID());
        }
        else if (CallPeerState.CONNECTING.equals(prevPeerState)
            || CallPeerState.CONNECTING_WITH_EARLY_MEDIA.equals(prevPeerState)
            || CallPeerState.ALERTING_REMOTE_SIDE.equals(prevPeerState))
        {
            responseIQ = GTalkPacketFactory.createCancel(
                getProtocolProvider().getOurJID(), peerJID, getSID());
            responseIQ.setInitiator(isInitiator() ? getAddress() :
                getProtocolProvider().getOurJID());
        }
        else if (prevPeerState.equals(CallPeerState.INCOMING_CALL))
        {
            responseIQ = GTalkPacketFactory.createBusy(
                getProtocolProvider().getOurJID(), peerJID, getSID());
            responseIQ.setInitiator(isInitiator() ? getAddress() :
                getProtocolProvider().getOurJID());
        }
        else if (prevPeerState.equals(CallPeerState.BUSY)
                 || prevPeerState.equals(CallPeerState.FAILED))
        {
            // For FAILED and BUSY we only need to update CALL_STATUS
            // as everything else has been done already.
        }
        else
        {
            logger.info("Could not determine call peer state!");
        }

        if (responseIQ != null)
        {
            if (reasonOtherExtension != null)
            {
                ReasonPacketExtension reason
                    = (ReasonPacketExtension)
                        responseIQ.getExtension(
                                ReasonPacketExtension.ELEMENT_NAME,
                                ReasonPacketExtension.NAMESPACE);

                if (reason == null)
                {
                    if (reasonOtherExtension instanceof ReasonPacketExtension)
                    {
                        responseIQ.setReason(
                                (ReasonPacketExtension) reasonOtherExtension);
                    }
                }
                else
                    reason.setOtherExtension(reasonOtherExtension);
            }

            getProtocolProvider().getConnection().sendPacket(responseIQ);
        }
    }

    /**
     * Initiate a Google Talk session {@link SessionIQ}.
     *
     * @param sessionInitiateExtensions a collection of additional and optional
     * <tt>PacketExtension</tt>s to be added to the <tt>initiate</tt>
     * {@link SessionIQ} which is to initiate the session with this
     * <tt>CallPeerGTalkImpl</tt>
     * @throws OperationFailedException exception
     */
    protected synchronized void initiateSession(
            Iterable<PacketExtension> sessionInitiateExtensions)
        throws OperationFailedException
    {
        sid = SessionIQ.generateSID();
        initiator = false;

        //Create the media description that we'd like to send to the other side.
        RtpDescriptionPacketExtension offer
            = getMediaHandler().createDescription();

        ProtocolProviderServiceJabberImpl protocolProvider
            = getProtocolProvider();

        sessionInitIQ
            = GTalkPacketFactory.createSessionInitiate(
                    protocolProvider.getOurJID(),
                    this.peerJID,
                    sid,
                    offer);

        if (sessionInitiateExtensions != null)
        {
            for (PacketExtension sessionInitiateExtension
                    : sessionInitiateExtensions)
            {
                sessionInitIQ.addExtension(sessionInitiateExtension);
            }
        }

        protocolProvider.getConnection().sendPacket(sessionInitIQ);

        // for Google Voice JID without resource we do not harvest and send
        // candidates
        if(getAddress().endsWith(
            ProtocolProviderServiceJabberImpl.GOOGLE_VOICE_DOMAIN))
        {
            return;
        }

        getMediaHandler().harvestCandidates(offer.getPayloadTypes(),
                new CandidatesSender()
        {
            public void sendCandidates(
                    Iterable<GTalkCandidatePacketExtension> candidates)
            {
                CallPeerGTalkImpl.this.sendCandidates(candidates);
            }
        });
    }

    /**
     * Process candidates received.
     *
     * @param sessionInitIQ The {@link SessionIQ} that created the session we
     * are handling here
     */
    public void processCandidates(SessionIQ sessionInitIQ)
    {
        Collection<PacketExtension> extensions = sessionInitIQ.getExtensions();
        List<GTalkCandidatePacketExtension> candidates =
            new ArrayList<GTalkCandidatePacketExtension>();

        for(PacketExtension ext : extensions)
        {
            if(ext.getElementName().equalsIgnoreCase(
                    GTalkCandidatePacketExtension.ELEMENT_NAME))
            {
                GTalkCandidatePacketExtension cand =
                    (GTalkCandidatePacketExtension)ext;
                candidates.add(cand);
            }
        }

        try
        {
            getMediaHandler().processCandidates(candidates);
        }
        catch (OperationFailedException ofe)
        {
            logger.warn("Failed to process an incoming candidates", ofe);

            //send an error response
            String reasonText = "Error: " + ofe.getMessage();
            SessionIQ errResp
                = GTalkPacketFactory.createSessionTerminate(
                        sessionInitIQ.getTo(),
                        sessionInitIQ.getFrom(),
                        sessionInitIQ.getID(),
                        Reason.GENERAL_ERROR,
                        reasonText);

            getMediaHandler().getTransportManager().close();
            setState(CallPeerState.FAILED, reasonText);
            getProtocolProvider().getConnection().sendPacket(errResp);
            return;
        }

        // HACK for FreeSwitch that send accept message before sending
        // candidates
        if(sessAcceptedWithNoCands != null)
        {
            if(isInitiator())
            {
                try
                {
                    answer();
                }
                catch(OperationFailedException e)
                {
                    logger.info("Failed to answer call (FreeSwitch hack)");
                }
            }
            else
            {
                final SessionIQ sess = sessAcceptedWithNoCands;
                sessAcceptedWithNoCands = null;

                // run in another thread to not block smack receive thread and
                // possibly delay others candidates messages.
                new Thread()
                {
                    @Override
                    public void run()
                    {
                        processSessionAccept(sess);
                    }
                }.start();
            }
            sessAcceptedWithNoCands = null;
        }
    }

    /**
     * Processes the session initiation {@link SessionIQ} that we were created
     * with, passing its content to the media handler.
     *
     * @param sessionInitIQ The {@link SessionIQ} that created the session that
     * we are handling here.
     */
    public void processSessionAccept(SessionIQ sessionInitIQ)
    {
        this.sessionInitIQ = sessionInitIQ;

        CallPeerMediaHandlerGTalkImpl mediaHandler = getMediaHandler();
        Collection<PacketExtension> extensions =
            sessionInitIQ.getExtensions();
        RtpDescriptionPacketExtension answer = null;

        for(PacketExtension ext : extensions)
        {
            if(ext.getElementName().equalsIgnoreCase(
                    RtpDescriptionPacketExtension.ELEMENT_NAME))
            {
                answer = (RtpDescriptionPacketExtension)ext;
                break;
            }
        }

        try
        {
            mediaHandler.getTransportManager().
                wrapupConnectivityEstablishment();
            mediaHandler.processAnswer(answer);
        }
        catch(IllegalArgumentException e)
        {
            // HACK for FreeSwitch that send accept message before sending
            // candidates
            sessAcceptedWithNoCands = sessionInitIQ;
            return;
        }
        catch(Exception exc)
        {
            if (logger.isInfoEnabled())
                logger.info("Failed to process a session-accept", exc);

            //send an error response
            String reasonText = "Error: " + exc.getMessage();
            SessionIQ errResp
                = GTalkPacketFactory.createSessionTerminate(
                        sessionInitIQ.getTo(),
                        sessionInitIQ.getFrom(),
                        sessionInitIQ.getID(),
                        Reason.GENERAL_ERROR,
                        reasonText);

            getMediaHandler().getTransportManager().close();
            setState(CallPeerState.FAILED, reasonText);
            getProtocolProvider().getConnection().sendPacket(errResp);
            return;
        }

        //tell everyone we are connecting so that the audio notifications would
        //stop
        setState(CallPeerState.CONNECTED);

        mediaHandler.start();
    }

    /**
     * Processes the session initiation {@link SessionIQ} that we were created
     * with, passing its content to the media handler and then sends either a
     * "session-info/ringing" or a "terminate" response.
     *
     * @param sessionInitIQ The {@link SessionIQ} that created the session that
     * we are handling here.
     */
    protected synchronized void processSessionInitiate(SessionIQ sessionInitIQ)
    {
        // Do initiate the session.
        this.sessionInitIQ = sessionInitIQ;
        this.initiator = true;

        RtpDescriptionPacketExtension description = null;

        for(PacketExtension ext : sessionInitIQ.getExtensions())
        {
            if(ext.getElementName().equals(
                    RtpDescriptionPacketExtension.ELEMENT_NAME))
            {
                description = (RtpDescriptionPacketExtension)ext;
                break;
            }
        }

        if(description == null)
        {
            logger.info("No description in incoming session initiate");

            //send an error response;
            String reasonText = "Error: no description";
            SessionIQ errResp
                = GTalkPacketFactory.createSessionTerminate(
                        sessionInitIQ.getTo(),
                        sessionInitIQ.getFrom(),
                        sessionInitIQ.getID(),
                        Reason.INCOMPATIBLE_PARAMETERS,
                        reasonText);

            setState(CallPeerState.FAILED, reasonText);
            getProtocolProvider().getConnection().sendPacket(errResp);
            return;
        }

        try
        {
            getMediaHandler().processOffer(description);
        }
        catch(Exception ex)
        {
            logger.info("Failed to process an incoming session initiate", ex);

            //send an error response;
            String reasonText = "Error: " + ex.getMessage();
            SessionIQ errResp
                = GTalkPacketFactory.createSessionTerminate(
                        sessionInitIQ.getTo(),
                        sessionInitIQ.getFrom(),
                        sessionInitIQ.getID(),
                        Reason.INCOMPATIBLE_PARAMETERS,
                        reasonText);

            setState(CallPeerState.FAILED, reasonText);
            getProtocolProvider().getConnection().sendPacket(errResp);
            return;
        }

        // If we do not get the info about the remote peer yet. Get it right
        // now.
        if(this.getDiscoveryInfo() == null)
        {
            String calleeURI = sessionInitIQ.getFrom();
            retrieveDiscoveryInfo(calleeURI);
        }
    }

    /**
     * Puts this peer into a {@link CallPeerState#DISCONNECTED}, indicating a
     * reason to the user, if there is one.
     *
     * @param sessionIQ the {@link SessionIQ} that's terminating our session.
     */
    public void processSessionReject(SessionIQ sessionIQ)
    {
        processSessionTerminate(sessionIQ);
    }

    /**
     * Puts this peer into a {@link CallPeerState#DISCONNECTED}, indicating a
     * reason to the user, if there is one.
     *
     * @param sessionIQ the {@link SessionIQ} that's terminating our session.
     */
    public void processSessionTerminate(SessionIQ sessionIQ)
    {
        String reasonStr = "Call ended by remote side.";
        ReasonPacketExtension reasonExt = sessionIQ.getReason();

        if(reasonStr != null && reasonExt != null)
        {
            Reason reason = reasonExt.getReason();

            if(reason != null)
                reasonStr += " Reason: " + reason.toString() + ".";

            String text = reasonExt.getText();

            if(text != null)
                reasonStr += " " + text;
        }

        getMediaHandler().getTransportManager().close();
        setState(CallPeerState.DISCONNECTED, reasonStr);
    }

    /**
     * Sends local candidate addresses from the local peer to the remote peer
     * using the <tt>candidates</tt> {@link SessionIQ}.
     *
     * @param candidates the local candidate addresses to be sent from the local
     * peer to the remote peer using the <tt>candidates</tt>
     * {@link SessionIQ}
     */
    protected void sendCandidates(
            Iterable<GTalkCandidatePacketExtension> candidates)
    {
        ProtocolProviderServiceJabberImpl protocolProvider
            = getProtocolProvider();

        SessionIQ candidatesIQ = new SessionIQ();

        candidatesIQ.setGTalkType(GTalkType.CANDIDATES);
        candidatesIQ.setFrom(protocolProvider.getOurJID());
        candidatesIQ.setInitiator(isInitiator() ? getAddress() :
            protocolProvider.getOurJID());
        candidatesIQ.setID(getSID());
        candidatesIQ.setTo(getAddress());
        candidatesIQ.setType(IQ.Type.SET);

        for (GTalkCandidatePacketExtension candidate : candidates)
        {
            // Android phone and Google Talk client does not seems to like IPv6
            // candidates since it reject the IQ candidates with an error
            // so do not send IPv6 candidates to Android phone or Talk client
            if(isAndroidOrVtokOrTalkClient(getAddress()) &&
                NetworkUtils.isIPv6Address(candidate.getAddress()))
                continue;

            candidatesIQ.addExtension(candidate);
        }

        protocolProvider.getConnection().sendPacket(candidatesIQ);
    }

    /**
     * {@inheritDoc}
     */
    public String getEntity()
    {
        return getAddress();
    }

    /**
     * {@inheritDoc}
     *
     * Uses the direction of the media stream as a fallback.
     * TODO: return the direction of the GTalk session?
     */
    @Override
    public MediaDirection getDirection(MediaType mediaType)
    {
        MediaStream stream = getMediaHandler().getStream(mediaType);
        if (stream != null)
        {
            MediaDirection direction = stream.getDirection();
            return direction == null
                    ? MediaDirection.INACTIVE
                    : direction;
        }

        return MediaDirection.INACTIVE;
    }
}
