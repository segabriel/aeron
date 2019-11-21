/*
 * Copyright 2014-2019 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.cluster;

import io.aeron.CommonContext;
import io.aeron.archive.ArchiveThreadingMode;
import io.aeron.cluster.service.Cluster;
import io.aeron.driver.MinMulticastFlowControlSupplier;
import io.aeron.driver.ThreadingMode;
import org.agrona.CloseHelper;
import org.agrona.collections.MutableInteger;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.YieldingIdleStrategy;
import org.agrona.concurrent.status.CountersReader;
import org.junit.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static io.aeron.Aeron.NULL_VALUE;
import static io.aeron.cluster.service.CommitPos.COMMIT_POSITION_TYPE_ID;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.*;
import static org.junit.Assert.assertThat;

//@Ignore
public class ClusterTest
{
    private static final String MSG = "Hello World!";

    @Test(timeout = 30_000)
    public void shouldStopFollowerAndRestartFollower() throws Exception
    {
        try (TestCluster cluster = TestCluster.startThreeNodeStaticCluster(NULL_VALUE))
        {
            cluster.awaitLeader();

            TestNode follower = cluster.followers().get(0);

            cluster.stopNode(follower);
            Thread.sleep(1_000);
            follower = cluster.startStaticNode(follower.index(), false);
            Thread.sleep(1_000);

            assertThat(follower.role(), is(Cluster.Role.FOLLOWER));
        }
    }

    @Test(timeout = 30_000)
    public void shouldNotifyClientOfNewLeader() throws Exception
    {
        try (TestCluster cluster = TestCluster.startThreeNodeStaticCluster(NULL_VALUE))
        {
            final TestNode leader = cluster.awaitLeader();

            cluster.connectClient();

            cluster.stopNode(leader);

            cluster.awaitLeadershipEvent(1);
        }
    }

    @Test(timeout = 30_000)
    public void shouldStopLeaderAndFollowersThenRestartAllWithSnapshot() throws Exception
    {
        try (TestCluster cluster = TestCluster.startThreeNodeStaticCluster(NULL_VALUE))
        {
            final TestNode leader = cluster.awaitLeader();

            cluster.takeSnapshot(leader);
            cluster.awaitSnapshotCounter(cluster.node(0), 1);
            cluster.awaitSnapshotCounter(cluster.node(1), 1);
            cluster.awaitSnapshotCounter(cluster.node(2), 1);

            cluster.stopNode(cluster.node(0));
            cluster.stopNode(cluster.node(1));
            cluster.stopNode(cluster.node(2));

            Thread.sleep(1_000);

            cluster.startStaticNode(0, false);
            cluster.startStaticNode(1, false);
            cluster.startStaticNode(2, false);

            cluster.awaitLeader();
            assertThat(cluster.followers().size(), is(2));

            cluster.awaitSnapshotLoadedForService(cluster.node(0));
            cluster.awaitSnapshotLoadedForService(cluster.node(1));
            cluster.awaitSnapshotLoadedForService(cluster.node(2));
        }
    }

    @Test(timeout = 30_000)
    public void shouldShutdownClusterAndRestartWithSnapshots() throws Exception
    {
        try (TestCluster cluster = TestCluster.startThreeNodeStaticCluster(NULL_VALUE))
        {
            final TestNode leader = cluster.awaitLeader();

            cluster.node(0).terminationExpected(true);
            cluster.node(1).terminationExpected(true);
            cluster.node(2).terminationExpected(true);

            cluster.shutdownCluster(leader);
            cluster.awaitNodeTermination(cluster.node(0));
            cluster.awaitNodeTermination(cluster.node(1));
            cluster.awaitNodeTermination(cluster.node(2));

            assertTrue(cluster.node(0).service().wasSnapshotTaken());
            assertTrue(cluster.node(1).service().wasSnapshotTaken());
            assertTrue(cluster.node(2).service().wasSnapshotTaken());

            cluster.stopNode(cluster.node(0));
            cluster.stopNode(cluster.node(1));
            cluster.stopNode(cluster.node(2));

            Thread.sleep(1_000);

            cluster.startStaticNode(0, false);
            cluster.startStaticNode(1, false);
            cluster.startStaticNode(2, false);

            cluster.awaitLeader();
            assertThat(cluster.followers().size(), is(2));

            cluster.awaitSnapshotLoadedForService(cluster.node(0));
            cluster.awaitSnapshotLoadedForService(cluster.node(1));
            cluster.awaitSnapshotLoadedForService(cluster.node(2));
        }
    }

    @Test(timeout = 30_000)
    public void shouldAbortClusterAndRestart() throws Exception
    {
        try (TestCluster cluster = TestCluster.startThreeNodeStaticCluster(NULL_VALUE))
        {
            final TestNode leader = cluster.awaitLeader();

            cluster.node(0).terminationExpected(true);
            cluster.node(1).terminationExpected(true);
            cluster.node(2).terminationExpected(true);

            cluster.abortCluster(leader);
            cluster.awaitNodeTermination(cluster.node(0));
            cluster.awaitNodeTermination(cluster.node(1));
            cluster.awaitNodeTermination(cluster.node(2));

            assertFalse(cluster.node(0).service().wasSnapshotTaken());
            assertFalse(cluster.node(1).service().wasSnapshotTaken());
            assertFalse(cluster.node(2).service().wasSnapshotTaken());

            cluster.stopNode(cluster.node(0));
            cluster.stopNode(cluster.node(1));
            cluster.stopNode(cluster.node(2));

            Thread.sleep(1_000);

            cluster.startStaticNode(0, false);
            cluster.startStaticNode(1, false);
            cluster.startStaticNode(2, false);

            cluster.awaitLeader();
            assertThat(cluster.followers().size(), is(2));

            assertFalse(cluster.node(0).service().wasSnapshotLoaded());
            assertFalse(cluster.node(1).service().wasSnapshotLoaded());
            assertFalse(cluster.node(2).service().wasSnapshotLoaded());
        }
    }

    @Test(timeout = 30_000)
    public void shouldAbortClusterOnTerminationTimeout() throws Exception
    {
        try (TestCluster cluster = TestCluster.startThreeNodeStaticCluster(NULL_VALUE))
        {
            final TestNode leader = cluster.awaitLeader();

            final List<TestNode> followers = cluster.followers();

            assertThat(followers.size(), is(2));
            final TestNode followerA = followers.get(0);
            final TestNode followerB = followers.get(1);

            leader.terminationExpected(true);
            followerA.terminationExpected(true);

            cluster.stopNode(followerB);

            cluster.connectClient();

            final int messageCount = 10;
            cluster.sendMessages(messageCount);
            cluster.awaitResponses(messageCount);

            cluster.abortCluster(leader);
            cluster.awaitNodeTermination(leader);
            cluster.awaitNodeTermination(followerA);

            cluster.stopNode(leader);
            cluster.stopNode(followerA);
        }
    }

    @Test(timeout = 30_000)
    public void shouldEchoMessagesThenContinueOnNewLeader() throws Exception
    {
        try (TestCluster cluster = TestCluster.startThreeNodeStaticCluster(NULL_VALUE))
        {
            final TestNode originalLeader = cluster.awaitLeader();
            cluster.connectClient();

            final int preFailureMessageCount = 10;
            final int postFailureMessageCount = 7;

            cluster.sendMessages(preFailureMessageCount);
            cluster.awaitResponses(preFailureMessageCount);
            cluster.awaitMessageCountForService(cluster.node(0), preFailureMessageCount);
            cluster.awaitMessageCountForService(cluster.node(1), preFailureMessageCount);
            cluster.awaitMessageCountForService(cluster.node(2), preFailureMessageCount);

            assertThat(cluster.client().leaderMemberId(), is(originalLeader.index()));

            cluster.stopNode(originalLeader);

            final TestNode newLeader = cluster.awaitLeader(originalLeader.index());

            cluster.sendMessages(postFailureMessageCount);
            cluster.awaitResponses(preFailureMessageCount + postFailureMessageCount);
            assertThat(cluster.client().leaderMemberId(), is(newLeader.index()));

            final TestNode follower = cluster.followers().get(0);

            cluster.awaitMessageCountForService(newLeader, preFailureMessageCount + postFailureMessageCount);
            cluster.awaitMessageCountForService(follower, preFailureMessageCount + postFailureMessageCount);
        }
    }

    @Test(timeout = 30_000)
    public void shouldStopLeaderAndRestartAsFollower() throws Exception
    {
        try (TestCluster cluster = TestCluster.startThreeNodeStaticCluster(NULL_VALUE))
        {
            final TestNode originalLeader = cluster.awaitLeader();

            cluster.stopNode(originalLeader);
            cluster.awaitLeader(originalLeader.index());

            final TestNode follower = cluster.startStaticNode(originalLeader.index(), false);

            Thread.sleep(5_000);

            assertThat(follower.role(), is(Cluster.Role.FOLLOWER));
            assertThat(follower.electionState(), is((Election.State)null));
        }
    }

    @Test(timeout = 30_000)
    public void shouldStopLeaderAndRestartAsFollowerWithSendingAfter() throws Exception
    {
        try (TestCluster cluster = TestCluster.startThreeNodeStaticCluster(NULL_VALUE))
        {
            final TestNode originalLeader = cluster.awaitLeader();

            cluster.stopNode(originalLeader);
            cluster.awaitLeader(originalLeader.index());

            final TestNode follower = cluster.startStaticNode(originalLeader.index(), false);

            while (follower.electionState() != null)
            {
                Thread.sleep(1000);
            }

            assertThat(follower.role(), is(Cluster.Role.FOLLOWER));

            cluster.connectClient();

            final int messageCount = 10;
            cluster.sendMessages(messageCount);
            cluster.awaitResponses(messageCount);
        }
    }

    @Test(timeout = 60_000)
    public void shouldStopLeaderAndRestartAsFollowerWithSendingAfterThenStopLeader() throws Exception
    {
        try (TestCluster cluster = TestCluster.startThreeNodeStaticCluster(NULL_VALUE))
        {
            final TestNode originalLeader = cluster.awaitLeader();

            cluster.stopNode(originalLeader);
            cluster.awaitLeader(originalLeader.index());

            final TestNode follower = cluster.startStaticNode(originalLeader.index(), false);

            Thread.sleep(5_000);

            assertThat(follower.role(), is(Cluster.Role.FOLLOWER));
            assertThat(follower.electionState(), is((Election.State)null));

            cluster.connectClient();

            final int messageCount = 10;
            cluster.sendMessages(messageCount);
            cluster.awaitResponses(messageCount);

            final TestNode leader = cluster.awaitLeader();

            cluster.stopNode(leader);

            cluster.awaitLeader(leader.index());
        }
    }

    @Test(timeout = 30_000)
    public void shouldAcceptMessagesAfterSingleNodeCleanRestart() throws Exception
    {
        try (TestCluster cluster = TestCluster.startThreeNodeStaticCluster(NULL_VALUE))
        {
            cluster.awaitLeader();

            TestNode follower = cluster.followers().get(0);

            cluster.stopNode(follower);

            Thread.sleep(10_000);

            follower = cluster.startStaticNode(follower.index(), true);

            Thread.sleep(1_000);

            assertThat(follower.role(), is(Cluster.Role.FOLLOWER));

            cluster.connectClient();

            final int messageCount = 10;
            cluster.sendMessages(messageCount);
            cluster.awaitResponses(messageCount);
            cluster.awaitMessageCountForService(follower, messageCount);
        }
    }

    @Test(timeout = 30_000)
    public void shouldReplaySnapshotTakenWhileDown() throws Exception
    {
        try (TestCluster cluster = TestCluster.startThreeNodeStaticCluster(NULL_VALUE))
        {
            final TestNode leader = cluster.awaitLeader();
            final TestNode followerA = cluster.followers().get(0);
            TestNode followerB = cluster.followers().get(1);

            cluster.stopNode(followerB);

            Thread.sleep(10_000);

            cluster.takeSnapshot(leader);
            cluster.awaitSnapshotCounter(leader, 1);
            cluster.awaitSnapshotCounter(followerA, 1);

            cluster.connectClient();

            final int messageCount = 10;
            cluster.sendMessages(messageCount);
            cluster.awaitResponses(messageCount);

            followerB = cluster.startStaticNode(followerB.index(), false);

            cluster.awaitSnapshotCounter(followerB, 1);
            assertThat(followerB.role(), is(Cluster.Role.FOLLOWER));

            cluster.awaitMessageCountForService(followerB, messageCount);
            assertThat(followerB.errors(), is(0L));
        }
    }

    @Test(timeout = 45_000)
    public void shouldTolerateMultipleLeaderFailures() throws Exception
    {
        try (TestCluster cluster = TestCluster.startThreeNodeStaticCluster(NULL_VALUE))
        {
            final TestNode firstLeader = cluster.awaitLeader();
            cluster.stopNode(firstLeader);

            final TestNode secondLeader = cluster.awaitLeader();

            final long commitPos = secondLeader.commitPosition();
            final TestNode newFollower = cluster.startStaticNode(firstLeader.index(), false);

            cluster.awaitCommitPosition(newFollower, commitPos);
            cluster.awaitNotInElection(newFollower);

            cluster.stopNode(secondLeader);

            cluster.awaitLeader();

            cluster.connectClient();

            final int messageCount = 10;
            cluster.sendMessages(messageCount);
            cluster.awaitResponses(messageCount);
        }
    }

    @Test(timeout = 30_000)
    public void shouldAcceptMessagesAfterTwoNodeCleanRestart() throws Exception
    {
        try (TestCluster cluster = TestCluster.startThreeNodeStaticCluster(NULL_VALUE))
        {
            cluster.awaitLeader();

            final List<TestNode> followers = cluster.followers();
            TestNode followerA = followers.get(0), followerB = followers.get(1);

            cluster.stopNode(followerA);
            cluster.stopNode(followerB);

            Thread.sleep(5_000);

            followerA = cluster.startStaticNode(followerA.index(), true);
            followerB = cluster.startStaticNode(followerB.index(), true);

            Thread.sleep(1_000);

            assertThat(followerA.role(), is(Cluster.Role.FOLLOWER));
            assertThat(followerB.role(), is(Cluster.Role.FOLLOWER));

            cluster.connectClient();
            final int messageCount = 10;

            cluster.sendMessages(messageCount);
            cluster.awaitResponses(messageCount);
            cluster.awaitMessageCountForService(followerA, messageCount);
            cluster.awaitMessageCountForService(followerB, messageCount);
        }
    }

    @Test(timeout = 30_000)
    public void shouldHaveOnlyOneCommitPositionCounter() throws Exception
    {
        try (TestCluster cluster = TestCluster.startThreeNodeStaticCluster(NULL_VALUE))
        {
            final TestNode leader = cluster.awaitLeader();

            final List<TestNode> followers = cluster.followers();
            final TestNode followerA = followers.get(0), followerB = followers.get(1);

            cluster.stopNode(leader);

            cluster.awaitLeader(leader.index());

            assertThat(countersOfType(followerA.countersReader(), COMMIT_POSITION_TYPE_ID), is(1));
            assertThat(countersOfType(followerB.countersReader(), COMMIT_POSITION_TYPE_ID), is(1));
        }
    }

    @Test(timeout = 30_000)
    public void shouldCallOnRoleChangeOnBecomingLeader() throws Exception
    {
        try (TestCluster cluster = TestCluster.startThreeNodeStaticCluster(NULL_VALUE))
        {
            TestNode leader = cluster.awaitLeader();

            List<TestNode> followers = cluster.followers();
            final TestNode followerA = followers.get(0);
            final TestNode followerB = followers.get(1);

            assertThat(leader.service().roleChangedTo(), is(Cluster.Role.LEADER));
            assertThat(followerA.service().roleChangedTo(), is((Cluster.Role)null));
            assertThat(followerB.service().roleChangedTo(), is((Cluster.Role)null));

            cluster.stopNode(leader);

            leader = cluster.awaitLeader(leader.index());
            followers = cluster.followers();
            final TestNode follower = followers.get(0);

            assertThat(leader.service().roleChangedTo(), is(Cluster.Role.LEADER));
            assertThat(follower.service().roleChangedTo(), is((Cluster.Role)null));
        }
    }

    @Test(timeout = 30_000)
    public void shouldLoseLeadershipWhenNoActiveQuorumOfFollowers() throws Exception
    {
        try (TestCluster cluster = TestCluster.startThreeNodeStaticCluster(NULL_VALUE))
        {
            final TestNode leader = cluster.awaitLeader();

            final List<TestNode> followers = cluster.followers();
            final TestNode followerA = followers.get(0);
            final TestNode followerB = followers.get(1);

            assertThat(leader.service().roleChangedTo(), is(Cluster.Role.LEADER));

            cluster.stopNode(followerA);
            cluster.stopNode(followerB);

            while (leader.service().roleChangedTo() == Cluster.Role.LEADER)
            {
                TestUtil.checkInterruptedStatus();
                Thread.yield();
            }

            assertThat(leader.service().roleChangedTo(), is(Cluster.Role.FOLLOWER));
        }
    }

    @Test(timeout = 60_000)
    public void shouldRecoverWhileMessagesContinue() throws Exception
    {
        try (TestCluster cluster = TestCluster.startThreeNodeStaticCluster(NULL_VALUE))
        {
            final TestNode leader = cluster.awaitLeader();

            final List<TestNode> followers = cluster.followers();
            final TestNode followerA = followers.get(0);
            TestNode followerB = followers.get(1);

            cluster.connectClient();
            final Thread messageThread = startMessageThread(cluster, TimeUnit.MICROSECONDS.toNanos(500));
            try
            {
                cluster.stopNode(followerB);
                Thread.sleep(10_000);

                followerB = cluster.startStaticNode(followerB.index(), false);
                Thread.sleep(30_000);
            }
            finally
            {
                messageThread.interrupt();
                messageThread.join();
            }

            assertThat(leader.errors(), is(0L));
            assertThat(followerA.errors(), is(0L));
            assertThat(followerB.errors(), is(0L));
            assertThat(followerB.electionState(), is((Election.State)null));
        }
    }

    @Test(timeout = 10_000)
    public void shouldCatchupFromEmptyLog() throws Exception
    {
        try (TestCluster cluster = TestCluster.startThreeNodeStaticCluster(NULL_VALUE))
        {
            cluster.awaitLeader();

            final List<TestNode> followers = cluster.followers();
            TestNode followerB = followers.get(1);

            cluster.stopNode(followerB);

            cluster.connectClient();
            final int messageCount = 10;
            cluster.sendMessages(messageCount);
            cluster.awaitResponses(messageCount);

            followerB = cluster.startStaticNode(followerB.index(), true);
            cluster.awaitMessageCountForService(followerB, messageCount);
        }
    }

    @Test(timeout = 30_000)
    public void shouldCatchupFromEmptyLogThenSnapshotAfterShutdownAndFollowerCleanStart() throws Exception
    {
        try (TestCluster cluster = TestCluster.startThreeNodeStaticCluster(NULL_VALUE))
        {
            final TestNode leader = cluster.awaitLeader();
            final List<TestNode> followers = cluster.followers();
            final TestNode followerA = followers.get(0);
            final TestNode followerB = followers.get(1);

            cluster.connectClient();
            final int messageCount = 10;
            cluster.sendMessages(messageCount);
            cluster.awaitResponses(messageCount);

            leader.terminationExpected(true);
            followerA.terminationExpected(true);
            followerB.terminationExpected(true);

            cluster.shutdownCluster(leader);
            cluster.awaitNodeTermination(cluster.node(0));
            cluster.awaitNodeTermination(cluster.node(1));
            cluster.awaitNodeTermination(cluster.node(2));

            assertTrue(cluster.node(0).service().wasSnapshotTaken());
            assertTrue(cluster.node(1).service().wasSnapshotTaken());
            assertTrue(cluster.node(2).service().wasSnapshotTaken());

            cluster.stopNode(cluster.node(0));
            cluster.stopNode(cluster.node(1));
            cluster.stopNode(cluster.node(2));

            Thread.sleep(1_000);

            cluster.startStaticNode(0, false);
            cluster.startStaticNode(1, false);
            cluster.startStaticNode(2, true);

            final TestNode newLeader = cluster.awaitLeader();

            assertNotEquals(newLeader.index(), is(2));

            assertTrue(cluster.node(0).service().wasSnapshotLoaded());
            assertTrue(cluster.node(1).service().wasSnapshotLoaded());
            assertFalse(cluster.node(2).service().wasSnapshotLoaded());

            cluster.awaitMessageCountForService(cluster.node(2), messageCount);
            cluster.awaitSnapshotCounter(cluster.node(2), 1);
            assertTrue(cluster.node(2).service().wasSnapshotTaken());
        }
    }

    @Test(timeout = 30_000)
    public void shouldCatchUpAfterFollowerMissesOneMessage() throws Exception
    {
        shouldCatchUpAfterFollowerMissesMessage(TestMessages.NO_OP);
    }

    @Test(timeout = 30_000)
    public void shouldCatchUpAfterFollowerMissesTimerRegistration() throws Exception
    {
        shouldCatchUpAfterFollowerMissesMessage(TestMessages.REGISTER_TIMER);
    }

    @Test(timeout = 30_000)
    public void shouldCatchUpTwoFreshNodesAfterRestart() throws Exception
    {
        try (TestCluster cluster = TestCluster.startThreeNodeStaticCluster(NULL_VALUE))
        {
            final TestNode leader = cluster.awaitLeader();
            final List<TestNode> followers = cluster.followers();

            cluster.connectClient();
            final int messageCount = 50_000;
            for (int i = 0; i < messageCount; i++)
            {
                cluster.msgBuffer().putStringWithoutLengthAscii(0, TestMessages.NO_OP);
                cluster.sendMessage(TestMessages.NO_OP.length());
            }
            cluster.awaitResponses(messageCount);

            cluster.node(0).terminationExpected(true);
            cluster.node(1).terminationExpected(true);
            cluster.node(2).terminationExpected(true);

            cluster.abortCluster(leader);
            cluster.awaitNodeTermination(cluster.node(0));
            cluster.awaitNodeTermination(cluster.node(1));
            cluster.awaitNodeTermination(cluster.node(2));

            cluster.stopNode(cluster.node(0));
            cluster.stopNode(cluster.node(1));
            cluster.stopNode(cluster.node(2));

            final TestNode oldLeader = cluster.startStaticNode(leader.index(), false);
            final TestNode oldFollower1 = cluster.startStaticNode(followers.get(0).index(), true);
            final TestNode oldFollower2 = cluster.startStaticNode(followers.get(1).index(), true);

            cluster.awaitLeader();

            assertThat(oldLeader.errors(), is(0L));
            assertThat(oldFollower1.errors(), is(0L));
            assertThat(oldFollower2.errors(), is(0L));

            assertThat(oldFollower1.electionState(), is((Election.State)null));
            assertThat(oldFollower2.electionState(), is((Election.State)null));
        }
    }

    @Test(timeout = 30_000)
    public void shouldReplayMultipleSnapshotsWithEmptyFollowerLog() throws Exception
    {
        try (TestCluster cluster = TestCluster.startThreeNodeStaticCluster(NULL_VALUE))
        {
            final TestNode leader = cluster.awaitLeader();
            final List<TestNode> followers = cluster.followers();
            final TestNode followerA = followers.get(0);
            final TestNode followerB = followers.get(1);

            cluster.connectClient();

            cluster.sendMessages(2);
            cluster.awaitResponses(2);
            cluster.awaitMessageCountForService(cluster.node(2), 2);

            cluster.takeSnapshot(leader);
            final int memberCount = 3;
            for (int memberId = 0; memberId < memberCount; memberId++)
            {
                final TestNode node = cluster.node(memberId);
                cluster.awaitSnapshotCounter(node, 1);
                assertTrue(node.service().wasSnapshotTaken());
                node.service().resetSnapshotTaken();
            }

            cluster.sendMessages(1);
            cluster.awaitResponses(3);
            cluster.awaitMessageCountForService(cluster.node(2), 3);

            leader.terminationExpected(true);
            followerA.terminationExpected(true);
            followerB.terminationExpected(true);

            cluster.awaitNeutralControlToggle(leader);
            cluster.shutdownCluster(leader);
            cluster.awaitNodeTermination(cluster.node(0));
            cluster.awaitNodeTermination(cluster.node(1));
            cluster.awaitNodeTermination(cluster.node(2));

            assertTrue(cluster.node(0).service().wasSnapshotTaken());
            assertTrue(cluster.node(1).service().wasSnapshotTaken());
            assertTrue(cluster.node(2).service().wasSnapshotTaken());

            cluster.stopNode(cluster.node(0));
            cluster.stopNode(cluster.node(1));
            cluster.stopNode(cluster.node(2));
            Thread.sleep(1_000);

            cluster.startStaticNode(0, false);
            cluster.startStaticNode(1, false);
            cluster.startStaticNode(2, true);

            final TestNode newLeader = cluster.awaitLeader();

            assertNotEquals(2, newLeader.index());

            assertTrue(cluster.node(0).service().wasSnapshotLoaded());
            assertTrue(cluster.node(1).service().wasSnapshotLoaded());
            assertFalse(cluster.node(2).service().wasSnapshotLoaded());

            assertEquals(3, cluster.node(0).service().messageCount());
            assertEquals(3, cluster.node(1).service().messageCount());
            assertEquals(3, cluster.node(2).service().messageCount());

            cluster.reconnectClient();

            final int msgCountAfterStart = 4;
            final int totalMsgCount = 2 + 1 + 4;
            cluster.sendMessages(msgCountAfterStart);
            cluster.awaitResponses(totalMsgCount);
            cluster.awaitMessageCountForService(newLeader, totalMsgCount);
            assertEquals(totalMsgCount, newLeader.service().messageCount());

            cluster.awaitMessageCountForService(cluster.node(1), totalMsgCount);
            assertEquals(totalMsgCount, cluster.node(1).service().messageCount());

            cluster.awaitMessageCountForService(cluster.node(2), totalMsgCount);
            assertEquals(totalMsgCount, cluster.node(2).service().messageCount());
        }
    }

    @Test(timeout = 30_000)
    public void shouldRecoverQuicklyAfterKillingFollowersThenRestartingOne() throws Exception
    {
        try (TestCluster cluster = TestCluster.startThreeNodeStaticCluster(NULL_VALUE))
        {
            cluster.awaitLeader();

            final TestNode leader = cluster.findLeader();
            final TestNode follower = cluster.followers().get(0);
            final TestNode follower2 = cluster.followers().get(1);

            cluster.connectClient();
            cluster.sendMessages(10);

            cluster.stopNode(follower);
            cluster.stopNode(follower2);

            while (leader.role() != Cluster.Role.FOLLOWER)
            {
                Thread.sleep(1_000);
                cluster.sendMessages(1);
            }

            cluster.startStaticNode(follower2.index(), true);
            cluster.awaitLeader();
        }
    }

    @Test(timeout = 30_000)
    public void shouldReplaceStaticNodeWithAnotherOne() throws Exception
    {
        TestNode testNode = null;
        try (TestCluster cluster = TestCluster.startThreeNodeStaticCluster(NULL_VALUE))
        {
            TestNode leader = cluster.awaitLeader();
            final TestNode follower = cluster.followers().get(0);
            final TestNode follower2 = cluster.followers().get(1);
            final int followerMemberId = follower.clusterMembership().memberId;
            System.out.println(leader.clusterMembership().activeMembersStr);

            cluster.stopNode(follower);
            leader.removeMember(followerMemberId, false);

            leader = cluster.awaitLeader();
            final String staticClusterMembers = leader.clusterMembership().activeMembersStr;
            System.out.println(staticClusterMembers);

            final int memberId = 6;
            final String memberEndpoints = memberEndpoints(memberId);
            leader.addMember(memberId, memberEndpoints);
            testNode = startStaticNode(memberId, staticClusterMembers + "|" + memberId + "," + memberEndpoints, true);

            leader = cluster.awaitLeader();
            System.out.println(leader.clusterMembership().activeMembersStr);

            cluster.connectClient();

            final int messageCount = 10;
            cluster.sendMessages(messageCount);
            cluster.awaitResponses(messageCount);

            assertThat(leader.service().messageCount(), is(messageCount));
            assertThat(follower2.service().messageCount(), is(messageCount));
            assertThat(testNode.service().messageCount(), is(messageCount));
        } finally
        {
            CloseHelper.quietClose(testNode);
        }
    }


    @Test(timeout = 30_000)
    public void shouldAddStaticMemberAtRuntime() throws Exception
    {
        List<TestNode> nodes = new ArrayList<>();
        try (TestCluster cluster = TestCluster.startSingleNodeStaticCluster())
        {
            TestNode leader = cluster.awaitLeader();

            int memberIdCounter = leader.clusterMembership().memberId;
            String staticClusterMembers = leader.clusterMembership().activeMembersStr;
            System.out.println(staticClusterMembers);

            for (int i = 0; i < 2; i++)
            {
                final int memberId = ++memberIdCounter;
                final String memberEndpoints = memberEndpoints(memberId);
                staticClusterMembers = staticClusterMembers + "|" + memberId + "," + memberEndpoints;

                leader = cluster.awaitLeader();
                leader.addMember(memberId, memberEndpoints);


                System.out.println(staticClusterMembers);
                final TestNode node = startStaticNode(memberId, staticClusterMembers, true);
                System.out.println(node.clusterMembership().activeMembersStr);
                nodes.add(node);
            }

            leader = cluster.awaitLeader();

            System.out.println(leader.index());
            System.out.println(leader.clusterMembership().activeMembersStr);

            cluster.connectClient();

            final int messageCount = 10;
            final int previousSent = leader.service().messageCount();
            System.out.println("previousSent = " + previousSent);
            cluster.sendMessages(messageCount);
            cluster.awaitResponses(messageCount);

            assertThat(leader.service().messageCount(), is(previousSent + messageCount));
            for (TestNode node : nodes)
            {
                assertThat(node.service().messageCount(), is(previousSent + messageCount));
            }
        } finally
        {
            CloseHelper.quietCloseAll(nodes);
        }
    }

    @Test(timeout = 60_000)
    public void shouldAddStaticMemberAtRuntime2() throws Exception
    {
        List<TestNode> nodes = new ArrayList<>();
        try (TestCluster cluster = TestCluster.startSingleNodeStaticCluster())
        {
            TestNode leader = cluster.awaitLeader();

            int memberIdCounter = leader.clusterMembership().memberId;
            String staticClusterMembers = leader.clusterMembership().activeMembersStr;
            System.out.println(staticClusterMembers);

            for (int i = 0; i < 2; i++)
            {
                final int memberId = ++memberIdCounter;
                final String memberEndpoints = memberEndpoints(memberId);
                staticClusterMembers = staticClusterMembers + "|" + memberId + "," + memberEndpoints;

                leader = cluster.awaitLeader();
                leader.addMember(memberId, memberEndpoints);


                System.out.println(staticClusterMembers);
                final TestNode node = startStaticNode(memberId, staticClusterMembers, true);
                System.out.println(node.clusterMembership().activeMembersStr);
                nodes.add(node);
            }

            leader = cluster.awaitLeader();

            System.out.println(leader.index());
            System.out.println(leader.clusterMembership().activeMembersStr);


            final TestNode removedNode = nodes.remove(0);
            int removedMemberId = removedNode.clusterMembership().memberId;
            CloseHelper.quietClose(removedNode);
            leader = cluster.awaitLeader();
            leader.removeMember(removedMemberId, false);

//            Thread.sleep(5000);
            {
                leader = cluster.awaitLeader();
                staticClusterMembers = leader.clusterMembership().activeMembersStr;

//                final int memberId = ++memberIdCounter;
                final int memberId = removedMemberId;
                final String memberEndpoints = memberEndpoints(memberId);
                staticClusterMembers = staticClusterMembers + "|" + memberId + "," + memberEndpoints;

                leader = cluster.awaitLeader();
                leader.addMember(memberId, memberEndpoints);

//                Thread.sleep(5000);

                System.out.println(staticClusterMembers);
                final TestNode node = startStaticNode(memberId, staticClusterMembers, true);
                System.out.println(node.clusterMembership().activeMembersStr);
                nodes.add(node);

            }

//            Thread.sleep(5000);

            leader = cluster.awaitLeader();


            cluster.connectClient();

            final int messageCount = 10;
            final int previousSent = leader.service().messageCount();
            System.out.println("previousSent = " + previousSent);
            cluster.sendMessages(messageCount);
            cluster.awaitResponses(messageCount);

            assertThat(leader.service().messageCount(), is(previousSent + messageCount));
            for (TestNode node : nodes)
            {
                System.err.println(node.service().index());
                assertThat(node.service().messageCount(), is(previousSent + messageCount));
            }
        } finally
        {
            CloseHelper.quietCloseAll(nodes);
        }
    }


    @Test(timeout = 30_000)
    public void shouldReplaceStaticNodeWithAnotherOne2() throws Exception
    {
        TestNode testNode = null;
        try (TestCluster cluster = TestCluster.startThreeNodeStaticCluster(NULL_VALUE))
        {
            TestNode leader = cluster.awaitLeader();
            final TestNode follower = cluster.followers().get(0);
//            final TestNode follower2 = cluster.followers().get(1);

            String staticClusterMembers = leader.clusterMembership().activeMembersStr;
            System.out.println(staticClusterMembers);

            final int memberId = follower.clusterMembership().memberId;
//            final ClusterMember[] clusterMembers = ClusterMember.parse(cluster.staticClusterMembers());
//            final String memberEndpoints = ClusterMember.findMember(clusterMembers, memberId).endpointsDetail();

//            cluster.connectClient();

//            final int messageCount = 10;
//            cluster.sendMessages(messageCount);
//            cluster.awaitResponses(messageCount);
//
//            Thread.sleep(1000);
//
//            assertThat(follower.service().messageCount(), is(messageCount));


            cluster.stopNode(follower);
            leader.removeMember(memberId, false);


            leader = cluster.awaitLeader();
            staticClusterMembers = leader.clusterMembership().activeMembersStr;
            System.out.println(staticClusterMembers);

            leader.addMember(memberId, memberEndpoints(memberId));

            testNode = startStaticNode(memberId, staticClusterMembers + "|" + memberId + "," + memberEndpoints(memberId), true);

//            Thread.sleep(2000);

            leader = cluster.awaitLeader();
            System.out.println(leader.clusterMembership().activeMembersStr);

            cluster.connectClient();
            final int messageCount = 10;
            cluster.sendMessages(messageCount);
            cluster.awaitResponses(messageCount);

            Thread.sleep(3000);

            assertThat(leader.service().messageCount(), is(messageCount));
//            assertThat(follower2.service().messageCount(), is(messageCount));
            assertThat(testNode.service().messageCount(), is(messageCount));
        } finally
        {
            CloseHelper.quietClose(testNode);
        }
    }

    private void shouldCatchUpAfterFollowerMissesMessage(final String message) throws InterruptedException
    {
        try (TestCluster cluster = TestCluster.startThreeNodeStaticCluster(NULL_VALUE))
        {
            cluster.awaitLeader();

            TestNode follower = cluster.followers().get(0);

            cluster.stopNode(follower);

            Thread.sleep(1_000);

            cluster.connectClient();
            cluster.msgBuffer().putStringWithoutLengthAscii(0, message);
            cluster.sendMessage(message.length());
            cluster.awaitResponses(1);

            Thread.sleep(1_000);

            follower = cluster.startStaticNode(follower.index(), false);

            Thread.sleep(1_000);

            assertThat(follower.role(), is(Cluster.Role.FOLLOWER));
            assertThat(follower.electionState(), is((Election.State)null));
        }
    }

    private int countersOfType(final CountersReader countersReader, final int typeIdToCount)
    {
        final MutableInteger count = new MutableInteger();

        countersReader.forEach(
            (counterId, typeId, keyBuffer, label) ->
            {
                if (typeId == typeIdToCount)
                {
                    count.value++;
                }
            });

        return count.get();
    }

    private Thread startMessageThread(final TestCluster cluster, final long intervalNs)
    {
        final Thread thread = new Thread(
            () ->
            {
                final IdleStrategy idleStrategy = YieldingIdleStrategy.INSTANCE;
                cluster.msgBuffer().putStringWithoutLengthAscii(0, MSG);

                while (!Thread.interrupted())
                {
                    if (cluster.client().offer(cluster.msgBuffer(), 0, MSG.length()) < 0)
                    {
                        LockSupport.parkNanos(intervalNs);
                    }

                    idleStrategy.idle(cluster.client().pollEgress());
                }
            });

        thread.setDaemon(true);
        thread.setName("message-thread");

        return thread;
    }

    private static final long MAX_CATALOG_ENTRIES = 128;
    private static final String LOG_CHANNEL =
        "aeron:udp?term-length=256k|control-mode=manual|control=localhost:20550";
    private static final String ARCHIVE_CONTROL_REQUEST_CHANNEL =
        "aeron:udp?term-length=64k|endpoint=localhost:8010";
    private static final String ARCHIVE_CONTROL_RESPONSE_CHANNEL =
        "aeron:udp?term-length=64k|endpoint=localhost:8020";
    private static final String ARCHIVE_RECORDING_EVENTS_CHANNEL =
        "aeron:udp?control-mode=dynamic|control=localhost:8030";

    private TestNode startStaticNode(final int index, final String staticClusterMembers, final boolean cleanStart)
    {
        final String baseDirName = CommonContext.getAeronDirectoryName() + "-" + index;
        final String aeronDirName = CommonContext.getAeronDirectoryName() + "-" + index + "-driver";
        final TestNode.Context context = new TestNode.Context(new TestNode.TestService().index(index));

        context.aeronArchiveContext
            .controlRequestChannel(memberSpecificPort(ARCHIVE_CONTROL_REQUEST_CHANNEL, index))
            .controlRequestStreamId(100)
            .controlResponseChannel(memberSpecificPort(ARCHIVE_CONTROL_RESPONSE_CHANNEL, index))
            .controlResponseStreamId(110 + index)
            .recordingEventsChannel(memberSpecificPort(ARCHIVE_RECORDING_EVENTS_CHANNEL, index))
            .aeronDirectoryName(baseDirName);

        context.mediaDriverContext
            .aeronDirectoryName(aeronDirName)
            .threadingMode(ThreadingMode.SHARED)
            .termBufferSparseFile(true)
            .multicastFlowControlSupplier(new MinMulticastFlowControlSupplier())
            .errorHandler(TestUtil.errorHandler(index))
            .dirDeleteOnShutdown(true)
            .dirDeleteOnStart(true);

        context.archiveContext
            .maxCatalogEntries(MAX_CATALOG_ENTRIES)
            .aeronDirectoryName(aeronDirName)
            .archiveDir(new File(baseDirName, "archive"))
            .controlChannel(context.aeronArchiveContext.controlRequestChannel())
            .controlStreamId(context.aeronArchiveContext.controlRequestStreamId())
            .localControlChannel("aeron:ipc?term-length=64k")
            .recordingEventsEnabled(false)
            .localControlStreamId(context.aeronArchiveContext.controlRequestStreamId())
            .recordingEventsChannel(context.aeronArchiveContext.recordingEventsChannel())
            .threadingMode(ArchiveThreadingMode.SHARED)
            .deleteArchiveOnStart(cleanStart);

        context.consensusModuleContext
            .errorHandler(TestUtil.errorHandler(index))
            .clusterMemberId(index)
            .clusterMembers(staticClusterMembers)
            .appointedLeaderId(NULL_VALUE)
            .aeronDirectoryName(aeronDirName)
            .clusterDir(new File(baseDirName, "consensus-module"))
            .ingressChannel("aeron:udp?term-length=64k")
            .logChannel(memberSpecificPort(LOG_CHANNEL, index))
            .archiveContext(context.aeronArchiveContext.clone())
            .deleteDirOnStart(cleanStart);

        context.serviceContainerContext
            .aeronDirectoryName(aeronDirName)
            .archiveContext(context.aeronArchiveContext.clone())
            .clusterDir(new File(baseDirName, "service"))
            .clusteredService(context.service)
            .errorHandler(TestUtil.errorHandler(index));

        return new TestNode(context);
    }

    private static String memberSpecificPort(final String channel, final int memberId)
    {
        return channel.substring(0, channel.length() - 1) + memberId;
    }

    private static String memberEndpoints(final int i)
    {
        return
            "localhost:2011" + i + ',' +
            "localhost:2022" + i + ',' +
            "localhost:2033" + i + ',' +
            "localhost:2044" + i + ',' +
            "localhost:801" + i;
    }
}
