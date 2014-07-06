/*
 * Copyright (C) 2014 The MoKee OpenSource Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.incallui;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.text.TextUtils;
import android.view.animation.DecelerateInterpolator;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.incallui.ContactInfoCache.ContactCacheEntry;
import com.android.incallui.ContactInfoCache.ContactInfoCacheCallback;
import com.android.incallui.InCallApp.NotificationBroadcastReceiver;
import com.android.internal.telephony.ITelephony;
import com.android.services.telephony.common.CallIdentification;
import com.android.services.telephony.common.Call;

import org.mokee.location.PhoneLocation;
import org.mokee.util.MoKeeUtils;

/**
 * Handles the call card activity that pops up when a call arrives
 */
public class InCallCardActivity extends Activity {

    private static NotificationManager mNotificationManager;
    private Activity mContext;

    private static final int SLIDE_IN_DURATION_MS = 500;
    private static final int IGNORED_NOTIFICATION = 2;
    private static String TAG = "InCallCardActivity";

    private TextView mNameTextView;
    private TextView mLocationTextView;
    private ImageView mContactImageView;

    private String mContactName;
    private String mContactNumber;
    private String mContactLabel;
    private String mContactLocation;

    private Call mCall;

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        mContext = this;
        mNotificationManager =
                (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_IGNORE_CHEEK_PRESSES);

        setContentView(R.layout.card_call_incoming);

        InCallPresenter.getInstance().setCardActivity(this);

        final CallList calls = CallList.getInstance();
        mCall = calls.getIncomingCall();

        CallIdentification identification = mCall.getIdentification();

        // Setup the fields to show the information of the call
        mNameTextView = (TextView) findViewById(R.id.txt_contact_name);
        mLocationTextView = (TextView) findViewById(R.id.txt_location);
        mContactImageView = (ImageView) findViewById(R.id.img_contact);

        // Setup the call button
        Button answer = (Button) findViewById(R.id.btn_answer);
        answer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                InCallPresenter.getInstance().startIncomingCallUi(
                        InCallPresenter.InCallState.INCALL);
                CallCommandClient.getInstance().answerCall(mCall.getCallId());
                finish();
            }
        });
        Button ignore = (Button) findViewById(R.id.btn_ignore);
        ignore.setOnClickListener(new View.OnClickListener() {

            @Override
            public void onClick(View v) {
                try {
                    ITelephony telephonyService = getTelephonyService();
                    if (telephonyService != null) {
                        telephonyService.silenceRinger();
                    }
                } catch (RemoteException ex) {
                    Log.w(TAG, "RemoteException from getPhoneInterface()" + ex.toString());
                }
                CallCommandClient.getInstance().setSystemBarNavigationEnabled(true);
                CallCommandClient.getInstance().setIgnoreCallState(true);
                buildAndSendNotification();
                finish();
            }
        });
        Button reject = (Button) findViewById(R.id.btn_reject);
        reject.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CallCommandClient.getInstance().rejectCall(mCall, false, null);
                finish();
            }
        });

        // Slide in the dialog
        final LinearLayout vg = (LinearLayout) findViewById(R.id.root);

        vg.setTranslationY(getResources().getDimensionPixelSize(R.dimen.incoming_call_card_height));
        vg.animate().translationY(0.0f).setDuration(SLIDE_IN_DURATION_MS)
                .setInterpolator(new DecelerateInterpolator()).start();

        // Lookup contact info
        startContactInfoSearch(identification);
    }

    private void buildAndSendNotification() {
        final Notification.Builder builder = getNotificationBuilder();
        final PendingIntent inCallPendingIntent = createLaunchPendingIntent();
        builder.setContentIntent(inCallPendingIntent);
        // set the content
        builder.setContentText(TextUtils.isEmpty(mContactLabel) ? mContactLocation : mContactNumber + " " + mContactLabel + " " + mContactLocation);
        builder.setSmallIcon(R.drawable.ic_block_contact_holo_dark);
        builder.setContentTitle(mContactName);
        addHangupAction(builder);
        addAnswerAction(builder);

        Notification notification = builder.build();
        Log.d(mContext, "Notifying IGNORED_NOTIFICATION: " + notification);
        mNotificationManager.notify(IGNORED_NOTIFICATION, notification);
    }

    public static void hideNotification() {
        mNotificationManager.cancel(IGNORED_NOTIFICATION);
    }

    private void addHangupAction(Notification.Builder builder) {
        Log.i(mContext, "Will show \"hang-up\" action in the ongoing ignored Notification");

        // TODO: use better asset.
        builder.addAction(R.drawable.stat_sys_phone_call_end,
                getText(R.string.description_target_decline),
                createRejectIgnoredCallPendingIntent(mContext));
    }

    private void addAnswerAction(Notification.Builder builder) {
        Log.i(mContext, "Will show \"hang-up\" action in the ongoing ignored Notification");

        // TODO: use better asset.
        builder.addAction(R.drawable.stat_sys_phone_call,
                getText(R.string.description_target_answer),
                createAnswerIgnoredCallPendingIntent(mContext));
    }

    private static PendingIntent createAnswerIgnoredCallPendingIntent(Context context) {
        final Intent intent = new Intent(InCallApp.ACTION_ANSWER_IGNORED_CALL, null,
                context, NotificationBroadcastReceiver.class);
        return PendingIntent.getBroadcast(context, 0, intent, 0);
    }

    private static PendingIntent createRejectIgnoredCallPendingIntent(Context context) {
        final Intent intent = new Intent(InCallApp.ACTION_REJECT_IGNORED_CALL, null,
                context, NotificationBroadcastReceiver.class);
        return PendingIntent.getBroadcast(context, 0, intent, 0);
    }

    private PendingIntent createLaunchPendingIntent() {

        final Intent intent = InCallPresenter.getInstance().getInCallIntent(/*showdialpad=*/false);

        // PendingIntent that can be used to launch the InCallActivity.  The
        // system fires off this intent if the user pulls down the windowshade
        // and clicks the notification's expanded view.  It's also used to
        // launch the InCallActivity immediately when when there's an incoming
        // call (see the "fullScreenIntent" field below).
        PendingIntent inCallPendingIntent = PendingIntent.getActivity(mContext, 0, intent, 0);

        return inCallPendingIntent;
    }

    /**
     * Starts a query for more contact data for the save primary and secondary calls.
     */
    private void startContactInfoSearch(final CallIdentification identification) {
        final ContactInfoCache cache = ContactInfoCache.getInstance(mContext);

        cache.findInfo(identification, true, new ContactInfoCacheCallback() {
            @Override
            public void onContactInfoComplete(int callId, ContactCacheEntry entry) {
                mContactName = TextUtils.isEmpty(entry.name) ? entry.number : entry.name;
                mContactNumber = entry.number;
                mNameTextView.setText(mContactName);
                String tmp;
                if (MoKeeUtils.isChineseLanguage() && !MoKeeUtils.isTWLanguage()) {
                    tmp = PhoneLocation.getCityFromPhone(entry.number);
                } else {
                    tmp = TextUtils.isEmpty(entry.location) ? CallerInfo.getGeoDescription(
                            mContext, entry.number) : entry.location;
                }
                mContactLabel = entry.label;
                mContactLocation = TextUtils.isEmpty(tmp) ? getString(R.string.unknown) : tmp;
                mLocationTextView.setText(TextUtils.isEmpty(entry.label) ? mContactLocation : entry.label
                        + " " + mContactLocation);
                if (entry.personUri != null) {
                    CallerInfoUtils.sendViewNotification(mContext, entry.personUri);
                }
            }

            @Override
            public void onImageLoadComplete(int callId, ContactCacheEntry entry) {
                if (entry.photo != null) {
                    Drawable current = mContactImageView.getDrawable();
                    AnimationUtils.startCrossFade(mContactImageView, current, entry.photo);
                }
            }
        });
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent keyEvent) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BACK:
                return false;
        }
        return super.onKeyDown(keyCode, keyEvent);
    }

    static ITelephony getTelephonyService() {
        return ITelephony.Stub.asInterface(
                ServiceManager.checkService(Context.TELEPHONY_SERVICE));
    }

    private Notification.Builder getNotificationBuilder() {
        final Notification.Builder builder = new Notification.Builder(mContext);
        builder.setOngoing(true);

        // Make the notification prioritized over the other normal notifications.
        builder.setPriority(Notification.PRIORITY_HIGH);

        return builder;
    }

}
