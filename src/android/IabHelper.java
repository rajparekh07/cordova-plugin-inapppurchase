/* Copyright (c) 2012 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alexdisler.inapppurchases;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender.SendIntentException;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.ConsumeParams;
import com.android.billingclient.api.ConsumeResponseListener;
import com.android.billingclient.api.PurchasesResponseListener;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.SkuDetailsParams;
import com.android.billingclient.api.SkuDetailsResponseListener;

import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.stream.Collectors;


/**
 * Provides convenience methods for in-app billing. You can create one instance of this
 * class for your application and use it to process in-app billing operations.
 * It provides synchronous (blocking) and asynchronous (non-blocking) methods for
 * many common in-app billing operations, as well as automatic signature
 * verification.
 *
 * After instantiating, you must perform setup in order to start using the object.
 * To perform setup, call the {@link #startSetup} method and provide a listener;
 * that listener will be notified when setup is complete, after which (and not before)
 * you may call other methods.
 *
 * After setup is complete, you will typically want to request an inventory of owned
 * items and subscriptions. See {@link #queryInventory}, {@link #queryInventoryAsync}
 * and related methods.
 *
 * When you are done with this object, don't forget to call {@link #dispose}
 * to ensure proper cleanup. This object holds a binding to the in-app billing
 * service, which will leak unless you dispose of it correctly. If you created
 * the object on an Activity's onCreate method, then the recommended
 * place to dispose of it is the Activity's onDestroy method.
 *
 * A note about threading: When using this object from a background thread, you may
 * call the blocking versions of methods; when using from a UI thread, call
 * only the asynchronous versions and handle the results via callbacks.
 * Also, notice that you can only call one asynchronous operation at a time;
 * attempting to start a second asynchronous operation while the first one
 * has not yet completed will result in an exception being thrown.
 *
 * @author Bruno Oliveira (Google)
 *
 */
public class IabHelper implements PurchasesUpdatedListener {
    // Is debug logging enabled?
    boolean mDebugLog = true;
    String mDebugTag = "IabHelper";

    // Can we skip the online purchase verification?
    // (Only allowed if the app is debuggable)
	private boolean mSkipPurchaseVerification = false;

    // Is setup done?
    boolean mSetupDone = false;

    // Has this object been disposed of? (If so, we should ignore callbacks, etc)
    boolean mDisposed = false;

    // Are subscriptions supported?
    boolean mSubscriptionsSupported = false;

    // Is an asynchronous operation in progress?
    // (only one at a time can be in progress)
    boolean mAsyncInProgress = false;

    // (for logging/debugging)
    // if mAsyncInProgress == true, what asynchronous operation is in progress?
    String mAsyncOperation = "";

    // Context we were passed during initialization
    Context mContext;

    // Connection to the service
    ServiceConnection mServiceConn;

    private BillingClient billingClient;


    // The request code used to launch purchase flow
    int mRequestCode;

    // The item type of the current purchase flow
    String mPurchasingItemType;

    // Public key for verifying signature, in base64 encoding
    String mSignatureBase64 = null;

    // Billing response codes
    public static final int BILLING_RESPONSE_RESULT_OK = 0;
    public static final int BILLING_RESPONSE_RESULT_USER_CANCELED = 1;
    public static final int BILLING_RESPONSE_RESULT_BILLING_UNAVAILABLE = 3;
    public static final int BILLING_RESPONSE_RESULT_ITEM_UNAVAILABLE = 4;
    public static final int BILLING_RESPONSE_RESULT_DEVELOPER_ERROR = 5;
    public static final int BILLING_RESPONSE_RESULT_ERROR = 6;
    public static final int BILLING_RESPONSE_RESULT_ITEM_ALREADY_OWNED = 7;
    public static final int BILLING_RESPONSE_RESULT_ITEM_NOT_OWNED = 8;

    // IAB Helper error codes
    public static final int IABHELPER_ERROR_BASE = -1000;
    public static final int IABHELPER_REMOTE_EXCEPTION = -1001;
    public static final int IABHELPER_BAD_RESPONSE = -1002;
    public static final int IABHELPER_VERIFICATION_FAILED = -1003;
    public static final int IABHELPER_SEND_INTENT_FAILED = -1004;
    public static final int IABHELPER_USER_CANCELLED = -1005;
    public static final int IABHELPER_UNKNOWN_PURCHASE_RESPONSE = -1006;
    public static final int IABHELPER_MISSING_TOKEN = -1007;
    public static final int IABHELPER_UNKNOWN_ERROR = -1008;
    public static final int IABHELPER_SUBSCRIPTIONS_NOT_AVAILABLE = -1009;
    public static final int IABHELPER_INVALID_CONSUMPTION = -1010;

    // Keys for the responses from InAppBillingService
    public static final String RESPONSE_CODE = "RESPONSE_CODE";
    public static final String RESPONSE_GET_SKU_DETAILS_LIST = "DETAILS_LIST";
    public static final String RESPONSE_BUY_INTENT = "BUY_INTENT";
    public static final String RESPONSE_INAPP_PURCHASE_DATA = "INAPP_PURCHASE_DATA";
    public static final String RESPONSE_INAPP_SIGNATURE = "INAPP_DATA_SIGNATURE";
    public static final String RESPONSE_INAPP_ITEM_LIST = "INAPP_PURCHASE_ITEM_LIST";
    public static final String RESPONSE_INAPP_PURCHASE_DATA_LIST = "INAPP_PURCHASE_DATA_LIST";
    public static final String RESPONSE_INAPP_SIGNATURE_LIST = "INAPP_DATA_SIGNATURE_LIST";
    public static final String INAPP_CONTINUATION_TOKEN = "INAPP_CONTINUATION_TOKEN";

    // Item types
    public static final String ITEM_TYPE_INAPP = "inapp";
    public static final String ITEM_TYPE_SUBS = "subs";

    // some fields on the getSkuDetails response bundle
    public static final String GET_SKU_DETAILS_ITEM_LIST = "ITEM_ID_LIST";
    public static final String GET_SKU_DETAILS_ITEM_TYPE_LIST = "ITEM_TYPE_LIST";

    /**
     * Creates an instance. After creation, it will not yet be ready to use. You must perform
     * setup by calling {@link #startSetup} and wait for setup to complete. This constructor does not
     * block and is safe to call from a UI thread.
     *
     * @param ctx Your application or Activity context. Needed to bind to the in-app billing service.
     * @param base64PublicKey Your application's public key, encoded in base64.
     *     This is used for verification of purchase signatures. You can find your app's base64-encoded
     *     public key in your application's page on Google Play Developer Console. Note that this
     *     is NOT your "developer public key".
     */
    public IabHelper(Context ctx, String base64PublicKey) {
        mContext = ctx;
        mSignatureBase64 = base64PublicKey;
        logDebug("IAB helper created.");
    }

    /**
     * Enables or disable debug logging through LogCat.
     */
    public void enableDebugLogging(boolean enable, String tag) {
        checkNotDisposed();
        mDebugLog = enable;
        mDebugTag = tag;
    }

    public void enableDebugLogging(boolean enable) {
        checkNotDisposed();
        mDebugLog = enable;
    }

    public void setSkipPurchaseVerification(boolean shouldSkipPurchaseVerification) {
        mSkipPurchaseVerification = shouldSkipPurchaseVerification;
    }

    @Override
    public void onPurchasesUpdated(@NonNull BillingResult billingResult, @Nullable List<com.android.billingclient.api.Purchase> list) {
        IabResult result;

        checkNotDisposed();
        checkSetupDone("handleActivityResult");

        // end of async purchase operation that started on launchPurchaseFlow
        flagEndAsync();

        if (list == null) {
            logError("Null data in IAB activity result.");
            result = new IabResult(IABHELPER_BAD_RESPONSE, "Null data in IAB result");
            if (mPurchaseListener != null) mPurchaseListener.onIabPurchaseFinished(result, null);
            return;
        }

        int responseCode = billingResult.getResponseCode();

        for (com.android.billingclient.api.Purchase data: list)
        {
            String purchaseData = data.getOriginalJson();
            String dataSignature = data.getSignature();

            if (responseCode == BILLING_RESPONSE_RESULT_OK) {
                logDebug("Successful resultcode from purchase activity.");
                logDebug("Purchase data: " + purchaseData);
                logDebug("Data signature: " + dataSignature);
                logDebug("Extras: " + data.getPackageName());
                logDebug("Expected item type: " + mPurchasingItemType);

                if (purchaseData == null || dataSignature == null) {
                    logError("BUG: either purchaseData or dataSignature is null.");
                    logDebug("Extras: " + data.getOrderId().toString());
                    result = new IabResult(IABHELPER_UNKNOWN_ERROR, "IAB returned null purchaseData or dataSignature");
                    if (mPurchaseListener != null) mPurchaseListener.onIabPurchaseFinished(result, null);
                    return;
                }

                Purchase purchase = null;
                try {
                    purchase = new Purchase(mPurchasingItemType, purchaseData, dataSignature);
                    String sku = purchase.getSku();
                    // Only allow purchase verification to be skipped if we are debuggable
                    boolean skipPurchaseVerification = (this.mSkipPurchaseVerification  &&
                            ((mContext.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0));
                    // Verify signature
                    if (!skipPurchaseVerification) {
                        if (!Security.verifyPurchase(mSignatureBase64, purchaseData, dataSignature)) {
                            logError("Purchase signature verification FAILED for sku " + sku);
                            result = new IabResult(IABHELPER_VERIFICATION_FAILED, "Signature verification failed for sku " + sku);
                            if (mPurchaseListener != null) mPurchaseListener.onIabPurchaseFinished(result, purchase);
                            return;
                        }
                        logDebug("Purchase signature successfully verified.");
                    }
                }
                catch (JSONException e) {
                    logError("Failed to parse purchase data.");
                    e.printStackTrace();
                    result = new IabResult(IABHELPER_BAD_RESPONSE, "Failed to parse purchase data.");
                    if (mPurchaseListener != null) mPurchaseListener.onIabPurchaseFinished(result, null);
                    return;
                }

                if (mPurchaseListener != null) {
                    mPurchaseListener.onIabPurchaseFinished(new IabResult(BILLING_RESPONSE_RESULT_OK, "Success"), purchase);
                }
            }
            else {
                logError("Purchase failed. Result code: " + Integer.toString(responseCode)
                        + ". Response: " + getResponseDesc(responseCode));
                result = new IabResult(IABHELPER_UNKNOWN_PURCHASE_RESPONSE, "Unknown purchase response.");
                if (mPurchaseListener != null) mPurchaseListener.onIabPurchaseFinished(result, null);
            }
        }
    }

    /**
     * Callback for setup process. This listener's {@link #onIabSetupFinished} method is called
     * when the setup process is complete.
     */
    public interface OnIabSetupFinishedListener {
        /**
         * Called to notify that setup is complete.
         *
         * @param result The result of the setup process.
         */
        public void onIabSetupFinished(IabResult result);
    }

    /**
     * Starts the setup process. This will start up the setup process asynchronously.
     * You will be notified through the listener when the setup process is complete.
     * This method is safe to call from a UI thread.
     *
     * @param listener The listener to notify when the setup process is complete.
     */
    public void startSetup(final OnIabSetupFinishedListener listener) {
        // If already set up, can't do it again.
        checkNotDisposed();
        if (mSetupDone) throw new IllegalStateException("IAB helper is already set up.");

        // Connection to IAB service
        logDebug("Starting in-app billing setup.");

        billingClient = BillingClient.newBuilder(mContext).enablePendingPurchases().setListener(this).build();
        billingClient.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(BillingResult billingResult) {
                if (mDisposed) return;
                logDebug("Billing service connected.");
                String packageName = mContext.getPackageName();
                try {
                    logDebug("Checking for in-app billing 3 support.");

                    // check for in-app billing v3 support
                    int response = billingResult.getResponseCode();
                    if (response != BILLING_RESPONSE_RESULT_OK) {
                        if (listener != null) listener.onIabSetupFinished(new IabResult(response,
                                "Error checking for billing v3 support."));

                        // if in-app purchases aren't supported, neither are subscriptions.
                        mSubscriptionsSupported = false;
                        return;
                    }
                    logDebug("In-app billing version 3 supported for " + packageName);

                    // check for v3 subscriptions support
                    response = billingClient.isFeatureSupported(BillingClient.FeatureType.SUBSCRIPTIONS).getResponseCode();
                    if (response == BILLING_RESPONSE_RESULT_OK) {
                        logDebug("Subscriptions AVAILABLE.");
                        mSubscriptionsSupported = true;
                    }
                    else {
                        logDebug("Subscriptions NOT AVAILABLE. Response: " + response);
                    }

                    mSetupDone = true;
                }
                catch (Exception e) {
                    if (listener != null) {
                        listener.onIabSetupFinished(new IabResult(IABHELPER_REMOTE_EXCEPTION,
                                "RemoteException while setting up in-app billing."));
                    }
                    e.printStackTrace();
                    return;
                }

                if (listener != null) {
                    listener.onIabSetupFinished(new IabResult(BILLING_RESPONSE_RESULT_OK, "Setup successful."));
                }
            }

            @Override
            public void onBillingServiceDisconnected() {
                // Logic from ServiceConnection.onServiceDisconnected should be moved here.
            }
        });
    }

    /**
     * Dispose of object, releasing resources. It's very important to call this
     * method when you are done with this object. It will release any resources
     * used by it such as service connections. Naturally, once the object is
     * disposed of, it can't be used again.
     */
    public void dispose() {
        logDebug("Disposing.");
        mSetupDone = false;
        if (mServiceConn != null) {
            logDebug("Unbinding from service.");
            if (mContext != null) mContext.unbindService(mServiceConn);
        }
        mDisposed = true;
        mContext = null;
        mServiceConn = null;
        mPurchaseListener = null;
    }

    private void checkNotDisposed() {
        if (mDisposed) throw new IllegalStateException("IabHelper was disposed of, so it cannot be used.");
    }

    /** Returns whether subscriptions are supported. */
    public boolean subscriptionsSupported() {
        checkNotDisposed();
        return mSubscriptionsSupported;
    }


    /**
     * Callback that notifies when a purchase is finished.
     */
    public interface OnIabPurchaseFinishedListener {
        /**
         * Called to notify that an in-app purchase finished. If the purchase was successful,
         * then the sku parameter specifies which item was purchased. If the purchase failed,
         * the sku and extraData parameters may or may not be null, depending on how far the purchase
         * process went.
         *
         * @param result The result of the purchase.
         * @param info The purchase information (null if purchase failed)
         */
        public void onIabPurchaseFinished(IabResult result, Purchase info);
    }

    // The listener registered on launchPurchaseFlow, which we have to call back when
    // the purchase finishes
    OnIabPurchaseFinishedListener mPurchaseListener;

    public void launchPurchaseFlow(Activity act, String sku, int requestCode, OnIabPurchaseFinishedListener listener) {
        launchPurchaseFlow(act, sku, requestCode, listener, "");
    }

    public void launchPurchaseFlow(Activity act, String sku, int requestCode,
            OnIabPurchaseFinishedListener listener, String extraData) {
        launchPurchaseFlow(act, sku, ITEM_TYPE_INAPP, requestCode, listener, extraData);
    }

    public void launchSubscriptionPurchaseFlow(Activity act, String sku, int requestCode,
            OnIabPurchaseFinishedListener listener) {
        launchSubscriptionPurchaseFlow(act, sku, requestCode, listener, "");
    }

    public void launchSubscriptionPurchaseFlow(Activity act, String sku, int requestCode,
            OnIabPurchaseFinishedListener listener, String extraData) {
        launchPurchaseFlow(act, sku, ITEM_TYPE_SUBS, requestCode, listener, extraData);
    }

    /**
     * Initiate the UI flow for an in-app purchase. Call this method to initiate an in-app purchase,
     * which will involve bringing up the Google Play screen. The calling activity will be paused while
     * the user interacts with Google Play, and the result will be delivered via the activity's
     * {@link android.app.Activity} method, at which point you must call
     * this object's {@link #handleActivityResult} method to continue the purchase flow. This method
     * MUST be called from the UI thread of the Activity.
     *
     * @param act The calling activity.
     * @param sku The sku of the item to purchase.
     * @param itemType indicates if it's a product or a subscription (ITEM_TYPE_INAPP or ITEM_TYPE_SUBS)
     * @param requestCode A request code (to differentiate from other responses --
     *     as in {@link android.app.Activity#startActivityForResult}).
     * @param listener The listener to notify when the purchase process finishes
     * @param extraData Extra data (developer payload), which will be returned with the purchase data
     *     when the purchase completes. This extra data will be permanently bound to that purchase
     *     and will always be returned when the purchase is queried.
     */
    public void launchPurchaseFlow(Activity act, String sku, String itemType, int requestCode,
                        OnIabPurchaseFinishedListener listener, String extraData) {
        checkNotDisposed();
        checkSetupDone("launchPurchaseFlow");
        flagStartAsync("launchPurchaseFlow");
        IabResult result;

        if (itemType.equals(ITEM_TYPE_SUBS) && !mSubscriptionsSupported) {
            IabResult r = new IabResult(IABHELPER_SUBSCRIPTIONS_NOT_AVAILABLE,
                    "Subscriptions are not available.");
            flagEndAsync();
            if (listener != null) listener.onIabPurchaseFinished(r, null);
            return;
        }

        try {
            logDebug("Constructing buy intent for " + sku + ", item type: " + itemType);

            SkuDetailsParams.Builder params = SkuDetailsParams.newBuilder();

            List<String> skus = new ArrayList<String>();
            skus.add(sku);
            params.setSkusList(skus);
            params.setType(itemType);
            params.build();

            billingClient.querySkuDetailsAsync(params.build(), new SkuDetailsResponseListener() {
                @Override
                public void onSkuDetailsResponse(@NonNull BillingResult billingResult, @Nullable List<com.android.billingclient.api.SkuDetails> list) {
                    int response = billingResult.getResponseCode();
                    if (response != BILLING_RESPONSE_RESULT_OK) {
                        logError("Unable to buy item, Error response: " + getResponseDesc(response));
                        flagEndAsync();
                        IabResult result = new IabResult(response, "Unable to buy item");
                        if (listener != null) listener.onIabPurchaseFinished(result, null);
                        return;
                    }

                    mRequestCode = requestCode;
                    mPurchaseListener = listener;
                    mPurchasingItemType = itemType;

                    BillingFlowParams purchaseParams = BillingFlowParams
                            .newBuilder()
                            .setObfuscatedAccountId(extraData)
                            .setSkuDetails(list.get(0))
                        .build();

                    billingClient.launchBillingFlow((Activity) mContext, purchaseParams);
                    flagEndAsync();
                }
            });
        }
        catch (Exception e) {
            logError("Exception while launching purchase flow for sku " + sku);
            e.printStackTrace();
            flagEndAsync();

            result = new IabResult(IABHELPER_SEND_INTENT_FAILED, "Failed to send intent.");
            if (listener != null) listener.onIabPurchaseFinished(result, null);
        }
    }

    /**
     * Handles an activity result that's part of the purchase flow in in-app billing. If you
     * are calling {@link #launchPurchaseFlow}, then you must call this method from your
     * Activity's {@link android.app.Activity@onActivityResult} method. This method
     * MUST be called from the UI thread of the Activity.
     *
     * @param requestCode The requestCode as you received it.
     * @param resultCode The resultCode as you received it.
     * @param data The data (Intent) as you received it.
     * @return Returns true if the result was related to a purchase flow and was handled;
     *     false if the result was not related to a purchase, in which case you should
     *     handle it normally.
     */
    public boolean handleActivityResult(int requestCode, int resultCode, Intent data) {
        IabResult result;
        if (requestCode != mRequestCode) return false;

        checkNotDisposed();
        checkSetupDone("handleActivityResult");

        // end of async purchase operation that started on launchPurchaseFlow
        flagEndAsync();

        if (data == null) {
            logError("Null data in IAB activity result.");
            result = new IabResult(IABHELPER_BAD_RESPONSE, "Null data in IAB result");
            if (mPurchaseListener != null) mPurchaseListener.onIabPurchaseFinished(result, null);
            return true;
        }

        int responseCode = getResponseCodeFromIntent(data);
        String purchaseData = data.getStringExtra(RESPONSE_INAPP_PURCHASE_DATA);
        String dataSignature = data.getStringExtra(RESPONSE_INAPP_SIGNATURE);

        if (resultCode == Activity.RESULT_OK && responseCode == BILLING_RESPONSE_RESULT_OK) {
            logDebug("Successful resultcode from purchase activity.");
            logDebug("Purchase data: " + purchaseData);
            logDebug("Data signature: " + dataSignature);
            logDebug("Extras: " + data.getExtras());
            logDebug("Expected item type: " + mPurchasingItemType);

            if (purchaseData == null || dataSignature == null) {
                logError("BUG: either purchaseData or dataSignature is null.");
                logDebug("Extras: " + data.getExtras().toString());
                result = new IabResult(IABHELPER_UNKNOWN_ERROR, "IAB returned null purchaseData or dataSignature");
                if (mPurchaseListener != null) mPurchaseListener.onIabPurchaseFinished(result, null);
                return true;
            }

            Purchase purchase = null;
            try {
                purchase = new Purchase(mPurchasingItemType, purchaseData, dataSignature);
                String sku = purchase.getSku();
                // Only allow purchase verification to be skipped if we are debuggable
                boolean skipPurchaseVerification = (this.mSkipPurchaseVerification  &&
                            ((mContext.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0));
                // Verify signature
                if (!skipPurchaseVerification) {
                    if (!Security.verifyPurchase(mSignatureBase64, purchaseData, dataSignature)) {
                        logError("Purchase signature verification FAILED for sku " + sku);
                        result = new IabResult(IABHELPER_VERIFICATION_FAILED, "Signature verification failed for sku " + sku);
                        if (mPurchaseListener != null) mPurchaseListener.onIabPurchaseFinished(result, purchase);
                        return true;
                    }
                    logDebug("Purchase signature successfully verified.");
                }
            }
            catch (JSONException e) {
                logError("Failed to parse purchase data.");
                e.printStackTrace();
                result = new IabResult(IABHELPER_BAD_RESPONSE, "Failed to parse purchase data.");
                if (mPurchaseListener != null) mPurchaseListener.onIabPurchaseFinished(result, null);
                return true;
            }

            if (mPurchaseListener != null) {
                mPurchaseListener.onIabPurchaseFinished(new IabResult(BILLING_RESPONSE_RESULT_OK, "Success"), purchase);
            }
        }
        else if (resultCode == Activity.RESULT_OK) {
            // result code was OK, but in-app billing response was not OK.
            logDebug("Result code was OK but in-app billing response was not OK: " + getResponseDesc(responseCode));
            if (mPurchaseListener != null) {
                result = new IabResult(responseCode, "Problem purchashing item.");
                mPurchaseListener.onIabPurchaseFinished(result, null);
            }
        }
        else if (resultCode == Activity.RESULT_CANCELED) {
            logDebug("Purchase canceled - Response: " + getResponseDesc(responseCode));
            result = new IabResult(IABHELPER_USER_CANCELLED, "User canceled.");
            if (mPurchaseListener != null) mPurchaseListener.onIabPurchaseFinished(result, null);
        }
        else {
            logError("Purchase failed. Result code: " + Integer.toString(resultCode)
                    + ". Response: " + getResponseDesc(responseCode));
            result = new IabResult(IABHELPER_UNKNOWN_PURCHASE_RESPONSE, "Unknown purchase response.");
            if (mPurchaseListener != null) mPurchaseListener.onIabPurchaseFinished(result, null);
        }
        return true;
    }

    public Inventory queryInventory(boolean querySkuDetails, List<String> moreSkus) throws IabException {
        try {
            return queryInventory(querySkuDetails, moreSkus, null).get();
        } catch (Exception e) {
            throw new IabException(new IabResult(BILLING_RESPONSE_RESULT_ERROR, "Query Failed"));
        }
    }

    public CompletableFuture<Inventory> queryInventoryAsync(boolean querySkuDetails, List<String> moreSkus) throws IabException {
        return queryInventory(querySkuDetails, moreSkus, null);
    }

    /**
     * Queries the inventory. This will query all owned items from the server, as well as
     * information on additional skus, if specified. This method may block or take long to execute.
     * Do not call from a UI thread. For that, use the non-blocking version {@link }.
     *
     * @param querySkuDetails if true, SKU details (price, description, etc) will be queried as well
     *     as purchase information.
     * @param moreItemSkus additional PRODUCT skus to query information on, regardless of ownership.
     *     Ignored if null or if querySkuDetails is false.
     * @param moreSubsSkus additional SUBSCRIPTIONS skus to query information on, regardless of ownership.
     *     Ignored if null or if querySkuDetails is false.
     * @throws IabException if a problem occurs while refreshing the inventory.
     */
    public CompletableFuture<Inventory> queryInventory(boolean querySkuDetails, List<String> moreItemSkus,
                                        List<String> moreSubsSkus) throws IabException {
        CompletableFuture<Inventory> rv = new CompletableFuture<>();
        checkNotDisposed();
        checkSetupDone("queryInventory");
        try {
            Inventory inv = new Inventory();
            CompletableFuture<Integer> responseFuture = queryPurchasesAsnyc(inv, ITEM_TYPE_INAPP);

            CompletableFuture<Integer> queryFuture =
                    querySkuDetails
                            ? querySkuDetailsAsync(ITEM_TYPE_INAPP, inv, moreItemSkus)
                            : CompletableFuture.completedFuture(BILLING_RESPONSE_RESULT_OK);

            CompletableFuture<Integer> subPurchaseFuture =
                    mSubscriptionsSupported
                        ? queryPurchasesAsnyc(inv, ITEM_TYPE_SUBS)
                        : CompletableFuture.completedFuture(BILLING_RESPONSE_RESULT_OK);

            CompletableFuture<Integer> subQueryFuture =
                    querySkuDetails & mSubscriptionsSupported
                        ? querySkuDetailsAsync(ITEM_TYPE_SUBS, inv, moreItemSkus)
                        : CompletableFuture.completedFuture(BILLING_RESPONSE_RESULT_OK);

            CompletableFuture
                    .allOf(responseFuture, queryFuture, subQueryFuture, subQueryFuture)
                    .thenRun(() -> rv.complete(inv));

            return rv;
        }
        catch (RemoteException e) {
            throw new IabException(IABHELPER_REMOTE_EXCEPTION, "Remote exception while refreshing inventory.", e);
        }
        catch (JSONException e) {
            throw new IabException(IABHELPER_BAD_RESPONSE, "Error parsing JSON response while refreshing inventory.", e);
        }
    }

    /**
     * Listener that notifies when an inventory query operation completes.
     */
    public interface QueryInventoryFinishedListener {
        /**
         * Called to notify that an inventory query operation completed.
         *
         * @param result The result of the operation.
         * @param inv The inventory.
         */
        public void onQueryInventoryFinished(IabResult result, Inventory inv);
    }


    /**
     * Asynchronous wrapper for inventory query. This will perform an inventory
     * query as described in {@link #queryInventory}, but will do so asynchronously
     * and call back the specified listener upon completion. This method is safe to
     * call from a UI thread.
     *
     * @param querySkuDetails as in {@link #queryInventory}
     * @param moreSkus as in {@link #queryInventory}
     * @param listener The listener to notify when the refresh operation completes.
     */
    public void queryInventoryAsync(final boolean querySkuDetails,
                               final List<String> moreSkus,
                               final QueryInventoryFinishedListener listener) {
        final Handler handler = new Handler();
        checkNotDisposed();
        checkSetupDone("queryInventory");
        flagStartAsync("refresh inventory");
        (new Thread(new Runnable() {
            public void run() {
                IabResult result = new IabResult(BILLING_RESPONSE_RESULT_OK, "Inventory refresh successful.");
                CompletableFuture<Inventory> inv = null;
                try {
                    inv = queryInventoryAsync(querySkuDetails, moreSkus);
                }
                catch (IabException ex) {
                    result = ex.getResult();
                }

                flagEndAsync();
                final IabResult result_f = result;

                inv.thenAccept((inv_f) -> {
                    if (!mDisposed && listener != null) {
                        handler.post(new Runnable() {
                            public void run() {
                                listener.onQueryInventoryFinished(result_f, inv_f);
                            }
                        });
                    }
                });
            }
        })).start();
    }

    public void queryInventoryAsync(QueryInventoryFinishedListener listener) {
        queryInventoryAsync(true, null, listener);
    }

    public void queryInventoryAsync(boolean querySkuDetails, QueryInventoryFinishedListener listener) {
        queryInventoryAsync(querySkuDetails, null, listener);
    }


    /**
     * Consumes a given in-app product. Consuming can only be done on an item
     * that's owned, and as a result of consumption, the user will no longer own it.
     * This method may block or take long to return. Do not call from the UI thread.
     * For that, see {@link #consumeAsync}.
     *
     * @param itemInfo The PurchaseInfo that represents the item to consume.
     * @throws IabException if there is a problem during consumption.
     */
    void consume(Purchase itemInfo, final OnConsumeFinishedListener singleListener) throws IabException {
        checkNotDisposed();
        checkSetupDone("consume");

        if (!itemInfo.mItemType.equals(ITEM_TYPE_INAPP)) {
            throw new IabException(IABHELPER_INVALID_CONSUMPTION,
                    "Items of type '" + itemInfo.mItemType + "' can't be consumed.");
        }

        try {
            String token = itemInfo.getToken();
            String sku = itemInfo.getSku();
            if (token == null || token.equals("")) {
               logError("Can't consume "+ sku + ". No token.");
               throw new IabException(IABHELPER_MISSING_TOKEN, "PurchaseInfo is missing token for sku: "
                   + sku + " " + itemInfo);
            }

            logDebug("Consuming sku: " + sku + ", token: " + token);
            billingClient.consumeAsync(ConsumeParams.newBuilder().setPurchaseToken(token).build(), new ConsumeResponseListener() {
                @Override
                public void onConsumeResponse(@NonNull BillingResult billingResult, @NonNull String s) {
                    int response = billingResult.getResponseCode();

                    try {
                        if (response == BILLING_RESPONSE_RESULT_OK) {
                            logDebug("Successfully consumed sku: " + sku);
                            singleListener.onConsumeFinished(
                                    itemInfo,
                                    new IabResult(BILLING_RESPONSE_RESULT_OK, "Successful consume of sku " + sku));
                        }
                        else {
                            logDebug("Error consuming consuming sku " + sku + ". " + getResponseDesc(response));
                            throw new IabException(response, "Error consuming sku " + sku);
                        }
                    } catch (IabException e) {
                        singleListener.onConsumeFinished(itemInfo, e.getResult());
                    }
                }
            });

        }
        catch (IabException e) {
            singleListener.onConsumeFinished(itemInfo, e.getResult());
        }
    }

    /**
     * Callback that notifies when a consumption operation finishes.
     */
    public interface OnConsumeFinishedListener {
        /**
         * Called to notify that a consumption has finished.
         *
         * @param purchase The purchase that was (or was to be) consumed.
         * @param result The result of the consumption operation.
         */
        public void onConsumeFinished(Purchase purchase, IabResult result);
    }

    /**
     * Callback that notifies when a multi-item consumption operation finishes.
     */
    public interface OnConsumeMultiFinishedListener {
        /**
         * Called to notify that a consumption of multiple items has finished.
         *
         * @param purchases The purchases that were (or were to be) consumed.
         * @param results The results of each consumption operation, corresponding to each
         *     sku.
         */
        public void onConsumeMultiFinished(List<Purchase> purchases, List<IabResult> results);
    }

    /**
     * Asynchronous wrapper to item consumption. Works like {@link #consume}, but
     * performs the consumption in the background and notifies completion through
     * the provided listener. This method is safe to call from a UI thread.
     *
     * @param purchase The purchase to be consumed.
     * @param listener The listener to notify when the consumption operation finishes.
     */
    public void consumeAsync(Purchase purchase, OnConsumeFinishedListener listener) {
        checkNotDisposed();
        checkSetupDone("consume");
        List<Purchase> purchases = new ArrayList<Purchase>();
        purchases.add(purchase);
        consumeAsyncInternal(purchases, listener);
    }

    /**
     * Returns a human-readable description for the given response code.
     *
     * @param code The response code
     * @return A human-readable string explaining the result code.
     *     It also includes the result code numerically.
     */
    public static String getResponseDesc(int code) {
        String[] iab_msgs = ("0:OK/1:User Canceled/2:Unknown/" +
                "3:Billing Unavailable/4:Item unavailable/" +
                "5:Developer Error/6:Error/7:Item Already Owned/" +
                "8:Item not owned").split("/");
        String[] iabhelper_msgs = ("0:OK/-1001:Remote exception during initialization/" +
                                   "-1002:Bad response received/" +
                                   "-1003:Purchase signature verification failed/" +
                                   "-1004:Send intent failed/" +
                                   "-1005:User cancelled/" +
                                   "-1006:Unknown purchase response/" +
                                   "-1007:Missing token/" +
                                   "-1008:Unknown error/" +
                                   "-1009:Subscriptions not available/" +
                                   "-1010:Invalid consumption attempt").split("/");

        if (code <= IABHELPER_ERROR_BASE) {
            int index = IABHELPER_ERROR_BASE - code;
            if (index >= 0 && index < iabhelper_msgs.length) return iabhelper_msgs[index];
            else return String.valueOf(code) + ":Unknown IAB Helper Error";
        }
        else if (code < 0 || code >= iab_msgs.length)
            return String.valueOf(code) + ":Unknown";
        else
            return iab_msgs[code];
    }


    // Checks that setup was done; if not, throws an exception.
    void checkSetupDone(String operation) {
        if (!mSetupDone) {
            logError("Illegal state for operation (" + operation + "): IAB helper is not set up.");
            throw new IllegalStateException("IAB helper is not set up. Can't perform operation: " + operation);
        }
    }

    // Workaround to bug where sometimes response codes come as Long instead of Integer
    int getResponseCodeFromBundle(Bundle b) {
        Object o = b.get(RESPONSE_CODE);
        if (o == null) {
            logDebug("Bundle with null response code, assuming OK (known issue)");
            return BILLING_RESPONSE_RESULT_OK;
        }
        else if (o instanceof Integer) return ((Integer)o).intValue();
        else if (o instanceof Long) return (int)((Long)o).longValue();
        else {
            logError("Unexpected type for bundle response code.");
            logError(o.getClass().getName());
            throw new RuntimeException("Unexpected type for bundle response code: " + o.getClass().getName());
        }
    }

    // Workaround to bug where sometimes response codes come as Long instead of Integer
    int getResponseCodeFromIntent(Intent i) {
        Object o = i.getExtras().get(RESPONSE_CODE);
        if (o == null) {
            logError("Intent with no response code, assuming OK (known issue)");
            return BILLING_RESPONSE_RESULT_OK;
        }
        else if (o instanceof Integer) return ((Integer)o).intValue();
        else if (o instanceof Long) return (int)((Long)o).longValue();
        else {
            logError("Unexpected type for intent response code.");
            logError(o.getClass().getName());
            throw new RuntimeException("Unexpected type for intent response code: " + o.getClass().getName());
        }
    }

    void flagStartAsync(String operation) {
        if (mAsyncInProgress) throw new IllegalStateException("Can't start async operation (" +
                operation + ") because another async operation(" + mAsyncOperation + ") is in progress.");
        mAsyncOperation = operation;
        mAsyncInProgress = true;
        logDebug("Starting async operation: " + operation);
    }

    void flagEndAsync() {
        logDebug("Ending async operation: " + mAsyncOperation);
        mAsyncOperation = "";
        mAsyncInProgress = false;
    }

    CompletableFuture<Integer> queryPurchasesAsnyc(
            Inventory inv,
            String itemType)
            throws JSONException, RemoteException
    {
        CompletableFuture<Integer> rv = new CompletableFuture<>();
        // Query purchases
        logDebug("Querying owned items, item type: " + itemType);
        logDebug("Package name: " + mContext.getPackageName());
        // Only allow purchase verification to be skipped if we are debuggable
        boolean skipPurchaseVerification = (this.mSkipPurchaseVerification  &&
                ((mContext.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0));
        String continueToken = null;

        logDebug("Calling getPurchases with continuation token: " + continueToken);



        billingClient.queryPurchasesAsync(itemType, new PurchasesResponseListener()
        {
            @Override
            public void onQueryPurchasesResponse(
                    @NonNull BillingResult billingResult,
                    @NonNull List<com.android.billingclient.api.Purchase> list)
            {
                boolean verificationFailed = false;

                int response = billingResult.getResponseCode();
                logDebug("Owned items response: " + String.valueOf(response));
                if (response != BILLING_RESPONSE_RESULT_OK) {
                    logDebug("getPurchases() failed: " + getResponseDesc(response));
                    rv.complete(response);
                    return;
                }

                for (com.android.billingclient.api.Purchase x:
                        list) {
                    try {
                        String purchaseData = x.getOriginalJson();
                        String signature = x.getSignature();
                        String sku = x.getSkus().get(0);
                        if (skipPurchaseVerification || Security.verifyPurchase(mSignatureBase64, purchaseData, signature)) {
                            logDebug("Sku is owned: " + sku);
                            Purchase purchase = new Purchase(itemType, purchaseData, signature);

                            if (TextUtils.isEmpty(purchase.getToken())) {
                                logWarn("BUG: empty/null token!");
                                logDebug("Purchase data: " + purchaseData);
                            }

                            // Record ownership and token
                            inv.addPurchase(purchase);
                        }
                        else {
                            logWarn("Purchase signature verification **FAILED**. Not adding item.");
                            logDebug("   Purchase data: " + purchaseData);
                            logDebug("   Signature: " + signature);
                            verificationFailed = true;
                        }


                    } catch (JSONException e) {
                        e.printStackTrace();
                        verificationFailed = true;
                    }
                }

                rv.complete(verificationFailed ? IABHELPER_VERIFICATION_FAILED : BILLING_RESPONSE_RESULT_OK);
            }
        });

        return rv;
    }

    void queryPurchasesAsnyc(
            Inventory inv,
            String itemType,
            CompletableFuture<Integer> promise)
            throws JSONException, RemoteException
    {
        // Query purchases
        logDebug("Querying owned items, item type: " + itemType);
        logDebug("Package name: " + mContext.getPackageName());
        // Only allow purchase verification to be skipped if we are debuggable
        boolean skipPurchaseVerification = (this.mSkipPurchaseVerification  &&
                ((mContext.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0));
        String continueToken = null;

        logDebug("Calling getPurchases with continuation token: " + continueToken);

        billingClient.queryPurchasesAsync(continueToken, new PurchasesResponseListener()
        {
            @Override
            public void onQueryPurchasesResponse(
                    @NonNull BillingResult billingResult,
                    @NonNull List<com.android.billingclient.api.Purchase> list)
            {
                boolean verificationFailed = false;

                int response = billingResult.getResponseCode();
                logDebug("Owned items response: " + String.valueOf(response));
                if (response != BILLING_RESPONSE_RESULT_OK) {
                    logDebug("getPurchases() failed: " + getResponseDesc(response));
                    promise.complete(response);
                    return;
                }

                for (com.android.billingclient.api.Purchase x:
                     list) {
                    try {
                        String purchaseData = x.getOriginalJson();
                        String signature = x.getSignature();
                        String sku = x.getSkus().get(0);
                        if (skipPurchaseVerification || Security.verifyPurchase(mSignatureBase64, purchaseData, signature)) {
                            logDebug("Sku is owned: " + sku);
                            Purchase purchase = new Purchase(itemType, purchaseData, signature);

                            if (TextUtils.isEmpty(purchase.getToken())) {
                                logWarn("BUG: empty/null token!");
                                logDebug("Purchase data: " + purchaseData);
                            }

                            // Record ownership and token
                            inv.addPurchase(purchase);
                        }
                        else {
                            logWarn("Purchase signature verification **FAILED**. Not adding item.");
                            logDebug("   Purchase data: " + purchaseData);
                            logDebug("   Signature: " + signature);
                            verificationFailed = true;
                        }


                    } catch (JSONException e) {
                        e.printStackTrace();
                        verificationFailed = true;
                    }
                }

                promise.complete(verificationFailed ? IABHELPER_VERIFICATION_FAILED : BILLING_RESPONSE_RESULT_OK);
            }
        });
    }

    void querySkuDetailsAsync(
            String purchasingItemType,
            Inventory inv,
            List<String> skus,
            CompletableFuture<Integer> callback)
    {
        logDebug("Querying SKU details.");
        ArrayList<String> skuList = new ArrayList<String>();
        skuList.addAll(inv.getAllOwnedSkus(purchasingItemType));
        if (skus != null) {
            for (String sku : skus) {
                if (!skuList.contains(sku)) {
                    skuList.add(sku);
                }
            }
        }

        if (skuList.size() == 0) {
            logDebug("queryPrices: nothing to do because there are no SKUs.");
            callback.complete(BILLING_RESPONSE_RESULT_OK);
            return;
        }

        Bundle querySkus = new Bundle();
        querySkus.putStringArrayList(GET_SKU_DETAILS_ITEM_LIST, skuList);

        billingClient.querySkuDetailsAsync(
                SkuDetailsParams.newBuilder().setSkusList(skuList).setType(purchasingItemType).build(),
                new SkuDetailsResponseListener()
                {
                    @Override
                    public void onSkuDetailsResponse(@NonNull BillingResult billingResult, @Nullable List<com.android.billingclient.api.SkuDetails> list) {
                        List<String> ids = list.stream().map((x) -> x.getSku()).collect(Collectors.toList());

                        int response = billingResult.getResponseCode();
                        if (response != BILLING_RESPONSE_RESULT_OK) {
                            logDebug("getSkuDetails() 1 failed: " + getResponseDesc(response));
                            callback.complete(response);
                            return;
                        }

                        list.stream().forEach((x) -> {
                            try {
                                inv.addSkuDetails(new SkuDetails(purchasingItemType, x.getOriginalJson()));
                            } catch (JSONException e) {
                                logDebug("getSkuDetails() 2 failed: " + e);
                                logDebug("getSkuDetails() failed at at json: " + x);
                            }
                        });

                        callback.complete(BILLING_RESPONSE_RESULT_OK);
                    }
                });
    }

    CompletableFuture<Integer> querySkuDetailsAsync(
            String purchasingItemType,
            Inventory inv,
            List<String> skus)
    {
        CompletableFuture<Integer> rv = new CompletableFuture<>();

        logDebug("Querying SKU details.");
        ArrayList<String> skuList = new ArrayList<String>();
        skuList.addAll(inv.getAllOwnedSkus(purchasingItemType));
        if (skus != null) {
            for (String sku : skus) {
                if (!skuList.contains(sku)) {
                    skuList.add(sku);
                }
            }
        }

        if (skuList.size() == 0) {
            logDebug("queryPrices: nothing to do because there are no SKUs.");
            rv.complete(BILLING_RESPONSE_RESULT_OK);
            return rv;
        }

        Bundle querySkus = new Bundle();
        querySkus.putStringArrayList(GET_SKU_DETAILS_ITEM_LIST, skuList);

        billingClient.querySkuDetailsAsync(
                SkuDetailsParams.newBuilder().setSkusList(skuList).setType(purchasingItemType).build(),
                new SkuDetailsResponseListener()
                {
                    @Override
                    public void onSkuDetailsResponse(@NonNull BillingResult billingResult, @Nullable List<com.android.billingclient.api.SkuDetails> list) {
                        List<String> ids = list.stream().map((x) -> x.getSku()).collect(Collectors.toList());

                        int response = billingResult.getResponseCode();
                        if (response != BILLING_RESPONSE_RESULT_OK) {
                            logDebug("getSkuDetails() 1 failed: " + getResponseDesc(response));
                            rv.complete(response);
                            return;
                        }

                        list.stream().forEach((x) -> {
                            try {
                                inv.addSkuDetails(new SkuDetails(purchasingItemType, x.getOriginalJson()));
                            } catch (JSONException e) {
                                logDebug("getSkuDetails() 2 failed: " + e);
                                logDebug("getSkuDetails() 3 failed: " + x);
                            }
                        });

                        rv.complete(BILLING_RESPONSE_RESULT_OK);
                    }
                });

        return rv;
    }

    void consumeAsyncInternal(final List<Purchase> purchases,
                              final OnConsumeFinishedListener singleListener) {
        final Handler handler = new Handler();
        flagStartAsync("consume");
        (new Thread(new Runnable() {
            public void run() {
                final List<IabResult> results = new ArrayList<IabResult>();
                for (Purchase purchase : purchases) {
                    try {
                        consume(purchase, singleListener);
                    }
                    catch (IabException ex) {
                        results.add(ex.getResult());
                    }
                }

                flagEndAsync();
                if (!mDisposed && singleListener != null) {
                    handler.post(new Runnable() {
                        public void run() {
                            singleListener.onConsumeFinished(purchases.get(0), results.get(0));
                        }
                    });
                }
            }
        })).start();
    }

    void logDebug(String msg) {
        if (mDebugLog) Log.d(mDebugTag, msg);
    }

    void logError(String msg) {
        Log.e(mDebugTag, "In-app billing error: " + msg);
    }

    void logWarn(String msg) {
        Log.w(mDebugTag, "In-app billing warning: " + msg);
    }
}

interface ResultAwaiter<T> {
    void OnResult(T result);
}
