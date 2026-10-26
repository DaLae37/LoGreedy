package com.dalae37.logreedy;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.util.*;
import java.util.function.Consumer;

/** Polling, bounded reads, no retained file handles (including on Windows). */
public final class Tailer {
    interface Identity { String Key(Path path, BasicFileAttributes attributes); }
    private final AgentConfig config;
    private final DiskQueue queue;
    private final Identity identity;
    private final Consumer<String> log;
    private final Map<String, State> states = new LinkedHashMap<>();
    private boolean initialScan = true, blocked;
    private int blockedPending, blockedQuarantine;
    public long readBytes, committedEvents, backpressure;

    public Tailer(AgentConfig config, DiskQueue queue, Consumer<String> log) {
        this(config, queue, log, (path, attributes) -> attributes.fileKey() == null ? "" : attributes.fileKey().toString());
    }
    Tailer(AgentConfig config, DiskQueue queue, Consumer<String> log, Identity identity) {
        this.config = config; this.queue = queue; this.log = log; this.identity = identity;
        for (Cursor cursor : queue.Cursors()) {
            AgentConfig.Source source = Source(cursor.source);
            if (source != null) { states.put(cursor.id, new State(source, cursor)); }
        }
    }
    private AgentConfig.Source Source(String name) {
        for (AgentConfig.Source source : config.sources) { if (source.name.equals(name)) { return source; } }
        return null;
    }

    private void Block() {
        blocked = true; blockedPending = queue.Pending(); blockedQuarantine = queue.Quarantined(); backpressure++;
    }
    public void Poll() throws IOException {
        if (blocked && queue.Pending() == blockedPending && queue.Quarantined() == blockedQuarantine) { return; }
        blocked = false;
        for (AgentConfig.Source source : config.sources) {
            LinkedHashSet<Path> candidates = Discover(source);
            Set<Path> owned = new HashSet<>();
            for (State state : new ArrayList<>(states.values())) {
                if (state.source != source) { continue; }
                Path found = Find(state, candidates, owned);
                if (found == null && System.currentTimeMillis() - state.lastActivity > config.retireMs) {
                    log.accept("Retiring missing generation; uncommitted bytes require the original file");
                    queue.Forget(state.cursor.id); states.remove(state.cursor.id); continue;
                }
                if (found != null) {
                    owned.add(found); state.path = found;
                    try {
                        Read(state);
                        if (!Matches(source, found) && state.readOffset == state.cursor.offset
                                && System.currentTimeMillis() - state.lastActivity > config.retireMs) {
                            queue.Forget(state.cursor.id); states.remove(state.cursor.id);
                        }
                    }
                    catch (DiskQueue.FullException exception) { Reset(state); Block(); return; }
                    catch (FileNotFoundException exception) { Reset(state); log.accept("File unavailable during poll; retaining checkpoint"); }
                    catch (NoSuchFileException exception) { Reset(state); log.accept("File disappeared during poll; retrying discovery"); }
                    catch (AccessDeniedException exception) { Reset(state); log.accept("File access denied; keeping checkpoint"); }
                }
            }
            boolean knownSource = queue.HasSource(source.name);
            for (Path path : candidates) {
                if (owned.contains(path) || !Matches(source, path) || Protected(path)) { continue; }
                if (states.size() >= config.maxFiles) { log.accept("files.max reached; discovery paused"); break; }
                BasicFileAttributes attributes;
                try { attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); }
                catch (NoSuchFileException exception) { continue; }
                Cursor cursor = new Cursor(); cursor.id = UUID.randomUUID().toString(); cursor.source = source.name;
                cursor.path = path.toString(); cursor.key = identity.Key(path, attributes);
                cursor.offset = initialScan && !knownSource && source.initial.equals("end") ? attributes.size() : 0;
                cursor.modified = attributes.lastModifiedTime().toMillis();
                try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
                    cursor.prefixLength = (int) Math.min(256L, file.length());
                    cursor.prefix = Fingerprint(file, 0, cursor.prefixLength);
                    cursor.anchor = Anchor(file, cursor.offset);
                }
                try { queue.CommitInitial(cursor); }
                catch (DiskQueue.FullException exception) { Block(); return; }
                State state = new State(source, cursor); states.put(cursor.id, state); owned.add(path);
                try { Read(state); }
                catch (DiskQueue.FullException exception) { Reset(state); Block(); return; }
            }
        }
        initialScan = false;
    }

    private void Reset(State state) {
        Cursor cursor = queue.CursorFor(state.cursor.id);
        if (cursor != null) { states.put(cursor.id, new State(state.source, cursor)); }
    }
    private boolean Protected(Path path) throws IOException {
        Path actual = path.toRealPath();
        Path spool = config.spool.toRealPath();
        if (actual.startsWith(spool)) { return true; }
        Path name = actual.getFileName();
        Path logParent = config.agentLog.getParent().toRealPath();
        Path cpParent = config.checkpoint.getParent().toRealPath();
        return (actual.getParent().equals(logParent) && name.toString().startsWith(config.agentLog.getFileName().toString()))
                || (actual.getParent().equals(cpParent) && name.toString().startsWith(config.checkpoint.getFileName().toString()));
    }
    private LinkedHashSet<Path> Discover(AgentConfig.Source source) throws IOException {
        LinkedHashSet<Path> result = new LinkedHashSet<>();
        Set<Path> roots = new LinkedHashSet<>();
        for (Path pattern : source.patterns) {
            Path root = pattern.getRoot();
            for (Path part : pattern) {
                if (HasGlob(part.toString())) { break; }
                root = root.resolve(part);
            }
            if (root.equals(pattern)) { root = pattern.getParent(); }
            roots.add(root);
        }
        final int[] visited = {0};
        for (Path root : roots) {
            if (!Files.isDirectory(root)) { continue; }
            Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), config.scanDepth, new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    if (++visited[0] > config.scanEntries) { return FileVisitResult.TERMINATE; }
                    return directory.toAbsolutePath().normalize().startsWith(config.spool) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) {
                    if (++visited[0] > config.scanEntries) { return FileVisitResult.TERMINATE; }
                    if (attributes.isRegularFile()) { result.add(path.toAbsolutePath().normalize()); }
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path path, IOException exception) {
                    log.accept("Discovery access failure; some paths remain unread"); return FileVisitResult.CONTINUE;
                }
            });
            if (visited[0] > config.scanEntries) { log.accept("scan.entries.max reached; narrow source globs"); break; }
        }
        List<Path> ordered = new ArrayList<>(result); Collections.sort(ordered);
        return new LinkedHashSet<>(ordered);
    }
    private static boolean HasGlob(String value) {
        return value.indexOf('*') >= 0 || value.indexOf('?') >= 0 || value.indexOf('[') >= 0 || value.indexOf('{') >= 0;
    }
    private boolean Matches(AgentConfig.Source source, Path path) {
        for (Path pattern : source.patterns) {
            if (path.getFileSystem().getPathMatcher("glob:" + pattern.toString().replace('\\', '/')).matches(path)) { return true; }
        }
        return false;
    }
    private Path Find(State state, Set<Path> candidates, Set<Path> owned) throws IOException {
        Path previous = state.path;
        if (!owned.contains(previous) && candidates.contains(previous) && Same(state, previous)) { return previous; }
        for (Path candidate : candidates) {
            if (!owned.contains(candidate) && !Protected(candidate) && Same(state, candidate)) { return candidate; }
        }
        return null;
    }
    private boolean Same(State state, Path path) throws IOException {
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) { return false; }
            String key = identity.Key(path, attributes);
            if (!state.cursor.key.isEmpty() && !key.isEmpty() && !state.cursor.key.equals(key)) { return false; }
            if (key.isEmpty() && !path.toString().equals(state.cursor.path)) {
                if (state.cursor.prefixLength == 0 || attributes.lastModifiedTime().toMillis() < state.cursor.modified) { return false; }
            }
            if (attributes.size() < state.readOffset || attributes.size() < state.cursor.prefixLength) { return false; }
            try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
                if (!state.cursor.prefix.equals(Fingerprint(file, 0, state.cursor.prefixLength))) { return false; }
                if (!state.cursor.anchor.equals(Anchor(file, state.cursor.offset))) { return false; }
                if (state.readAnchor != null && !state.readAnchor.equals(Anchor(file, state.readOffset))) { return false; }
            }
            // No key: identical bytes in copied files are ambiguous; prefer original path.
            return true;
        } catch (NoSuchFileException exception) { return false; }
    }

    private void Read(State state) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(state.path.toFile(), "r")) {
            int remaining = config.readBytes;
            byte[] bytes = new byte[config.bufferBytes];
            file.seek(state.readOffset);
            if (state.input.hasRemaining()) { Decode(state, file, state.readOffset - state.input.limit()); }
            while (remaining > 0 && state.staged.size() < 128) {
                int count = file.read(bytes, 0, Math.min(bytes.length, remaining));
                if (count < 0) { break; }
                state.input.compact(); state.input.put(bytes, 0, count); state.input.flip();
                long start = state.readOffset - (state.input.remaining() - count);
                state.readOffset += count; readBytes += count; remaining -= count;
                Decode(state, file, start);
                state.lastActivity = System.currentTimeMillis();
            }
            if (System.currentTimeMillis() - state.lastActivity >= state.source.timeoutMs) { Flush(state, file); }
            state.readAnchor = Anchor(file, state.readOffset);
        }
        Persist(state);
    }
    private void Persist(State state) throws IOException {
        for (Event event : state.staged) {
            queue.Put(event); state.cursor = event.cursor; committedEvents++;
        }
        state.staged.clear();
    }
    private void Decode(State state, RandomAccessFile file, long start) throws IOException {
        while (state.input.hasRemaining() && state.staged.size() < 128) {
            CharBuffer output = state.character; output.clear();
            CoderResult result = state.decoder.decode(state.input, output, false);
            if (result.isOverflow() && output.position() == 0) {
                output = state.surrogate; output.clear(); result = state.decoder.decode(state.input, output, false);
            }
            if (result.isError()) { result.throwException(); }
            long end = start + state.input.position();
            output.flip();
            boolean produced = output.hasRemaining(), newline = false;
            while (output.hasRemaining()) { char ch = output.get(); state.line.append(ch); newline |= ch == '\n'; }
            if (produced) {
                state.lineEnd = end;
                if (newline || end - state.lineStart >= config.eventBytes) { CompleteLine(state, file, end); }
            }
            if (result.isUnderflow()) { break; }
        }
    }
    private void CompleteLine(State state, RandomAccessFile file, long end) throws IOException {
        String line = state.line.toString();
        if (state.event.length() > 0 && (state.source.multiline == null
                || state.source.multiline.matcher(line).find()
                || end - state.eventStart > config.eventBytes || state.lines >= config.eventLines)) {
            Commit(state, file);
        }
        if (state.event.length() == 0) { state.eventStart = state.lineStart; }
        state.event.append(line); state.eventEnd = end; state.lines++;
        state.line.setLength(0); state.lineStart = end;
        if (state.source.multiline == null || state.eventEnd - state.eventStart >= config.eventBytes || state.lines >= config.eventLines) {
            Commit(state, file);
        }
    }
    private void Flush(State state, RandomAccessFile file) throws IOException {
        // Never commit a trailing, incomplete encoded character.
        if (state.input.hasRemaining()) { return; }
        if (state.line.length() > 0) { CompleteLine(state, file, state.lineEnd); }
        if (state.event.length() > 0) { Commit(state, file); }
    }
    private void Commit(State state, RandomAccessFile file) throws IOException {
        Cursor cursor = state.cursor.Copy(); cursor.offset = state.eventEnd;
        cursor.path = state.path.toString(); cursor.anchor = Anchor(file, cursor.offset);
        cursor.modified = Files.getLastModifiedTime(state.path).toMillis();
        state.staged.add(Event.Create(config, state.source, cursor, state.event.toString()));
        state.event.setLength(0); state.lines = 0;
    }
    public void Flush() throws IOException {
        for (State state : states.values()) {
            if (!Files.exists(state.path) || !Same(state, state.path)) { continue; }
            try {
                try (RandomAccessFile file = new RandomAccessFile(state.path.toFile(), "r")) { Flush(state, file); }
                Persist(state);
            } catch (DiskQueue.FullException exception) { Reset(state); Block(); return; }
        }
    }
    private static String Anchor(RandomAccessFile file, long offset) throws IOException {
        return Fingerprint(file, Math.max(0L, offset - 256), (int) Math.min(256L, offset));
    }
    private static String Fingerprint(RandomAccessFile file, long position, int size) throws IOException {
        try {
            long previous = file.getFilePointer(); byte[] bytes = new byte[size]; file.seek(position);
            file.readFully(bytes); file.seek(previous);
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder text = new StringBuilder();
            for (byte value : hash) { text.append(String.format("%02x", value & 255)); }
            return text.toString();
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    public Map<String, Long> ReadOffsets() {
        Map<String, Long> result = new LinkedHashMap<>();
        for (State state : states.values()) { result.put(state.cursor.id, state.readOffset); }
        return result;
    }
    private final class State {
        final AgentConfig.Source source;
        Cursor cursor;
        Path path;
        long readOffset, lineStart, lineEnd, eventStart, eventEnd, lastActivity = System.currentTimeMillis();
        String readAnchor;
        int lines;
        final CharsetDecoder decoder;
        final CharBuffer character = CharBuffer.allocate(1), surrogate = CharBuffer.allocate(2);
        final ByteBuffer input = ByteBuffer.allocate(config.bufferBytes + 16);
        final List<Event> staged = new ArrayList<>();
        final StringBuilder line = new StringBuilder(), event = new StringBuilder();
        State(AgentConfig.Source source, Cursor cursor) {
            this.source = source; this.cursor = cursor; path = Paths.get(cursor.path);
            readOffset = cursor.offset; lineStart = cursor.offset;
            decoder = source.charset.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
            input.limit(0);
        }
    }
}
