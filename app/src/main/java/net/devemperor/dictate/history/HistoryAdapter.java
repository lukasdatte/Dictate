package net.devemperor.dictate.history;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import net.devemperor.dictate.R;
import net.devemperor.dictate.core.ActiveJobRegistry;
import net.devemperor.dictate.database.entity.SessionEntity;
import net.devemperor.dictate.database.entity.SessionStatus;
import net.devemperor.dictate.database.entity.SessionType;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.ViewHolder> {

    private final List<SessionEntity> data;
    private final AdapterCallback callback;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("dd.MM.yyyy, HH:mm", Locale.getDefault());

    public interface AdapterCallback {
        void onItemClicked(SessionEntity session);
        void onItemLongClicked(SessionEntity session, int position);
    }

    public HistoryAdapter(List<SessionEntity> data, AdapterCallback callback) {
        this.data = data;
        this.callback = callback;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_history_session, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        SessionEntity session = data.get(position);

        // Parse type once for icon + subtitle
        SessionType type = null;
        try {
            type = SessionType.valueOf(session.getType());
        } catch (IllegalArgumentException ignored) {
        }

        // Type icon
        String icon;
        if (type == null) {
            icon = "\u2753"; // question mark
        } else {
            switch (type) {
                case REWORDING:
                    icon = "\u270F\uFE0F"; // pencil
                    break;
                case POST_PROCESSING:
                    icon = "\uD83D\uDD04"; // arrows
                    break;
                case RECORDING:
                default:
                    icon = "\uD83C\uDFA4"; // mic
                    break;
            }
        }
        holder.typeIconTv.setText(icon);

        // Date
        holder.dateTv.setText(dateFormat.format(new Date(session.getCreatedAt())));

        // Subtitle — type-specific
        String subtitle = "";
        if (type != null) {
            switch (type) {
                case RECORDING:
                    long dur = session.getAudioDurationSeconds();
                    subtitle = holder.itemView.getContext().getString(
                            R.string.dictate_history_duration, dur / 60, dur % 60);
                    break;
                case REWORDING:
                    subtitle = holder.itemView.getContext().getString(R.string.dictate_history_rewording);
                    break;
                case POST_PROCESSING:
                    subtitle = holder.itemView.getContext().getString(
                            R.string.dictate_history_filter_post_processing);
                    break;
            }
        }
        holder.subtitleTv.setText(subtitle);
        holder.subtitleTv.setVisibility(subtitle.isEmpty() ? View.GONE : View.VISIBLE);

        // Preview text
        String preview = session.getFinalOutputText();
        if (preview == null || preview.isEmpty()) {
            preview = session.getInputText();
        }
        holder.previewTv.setText(preview != null ? preview : "");
        holder.previewTv.setVisibility(preview != null && !preview.isEmpty() ? View.VISIBLE : View.GONE);

        // Status badge — persistent status or runtime overlay (Phase 10.1)
        applyStatusBadge(holder, session);

        // Click handlers
        holder.itemView.setOnClickListener(v -> callback.onItemClicked(session));
        holder.itemView.setOnLongClickListener(v -> {
            callback.onItemLongClicked(session, holder.getAdapterPosition());
            return true;
        });
    }

    private void applyStatusBadge(ViewHolder holder, SessionEntity session) {
        // Runtime overlay takes precedence over persisted status.
        if (ActiveJobRegistry.INSTANCE.isActive(session.getId())) {
            holder.statusIcon.setVisibility(View.VISIBLE);
            holder.statusIcon.setImageResource(R.drawable.ic_baseline_sync_24);
            holder.statusTv.setVisibility(View.VISIBLE);
            holder.statusTv.setText(R.string.dictate_status_running);
            return;
        }

        SessionStatus status;
        try {
            status = SessionStatus.valueOf(session.getStatus());
        } catch (IllegalArgumentException e) {
            status = SessionStatus.RECORDED;
        }
        switch (status) {
            case COMPLETED:
                holder.statusIcon.setVisibility(View.GONE);
                holder.statusTv.setVisibility(View.GONE);
                break;
            case RECORDED:
                holder.statusIcon.setVisibility(View.VISIBLE);
                holder.statusIcon.setImageResource(R.drawable.ic_baseline_pending_24);
                holder.statusTv.setVisibility(View.VISIBLE);
                holder.statusTv.setText(R.string.dictate_status_recorded);
                break;
            case FAILED:
                holder.statusIcon.setVisibility(View.VISIBLE);
                holder.statusIcon.setImageResource(R.drawable.ic_baseline_error_outline_24);
                holder.statusTv.setVisibility(View.VISIBLE);
                holder.statusTv.setText(R.string.dictate_status_failed);
                break;
            case CANCELLED:
                holder.statusIcon.setVisibility(View.VISIBLE);
                holder.statusIcon.setImageResource(R.drawable.ic_baseline_cancel_24);
                holder.statusTv.setVisibility(View.VISIBLE);
                holder.statusTv.setText(R.string.dictate_status_cancelled);
                break;
        }
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView typeIconTv;
        final TextView dateTv;
        final TextView subtitleTv;
        final TextView previewTv;
        final ImageView statusIcon;
        final TextView statusTv;

        public ViewHolder(View itemView) {
            super(itemView);
            typeIconTv = itemView.findViewById(R.id.item_history_type_icon_tv);
            dateTv = itemView.findViewById(R.id.item_history_date_tv);
            subtitleTv = itemView.findViewById(R.id.item_history_subtitle_tv);
            previewTv = itemView.findViewById(R.id.item_history_preview_tv);
            statusIcon = itemView.findViewById(R.id.item_history_status_icon);
            statusTv = itemView.findViewById(R.id.item_history_status_tv);
        }
    }
}
