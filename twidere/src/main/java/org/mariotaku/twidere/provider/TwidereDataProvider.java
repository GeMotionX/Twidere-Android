/*
 * 				Twidere - Twitter client for Android
 * 
 *  Copyright (C) 2012-2014 Mariotaku Lee <mariotaku.lee@gmail.com>
 * 
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 * 
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 * 
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.mariotaku.twidere.provider;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.support.annotation.NonNull;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationCompat.InboxStyle;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.StyleSpan;
import android.util.Log;

import com.squareup.otto.Bus;

import org.apache.commons.lang3.ArrayUtils;
import org.mariotaku.querybuilder.Columns.Column;
import org.mariotaku.querybuilder.Expression;
import org.mariotaku.querybuilder.RawItemArray;
import org.mariotaku.querybuilder.query.SQLSelectQuery;
import org.mariotaku.twidere.Constants;
import org.mariotaku.twidere.R;
import org.mariotaku.twidere.activity.support.HomeActivity;
import org.mariotaku.twidere.app.TwidereApplication;
import org.mariotaku.twidere.fragment.support.DirectMessagesFragment;
import org.mariotaku.twidere.fragment.support.HomeTimelineFragment;
import org.mariotaku.twidere.fragment.support.MentionsTimelineFragment;
import org.mariotaku.twidere.model.AccountPreferences;
import org.mariotaku.twidere.model.ParcelableStatus;
import org.mariotaku.twidere.model.StringLongPair;
import org.mariotaku.twidere.model.UnreadItem;
import org.mariotaku.twidere.provider.TwidereDataStore.Accounts;
import org.mariotaku.twidere.provider.TwidereDataStore.CachedRelationships;
import org.mariotaku.twidere.provider.TwidereDataStore.CachedUsers;
import org.mariotaku.twidere.provider.TwidereDataStore.DirectMessages;
import org.mariotaku.twidere.provider.TwidereDataStore.Drafts;
import org.mariotaku.twidere.provider.TwidereDataStore.Mentions;
import org.mariotaku.twidere.provider.TwidereDataStore.Preferences;
import org.mariotaku.twidere.provider.TwidereDataStore.SearchHistory;
import org.mariotaku.twidere.provider.TwidereDataStore.Statuses;
import org.mariotaku.twidere.provider.TwidereDataStore.UnreadCounts;
import org.mariotaku.twidere.util.AsyncTwitterWrapper;
import org.mariotaku.twidere.util.ImagePreloader;
import org.mariotaku.twidere.util.MediaPreviewUtils;
import org.mariotaku.twidere.util.ParseUtils;
import org.mariotaku.twidere.util.PermissionsManager;
import org.mariotaku.twidere.util.ReadStateManager;
import org.mariotaku.twidere.util.SQLiteDatabaseWrapper;
import org.mariotaku.twidere.util.SQLiteDatabaseWrapper.LazyLoadCallback;
import org.mariotaku.twidere.util.SharedPreferencesWrapper;
import org.mariotaku.twidere.util.TwidereArrayUtils;
import org.mariotaku.twidere.util.TwidereQueryBuilder.CachedUsersQueryBuilder;
import org.mariotaku.twidere.util.TwidereQueryBuilder.ConversationQueryBuilder;
import org.mariotaku.twidere.util.UserColorNameUtils;
import org.mariotaku.twidere.util.Utils;
import org.mariotaku.twidere.util.collection.CompactHashSet;
import org.mariotaku.twidere.util.message.UnreadCountUpdatedEvent;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import twitter4j.http.HostAddressResolver;

import static org.mariotaku.twidere.util.Utils.clearAccountColor;
import static org.mariotaku.twidere.util.Utils.clearAccountName;
import static org.mariotaku.twidere.util.Utils.getAccountIds;
import static org.mariotaku.twidere.util.Utils.getNotificationUri;
import static org.mariotaku.twidere.util.Utils.getTableId;
import static org.mariotaku.twidere.util.Utils.getTableNameById;
import static org.mariotaku.twidere.util.Utils.isNotificationsSilent;

public final class TwidereDataProvider extends ContentProvider implements Constants, OnSharedPreferenceChangeListener,
        LazyLoadCallback {

    public static final String TAG_OLDEST_MESSAGES = "oldest_messages";
    private ContentResolver mContentResolver;
    private SQLiteDatabaseWrapper mDatabaseWrapper;
    private PermissionsManager mPermissionsManager;
    private NotificationManager mNotificationManager;
    private ReadStateManager mReadStateManager;
    private SharedPreferencesWrapper mPreferences;
    private ImagePreloader mImagePreloader;
    private HostAddressResolver mHostAddressResolver;
    private Handler mHandler;

    private boolean mHomeActivityInBackground;

    private boolean mNameFirst;

    private final BroadcastReceiver mHomeActivityStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            final String action = intent.getAction();
            if (BROADCAST_HOME_ACTIVITY_ONSTART.equals(action)) {
                mHomeActivityInBackground = false;
            } else if (BROADCAST_HOME_ACTIVITY_ONSTOP.equals(action)) {
                mHomeActivityInBackground = true;
            }
        }

    };

    @Override
    public int bulkInsert(final Uri uri, @NonNull final ContentValues[] valuesArray) {
        try {
            final int tableId = getTableId(uri);
            final String table = getTableNameById(tableId);
            checkWritePermission(tableId, table);
            switch (tableId) {
                case TABLE_ID_DIRECT_MESSAGES_CONVERSATION:
                case TABLE_ID_DIRECT_MESSAGES:
                case TABLE_ID_DIRECT_MESSAGES_CONVERSATIONS_ENTRIES:
                    return 0;
            }
            int result = 0;
            final long[] newIds = new long[valuesArray.length];
            if (table != null) {
                mDatabaseWrapper.beginTransaction();
                if (tableId == TABLE_ID_CACHED_USERS) {
                    for (final ContentValues values : valuesArray) {
                        final Expression where = Expression.equals(CachedUsers.USER_ID,
                                values.getAsLong(CachedUsers.USER_ID));
                        mDatabaseWrapper.update(table, values, where.getSQL(), null);
                        newIds[result++] = mDatabaseWrapper.insertWithOnConflict(table, null,
                                values, SQLiteDatabase.CONFLICT_IGNORE);
                    }
                } else if (tableId == TABLE_ID_SEARCH_HISTORY) {
                    for (final ContentValues values : valuesArray) {
                        values.put(SearchHistory.RECENT_QUERY, System.currentTimeMillis());
                        final Expression where = Expression.equalsArgs(SearchHistory.QUERY);
                        final String[] args = {values.getAsString(SearchHistory.QUERY)};
                        mDatabaseWrapper.update(table, values, where.getSQL(), args);
                        newIds[result++] = mDatabaseWrapper.insertWithOnConflict(table, null,
                                values, SQLiteDatabase.CONFLICT_IGNORE);
                    }
                } else if (shouldReplaceOnConflict(tableId)) {
                    for (final ContentValues values : valuesArray) {
                        newIds[result++] = mDatabaseWrapper.insertWithOnConflict(table, null,
                                values, SQLiteDatabase.CONFLICT_REPLACE);
                    }
                } else {
                    for (final ContentValues values : valuesArray) {
                        newIds[result++] = mDatabaseWrapper.insert(table, null, values);
                    }
                }
                mDatabaseWrapper.setTransactionSuccessful();
                mDatabaseWrapper.endTransaction();
            }
            if (result > 0) {
                onDatabaseUpdated(tableId, uri);
            }
            onNewItemsInserted(uri, tableId, valuesArray, newIds);
            return result;
        } catch (final SQLException e) {
            throw new IllegalStateException(e);
        }
    }


    @Override
    public int delete(final Uri uri, final String selection, final String[] selectionArgs) {
        try {
            final int tableId = getTableId(uri);
            final String table = getTableNameById(tableId);
            checkWritePermission(tableId, table);
            switch (tableId) {
                case TABLE_ID_DIRECT_MESSAGES_CONVERSATION:
                case TABLE_ID_DIRECT_MESSAGES:
                case TABLE_ID_DIRECT_MESSAGES_CONVERSATIONS_ENTRIES:
                    return 0;
                case VIRTUAL_TABLE_ID_NOTIFICATIONS: {
                    final List<String> segments = uri.getPathSegments();
                    if (segments.size() == 1) {
                        clearNotification();
                    } else if (segments.size() == 2) {
                        final int notificationType = ParseUtils.parseInt(segments.get(1));
                        clearNotification(notificationType, 0);
                    } else if (segments.size() == 3) {
                        final int notificationType = ParseUtils.parseInt(segments.get(1));
                        final long accountId = ParseUtils.parseLong(segments.get(2));
                        clearNotification(notificationType, accountId);
                    }
                    return 1;
                }
                case VIRTUAL_TABLE_ID_UNREAD_COUNTS: {
                    return 0;
                }
            }
            if (table == null) return 0;
            final int result = mDatabaseWrapper.delete(table, selection, selectionArgs);
            if (result > 0) {
                onDatabaseUpdated(tableId, uri);
            }
            return result;
        } catch (final SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public String getType(final Uri uri) {
        return null;
    }

    @Override
    public Uri insert(final Uri uri, final ContentValues values) {
        try {
            final int tableId = getTableId(uri);
            final String table = getTableNameById(tableId);
            checkWritePermission(tableId, table);
            switch (tableId) {
                case TABLE_ID_DIRECT_MESSAGES_CONVERSATION:
                case TABLE_ID_DIRECT_MESSAGES:
                case TABLE_ID_DIRECT_MESSAGES_CONVERSATIONS_ENTRIES:
                    return null;
            }
            if (table == null) return null;
            final long rowId;
            if (tableId == TABLE_ID_CACHED_USERS) {
                final Expression where = Expression.equals(CachedUsers.USER_ID,
                        values.getAsLong(CachedUsers.USER_ID));
                mDatabaseWrapper.update(table, values, where.getSQL(), null);
                rowId = mDatabaseWrapper.insertWithOnConflict(table, null, values,
                        SQLiteDatabase.CONFLICT_IGNORE);
            } else if (tableId == TABLE_ID_SEARCH_HISTORY) {
                values.put(SearchHistory.RECENT_QUERY, System.currentTimeMillis());
                final Expression where = Expression.equalsArgs(SearchHistory.QUERY);
                final String[] args = {values.getAsString(SearchHistory.QUERY)};
                mDatabaseWrapper.update(table, values, where.getSQL(), args);
                rowId = mDatabaseWrapper.insertWithOnConflict(table, null, values,
                        SQLiteDatabase.CONFLICT_IGNORE);
            } else if (tableId == TABLE_ID_CACHED_RELATIONSHIPS) {
                final long accountId = values.getAsLong(CachedRelationships.ACCOUNT_ID);
                final long userId = values.getAsLong(CachedRelationships.USER_ID);
                final Expression where = Expression.and(
                        Expression.equals(CachedRelationships.ACCOUNT_ID, accountId),
                        Expression.equals(CachedRelationships.USER_ID, userId)
                );
                if (mDatabaseWrapper.update(table, values, where.getSQL(), null) > 0) {
                    final String[] projection = {CachedRelationships._ID};
                    final Cursor c = mDatabaseWrapper.query(table, projection, where.getSQL(), null,
                            null, null, null);
                    if (c.moveToFirst()) {
                        rowId = c.getLong(0);
                    } else {
                        rowId = 0;
                    }
                    c.close();
                } else {
                    rowId = mDatabaseWrapper.insertWithOnConflict(table, null, values,
                            SQLiteDatabase.CONFLICT_IGNORE);
                }
            } else if (shouldReplaceOnConflict(tableId)) {
                rowId = mDatabaseWrapper.insertWithOnConflict(table, null, values,
                        SQLiteDatabase.CONFLICT_REPLACE);
            } else {
                rowId = mDatabaseWrapper.insert(table, null, values);
            }
            onDatabaseUpdated(tableId, uri);
            onNewItemsInserted(uri, tableId, values, rowId);
            return Uri.withAppendedPath(uri, String.valueOf(rowId));
        } catch (final SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public boolean onCreate() {
        final Context context = getContext();
        final TwidereApplication app = TwidereApplication.getInstance(context);
        mHandler = new Handler(Looper.getMainLooper());
        mDatabaseWrapper = new SQLiteDatabaseWrapper(this);
        mHostAddressResolver = app.getHostAddressResolver();
        mPreferences = SharedPreferencesWrapper.getInstance(context, SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE);
        mPreferences.registerOnSharedPreferenceChangeListener(this);
        updatePreferences();
        mPermissionsManager = new PermissionsManager(context);
        mReadStateManager = app.getReadStateManager();
        mImagePreloader = new ImagePreloader(context, app.getImageLoader());
        final IntentFilter filter = new IntentFilter();
        filter.addAction(BROADCAST_HOME_ACTIVITY_ONSTART);
        filter.addAction(BROADCAST_HOME_ACTIVITY_ONSTOP);
        context.registerReceiver(mHomeActivityStateReceiver, filter);
        // final GetWritableDatabaseTask task = new
        // GetWritableDatabaseTask(context, helper, mDatabaseWrapper);
        // task.executeTask();
        return true;
    }

    @Override
    public SQLiteDatabase onCreateSQLiteDatabase() {
        final TwidereApplication app = TwidereApplication.getInstance(getContext());
        final SQLiteOpenHelper helper = app.getSQLiteOpenHelper();
        return helper.getWritableDatabase();
    }

    @Override
    public void onSharedPreferenceChanged(final SharedPreferences sharedPreferences, final String key) {
        updatePreferences();
    }

    @Override
    public ParcelFileDescriptor openFile(final Uri uri, final String mode) throws FileNotFoundException {
        if (uri == null || mode == null) throw new IllegalArgumentException();
        final int table_id = getTableId(uri);
        final String table = getTableNameById(table_id);
        final int mode_code;
        if ("r".equals(mode)) {
            mode_code = ParcelFileDescriptor.MODE_READ_ONLY;
        } else if ("rw".equals(mode)) {
            mode_code = ParcelFileDescriptor.MODE_READ_WRITE;
        } else if ("rwt".equals(mode)) {
            mode_code = ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_TRUNCATE;
        } else
            throw new IllegalArgumentException();
        if (mode_code == ParcelFileDescriptor.MODE_READ_ONLY) {
            checkReadPermission(table_id, table, null);
        } else if ((mode_code & ParcelFileDescriptor.MODE_READ_WRITE) != 0) {
            checkReadPermission(table_id, table, null);
            checkWritePermission(table_id, table);
        }
        switch (table_id) {
            case VIRTUAL_TABLE_ID_CACHED_IMAGES: {
                return getCachedImageFd(uri.getQueryParameter(QUERY_PARAM_URL));
            }
            case VIRTUAL_TABLE_ID_CACHE_FILES: {
                return getCacheFileFd(uri.getLastPathSegment());
            }
        }
        return null;
    }

    @Override
    public Cursor query(final Uri uri, final String[] projection, final String selection, final String[] selectionArgs,
                        final String sortOrder) {
        try {
            final int tableId = getTableId(uri);
            final String table = getTableNameById(tableId);
            checkReadPermission(tableId, table, projection);
            switch (tableId) {
                case VIRTUAL_TABLE_ID_DATABASE_READY: {
                    if (mDatabaseWrapper.isReady())
                        return new MatrixCursor(projection != null ? projection : new String[0]);
                    return null;
                }
                case VIRTUAL_TABLE_ID_PERMISSIONS: {
                    final MatrixCursor c = new MatrixCursor(TwidereDataStore.Permissions.MATRIX_COLUMNS);
                    final Map<String, String> map = mPermissionsManager.getAll();
                    for (final Map.Entry<String, String> item : map.entrySet()) {
                        c.addRow(new Object[]{item.getKey(), item.getValue()});
                    }
                    return c;
                }
                case VIRTUAL_TABLE_ID_ALL_PREFERENCES: {
                    return getPreferencesCursor(mPreferences, null);
                }
                case VIRTUAL_TABLE_ID_PREFERENCES: {
                    return getPreferencesCursor(mPreferences, uri.getLastPathSegment());
                }
                case VIRTUAL_TABLE_ID_DNS: {
                    return getDNSCursor(uri.getLastPathSegment());
                }
                case VIRTUAL_TABLE_ID_CACHED_IMAGES: {
                    return getCachedImageCursor(uri.getQueryParameter(QUERY_PARAM_URL));
                }
                case VIRTUAL_TABLE_ID_NOTIFICATIONS: {
                    final List<String> segments = uri.getPathSegments();
                    if (segments.size() == 2)
                        return getNotificationsCursor(ParseUtils.parseInt(segments.get(1), -1));
                    else
                        return getNotificationsCursor();
                }
                case VIRTUAL_TABLE_ID_UNREAD_COUNTS: {
                    final List<String> segments = uri.getPathSegments();
                    if (segments.size() == 2)
                        return getUnreadCountsCursor(ParseUtils.parseInt(segments.get(1), -1));
                    else
                        return getUnreadCountsCursor();
                }
                case VIRTUAL_TABLE_ID_UNREAD_COUNTS_BY_TYPE: {
                    final List<String> segments = uri.getPathSegments();
                    if (segments.size() != 3) return null;
                    return getUnreadCountsCursorByType(segments.get(2));
                }
                case TABLE_ID_DIRECT_MESSAGES_CONVERSATION: {
                    final List<String> segments = uri.getPathSegments();
                    if (segments.size() != 4) return null;
                    final long accountId = ParseUtils.parseLong(segments.get(2));
                    final long conversationId = ParseUtils.parseLong(segments.get(3));
                    final SQLSelectQuery query = ConversationQueryBuilder.buildByConversationId(projection,
                            accountId, conversationId, selection, sortOrder);
                    final Cursor c = mDatabaseWrapper.rawQuery(query.getSQL(), selectionArgs);
                    setNotificationUri(c, DirectMessages.CONTENT_URI);
                    return c;
                }
                case TABLE_ID_DIRECT_MESSAGES_CONVERSATION_SCREEN_NAME: {
                    final List<String> segments = uri.getPathSegments();
                    if (segments.size() != 4) return null;
                    final long accountId = ParseUtils.parseLong(segments.get(2));
                    final String screenName = segments.get(3);
                    final SQLSelectQuery query = ConversationQueryBuilder.buildByScreenName(projection,
                            accountId, screenName, selection, sortOrder);
                    final Cursor c = mDatabaseWrapper.rawQuery(query.getSQL(), selectionArgs);
                    setNotificationUri(c, DirectMessages.CONTENT_URI);
                    return c;
                }
                case VIRTUAL_TABLE_ID_CACHED_USERS_WITH_RELATIONSHIP: {
                    final long accountId = ParseUtils.parseLong(uri.getLastPathSegment(), -1);
                    final SQLSelectQuery query = CachedUsersQueryBuilder.buildWithRelationship(projection,
                            selection, sortOrder, accountId);
                    final Cursor c = mDatabaseWrapper.rawQuery(query.getSQL(), selectionArgs);
                    setNotificationUri(c, CachedUsers.CONTENT_URI);
                    return c;
                }
                case VIRTUAL_TABLE_ID_CACHED_USERS_WITH_SCORE: {
                    final long accountId = ParseUtils.parseLong(uri.getLastPathSegment(), -1);
                    final SQLSelectQuery query = CachedUsersQueryBuilder.buildWithScore(projection,
                            selection, sortOrder, accountId);
                    final Cursor c = mDatabaseWrapper.rawQuery(query.getSQL(), selectionArgs);
                    setNotificationUri(c, CachedUsers.CONTENT_URI);
                    return c;
                }
                case VIRTUAL_TABLE_ID_DRAFTS_UNSENT: {
                    final TwidereApplication app = TwidereApplication.getInstance(getContext());
                    final AsyncTwitterWrapper twitter = app.getTwitterWrapper();
                    final RawItemArray sendingIds = new RawItemArray(twitter.getSendingDraftIds());
                    final Expression where;
                    if (selection != null) {
                        where = Expression.and(new Expression(selection),
                                Expression.notIn(new Column(Drafts._ID), sendingIds));
                    } else {
                        where = Expression.and(Expression.notIn(new Column(Drafts._ID), sendingIds));
                    }
                    final Cursor c = mDatabaseWrapper.query(Drafts.TABLE_NAME, projection,
                            where.getSQL(), selectionArgs, null, null, sortOrder);
                    setNotificationUri(c, getNotificationUri(tableId, uri));
                    return c;
                }
            }
            if (table == null) return null;
            final Cursor c = mDatabaseWrapper.query(table, projection, selection, selectionArgs, null, null, sortOrder);
            setNotificationUri(c, getNotificationUri(tableId, uri));
            return c;
        } catch (final SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public int update(final Uri uri, final ContentValues values, final String selection, final String[] selectionArgs) {
        try {
            final int tableId = getTableId(uri);
            final String table = getTableNameById(tableId);
            checkWritePermission(tableId, table);
            int result = 0;
            if (table != null) {
                switch (tableId) {
                    case TABLE_ID_DIRECT_MESSAGES_CONVERSATION:
                    case TABLE_ID_DIRECT_MESSAGES:
                    case TABLE_ID_DIRECT_MESSAGES_CONVERSATIONS_ENTRIES:
                        return 0;
                }
                result = mDatabaseWrapper.update(table, values, selection, selectionArgs);
            }
            if (result > 0) {
                onDatabaseUpdated(tableId, uri);
            }
            return result;
        } catch (final SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private void buildNotification(final NotificationCompat.Builder builder, final AccountPreferences accountPrefs,
                                   final int notificationType, final String ticker, final String title, final String message, final long when,
                                   final int icon, final Bitmap largeIcon, final Intent contentIntent, final Intent deleteIntent) {
        final Context context = getContext();
        builder.setTicker(ticker);
        builder.setContentTitle(title);
        builder.setContentText(message);
        builder.setAutoCancel(true);
        builder.setWhen(System.currentTimeMillis());
        builder.setSmallIcon(icon);
        if (largeIcon != null) {
            builder.setLargeIcon(largeIcon);
        }
        if (deleteIntent != null) {
            builder.setDeleteIntent(PendingIntent.getBroadcast(context, 0, deleteIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT));
        }
        if (contentIntent != null) {
            builder.setContentIntent(PendingIntent.getActivity(context, 0, contentIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT));
        }
        int defaults = 0;
        if (isNotificationAudible()) {
            if (AccountPreferences.isNotificationHasRingtone(notificationType)) {
                final Uri ringtone = accountPrefs.getNotificationRingtone();
                builder.setSound(ringtone, Notification.STREAM_DEFAULT);
            }
            if (AccountPreferences.isNotificationHasVibration(notificationType)) {
                defaults |= Notification.DEFAULT_VIBRATE;
            } else {
                defaults &= ~Notification.DEFAULT_VIBRATE;
            }
        }
        if (AccountPreferences.isNotificationHasLight(notificationType)) {
            final int color = accountPrefs.getNotificationLightColor();
            builder.setLights(color, 1000, 2000);
        }
        builder.setDefaults(defaults);
    }

    private boolean checkPermission(final String... permissions) {
        return mPermissionsManager.checkCallingPermission(permissions);
    }

    private void checkReadPermission(final int id, final String table, final String[] projection) {
        switch (id) {
            case VIRTUAL_TABLE_ID_PREFERENCES:
            case VIRTUAL_TABLE_ID_DNS: {
                if (!checkPermission(PERMISSION_PREFERENCES))
                    throw new SecurityException("Access preferences requires level PERMISSION_LEVEL_PREFERENCES");
                break;
            }
            case TABLE_ID_ACCOUNTS: {
                // Reading some infomation like user_id, screen_name etc is
                // okay, but reading columns like password requires higher
                // permission level.
                final String[] credentialsCols = {Accounts.BASIC_AUTH_PASSWORD, Accounts.OAUTH_TOKEN,
                        Accounts.OAUTH_TOKEN_SECRET, Accounts.CONSUMER_KEY, Accounts.CONSUMER_SECRET};
                if (projection == null || TwidereArrayUtils.contains(projection, credentialsCols)
                        && !checkPermission(PERMISSION_ACCOUNTS))
                    throw new SecurityException("Access column " + TwidereArrayUtils.toString(projection, ',', true)
                            + " in database accounts requires level PERMISSION_LEVEL_ACCOUNTS");
                if (!checkPermission(PERMISSION_READ))
                    throw new SecurityException("Access database " + table + " requires level PERMISSION_LEVEL_READ");
                break;
            }
            case TABLE_ID_DIRECT_MESSAGES:
            case TABLE_ID_DIRECT_MESSAGES_INBOX:
            case TABLE_ID_DIRECT_MESSAGES_OUTBOX:
            case TABLE_ID_DIRECT_MESSAGES_CONVERSATION:
            case TABLE_ID_DIRECT_MESSAGES_CONVERSATION_SCREEN_NAME:
            case TABLE_ID_DIRECT_MESSAGES_CONVERSATIONS_ENTRIES: {
                if (!checkPermission(PERMISSION_DIRECT_MESSAGES))
                    throw new SecurityException("Access database " + table
                            + " requires level PERMISSION_LEVEL_DIRECT_MESSAGES");
                break;
            }
            case TABLE_ID_STATUSES:
            case TABLE_ID_MENTIONS:
            case TABLE_ID_TABS:
            case TABLE_ID_DRAFTS:
            case TABLE_ID_CACHED_USERS:
            case TABLE_ID_FILTERED_USERS:
            case TABLE_ID_FILTERED_KEYWORDS:
            case TABLE_ID_FILTERED_SOURCES:
            case TABLE_ID_FILTERED_LINKS:
            case TABLE_ID_TRENDS_LOCAL:
            case TABLE_ID_CACHED_STATUSES:
            case TABLE_ID_CACHED_HASHTAGS: {
                if (!checkPermission(PERMISSION_READ))
                    throw new SecurityException("Access database " + table + " requires level PERMISSION_LEVEL_READ");
                break;
            }
        }
    }

    private void checkWritePermission(final int id, final String table) {
        switch (id) {
            case TABLE_ID_ACCOUNTS: {
                // Writing to accounts database is not allowed for third-party
                // applications.
                if (!mPermissionsManager.checkSignature(Binder.getCallingUid()))
                    throw new SecurityException(
                            "Writing to accounts database is not allowed for third-party applications");
                break;
            }
            case TABLE_ID_DIRECT_MESSAGES:
            case TABLE_ID_DIRECT_MESSAGES_INBOX:
            case TABLE_ID_DIRECT_MESSAGES_OUTBOX:
            case TABLE_ID_DIRECT_MESSAGES_CONVERSATION:
            case TABLE_ID_DIRECT_MESSAGES_CONVERSATION_SCREEN_NAME:
            case TABLE_ID_DIRECT_MESSAGES_CONVERSATIONS_ENTRIES: {
                if (!checkPermission(PERMISSION_DIRECT_MESSAGES))
                    throw new SecurityException("Access database " + table
                            + " requires level PERMISSION_LEVEL_DIRECT_MESSAGES");
                break;
            }
            case TABLE_ID_STATUSES:
            case TABLE_ID_MENTIONS:
            case TABLE_ID_TABS:
            case TABLE_ID_DRAFTS:
            case TABLE_ID_CACHED_USERS:
            case TABLE_ID_FILTERED_USERS:
            case TABLE_ID_FILTERED_KEYWORDS:
            case TABLE_ID_FILTERED_SOURCES:
            case TABLE_ID_FILTERED_LINKS:
            case TABLE_ID_TRENDS_LOCAL:
            case TABLE_ID_CACHED_STATUSES:
            case TABLE_ID_CACHED_HASHTAGS: {
                if (!checkPermission(PERMISSION_WRITE))
                    throw new SecurityException("Access database " + table + " requires level PERMISSION_LEVEL_WRITE");
                break;
            }
        }
    }

    private void clearNotification() {
        getNotificationManager().cancelAll();
    }

    private void clearNotification(final int notificationType, final long accountId) {

    }

    private Cursor getCachedImageCursor(final String url) {
        if (Utils.isDebugBuild()) {
            Log.d(LOGTAG, String.format("getCachedImageCursor(%s)", url));
        }
        final MatrixCursor c = new MatrixCursor(TwidereDataStore.CachedImages.MATRIX_COLUMNS);
        final File file = mImagePreloader.getCachedImageFile(url);
        if (url != null && file != null) {
            c.addRow(new String[]{url, file.getPath()});
        }
        return c;
    }

    private ParcelFileDescriptor getCachedImageFd(final String url) throws FileNotFoundException {
        if (Utils.isDebugBuild()) {
            Log.d(LOGTAG, String.format("getCachedImageFd(%s)", url));
        }
        final File file = mImagePreloader.getCachedImageFile(url);
        if (file == null) return null;
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    private ParcelFileDescriptor getCacheFileFd(final String name) throws FileNotFoundException {
        if (name == null) return null;
        final Context mContext = getContext();
        final File cacheDir = mContext.getCacheDir();
        final File file = new File(cacheDir, name);
        if (!file.exists()) return null;
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    private ContentResolver getContentResolver() {
        if (mContentResolver != null) return mContentResolver;
        final Context context = getContext();
        return mContentResolver = context.getContentResolver();
    }

    private Cursor getDNSCursor(final String host) {
        final MatrixCursor c = new MatrixCursor(TwidereDataStore.DNS.MATRIX_COLUMNS);
        try {
            final InetAddress[] addresses = mHostAddressResolver.resolve(host);
            for (InetAddress address : addresses) {
                c.addRow(new String[]{host, address.getHostAddress()});
            }
        } catch (final IOException ignore) {
            if (Utils.isDebugBuild()) {
                Log.w(LOGTAG, ignore);
            }
        }
        return c;
    }

    private NotificationManager getNotificationManager() {
        if (mNotificationManager != null) return mNotificationManager;
        final Context context = getContext();
        return mNotificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private Cursor getNotificationsCursor() {
        final MatrixCursor c = new MatrixCursor(TwidereDataStore.Notifications.MATRIX_COLUMNS);
        return c;
    }

    private Cursor getNotificationsCursor(final int id) {
        final MatrixCursor c = new MatrixCursor(TwidereDataStore.Notifications.MATRIX_COLUMNS);
        return c;
    }

    private Bitmap getProfileImageForNotification(final String profile_image_url) {
        final Context context = getContext();
        final Resources res = context.getResources();
        final int w = res.getDimensionPixelSize(android.R.dimen.notification_large_icon_width);
        final int h = res.getDimensionPixelSize(android.R.dimen.notification_large_icon_height);
        final File profile_image_file = mImagePreloader.getCachedImageFile(profile_image_url);
        final Bitmap profile_image = profile_image_file != null && profile_image_file.isFile() ? BitmapFactory
                .decodeFile(profile_image_file.getPath()) : null;
        if (profile_image != null) return Bitmap.createScaledBitmap(profile_image, w, h, true);
        return Bitmap.createScaledBitmap(BitmapFactory.decodeResource(res, R.mipmap.ic_launcher), w, h, true);
    }

    private Cursor getUnreadCountsCursor() {
        final MatrixCursor c = new MatrixCursor(TwidereDataStore.UnreadCounts.MATRIX_COLUMNS);
        return c;
    }

    private Cursor getUnreadCountsCursor(final int position) {
        final MatrixCursor c = new MatrixCursor(TwidereDataStore.UnreadCounts.MATRIX_COLUMNS);

        return c;
    }

    private Cursor getUnreadCountsCursorByType(final String type) {
        final MatrixCursor c = new MatrixCursor(TwidereDataStore.UnreadCounts.MATRIX_COLUMNS);
        return c;
    }

    private int getUsersCount(final List<ParcelableStatus> items) {
        if (items == null || items.isEmpty()) return 0;
        final Set<Long> ids = new HashSet<>();
        for (final ParcelableStatus item : items.toArray(new ParcelableStatus[items.size()])) {
            ids.add(item.user_id);
        }
        return ids.size();
    }

    private boolean isNotificationAudible() {
        return mHomeActivityInBackground && !isNotificationsSilent(getContext());
    }

    private void notifyContentObserver(final Uri uri) {
        final ContentResolver cr = getContentResolver();
        if (uri == null || cr == null) return;
        cr.notifyChange(uri, null);
    }


    private void notifyUnreadCountChanged(final int position) {
        final Context context = getContext();
        final Bus bus = TwidereApplication.getInstance(context).getMessageBus();
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                bus.post(new UnreadCountUpdatedEvent(position));
            }
        });
        notifyContentObserver(UnreadCounts.CONTENT_URI);
    }

    private void onDatabaseUpdated(final int tableId, final Uri uri) {
        if (uri == null) return;
        switch (tableId) {
            case TABLE_ID_ACCOUNTS: {
                clearAccountColor();
                clearAccountName();
                break;
            }
        }
        notifyContentObserver(getNotificationUri(tableId, uri));
    }


    private void onNewItemsInserted(final Uri uri, final int tableId, final ContentValues values, final long newId) {
        onNewItemsInserted(uri, tableId, new ContentValues[]{values}, new long[]{newId});

    }

    private void onNewItemsInserted(final Uri uri, final int tableId, final ContentValues[] valuesArray, final long[] newIds) {
        if (uri == null || valuesArray == null || valuesArray.length == 0) return;
        preloadImages(valuesArray);
        if (!uri.getBooleanQueryParameter(QUERY_PARAM_NOTIFY, true)) return;
        switch (tableId) {
            case TABLE_ID_STATUSES: {
                final AccountPreferences[] prefs = AccountPreferences.getNotificationEnabledPreferences(getContext(),
                        getAccountIds(getContext()));
                for (final AccountPreferences pref : prefs) {
                    if (!pref.isHomeTimelineNotificationEnabled()) continue;
                    showTimelineNotification(pref, mReadStateManager.getPosition(HomeTimelineFragment.KEY_READ_POSITION_TAG));
                }
                notifyUnreadCountChanged(NOTIFICATION_ID_HOME_TIMELINE);
                break;
            }
            case TABLE_ID_MENTIONS: {
                final AccountPreferences[] prefs = AccountPreferences.getNotificationEnabledPreferences(getContext(),
                        getAccountIds(getContext()));
                for (final AccountPreferences pref : prefs) {
                    if (!pref.isMentionsNotificationEnabled()) continue;
                    showMentionsNotification(pref, mReadStateManager.getPosition(MentionsTimelineFragment.KEY_READ_POSITION_TAG));
                }
                notifyUnreadCountChanged(NOTIFICATION_ID_MENTIONS_TIMELINE);
                break;
            }
            case TABLE_ID_DIRECT_MESSAGES_INBOX: {
                final AccountPreferences[] prefs = AccountPreferences.getNotificationEnabledPreferences(getContext(),
                        getAccountIds(getContext()));
                for (final AccountPreferences pref : prefs) {
                    if (!pref.isDirectMessagesNotificationEnabled()) continue;
                    final StringLongPair[] pairs = mReadStateManager.getPositionPairs(DirectMessagesFragment.KEY_READ_POSITION_TAG);
                    showMessagesNotification(pref, pairs, valuesArray);
                }
                notifyUnreadCountChanged(NOTIFICATION_ID_DIRECT_MESSAGES);
                break;
            }
            case TABLE_ID_DRAFTS: {
                break;
            }
        }
    }

    private void showTimelineNotification(AccountPreferences pref, long position) {
        final long accountId = pref.getAccountId();
        final Context context = getContext();
        final Resources resources = context.getResources();
        final NotificationManager nm = getNotificationManager();
        final Expression selection = Expression.and(Expression.equals(Statuses.ACCOUNT_ID, accountId),
                Expression.greaterThan(Statuses.STATUS_ID, position));
        final String filteredSelection = Utils.buildStatusFilterWhereClause(Statuses.TABLE_NAME,
                selection, true).getSQL();
        final String[] userProjection = {Statuses.USER_ID, Statuses.USER_NAME, Statuses.USER_SCREEN_NAME};
        final String[] statusProjection = new String[0];
        final Cursor statusCursor = mDatabaseWrapper.query(Statuses.TABLE_NAME, statusProjection,
                filteredSelection, null, null, null, Statuses.SORT_ORDER_TIMESTAMP_DESC);
        final Cursor userCursor = mDatabaseWrapper.query(Statuses.TABLE_NAME, userProjection,
                filteredSelection, null, Statuses.USER_ID, null, Statuses.SORT_ORDER_TIMESTAMP_DESC);
        try {
            final int usersCount = userCursor.getCount();
            final int statusesCount = statusCursor.getCount();
            if (statusesCount == 0 || usersCount == 0) return;
            final int idxUserName = userCursor.getColumnIndex(Statuses.USER_NAME),
                    idxUserScreenName = userCursor.getColumnIndex(Statuses.USER_NAME),
                    idxUserId = userCursor.getColumnIndex(Statuses.USER_NAME);
            final String notificationTitle = resources.getQuantityString(R.plurals.N_new_statuses,
                    statusesCount, statusesCount);
            final String notificationContent;
            userCursor.moveToFirst();
            final String displayName = UserColorNameUtils.getUserNickname(context, userCursor.getLong(idxUserId),
                    mNameFirst ? userCursor.getString(idxUserName) : userCursor.getString(idxUserScreenName));
            if (usersCount == 1) {
                notificationContent = context.getString(R.string.from_name, displayName);
            } else if (usersCount == 2) {
                userCursor.moveToPosition(1);
                final String othersName = UserColorNameUtils.getUserNickname(context, userCursor.getLong(idxUserId),
                        mNameFirst ? userCursor.getString(idxUserName) : userCursor.getString(idxUserScreenName));
                notificationContent = resources.getQuantityString(R.plurals.from_name_and_N_others,
                        usersCount - 1, othersName, usersCount - 1);
            } else {
                userCursor.moveToPosition(1);
                final String othersName = UserColorNameUtils.getUserNickname(context, userCursor.getLong(idxUserId),
                        mNameFirst ? userCursor.getString(idxUserName) : userCursor.getString(idxUserScreenName));
                notificationContent = resources.getString(R.string.from_name_and_N_others, othersName, usersCount - 1);
            }

            // Setup on click intent
            final Intent homeIntent = new Intent(context, HomeActivity.class);
            final PendingIntent clickIntent = PendingIntent.getActivity(context, 0, homeIntent, 0);

            // Setup notification
            final NotificationCompat.Builder builder = new NotificationCompat.Builder(context);
            builder.setSmallIcon(R.drawable.ic_stat_twitter);
            builder.setTicker(notificationTitle);
            builder.setContentTitle(notificationTitle);
            builder.setContentText(notificationContent);
            builder.setCategory(NotificationCompat.CATEGORY_SOCIAL);
            builder.setContentIntent(clickIntent);
            builder.setNumber(statusesCount);
            builder.setColor(pref.getNotificationLightColor());
            nm.notify("home_" + accountId, NOTIFICATION_ID_HOME_TIMELINE, builder.build());
        } finally {
            statusCursor.close();
            userCursor.close();
        }
    }

    private void showMentionsNotification(AccountPreferences pref, long position) {
        final long accountId = pref.getAccountId();
        final Context context = getContext();
        final Resources resources = context.getResources();
        final NotificationManager nm = getNotificationManager();
        final Expression selection = Expression.and(Expression.equals(Statuses.ACCOUNT_ID, accountId),
                Expression.greaterThan(Statuses.STATUS_ID, position));
        final String filteredSelection = Utils.buildStatusFilterWhereClause(Mentions.TABLE_NAME,
                selection, true).getSQL();
        final String[] userProjection = {Statuses.USER_ID, Statuses.USER_NAME, Statuses.USER_SCREEN_NAME};
        final String[] statusProjection = {Statuses.USER_ID, Statuses.USER_NAME, Statuses.USER_SCREEN_NAME,
                Statuses.TEXT_UNESCAPED, Statuses.STATUS_TIMESTAMP};
        final Cursor statusCursor = mDatabaseWrapper.query(Mentions.TABLE_NAME, statusProjection,
                filteredSelection, null, null, null, Statuses.SORT_ORDER_TIMESTAMP_DESC);
        final Cursor userCursor = mDatabaseWrapper.query(Mentions.TABLE_NAME, userProjection,
                filteredSelection, null, Statuses.USER_ID, null, Statuses.SORT_ORDER_TIMESTAMP_DESC);
        try {
            final int usersCount = userCursor.getCount();
            final int statusesCount = statusCursor.getCount();
            if (statusesCount == 0 || usersCount == 0) return;
            final String accountName = Utils.getAccountName(context, accountId);
            final String accountScreenName = Utils.getAccountScreenName(context, accountId);
            final int idxStatusText = statusCursor.getColumnIndex(Statuses.TEXT_UNESCAPED),
                    idxStatusTimestamp = statusCursor.getColumnIndex(Statuses.STATUS_TIMESTAMP),
                    idxStatusUserName = statusCursor.getColumnIndex(Statuses.USER_NAME),
                    idxStatusUserScreenName = statusCursor.getColumnIndex(Statuses.USER_SCREEN_NAME),
                    idxUserName = userCursor.getColumnIndex(Statuses.USER_NAME),
                    idxUserScreenName = userCursor.getColumnIndex(Statuses.USER_NAME),
                    idxUserId = userCursor.getColumnIndex(Statuses.USER_NAME);

            final CharSequence notificationTitle = resources.getQuantityString(R.plurals.N_new_mentions,
                    statusesCount, statusesCount);
            final String notificationContent;
            userCursor.moveToFirst();
            final String displayName = UserColorNameUtils.getUserNickname(context, userCursor.getLong(idxUserId),
                    mNameFirst ? userCursor.getString(idxUserName) : userCursor.getString(idxUserScreenName));
            if (usersCount == 1) {
                notificationContent = context.getString(R.string.notification_mention, displayName);
            } else {
                notificationContent = context.getString(R.string.notification_mention_multiple,
                        displayName, usersCount - 1);
            }

            // Add rich notification and get latest tweet timestamp
            long when = -1;
            final InboxStyle style = new InboxStyle();
            for (int i = 0, j = Math.min(statusesCount, 5); statusCursor.moveToPosition(i) && i < j; i++) {
                if (when < 0) {
                    when = statusCursor.getLong(idxStatusTimestamp);
                }
                final SpannableStringBuilder sb = new SpannableStringBuilder();
                sb.append(UserColorNameUtils.getUserNickname(context, statusCursor.getLong(idxUserId),
                        mNameFirst ? statusCursor.getString(idxStatusUserName) : statusCursor.getString(idxStatusUserScreenName)));
                sb.setSpan(new StyleSpan(Typeface.BOLD), 0, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.append(' ');
                sb.append(statusCursor.getString(idxStatusText));
                style.addLine(sb);
            }
            if (mNameFirst) {
                style.setSummaryText(accountName);
            } else {
                style.setSummaryText("@" + accountScreenName);
            }

            // Setup on click intent
            final Intent homeIntent = new Intent(context, HomeActivity.class);
            final PendingIntent clickIntent = PendingIntent.getActivity(context, 0, homeIntent, 0);

            // Setup notification
            final NotificationCompat.Builder builder = new NotificationCompat.Builder(context);
            builder.setSmallIcon(R.drawable.ic_stat_mention);
            builder.setTicker(notificationTitle);
            builder.setContentTitle(notificationTitle);
            builder.setContentText(notificationContent);
            builder.setCategory(NotificationCompat.CATEGORY_SOCIAL);
            builder.setContentIntent(clickIntent);
            builder.setNumber(statusesCount);
            builder.setWhen(when);
            builder.setStyle(style);
            builder.setColor(pref.getNotificationLightColor());
            nm.notify("mentions_" + accountId, NOTIFICATION_ID_MENTIONS_TIMELINE,
                    builder.build());
        } finally {
            statusCursor.close();
            userCursor.close();
        }
    }

    private void showMessagesNotification(AccountPreferences pref, StringLongPair[] pairs, ContentValues[] valuesArray) {
        final long accountId = pref.getAccountId();
        final long prevOldestId = mReadStateManager.getPosition(TAG_OLDEST_MESSAGES, String.valueOf(accountId));
        long oldestId = -1;
        for (final ContentValues contentValues : valuesArray) {
            final long messageId = contentValues.getAsLong(DirectMessages.MESSAGE_ID);
            oldestId = oldestId < 0 ? messageId : Math.min(oldestId, messageId);
            if (messageId <= prevOldestId) return;
        }
        mReadStateManager.setPosition(TAG_OLDEST_MESSAGES, String.valueOf(accountId), oldestId, false);
        final Context context = getContext();
        final Resources resources = context.getResources();
        final NotificationManager nm = getNotificationManager();
        final ArrayList<Expression> orExpressions = new ArrayList<>();
        final String prefix = accountId + "-";
        final int prefixLength = prefix.length();
        final Set<Long> senderIds = new CompactHashSet<>();
        for (StringLongPair pair : pairs) {
            final String key = pair.getKey();
            if (key.startsWith(prefix)) {
                final long senderId = Long.parseLong(key.substring(prefixLength));
                senderIds.add(senderId);
                final Expression expression = Expression.and(
                        Expression.equals(DirectMessages.SENDER_ID, senderId),
                        Expression.greaterThan(DirectMessages.MESSAGE_ID, pair.getValue())
                );
                orExpressions.add(expression);
            }
        }
        orExpressions.add(Expression.notIn(new Column(DirectMessages.SENDER_ID), new RawItemArray(senderIds.toArray())));
        final Expression selection = Expression.and(
                Expression.equals(DirectMessages.ACCOUNT_ID, accountId),
                Expression.greaterThan(DirectMessages.MESSAGE_ID, prevOldestId),
                Expression.or(orExpressions.toArray(new Expression[orExpressions.size()]))
        );
        final String filteredSelection = selection.getSQL();
        final String[] userProjection = {DirectMessages.SENDER_ID, DirectMessages.SENDER_NAME,
                DirectMessages.SENDER_SCREEN_NAME};
        final String[] messageProjection = {DirectMessages.SENDER_ID, DirectMessages.SENDER_NAME,
                DirectMessages.SENDER_SCREEN_NAME, DirectMessages.TEXT_UNESCAPED,
                DirectMessages.MESSAGE_TIMESTAMP};
        final Cursor messageCursor = mDatabaseWrapper.query(DirectMessages.Inbox.TABLE_NAME, messageProjection,
                filteredSelection, null, null, null, DirectMessages.DEFAULT_SORT_ORDER);
        final Cursor userCursor = mDatabaseWrapper.query(DirectMessages.Inbox.TABLE_NAME, userProjection,
                filteredSelection, null, DirectMessages.SENDER_ID, null, DirectMessages.DEFAULT_SORT_ORDER);
        try {
            final int usersCount = userCursor.getCount();
            final int messagesCount = messageCursor.getCount();
            if (messagesCount == 0 || usersCount == 0) return;
            final String accountName = Utils.getAccountName(context, accountId);
            final String accountScreenName = Utils.getAccountScreenName(context, accountId);
            final int idxMessageText = messageCursor.getColumnIndex(DirectMessages.TEXT_UNESCAPED),
                    idxMessageTimestamp = messageCursor.getColumnIndex(DirectMessages.MESSAGE_TIMESTAMP),
                    idxMessageUserName = messageCursor.getColumnIndex(DirectMessages.SENDER_NAME),
                    idxMessageUserScreenName = messageCursor.getColumnIndex(DirectMessages.SENDER_SCREEN_NAME),
                    idxUserName = userCursor.getColumnIndex(DirectMessages.SENDER_NAME),
                    idxUserScreenName = userCursor.getColumnIndex(DirectMessages.SENDER_NAME),
                    idxUserId = userCursor.getColumnIndex(DirectMessages.SENDER_NAME);

            final CharSequence notificationTitle = resources.getQuantityString(R.plurals.N_new_messages,
                    messagesCount, messagesCount);
            final String notificationContent;
            userCursor.moveToFirst();
            final String displayName = UserColorNameUtils.getUserNickname(context, userCursor.getLong(idxUserId),
                    mNameFirst ? userCursor.getString(idxUserName) : userCursor.getString(idxUserScreenName));
            if (usersCount == 1) {
                if (messagesCount == 1) {
                    notificationContent = context.getString(R.string.notification_direct_message, displayName);
                } else {
                    notificationContent = context.getString(R.string.notification_direct_message_multiple_messages,
                            displayName, messagesCount);
                }
            } else {
                notificationContent = context.getString(R.string.notification_direct_message_multiple_users,
                        displayName, usersCount - 1, messagesCount);
            }

            // Add rich notification and get latest tweet timestamp
            long when = -1;
            final InboxStyle style = new InboxStyle();
            for (int i = 0, j = Math.min(messagesCount, 5); messageCursor.moveToPosition(i) && i < j; i++) {
                if (when < 0) {
                    when = messageCursor.getLong(idxMessageTimestamp);
                }
                final SpannableStringBuilder sb = new SpannableStringBuilder();
                sb.append(UserColorNameUtils.getUserNickname(context, messageCursor.getLong(idxUserId),
                        mNameFirst ? messageCursor.getString(idxMessageUserName) : messageCursor.getString(idxMessageUserScreenName)));
                sb.setSpan(new StyleSpan(Typeface.BOLD), 0, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.append(' ');
                sb.append(messageCursor.getString(idxMessageText));
                style.addLine(sb);
            }
            if (mNameFirst) {
                style.setSummaryText(accountName);
            } else {
                style.setSummaryText("@" + accountScreenName);
            }

            // Setup on click intent
            final Intent homeIntent = new Intent(context, HomeActivity.class);
            final PendingIntent clickIntent = PendingIntent.getActivity(context, 0, homeIntent, 0);

            // Setup notification
            final NotificationCompat.Builder builder = new NotificationCompat.Builder(context);
            builder.setSmallIcon(R.drawable.ic_stat_direct_message);
            builder.setTicker(notificationTitle);
            builder.setContentTitle(notificationTitle);
            builder.setContentText(notificationContent);
            builder.setCategory(NotificationCompat.CATEGORY_SOCIAL);
            builder.setContentIntent(clickIntent);
            builder.setNumber(messagesCount);
            builder.setWhen(when);
            builder.setStyle(style);
            builder.setColor(pref.getNotificationLightColor());
            nm.notify("messages_" + accountId, NOTIFICATION_ID_DIRECT_MESSAGES, builder.build());
        } finally {
            messageCursor.close();
            userCursor.close();
        }
    }

    private void preloadImages(final ContentValues... values) {
        if (values == null) return;
        for (final ContentValues v : values) {
            if (mPreferences.getBoolean(KEY_PRELOAD_PROFILE_IMAGES, false)) {
                mImagePreloader.preloadImage(v.getAsString(Statuses.USER_PROFILE_IMAGE_URL));
                mImagePreloader.preloadImage(v.getAsString(DirectMessages.SENDER_PROFILE_IMAGE_URL));
                mImagePreloader.preloadImage(v.getAsString(DirectMessages.RECIPIENT_PROFILE_IMAGE_URL));
            }
            if (mPreferences.getBoolean(KEY_PRELOAD_PREVIEW_IMAGES, false)) {
                final String textHtml = v.getAsString(Statuses.TEXT_HTML);
                for (final String link : MediaPreviewUtils.getSupportedLinksInStatus(textHtml)) {
                    mImagePreloader.preloadImage(link);
                }
            }
        }
    }

    private void setNotificationUri(final Cursor c, final Uri uri) {
        final ContentResolver cr = getContentResolver();
        if (cr == null || c == null || uri == null) return;
        c.setNotificationUri(cr, uri);
    }

    private void updatePreferences() {
        mNameFirst = mPreferences.getBoolean(KEY_NAME_FIRST, false);
    }

    private static Cursor getPreferencesCursor(final SharedPreferencesWrapper preferences, final String key) {
        final MatrixCursor c = new MatrixCursor(TwidereDataStore.Preferences.MATRIX_COLUMNS);
        final Map<String, Object> map = new HashMap<>();
        final Map<String, ?> all = preferences.getAll();
        if (key == null) {
            map.putAll(all);
        } else {
            map.put(key, all.get(key));
        }
        for (final Map.Entry<String, ?> item : map.entrySet()) {
            final Object value = item.getValue();
            final int type = getPreferenceType(value);
            c.addRow(new Object[]{item.getKey(), ParseUtils.parseString(value), type});
        }
        return c;
    }

    private static int getPreferenceType(final Object object) {
        if (object == null)
            return Preferences.TYPE_NULL;
        else if (object instanceof Boolean)
            return Preferences.TYPE_BOOLEAN;
        else if (object instanceof Integer)
            return Preferences.TYPE_INTEGER;
        else if (object instanceof Long)
            return Preferences.TYPE_LONG;
        else if (object instanceof Float)
            return Preferences.TYPE_FLOAT;
        else if (object instanceof String) return Preferences.TYPE_STRING;
        return Preferences.TYPE_INVALID;
    }


    private static int getUnreadCount(final List<UnreadItem> set, final long... accountIds) {
        if (set == null || set.isEmpty()) return 0;
        int count = 0;
        for (final UnreadItem item : set.toArray(new UnreadItem[set.size()])) {
            if (item != null && ArrayUtils.contains(accountIds, item.account_id)) {
                count++;
            }
        }
        return count;
    }

    private static <T> T safeGet(final List<T> list, final int index) {
        return index >= 0 && index < list.size() ? list.get(index) : null;
    }

    private static boolean shouldReplaceOnConflict(final int table_id) {
        switch (table_id) {
            case TABLE_ID_CACHED_HASHTAGS:
            case TABLE_ID_CACHED_STATUSES:
            case TABLE_ID_CACHED_USERS:
            case TABLE_ID_CACHED_RELATIONSHIPS:
            case TABLE_ID_SEARCH_HISTORY:
            case TABLE_ID_FILTERED_USERS:
            case TABLE_ID_FILTERED_KEYWORDS:
            case TABLE_ID_FILTERED_SOURCES:
            case TABLE_ID_FILTERED_LINKS:
                return true;
        }
        return false;
    }

    @SuppressWarnings("unused")
    private static class GetWritableDatabaseTask extends AsyncTask<Void, Void, SQLiteDatabase> {
        private final Context mContext;
        private final SQLiteOpenHelper mHelper;
        private final SQLiteDatabaseWrapper mWrapper;

        GetWritableDatabaseTask(final Context context, final SQLiteOpenHelper helper,
                                final SQLiteDatabaseWrapper wrapper) {
            mContext = context;
            mHelper = helper;
            mWrapper = wrapper;
        }

        @Override
        protected SQLiteDatabase doInBackground(final Void... params) {
            return mHelper.getWritableDatabase();
        }

        @Override
        protected void onPostExecute(final SQLiteDatabase result) {
            mWrapper.setSQLiteDatabase(result);
            if (result != null) {
                mContext.sendBroadcast(new Intent(BROADCAST_DATABASE_READY));
            }
        }
    }

}
