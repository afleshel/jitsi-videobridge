/*
 * Copyright @ 2015 Atlassian Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi.videobridge.eventadmin.callstats;

import java.util.*;

import io.callstats.sdk.*;
import io.callstats.sdk.data.*;
import io.callstats.sdk.listeners.*;

import org.jitsi.eventadmin.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;
import org.jitsi.util.concurrent.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.stats.*;

/**
 * Handles events of the bridge for creating a conference/channel or expiring it
 * and reports statistics per endpoint.
 *
 * @author Damian Minkov
 */
class CallStatsConferenceStatsHandler
    implements EventHandler
{
    /**
     * The <tt>Logger</tt> used by the <tt>CallStatsConferenceStatsHandler</tt>
     * class and its instances to print debug information.
     */
    private static final Logger logger
        = Logger.getLogger(CallStatsConferenceStatsHandler.class);

    /*
    * The {@link MediaType}s for which we will report to callstats.
    */
    private static final MediaType[] MEDIA_TYPES
        = { MediaType.AUDIO, MediaType.VIDEO };


    /**
     * The entry point into the callstats.io (Java) library.
     */
    private CallStats callStats;

    /**
     * The id which identifies the current bridge.
     */
    private String bridgeId;

    /**
     * The {@link RecurringProcessibleExecutor} which periodically invokes
     * generating and pushing statistics per conference for every Channel.
     */
    private final RecurringProcessibleExecutor statisticsExecutor
        = new RecurringProcessibleExecutor();

    /**
     * List of the processor per conference. Kept in order to stop and
     * deRegister them from the executor.
     */
    private final Map<Conference,ConferencePeriodicProcessible>
        statisticsProcessors
            = new HashMap<>();

    /**
     * The interval to poll for stats and to push them to the callstats service.
     */
    private int interval;

    /**
     * Starts the handler with initialized callstats library.
     * @param callStats
     */
    void start(CallStats callStats, String bridgeId, int interval)
    {
        this.callStats = callStats;
        this.bridgeId = bridgeId;
        this.interval = interval;
    }

    /**
     * Stops and cancels all pending operations. Clears all listeners.
     */
    void stop()
    {
        // Let's stop all left processibles.
        for (ConferencePeriodicProcessible cpp : statisticsProcessors.values())
        {
            statisticsExecutor.deRegisterRecurringProcessible(cpp);
        }
    }

    /**
     * Handles events.
     * @param event the event
     */
    @Override
    public void handleEvent(Event event)
    {
        if (event == null)
        {
            logger.debug("Could not handle an event because it was null.");
            return;
        }

        String topic = event.getTopic();

        if (EventFactory.CONFERENCE_CREATED_TOPIC.equals(topic))
        {
            conferenceCreated(
                    (Conference) event.getProperty(EventFactory.EVENT_SOURCE));
        }
        else if (EventFactory.CONFERENCE_EXPIRED_TOPIC.equals(topic))
        {
            conferenceExpired(
                    (Conference) event.getProperty(EventFactory.EVENT_SOURCE));
        }
    }

    /**
     * Conference created.
     * @param conference
     */
    private void conferenceCreated(final Conference conference)
    {
        if (conference == null)
        {
            logger.debug(
                    "Could not log conference created event because the"
                        + " conference is null.");
            return;
        }

        // Create a new PeriodicProcessible and start it.
        ConferencePeriodicProcessible cpp
            = new ConferencePeriodicProcessible(conference, interval);

        cpp.start();
    }

    /**
     * Conference expired.
     * @param conference
     */
    private void conferenceExpired(Conference conference)
    {
        if (conference == null)
        {
            logger.debug(
                    "Could not log conference expired event because the"
                        + " conference is null.");
            return;
        }

        ConferencePeriodicProcessible cpp
            = statisticsProcessors.remove(conference);

        if (cpp == null)
            return;

        cpp.stop();
        statisticsExecutor.deRegisterRecurringProcessible(cpp);
    }

    /**
     * Implements a {@link RecurringProcessible} which periodically generates a
     * statistics for the conference channels.
     */
    private class ConferencePeriodicProcessible
        extends PeriodicProcessibleWithObject<Conference>
    {
        /**
         * The user info object used to identify the reports to callstats. Holds
         * the conference, the bridgeID and user callstats ID.
         */
        private UserInfo userInfo;

        /**
         * Initializes a new {@code ConferencePeriodicProcessible} instance
         * which is to {@code period}ically generate statistics for the
         * conference channels.
         *
         * @param conference the {@code Conference}'s channels to be
         * {@code period}ically checked for statistics by the new instance
         * @param period the time in milliseconds between consecutive
         * generations of statistics
         */
        public ConferencePeriodicProcessible(
                Conference conference,
                long period)
        {
            super(conference, period);
        }

        /**
         * {@inheritDoc}
         *
         * Invokes {@link Statistics#generate()} on {@link #o}.
         */
        @Override
        protected void doProcess()
        {
            for (Endpoint e : o.getEndpoints())
            {
                for (MediaType mediaType : MEDIA_TYPES)
                {
                    for (RtpChannel rc : e.getChannels(mediaType))
                        processChannelStats(rc);
                }
            }
        }

        /**
         * Called when conference is created. Sends a setup event to callstats
         * and creates the userInfo object that identifies the statistics for
         * this conference.
         */
        void start()
        {
            ConferenceInfo conferenceInfo
                = new ConferenceInfo(
                        o.getID(),
                        bridgeId);

            // Send setup event to callstats and on successful response create
            // the userInfo object.
            callStats.sendCallStatsConferenceEvent(
                    CallStatsConferenceEvents.CONFERENCE_SETUP,
                    conferenceInfo,
                    new CallStatsStartConferenceListener()
                    {
                        @Override
                        public void onResponse(String ucid)
                        {
                            userInfo = new UserInfo(o.getID(), bridgeId, ucid);
                            // Successful setup from callstats' perspective. Add
                            // it to statisticsProcessors map and register it
                            // for periodic execution.
                            statisticsProcessors.put(
                                    o,
                                    ConferencePeriodicProcessible.this);
                            statisticsExecutor.registerRecurringProcessible(
                                    ConferencePeriodicProcessible.this);
                        }

                        @Override
                        public void onError(
                                CallStatsErrors callStatsErrors,
                                String s)
                        {
                            logger.error(s + "," + callStatsErrors);
                        }
                    });
        }

        /**
         * The conference has expired, send terminate event to callstats.
         */
        void stop()
        {
            callStats.sendCallStatsConferenceEvent(
                    CallStatsConferenceEvents.CONFERENCE_TERMINATED,
                    userInfo);
        }

        /**
         * Process channel statistics.
         * @param channel the channel to process
         */
        private void processChannelStats(RtpChannel channel)
        {
            if (channel == null)
            {
                logger.debug(
                        "Could not log the channel expired event because the"
                            + " channel is null.");
                return;
            }

            if (channel.getReceiveSSRCs().length == 0)
                return;

            MediaStream stream = channel.getStream();
            if (stream == null)
                return;

            MediaStreamStats stats = stream.getMediaStreamStats();
            if (stats == null)
                return;

            Endpoint endpoint = channel.getEndpoint();
            String endpointID = (endpoint == null) ? "" : endpoint.getID();
            String conferenceID = channel.getContent().getConference().getID();

            callStats.startStatsReportingForUser(
                    endpointID,
                    conferenceID);

            // Send stats for received streams.
            for (MediaStreamSSRCStats receivedStat : stats.getReceivedStats())
            {
                ConferenceStats conferenceStats
                    = new ConferenceStatsBuilder()
                        .bytesSent(receivedStat.getNbBytes())
                        .packetsSent(receivedStat.getNbPackets())
                        .ssrc(String.valueOf(receivedStat.getSSRC()))
                        .confID(conferenceID)
                        .localUserID(bridgeId)
                        .remoteUserID(endpointID)
                        .statsType(CallStatsStreamType.INBOUND)
                        .jitter(receivedStat.getJitter())
                        .rtt((int) receivedStat.getRttMs())
                        .ucID(userInfo.getUcID())
                        .build();
                callStats.reportConferenceStats(endpointID, conferenceStats);
            }

            // Send stats for sent streams.
            for (MediaStreamSSRCStats sentStat : stats.getSentStats())
            {
                ConferenceStats conferenceStats
                    = new ConferenceStatsBuilder()
                        .bytesSent(sentStat.getNbBytes())
                        .packetsSent(sentStat.getNbPackets())
                        .ssrc(String.valueOf(sentStat.getSSRC()))
                        .confID(conferenceID)
                        .localUserID(bridgeId)
                        .remoteUserID(endpointID)
                        .statsType(CallStatsStreamType.OUTBOUND)
                        .jitter(sentStat.getJitter())
                        .rtt((int) sentStat.getRttMs())
                        .ucID(userInfo.getUcID())
                        .build();
                callStats.reportConferenceStats(endpointID, conferenceStats);
            }

            callStats.stopStatsReportingForUser(endpointID, conferenceID);
        }
    }
}
