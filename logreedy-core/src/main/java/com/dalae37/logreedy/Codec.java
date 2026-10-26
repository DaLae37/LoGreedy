package com.dalae37.logreedy;

import java.io.*;
import java.nio.charset.StandardCharsets;

final class Codec {
    private Codec() { }
    static void String(DataOutput output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length); output.write(bytes);
    }
    static String String(DataInput input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > 67108864) { throw new IOException("Invalid string length"); }
        byte[] bytes = new byte[length]; input.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
    static String Quote(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            switch (ch) {
                case '"': result.append("\\\""); break;
                case '\\': result.append("\\\\"); break;
                case '\n': result.append("\\n"); break;
                case '\r': result.append("\\r"); break;
                case '\t': result.append("\\t"); break;
                default:
                    if (ch < 32 || Character.isSurrogate(ch)) { result.append(String.format("\\u%04x", (int) ch)); }
                    else { result.append(ch); }
            }
        }
        return result.append('"').toString();
    }
}
