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

package org.mariotaku.twidere.fragment.support;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.LoaderManager.LoaderCallbacks;
import android.support.v4.content.AsyncTaskLoader;
import android.support.v4.content.Loader;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.mariotaku.twidere.R;
import org.mariotaku.twidere.app.TwidereApplication;
import org.mariotaku.twidere.model.ParcelableStatus;
import org.mariotaku.twidere.model.SingleResponse;
import org.mariotaku.twidere.util.AsyncTwitterWrapper;
import org.mariotaku.twidere.util.ImageLoadingHandler;
import org.mariotaku.twidere.util.MediaLoaderWrapper;
import org.mariotaku.twidere.util.SharedPreferencesWrapper;
import org.mariotaku.twidere.util.Utils;
import org.mariotaku.twidere.view.holder.StatusViewHolder;

import twitter4j.TranslationResult;
import twitter4j.Twitter;
import twitter4j.TwitterException;

public class StatusTranslateDialogFragment extends BaseSupportDialogFragment implements
        LoaderCallbacks<SingleResponse<TranslationResult>> {

    private StatusViewHolder mHolder;
    private ProgressBar mProgressBar;
    private TextView mMessageView;
    private View mProgressContainer;
    private View mStatusContainer;

    public StatusTranslateDialogFragment() {
        setStyle(STYLE_NO_TITLE, 0);
    }

    @Override
    public void onActivityCreated(final Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        final Bundle args = getArguments();
        if (args == null || args.getParcelable(EXTRA_STATUS) == null) {
            dismiss();
            return;
        }
        getLoaderManager().initLoader(0, args, this);
    }

    @Override
    public Loader<SingleResponse<TranslationResult>> onCreateLoader(final int id, final Bundle args) {
        final ParcelableStatus status = args.getParcelable(EXTRA_STATUS);
        mStatusContainer.setVisibility(View.GONE);
        mProgressContainer.setVisibility(View.VISIBLE);
        mProgressBar.setVisibility(View.VISIBLE);
        mMessageView.setVisibility(View.VISIBLE);
        mMessageView.setText(R.string.please_wait);
        return new TranslationResultLoader(getActivity(), status.account_id, status.id);
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mProgressContainer = view.findViewById(R.id.progress_container);
        mProgressBar = (ProgressBar) mProgressContainer.findViewById(android.R.id.progress);
        mMessageView = (TextView) mProgressContainer.findViewById(android.R.id.message);
        mStatusContainer = view.findViewById(R.id.status_container);
        mHolder = new StatusViewHolder(mStatusContainer);
    }

    @Override
    public View onCreateView(final LayoutInflater inflater, final ViewGroup parent, final Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_translate_status, parent, false);
    }

    @Override
    public void onLoaderReset(final Loader<SingleResponse<TranslationResult>> loader) {

    }

    @Override
    public void onLoadFinished(final Loader<SingleResponse<TranslationResult>> loader,
                               final SingleResponse<TranslationResult> data) {
        final Bundle args = getArguments();
        final ParcelableStatus status = args.getParcelable(EXTRA_STATUS);
        if (status != null && data.getData() != null) {
            displayTranslatedStatus(status, data.getData());
            mStatusContainer.setVisibility(View.VISIBLE);
            mProgressContainer.setVisibility(View.GONE);
        } else {
            mStatusContainer.setVisibility(View.GONE);
            mProgressContainer.setVisibility(View.VISIBLE);
            mProgressBar.setVisibility(View.GONE);
            mMessageView.setVisibility(View.VISIBLE);
            mMessageView.setText(Utils.getErrorMessage(getActivity(), data.getException()));
        }
    }

    private void displayTranslatedStatus(final ParcelableStatus status, final TranslationResult translated) {
        if (status == null || translated == null) return;
        final FragmentActivity activity = getActivity();
        final TwidereApplication application = getApplication();
        final MediaLoaderWrapper loader = application.getImageLoaderWrapper();
        final ImageLoadingHandler handler = new ImageLoadingHandler(R.id.media_preview_progress);
        final AsyncTwitterWrapper twitter = getTwitterWrapper();
        final SharedPreferencesWrapper preferences = SharedPreferencesWrapper.getInstance(activity,
                SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE);
        final int profileImageStyle = Utils.getProfileImageStyle(preferences.getString(KEY_PROFILE_IMAGE_STYLE, null));
        final int mediaPreviewStyle = Utils.getMediaPreviewStyle(preferences.getString(KEY_MEDIA_PREVIEW_STYLE, null));
        final boolean nameFirst = preferences.getBoolean(KEY_NAME_FIRST, true);
        final boolean displayMediaPreview = preferences.getBoolean(KEY_MEDIA_PREVIEW, false);

        mHolder.displayStatus(activity, loader, handler, twitter, displayMediaPreview, true,
                true, nameFirst, profileImageStyle, mediaPreviewStyle, status, null, true);

        mStatusContainer.findViewById(R.id.item_menu).setVisibility(View.GONE);
        mStatusContainer.findViewById(R.id.action_buttons).setVisibility(View.GONE);
        mStatusContainer.findViewById(R.id.reply_retweet_status).setVisibility(View.GONE);
    }

    public static void show(final FragmentManager fm, final ParcelableStatus status) {
        final StatusTranslateDialogFragment df = new StatusTranslateDialogFragment();
        final Bundle args = new Bundle();
        args.putParcelable(EXTRA_STATUS, status);
        df.setArguments(args);
        df.show(fm, "translate_status");
    }

    public static final class TranslationResultLoader extends AsyncTaskLoader<SingleResponse<TranslationResult>> {

        private final long mAccountId;
        private final long mStatusId;

        public TranslationResultLoader(final Context context, final long accountId, final long statusId) {
            super(context);
            mAccountId = accountId;
            mStatusId = statusId;
        }

        @Override
        public SingleResponse<TranslationResult> loadInBackground() {
            final Context context = getContext();
            final Twitter twitter = Utils.getTwitterInstance(context, mAccountId, false);
            final SharedPreferences prefs = context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE);
            if (twitter == null) return SingleResponse.getInstance();
            try {
                final String prefDest = prefs.getString(KEY_TRANSLATION_DESTINATION, null);
                final String dest;
                if (TextUtils.isEmpty(prefDest)) {
                    dest = twitter.getAccountSettings().getLanguage();
                    final Editor editor = prefs.edit();
                    editor.putString(KEY_TRANSLATION_DESTINATION, dest);
                    editor.apply();
                } else {
                    dest = prefDest;
                }
                return SingleResponse.getInstance(twitter.showTranslation(mStatusId, dest));
            } catch (final TwitterException e) {
                return SingleResponse.getInstance(e);
            }
        }

        @Override
        protected void onStartLoading() {
            forceLoad();
        }

    }

}
