package org.moire.ultrasonic.fragment;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import org.jetbrains.annotations.NotNull;
import org.moire.ultrasonic.R;
import org.moire.ultrasonic.api.subsonic.ApiNotSupportedException;
import org.moire.ultrasonic.data.ActiveServerProvider;
import org.moire.ultrasonic.domain.Playlist;
import org.moire.ultrasonic.service.MusicService;
import org.moire.ultrasonic.service.MusicServiceFactory;
import org.moire.ultrasonic.service.OfflineException;
import org.moire.ultrasonic.subsonic.DownloadHandler;
import org.moire.ultrasonic.util.BackgroundTask;
import org.moire.ultrasonic.util.CacheCleaner;
import org.moire.ultrasonic.util.CancellationToken;
import org.moire.ultrasonic.util.Constants;
import org.moire.ultrasonic.util.LoadingTask;
import org.moire.ultrasonic.util.TabActivityBackgroundTask;
import org.moire.ultrasonic.util.Util;
import org.moire.ultrasonic.view.PlaylistAdapter;

import java.util.List;

import kotlin.Lazy;
import timber.log.Timber;

import static org.koin.java.KoinJavaComponent.inject;

public class PlaylistsFragment extends Fragment {

    private SwipeRefreshLayout refreshPlaylistsListView;
    private ListView playlistsListView;
    private View emptyTextView;
    private PlaylistAdapter playlistAdapter;

    private final Lazy<DownloadHandler> downloadHandler = inject(DownloadHandler.class);
    private CancellationToken cancellationToken;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        Util.applyTheme(this.getContext());
        super.onCreate(savedInstanceState);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.select_playlist, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        cancellationToken = new CancellationToken();

        refreshPlaylistsListView = view.findViewById(R.id.select_playlist_refresh);
        playlistsListView = view.findViewById(R.id.select_playlist_list);

        refreshPlaylistsListView.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener()
        {
            @Override
            public void onRefresh()
            {
                new GetDataTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
            }
        });

        emptyTextView = view.findViewById(R.id.select_playlist_empty);
        playlistsListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Playlist playlist = (Playlist) parent.getItemAtPosition(position);

                if (playlist == null)
                {
                    return;
                }

                Bundle bundle = new Bundle();
                bundle.putString(Constants.INTENT_EXTRA_NAME_ID, playlist.getId());
                bundle.putString(Constants.INTENT_EXTRA_NAME_PLAYLIST_ID, playlist.getId());
                bundle.putString(Constants.INTENT_EXTRA_NAME_PLAYLIST_NAME, playlist.getName());
                Navigation.findNavController(getView()).navigate(R.id.selectAlbumFragment, bundle);
            }
        });
        registerForContextMenu(playlistsListView);

        FragmentTitle.Companion.setTitle(this, R.string.playlist_label);

        load();
    }

    @Override
    public void onDestroyView() {
        cancellationToken.cancel();
        super.onDestroyView();
    }

    private void refresh()
    {
        // TODO: create better restart
        getView().post(new Runnable() {
            public void run() {
                Timber.d("Refresh called...");
                if (getArguments() == null) {
                    Bundle bundle = new Bundle();
                    bundle.putBoolean(Constants.INTENT_EXTRA_NAME_REFRESH, true);
                    setArguments(bundle);
                } else {
                    getArguments().putBoolean(Constants.INTENT_EXTRA_NAME_REFRESH, true);
                }
                onViewCreated(getView(), null);
            }
        });

/*        finish();
        Intent intent = new Intent(this, SelectPlaylistActivity.class);
        intent.putExtra(Constants.INTENT_EXTRA_NAME_REFRESH, true);
        startActivityForResultWithoutTransition(this, intent);
 */
    }

    private void load()
    {
        BackgroundTask<List<Playlist>> task = new TabActivityBackgroundTask<List<Playlist>>(getActivity(), true, refreshPlaylistsListView, cancellationToken)
        {
            @Override
            protected List<Playlist> doInBackground() throws Throwable
            {
                MusicService musicService = MusicServiceFactory.getMusicService(getContext());
                boolean refresh = getArguments() != null && getArguments().getBoolean(Constants.INTENT_EXTRA_NAME_REFRESH, false);
                List<Playlist> playlists = musicService.getPlaylists(refresh, getContext(), this);

                if (!ActiveServerProvider.Companion.isOffline(getContext()))
                    new CacheCleaner(getContext()).cleanPlaylists(playlists);
                return playlists;
            }

            @Override
            protected void done(List<Playlist> result)
            {
                playlistsListView.setAdapter(playlistAdapter = new PlaylistAdapter(getContext(), result));
                emptyTextView.setVisibility(result.isEmpty() ? View.VISIBLE : View.GONE);
            }
        };
        task.execute();
    }

    @Override
    public void onCreateContextMenu(@NotNull ContextMenu menu, @NotNull View view, ContextMenu.ContextMenuInfo menuInfo)
    {
        super.onCreateContextMenu(menu, view, menuInfo);

        MenuInflater inflater = getActivity().getMenuInflater();
        if (ActiveServerProvider.Companion.isOffline(getContext())) inflater.inflate(R.menu.select_playlist_context_offline, menu);
        else inflater.inflate(R.menu.select_playlist_context, menu);

        MenuItem downloadMenuItem = menu.findItem(R.id.album_menu_download);

        if (downloadMenuItem != null)
        {
            downloadMenuItem.setVisible(!ActiveServerProvider.Companion.isOffline(getContext()));
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem menuItem)
    {
        AdapterView.AdapterContextMenuInfo info = (AdapterView.AdapterContextMenuInfo) menuItem.getMenuInfo();
        if (info == null)
        {
            return false;
        }

        Playlist playlist = (Playlist) playlistsListView.getItemAtPosition(info.position);
        if (playlist == null)
        {
            return false;
        }

        Bundle bundle;
        switch (menuItem.getItemId())
        {
            case R.id.playlist_menu_pin:
                downloadHandler.getValue().downloadPlaylist(this, playlist.getId(), playlist.getName(), true, true, false, false, true, false, false);
                break;
            case R.id.playlist_menu_unpin:
                downloadHandler.getValue().downloadPlaylist(this, playlist.getId(), playlist.getName(), false, false, false, false, true, false, true);
                break;
            case R.id.playlist_menu_download:
                downloadHandler.getValue().downloadPlaylist(this, playlist.getId(), playlist.getName(), false, false, false, false, true, false, false);
                break;
            case R.id.playlist_menu_play_now:
                bundle = new Bundle();
                bundle.putString(Constants.INTENT_EXTRA_NAME_PLAYLIST_ID, playlist.getId());
                bundle.putString(Constants.INTENT_EXTRA_NAME_PLAYLIST_NAME, playlist.getName());
                bundle.putBoolean(Constants.INTENT_EXTRA_NAME_AUTOPLAY, true);
                Navigation.findNavController(getView()).navigate(R.id.selectAlbumFragment, bundle);
                break;
            case R.id.playlist_menu_play_shuffled:
                bundle = new Bundle();
                bundle.putString(Constants.INTENT_EXTRA_NAME_PLAYLIST_ID, playlist.getId());
                bundle.putString(Constants.INTENT_EXTRA_NAME_PLAYLIST_NAME, playlist.getName());
                bundle.putBoolean(Constants.INTENT_EXTRA_NAME_AUTOPLAY, true);
                bundle.putBoolean(Constants.INTENT_EXTRA_NAME_SHUFFLE, true);
                Navigation.findNavController(getView()).navigate(R.id.selectAlbumFragment, bundle);
                break;
            case R.id.playlist_menu_delete:
                deletePlaylist(playlist);
                break;
            case R.id.playlist_info:
                displayPlaylistInfo(playlist);
                break;
            case R.id.playlist_update_info:
                updatePlaylistInfo(playlist);
                break;
            default:
                return super.onContextItemSelected(menuItem);
        }
        return true;
    }

    private void deletePlaylist(final Playlist playlist)
    {
        new AlertDialog.Builder(getContext()).setIcon(android.R.drawable.ic_dialog_alert).setTitle(R.string.common_confirm).setMessage(getResources().getString(R.string.delete_playlist, playlist.getName())).setPositiveButton(R.string.common_ok, new DialogInterface.OnClickListener()
        {
            @Override
            public void onClick(DialogInterface dialog, int which)
            {
                new LoadingTask<Void>(getActivity(), false, refreshPlaylistsListView)
                {
                    @Override
                    protected Void doInBackground() throws Throwable
                    {
                        MusicService musicService = MusicServiceFactory.getMusicService(getContext());
                        musicService.deletePlaylist(playlist.getId(), getContext(), null);
                        return null;
                    }

                    @Override
                    protected void done(Void result)
                    {
                        playlistAdapter.remove(playlist);
                        playlistAdapter.notifyDataSetChanged();
                        Util.toast(getContext(), getResources().getString(R.string.menu_deleted_playlist, playlist.getName()));
                    }

                    @Override
                    protected void error(Throwable error)
                    {
                        String msg;
                        msg = error instanceof OfflineException || error instanceof ApiNotSupportedException ? getErrorMessage(error) : String.format("%s %s", getResources().getString(R.string.menu_deleted_playlist_error, playlist.getName()), getErrorMessage(error));

                        Util.toast(getContext(), msg, false);
                    }
                }.execute();
            }

        }).setNegativeButton(R.string.common_cancel, null).show();
    }

    private void displayPlaylistInfo(final Playlist playlist)
    {
        final TextView textView = new TextView(getContext());
        textView.setPadding(5, 5, 5, 5);

        final Spannable message = new SpannableString("Owner: " + playlist.getOwner() + "\nComments: " +
            ((playlist.getComment() == null) ? "" : playlist.getComment()) +
            "\nSong Count: " + playlist.getSongCount() +
            ((playlist.getPublic() == null) ? "" : ("\nPublic: " + playlist.getPublic()) + ((playlist.getCreated() == null) ? "" : ("\nCreation Date: " + playlist.getCreated().replace('T', ' ')))));

        Linkify.addLinks(message, Linkify.WEB_URLS);
        textView.setText(message);
        textView.setMovementMethod(LinkMovementMethod.getInstance());

        new AlertDialog.Builder(getContext()).setTitle(playlist.getName()).setCancelable(true).setIcon(android.R.drawable.ic_dialog_info).setView(textView).show();
    }

    private void updatePlaylistInfo(final Playlist playlist)
    {
        View dialogView = getLayoutInflater().inflate(R.layout.update_playlist, null);

        if (dialogView == null)
        {
            return;
        }

        final EditText nameBox = dialogView.findViewById(R.id.get_playlist_name);
        final EditText commentBox = dialogView.findViewById(R.id.get_playlist_comment);
        final CheckBox publicBox = dialogView.findViewById(R.id.get_playlist_public);

        nameBox.setText(playlist.getName());
        commentBox.setText(playlist.getComment());
        Boolean pub = playlist.getPublic();

        if (pub == null)
        {
            publicBox.setEnabled(false);
        }
        else
        {
            publicBox.setChecked(pub);
        }

        AlertDialog.Builder alertDialog = new AlertDialog.Builder(getContext());

        alertDialog.setIcon(android.R.drawable.ic_dialog_alert);
        alertDialog.setTitle(R.string.playlist_update_info);
        alertDialog.setView(dialogView);
        alertDialog.setPositiveButton(R.string.common_ok, new DialogInterface.OnClickListener()
        {
            @Override
            public void onClick(DialogInterface dialog, int which)
            {
                new LoadingTask<Void>(getActivity(), false, refreshPlaylistsListView)
                {
                    @Override
                    protected Void doInBackground() throws Throwable
                    {
                        Editable nameBoxText = nameBox.getText();
                        Editable commentBoxText = commentBox.getText();
                        String name = nameBoxText != null ? nameBoxText.toString() : null;
                        String comment = commentBoxText != null ? commentBoxText.toString() : null;

                        MusicService musicService = MusicServiceFactory.getMusicService(getContext());
                        musicService.updatePlaylist(playlist.getId(), name, comment, publicBox.isChecked(), getContext(), null);
                        return null;
                    }

                    @Override
                    protected void done(Void result)
                    {
                        refresh();
                        Util.toast(getContext(), getResources().getString(R.string.playlist_updated_info, playlist.getName()));
                    }

                    @Override
                    protected void error(Throwable error)
                    {
                        String msg;
                        msg = error instanceof OfflineException || error instanceof ApiNotSupportedException ? getErrorMessage(error) : String.format("%s %s", getResources().getString(R.string.playlist_updated_info_error, playlist.getName()), getErrorMessage(error));

                        Util.toast(getContext(), msg, false);
                    }
                }.execute();
            }

        });
        alertDialog.setNegativeButton(R.string.common_cancel, null);
        alertDialog.show();
    }

    private class GetDataTask extends AsyncTask<Void, Void, String[]>
    {
        @Override
        protected void onPostExecute(String[] result)
        {
            super.onPostExecute(result);
        }

        @Override
        protected String[] doInBackground(Void... params)
        {
            refresh();
            return null;
        }
    }
}
