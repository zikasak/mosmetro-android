/**
 * Wi-Fi в метро (pw.thedrhax.mosmetro, Moscow Wi-Fi autologin)
 * Copyright © 2015 Dmitry Karikh <the.dr.hax@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package pw.thedrhax.mosmetro.authenticator;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

import pw.thedrhax.mosmetro.R;
import pw.thedrhax.mosmetro.authenticator.providers.Enforta;
import pw.thedrhax.mosmetro.authenticator.providers.MAInet;
import pw.thedrhax.mosmetro.authenticator.providers.MosMetroV1;
import pw.thedrhax.mosmetro.authenticator.providers.MosMetroV2;
import pw.thedrhax.mosmetro.authenticator.providers.MosMetroV2mcc;
import pw.thedrhax.mosmetro.authenticator.providers.MosMetroV3;
import pw.thedrhax.mosmetro.authenticator.providers.Unknown;
import pw.thedrhax.mosmetro.httpclient.Client;
import pw.thedrhax.mosmetro.httpclient.ParsedResponse;
import pw.thedrhax.mosmetro.httpclient.clients.OkHttp;
import pw.thedrhax.util.Listener;
import pw.thedrhax.util.Logger;
import pw.thedrhax.util.Randomizer;
import pw.thedrhax.util.Util;

/**
 * Base class for all providers.
 *
 * @author Dmitry Karikh <the.dr.hax@gmail.com>
 * @see LinkedList
 * @see Task
 */

public abstract class Provider extends LinkedList<Task> {
    /**
     * URL used to detect if Captive Portal is present in the current network.
     */
    protected static final String[] GENERATE_204 = {
            "www.google.ru/generate_204",
            "www.google.ru/gen_204",
            "google.com/generate_204",
            // "www.google.com/generate_204",
            "gstatic.com/generate_204",
            // "www.gstatic.com/generate_204",
            // "connectivitycheck.android.com/generate_204",
            // "connectivitycheck.gstatic.com/generate_204"
            "clients1.google.com/generate_204",
            "maps.google.com/generate_204",
            "mt0.google.com/generate_204",
            "mt1.google.com/generate_204",
            "mt2.google.com/generate_204",
            "mt3.google.com/generate_204"
    };

    /**
     * List of supported SSIDs
     */
    public static final String[] SSIDs = {
            "MosMetro_Free",
            "AURA",
            "MosGorTrans_Free",
            "MT_FREE", "MT_FREE_",
            "CPPK_Free",
            "Air_WiFi_Free",
            "MAInet_public"
    };

    protected Context context;
    protected SharedPreferences settings;
    protected Randomizer random;

    private List<Provider> children = new LinkedList<>();
    private boolean initialized = false;

    /**
     * Number of retries for each request
     */
    protected int pref_retry_count;

    /**
     * Default Client used for all network operations
     */
    protected Client client;

    /**
     * Find Provider using already received response from server.
     * @param context   Android Context required to create the new instance.
     * @param response  Instance of ParsedResponse.
     * @return          New Provider instance.
     *
     * @see Client
     */
    @NonNull public static Provider find(Context context, ParsedResponse response) {
        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(context);

        if (MosMetroV3.match(response) && settings.getBoolean("pref_mosmetro_v3", true)) return new MosMetroV3(context, response);
        else if (MosMetroV2.match(response)) return new MosMetroV2(context, response);
        else if (MosMetroV2mcc.match(response)) return new MosMetroV2mcc(context, response);
        else if (MosMetroV1.match(response)) return new MosMetroV1(context, response);
        else if (MAInet.match(response)) return new MAInet(context, response);
        else if (Enforta.match(response)) return new Enforta(context);
        else return new Unknown(context, response);
    }

    /**
     * Find Provider by sending predefined request to get the redirect.
     *
     * @param context   Android Context required to create the new instance.
     * @param running   Listener used to interrupt this method.
     * @return          New Provider instance.
     */
    @NonNull public static Provider find(Context context, Listener<Boolean> running) {
        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(context);
        Logger.log(context.getString(R.string.auth_provider_check));

        ParsedResponse response = generate_204(context, running);
        Provider result = Provider.find(context, response);

        if (result instanceof Unknown && response.getResponseCode() != 204) {
            Logger.log(Logger.LEVEL.DEBUG, response.toString());
            Logger.log(context.getString(R.string.error,
                    context.getString(R.string.auth_error_provider)
            ));
            Logger.log(context.getString(R.string.auth_provider_assume));

            if (settings.getBoolean("pref_mosmetro_v3", true)) {
                return new MosMetroV3(context, response);
            } else {
                return new MosMetroV2(context, response);
            }
        }

        return result;
    }

    /**
     * Check if a particular SSID is supported.
     * @param SSID  SSID of the Wi-Fi network to be tested.
     * @return  True if network is supported; otherwise, false.
     */
    public static boolean isSSIDSupported(String SSID) {
        for (String a : SSIDs) {
            if (a.equals(SSID))
                return true;
        }
        // TODO: Issue #70
        return false;
    }

    /**
     * Main constructor
     * @param context   Android application Context
     */
    public Provider(Context context) {
        this.context = context;
        this.settings = PreferenceManager.getDefaultSharedPreferences(context);
        this.random = new Randomizer(context);
        this.pref_retry_count = Util.getIntPreference(context, "pref_retry_count", 3);
        setClient(new OkHttp(context));
    }

    /**
     * Checks network connection state without binding to a specific provider.
     * This implementation uses generate_204 method, that is default for Android.
     * @return ParsedResponse that contains response code to be compared with 204.
     */
    public static ParsedResponse generate_204(Context context, Listener<Boolean> running) {
        Randomizer random  = new Randomizer(context);

        Client client = new OkHttp(context)
                .trustAllCerts()
                .followRedirects(false)
                .setRunningListener(running);

        ParsedResponse response = new ParsedResponse("<b>Empty response</b>");

        try {
            String url = "http://" + random.choose(GENERATE_204);
            Logger.log(Logger.LEVEL.DEBUG, "Provider | generate_204() | URL: " + url);
            response = client.get(url, null);
        } catch (IOException ex) {
            Logger.log(Logger.LEVEL.DEBUG, ex);
            return response;
        }
        if (response.getResponseCode() != 204) return response;

        try {
            response = client.get("https://" + random.choose(GENERATE_204), null);
        } catch (IOException ex) {
            Logger.log(Logger.LEVEL.DEBUG, ex);
            return response;
        }

        return response;
    }

    /**
     * Checks network connection state for a specific provider.
     * @return True if internet access is available; otherwise, false is returned.
     */
    public boolean isConnected() {
        return isConnected(generate_204(context, running));
    }

    /**
     * Checks ParsedResponse to be a valid generate_204 response.
     * @return True if generate_204 response is valid; otherwise, false is returned.
     */
    protected static boolean isConnected(ParsedResponse response) {
        return response.getResponseCode() == 204;
    }

    /**
     * Get Provider's short description.
     * @return Provider's name.
     */
    public String getName() {
        return this.getClass().getSimpleName();
    }

    public enum RESULT {
        CONNECTED, ALREADY_CONNECTED,           // Success
        NOT_REGISTERED, ERROR, NOT_SUPPORTED,   // Error
        INTERRUPTED                             // Stopped
    }

    /**
     * Add child Provider and run all it's Tasks after 'index'.
     * If parent Provider is already initialized, child will be initialized as well.
     * @return True if all Tasks are added to master and master.init() returns True
     */
    public boolean add(int index, Provider p) {
        children.add(p.setRunningListener(running).setCallback(callback).setClient(client));
        return super.addAll(index, p) && (!initialized || init());
    }

    /**
     * Initialize this Provider and it's children.
     * Warning: May be called more than once!
     * @return true on success, false on error
     */
    protected boolean init() {
        for (Provider p : children) {
            if (!p.init()) {
                Logger.log(context.getString(R.string.error,
                        context.getString(R.string.auth_algorithm_failure)
                ));
                return false;
            }
            running.subscribe(p.running);
        }

        for (Task task : this) {
            if (task instanceof InterceptorTask && !client.interceptors.contains(task)) {
                client.interceptors.add((InterceptorTask) task);
            }
        }

        initialized = true;
        return true;
    }

    /**
     * Reverse effect of init().
     * Warning: May be called more than once!
     */
    protected void deinit() {
        for (Task task : this) {
            if (task instanceof InterceptorTask && client.interceptors.contains(task)) {
                client.interceptors.remove(task);
            }
        }

        for (Provider p : children) {
            running.unsubscribe(p.running);
            p.deinit();
        }

        initialized = false;
    }

    /**
     * Start the connection sequence defined in child classes.
     */
    public RESULT start() {
        HashMap<String,Object> vars = new HashMap<>();
        vars.put("time_start", System.currentTimeMillis());
        vars.put("result", RESULT.ERROR);

        if (!init()) {
            Logger.log(this, "Initialization failed");
            return RESULT.ERROR;
        }

        int progress;
        for (int i = 0; i < size(); i++) {
            if (isStopped()) {
                deinit();
                if (vars.get("result") != RESULT.ERROR) {
                    return (RESULT) vars.get("result");
                } else {
                    return RESULT.INTERRUPTED;
                }
            }

            progress = (i + 1) * 100 / size();
            if (get(i) instanceof NamedTask) {
                Logger.log(((NamedTask)get(i)).getName());
                callback.onProgressUpdate(progress, ((NamedTask)get(i)).getName());
            } else {
                callback.onProgressUpdate(progress);
            }
            if (!get(i).run(vars)) break;
        }

        vars.put("time_end", System.currentTimeMillis());
        new StatisticsTask(this).run(vars);

        deinit();
        Logger.date("<<< ");
        return (RESULT)vars.get("result");
    }

    /**
     * Listener used to stop Provider immediately after
     * variable is changed by another thread
     */
    protected final Listener<Boolean> running = new Listener<>(true);

    /**
     * Subscribe to another Listener to implement cascade notifications
     */
    public Provider setRunningListener(Listener<Boolean> master) {
        running.subscribe(master); return this;
    }

    /**
     * Replace default Client
     */
    public Provider setClient(Client client) {
        this.client = client
                .setRunningListener(running)
                .setDelaysEnabled(settings.getBoolean("pref_delay_always", false));
        return this;
    }

    /**
     * Method used to check if Provider must finish as soon as possible.
     * @return true is Provider must stop, otherwise false.
     */
    protected boolean isStopped() {
        return !running.get();
    }

    /**
     * The ICallback interface is used by other classes to get messages from Provider
     * during runtime.
     */
    public interface ICallback {
        /**
         * Report the progress of algorithm execution.
         * @param progress  Any Integer between 0 and 100.
         */
        void onProgressUpdate(int progress);

        /**
         * Report the progress of algorithm execution with the description of current Task.
         * @param progress  Any Integer between 0 and 100.
         * @param message   Text massage to display in notification.
         */
        void onProgressUpdate(int progress, String message);
    }

    protected ICallback callback = new ICallback() {
        @Override
        public void onProgressUpdate(int progress) {

        }

        @Override
        public void onProgressUpdate(int progress, String message) {

        }
    };

    /**
     * Set callback for this Provider.
     * @param callback  Any implementation of the ICallback interface.
     * @return  Saved instance of this Provider.
     */
    public Provider setCallback(ICallback callback) {
        this.callback = callback; return this;
    }
}
