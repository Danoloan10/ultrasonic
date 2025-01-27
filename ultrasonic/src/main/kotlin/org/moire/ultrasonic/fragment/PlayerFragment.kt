/*
 * PlayerFragment.kt
 * Copyright (C) 2009-2021 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.fragment

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.graphics.Point
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.view.ContextMenu
import android.view.ContextMenu.ContextMenuInfo
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AnimationUtils
import android.widget.AdapterView.AdapterContextMenuInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import android.widget.TextView
import android.widget.ViewFlipper
import androidx.fragment.app.Fragment
import androidx.navigation.Navigation
import com.mobeta.android.dslv.DragSortListView
import com.mobeta.android.dslv.DragSortListView.DragSortListener
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Date
import java.util.LinkedList
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import org.koin.android.ext.android.inject
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.moire.ultrasonic.R
import org.moire.ultrasonic.audiofx.EqualizerController
import org.moire.ultrasonic.audiofx.VisualizerController
import org.moire.ultrasonic.data.ActiveServerProvider.Companion.isOffline
import org.moire.ultrasonic.domain.MusicDirectory
import org.moire.ultrasonic.domain.PlayerState
import org.moire.ultrasonic.domain.RepeatMode
import org.moire.ultrasonic.featureflags.Feature
import org.moire.ultrasonic.featureflags.FeatureStorage
import org.moire.ultrasonic.fragment.FragmentTitle.Companion.setTitle
import org.moire.ultrasonic.service.DownloadFile
import org.moire.ultrasonic.service.LocalMediaPlayer
import org.moire.ultrasonic.service.MediaPlayerController
import org.moire.ultrasonic.service.MusicServiceFactory.getMusicService
import org.moire.ultrasonic.subsonic.ImageLoaderProvider
import org.moire.ultrasonic.subsonic.NetworkAndStorageChecker
import org.moire.ultrasonic.subsonic.ShareHandler
import org.moire.ultrasonic.util.CancellationToken
import org.moire.ultrasonic.util.Constants
import org.moire.ultrasonic.util.Settings
import org.moire.ultrasonic.util.SilentBackgroundTask
import org.moire.ultrasonic.util.Util
import org.moire.ultrasonic.view.AutoRepeatButton
import org.moire.ultrasonic.view.SongListAdapter
import org.moire.ultrasonic.view.VisualizerView
import timber.log.Timber

/**
 * Contains the Music Player screen of Ultrasonic with playback controls and the playlist
 *
 * TODO: This class was more or less straight converted from Java legacy code.
 * There are many places where further cleanup would be nice.
 * The usage of threads and SilentBackgroundTask can be replaced with Coroutines.
 */
@Suppress("LargeClass", "TooManyFunctions", "MagicNumber")
class PlayerFragment : Fragment(), GestureDetector.OnGestureListener, KoinComponent {
    // Settings
    private var currentRevision: Long = 0
    private var swipeDistance = 0
    private var swipeVelocity = 0
    private var jukeboxAvailable = false
    private var useFiveStarRating = false
    private var isEqualizerAvailable = false
    private var isVisualizerAvailable = false

    // Detectors & Callbacks
    private lateinit var gestureScanner: GestureDetector
    private lateinit var cancellationToken: CancellationToken

    // Data & Services
    private val networkAndStorageChecker: NetworkAndStorageChecker by inject()
    private val mediaPlayerController: MediaPlayerController by inject()
    private val localMediaPlayer: LocalMediaPlayer by inject()
    private val shareHandler: ShareHandler by inject()
    private val imageLoaderProvider: ImageLoaderProvider by inject()
    private lateinit var executorService: ScheduledExecutorService
    private var currentPlaying: DownloadFile? = null
    private var currentSong: MusicDirectory.Entry? = null
    private var onProgressChangedTask: SilentBackgroundTask<Void?>? = null

    // Views and UI Elements
    private lateinit var visualizerViewLayout: LinearLayout
    private lateinit var visualizerView: VisualizerView
    private lateinit var playlistNameView: EditText
    private lateinit var starMenuItem: MenuItem
    private lateinit var fiveStar1ImageView: ImageView
    private lateinit var fiveStar2ImageView: ImageView
    private lateinit var fiveStar3ImageView: ImageView
    private lateinit var fiveStar4ImageView: ImageView
    private lateinit var fiveStar5ImageView: ImageView
    private lateinit var playlistFlipper: ViewFlipper
    private lateinit var emptyTextView: TextView
    private lateinit var songTitleTextView: TextView
    private lateinit var albumTextView: TextView
    private lateinit var artistTextView: TextView
    private lateinit var albumArtImageView: ImageView
    private lateinit var playlistView: DragSortListView
    private lateinit var positionTextView: TextView
    private lateinit var downloadTrackTextView: TextView
    private lateinit var downloadTotalDurationTextView: TextView
    private lateinit var durationTextView: TextView
    private lateinit var pauseButton: View
    private lateinit var stopButton: View
    private lateinit var startButton: View
    private lateinit var repeatButton: ImageView
    private lateinit var hollowStar: Drawable
    private lateinit var fullStar: Drawable
    private lateinit var progressBar: SeekBar

    override fun onCreate(savedInstanceState: Bundle?) {
        Util.applyTheme(this.context)
        super.onCreate(savedInstanceState)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.current_playing, container, false)
    }

    fun findViews(view: View) {
        playlistFlipper = view.findViewById(R.id.current_playing_playlist_flipper)
        emptyTextView = view.findViewById(R.id.playlist_empty)
        songTitleTextView = view.findViewById(R.id.current_playing_song)
        albumTextView = view.findViewById(R.id.current_playing_album)
        artistTextView = view.findViewById(R.id.current_playing_artist)
        albumArtImageView = view.findViewById(R.id.current_playing_album_art_image)
        positionTextView = view.findViewById(R.id.current_playing_position)
        downloadTrackTextView = view.findViewById(R.id.current_playing_track)
        downloadTotalDurationTextView = view.findViewById(R.id.current_total_duration)
        durationTextView = view.findViewById(R.id.current_playing_duration)
        progressBar = view.findViewById(R.id.current_playing_progress_bar)
        playlistView = view.findViewById(R.id.playlist_view)

        pauseButton = view.findViewById(R.id.button_pause)
        stopButton = view.findViewById(R.id.button_stop)
        startButton = view.findViewById(R.id.button_start)
        repeatButton = view.findViewById(R.id.button_repeat)
        visualizerViewLayout = view.findViewById(R.id.current_playing_visualizer_layout)
        fiveStar1ImageView = view.findViewById(R.id.song_five_star_1)
        fiveStar2ImageView = view.findViewById(R.id.song_five_star_2)
        fiveStar3ImageView = view.findViewById(R.id.song_five_star_3)
        fiveStar4ImageView = view.findViewById(R.id.song_five_star_4)
        fiveStar5ImageView = view.findViewById(R.id.song_five_star_5)
    }

    @Suppress("LongMethod")
    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        cancellationToken = CancellationToken()
        setTitle(this, R.string.common_appname)
        val windowManager = requireActivity().windowManager
        val display = windowManager.defaultDisplay
        val size = Point()
        display.getSize(size)
        val width = size.x
        val height = size.y
        setHasOptionsMenu(true)
        useFiveStarRating = get<FeatureStorage>().isFeatureEnabled(Feature.FIVE_STAR_RATING)
        swipeDistance = (width + height) * PERCENTAGE_OF_SCREEN_FOR_SWIPE / 100
        swipeVelocity = swipeDistance
        gestureScanner = GestureDetector(context, this)

        // The secondary progress is an indicator of how far the song is cached.
        localMediaPlayer.secondaryProgress.observe(
            viewLifecycleOwner,
            {
                progressBar.secondaryProgress = it
            }
        )

        findViews(view)
        val previousButton: AutoRepeatButton = view.findViewById(R.id.button_previous)
        val nextButton: AutoRepeatButton = view.findViewById(R.id.button_next)
        val shuffleButton = view.findViewById<View>(R.id.button_shuffle)
        val ratingLinearLayout = view.findViewById<LinearLayout>(R.id.song_rating)
        if (!useFiveStarRating) ratingLinearLayout.visibility = View.GONE
        hollowStar = Util.getDrawableFromAttribute(view.context, R.attr.star_hollow)
        fullStar = Util.getDrawableFromAttribute(context, R.attr.star_full)

        fiveStar1ImageView.setOnClickListener { setSongRating(1) }
        fiveStar2ImageView.setOnClickListener { setSongRating(2) }
        fiveStar3ImageView.setOnClickListener { setSongRating(3) }
        fiveStar4ImageView.setOnClickListener { setSongRating(4) }
        fiveStar5ImageView.setOnClickListener { setSongRating(5) }

        albumArtImageView.setOnTouchListener { _, me ->
            gestureScanner.onTouchEvent(me)
        }

        albumArtImageView.setOnClickListener {
            toggleFullScreenAlbumArt()
        }

        previousButton.setOnClickListener {
            networkAndStorageChecker.warnIfNetworkOrStorageUnavailable()
            object : SilentBackgroundTask<Void?>(activity) {
                override fun doInBackground(): Void? {
                    mediaPlayerController.previous()
                    return null
                }

                override fun done(result: Void?) {
                    onCurrentChanged()
                    onSliderProgressChanged()
                }
            }.execute()
        }

        previousButton.setOnRepeatListener {
            val incrementTime = Settings.incrementTime
            changeProgress(-incrementTime)
        }

        nextButton.setOnClickListener {
            networkAndStorageChecker.warnIfNetworkOrStorageUnavailable()
            object : SilentBackgroundTask<Boolean?>(activity) {
                override fun doInBackground(): Boolean {
                    mediaPlayerController.next()
                    return true
                }

                override fun done(result: Boolean?) {
                    if (result == true) {
                        onCurrentChanged()
                        onSliderProgressChanged()
                    }
                }
            }.execute()
        }

        nextButton.setOnRepeatListener {
            val incrementTime = Settings.incrementTime
            changeProgress(incrementTime)
        }
        pauseButton.setOnClickListener {
            object : SilentBackgroundTask<Void?>(activity) {
                override fun doInBackground(): Void? {
                    mediaPlayerController.pause()
                    return null
                }

                override fun done(result: Void?) {
                    onCurrentChanged()
                    onSliderProgressChanged()
                }
            }.execute()
        }
        stopButton.setOnClickListener {
            object : SilentBackgroundTask<Void?>(activity) {
                override fun doInBackground(): Void? {
                    mediaPlayerController.reset()
                    return null
                }

                override fun done(result: Void?) {
                    onCurrentChanged()
                    onSliderProgressChanged()
                }
            }.execute()
        }
        startButton.setOnClickListener {
            networkAndStorageChecker.warnIfNetworkOrStorageUnavailable()
            object : SilentBackgroundTask<Void?>(activity) {
                override fun doInBackground(): Void? {
                    start()
                    return null
                }

                override fun done(result: Void?) {
                    onCurrentChanged()
                    onSliderProgressChanged()
                }
            }.execute()
        }
        shuffleButton.setOnClickListener {
            mediaPlayerController.shuffle()
            Util.toast(activity, R.string.download_menu_shuffle_notification)
        }

        repeatButton.setOnClickListener {
            val repeatMode = mediaPlayerController.repeatMode.next()
            mediaPlayerController.repeatMode = repeatMode
            onDownloadListChanged()
            when (repeatMode) {
                RepeatMode.OFF -> Util.toast(
                    context, R.string.download_repeat_off
                )
                RepeatMode.ALL -> Util.toast(
                    context, R.string.download_repeat_all
                )
                RepeatMode.SINGLE -> Util.toast(
                    context, R.string.download_repeat_single
                )
                else -> {
                }
            }
        }

        progressBar.setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                object : SilentBackgroundTask<Void?>(activity) {
                    override fun doInBackground(): Void? {
                        mediaPlayerController.seekTo(progressBar.progress)
                        return null
                    }

                    override fun done(result: Void?) {
                        onSliderProgressChanged()
                    }
                }.execute()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {}
        })

        playlistView.setOnItemClickListener { _, _, position, _ ->
            networkAndStorageChecker.warnIfNetworkOrStorageUnavailable()
            object : SilentBackgroundTask<Void?>(activity) {
                override fun doInBackground(): Void? {
                    mediaPlayerController.play(position)
                    return null
                }

                override fun done(result: Void?) {
                    onCurrentChanged()
                    onSliderProgressChanged()
                }
            }.execute()
        }
        registerForContextMenu(playlistView)

        if (arguments != null && requireArguments().getBoolean(
            Constants.INTENT_EXTRA_NAME_SHUFFLE,
            false
        )
        ) {
            networkAndStorageChecker.warnIfNetworkOrStorageUnavailable()
            mediaPlayerController.isShufflePlayEnabled = true
        }

        visualizerViewLayout.visibility = View.GONE
        VisualizerController.get().observe(
            requireActivity(),
            { visualizerController ->
                if (visualizerController != null) {
                    Timber.d("VisualizerController Observer.onChanged received controller")
                    visualizerView = VisualizerView(context)
                    visualizerViewLayout.addView(
                        visualizerView,
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.MATCH_PARENT
                        )
                    )
                    if (!visualizerView.isActive) {
                        visualizerViewLayout.visibility = View.GONE
                    } else {
                        visualizerViewLayout.visibility = View.VISIBLE
                    }
                    visualizerView.setOnTouchListener { _, _ ->
                        visualizerView.isActive = !visualizerView.isActive
                        mediaPlayerController.showVisualization = visualizerView.isActive
                        true
                    }
                    isVisualizerAvailable = true
                } else {
                    Timber.d("VisualizerController Observer.onChanged has no controller")
                    visualizerViewLayout.visibility = View.GONE
                    isVisualizerAvailable = false
                }
            }
        )

        EqualizerController.get().observe(
            requireActivity(),
            { equalizerController ->
                isEqualizerAvailable = if (equalizerController != null) {
                    Timber.d("EqualizerController Observer.onChanged received controller")
                    true
                } else {
                    Timber.d("EqualizerController Observer.onChanged has no controller")
                    false
                }
            }
        )
        Thread {
            try {
                jukeboxAvailable = mediaPlayerController.isJukeboxAvailable
            } catch (all: Exception) {
                Timber.e(all)
            }
        }.start()
        view.setOnTouchListener { _, event -> gestureScanner.onTouchEvent(event) }
    }

    override fun onResume() {
        super.onResume()
        if (mediaPlayerController.currentPlaying == null) {
            playlistFlipper.displayedChild = 1
        } else {
            // Download list and Album art must be updated when Resumed
            onDownloadListChanged()
            onCurrentChanged()
        }
        val handler = Handler()
        val runnable = Runnable { handler.post { update(cancellationToken) } }
        executorService = Executors.newSingleThreadScheduledExecutor()
        executorService.scheduleWithFixedDelay(runnable, 0L, 250L, TimeUnit.MILLISECONDS)

        if (mediaPlayerController.keepScreenOn) {
            requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        if (::visualizerView.isInitialized) {
            visualizerView.isActive = mediaPlayerController.showVisualization
        }

        requireActivity().invalidateOptionsMenu()
    }

    // Scroll to current playing.
    private fun scrollToCurrent() {
        val adapter = playlistView.adapter
        if (adapter != null) {
            val count = adapter.count
            for (i in 0 until count) {
                if (currentPlaying == playlistView.getItemAtPosition(i)) {
                    playlistView.smoothScrollToPositionFromTop(i, 40)
                    return
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        executorService.shutdown()
        if (::visualizerView.isInitialized) {
            visualizerView.isActive = mediaPlayerController.showVisualization
        }
    }

    override fun onDestroyView() {
        cancellationToken.cancel()
        super.onDestroyView()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.nowplaying, menu)
        super.onCreateOptionsMenu(menu, inflater)
    }

    @Suppress("ComplexMethod", "LongMethod", "NestedBlockDepth")
    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)
        val screenOption = menu.findItem(R.id.menu_item_screen_on_off)
        val jukeboxOption = menu.findItem(R.id.menu_item_jukebox)
        val equalizerMenuItem = menu.findItem(R.id.menu_item_equalizer)
        val visualizerMenuItem = menu.findItem(R.id.menu_item_visualizer)
        val shareMenuItem = menu.findItem(R.id.menu_item_share)
        starMenuItem = menu.findItem(R.id.menu_item_star)
        val bookmarkMenuItem = menu.findItem(R.id.menu_item_bookmark_set)
        val bookmarkRemoveMenuItem = menu.findItem(R.id.menu_item_bookmark_delete)

        if (isOffline()) {
            if (shareMenuItem != null) {
                shareMenuItem.isVisible = false
            }
            starMenuItem.isVisible = false
            if (bookmarkMenuItem != null) {
                bookmarkMenuItem.isVisible = false
            }
            if (bookmarkRemoveMenuItem != null) {
                bookmarkRemoveMenuItem.isVisible = false
            }
        }
        if (equalizerMenuItem != null) {
            equalizerMenuItem.isEnabled = isEqualizerAvailable
            equalizerMenuItem.isVisible = isEqualizerAvailable
        }
        if (visualizerMenuItem != null) {
            visualizerMenuItem.isEnabled = isVisualizerAvailable
            visualizerMenuItem.isVisible = isVisualizerAvailable
        }
        val mediaPlayerController = mediaPlayerController
        val downloadFile = mediaPlayerController.currentPlaying
        if (downloadFile != null) {
            currentSong = downloadFile.song
        }
        if (useFiveStarRating) starMenuItem.isVisible = false
        if (currentSong != null) {
            starMenuItem.icon = if (currentSong!!.starred) fullStar else hollowStar
        } else {
            starMenuItem.icon = hollowStar
        }
        if (mediaPlayerController.keepScreenOn) {
            screenOption?.setTitle(R.string.download_menu_screen_off)
        } else {
            screenOption?.setTitle(R.string.download_menu_screen_on)
        }
        if (jukeboxOption != null) {
            jukeboxOption.isEnabled = jukeboxAvailable
            jukeboxOption.isVisible = jukeboxAvailable
            if (mediaPlayerController.isJukeboxEnabled) {
                jukeboxOption.setTitle(R.string.download_menu_jukebox_off)
            } else {
                jukeboxOption.setTitle(R.string.download_menu_jukebox_on)
            }
        }
    }

    override fun onCreateContextMenu(menu: ContextMenu, view: View, menuInfo: ContextMenuInfo?) {
        super.onCreateContextMenu(menu, view, menuInfo)
        if (view === playlistView) {
            val info = menuInfo as AdapterContextMenuInfo?
            val downloadFile = playlistView.getItemAtPosition(info!!.position) as DownloadFile
            val menuInflater = requireActivity().menuInflater
            menuInflater.inflate(R.menu.nowplaying_context, menu)
            val song: MusicDirectory.Entry?

            song = downloadFile.song

            if (song.parent == null) {
                val menuItem = menu.findItem(R.id.menu_show_album)
                if (menuItem != null) {
                    menuItem.isVisible = false
                }
            }

            if (isOffline() || !Settings.shouldUseId3Tags) {
                menu.findItem(R.id.menu_show_artist)?.isVisible = false
            }

            if (isOffline()) {
                menu.findItem(R.id.menu_lyrics)?.isVisible = false
            }
        }
    }

    override fun onContextItemSelected(menuItem: MenuItem): Boolean {
        val info = menuItem.menuInfo as AdapterContextMenuInfo
        val downloadFile = playlistView.getItemAtPosition(info.position) as DownloadFile
        return menuItemSelected(menuItem.itemId, downloadFile) || super.onContextItemSelected(
            menuItem
        )
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return menuItemSelected(item.itemId, null) || super.onOptionsItemSelected(item)
    }

    @Suppress("ComplexMethod", "LongMethod", "ReturnCount")
    private fun menuItemSelected(menuItemId: Int, song: DownloadFile?): Boolean {
        var entry: MusicDirectory.Entry? = null
        val bundle: Bundle
        if (song != null) {
            entry = song.song
        }

        when (menuItemId) {
            R.id.menu_show_artist -> {
                if (entry == null) {
                    return false
                }
                if (Settings.shouldUseId3Tags) {
                    bundle = Bundle()
                    bundle.putString(Constants.INTENT_EXTRA_NAME_ID, entry.artistId)
                    bundle.putString(Constants.INTENT_EXTRA_NAME_NAME, entry.artist)
                    bundle.putString(Constants.INTENT_EXTRA_NAME_PARENT_ID, entry.artistId)
                    bundle.putBoolean(Constants.INTENT_EXTRA_NAME_ARTIST, true)
                    Navigation.findNavController(requireView())
                        .navigate(R.id.playerToSelectAlbum, bundle)
                }
                return true
            }
            R.id.menu_show_album -> {
                if (entry == null) {
                    return false
                }
                val albumId = if (Settings.shouldUseId3Tags) entry.albumId else entry.parent
                bundle = Bundle()
                bundle.putString(Constants.INTENT_EXTRA_NAME_ID, albumId)
                bundle.putString(Constants.INTENT_EXTRA_NAME_NAME, entry.album)
                bundle.putString(Constants.INTENT_EXTRA_NAME_PARENT_ID, entry.parent)
                bundle.putBoolean(Constants.INTENT_EXTRA_NAME_IS_ALBUM, true)
                Navigation.findNavController(requireView())
                    .navigate(R.id.playerToSelectAlbum, bundle)
                return true
            }
            R.id.menu_lyrics -> {
                if (entry == null) {
                    return false
                }
                bundle = Bundle()
                bundle.putString(Constants.INTENT_EXTRA_NAME_ARTIST, entry.artist)
                bundle.putString(Constants.INTENT_EXTRA_NAME_TITLE, entry.title)
                Navigation.findNavController(requireView()).navigate(R.id.playerToLyrics, bundle)
                return true
            }
            R.id.menu_remove -> {
                mediaPlayerController.removeFromPlaylist(song!!)
                onDownloadListChanged()
                return true
            }
            R.id.menu_item_screen_on_off -> {
                val window = requireActivity().window
                if (mediaPlayerController.keepScreenOn) {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    mediaPlayerController.keepScreenOn = false
                } else {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    mediaPlayerController.keepScreenOn = true
                }
                return true
            }
            R.id.menu_shuffle -> {
                mediaPlayerController.shuffle()
                Util.toast(context, R.string.download_menu_shuffle_notification)
                return true
            }
            R.id.menu_item_equalizer -> {
                Navigation.findNavController(requireView()).navigate(R.id.playerToEqualizer)
                return true
            }
            R.id.menu_item_visualizer -> {
                val active = !visualizerView.isActive
                visualizerView.isActive = active
                if (!visualizerView.isActive) {
                    visualizerViewLayout.visibility = View.GONE
                } else {
                    visualizerViewLayout.visibility = View.VISIBLE
                }
                mediaPlayerController.showVisualization = visualizerView.isActive
                Util.toast(
                    context,
                    if (active) R.string.download_visualizer_on
                    else R.string.download_visualizer_off
                )
                return true
            }
            R.id.menu_item_jukebox -> {
                val jukeboxEnabled = !mediaPlayerController.isJukeboxEnabled
                mediaPlayerController.isJukeboxEnabled = jukeboxEnabled
                Util.toast(
                    context,
                    if (jukeboxEnabled) R.string.download_jukebox_on
                    else R.string.download_jukebox_off,
                    false
                )
                return true
            }
            R.id.menu_item_toggle_list -> {
                toggleFullScreenAlbumArt()
                return true
            }
            R.id.menu_item_clear_playlist -> {
                mediaPlayerController.isShufflePlayEnabled = false
                mediaPlayerController.clear()
                onDownloadListChanged()
                return true
            }
            R.id.menu_item_save_playlist -> {
                if (mediaPlayerController.playlistSize > 0) {
                    showSavePlaylistDialog()
                }
                return true
            }
            R.id.menu_item_star -> {
                if (currentSong == null) {
                    return true
                }
                val isStarred = currentSong!!.starred
                val id = currentSong!!.id
                if (isStarred) {
                    starMenuItem.icon = hollowStar
                    currentSong!!.starred = false
                } else {
                    starMenuItem.icon = fullStar
                    currentSong!!.starred = true
                }
                Thread {
                    val musicService = getMusicService()
                    try {
                        if (isStarred) {
                            musicService.unstar(id, null, null)
                        } else {
                            musicService.star(id, null, null)
                        }
                    } catch (all: Exception) {
                        Timber.e(all)
                    }
                }.start()
                return true
            }
            R.id.menu_item_bookmark_set -> {
                if (currentSong == null) {
                    return true
                }
                val songId = currentSong!!.id
                val playerPosition = mediaPlayerController.playerPosition
                currentSong!!.bookmarkPosition = playerPosition
                val bookmarkTime = Util.formatTotalDuration(playerPosition.toLong(), true)
                Thread {
                    val musicService = getMusicService()
                    try {
                        musicService.createBookmark(songId, playerPosition)
                    } catch (all: Exception) {
                        Timber.e(all)
                    }
                }.start()
                val msg = resources.getString(
                    R.string.download_bookmark_set_at_position,
                    bookmarkTime
                )
                Util.toast(context, msg)
                return true
            }
            R.id.menu_item_bookmark_delete -> {
                if (currentSong == null) {
                    return true
                }
                val bookmarkSongId = currentSong!!.id
                currentSong!!.bookmarkPosition = 0
                Thread {
                    val musicService = getMusicService()
                    try {
                        musicService.deleteBookmark(bookmarkSongId)
                    } catch (all: Exception) {
                        Timber.e(all)
                    }
                }.start()
                Util.toast(context, R.string.download_bookmark_removed)
                return true
            }
            R.id.menu_item_share -> {
                val mediaPlayerController = mediaPlayerController
                val entries: MutableList<MusicDirectory.Entry?> = ArrayList()
                val downloadServiceSongs = mediaPlayerController.playList
                for (downloadFile in downloadServiceSongs) {
                    val playlistEntry = downloadFile.song
                    entries.add(playlistEntry)
                }
                shareHandler.createShare(this, entries, null, cancellationToken)
                return true
            }
            else -> return false
        }
    }

    private fun update(cancel: CancellationToken?) {
        if (cancel!!.isCancellationRequested) return
        val mediaPlayerController = mediaPlayerController
        if (currentRevision != mediaPlayerController.playListUpdateRevision) {
            onDownloadListChanged()
        }
        if (currentPlaying != mediaPlayerController.currentPlaying) {
            onCurrentChanged()
        }
        onSliderProgressChanged()
        requireActivity().invalidateOptionsMenu()
    }

    private fun savePlaylistInBackground(playlistName: String) {
        Util.toast(context, resources.getString(R.string.download_playlist_saving, playlistName))
        mediaPlayerController.suggestedPlaylistName = playlistName
        object : SilentBackgroundTask<Void?>(activity) {
            @Throws(Throwable::class)
            override fun doInBackground(): Void? {
                val entries: MutableList<MusicDirectory.Entry> = LinkedList()
                for (downloadFile in mediaPlayerController.playList) {
                    entries.add(downloadFile.song)
                }
                val musicService = getMusicService()
                musicService.createPlaylist(null, playlistName, entries)
                return null
            }

            override fun done(result: Void?) {
                Util.toast(context, R.string.download_playlist_done)
            }

            override fun error(error: Throwable) {
                Timber.e(error, "Exception has occurred in savePlaylistInBackground")
                val msg = String.format(
                    Locale.ROOT,
                    "%s %s",
                    resources.getString(R.string.download_playlist_error),
                    getErrorMessage(error)
                )
                Util.toast(context, msg)
            }
        }.execute()
    }

    private fun toggleFullScreenAlbumArt() {
        if (playlistFlipper.displayedChild == 1) {
            playlistFlipper.inAnimation =
                AnimationUtils.loadAnimation(context, R.anim.push_down_in)
            playlistFlipper.outAnimation =
                AnimationUtils.loadAnimation(context, R.anim.push_down_out)
            playlistFlipper.displayedChild = 0
        } else {
            playlistFlipper.inAnimation =
                AnimationUtils.loadAnimation(context, R.anim.push_up_in)
            playlistFlipper.outAnimation =
                AnimationUtils.loadAnimation(context, R.anim.push_up_out)
            playlistFlipper.displayedChild = 1
        }
        scrollToCurrent()
    }

    private fun start() {
        val service = mediaPlayerController
        val state = service.playerState
        if (state === PlayerState.PAUSED ||
            state === PlayerState.COMPLETED || state === PlayerState.STOPPED
        ) {
            service.start()
        } else if (state === PlayerState.IDLE) {
            networkAndStorageChecker.warnIfNetworkOrStorageUnavailable()
            val current = mediaPlayerController.currentPlayingNumberOnPlaylist
            if (current == -1) {
                service.play(0)
            } else {
                service.play(current)
            }
        }
    }

    private fun onDownloadListChanged() {
        val mediaPlayerController = mediaPlayerController
        val list = mediaPlayerController.playList
        emptyTextView.setText(R.string.download_empty)
        val adapter = SongListAdapter(context, list)
        playlistView.adapter = adapter
        playlistView.setDragSortListener(object : DragSortListener {
            override fun drop(from: Int, to: Int) {
                if (from != to) {
                    val item = adapter.getItem(from)
                    adapter.remove(item)
                    adapter.notifyDataSetChanged()
                    adapter.insert(item, to)
                    adapter.notifyDataSetChanged()
                }
            }

            override fun drag(from: Int, to: Int) {}
            override fun remove(which: Int) {

                val item = adapter.getItem(which) ?: return

                val currentPlaying = mediaPlayerController.currentPlaying
                if (currentPlaying == item) {
                    mediaPlayerController.next()
                }
                adapter.remove(item)
                adapter.notifyDataSetChanged()
                val songRemoved = String.format(
                    resources.getString(R.string.download_song_removed),
                    item.song.title
                )
                Util.toast(context, songRemoved)
                onDownloadListChanged()
                onCurrentChanged()
            }
        })
        emptyTextView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        currentRevision = mediaPlayerController.playListUpdateRevision
        when (mediaPlayerController.repeatMode) {
            RepeatMode.OFF -> repeatButton.setImageDrawable(
                Util.getDrawableFromAttribute(
                    context, R.attr.media_repeat_off
                )
            )
            RepeatMode.ALL -> repeatButton.setImageDrawable(
                Util.getDrawableFromAttribute(
                    context, R.attr.media_repeat_all
                )
            )
            RepeatMode.SINGLE -> repeatButton.setImageDrawable(
                Util.getDrawableFromAttribute(
                    context, R.attr.media_repeat_single
                )
            )
            else -> {
            }
        }
    }

    private fun onCurrentChanged() {
        currentPlaying = mediaPlayerController.currentPlaying
        scrollToCurrent()
        val totalDuration = mediaPlayerController.playListDuration
        val totalSongs = mediaPlayerController.playlistSize.toLong()
        val currentSongIndex = mediaPlayerController.currentPlayingNumberOnPlaylist + 1
        val duration = Util.formatTotalDuration(totalDuration)
        val trackFormat =
            String.format(Locale.getDefault(), "%d / %d", currentSongIndex, totalSongs)
        if (currentPlaying != null) {
            currentSong = currentPlaying!!.song
            songTitleTextView.text = currentSong!!.title
            albumTextView.text = currentSong!!.album
            artistTextView.text = currentSong!!.artist
            downloadTrackTextView.text = trackFormat
            downloadTotalDurationTextView.text = duration
            imageLoaderProvider.getImageLoader()
                .loadImage(albumArtImageView, currentSong, true, 0)
            displaySongRating()
        } else {
            currentSong = null
            songTitleTextView.text = null
            albumTextView.text = null
            artistTextView.text = null
            downloadTrackTextView.text = null
            downloadTotalDurationTextView.text = null
            imageLoaderProvider.getImageLoader()
                .loadImage(albumArtImageView, null, true, 0)
        }
    }

    private fun onSliderProgressChanged() {
        if (onProgressChangedTask != null) {
            return
        }
        onProgressChangedTask = object : SilentBackgroundTask<Void?>(activity) {
            var isJukeboxEnabled = false
            var millisPlayed = 0
            var duration: Int? = null
            var playerState: PlayerState? = null
            override fun doInBackground(): Void? {
                isJukeboxEnabled = mediaPlayerController.isJukeboxEnabled
                millisPlayed = max(0, mediaPlayerController.playerPosition)
                duration = mediaPlayerController.playerDuration
                playerState = mediaPlayerController.playerState
                return null
            }

            @Suppress("LongMethod")
            override fun done(result: Void?) {
                if (cancellationToken.isCancellationRequested) return
                if (currentPlaying != null) {
                    val millisTotal = if (duration == null) 0 else duration!!
                    positionTextView.text = Util.formatTotalDuration(millisPlayed.toLong(), true)
                    durationTextView.text = Util.formatTotalDuration(millisTotal.toLong(), true)
                    progressBar.max =
                        if (millisTotal == 0) 100 else millisTotal // Work-around for apparent bug.
                    progressBar.progress = millisPlayed
                    progressBar.isEnabled = currentPlaying!!.isWorkDone || isJukeboxEnabled
                } else {
                    positionTextView.setText(R.string.util_zero_time)
                    durationTextView.setText(R.string.util_no_time)
                    progressBar.progress = 0
                    progressBar.max = 0
                    progressBar.isEnabled = false
                }

                when (playerState) {
                    PlayerState.DOWNLOADING -> {
                        val progress =
                            if (currentPlaying != null) currentPlaying!!.progress.value!! else 0
                        val downloadStatus = resources.getString(
                            R.string.download_playerstate_downloading,
                            Util.formatPercentage(progress)
                        )
                        setTitle(this@PlayerFragment, downloadStatus)
                    }
                    PlayerState.PREPARING -> setTitle(
                        this@PlayerFragment,
                        R.string.download_playerstate_buffering
                    )
                    PlayerState.STARTED -> {
                        if (mediaPlayerController.isShufflePlayEnabled) {
                            setTitle(
                                this@PlayerFragment,
                                R.string.download_playerstate_playing_shuffle
                            )
                        } else {
                            setTitle(this@PlayerFragment, R.string.common_appname)
                        }
                    }
                    PlayerState.IDLE,
                    PlayerState.PREPARED,
                    PlayerState.STOPPED,
                    PlayerState.PAUSED,
                    PlayerState.COMPLETED -> {
                    }
                    else -> setTitle(this@PlayerFragment, R.string.common_appname)
                }

                when (playerState) {
                    PlayerState.STARTED -> {
                        pauseButton.visibility = View.VISIBLE
                        stopButton.visibility = View.GONE
                        startButton.visibility = View.GONE
                    }
                    PlayerState.DOWNLOADING, PlayerState.PREPARING -> {
                        pauseButton.visibility = View.GONE
                        stopButton.visibility = View.VISIBLE
                        startButton.visibility = View.GONE
                    }
                    else -> {
                        pauseButton.visibility = View.GONE
                        stopButton.visibility = View.GONE
                        startButton.visibility = View.VISIBLE
                    }
                }

                // TODO: It would be a lot nicer if MediaPlayerController would send an event
                //  when this is necessary instead of updating every time
                displaySongRating()
                onProgressChangedTask = null
            }
        }
        onProgressChangedTask!!.execute()
    }

    private fun changeProgress(ms: Int) {
        object : SilentBackgroundTask<Void?>(activity) {
            var msPlayed = 0
            var duration: Int? = null
            var seekTo = 0
            override fun doInBackground(): Void? {
                msPlayed = max(0, mediaPlayerController.playerPosition)
                duration = mediaPlayerController.playerDuration
                val msTotal = duration!!
                seekTo = (msPlayed + ms).coerceAtMost(msTotal)
                mediaPlayerController.seekTo(seekTo)
                return null
            }

            override fun done(result: Void?) {
                progressBar.progress = seekTo
            }
        }.execute()
    }

    override fun onDown(me: MotionEvent): Boolean {
        return false
    }

    @Suppress("ReturnCount")
    override fun onFling(
        e1: MotionEvent,
        e2: MotionEvent,
        velocityX: Float,
        velocityY: Float
    ): Boolean {
        val e1X = e1.x
        val e2X = e2.x
        val e1Y = e1.y
        val e2Y = e2.y
        val absX = abs(velocityX)
        val absY = abs(velocityY)

        // Right to Left swipe
        if (e1X - e2X > swipeDistance && absX > swipeVelocity) {
            networkAndStorageChecker.warnIfNetworkOrStorageUnavailable()
            mediaPlayerController.next()
            onCurrentChanged()
            onSliderProgressChanged()
            return true
        }

        // Left to Right swipe
        if (e2X - e1X > swipeDistance && absX > swipeVelocity) {
            networkAndStorageChecker.warnIfNetworkOrStorageUnavailable()
            mediaPlayerController.previous()
            onCurrentChanged()
            onSliderProgressChanged()
            return true
        }

        // Top to Bottom swipe
        if (e2Y - e1Y > swipeDistance && absY > swipeVelocity) {
            networkAndStorageChecker.warnIfNetworkOrStorageUnavailable()
            mediaPlayerController.seekTo(mediaPlayerController.playerPosition + 30000)
            onSliderProgressChanged()
            return true
        }

        // Bottom to Top swipe
        if (e1Y - e2Y > swipeDistance && absY > swipeVelocity) {
            networkAndStorageChecker.warnIfNetworkOrStorageUnavailable()
            mediaPlayerController.seekTo(mediaPlayerController.playerPosition - 8000)
            onSliderProgressChanged()
            return true
        }
        return false
    }

    override fun onLongPress(e: MotionEvent) {}
    override fun onScroll(
        e1: MotionEvent,
        e2: MotionEvent,
        distanceX: Float,
        distanceY: Float
    ): Boolean {
        return false
    }

    override fun onShowPress(e: MotionEvent) {}
    override fun onSingleTapUp(e: MotionEvent): Boolean {
        return false
    }

    private fun displaySongRating() {
        var rating = 0

        if (currentSong?.userRating != null) {
            rating = currentSong!!.userRating!!
        }

        fiveStar1ImageView.setImageDrawable(if (rating > 0) fullStar else hollowStar)
        fiveStar2ImageView.setImageDrawable(if (rating > 1) fullStar else hollowStar)
        fiveStar3ImageView.setImageDrawable(if (rating > 2) fullStar else hollowStar)
        fiveStar4ImageView.setImageDrawable(if (rating > 3) fullStar else hollowStar)
        fiveStar5ImageView.setImageDrawable(if (rating > 4) fullStar else hollowStar)
    }

    private fun setSongRating(rating: Int) {
        if (currentSong == null) return
        displaySongRating()
        mediaPlayerController.setSongRating(rating)
    }

    private fun showSavePlaylistDialog() {
        val layout = LayoutInflater.from(this.context).inflate(R.layout.save_playlist, null)

        playlistNameView = layout.findViewById(R.id.save_playlist_name)

        val builder: AlertDialog.Builder = AlertDialog.Builder(context)
        builder.setTitle(R.string.download_playlist_title)
        builder.setMessage(R.string.download_playlist_name)

        builder.setPositiveButton(R.string.common_save) { _, _ ->
            savePlaylistInBackground(
                playlistNameView.text.toString()
            )
        }

        builder.setNegativeButton(R.string.common_cancel) { dialog, _ -> dialog.cancel() }
        builder.setView(layout)
        builder.setCancelable(true)
        val dialog = builder.create()
        val playlistName = mediaPlayerController.suggestedPlaylistName
        if (playlistName != null) {
            playlistNameView.setText(playlistName)
        } else {
            val dateFormat: DateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            playlistNameView.setText(dateFormat.format(Date()))
        }
        dialog.show()
    }

    companion object {
        private const val PERCENTAGE_OF_SCREEN_FOR_SWIPE = 5
    }
}
