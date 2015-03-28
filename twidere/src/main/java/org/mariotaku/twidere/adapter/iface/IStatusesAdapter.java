package org.mariotaku.twidere.adapter.iface;

import org.mariotaku.twidere.model.ParcelableStatus;
import org.mariotaku.twidere.view.holder.StatusViewHolder.StatusClickListener;

/**
 * Created by mariotaku on 14/11/18.
 */
public interface IStatusesAdapter<Data> extends IContentCardAdapter, StatusClickListener {

    ParcelableStatus getStatus(int position);

    int getStatusesCount();

    long getStatusId(int position);

    boolean isMediaPreviewEnabled();

    boolean isNameFirst();

    void setData(Data data);

    boolean shouldShowAccountsColor();
}
