/*
 * Copyright (C) 2015 Jacob Klinker
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.klinker.android.send_message;

import static com.google.android.mms.pdu_alt.PduHeaders.STATUS_RETRIEVED;

import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.provider.Telephony;
import android.telephony.SmsManager;
import android.util.Log;

import com.android.mms.service_alt.DownloadRequest;
import com.android.mms.service_alt.MmsConfig;
import com.android.mms.transaction.DownloadManager;
import com.android.mms.transaction.HttpUtils;
import com.android.mms.transaction.TransactionSettings;
import com.android.mms.util.SendingProgressTokenManager;
import com.google.android.mms.InvalidHeaderValueException;
import com.google.android.mms.MmsException;
import com.google.android.mms.pdu_alt.EncodedStringValue;
import com.google.android.mms.pdu_alt.GenericPdu;
import com.google.android.mms.pdu_alt.NotificationInd;
import com.google.android.mms.pdu_alt.NotifyRespInd;
import com.google.android.mms.pdu_alt.PduComposer;
import com.google.android.mms.pdu_alt.PduHeaders;
import com.google.android.mms.pdu_alt.PduParser;
import com.google.android.mms.pdu_alt.PduPersister;
import com.google.android.mms.pdu_alt.RetrieveConf;
import com.google.android.mms.util_alt.SqliteWrapper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public abstract class MmsReceivedReceiver extends BroadcastReceiver {
    private static final String TAG = "MmsReceivedReceiver";

    public static final String MMS_RECEIVED = "com.klinker.android.messaging.MMS_RECEIVED";
    public static final String EXTRA_FILE_PATH = "file_path";
    public static final String EXTRA_LOCATION_URL = "location_url";
    public static final String EXTRA_TRANSACTION_ID = "transaction_id";
    public static final String EXTRA_TRIGGER_PUSH = "trigger_push";
    public static final String EXTRA_URI = "notification_ind_uri";
    public static final String SUBSCRIPTION_ID = "subscription_id";

    private static final String LOCATION_SELECTION =
            Telephony.Mms.MESSAGE_TYPE + "=? AND " + Telephony.Mms.CONTENT_LOCATION + " =?";

    private static final ExecutorService RECEIVE_NOTIFICATION_EXECUTOR = Executors.newSingleThreadExecutor();

    public boolean isAddressBlocked(Context context, String address) {
        // Subclasses can override this to screen messages.
        return false;
    }

    public abstract void onMessageReceived(Context context, Uri messageUri);

    public abstract void onError(Context context, String error);

    public MmscInformation getMmscInfoForReceptionAck(Context context, int subscriptionId) {
        // Override this and provide the MMSC to send the ACK to.
        // some carriers will download duplicate MMS messages without this ACK. When using the
        // system sending method, apparently Google does not do this for us. Not sure why.
        // You might have to have users manually enter their APN settings if you cannot get them
        // from the system somehow.

        return null;
    }

    @Override
    public final void onReceive(final Context context, final Intent intent) {
        Log.v(TAG, "MMS has finished downloading, persisting it to the database");

        final String path = intent.getStringExtra(EXTRA_FILE_PATH);
        final int subscriptionId = intent.getIntExtra(SUBSCRIPTION_ID, Utils.getDefaultSubscriptionId());
        final String locationUrl = intent.getStringExtra(EXTRA_LOCATION_URL);
        final String transactionId = intent.getStringExtra(EXTRA_TRANSACTION_ID);
        Log.v(TAG, path);

        new Thread(() -> {
            FileInputStream reader = null;
            Uri messageUri = null;
            String errorMessage = null;

            try {
                File mDownloadFile = new File(path);
                final int nBytes = (int) mDownloadFile.length();
                reader = new FileInputStream(mDownloadFile);
                final byte[] response = new byte[nBytes];
                reader.read(response, 0, nBytes);

                final MmsConfig.Overridden mmsConfig = new MmsConfig.Overridden(new MmsConfig(context), null);
                final String address = parseSenderAddressFromPdu(context, response, locationUrl, mmsConfig);
                if (isAddressBlocked(context, address)) {
                    // Delete the corresponding NotificationInd.
                    SqliteWrapper.delete(context,
                            context.getContentResolver(),
                            Telephony.Mms.CONTENT_URI,
                            LOCATION_SELECTION,
                            new String[]{
                                    Integer.toString(PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND),
                                    locationUrl
                            });
                    return;
                }

                List<CommonAsyncTask> tasks = getNotificationTask(context, intent, response, subscriptionId);
                messageUri = DownloadRequest.persist(context, response, mmsConfig,
                        locationUrl, transactionId, subscriptionId, null);

                Log.v(TAG, "response saved successfully");
                Log.v(TAG, "response length: " + response.length);
                mDownloadFile.delete();

                if (tasks != null) {
                    Log.v(TAG, "running the common async notifier for download");
                    for (CommonAsyncTask task : tasks)
                        task.executeOnExecutor(RECEIVE_NOTIFICATION_EXECUTOR);
                }
            } catch (FileNotFoundException e) {
                errorMessage = "MMS received, file not found exception";
                Log.e(TAG, errorMessage, e);
            } catch (IOException e) {
                errorMessage = "MMS received, io exception";
                Log.e(TAG, errorMessage, e);
            } finally {
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (IOException e) {
                        errorMessage = "MMS received, io exception";
                        Log.e(TAG, "MMS received, io exception", e);
                    }
                }
            }

            handleHttpError(context, intent);
            DownloadManager.finishDownload(intent.getStringExtra(EXTRA_LOCATION_URL));

            if (messageUri != null) {
                onMessageReceived(context, messageUri);
            }

            if (errorMessage != null) {
                onError(context, errorMessage);
            }
        }).start();
    }

    private String parseSenderAddressFromPdu(Context context, byte[] data, String locationUrl, MmsConfig.Overridden mmsConfig) {
        if (data == null || data.length < 1) {
            // Update the retrieve status of the NotificationInd
            updateNotificationIndRetrieveStatus(context, locationUrl, PduHeaders.RETRIEVE_STATUS_ERROR_END);
            return null;
        }
        final GenericPdu pdu = new PduParser(data, mmsConfig.getSupportMmsContentDisposition()).parse();
        if (!(pdu instanceof RetrieveConf)) {
            return null;
        }
        return pdu.getFrom().getString();
    }

    private void updateNotificationIndRetrieveStatus(Context context, String locationUrl, int retrieveStatus) {
        final ContentValues values = new ContentValues(1);
        values.put(Telephony.Mms.RETRIEVE_STATUS, retrieveStatus);
        SqliteWrapper.update(
                context,
                context.getContentResolver(),
                Telephony.Mms.CONTENT_URI,
                values,
                LOCATION_SELECTION,
                new String[]{
                        Integer.toString(PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND),
                        locationUrl
                });
    }

    private void handleHttpError(Context context, Intent intent) {
        final int httpError = intent.getIntExtra(SmsManager.EXTRA_MMS_HTTP_STATUS, 0);
        if (httpError == 404 || httpError == 400) {
            // Delete the corresponding NotificationInd
            SqliteWrapper.delete(context,
                    context.getContentResolver(),
                    Telephony.Mms.CONTENT_URI,
                    LOCATION_SELECTION,
                    new String[]{
                            Integer.toString(PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND),
                            intent.getStringExtra(EXTRA_LOCATION_URL)
                    });
        }
    }

    private static NotificationInd getNotificationInd(Context context, Intent intent) throws MmsException {
        return (NotificationInd) PduPersister.getPduPersister(context).load((Uri) intent.getParcelableExtra(EXTRA_URI));
    }

    private static abstract class CommonAsyncTask extends AsyncTask<Void, Void, Void> {
        protected final Context mContext;
        protected final TransactionSettings mTransactionSettings;
        final NotificationInd mNotificationInd;
        final String mContentLocation;

        CommonAsyncTask(Context context, TransactionSettings settings, NotificationInd ind) {
            mContext = context;
            mTransactionSettings = settings;
            mNotificationInd = ind;
            mContentLocation = new String(ind.getContentLocation());
        }

        /**
         * A common method to send a PDU to MMSC.
         *
         * @param pdu     A byte array which contains the data of the PDU.
         * @param mmscUrl Url of the recipient MMSC.
         * @return A byte array which contains the response data.
         * If an HTTP error code is returned, an IOException will be thrown.
         * @throws java.io.IOException                 if any error occurred on network interface or
         *                                             an HTTP error code(>=400) returned from the server.
         * @throws com.google.android.mms.MmsException if pdu is null.
         */
        byte[] sendPdu(byte[] pdu, String mmscUrl) throws IOException, MmsException {
            return sendPdu(SendingProgressTokenManager.NO_TOKEN, pdu, mmscUrl);
        }

        /**
         * A common method to send a PDU to MMSC.
         *
         * @param pdu A byte array which contains the data of the PDU.
         * @return A byte array which contains the response data.
         * If an HTTP error code is returned, an IOException will be thrown.
         * @throws java.io.IOException                 if any error occurred on network interface or
         *                                             an HTTP error code(>=400) returned from the server.
         * @throws com.google.android.mms.MmsException if pdu is null.
         */
        byte[] sendPdu(byte[] pdu) throws IOException, MmsException {
            return sendPdu(SendingProgressTokenManager.NO_TOKEN, pdu,
                    mTransactionSettings.getMmscUrl());
        }

        /**
         * A common method to send a PDU to MMSC.
         *
         * @param token   The token to identify the sending progress.
         * @param pdu     A byte array which contains the data of the PDU.
         * @param mmscUrl Url of the recipient MMSC.
         * @return A byte array which contains the response data.
         * If an HTTP error code is returned, an IOException will be thrown.
         * @throws java.io.IOException                 if any error occurred on network interface or
         *                                             an HTTP error code(>=400) returned from the server.
         * @throws com.google.android.mms.MmsException if pdu is null.
         */
        private byte[] sendPdu(long token, byte[] pdu,
                               String mmscUrl) throws IOException, MmsException {
            if (pdu == null) {
                throw new MmsException();
            }

            if (mmscUrl == null) {
                throw new IOException("Cannot establish route: mmscUrl is null");
            }

            if (com.android.mms.transaction.Transaction.useWifi(mContext)) {
                return HttpUtils.httpConnection(
                        mContext, token,
                        mmscUrl,
                        pdu, HttpUtils.HTTP_POST_METHOD,
                        false, null, 0);
            }

            Utils.ensureRouteToHost(mContext, mmscUrl, mTransactionSettings.getProxyAddress());
            return HttpUtils.httpConnection(
                    mContext, token,
                    mmscUrl,
                    pdu, HttpUtils.HTTP_POST_METHOD,
                    mTransactionSettings.isProxySet(),
                    mTransactionSettings.getProxyAddress(),
                    mTransactionSettings.getProxyPort());
        }
    }

    private static class NotifyRespTask extends CommonAsyncTask {
        NotifyRespTask(Context context, NotificationInd ind, TransactionSettings settings) {
            super(context, settings, ind);
        }

        @Override
        protected Void doInBackground(Void... params) {
            // Create the M-NotifyResp.ind
            NotifyRespInd notifyRespInd = null;
            try {
                notifyRespInd = new NotifyRespInd(
                        PduHeaders.CURRENT_MMS_VERSION,
                        mNotificationInd.getTransactionId(),
                        STATUS_RETRIEVED);

                // Pack M-NotifyResp.ind and send it
                if (com.android.mms.MmsConfig.getNotifyWapMMSC()) {
                    sendPdu(new PduComposer(mContext, notifyRespInd).make(), mContentLocation);
                } else {
                    sendPdu(new PduComposer(mContext, notifyRespInd).make());
                }
            } catch (MmsException e) {
                Log.e(TAG, "error", e);
            } catch (IOException e) {
                Log.e(TAG, "error", e);
            }
            return null;
        }
    }

    private static class AcknowledgeIndTask extends CommonAsyncTask {
        private final RetrieveConf mRetrieveConf;

        AcknowledgeIndTask(Context context, NotificationInd ind, TransactionSettings settings, RetrieveConf rc) {
            super(context, settings, ind);
            mRetrieveConf = rc;
        }

        @Override
        protected Void doInBackground(Void... params) {
            // Send M-Acknowledge.ind to MMSC if required.
            // If the Transaction-ID isn't set in the M-Retrieve.conf, it means
            // the MMS proxy-relay doesn't require an ACK.
            byte[] tranId = mRetrieveConf.getTransactionId();
            if (tranId != null) {
                Log.v(TAG, "sending ACK to MMSC: " + mTransactionSettings.getMmscUrl());
                // Create M-Acknowledge.ind
                com.google.android.mms.pdu_alt.AcknowledgeInd acknowledgeInd = null;

                try {
                    acknowledgeInd = new com.google.android.mms.pdu_alt.AcknowledgeInd(
                            PduHeaders.CURRENT_MMS_VERSION, tranId);

                    // insert the 'from' address per spec
                    String lineNumber = Utils.getMyPhoneNumber(mContext);

                    if (lineNumber != null) {
                        acknowledgeInd.setFrom(new EncodedStringValue(lineNumber));
                    } else {
                        acknowledgeInd.setFrom(new EncodedStringValue(""));
                    }

                    // Pack M-Acknowledge.ind and send it
                    if (com.android.mms.MmsConfig.getNotifyWapMMSC()) {
                        sendPdu(new PduComposer(mContext, acknowledgeInd).make(), mContentLocation);
                    } else {
                        sendPdu(new PduComposer(mContext, acknowledgeInd).make());
                    }
                } catch (InvalidHeaderValueException e) {
                    Log.e(TAG, "error", e);
                } catch (MmsException e) {
                    Log.e(TAG, "error", e);
                } catch (IOException e) {
                    Log.e(TAG, "error", e);
                }
            }
            return null;
        }
    }

    private List<CommonAsyncTask> getNotificationTask(Context context, Intent intent, byte[] response, int subscriptionId) {
        if (response.length == 0) {
            Log.v(TAG, "MmsReceivedReceiver.sendNotification blank response");
            return null;
        }

        if (getMmscInfoForReceptionAck(context, subscriptionId) == null) {
            Log.v(TAG, "No MMSC information set, so no notification tasks will be able to complete");
            return null;
        }

        final GenericPdu pdu =
                (new PduParser(response, new MmsConfig.Overridden(new MmsConfig(context), null).
                        getSupportMmsContentDisposition())).parse();
        if (!(pdu instanceof RetrieveConf)) {
            android.util.Log.e(TAG, "MmsReceivedReceiver.sendNotification failed to parse pdu");
            return null;
        }

        try {
            final NotificationInd ind = getNotificationInd(context, intent);
            final MmscInformation mmsc = getMmscInfoForReceptionAck(context, subscriptionId);
            final TransactionSettings transactionSettings = new TransactionSettings(mmsc.mmscUrl, mmsc.mmsProxy, mmsc.proxyPort);

            final List<CommonAsyncTask> responseTasks = new ArrayList<>();
            responseTasks.add(new AcknowledgeIndTask(context, ind, transactionSettings, (RetrieveConf) pdu));
            responseTasks.add(new NotifyRespTask(context, ind, transactionSettings));

            return responseTasks;
        } catch (MmsException e) {
            Log.e(TAG, "error", e);
            return null;
        }
    }

    public static class MmscInformation {
        String mmscUrl;
        String mmsProxy;
        int proxyPort;

        public MmscInformation(String mmscUrl, String mmsProxy, int proxyPort) {
            this.mmscUrl = mmscUrl;
            this.mmsProxy = mmsProxy;
            this.proxyPort = proxyPort;
        }
    }
}
