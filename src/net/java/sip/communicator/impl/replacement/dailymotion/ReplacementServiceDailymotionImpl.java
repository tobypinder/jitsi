/*
 * SIP Communicator, the OpenSource Java VoIP and Instant Messaging client.
 * 
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package net.java.sip.communicator.impl.replacement.dailymotion;

import java.util.regex.*;

import net.java.sip.communicator.service.replacement.*;
import net.java.sip.communicator.util.*;

/**
 * Implements the {@link ReplacementService} to provide previews for Dailymotion
 * links.
 * 
 * @author Purvesh Sahoo
 */
public class ReplacementServiceDailymotionImpl
    implements ReplacementService
{
    /**
     * The logger for this class.
     */
    private static final Logger logger =
        Logger.getLogger(ReplacementServiceDailymotionImpl.class);

    /**
     * The regex used to match the link in the message.
     */
    public static final String DAILYMOTION_PATTERN =
        "(http.*?(www\\.)*?dailymotion\\.com\\/video\\/([a-zA-Z0-9_\\-]+))([?#]([a-zA-Z0-9_\\-]+))*";

    /**
     * Configuration label shown in the config form. 
     */
    public static final String DAILYMOTION_CONFIG_LABEL = "DailyMotion";
    
    /**
     * Source name; also used as property label.
     */
    public static final String SOURCE_NAME = "DAILYMOTION";

    /**
     * Constructor for <tt>ReplacementServiceDailymotionImpl</tt>.
     */
    public ReplacementServiceDailymotionImpl()
    {
        logger.trace("Creating a DailyMotion Source.");
    }

    /**
     * Replaces the dailymotion video links in the chat message with their
     * corresponding thumbnails.
     * 
     * @param chatString the original chat message.
     * @return replaced chat message with the thumbnail image; the original
     *         message in case of no match.
     */
    public String getReplacedMessage(final String chatString)
    {

        final Pattern p =
            Pattern.compile(DAILYMOTION_PATTERN, Pattern.CASE_INSENSITIVE
                | Pattern.DOTALL);
        Matcher m = p.matcher(chatString);

        int count = 0, startPos = 0;
        StringBuffer msgBuff = new StringBuffer();

        while (m.find())
        {

            count++;
            msgBuff.append(chatString.substring(startPos, m.start()));
            startPos = m.end();

            if (count % 2 == 0)
            {
                msgBuff.append("<IMG HEIGHT=\"120\" WIDTH=\"160\" SRC=\"");
                msgBuff
                    .append("http://www.dailymotion.com/thumbnail/160x120/video/");
                msgBuff.append(m.group(3));
                msgBuff.append("\"></IMG>");
            }
            else
            {
                msgBuff.append(chatString.substring(m.start(), m.end()));
            }
        }

        msgBuff.append(chatString.substring(startPos));

        if (!msgBuff.toString().equals(chatString))
            return msgBuff.toString();

        return chatString;
    }
    
    /**
     * Returns the source name
     * 
     * @return the source name
     */
    public String getSourceName()
    {
        return SOURCE_NAME;
    }
}