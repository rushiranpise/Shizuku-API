package rikka.shizuku.server.api;

import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import moe.shizuku.server.IRemoteProcess;
import rikka.shizuku.server.util.Logger;
import rikka.shizuku.server.util.ParcelFileDescriptorUtil;

public class RemoteProcessHolder extends IRemoteProcess.Stub {

    private static final Logger LOGGER = new Logger("RemoteProcessHolder");

    private final Process process;
    private final int uid;
    private final int ownerPid;
    private final boolean temporary;
    private ParcelFileDescriptor in;
    private ParcelFileDescriptor out;
    private Runnable onGone;

    public RemoteProcessHolder(Process process, IBinder token, int uid, int ownerPid, boolean temporary) {
        this.process = process;
        this.uid = uid;
        this.ownerPid = ownerPid;
        this.temporary = temporary;

        if (token != null) {
            try {
                DeathRecipient deathRecipient = () -> {
                    try {
                        if (alive()) {
                            destroy();
                            LOGGER.i("destroy process because the owner is dead");
                        }
                    } catch (Throwable e) {
                        LOGGER.w(e, "failed to destroy process");
                    }
                };
                token.linkToDeath(deathRecipient, 0);
            } catch (Throwable e) {
                LOGGER.w(e, "linkToDeath");
            }
        }
    }

    @Override
    public ParcelFileDescriptor getOutputStream() {
        if (out == null) {
            try {
                out = ParcelFileDescriptorUtil.pipeTo(process.getOutputStream());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        return out;
    }

    @Override
    public ParcelFileDescriptor getInputStream() {
        if (in == null) {
            try {
                in = ParcelFileDescriptorUtil.pipeFrom(process.getInputStream());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        return in;
    }

    @Override
    public ParcelFileDescriptor getErrorStream() {
        try {
            return ParcelFileDescriptorUtil.pipeFrom(process.getErrorStream());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public int waitFor() {
        try {
            return process.waitFor();
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public int exitValue() {
        return process.exitValue();
    }

    public int getUid() {
        return uid;
    }

    /** The client process that asked for this shell. */
    public int getOwnerPid() {
        return ownerPid;
    }

    /** Whether the shell was started while its owner held a temporary grant. */
    public boolean isTemporary() {
        return temporary;
    }

    /** Called when the holder is destroyed, so the server can drop it from its list. */
    public void setOnGone(Runnable onGone) {
        this.onGone = onGone;
    }

    /** [alive] without the checked exception, for counting. */
    public boolean isAliveQuiet() {
        try {
            return alive();
        } catch (Throwable e) {
            return false;
        }
    }

    @Override
    public void destroy() {
        process.destroy();
        Runnable r = onGone;
        if (r != null) {
            r.run();
        }
    }

    @Override
    public boolean alive() throws RemoteException {
        try {
            this.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    @Override
    public boolean waitForTimeout(long timeout, String unitName) throws RemoteException {
        TimeUnit unit = TimeUnit.valueOf(unitName);
        long startTime = System.nanoTime();
        long rem = unit.toNanos(timeout);

        do {
            try {
                exitValue();
                return true;
            } catch (IllegalThreadStateException ex) {
                if (rem > 0) {
                    try {
                        Thread.sleep(
                                Math.min(TimeUnit.NANOSECONDS.toMillis(rem) + 1, 100));
                    } catch (InterruptedException e) {
                        throw new IllegalStateException();
                    }
                }
            }
            rem = unit.toNanos(timeout) - (System.nanoTime() - startTime);
        } while (rem > 0);
        return false;
    }
}
