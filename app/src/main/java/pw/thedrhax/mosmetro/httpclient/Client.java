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

package pw.thedrhax.mosmetro.httpclient;

import android.content.Context;
import android.os.SystemClock;
import android.support.annotation.NonNull;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import pw.thedrhax.util.Listener;
import pw.thedrhax.util.RandomUserAgent;
import pw.thedrhax.util.Randomizer;
import pw.thedrhax.util.Util;

public abstract class Client {
    public static final String HEADER_ACCEPT = "Accept";
    public static final String HEADER_USER_AGENT = "User-Agent";
    public static final String HEADER_REFERER = "Referer";
    public static final String HEADER_CSRF = "X-CSRF-Token";

    protected Map<String,String> headers = new HashMap<>();
    protected Context context;
    protected Randomizer random;
    protected ParsedResponse last_response = new ParsedResponse(this, "", "", 200);

    protected Client(Context context) {
        this.context = context;
    }

    protected void configure() {
        random = new Randomizer(context);

        setHeader(HEADER_USER_AGENT, RandomUserAgent.getRandomUserAgent());
        setHeader(HEADER_ACCEPT, "text/html,application/xhtml+xml," +
                "application/xml;q=0.9,image/webp,*/*;q=0.8");

        int timeout = Util.getIntPreference(context, "pref_timeout", 5);
        if (timeout >= 0) setTimeout(timeout * 1000);
    }

    // Settings methods
    public abstract Client followRedirects(boolean follow);

    public Client setHeader (String name, String value) {
        headers.put(name, value); return this;
    }

    public String getHeader (String name) {
        return headers.containsKey(name) ? headers.get(name) : null;
    }

    public Client resetHeaders () {
        headers = new HashMap<>(); return this;
    }

    public abstract Client setCookie(String url, String name, String value);
    public abstract Map<String,String> getCookies(String url);

    public abstract Client setTimeout(int ms);

    // IO methods
    public abstract ParsedResponse get(String link, Map<String,String> params) throws IOException;
    public abstract ParsedResponse post(String link, Map<String,String> params) throws IOException;
    public abstract InputStream getInputStream(String link) throws IOException;

    @NonNull
    public ParsedResponse response() {
        return last_response;
    }

    // Retry methods
    public ParsedResponse get(final String link, final Map<String,String> params,
                      int retries) throws IOException {
        return new RetryOnException<ParsedResponse>() {
            @Override
            public ParsedResponse body() throws IOException {
                random.delay(running);
                return get(link, params);
            }
        }.run(retries);
    }
    public ParsedResponse post(final String link, final Map<String,String> params,
                       int retries) throws IOException {
        return new RetryOnException<ParsedResponse>() {
            @Override
            public ParsedResponse body() throws IOException {
                random.delay(running);
                return post(link, params);
            }
        }.run(retries);
    }
    public InputStream getInputStream(final String link, int retries) throws IOException {
        return new RetryOnException<InputStream>() {
            @Override
            public InputStream body() throws IOException {
                return getInputStream(link);
            }
        }.run(retries);
    }

    // Cancel current request
    public abstract void stop();

    // Convert methods
    protected static String requestToString (Map<String,String> params) {
        StringBuilder params_string = new StringBuilder();

        if (params != null)
            for (Map.Entry<String,String> entry : params.entrySet())
                params_string
                        .append(params_string.length() == 0 ? "?" : "&")
                        .append(entry.getKey())
                        .append("=")
                        .append(entry.getValue());

        return params_string.toString();
    }

    protected final Listener<Boolean> running = new Listener<Boolean>(true) {
        @Override
        public void onChange(Boolean new_value) {
            if (!new_value) {
                stop();
            }
        }
    };

    public Client setRunningListener(Listener<Boolean> master) {
        running.subscribe(master); return this;
    }

    private abstract class RetryOnException<T> {
        T run(int retries) throws IOException {
            IOException last_ex = null;
            for (int i = 0; i < retries; i++) {
                try {
                    return body();
                } catch (IOException ex) {
                    last_ex = ex;
                    if (running.get()) {
                        SystemClock.sleep(1000);
                    } else {
                        break;
                    }
                }
            }
            throw last_ex;
        }
        public abstract T body() throws IOException;
    }
}
