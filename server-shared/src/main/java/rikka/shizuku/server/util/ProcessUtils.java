package rikka.shizuku.server.util;

import android.system.Os;
import android.system.OsConstants;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Best-effort process bookkeeping for the manager's temporary-session view.
 *
 * <p>None of this is containment. A process that calls {@code setsid} leaves the session it was
 * started in, and a process that drops to another uid may not be killable from here. The tracked
 * resources the server itself created are the reliable part; everything below is a convenience
 * for the descendants that stayed in the session.
 */
public final class ProcessUtils {

    private ProcessUtils() {
    }

    public static boolean isAlive(int pid) {
        return pid > 0 && new File("/proc/" + pid).exists();
    }

    public static void killPid(int pid) {
        if (pid <= 0 || pid == Os.getpid()) {
            return;
        }
        try {
            Os.kill(pid, OsConstants.SIGKILL);
        } catch (Throwable ignored) {
        }
    }

    /**
     * The pid of the process whose command line carries {@code token}, or -1.
     *
     * <p>User services are started as a backgrounded {@code app_process} and the server never
     * learns their pid; the token is in their command line, which is the one stable handle.
     */
    public static int findPidByToken(String token) {
        if (token == null || token.isEmpty()) {
            return -1;
        }
        File[] entries = new File("/proc").listFiles();
        if (entries == null) {
            return -1;
        }
        int self = Os.getpid();
        for (File entry : entries) {
            String name = entry.getName();
            if (!isNumber(name)) {
                continue;
            }
            int pid;
            try {
                pid = Integer.parseInt(name);
            } catch (NumberFormatException e) {
                continue;
            }
            if (pid == self) {
                continue;
            }
            String cmdline = readCmdline(pid);
            if (cmdline != null && cmdline.contains(token)) {
                return pid;
            }
        }
        return -1;
    }

    /**
     * Kills every process still in the session {@code sid}, including the leader.
     *
     * <p>Session rather than process group: an interactive shell with job control puts each
     * pipeline in its own group but keeps them all in the shell's session, so a group kill would
     * only reach one job.
     */
    public static void killSession(int sid) {
        if (sid <= 0) {
            return;
        }
        int self = Os.getpid();
        List<Integer> pids = new ArrayList<>();
        File[] entries = new File("/proc").listFiles();
        if (entries != null) {
            for (File entry : entries) {
                String name = entry.getName();
                if (!isNumber(name)) {
                    continue;
                }
                int pid;
                try {
                    pid = Integer.parseInt(name);
                } catch (NumberFormatException e) {
                    continue;
                }
                if (pid == self) {
                    continue;
                }
                if (readSession(pid) == sid) {
                    pids.add(pid);
                }
            }
        }
        if (sid != self && !pids.contains(sid)) {
            pids.add(sid);
        }
        for (int pid : pids) {
            killPid(pid);
        }
    }

    private static boolean isNumber(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** The command line of {@code pid}, with NULs turned into spaces; null when unreadable. */
    public static String cmdline(int pid) {
        return readCmdline(pid);
    }

    private static String readCmdline(int pid) {
        try (FileInputStream in = new FileInputStream("/proc/" + pid + "/cmdline")) {
            byte[] buf = new byte[4096];
            int n = in.read(buf);
            if (n <= 0) {
                return null;
            }
            return new String(buf, 0, n, StandardCharsets.UTF_8).replace('\0', ' ');
        } catch (Throwable e) {
            return null;
        }
    }

    private static int readSession(int pid) {
        try (FileInputStream in = new FileInputStream("/proc/" + pid + "/stat")) {
            byte[] buf = new byte[4096];
            int n = in.read(buf);
            if (n <= 0) {
                return -1;
            }
            String stat = new String(buf, 0, n, StandardCharsets.UTF_8);
            int close = stat.lastIndexOf(')');
            if (close < 0 || close + 2 >= stat.length()) {
                return -1;
            }
            // After "pid (comm)": state ppid pgrp session ...
            String[] parts = stat.substring(close + 2).trim().split("\\s+");
            if (parts.length < 4) {
                return -1;
            }
            return Integer.parseInt(parts[3]);
        } catch (Throwable e) {
            return -1;
        }
    }
}
