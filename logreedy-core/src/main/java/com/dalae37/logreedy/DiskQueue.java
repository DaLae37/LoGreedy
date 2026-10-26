package com.dalae37.logreedy;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32;

/** Version 1 framed WAL: PUT embeds event+cursor; ACK never advances a cursor. */
public final class DiskQueue implements Closeable {
    private static final int MAGIC = 0x4C475131;
    private final Path directory, checkpoint;
    private final long maximum;
    private final int maxCursors;
    private final Faults faults;
    private final LinkedHashMap<Long, Event> pending = new LinkedHashMap<>();
    private final LinkedHashMap<Long, Event> quarantined = new LinkedHashMap<>();
    private final Set<String> sources = new LinkedHashSet<>();
    private final LinkedHashMap<String, Cursor> cursors = new LinkedHashMap<>();
    private RandomAccessFile journal;
    private FileChannel lockChannel;
    private FileLock lock;
    private long nextId = 1, lastNanos;
    private boolean failed;

    public DiskQueue(Path directory, Path checkpoint, long maximum, int maxCursors) throws IOException {
        this(directory, checkpoint, maximum, maxCursors, Faults.NONE);
    }

    DiskQueue(Path directory, Path checkpoint, long maximum, int maxCursors, Faults faults) throws IOException {
        this.directory = directory; this.checkpoint = checkpoint; this.maximum = maximum;
        this.maxCursors = maxCursors; this.faults = faults;
        Files.createDirectories(directory); Files.createDirectories(checkpoint.getParent());
        try {
            lockChannel = FileChannel.open(directory.resolve("agent.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try { lock = lockChannel.tryLock(); }
            catch (OverlappingFileLockException exception) { throw new LockedException(); }
            if (lock == null) { throw new LockedException(); }
            Files.deleteIfExists(directory.resolve("queue.compact"));
            Path path = directory.resolve("queue.wal");
            journal = new RandomAccessFile(path.toFile(), "rw");
            if (journal.length() == 0) { journal.writeInt(MAGIC); journal.writeInt(1); journal.getFD().sync(); SyncDirectory(directory); }
            Recover(); ExportCheckpoint();
        } catch (IOException | RuntimeException exception) {
            close(); throw exception;
        }
    }

    public synchronized List<Cursor> Cursors() {
        List<Cursor> result = new ArrayList<>();
        for (Cursor cursor : cursors.values()) { result.add(cursor.Copy()); }
        return result;
    }
    public synchronized Cursor CursorFor(String id) { Cursor value = cursors.get(id); return value == null ? null : value.Copy(); }
    public synchronized boolean HasSource(String source) { return sources.contains(source); }
    public synchronized int Pending() { return pending.size(); }
    public synchronized int Quarantined() { return quarantined.size(); }
    public synchronized boolean Healthy() { return !failed; }
    public synchronized long Bytes() throws IOException { return journal.length(); }

    public synchronized void CommitInitial(Cursor cursor) throws IOException {
        CheckCursor(cursor);
        byte[] record = Record(3, cursor, null, 0);
        EnsureSpace(record.length + 8);
        Append(record, "initial"); cursors.put(cursor.id, cursor.Copy()); sources.add(cursor.source); ExportCheckpoint();
    }

    public synchronized void Put(Event event) throws IOException {
        CheckCursor(event.cursor);
        event.id = nextId;
        event.nanos = Math.max(event.nanos, lastNanos + 1);
        byte[] record = Record(1, null, event, 0);
        EnsureSpace(record.length + 8);
        Append(record, "put");
        ApplyPut(event); ExportCheckpoint();
    }

    public synchronized List<Event> Batch(int events, int bytes) {
        List<Event> batch = new ArrayList<>(); int count = 0;
        if (failed) { return batch; }
        for (Event event : pending.values()) {
            int size = event.message.length() * 6 + 2048;
            if (!batch.isEmpty() && count + size > bytes) { break; }
            batch.add(event); count += size;
            if (batch.size() == events) { break; }
        }
        return batch;
    }

    public synchronized void Ack(List<Event> batch) throws IOException {
        for (Event event : batch) {
            Append(Record(2, null, null, event.id), "ack"); pending.remove(event.id);
        }
        Compact();
    }

    public synchronized void Quarantine(Event event) throws IOException {
        Append(Record(4, null, null, event.id), "quarantine");
        pending.remove(event.id); quarantined.put(event.id, event); Compact();
    }

    public synchronized int ReplayQuarantine() throws IOException {
        int count = 0;
        for (Event event : new ArrayList<>(quarantined.values())) {
            event.nanos = Math.max(System.currentTimeMillis() * 1000000L, lastNanos + 1);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream output = new DataOutputStream(bytes);
            output.writeByte(7); event.Write(output);
            EnsureSpace(bytes.size() + 8L); Append(bytes.toByteArray(), "replay");
            pending.put(event.id, event); quarantined.remove(event.id); lastNanos = event.nanos; count++;
        }
        Compact(); return count;
    }
    public synchronized void Forget(String id) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes); output.writeByte(6); Codec.String(output, id);
        Append(bytes.toByteArray(), "forget"); cursors.remove(id); Compact(); ExportCheckpoint();
    }
    private byte[] Forgotten(String id) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream output = new DataOutputStream(bytes);
        output.writeByte(6); Codec.String(output, id); return bytes.toByteArray();
    }
    private void CheckCursor(Cursor cursor) throws IOException {
        if (failed) { throw new IOException("Queue requires restart after an I/O failure"); }
        if (!cursors.containsKey(cursor.id) && cursors.size() >= maxCursors) { throw new FullException("Cursor limit reached"); }
    }
    private void EnsureSpace(long bytes) throws IOException {
        if (journal.length() + bytes + 4096 > maximum / 2) { Compact(); }
        if (journal.length() + bytes + 4096 > maximum / 2) { throw new FullException("Spool capacity reached"); }
    }
    private byte[] Record(int type, Cursor cursor, Event event, long id) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(buffer); output.writeByte(type);
        if (type == 1) { event.Write(output); }
        else if (type == 3) { cursor.Write(output); }
        else if (type == 5) { output.writeLong(nextId); output.writeLong(lastNanos); }
        else { output.writeLong(id); }
        output.flush(); return buffer.toByteArray();
    }
    private void Append(byte[] bytes, String phase) throws IOException {
        if (failed) { throw new IOException("Queue is unhealthy"); }
        if ((phase.equals("ack") || phase.equals("quarantine") || phase.equals("forget"))
                && journal.length() + bytes.length + 8L > maximum / 2) { Compact(); }
        try { WriteFrame(journal, bytes, phase); journal.getFD().sync(); faults.At(phase + ".forced"); }
        catch (IOException exception) { failed = true; throw exception; }
    }
    private void WriteFrame(RandomAccessFile output, byte[] bytes, String phase) throws IOException {
        if (phase.equals("compact") && output.length() + journal.length() + bytes.length + 8L + 4096 > maximum) {
            throw new IOException("Insufficient spool compaction reserve");
        }
        CRC32 crc = new CRC32(); crc.update(bytes);
        output.seek(output.length()); output.writeInt(bytes.length);
        output.write(bytes, 0, bytes.length / 2); faults.At(phase + ".partial");
        output.write(bytes, bytes.length / 2, bytes.length - bytes.length / 2); output.writeInt((int) crc.getValue());
    }
    private void Recover() throws IOException {
        journal.seek(0);
        if (journal.readInt() != MAGIC || journal.readInt() != 1) { throw new IOException("Unsupported queue format; preserve spool for migration"); }
        while (journal.getFilePointer() < journal.length()) {
            long start = journal.getFilePointer();
            if (journal.length() - start < 4) { TruncateTail(start); break; }
            int size = journal.readInt();
            if (size < 1 || size > maximum || size > 67108864) { throw new IOException("Corrupt WAL frame length at " + start); }
            if (journal.length() - journal.getFilePointer() < size + 4L) { TruncateTail(start); break; }
            byte[] bytes = new byte[size]; journal.readFully(bytes);
            CRC32 crc = new CRC32(); crc.update(bytes);
            if (journal.readInt() != (int) crc.getValue()) { throw new IOException("WAL checksum mismatch at " + start); }
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes));
            int type = input.readUnsignedByte();
            if (type == 1) { ApplyPut(Event.Read(input)); }
            else if (type == 2) { pending.remove(input.readLong()); }
            else if (type == 3) { Cursor cursor = Cursor.Read(input); cursors.put(cursor.id, cursor); sources.add(cursor.source); }
            else if (type == 4) { long id = input.readLong(); Event event = pending.remove(id); if (event != null) { quarantined.put(id, event); } }
            else if (type == 8) { sources.add(Codec.String(input)); }
            else if (type == 7) {
                Event event = Event.Read(input); pending.put(event.id, event); quarantined.remove(event.id);
                nextId = Math.max(nextId, event.id + 1); lastNanos = Math.max(lastNanos, event.nanos);
            }
            else if (type == 6) { cursors.remove(Codec.String(input)); }
            else if (type == 5) { nextId = Math.max(nextId, input.readLong()); lastNanos = Math.max(lastNanos, input.readLong()); }
            else { throw new IOException("Unsupported WAL record type " + type); }
            if (input.available() != 0 || cursors.size() > maxCursors) { throw new IOException("Invalid WAL state or reduced cursor limit"); }
        }
    }
    private void ApplyPut(Event event) {
        pending.put(event.id, event); cursors.put(event.cursor.id, event.cursor.Copy()); sources.add(event.cursor.source);
        nextId = Math.max(nextId, event.id + 1); lastNanos = Math.max(lastNanos, event.nanos);
    }
    private void TruncateTail(long size) throws IOException { journal.setLength(size); journal.getFD().sync(); }

    private void Compact() throws IOException {
        if (failed) { throw new IOException("Queue is unhealthy"); }
        Path temp = directory.resolve("queue.compact");
        try {
            try (RandomAccessFile output = new RandomAccessFile(temp.toFile(), "rw")) {
                output.setLength(0); output.writeInt(MAGIC); output.writeInt(1);
                WriteFrame(output, Record(5, null, null, 0), "compact");
                for (Event event : pending.values()) { WriteFrame(output, Record(1, null, event, 0), "compact"); }
                for (Event event : quarantined.values()) {
                    WriteFrame(output, Record(1, null, event, 0), "compact");
                    WriteFrame(output, Record(4, null, null, event.id), "compact");
                }
                for (Event event : pending.values()) {
                    if (!cursors.containsKey(event.cursor.id)) { WriteFrame(output, Forgotten(event.cursor.id), "compact"); }
                }
                for (Event event : quarantined.values()) {
                    if (!cursors.containsKey(event.cursor.id)) { WriteFrame(output, Forgotten(event.cursor.id), "compact"); }
                }
                for (Cursor cursor : cursors.values()) { WriteFrame(output, Record(3, cursor, null, 0), "compact"); }
                if (output.length() + journal.length() + 4096 > maximum) { throw new IOException("Insufficient spool compaction reserve"); }
                for (String source : sources) {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream data = new DataOutputStream(bytes);
                    data.writeByte(8); Codec.String(data, source); WriteFrame(output, bytes.toByteArray(), "compact");
                }
                output.getFD().sync();
            }
            journal.close(); journal = null;
            AtomicReplace(temp, directory.resolve("queue.wal"));
            journal = new RandomAccessFile(directory.resolve("queue.wal").toFile(), "rw");
        } catch (IOException exception) { failed = true; throw exception; }
    }
    private void ExportCheckpoint() throws IOException {
        Path temp = checkpoint.resolveSibling(checkpoint.getFileName() + ".tmp");
        try {
            try (RandomAccessFile output = new RandomAccessFile(temp.toFile(), "rw")) {
                output.setLength(0); output.writeInt(MAGIC); output.writeInt(1);
                for (Cursor cursor : cursors.values()) { WriteFrame(output, Record(3, cursor, null, 0), "checkpoint"); }
                output.getFD().sync(); faults.At("checkpoint.forced");
            }
            AtomicReplace(temp, checkpoint);
        } catch (IOException exception) { failed = true; throw exception; }
    }
    private static void AtomicReplace(Path from, Path to) throws IOException {
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        SyncDirectory(to.getParent());
    }
    private static void SyncDirectory(Path path) throws IOException {
        // Windows and some UNIX providers cannot open directories. File fsync and atomic move remain required.
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) { channel.force(true); }
        catch (AccessDeniedException | UnsupportedOperationException exception) { /* Documented platform durability limit. */ }
        catch (FileSystemException exception) { if (!System.getProperty("os.name").startsWith("Windows")) { throw exception; } }
    }
    @Override public synchronized void close() throws IOException {
        IOException error = null;
        if (journal != null) { try { journal.close(); } catch (IOException exception) { error = exception; } journal = null; }
        if (lock != null) { try { lock.release(); } catch (IOException exception) { error = exception; } lock = null; }
        if (lockChannel != null) { try { lockChannel.close(); } catch (IOException exception) { error = exception; } lockChannel = null; }
        if (error != null) { throw error; }
    }
    public static final class FullException extends IOException { FullException(String message) { super(message); } }
    public static final class LockedException extends IOException { LockedException() { super("Another agent holds this spool lock"); } }
}
