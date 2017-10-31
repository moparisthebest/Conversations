package eu.siacs.conversations.services;

import android.content.*;
import android.os.Bundle;
import android.telephony.SmsMessage;
import android.util.Log;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xmpp.jid.Jid;
import eu.siacs.conversations.xmpp.stanzas.MessagePacket;

public class SMSReceiver extends BroadcastReceiver {

    public static XmppConnectionService xmppConnectionService;

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
                // test
                android.widget.Toast.makeText(context, from + ": " + message, android.widget.Toast.LENGTH_SHORT).show();
/*
                */
                final Jid fromJid = Jid.fromString(from + "@echo.burtrum.org");
                final Account account = xmppConnectionService.getAccounts().get(0);

                final MessagePacket packet = new MessagePacket();
                packet.setType(MessagePacket.TYPE_NORMAL);
                //packet.setAttribute("id",id);
                packet.setTo(fromJid);
                packet.setFrom(account.getJid());

                packet.addChild("echo", "https://code.moparisthebest.com/moparisthebest/xmpp-echo-self");

                final Element forwarded = packet.addChild("forwarded", "urn:xmpp:forward:0");
                // todo: add delay to forwarded with msg.getTimestampMillis()
                final MessagePacket forwardedMsg = new MessagePacket();
                forwarded.addChild(forwardedMsg);

                forwardedMsg.setAttribute("xmlns", "jabber:client");
                forwardedMsg.setType(MessagePacket.TYPE_CHAT);
                forwardedMsg.setTo(account.getJid());
                forwardedMsg.setFrom(fromJid);
                forwardedMsg.setBody(message);

                xmppConnectionService.sendMessagePacket(account, packet);

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
}