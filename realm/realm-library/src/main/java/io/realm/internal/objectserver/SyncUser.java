package io.realm.internal.objectserver;/*
 * Copyright 2016 Realm Inc.
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

import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import io.realm.RealmAsyncTask;
import io.realm.Session;
import io.realm.SyncConfiguration;
import io.realm.SyncManager;
import io.realm.User;
import io.realm.internal.async.RealmAsyncTaskImpl;
import io.realm.internal.network.AuthenticationServer;
import io.realm.internal.network.ExponentialBackoffTask;
import io.realm.internal.network.RefreshResponse;

/**
 * Internal representation of a user on the Realm Object Server.
 * The public API is defined by {@link User}.
 */
public class SyncUser {

    // Time left on current refresh token, before we want to begin refreshing it.
    // Failing to refresh it before it expires, will result in the user no longer being valid, and not being able
    // to synchronize changes. It will still be possible to open Realms and read their data.
    private final long REFRESH_WINDOW_MS = TimeUnit.SECONDS.toMillis(5);

    private final String identity;
    private Token refreshToken;
    private URL authenticationUrl;
    private Map<URI, AccessDescription> realms = new HashMap<URI, AccessDescription>();
    private List<Session> sessions = new ArrayList<Session>();
    private RealmAsyncTask refreshTask;
    private boolean loggedIn;

    /**
     * Create a new Realm Object Server User
     */
    public SyncUser(Token refreshToken, URL authenticationUrl) {
        this.identity = refreshToken.identity();
        this.authenticationUrl = authenticationUrl;
        setRefreshToken(refreshToken);
        this.loggedIn = true;
    }

    public void setRefreshToken(final Token refreshToken) {
        this.refreshToken = refreshToken; // Replace any existing token. TODO re-save the user with latest token.

        // Schedule a refresh. This method cannot fail, but will continue retrying until either the app is killed
        // or the attempt was successful.
        final long expire = refreshToken.expiresMs();
        final AuthenticationServer server = SyncManager.getAuthServer();
        Future<?> task = SyncManager.NETWORK_POOL_EXECUTOR.submit(new ExponentialBackoffTask<RefreshResponse>() {
            @Override
            protected RefreshResponse execute() {
                long timeToExpiration = System.currentTimeMillis() - expire;
                if (timeToExpiration - REFRESH_WINDOW_MS > 0) {
                    SystemClock.sleep(timeToExpiration);
                }
                return server.refresh(refreshToken.value(), authenticationUrl);
            }

            @Override
            protected void onSuccess(RefreshResponse response) {
                setRefreshToken(response.getRefreshToken());
            }

            @Override
            protected void onError(RefreshResponse response) {

            }
        });
        refreshTask = new RealmAsyncTaskImpl(task, SyncManager.NETWORK_POOL_EXECUTOR);
    }

    /**
     * Checks if the user has access to the given Realm. Being authenticated means that the
     * user is know by the Realm Object Server and have been granted access to the given Realm.
     *
     * Authenticating will happen automatically as part of opening a Realm.
     */
    public boolean isAuthenticated(SyncConfiguration configuration) {
        Token token = getAccessToken(configuration.getServerUrl());
        return token != null && token.expiresMs() > System.currentTimeMillis();
    }

    public String toJson() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("authUrl", authenticationUrl);
            obj.put("userToken", refreshToken.toJson());
            JSONArray realmList = new JSONArray();
            for (Map.Entry<URI, AccessDescription> entry : realms.entrySet()) {
                JSONObject token = new JSONObject();
                token.put("uri", entry.getKey().toString());
                token.put("description", entry.getValue().toJson());
                realmList.put(token);
            }
            obj.put("realms", realmList);
            return obj.toString();
        } catch (JSONException e) {
            throw new RuntimeException("Could not convert User to JSON", e);
        }
    }

    public String getIdentity() {
        return identity;
    }

    public Token getAccessToken(URI serverUrl) {
        AccessDescription accessDescription = realms.get(serverUrl);
        return (accessDescription != null) ? accessDescription.accessToken : null;
    }

    public void addRealm(URI uri, AccessDescription description) {
        realms.put(uri, description);
    }

    // When a session is started, add it to the user so it can be tracked
    public void addSession(Session session) {
        sessions.add(session);
    }

    /**
     * Adds an access token to this user.
     * <p>
     * An access token is a token granting access to one remote Realm. Access Tokens are normally fetched transparently
     * when opening a Realm, but using this method it is possible to add tokens upfront if they have been fetched or
     * created manually.
     *
     * @param uri {@link java.net.URI} pointing to a remote Realm.
     * @param accessToken
     */
    public void addRealm(URI uri, String accessToken, String localPath, boolean deleteOnLogout) {
        if (uri == null || accessToken == null) {
            throw new IllegalArgumentException("Non-null 'uri' and 'accessToken' required.");
        }
        uri = SyncUtil.getFullServerUrl(uri, identity);

        // Optimistically create a long-lived token with all permissions. If this is incorrect the Object Server
        // will reject it anyway. If tokens are added manually it is up to the user to ensure they are also used
        // correctly.
        Token token = new Token(accessToken, null, uri.toString(), Long.MAX_VALUE, Token.Permission.values());
        addRealm(uri, new AccessDescription(token, localPath, deleteOnLogout));
    }

    public URL getAuthenticationUrl() {
        return authenticationUrl;
    }

    public Token getUserToken() {
        return refreshToken;
    }

    public List<Session> getSessions() {
        return sessions;
    }

    public void clearTokens() {
        realms.clear();
        refreshToken = null;
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
    }

    public boolean isLoggedIn() {
        return loggedIn;
    }

    // Local Logout means that the user is no longer able to create new sync configurations,
    // nor synchronize changes
    public void localLogout() {
        loggedIn = false;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        SyncUser syncUser = (SyncUser) o;

        if (!identity.equals(syncUser.identity)) return false;
        if (!refreshToken.equals(syncUser.refreshToken)) return false;
        if (!authenticationUrl.toString().equals(syncUser.authenticationUrl.toString())) return false;
        return realms.equals(syncUser.realms);

    }

    @Override
    public int hashCode() {
        int result = identity.hashCode();
        result = 31 * result + refreshToken.hashCode();
        result = 31 * result + authenticationUrl.toString().hashCode();
        result = 31 * result + realms.hashCode();
        return result;
    }

    public Collection<AccessDescription> getRealms() {
        return realms.values();
    }

    // Wrapper for all Realm data needed by a User that might get serialized.
    public static class AccessDescription {
        public Token accessToken;
        public String localPath;
        public boolean deleteOnLogout;

        public AccessDescription(Token accessToken, String localPath, boolean deleteOnLogout) {
            this.accessToken = accessToken;
            this.localPath = localPath;
            this.deleteOnLogout = deleteOnLogout;
        }

        public static AccessDescription fromJson(JSONObject json) {
            try {
                Token token = Token.from(json.getJSONObject("accessToken"));
                String localPath = json.getString("localPath");
                boolean deleteOnLogout = json.getBoolean("deleteOnLogout");
                return new AccessDescription(token, localPath, deleteOnLogout);
            } catch (JSONException e) {
                throw new RuntimeException(e);
            }
        }

        public JSONObject toJson() {
            try {
                JSONObject obj = new JSONObject();
                obj.put("accessToken", accessToken.toJson());
                obj.put("localPath", localPath);
                obj.put("deleteOnLogout", deleteOnLogout);
                return obj;
            } catch (JSONException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            AccessDescription that = (AccessDescription) o;

            if (deleteOnLogout != that.deleteOnLogout) return false;
            if (!accessToken.equals(that.accessToken)) return false;
            return localPath.equals(that.localPath);

        }

        @Override
        public int hashCode() {
            int result = accessToken.hashCode();
            result = 31 * result + localPath.hashCode();
            result = 31 * result + (deleteOnLogout ? 1 : 0);
            return result;
        }
    }
}
