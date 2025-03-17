package dev.brahmkshatriya.echo.ui.media.more

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.app.ShareCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.clients.ArtistFollowClient
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.LibraryFeedClient
import dev.brahmkshatriya.echo.common.clients.RadioClient
import dev.brahmkshatriya.echo.common.clients.SaveToLibraryClient
import dev.brahmkshatriya.echo.common.clients.ShareClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.clients.TrackHideClient
import dev.brahmkshatriya.echo.common.clients.TrackLikeClient
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.EchoMediaItem.Companion.toMediaItem
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.databinding.DialogMediaMoreBinding
import dev.brahmkshatriya.echo.extensions.builtin.offline.OfflineExtension
import dev.brahmkshatriya.echo.extensions.builtin.unified.UnifiedExtension.Companion.EXTENSION_ID
import dev.brahmkshatriya.echo.ui.UiViewModel
import dev.brahmkshatriya.echo.ui.common.FragmentUtils.openFragment
import dev.brahmkshatriya.echo.ui.media.MediaFragment
import dev.brahmkshatriya.echo.ui.media.MediaViewModel
import dev.brahmkshatriya.echo.ui.media.more.Action.Companion.resource
import dev.brahmkshatriya.echo.ui.player.PlayerViewModel
import dev.brahmkshatriya.echo.ui.player.audiofx.AudioEffectsBottomSheet
import dev.brahmkshatriya.echo.ui.player.quality.QualitySelectionBottomSheet
import dev.brahmkshatriya.echo.ui.player.sleep.SleepTimerBottomSheet
import dev.brahmkshatriya.echo.ui.shelf.adapter.MediaItemViewHolder
import dev.brahmkshatriya.echo.utils.ContextUtils.observe
import dev.brahmkshatriya.echo.utils.Serializer.getSerialized
import dev.brahmkshatriya.echo.utils.Serializer.putSerialized
import dev.brahmkshatriya.echo.utils.ui.AutoClearedValue.Companion.autoCleared
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.core.parameter.parametersOf

class MediaMoreBottomSheet : BottomSheetDialogFragment() {

    companion object {
        fun newInstance(
            contId: Int,
            extensionId: String,
            item: EchoMediaItem,
            loaded: Boolean,
            fromPlayer: Boolean = false
        ) = MediaMoreBottomSheet().apply {
            arguments = Bundle().apply {
                putInt("id", contId)
                putString("extensionId", extensionId)
                putSerialized("item", item)
                putBoolean("loaded", loaded)
                putBoolean("fromPlayer", fromPlayer)
            }
        }
    }

    private var binding by autoCleared<DialogMediaMoreBinding>()
    private val playerViewModel by activityViewModel<PlayerViewModel>()
    private val uiViewModel by activityViewModel<UiViewModel>()
    private val vm by viewModel<MediaViewModel> { parametersOf(extensionId, item, loaded, false) }

    private val itemAdapter by lazy {
        GenericItemAdapter(
            playerViewModel.playerState.current,
            object : MediaItemViewHolder.Listener {
                override fun onMediaItemClicked(
                    extensionId: String?, item: EchoMediaItem?, it: View?
                ) {
                    openItemFragment(extensionId, item)
                    dismiss()
                }
            }
        )
    }
    private val actionAdapter by lazy { Action.Adapter() }
    private val loadingAdapter by lazy { LoadingAdapter() }

    private val args by lazy { requireArguments() }
    private val contId by lazy { args.getInt("id") }
    private val extensionId by lazy { args.getString("extensionId")!! }
    private val item by lazy { args.getSerialized<EchoMediaItem>("item")!! }
    private val loaded by lazy { args.getBoolean("loaded") }
    private val fromPlayer by lazy { args.getBoolean("fromPlayer") }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        binding = DialogMediaMoreBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.root.adapter = ConcatAdapter(itemAdapter, actionAdapter, loadingAdapter)
        observe(vm.itemFlow) {
            itemAdapter.submitList(extensionId, listOf(item))
        }
        observe(playerViewModel.playerState.current) { itemAdapter.onCurrentChanged() }
        loadingAdapter.setLoading(vm.isLoading)
        observe(vm.loadingFlow) { loadingAdapter.setLoading(it) }
        vm.run {
            observe(itemFlow.combine(extensionFlow) { _, _ -> }.combine(savedState) { _, _ -> }) {
                val client = extensionFlow.value?.instance?.value()?.getOrNull()
                actionAdapter.submitList(
                    getActions(client, itemFlow.value, !isLoading)
                )
            }
        }
    }

    private fun getActions(
        client: ExtensionClient?, item: EchoMediaItem, loaded: Boolean
    ) = when (item) {
        is EchoMediaItem.Lists -> getPlayButtons(client, item, loaded) + when (item) {
            is EchoMediaItem.Lists.AlbumItem -> listOfNotNull(
                radioButton(client, item, loaded),
                saveToPlaylist(client, item),
                saveToLibraryButton(client, loaded),
                downloadButton(client, item),
            ) + item.album.artists.map {
                Action(it.name, Action.CustomImage(it.cover, R.drawable.ic_artist, true)) {
                    openItemFragment(extensionId, it.toMediaItem())
                }
            }

            is EchoMediaItem.Lists.PlaylistItem -> listOfNotNull(
                radioButton(client, item, loaded),
                saveToPlaylist(client, item),
                saveToLibraryButton(client, loaded),
                downloadButton(client, item),
                if (client is LibraryFeedClient && item.playlist.isEditable)
                    resource(R.drawable.ic_delete, R.string.delete_playlist) {
                        vm.deletePlaylist(item.playlist)
                    }
                else null,
            ) + item.playlist.authors.map {
                Action(it.name, Action.CustomImage(it.cover, R.drawable.ic_person, true)) {
                    openItemFragment(extensionId, it.toMediaItem())
                }
            }

            is EchoMediaItem.Lists.RadioItem ->
                listOfNotNull(saveToLibraryButton(client, loaded))
        }

        is EchoMediaItem.Profile -> getPlayButtons(client, item, loaded) + when (item) {
            is EchoMediaItem.Profile.ArtistItem -> listOfNotNull(
                radioButton(client, item, loaded),
                saveToLibraryButton(client, loaded),
                followButton(client, item.artist, loaded)
            )

            is EchoMediaItem.Profile.UserItem -> listOf()
        }

        is EchoMediaItem.TrackItem -> getTrackButtons(client, item.track, loaded) + listOfNotNull(
            likeButton(client, item.track, loaded),
            hideButton(client, item.track, loaded),
            radioButton(client, item, loaded),
            saveToPlaylist(client, item),
            saveToLibraryButton(client, loaded),
            downloadButton(client, item),
            item.track.album?.let {
                Action(it.title, Action.CustomImage(it.cover, R.drawable.ic_album, false)) {
                    openItemFragment(extensionId, it.toMediaItem())
                }
            }
        ) + item.track.artists.map {
            Action(it.name, Action.CustomImage(it.cover, R.drawable.ic_artist, true)) {
                openItemFragment(extensionId, it.toMediaItem())
            }
        }

    } + listOfNotNull(shareButton(client, item, loaded))

    private fun getPlayButtons(
        client: ExtensionClient?, item: EchoMediaItem, loaded: Boolean
    ) = if (client is TrackClient) listOfNotNull(
        resource(R.drawable.ic_play, R.string.play) {
            playerViewModel.play(extensionId, item, loaded)
        },
        if (playerViewModel.queue.isNotEmpty())
            resource(R.drawable.ic_playlist_play, R.string.add_to_next) {
                playerViewModel.addToNext(extensionId, item, loaded)
            }
        else null,
        if (playerViewModel.queue.size > 1)
            resource(R.drawable.ic_playlist_add, R.string.add_to_queue) {
                playerViewModel.addToQueue(extensionId, item, loaded)
            }
        else null
    ) else listOf()

    private fun getTrackButtons(client: ExtensionClient?, track: Track, loaded: Boolean) =
        if (client is TrackClient && !fromPlayer) getPlayButtons(
            client,
            track.toMediaItem(),
            loaded
        )
        else listOf(
            resource(R.drawable.ic_equalizer, R.string.audio_fx) {
                AudioEffectsBottomSheet().show(parentFragmentManager, null)
            },
            resource(R.drawable.ic_snooze, R.string.sleep_timer) {
                SleepTimerBottomSheet().show(parentFragmentManager, null)
            },
            resource(R.drawable.ic_high_quality, R.string.quality_selection) {
                QualitySelectionBottomSheet().show(parentFragmentManager, null)
            }
        )

    private fun likeButton(
        client: ExtensionClient?, track: Track, loaded: Boolean
    ) = if (client is TrackLikeClient && loaded && !fromPlayer)
        if (!track.isLiked)
            resource(R.drawable.ic_heart_outline_40dp, R.string.like) {
                vm.like(track, true)
            }
        else resource(R.drawable.ic_heart_filled_40dp, R.string.unlike) {
            vm.like(track, false)
        }
    else null

    private fun hideButton(
        client: ExtensionClient?, track: Track, loaded: Boolean
    ) = if (client is TrackHideClient && loaded)
        if (!track.isHidden)
            resource(R.drawable.ic_hide_outline, R.string.hide) {
                vm.hide(track, true)
            }
        else resource(R.drawable.ic_hide_filled, R.string.unhide) {
            vm.hide(track, false)
        }
    else null

    private fun followButton(
        client: ExtensionClient?, artist: Artist, loaded: Boolean
    ) = if (client is ArtistFollowClient && loaded)
        if (!artist.isFollowing)
            resource(R.drawable.ic_heart_outline_40dp, R.string.follow) {
                vm.follow(artist, true)
            }
        else resource(R.drawable.ic_heart_filled_40dp, R.string.unfollow) {
            vm.follow(artist, false)
        }
    else null

    private fun saveToPlaylist(
        client: ExtensionClient?, item: EchoMediaItem
    ) = if (client is LibraryFeedClient)
        resource(R.drawable.ic_library_music, R.string.save_to_playlist) {
            TODO()
//            AddToPlaylistBottomSheet.newInstance(clientId, item)
//                .show(parentFragmentManager, null)
        }
    else null

    private fun downloadButton(
        client: ExtensionClient?, item: EchoMediaItem
    ) = if (item.extras[EXTENSION_ID] != OfflineExtension.metadata.id && client is TrackClient)
        resource(R.drawable.ic_download_for_offline, R.string.download) {
            TODO()
//            downloadViewModel.addToDownload(requireActivity(), clientId, item)
        }
    else null

    private fun radioButton(
        client: ExtensionClient?, item: EchoMediaItem, loaded: Boolean
    ) = if (client is RadioClient && loaded) resource(
        R.drawable.ic_sensors, R.string.radio
    ) { playerViewModel.radio(extensionId, item) }
    else null

    private fun shareButton(client: ExtensionClient?, item: EchoMediaItem, loaded: Boolean) =
        if (client is ShareClient && loaded) resource(
            R.drawable.ic_forward,
            R.string.share
        ) {
            lifecycleScope.launch {
                val url = vm.onShare()
                ShareCompat.IntentBuilder(requireActivity())
                    .setType("text/plain")
                    .setChooserTitle("${vm.extensionFlow.value?.name} - ${vm.itemFlow.value.title}")
                    .setText(url)
                    .startChooser()
            }
        } else null

    private fun saveToLibraryButton(
        client: ExtensionClient?, loaded: Boolean
    ) = if (client is SaveToLibraryClient && loaded) {
        if (vm.savedState.value) resource(
            R.drawable.ic_bookmark_filled, R.string.remove_from_library
        ) { vm.saveToLibrary(false) }
        else resource(
            R.drawable.ic_bookmark_outline, R.string.save_to_library
        ) { vm.saveToLibrary(true) }
    } else null

    private fun openItemFragment(extensionId: String?, item: EchoMediaItem?) {
        extensionId ?: return
        item ?: return
        requireActivity().openFragment<MediaFragment>(
            null,
            MediaFragment.getBundle(extensionId, item, loaded),
            contId
        )
        dismiss()
        uiViewModel.collapsePlayer()
    }
}