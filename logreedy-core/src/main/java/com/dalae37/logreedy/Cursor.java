package com.dalae37.logreedy;

import java.io.*;

/** A byte boundary owned by a durable journal record. */
public final class Cursor {
    public String id, source, path, key, prefix, anchor;
    public long offset, modified;
    public int prefixLength;

    public Cursor Copy() {
        Cursor copy = new Cursor();
        copy.id = id; copy.source = source; copy.path = path; copy.key = key;
        copy.prefix = prefix; copy.anchor = anchor; copy.offset = offset;
        copy.modified = modified; copy.prefixLength = prefixLength;
        return copy;
    }

    public void Write(DataOutput output) throws IOException {
        Codec.String(output, id); Codec.String(output, source); Codec.String(output, path);
        Codec.String(output, key); Codec.String(output, prefix); Codec.String(output, anchor);
        output.writeLong(offset); output.writeLong(modified); output.writeInt(prefixLength);
    }

    public static Cursor Read(DataInput input) throws IOException {
        Cursor cursor = new Cursor();
        cursor.id = Codec.String(input); cursor.source = Codec.String(input); cursor.path = Codec.String(input);
        cursor.key = Codec.String(input); cursor.prefix = Codec.String(input); cursor.anchor = Codec.String(input);
        cursor.offset = input.readLong(); cursor.modified = input.readLong(); cursor.prefixLength = input.readInt();
        if (cursor.offset < 0 || cursor.prefixLength < 0 || cursor.prefixLength > 256) { throw new IOException("Invalid cursor"); }
        return cursor;
    }
}
