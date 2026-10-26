package com.dalae37.logreedy;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class QueueTest {
    @Test public void DurablePutAckAndRestart() throws Exception {
        try (Fixture fixture = new Fixture()) {
            try (DiskQueue queue = fixture.Queue()) { queue.Put(fixture.Event(5, "hello")); assertEquals(5, queue.Cursors().get(0).offset); }
            try (DiskQueue queue = fixture.Queue()) { assertEquals("hello", queue.Batch(10, 10000).get(0).message); queue.Ack(queue.Batch(10, 10000)); }
            try (DiskQueue queue = fixture.Queue()) { assertEquals(0, queue.Pending()); assertEquals(5, queue.Cursors().get(0).offset); }
        }
    }
    @Test public void CapacityDoesNotAdvanceCommit() throws Exception {
        try (Fixture fixture = new Fixture()) {
            AgentConfig config = fixture.Config();
            try (DiskQueue queue = new DiskQueue(config.spool, config.checkpoint, 12000, 128)) {
                int accepted = 0;
                try { for (int index = 1; index <= 100; index++) { queue.Put(fixture.Event(index, "x".repeat(1000))); accepted++; } fail(); }
                catch (DiskQueue.FullException expected) { assertTrue(accepted > 0); }
                assertEquals(accepted, queue.CursorFor("file-1").offset);
                queue.Ack(queue.Batch(100, 1000000)); queue.Put(fixture.Event(200, "after reclaim"));
                assertEquals(1, queue.Pending());
            }
        }
    }
    @Test public void DiskFullDuringWriteFailsClosedAndRecoversPreviousBoundary() throws Exception {
        try (Fixture fixture = new Fixture()) {
            AgentConfig config = fixture.Config(); AtomicBoolean armed = new AtomicBoolean();
            Faults disk = point -> { if (armed.get() && point.equals("put.partial")) { throw new IOException("injected ENOSPC"); } };
            try (DiskQueue queue = new DiskQueue(config.spool, config.checkpoint, 131072, 128, disk)) {
                queue.Put(fixture.Event(5, "before")); armed.set(true);
                try { queue.Put(fixture.Event(10, "after")); fail(); } catch (IOException expected) { assertFalse(queue.Healthy()); }
                assertEquals(5, queue.CursorFor("file-1").offset);
            }
            try (DiskQueue queue = fixture.Queue()) { assertEquals(1, queue.Pending()); assertEquals(5, queue.CursorFor("file-1").offset); }
        }
    }
    @Test public void CompleteCorruptFrameStopsWithoutDeletingData() throws Exception {
        try (Fixture fixture = new Fixture()) {
            try (DiskQueue queue = fixture.Queue()) { queue.Put(fixture.Event(5, "hello")); }
            Path wal = fixture.root.resolve("data/spool/queue.wal"); long size = Files.size(wal);
            try (RandomAccessFile file = new RandomAccessFile(wal.toFile(), "rw")) { file.seek(size - 1); int previous = file.read(); file.seek(size - 1); file.write(previous ^ 255); }
            try { fixture.Queue(); fail(); } catch (IOException expected) { assertTrue(expected.getMessage().contains("checksum")); }
            assertEquals(size, Files.size(wal));
        }
    }
    @Test public void DuplicateLockIsRejected() throws Exception {
        try (Fixture fixture = new Fixture(); DiskQueue queue = fixture.Queue()) {
            try { fixture.Queue(); fail(); } catch (DiskQueue.LockedException expected) { assertEquals(0, queue.Pending()); }
            assertEquals(4, new Agent(fixture.Config(), PlatformExecution::new).Run());
        }
    }
    @Test public void ActualProcessHaltsAtDurabilityBoundaries() throws Exception {
        for (String phase : Arrays.asList("put.partial", "put.forced", "checkpoint.partial", "checkpoint.forced", "ack.partial", "ack.forced", "compact.partial")) {
            try (Fixture fixture = new Fixture()) {
                fixture.Config(); Process process = fixture.Probe(phase);
                assertTrue(phase, process.waitFor(20, TimeUnit.SECONDS)); assertEquals(phase, 77, process.exitValue());
                // Even a damaged derived checkpoint must not override the WAL.
                Files.write(fixture.root.resolve("data/checkpoint/state.bin"), new byte[] {1, 2});
                try (DiskQueue queue = fixture.Queue()) {
                    boolean beforePut = phase.equals("put.partial");
                    boolean afterAck = phase.equals("ack.forced") || phase.equals("compact.partial");
                    assertEquals(phase, beforePut || afterAck ? 0 : 1, queue.Pending());
                    if (!beforePut) { assertEquals(5, queue.CursorFor("file-1").offset); }
                }
            }
        }
    }
    @Test public void LokiAcceptedBeforeLocalAckMayDuplicateButNeverLose() throws Exception {
        try (Fixture fixture = new Fixture(); Fixture.Mock mock = new Fixture.Mock()) {
            fixture.properties.setProperty("loki.url", mock.Url()); fixture.Config();
            Process process = fixture.Probe("loki.accepted");
            assertTrue(process.waitFor(20, TimeUnit.SECONDS)); assertEquals(77, process.exitValue());
            assertEquals(Arrays.asList("hello"), mock.Messages());
            try (DiskQueue queue = fixture.Queue()) {
                assertEquals(1, queue.Pending());
                LokiClient.Result result = new LokiClient(fixture.Config()).Send(queue.Batch(10, 10000));
                assertTrue(result.Success()); queue.Ack(queue.Batch(10, 10000));
            }
            assertEquals(Arrays.asList("hello", "hello"), mock.Messages());
        }
    }

    @Test public void QuarantineReplayNeverRewindsLatestCheckpointAndSurvivesRestart() throws Exception {
        try (Fixture fixture = new Fixture()) {
            long nanos;
            try (DiskQueue queue = fixture.Queue()) {
                Event first = fixture.Event(5, "bad"); queue.Put(first); queue.Quarantine(first);
                Event second = fixture.Event(10, "good"); queue.Put(second); nanos = second.nanos;
                queue.Ack(queue.Batch(10, 10000)); assertEquals(1, queue.ReplayQuarantine());
                assertEquals(10, queue.CursorFor("file-1").offset); assertTrue(queue.Batch(10, 10000).get(0).nanos > nanos);
            }
            try (DiskQueue queue = fixture.Queue()) {
                assertEquals(1, queue.Pending()); assertEquals(0, queue.Quarantined()); assertEquals(10, queue.CursorFor("file-1").offset);
            }
        }
    }
}
