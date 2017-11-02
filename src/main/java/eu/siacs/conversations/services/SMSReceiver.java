package eu.siacs.conversations.services;

import android.content.*;
import android.os.Bundle;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;
import android.util.Log;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xmpp.jid.Jid;
import eu.siacs.conversations.xmpp.stanzas.MessagePacket;

public class SMSReceiver extends BroadcastReceiver {

    public static final String ECHO_SERVER = "echo.burtrum.org";

    public static XmppConnectionService xmppConnectionService;

    public static Account account = null;

    public static Account getAccount() {
        if(account != null)
            return account;
        return account = xmppConnectionService.getAccounts().get(0);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        Bundle bundle = intent.getExtras();
        if (bundle == null)
            return;

        Object[] pdus = (Object[]) bundle.get("pdus");
        for (int i = 0; i < pdus.length; ++i) {
            SmsMessage msg = SmsMessage.createFromPdu((byte[]) pdus[i]);
            final String from = msg.getOriginatingAddress();
            //if (!"7243".equals(msg.getOriginatingAddress())) continue;
            // it's a message from Page Plus, but is it a balance message?
            final String message = msg.getMessageBody();
            if (message == null)
                continue;
            // it IS a balance message, get to parsing...
            Log.d(Config.LOGTAG, "SMSReceiver: " + from + ": " + message);
            Log.d(Config.LOGTAG, "SMSReceiver: xmppConnectionService: " + xmppConnectionService);
            try {
                final Jid fromJid = Jid.fromParts(from, ECHO_SERVER, null);

                final MessagePacket packet = new MessagePacket();
                packet.setType(MessagePacket.TYPE_NORMAL);
                //packet.setAttribute("id",id);
                packet.setTo(fromJid);
                packet.setFrom(getAccount().getJid());

                packet.addChild("echo", "https://code.moparisthebest.com/moparisthebest/xmpp-echo-self");

                final Element forwarded = packet.addChild("forwarded", "urn:xmpp:forward:0");
                // todo: add delay to forwarded with msg.getTimestampMillis()
                final MessagePacket forwardedMsg = new MessagePacket();
                forwarded.addChild(forwardedMsg);

                forwardedMsg.setAttribute("xmlns", "jabber:client");
                forwardedMsg.setType(MessagePacket.TYPE_CHAT);
                forwardedMsg.setTo(getAccount().getJid());
                forwardedMsg.setFrom(fromJid);
                forwardedMsg.setBody(message);

                xmppConnectionService.sendMessagePacket(getAccount(), packet);

                //---send a broadcast intent to update the SMS received in the activity---
                /*
                Intent broadcastIntent = new Intent();
                broadcastIntent.setAction(Main.PP_BAL_RECEIVED_ACTION);
                broadcastIntent.putExtra(Main.BALANCE, "someString");
                context.sendBroadcast(broadcastIntent);
                */


            } catch (Exception e) {
                Log.d(Config.LOGTAG, "SMSReceiver: ", e);
            }
        }
    }

    public static void newMessage(final Message message) {
        Log.d(Config.LOGTAG, "SMSReceiver: checking: " + message.toString());
        //Log.d(Config.LOGTAG, String.format("SMSReceiver: checking counterpart: '%s' true: '%s'", message.getCounterpart(), message.getTrueCounterpart()));
        if(!message.getConversation().getJid().getDomainpart().equals(ECHO_SERVER) ||
                !message.getConversation().getAccount().getJid().toBareJid().equals(getAccount().getJid().toBareJid()) ||
                message.getStatus() == Message.STATUS_RECEIVED)
            return;
        Log.d(Config.LOGTAG, String.format("SMSReceiver: sending to '%s' body: '%s'", message.getConversation().getJid().getLocalpart(), message.getBody()));
        //if(false)
        new Thread(new Runnable() {
            public void run() {
                final SmsManager sms = SmsManager.getDefault();
                sms.sendTextMessage(message.getConversation().getJid().getLocalpart(), null, message.getBody(), null, null);
            }
        }).start();
    }
}