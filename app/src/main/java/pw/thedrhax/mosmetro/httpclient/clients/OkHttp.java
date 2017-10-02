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

package pw.thedrhax.mosmetro.httpclient.clients;

import android.content.Context;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import pw.thedrhax.mosmetro.httpclient.Client;
import pw.thedrhax.mosmetro.httpclient.ParsedResponse;
import pw.thedrhax.util.AndroidHacks;
import pw.thedrhax.util.Logger;

public class OkHttp extends Client {
    private OkHttpClient client;
    private Call last_call = null;

    public OkHttp(Context context) {
        super(context);
        client = new OkHttpClient.Builder().cookieJar(new InterceptedCookieJar()).build();
        configure();
    }

    @Override
    public Client setCookie(String url, String name, String value) {
        HttpUrl httpUrl = HttpUrl.parse(url);
        List<Cookie> url_cookies = new ArrayList<>();
        url_cookies.add(Cookie.parse(httpUrl, name + "=" + value));
        client.cookieJar().saveFromResponse(httpUrl, url_cookies);
        return this;
    }

    public Map<String, String> getCookies(String url) {
        HttpUrl httpUrl = HttpUrl.parse(url);
        Map<String,String> result = new HashMap<>();
        List<Cookie> url_cookies = client.cookieJar().loadForRequest(httpUrl);
        if (url_cookies != null)
            for (Cookie cookie : url_cookies) {
                result.put(cookie.name(), cookie.value());
            }
        return result;
    }

    @Override
    public Client setTimeout(int ms) {
        client = client.newBuilder()
                .connectTimeout(ms, TimeUnit.MILLISECONDS)
                .readTimeout(ms, TimeUnit.MILLISECONDS)
                .writeTimeout(ms, TimeUnit.MILLISECONDS)
                .build();

        return this;
    }

    @Override
    public Client followRedirects(boolean follow) {
        client = client.newBuilder()
                .followRedirects(follow)
                .followSslRedirects(follow)
                .build();

        return this;
    }

    private Response call(Request.Builder builder) throws IOException {
        random.delay(running);

        // Populate headers
        for (String name : headers.keySet()) {
            builder.addHeader(name, getHeader(name));
        }

        if (context != null) {
            AndroidHacks.bindToWiFi(context);
        }

        last_call = client.newCall(builder.build());
        return last_call.execute();
    }

    @Override
    public ParsedResponse get(String link, Map<String, String> params) throws IOException {
        Response response = call(new Request.Builder().url(link + requestToString(params)).get());
        setHeader(HEADER_REFERER, link);
        return parse(link, response);
    }

    @Override
    public ParsedResponse post(String link, Map<String, String> params) throws IOException {
        FormBody.Builder body = new FormBody.Builder();

        if (params != null) {
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (entry.getValue() != null)
                    body.add(entry.getKey(), entry.getValue());
            }
        }

        Response response = call(new Request.Builder().url(link).post(body.build()));
        setHeader(HEADER_REFERER, link);
        return parse(link, response);
    }

    @Override
    public InputStream getInputStream(String link) throws IOException {
        Response response = call(new Request.Builder().url(link).get());
        ResponseBody body = response.body();

        if (body == null) {
            throw new IOException("Empty response: " + response.code());
        }

        return body.byteStream();
    }

    @Override
    public void stop() {
        if (last_call != null) {
            last_call.cancel();
        }
    }

    private ParsedResponse parse(String link, Response response) throws IOException {
        ResponseBody body = response.body();

        if (body == null) {
            throw new IOException("Response body is null! Code: " + response.code());
        }

        return new ParsedResponse(this, link, body.string(), response.code());
    }

    private class InterceptedCookieJar implements CookieJar {
        private HashMap<HttpUrl, List<Cookie>> cookies = new HashMap<>();

        private HttpUrl getHost (HttpUrl url) {
            return HttpUrl.parse("http://" + url.host());
        }

        @Override
        public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
            HttpUrl host = getHost(url);
            List<Cookie> url_cookies = loadForRequest(host);
            for (Cookie cookie : cookies) {
                List<Cookie> for_deletion = new ArrayList<>();
                for (Cookie old_cookie : url_cookies) {
                    if (cookie.name().equals(old_cookie.name()))
                        for_deletion.add(old_cookie);
                }
                for (Cookie old_cookie : for_deletion) {
                    url_cookies.remove(old_cookie);
                }
                url_cookies.add(cookie);
                Logger.log(Logger.LEVEL.DEBUG, String.format(
                        "CookieJar | Add: %s = %s",
                        cookie.name(), cookie.value()
                ));
            }
            this.cookies.put(host, url_cookies);
        }

        @Override
        public List<Cookie> loadForRequest(HttpUrl url) {
            HttpUrl host = getHost(url);
            List<Cookie> url_cookies = cookies.get(host);
            return (url_cookies != null) ? url_cookies : new ArrayList<Cookie>();
        }
    }
}
