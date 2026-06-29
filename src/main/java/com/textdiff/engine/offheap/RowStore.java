package com.textdiff.engine.offheap;

import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

import static java.nio.file.StandardOpenOption.*;

/**
 * 追加式行存储：用 FileChannel 定位读写一个临时文件，原生 long 偏移，磁盘支撑、内存有界。
 * 条目布局：[keyLen:int][keyBytes UTF-8][colCount:int]( [colLen:int][colBytes UTF-8] )*。
 * （mmap 为后续性能优化项；当前用定位 read/write 以规避 Windows 映射未释放问题。）
 */
public final class RowStore implements AutoCloseable {
    private final Path file;
    private final FileChannel ch;
    private long writePos = 0;

    public RowStore(Path tmpDir) {
        try {
            this.file = Files.createTempFile(tmpDir, "rowstore", ".bin");
            this.ch = FileChannel.open(file, READ, WRITE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 追加一条 (key, cols)，返回其 long 指针。 */
    public long append(String key, String[] cols) {
        byte[] kb = key.getBytes(StandardCharsets.UTF_8);
        byte[][] cb = new byte[cols.length][];
        int size = 4 + kb.length + 4;
        for (int i = 0; i < cols.length; i++) {
            cb[i] = cols[i].getBytes(StandardCharsets.UTF_8);
            size += 4 + cb[i].length;
        }
        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.putInt(kb.length).put(kb).putInt(cols.length);
        for (byte[] c : cb) buf.putInt(c.length).put(c);
        buf.flip();
        long ptr = writePos;
        try {
            long p = ptr;
            while (buf.hasRemaining()) p += ch.write(buf, p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        writePos += size;
        return ptr;
    }

    /** 仅读 key（探查期碰撞校验用，避免读全部列）。 */
    public String readKey(long ptr) {
        try {
            int kl = readInt(ptr);
            return new String(readFully(ptr + 4, kl), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 读出该条目的全部列。 */
    public String[] readCols(long ptr) {
        try {
            int kl = readInt(ptr);
            long p = ptr + 4 + kl;
            int n = readInt(p);
            p += 4;
            String[] out = new String[n];
            for (int i = 0; i < n; i++) {
                int cl = readInt(p);
                p += 4;
                out[i] = new String(readFully(p, cl), StandardCharsets.UTF_8);
                p += cl;
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private int readInt(long pos) throws IOException {
        byte[] b = readFully(pos, 4);
        return ((b[0] & 0xff) << 24) | ((b[1] & 0xff) << 16) | ((b[2] & 0xff) << 8) | (b[3] & 0xff);
    }

    private byte[] readFully(long pos, int len) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(len);
        long p = pos;
        while (b.hasRemaining()) {
            int n = ch.read(b, p);
            if (n < 0) throw new EOFException("RowStore 读越界 @" + pos);
            p += n;
        }
        return b.array();
    }

    /** 仅供测试。 */
    Path file() {
        return file;
    }

    @Override
    public void close() {
        try {
            ch.close();
        } catch (IOException ignored) {
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
        }
    }
}
