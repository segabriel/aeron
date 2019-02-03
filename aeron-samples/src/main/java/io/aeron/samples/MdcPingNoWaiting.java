/*
 * Copyright 2014-2019 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.samples;

import io.aeron.Aeron;
import io.aeron.ChannelUriStringBuilder;
import io.aeron.CommonContext;
import io.aeron.FragmentAssembler;
import io.aeron.Image;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import org.HdrHistogram.Histogram;
import org.agrona.BitUtil;
import org.agrona.BufferUtil;
import org.agrona.CloseHelper;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.console.ContinueBarrier;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Ping component of Ping-Pong latency test recorded to a histogram to capture full distribution..
 * <p>
 * Initiates messages sent to {@link MdcPong} and records times.
 * @see MdcPong
 */
public class MdcPingNoWaiting
{
    private static final int INBOUND_STREAM_ID = 0xcafe0000;
    private static final int OUTBOUND_STREAM_ID = 0xcafe0000;
    private static final int PORT = 13000;
    private static final int CONTROL_PORT = 13001;
    private static final int SESSION_ID = 1001;
    private static final String OUTBOUND_CHANNEL = new ChannelUriStringBuilder()
        .endpoint("localhost:" + PORT)
        .sessionId(SESSION_ID)
        .media("udp")
        .reliable(Boolean.TRUE)
        .build();
    private static final String INBOUND_CHANNEL = new ChannelUriStringBuilder()
        .controlEndpoint("localhost:" + CONTROL_PORT)
        .controlMode(CommonContext.MDC_CONTROL_MODE_DYNAMIC)
        .sessionId(SESSION_ID ^ Integer.MAX_VALUE)
        .reliable(Boolean.TRUE)
        .media("udp")
        .build();

    private static final long NUMBER_OF_MESSAGES = SampleConfiguration.NUMBER_OF_MESSAGES;
    private static final long WARMUP_NUMBER_OF_MESSAGES = SampleConfiguration.WARMUP_NUMBER_OF_MESSAGES;
    private static final int WARMUP_NUMBER_OF_ITERATIONS = SampleConfiguration.WARMUP_NUMBER_OF_ITERATIONS;
    private static final int MESSAGE_LENGTH = SampleConfiguration.MESSAGE_LENGTH;
    private static final int FRAGMENT_COUNT_LIMIT = SampleConfiguration.FRAGMENT_COUNT_LIMIT;
    private static final boolean EMBEDDED_MEDIA_DRIVER = SampleConfiguration.EMBEDDED_MEDIA_DRIVER;
    private static final boolean EXCLUSIVE_PUBLICATIONS = SampleConfiguration.EXCLUSIVE_PUBLICATIONS;

    private static final UnsafeBuffer OFFER_BUFFER = new UnsafeBuffer(
        BufferUtil.allocateDirectAligned(MESSAGE_LENGTH, BitUtil.CACHE_LINE_LENGTH));
    private static final Histogram HISTOGRAM = new Histogram(TimeUnit.SECONDS.toNanos(10), 3);
    private static final CountDownLatch LATCH = new CountDownLatch(1);
    private static final IdleStrategy POLLING_IDLE_STRATEGY = new BusySpinIdleStrategy();

    public static void main(final String[] args) throws Exception
    {
        final MediaDriver driver = EMBEDDED_MEDIA_DRIVER ? MediaDriver.launchEmbedded() : null;
        final Aeron.Context ctx = new Aeron.Context()
            .availableImageHandler(MdcPingNoWaiting::availablePongImageHandler);
        final FragmentHandler fragmentHandler = new FragmentAssembler(MdcPingNoWaiting::pongHandler);

        if (EMBEDDED_MEDIA_DRIVER)
        {
            ctx.aeronDirectoryName(driver.aeronDirectoryName());
        }

        System.out.println("Publishing Ping at " + OUTBOUND_CHANNEL + " on stream Id " + OUTBOUND_STREAM_ID);
        System.out.println("Subscribing Pong at " + INBOUND_CHANNEL + " on stream Id " + INBOUND_STREAM_ID);
        System.out.println("Message length of " + MESSAGE_LENGTH + " bytes");
        System.out.println("Using exclusive publications " + EXCLUSIVE_PUBLICATIONS);

        try (Aeron aeron = Aeron.connect(ctx);
            Subscription subscription = aeron.addSubscription(INBOUND_CHANNEL, INBOUND_STREAM_ID);
            Publication publication = EXCLUSIVE_PUBLICATIONS ?
                aeron.addExclusivePublication(OUTBOUND_CHANNEL, OUTBOUND_STREAM_ID) :
                aeron.addPublication(OUTBOUND_CHANNEL, OUTBOUND_STREAM_ID))
        {
            System.out.println("Waiting for new image from Pong...");
            LATCH.await();

            System.out.println(
                "Warming up... " + WARMUP_NUMBER_OF_ITERATIONS +
                " iterations of " + WARMUP_NUMBER_OF_MESSAGES + " messages");

            for (int i = 0; i < WARMUP_NUMBER_OF_ITERATIONS; i++)
            {
                roundTripMessages(fragmentHandler, publication, subscription, WARMUP_NUMBER_OF_MESSAGES);
            }

            Thread.sleep(100);
            final ContinueBarrier barrier = new ContinueBarrier("Execute again?");

            do
            {
                HISTOGRAM.reset();
                System.out.println("Pinging " + NUMBER_OF_MESSAGES + " messages");

                roundTripMessages(fragmentHandler, publication, subscription, NUMBER_OF_MESSAGES);
                System.out.println("Histogram of RTT latencies in microseconds.");

                HISTOGRAM.outputPercentileDistribution(System.out, 1000.0);
            }
            while (barrier.await());
        }

        CloseHelper.quietClose(driver);
    }

    private static void roundTripMessages(
        final FragmentHandler fragmentHandler,
        final Publication publication,
        final Subscription subscription,
        final long count)
    {
        while (!subscription.isConnected())
        {
            Thread.yield();
        }

        final Image image = subscription.imageAtIndex(0);

        int received = 0;

        for (long i = 0; i < count; )
        {
            int workCount = 0;

            OFFER_BUFFER.putLong(0, System.nanoTime());

            final long offeredPosition = publication.offer(OFFER_BUFFER, 0, MESSAGE_LENGTH);

            if (offeredPosition > 0)
            {
                i++;
                workCount = 1;
            }

            final int poll = image.poll(fragmentHandler, FRAGMENT_COUNT_LIMIT);

            workCount += poll;
            received += poll;

            POLLING_IDLE_STRATEGY.idle(workCount);
        }

        while (received < count)
        {
            final int poll = image.poll(fragmentHandler, FRAGMENT_COUNT_LIMIT);
            received += poll;
            POLLING_IDLE_STRATEGY.idle(poll);
        }
    }

    private static void pongHandler(final DirectBuffer buffer, final int offset, final int length, final Header header)
    {
        final long pingTimestamp = buffer.getLong(offset);
        final long rttNs = System.nanoTime() - pingTimestamp;

        HISTOGRAM.recordValue(rttNs);
    }

    private static void availablePongImageHandler(final Image image)
    {
        final Subscription subscription = image.subscription();
        System.out.format(
            "Available image: channel=%s streamId=%d session=%d%n",
            subscription.channel(), subscription.streamId(), image.sessionId());

        if (INBOUND_STREAM_ID == subscription.streamId() && INBOUND_CHANNEL.equals(subscription.channel()))
        {
            LATCH.countDown();
        }
    }
}
