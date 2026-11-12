package rikka.shizuku.server;

import static rikka.shizuku.ShizukuApiConstants.USER_SERVICE_TRANSACTION_destroy;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteCallbackList;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import moe.shizuku.server.IShizukuServiceConnection;
import rikka.shizuku.server.util.HandlerUtil;
import rikka.shizuku.server.util.Logger;

public abstract class UserServiceRecord {

    /**
     * One client that bound this service, with the process and grant it held when it did.
     *
     * <p>A service key can be shared by several clients, so the requester belongs to the
     * connection, not to the record: two processes of one app can bind the same service under
     * different grants, and a session stop has to remove only the association it names.
     */
    public static final class Association {
        final IShizukuServiceConnection callback;
        public final int uid;
        public final int pid;
        public final boolean temporary;

        Association(IShizukuServiceConnection callback, int uid, int pid, boolean temporary) {
            this.callback = callback;
            this.uid = uid;
            this.pid = pid;
            this.temporary = temporary;
        }

        boolean matches(int uid, int sessionPid) {
            return temporary && this.uid == uid && (sessionPid == 0 || pid == sessionPid);
        }
    }

    private Runnable startTimeoutCallback;

    private final Map<IBinder, Association> associations = new LinkedHashMap<>();
    /** The last temporary holder of a daemon that outlived its client, kept for the manager. */
    private Association orphan;

    private class ConnectionList extends RemoteCallbackList<IShizukuServiceConnection> {

        @Override
        public void onCallbackDied(IShizukuServiceConnection callback) {
            boolean empty = removeAssociation(callback);

            if (daemon || !empty) {
                return;
            }

            LOGGER.v("Remove service record %s since it does not run as a daemon and all connections are gone", token);
            removeSelf();
        }
    }

    protected static final Logger LOGGER = new Logger("UserServiceRecord");

    private final IBinder.DeathRecipient deathRecipient;
    public final int versionCode;
    public String token;
    public IBinder service;
    public final RemoteCallbackList<IShizukuServiceConnection> callbacks = new ConnectionList();
    public boolean daemon;
    public boolean starting;

    /** The service process itself, found when its binder arrives; -1 until then. */
    public int pid = -1;
    /** The package the service belongs to, for the manager's list. */
    public String packageName;

    public boolean isAlive() {
        return service != null && service.pingBinder();
    }

    public UserServiceRecord(int versionCode, boolean daemon) {
        this.versionCode = versionCode;
        this.token = UUID.randomUUID().toString() + "-" + System.currentTimeMillis();
        this.deathRecipient = () -> {
            LOGGER.v("Binder for service record %s is dead", token);
            removeSelf();
        };
        this.daemon = daemon;
    }

    public void register(IShizukuServiceConnection callback, int uid, int pid, boolean temporary) {
        synchronized (associations) {
            associations.put(callback.asBinder(), new Association(callback, uid, pid, temporary));
            orphan = null;
        }
        callbacks.register(callback);
    }

    public void unregister(IShizukuServiceConnection callback) {
        removeAssociation(callback);
        callbacks.unregister(callback);
    }

    private boolean removeAssociation(IShizukuServiceConnection callback) {
        synchronized (associations) {
            Association association = associations.remove(callback.asBinder());
            if (association != null && association.temporary && associations.isEmpty()) {
                orphan = association;
            }
            return association != null && associations.isEmpty();
        }
    }

    /** The live associations, or the last temporary holder when only a daemon remains. */
    public List<Association> snapshotAssociations() {
        synchronized (associations) {
            List<Association> list = new ArrayList<>(associations.values());
            if (list.isEmpty() && orphan != null) {
                list.add(orphan);
            }
            return list;
        }
    }

    /**
     * Removes the temporary associations matching the selected client/session, and returns
     * whether no association is left, i.e. whether stopping the record may kill its process.
     * A service still used by another client, temporary or permanent, is left running.
     */
    public boolean removeTemporaryAssociations(int uid, int sessionPid, int pid) {
        if (pid != 0 && this.pid != pid) {
            return false;
        }

        List<IShizukuServiceConnection> removed = new ArrayList<>();
        boolean empty;
        synchronized (associations) {
            boolean matched = false;
            for (Iterator<Map.Entry<IBinder, Association>> it = associations.entrySet().iterator(); it.hasNext(); ) {
                Association association = it.next().getValue();
                if (!association.matches(uid, sessionPid)) {
                    continue;
                }
                it.remove();
                removed.add(association.callback);
                matched = true;
            }
            if (orphan != null && orphan.matches(uid, sessionPid)) {
                orphan = null;
                matched = true;
            }
            empty = matched && associations.isEmpty();
        }
        for (IShizukuServiceConnection callback : removed) {
            callbacks.unregister(callback);
        }
        return empty;
    }

    public void setStartingTimeout(long timeoutMillis) {
        if (starting) {
            LOGGER.w("Service record %s is already starting", token);
            return;
        }

        LOGGER.v("Set starting timeout for service record %s: %d", token, timeoutMillis);

        starting = true;
        startTimeoutCallback = () -> {
            if (starting) {
                LOGGER.w("Service record %s is not started in %d ms", token, timeoutMillis);
                removeSelf();
            }
        };
        HandlerUtil.getMainHandler().postDelayed(startTimeoutCallback, timeoutMillis);
    }

    public void setDaemon(boolean daemon) {
        this.daemon = daemon;
    }

    public void setBinder(IBinder binder) {
        LOGGER.v("Binder received for service record %s", token);

        HandlerUtil.getMainHandler().removeCallbacks(startTimeoutCallback);

        service = binder;

        try {
            binder.linkToDeath(deathRecipient, 0);
        } catch (Throwable tr) {
            LOGGER.w("linkToDeath %s", token);
        }

        broadcastBinderReceived();
    }

    public void broadcastBinderReceived() {
        LOGGER.v("Broadcast binder received for service record %s", token);

        int count = callbacks.beginBroadcast();
        for (int i = 0; i < count; i++) {
            try {
                callbacks.getBroadcastItem(i).connected(service);
            } catch (Throwable e) {
                LOGGER.w("Failed to call connected %s", token);
            }
        }
        callbacks.finishBroadcast();
    }

    public void broadcastBinderDied() {
        LOGGER.v("Broadcast binder died for service record %s", token);

        int count = callbacks.beginBroadcast();
        for (int i = 0; i < count; i++) {
            try {
                callbacks.getBroadcastItem(i).died();
            } catch (Throwable e) {
                LOGGER.w("Failed to call died %s", token);
            }
        }
        callbacks.finishBroadcast();
    }

    public abstract void removeSelf();

    public void destroy() {
        if (service != null) {
            service.unlinkToDeath(deathRecipient, 0);
        }

        if (service != null && service.pingBinder()) {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(service.getInterfaceDescriptor());
                service.transact(USER_SERVICE_TRANSACTION_destroy, data, reply, Binder.FLAG_ONEWAY);
            } catch (Throwable e) {
                LOGGER.w("Failed to call destroy %s", token);
            } finally {
                data.recycle();
                reply.recycle();
            }
        }

        synchronized (associations) {
            associations.clear();
            orphan = null;
        }
        callbacks.kill();
    }
}
