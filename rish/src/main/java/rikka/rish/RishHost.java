package rikka.rish;

import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

public class RishHost {

    private static final String TAG = "RishHost";

    // libcore/ojluni/src/main/java/java/lang/ProcessImpl.java

    private static byte[] createCBytesForStringArray(String[] array) {
        if (array == null) {
            return null;
        }

        byte[][] bytes = new byte[array.length][];
        int count = bytes.length; // For added NUL bytes
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = array[i].getBytes();
            count += bytes[i].length;
        }
        byte[] block = new byte[count];
        int i = 0;
        for (byte[] arg : bytes) {
            System.arraycopy(arg, 0, block, i, arg.length);
            i += arg.length + 1;
            // No need to write NUL bytes explicitly
        }
        return block;
    }

    private static byte[] createCBytesForString(String s) {
        if (s == null) {
            return null;
        }

        byte[] bytes = s.getBytes();
        byte[] result = new byte[bytes.length + 1];
        System.arraycopy(bytes, 0,
                result, 0,
                bytes.length);
        result[result.length - 1] = (byte) 0;
        return result;
    }

    private static int detachFd(ParcelFileDescriptor pfd) {
        if (pfd == null) {
            return -1;
        }
        return pfd.detachFd();
    }

    private final String[] args;
    private final String[] env;
    private final String dir;
    private final byte tty;
    private final int stdin;
    private final int stdout;
    private final int stderr;
    private int pid;
    private int ptmx;
    private volatile int exitCode = Integer.MAX_VALUE;

    public RishHost(
            String[] args, String[] env, String dir,
            byte tty,
            ParcelFileDescriptor stdin, ParcelFileDescriptor stdout, ParcelFileDescriptor stderr) {

        this.args = args;
        this.env = env;
        this.dir = dir;
        this.tty = tty;
        this.stdin = detachFd(stdin);
        this.stdout = detachFd(stdout);
        this.stderr = detachFd(stderr);
    }

    /**
     * Fork and execute, start transfer threads.
     */
    public void start() {
        Log.d(TAG, "start");


        byte[] argBlock = createCBytesForStringArray(args);
        byte[] envBlock = createCBytesForStringArray(env);
        byte[] dirBlock = createCBytesForString(dir);

        int[] result = start(
                argBlock, args.length,
                envBlock, env != null ? env.length : -1,
                dirBlock,
                tty, stdin, stdout, stderr);

        pid = result[0];
        ptmx = result[1];

        new Thread(() -> {
            exitCode = waitFor(pid);
            closePtmx();
        }).start();
    }

    /** The transfers use duplicates, so the master is closed once, when the host is gone. */
    private synchronized void closePtmx() {
        if (ptmx >= 0) {
            try {
                ParcelFileDescriptor.adoptFd(ptmx).close();
            } catch (Throwable ignored) {
            }
            ptmx = -1;
        }
    }

    public int getPid() {
        return pid;
    }

    public int getExitCode() {
        return exitCode;
    }

    /** Whether the forked shell is still running. */
    public boolean isAlive() {
        return exitCode == Integer.MAX_VALUE;
    }

    /**
     * Kills the forked shell. The host is a fork of the server, so the server may always signal
     * it; descendants are handled separately by session.
     */
    public void kill() {
        if (pid <= 0) {
            return;
        }
        try {
            Os.kill(pid, OsConstants.SIGKILL);
        } catch (Throwable ignored) {
        }
    }

    public synchronized void setWindowSize(long size) {
        Log.d(TAG, "setWindowSize");

        if (ptmx >= 0) {
            setWindowSize(ptmx, size);
        }
    }

    private static native int[] start(
            byte[] argBlock, int argc,
            byte[] envBlock, int envc,
            byte[] dirBlock,
            byte tty, int stdin, int stdout, int stderr);

    private static native void setWindowSize(int ptmx, long size);

    private static native int waitFor(int pid);
}
