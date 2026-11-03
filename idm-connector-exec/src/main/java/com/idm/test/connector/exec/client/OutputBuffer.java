package com.idm.test.connector.exec.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Drains a process stream on its own thread, keeping its first {@code limit} bytes and counting all of
 * them. Draining everything is what keeps a command that writes a flood from blocking on a full pipe.
 */
final class OutputBuffer implements Runnable {

    private final InputStream in;
    private final long limit;
    private final ByteArrayOutputStream kept = new ByteArrayOutputStream();
    private long total;

    OutputBuffer(InputStream in, long limit) {
        this.in = in;
        this.limit = limit;
    }

    @Override
    public void run() {
        byte[] chunk = new byte[8192];
        try (in) {
            int n;
            while ((n = in.read(chunk)) != -1) {
                synchronized (this) {
                    long room = limit - kept.size();
                    if (room > 0) {
                        kept.write(chunk, 0, (int) Math.min(room, n));
                    }
                    total += n;
                }
            }
        } catch (IOException e) {
            // The stream closed under us (the process was killed): what was read so far stands.
        }
    }

    synchronized byte[] kept() {
        return kept.toByteArray();
    }

    synchronized long total() {
        return total;
    }
}
