package eu.siacs.conversations.remote;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Log;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.XmlReader;
import eu.siacs.conversations.xmpp.OnIqPacketReceived;
import eu.siacs.conversations.xmpp.XmppConnection;
import eu.siacs.conversations.xmpp.stanzas.*;
import org.openintents.xmpp.IXmppPluginCallback;
import org.openintents.xmpp.XmppError;
import org.openintents.xmpp.util.XmppPluginCallbackApi;

import java.io.*;
import java.util.*;

import static org.openintents.xmpp.util.XmppPluginCallbackApi.*;
import static org.openintents.xmpp.util.XmppServiceApi.*;
import static org.openintents.xmpp.util.XmppUtils.*;

public class XmppPluginService extends Service {

    private static final int[] SUPPORTED_VERSIONS = new int[]{1};

    private XmppConnectionService xmppConnectionService = null;
    private boolean xmppConnectionServiceBound = false;

    private RemoteCallbackList<XmppPluginCallbackApi> pluginCallbacks = new RemoteCallbackList<>();

    @Override
    public void onCreate() {
        super.onCreate();

        if (!xmppConnectionServiceBound) {
            connectToBackend();
        }
    }

    protected ServiceConnection mConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            final XmppConnectionService.XmppConnectionBinder binder = (XmppConnectionService.XmppConnectionBinder) service;
            xmppConnectionService = binder.getService();
            xmppConnectionService.setXmppPluginService(XmppPluginService.this);
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            xmppConnectionService = null;
            xmppConnectionServiceBound = false;
        }
    };

    public void connectToBackend() {
        final Intent intent = new Intent(this, XmppConnectionService.class);
        intent.setAction("ui");
        startService(intent);
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
    }


    private final org.openintents.xmpp.AbstractXmppService mBinder = new org.openintents.xmpp.AbstractXmppService() {

        @Override
        public Intent execute(final Intent data, final InputStream input, final OutputStream output) {
            try {
                return executeInternal(data, input, output);
            } catch (Exception e) {
                Log.e(Config.LOGTAG, "error in execute", e);
                return getExceptionError(e);
            }
        }

        @Override
        public Intent callback(final Intent data, final IXmppPluginCallback iXmppPluginCallback) {
            try {
                return callbackInternal(data, iXmppPluginCallback);
            } catch (Exception e) {
                Log.e(Config.LOGTAG, "error in callback", e);
                return getExceptionError(e);
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    protected Intent executeInternal(final Intent data, final InputStream inputStream, final OutputStream outputStream) throws Exception {

        // We need to be able to load our own parcelables
        data.setExtrasClassLoader(getClassLoader());

        final Intent errorResult = checkRequirements(data);
        if (errorResult != null) {
            return errorResult;
        }

        final String action = data.getAction();
        switch (action) {
            case ACTION_CHECK_PERMISSION: {
                return null;// todo: checkPermissionImpl(data);
            }
            case ACTION_GET_ACCOUNT_JID: {
                return getAccountJid(data);
            }
            case ACTION_SEND_RAW_XML: {
                return sendRawXml(data, null);
            }
            default: {
                return getError(XmppError.GENERIC_ERROR, "invalid action!");
            }
        }
    }

    protected Intent callbackInternal(final Intent data, final IXmppPluginCallback iXmppPluginCallback) throws Exception {

        // We need to be able to load our own parcelables
        data.setExtrasClassLoader(getClassLoader());

        final Intent errorResult = checkRequirements(data);
        if (errorResult != null) {
            return errorResult;
        }

        final String action = data.getAction();
        switch (action) {
            case ACTION_REGISTER_PLUGIN_CALLBACK: {
                return registerPluginCallback(data, iXmppPluginCallback);
            }
            case ACTION_UNREGISTER_PLUGIN_CALLBACK: {
                return unRegisterPluginCallback(data, iXmppPluginCallback);
            }
            case ACTION_SEND_RAW_XML: {
                return sendRawXml(data, iXmppPluginCallback);
            }
            default: {
                return getError(XmppError.GENERIC_ERROR, "invalid action!");
            }
        }
    }

    private Intent registerPluginCallback(final Intent data, final IXmppPluginCallback iXmppPluginCallback) {
        if(pluginCallbacks.register(new XmppPluginCallbackApi(getApplicationContext(), iXmppPluginCallback,
                data.getStringExtra(EXTRA_ACCOUNT_JID), data.getStringExtra(EXTRA_JID_LOCAL_PART), data.getStringExtra(EXTRA_JID_DOMAIN))))
            return getSuccess();
        return getError(XmppError.GENERIC_ERROR, "callback register failed");
    }

    private Intent unRegisterPluginCallback(final Intent data, final IXmppPluginCallback iXmppPluginCallback) {
        if(pluginCallbacks.unregister(new XmppPluginCallbackApi(getApplicationContext(), iXmppPluginCallback, "fake", null, null)))
            return getSuccess();
        /*
        for(Iterator<XmppPluginCallbackApi> i = pluginCallbacks.iterator(); i.hasNext();) {
            final XmppPluginCallbackApi pluginCallback = i.next();
            if(pluginCallback.getXmppPluginCallback().getDelegate().equals(iXmppPluginCallback)) {
                i.remove();
                return getSuccess();
            }
        }*/
        return getError(XmppError.GENERIC_ERROR, "callback unregister failed");
    }

    /**
     * Check requirements:
     * - params != null
     * - has supported API version
     * - is allowed to call the service (access has been granted)
     *
     * @return null if everything is okay, or a Bundle with an createErrorPendingIntent/PendingIntent
     */
    private Intent checkRequirements(Intent data) {
        // params Bundle is required!
        if (data == null) {
            return getError(XmppError.GENERIC_ERROR, "params Bundle required!");
        }

        if(data.getAction().equals(ACTION_GET_SUPPORTED_VERSIONS)) {
            // for this action, we want to skip both version AND permission checks
            return getSuccess().putExtra(EXTRA_SUPPORTED_VERSIONS, SUPPORTED_VERSIONS);
        }

        // version code is required and needs to correspond to version code of service!
        // History of versions in Xmpp-api's CHANGELOG.md
        final int version = data.getIntExtra(EXTRA_API_VERSION, -1);
        if (version == -1 || Arrays.binarySearch(SUPPORTED_VERSIONS, version) < 0) {
            return getError
                    (XmppError.INCOMPATIBLE_API_VERSIONS, "Incompatible API versions!\n"
                            + "used API version: " + version + "\n"
                            + "supported API versions: " + Arrays.toString(SUPPORTED_VERSIONS))
                    .putExtra(EXTRA_SUPPORTED_VERSIONS, SUPPORTED_VERSIONS);
        }

        // check if caller is allowed to access Conversations
        // todo:
        /*
        Intent result = mApiPermissionHelper.isAllowedOrReturnIntent(data);
        if (result != null) {
            return result;
        }
        */

        return null;
    }

    private Intent getAccountJid(Intent data) {
        // if data already contains EXTRA_ACCOUNT_JID, it has been executed again
        // after user interaction. Then, we just need to return the JID again!
        if (data.hasExtra(EXTRA_ACCOUNT_JID)) {
            final String accountJid = data.getStringExtra(EXTRA_ACCOUNT_JID);
            return getSuccess().putExtra(EXTRA_ACCOUNT_JID, accountJid);
        } else {
            /*
            String currentPkg = mApiPermissionHelper.getCurrentCallingPackage();
            String preferredUserId = data.getStringExtra(EXTRA_USER_ID);

            PendingIntent pi = mApiPendingIntentFactory.createSelectSignKeyIdPendingIntent(data, currentPkg, preferredUserId);

            // return PendingIntent to be executed by client
            Intent result = new Intent();
            result.putExtra(RESULT_CODE, RESULT_CODE_USER_INTERACTION_REQUIRED);
            result.putExtra(RESULT_INTENT, pi);
            */

            // todo: implement chooser above, until then, first account will do
            final String accountJid = xmppConnectionService.getAccounts().get(0).getJid().toBareJid().toString();
            return getSuccess().putExtra(EXTRA_ACCOUNT_JID, accountJid);
        }
    }

    private Intent sendRawXml(Intent data, final IXmppPluginCallback iXmppPluginCallback) throws Exception {
        final Account account = findAccount(data.getStringExtra(EXTRA_ACCOUNT_JID));
        final XmlReader reader = new XmlReader(new StringReader(data.getStringExtra(EXTRA_RAW_XML)));
        final Element parsedRawXml = reader.nextWholeElement(); // todo: should do this in loop or????
        Log.d(Config.LOGTAG, "rawXml: " + parsedRawXml.toString());
        switch(parsedRawXml.getName()) {
            case "message": {
                final MessagePacket packet = create(parsedRawXml, new MessagePacket());
                packet.setFrom(account.getJid());
                Log.d(Config.LOGTAG, "msgXml: " + packet.toString());
                xmppConnectionService.sendMessagePacket(account, packet);
                return getSuccess();
            }
            case "iq": {
                final IqPacket packet = create(parsedRawXml, new IqPacket());
                packet.setFrom(account.getJid());

                OnIqPacketReceived callback = null;
                if(iXmppPluginCallback != null) {
                    // plugin wants notified of response
                    callback = new OnIqPacketReceived() {
                        @Override
                        public void onIqPacketReceived(final Account account, final IqPacket packet) {
                            final Intent result = new Intent();
                            result.setAction(ACTION_IQ_RESPONSE);
                            final String accountJid = account.getJid().toBareJid().toString();
                            result.putExtra(EXTRA_ACCOUNT_JID, accountJid);
                            result.putExtra(EXTRA_RAW_XML, packet.toString());
                            try {
                                iXmppPluginCallback.execute(result, null, -1);
                            } catch (RemoteException e) {
                                Log.e(Config.LOGTAG, "error returning iq to callback", e);
                            }
                        }
                    };
                }

                xmppConnectionService.sendIqPacket(account, packet, callback);
                return getSuccess();
            }
            case "presence": {
                final PresencePacket packet = create(parsedRawXml, new PresencePacket());
                packet.setFrom(account.getJid());
                xmppConnectionService.sendPresencePacket(account, packet);
                return getSuccess();
            }
            default: {
                // must be some type of nonza
                XmppConnection connection = account.getXmppConnection();
                if (connection != null) {
                    connection.sendPacket(create(parsedRawXml, new GenericStanza(parsedRawXml.getName())));
                }
                return getSuccess();
            }
        }
    }

    private Account findAccount(final String accountJid) {
        if(accountJid != null)
            for(Account account : xmppConnectionService.getAccounts())
                if(accountJid.equals(account.getJid().toBareJid().toString()))
                    return account;
        return null;
    }

    private static <T extends Element> T create(final Element element, final T packet) {
        packet.setAttributes(element.getAttributes());
        packet.setContent(element.getContent());
        packet.setChildren(element.getChildren());
        return packet;
    }

    private final IXmppCallback newMessageReturn = new IXmppCallback() {
        @Override
        public void onReturn(final Intent result) {
            if(result.getIntExtra(RESULT_CODE, RESULT_CODE_ERROR) == RESULT_CODE_ERROR) {
                // We need to be able to load our own parcelables
                result.setExtrasClassLoader(getClassLoader());
                Log.e(Config.LOGTAG, "error calling callback: " + result.getParcelableExtra(RESULT_ERROR));
                // todo: should remove from pluginCallbacks on error or?
            }
        }
    };

    public void newMessage(final Message message) {
        final int N = pluginCallbacks.beginBroadcast();
        try {
            if (N == 0)
                return;
            final String accountJid = message.getConversation().getAccount().getJid().toBareJid().toString();
            final String localPart = message.getConversation().getJid().getLocalpart();
            final String domain = message.getConversation().getJid().getDomainpart();
            Intent result = null; // lazy load this
            //for(final XmppPluginCallbackApi api : pluginCallbacks) {
            for (int i = 0; i < N; ++i) {
                final XmppPluginCallbackApi api = pluginCallbacks.getBroadcastItem(i);
                if (api.matches(accountJid, localPart, domain)) {
                    if (result == null) {
                        result = new Intent();
                        result.setAction(ACTION_NEW_MESSAGE);
                        result.putExtra(EXTRA_ACCOUNT_JID, accountJid);
                        result.putExtra(EXTRA_MESSAGE_STATUS, message.getStatus());
                        final String partnerJid = message.getConversation().getJid().toBareJid().toString();
                        // need to set from/to based on status
                        if (message.getStatus() == 0) {
                            result.putExtra(EXTRA_MESSAGE_TO, accountJid);
                            result.putExtra(EXTRA_MESSAGE_FROM, partnerJid);
                        } else {
                            result.putExtra(EXTRA_MESSAGE_FROM, accountJid);
                            result.putExtra(EXTRA_MESSAGE_TO, partnerJid);
                        }
                        result.putExtra(EXTRA_MESSAGE_BODY, message.getBody());
                    }
                    api.executeApiAsync(result, null, null, newMessageReturn);
                }
            }
        } finally {
            pluginCallbacks.finishBroadcast();
        }
    }
}
