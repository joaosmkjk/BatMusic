package com.batmusic.app

import android.app.PendingIntent
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.core.content.ContextCompat
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.batmusic.app.audio.BatAudioSettings
import com.batmusic.app.audio.AudioEngine
import com.batmusic.app.audio.BatAudioSettingsMigration
import com.batmusic.app.data.PlaybackQueue
import com.batmusic.app.data.PlaybackQueueRestorer
import com.batmusic.app.data.PlaybackQueueStore
import com.batmusic.app.data.PlaylistRepository
import com.batmusic.app.data.CatalogStatus
import com.batmusic.app.data.PlaybackQueuePolicies
import com.batmusic.app.data.QueueMutationOrigin
import com.batmusic.app.data.QueueMediaDescriptor

internal object PlaybackEffects {
    const val COMMAND_SET = "com.batmusic.app.SET_AUDIO_EFFECTS"
    const val EXTRA_EQ_ENABLED = "eq_enabled"
    const val EXTRA_EQ_GAINS = "eq_gains_tenth_db"
    const val EXTRA_BASS_ENABLED = "bass_enabled"
    const val EXTRA_BASS_STRENGTH = "bass_strength"
    const val EXTRA_BAT_ENABLED = "bat_audio_enabled"
    const val EXTRA_BAT_BOOST = "bat_boost"
    const val EXTRA_PREAMP_DB = "preamp_db_float"
    const val EXTRA_COMPRESSOR_ENABLED = "compressor_enabled"
    const val EXTRA_MULTIBAND_ENABLED = "multiband_enabled"
    const val EXTRA_COMPRESSOR_THRESHOLD = "compressor_threshold_db"
    const val EXTRA_COMPRESSOR_RATIO = "compressor_ratio"
    const val EXTRA_COMPRESSOR_ATTACK = "compressor_attack_ms"
    const val EXTRA_COMPRESSOR_RELEASE = "compressor_release_ms"
    const val EXTRA_COMPRESSOR_MAKEUP = "compressor_makeup_db"
    const val EXTRA_VISUALIZER_MODE = "visualizer_mode"
    const val EXTRA_AUDIO_AB_ORIGINAL = "audio_ab_original"

    const val SESSION_EQ_SUPPORTED = "audio_eq_supported"
    const val SESSION_BASS_SUPPORTED = "audio_bass_supported"
    const val SESSION_EFFECT_STATUS = "audio_effect_status"
    const val SESSION_OUTPUT_PEAK = "audio_output_peak"
    const val SESSION_LIMITER_ACTIVE = "audio_limiter_active"
    const val SESSION_CLIP_BLOCKS = "audio_clip_blocks"
    const val SESSION_LIMITER_GR_DB = "audio_limiter_gain_reduction_db"
    const val SESSION_COMPRESSOR_GR_DB = "audio_compressor_gain_reduction_db"
    const val SESSION_PRE_LIMITER_PEAK = "audio_pre_limiter_peak"
    const val SESSION_PRE_LIMITER_RMS_DB = "audio_pre_limiter_rms_db"
    const val SESSION_OUTPUT_RMS_DB = "audio_output_rms_db"
    const val SESSION_MOMENTARY_LUFS = "audio_momentary_lufs"
    const val SESSION_INTEGRATED_LUFS = "audio_integrated_lufs"
    const val EXTRA_LIMITER_RELEASE = "limiter_release_ms"
    const val SESSION_VISUALIZER_MODE = "audio_visualizer_mode"
    const val SESSION_WAVEFORM = "audio_visualizer_waveform"
    const val SESSION_SPECTRUM = "audio_visualizer_spectrum"

    val command = SessionCommand(COMMAND_SET, Bundle.EMPTY)
    val bandFrequenciesHz = intArrayOf(31, 62, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000)
}

internal object PlaybackQueueCommands {
    const val APPLY_TRANSACTION = "com.batmusic.app.APPLY_QUEUE_TRANSACTION"
    const val EXTRA_CONTEXT_TYPE = "queue_context_type"
    const val EXTRA_CONTEXT_ID = "queue_context_id"
    const val EXTRA_MEDIA_IDS = "queue_media_ids"
    const val EXTRA_URIS = "queue_uris"
    const val EXTRA_TITLES = "queue_titles"
    const val EXTRA_ARTISTS = "queue_artists"
    const val EXTRA_ALBUMS = "queue_albums"
    const val EXTRA_ARTWORKS = "queue_artworks"
    const val EXTRA_SHUFFLE_ORDER = "queue_shuffle_order"
    const val EXTRA_SHUFFLE_ENABLED = "queue_shuffle_enabled"
    const val EXTRA_REPEAT_MODE = "queue_repeat_mode"
    const val EXTRA_CURRENT_INDEX = "queue_current_index"
    const val EXTRA_POSITION_MS = "queue_position_ms"
    const val EXTRA_PLAY_WHEN_READY = "queue_play_when_ready"
    val command = SessionCommand(APPLY_TRANSACTION, Bundle.EMPTY)
}

@UnstableApi
class PlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private var mediaSession: MediaSession? = null
    private val audioEngine = AudioEngine()
    private lateinit var queueStore: PlaybackQueueStore
    private val playbackQueue = PlaybackQueue()
    private var activeQueueContextType = "LIBRARY"
    private var activeQueueContextId = "all"
    private var expectedPlaylistIds: List<String>? = null
    private var queueTransactionApplying = false
    private var queueRestorationApplying = false
    private var snapshotRestoreDeferred = false
    private var queueStateDiverged = false
    private var untrustedTimelineMutation = false
    private var pendingMutationOrigin = QueueMutationOrigin.EXTERNAL_CONTROLLER
    private val untrustedMutationFallback = Runnable {
        if (untrustedTimelineMutation && !queueTransactionApplying) {
            val context = PlaybackQueuePolicies.contextForMutation(
                pendingMutationOrigin, activeQueueContextType, activeQueueContextId
            )
            activeQueueContextType = context.first
            activeQueueContextId = context.second
            untrustedTimelineMutation = false
            persistQueueSnapshot()
        }
    }
    private var equalizerEnabled = false
    private var bassBoostEnabled = false
    private var bassStrength = 0
    private var preampDb = 0f
    private var compressorEnabled = false
    private var multibandEnabled = false
    private var limiterReleaseMs = 120f
    private var compressorThresholdDb = -12f
    private var compressorRatio = 3f
    private var compressorAttackMs = 6f
    private var compressorReleaseMs = 120f
    private var compressorMakeupDb = 0f
    private var batAudioEnabled = false
    private var batBoost = 100
    private var audioABOriginal = false
    private var bandGainsTenthDb = IntArray(PlaybackEffects.bandFrequenciesHz.size)
    private val meterHandler = Handler(Looper.getMainLooper())
    private val meterPublisher = object : Runnable {
        override fun run() {
            if (player.isPlaying) {
                publishEffectStatus()
                meterHandler.postDelayed(this, 250L)
            }
        }
    }

    private fun startMeterPublisher() {
        meterHandler.removeCallbacks(meterPublisher)
        if (player.isPlaying) meterHandler.post(meterPublisher)
    }
    private val queueCheckpoint = object : Runnable {
        override fun run() {
            if (PlaybackQueuePolicies.shouldPersistPosition(
                    player.isPlaying, player.mediaItemCount > 0, queueTransactionApplying,
                    queueRestorationApplying || queueStateDiverged, untrustedTimelineMutation
                )) {
                persistQueuePosition()
            }
            meterHandler.postDelayed(this, QUEUE_POSITION_CHECKPOINT_MS)
        }
    }
    private val queueListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            // Integrated loudness belongs to one track, never to the whole playback session.
            audioEngine.processor.resetTrackLoudness()
        }

        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_IS_PLAYING_CHANGED) ||
                events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)) {
                if (player.isPlaying) startMeterPublisher() else {
                    meterHandler.removeCallbacks(meterPublisher)
                    publishEffectStatus()
                }
            }
            val queueStateChanged = events.contains(Player.EVENT_TIMELINE_CHANGED) ||
                events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) ||
                events.contains(Player.EVENT_POSITION_DISCONTINUITY) ||
                events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) ||
                events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED) ||
                events.contains(Player.EVENT_IS_PLAYING_CHANGED) ||
                events.contains(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED) ||
                events.contains(Player.EVENT_REPEAT_MODE_CHANGED)
            if (queueStateChanged && !queueTransactionApplying && !queueRestorationApplying &&
                !untrustedTimelineMutation && !queueStateDiverged) persistQueueSnapshot()
        }
    }

    private val effectCommandCallback = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                .buildUpon()
                .add(PlaybackEffects.command)
                .add(PlaybackQueueCommands.command)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            if (controller.packageName != packageName) {
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
            if (customCommand.customAction == PlaybackQueueCommands.APPLY_TRANSACTION) {
                return applyQueueTransaction(args)
            }
            if (customCommand.customAction != PlaybackEffects.COMMAND_SET) {
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }

            equalizerEnabled = args.getBoolean(PlaybackEffects.EXTRA_EQ_ENABLED, equalizerEnabled)
            bassBoostEnabled = args.getBoolean(PlaybackEffects.EXTRA_BASS_ENABLED, bassBoostEnabled)
            bassStrength = args.getInt(PlaybackEffects.EXTRA_BASS_STRENGTH, bassStrength).coerceIn(0, 1000)
            preampDb = args.getFloat(PlaybackEffects.EXTRA_PREAMP_DB, preampDb).coerceIn(0f, 24f)
            compressorEnabled = args.getBoolean(PlaybackEffects.EXTRA_COMPRESSOR_ENABLED, compressorEnabled)
            multibandEnabled = args.getBoolean(PlaybackEffects.EXTRA_MULTIBAND_ENABLED, multibandEnabled)
            compressorThresholdDb = args.getFloat(PlaybackEffects.EXTRA_COMPRESSOR_THRESHOLD, compressorThresholdDb).coerceIn(-36f, 0f)
            compressorRatio = args.getFloat(PlaybackEffects.EXTRA_COMPRESSOR_RATIO, compressorRatio).coerceIn(1f, 12f)
            compressorAttackMs = args.getFloat(PlaybackEffects.EXTRA_COMPRESSOR_ATTACK, compressorAttackMs).coerceIn(1f, 100f)
            compressorReleaseMs = args.getFloat(PlaybackEffects.EXTRA_COMPRESSOR_RELEASE, compressorReleaseMs).coerceIn(10f, 1_000f)
            compressorMakeupDb = args.getFloat(PlaybackEffects.EXTRA_COMPRESSOR_MAKEUP, compressorMakeupDb).coerceIn(-12f, 12f)
            limiterReleaseMs = args.getFloat(PlaybackEffects.EXTRA_LIMITER_RELEASE, limiterReleaseMs).coerceIn(20f, 1_000f)
            audioEngine.setVisualizerMode(args.getString(PlaybackEffects.EXTRA_VISUALIZER_MODE, audioEngine.visualizerMode) ?: "OFF")
            audioABOriginal = args.getBoolean(PlaybackEffects.EXTRA_AUDIO_AB_ORIGINAL, audioABOriginal)
            batAudioEnabled = args.getBoolean(PlaybackEffects.EXTRA_BAT_ENABLED, batAudioEnabled)
            batBoost = args.getInt(PlaybackEffects.EXTRA_BAT_BOOST, batBoost).coerceIn(100, 200)
            args.getIntArray(PlaybackEffects.EXTRA_EQ_GAINS)?.let { incoming ->
                bandGainsTenthDb = IntArray(PlaybackEffects.bandFrequenciesHz.size) { index ->
                    incoming.getOrElse(index) { 0 }.coerceIn(-120, 120)
                }
            }
            saveEffectSettings()
            applyEffects()
            publishEffectStatus()
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onSetMediaItems(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<androidx.media3.common.MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            // Raw setMediaItems has no app queue context attached. Treat it as untrusted;
            // the complete BatMusic replacement path uses APPLY_TRANSACTION instead.
            untrustedTimelineMutation = true
            pendingMutationOrigin = if (controller.packageName == packageName) {
                QueueMutationOrigin.INTERNAL_UNSCOPED
            } else QueueMutationOrigin.EXTERNAL_CONTROLLER
            meterHandler.removeCallbacks(untrustedMutationFallback)
            meterHandler.postDelayed(untrustedMutationFallback, UNTRUSTED_MUTATION_TIMEOUT_MS)
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs)
            )
        }

        override fun onPlayerInteractionFinished(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            playerCommands: Player.Commands
        ) {
            if (playerCommands.contains(Player.COMMAND_CHANGE_MEDIA_ITEMS) && !queueTransactionApplying) {
                val origin = if (controller.packageName == packageName) {
                    QueueMutationOrigin.INTERNAL_UNSCOPED
                } else QueueMutationOrigin.EXTERNAL_CONTROLLER
                val context = PlaybackQueuePolicies.contextForMutation(origin, activeQueueContextType, activeQueueContextId)
                activeQueueContextType = context.first
                activeQueueContextId = context.second
                untrustedTimelineMutation = false
                meterHandler.removeCallbacks(untrustedMutationFallback)
                persistQueueSnapshot()
            }
        }

        override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
            if (!untrustedTimelineMutation) return
            meterHandler.removeCallbacks(untrustedMutationFallback)
            val context = PlaybackQueuePolicies.contextForMutation(
                pendingMutationOrigin, activeQueueContextType, activeQueueContextId
            )
            activeQueueContextType = context.first
            activeQueueContextId = context.second
            untrustedTimelineMutation = false
            persistQueueSnapshot()
        }
    }

    override fun onCreate() {
        super.onCreate()
        queueStore = PlaybackQueueStore(this)
        loadEffectSettings()
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: android.content.Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf<AudioProcessor>(audioEngine.processor))
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
        }
        player = ExoPlayer.Builder(this)
            .setRenderersFactory(renderersFactory)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.addListener(queueListener)
        restoreQueueSnapshot()

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        mediaSession = MediaSession.Builder(this, player)
            .setCallback(effectCommandCallback)
            .setSessionActivity(sessionActivity)
            .setSessionExtras(effectStatusExtras())
            .build()

        applyEffects()
        publishEffectStatus()
        startMeterPublisher()
        meterHandler.postDelayed(queueCheckpoint, 1_000L)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    private fun applyEffects() {
        audioEngine.configure(BatAudioSettings(
            enabled = batAudioEnabled && !audioABOriginal,
            // A/B bypasses tone processing only; peak protection stays active in both paths.
            limiterEnabled = true,
            batBoost = batBoost,
            equalizerEnabled = equalizerEnabled,
            bandGainsDb = bandGainsTenthDb.map { it / 10f },
            bassBoost = if (bassBoostEnabled) bassStrength / 10 else 0,
            preGainDb = preampDb,
            compressorEnabled = compressorEnabled,
            compressorThresholdDb = compressorThresholdDb,
            compressorRatio = compressorRatio,
            compressorAttackMs = compressorAttackMs,
            compressorReleaseMs = compressorReleaseMs,
            compressorMakeupDb = compressorMakeupDb,
            limiterReleaseMs = limiterReleaseMs,
            multibandEnabled = multibandEnabled
        ))
    }

    private fun publishEffectStatus() {
        mediaSession?.setSessionExtras(effectStatusExtras())
    }

    private fun effectStatusExtras() = Bundle().apply {
        putBoolean(PlaybackEffects.SESSION_EQ_SUPPORTED, audioEngine.processor.pcmProcessingSupported)
        putBoolean(PlaybackEffects.SESSION_BASS_SUPPORTED, audioEngine.processor.pcmProcessingSupported)
        putString(PlaybackEffects.SESSION_EFFECT_STATUS, currentEffectStatus())
        val meter = audioEngine.meter()
        putFloat(PlaybackEffects.SESSION_OUTPUT_PEAK, meter.peak)
        putBoolean(PlaybackEffects.SESSION_LIMITER_ACTIVE, meter.limiterActive)
        putLong(PlaybackEffects.SESSION_CLIP_BLOCKS, meter.limitedBlocks)
        putFloat(PlaybackEffects.SESSION_LIMITER_GR_DB, meter.limiterGainReductionDb)
        putFloat(PlaybackEffects.SESSION_COMPRESSOR_GR_DB, meter.compressorGainReductionDb)
        putFloat(PlaybackEffects.SESSION_PRE_LIMITER_PEAK, meter.preLimiterPeak)
        putFloat(PlaybackEffects.SESSION_PRE_LIMITER_RMS_DB, meter.preLimiterRmsDb)
        putFloat(PlaybackEffects.SESSION_OUTPUT_RMS_DB, meter.outputRmsDb)
        putFloat(PlaybackEffects.SESSION_MOMENTARY_LUFS, meter.momentaryLufs)
        putFloat(PlaybackEffects.SESSION_INTEGRATED_LUFS, meter.integratedLufs)
        putString(PlaybackEffects.SESSION_VISUALIZER_MODE, audioEngine.visualizerMode)
        if (player.isPlaying && audioEngine.visualizerMode != "OFF") {
            val visualizer = audioEngine.visualizerFrame()
            putFloatArray(PlaybackEffects.SESSION_WAVEFORM, visualizer.waveform)
            putFloatArray(PlaybackEffects.SESSION_SPECTRUM, visualizer.spectrum)
        }
    }

    private fun currentEffectStatus(): String = when {
        audioABOriginal -> "A/B • áudio original"
        !batAudioEnabled -> "Bat Audio pronto • Flat"
        !audioEngine.processor.pcmProcessingSupported -> "Efeitos configurados • aguardando PCM compatível."
        else -> "PCM ativo • compressor ${if (compressorEnabled) "ligado" else "desligado"} • limiter de pico ligado."
    }

    private fun loadEffectSettings() {
        BatAudioSettingsMigration.migrate(this)
        val preferences = getSharedPreferences("batmusic", MODE_PRIVATE)
        equalizerEnabled = preferences.getBoolean("eq_enabled", false)
        bassBoostEnabled = preferences.getBoolean("bass_enabled", false)
        batBoost = preferences.getInt("bat_boost", 100).let { if (it < 100) 100 + it else it }.coerceIn(100, 200)
        bassStrength = preferences.getInt("bass_strength", 0).coerceIn(0, 1000)
        preampDb = preferences.getFloat("preamp_db_float", preferences.getInt("preamp_db", 0).toFloat()).coerceIn(0f, 24f)
        compressorEnabled = preferences.getBoolean("compressor_enabled", false)
        multibandEnabled = preferences.getBoolean("multiband_enabled", false)
        limiterReleaseMs = preferences.getFloat("limiter_release_ms", 120f).coerceIn(20f, 1_000f)
        compressorThresholdDb = preferences.getFloat("compressor_threshold", -12f).coerceIn(-36f, 0f)
        compressorRatio = preferences.getFloat("compressor_ratio", 3f).coerceIn(1f, 12f)
        compressorAttackMs = preferences.getFloat("compressor_attack", 6f).coerceIn(1f, 100f)
        compressorReleaseMs = preferences.getFloat("compressor_release", 120f).coerceIn(10f, 1_000f)
        compressorMakeupDb = preferences.getFloat("compressor_makeup", 0f).coerceIn(-12f, 12f)
        batAudioEnabled = preferences.getBoolean(
            "bat_audio_enabled", equalizerEnabled || bassBoostEnabled || preampDb != 0f
        )
        bandGainsTenthDb = preferences.getString("eq_gains", null)
            ?.split(',')
            ?.mapNotNull { it.toIntOrNull() }
            ?.takeIf { it.size == PlaybackEffects.bandFrequenciesHz.size }
            ?.map { it.coerceIn(-120, 120) }
            ?.toIntArray()
            ?: IntArray(PlaybackEffects.bandFrequenciesHz.size)
    }

    private fun saveEffectSettings() {
        getSharedPreferences("batmusic", MODE_PRIVATE).edit()
            .putBoolean("eq_enabled", equalizerEnabled)
            .putBoolean("bass_enabled", bassBoostEnabled)
            .putBoolean("bat_audio_enabled", batAudioEnabled)
            .putInt("bat_boost", batBoost)
            .putInt("bass_strength", bassStrength)
            .putFloat("preamp_db_float", preampDb)
            .putBoolean("compressor_enabled", compressorEnabled)
            .putBoolean("multiband_enabled", multibandEnabled)
            .putFloat("limiter_release_ms", limiterReleaseMs)
            .putFloat("compressor_threshold", compressorThresholdDb)
            .putFloat("compressor_ratio", compressorRatio)
            .putFloat("compressor_attack", compressorAttackMs)
            .putFloat("compressor_release", compressorReleaseMs)
            .putFloat("compressor_makeup", compressorMakeupDb)
            .putString("eq_gains", bandGainsTenthDb.joinToString(","))
            .apply()
    }

    private fun applyQueueTransaction(args: Bundle): ListenableFuture<SessionResult> {
        val ids = args.getStringArrayList(PlaybackQueueCommands.EXTRA_MEDIA_IDS)
        val uris = args.getStringArrayList(PlaybackQueueCommands.EXTRA_URIS)
        val titles = args.getStringArrayList(PlaybackQueueCommands.EXTRA_TITLES)
        val artists = args.getStringArrayList(PlaybackQueueCommands.EXTRA_ARTISTS)
        val albums = args.getStringArrayList(PlaybackQueueCommands.EXTRA_ALBUMS)
        val artworks = args.getStringArrayList(PlaybackQueueCommands.EXTRA_ARTWORKS)
        val order = args.getIntArray(PlaybackQueueCommands.EXTRA_SHUFFLE_ORDER)
        if (ids == null || uris == null || titles == null || artists == null || albums == null || artworks == null || order == null) {
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))
        }
        if (ids.isEmpty() || ids.size != uris.size || ids.size != titles.size || ids.size != artists.size ||
            ids.size != albums.size || ids.size != artworks.size || ids.any(String::isBlank) || uris.any(String::isBlank) ||
            order.size != ids.size || order.toSet().size != order.size || order.any { it !in order.indices }) {
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))
        }
        val startIndex = args.getInt(PlaybackQueueCommands.EXTRA_CURRENT_INDEX, -1)
        if (startIndex !in ids.indices) return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))

        val contextType = args.getString(PlaybackQueueCommands.EXTRA_CONTEXT_TYPE) ?: PlaybackQueueRestorer.CONTEXT_CUSTOM
        val contextId = args.getString(PlaybackQueueCommands.EXTRA_CONTEXT_ID).orEmpty()
        val shuffleEnabled = args.getBoolean(PlaybackQueueCommands.EXTRA_SHUFFLE_ENABLED, false)
        val repeatMode = args.getInt(PlaybackQueueCommands.EXTRA_REPEAT_MODE, Player.REPEAT_MODE_OFF)
            .takeIf { it == Player.REPEAT_MODE_OFF || it == Player.REPEAT_MODE_ONE || it == Player.REPEAT_MODE_ALL }
            ?: Player.REPEAT_MODE_OFF
        val positionMs = args.getLong(PlaybackQueueCommands.EXTRA_POSITION_MS, 0L).coerceAtLeast(0L)
        val playWhenReady = args.getBoolean(PlaybackQueueCommands.EXTRA_PLAY_WHEN_READY, false)
        val playlists = PlaylistRepository(this).all().associate { it.id to it.songIds.map(Long::toString) }
        if (contextType == PlaybackQueueRestorer.CONTEXT_PLAYLIST && playlists[contextId] != ids) {
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))
        }
        val requestedItems = ids.indices.map { index ->
            androidx.media3.common.MediaItem.Builder()
                .setMediaId(ids[index])
                .setUri(Uri.parse(uris[index]))
                .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(titles[index]).setArtist(artists[index]).setAlbumTitle(albums[index])
                    .setArtworkUri(artworks[index].takeIf(String::isNotBlank)?.let(Uri::parse)).build())
                .build()
        }
        val previousSnapshot = queueStore.read()
        val previousItems = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
        val previousIndex = player.currentMediaItemIndex
        val previousPosition = player.currentPosition.coerceAtLeast(0L)
        val previousShuffle = player.shuffleModeEnabled
        val previousRepeat = player.repeatMode
        val previousPlayWhenReady = player.playWhenReady
        val previousOrder = playbackQueue.capture(player, activeQueueContextType, activeQueueContextId).shuffleOrderIndices
        queueTransactionApplying = true
        untrustedTimelineMutation = false
        try {
            player.setMediaItems(requestedItems, startIndex, positionMs)
            player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(order, 0L))
            player.shuffleModeEnabled = shuffleEnabled
            player.repeatMode = repeatMode
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.playWhenReady = playWhenReady
            val appliedIds = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
            if (appliedIds != ids) throw IllegalStateException("Player timeline did not match the requested queue transaction")
            val committedContext = PlaybackQueuePolicies.contextForMutation(
                QueueMutationOrigin.INTERNAL_TRANSACTION, contextType, contextId
            )
            activeQueueContextType = committedContext.first
            activeQueueContextId = committedContext.second
            expectedPlaylistIds = if (contextType == PlaybackQueueRestorer.CONTEXT_PLAYLIST) ids.toList() else null
            val candidate = playbackQueue.capture(player, committedContext.first, committedContext.second).copy(
                contextType = committedContext.first,
                contextId = committedContext.second,
                currentMediaIndex = startIndex,
                currentMediaId = ids[startIndex],
                positionMs = positionMs,
                shuffleEnabled = shuffleEnabled,
                shuffleOrderIndices = order.toList(),
                repeatMode = repeatMode,
                playWhenReady = playWhenReady
            )
            val applied = PlaybackQueuePolicies.snapshotAfterApply(previousSnapshot, candidate, applied = true)
                ?: throw IllegalStateException("Queue transaction has no committed snapshot")
            if (!queueStore.write(applied)) throw IllegalStateException("Playback queue snapshot could not be written")
            snapshotRestoreDeferred = false
            queueStateDiverged = false
            queueTransactionApplying = false
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        } catch (error: Exception) {
            android.util.Log.e("PlaybackService", "Queue transaction failed; restoring the previous player state", error)
            activeQueueContextType = previousSnapshot?.contextType ?: "LIBRARY"
            activeQueueContextId = previousSnapshot?.contextId ?: "all"
            expectedPlaylistIds = previousSnapshot?.takeIf { it.contextType == PlaybackQueueRestorer.CONTEXT_PLAYLIST }
                ?.items?.map { it.mediaId }
            try {
                if (previousItems.isEmpty()) {
                    player.clearMediaItems()
                } else {
                    player.setMediaItems(previousItems, previousIndex.coerceIn(previousItems.indices), previousPosition)
                    player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(
                        previousOrder.take(previousItems.size).toIntArray(), 0L
                    ))
                }
                player.shuffleModeEnabled = previousShuffle
                player.repeatMode = previousRepeat
                if (previousItems.isNotEmpty() && player.playbackState == Player.STATE_IDLE) player.prepare()
                player.playWhenReady = previousPlayWhenReady
            } catch (rollbackError: Exception) {
                android.util.Log.e("PlaybackService", "Queue rollback failed; keeping the last persisted snapshot", rollbackError)
                queueStateDiverged = true
            } finally {
                queueTransactionApplying = false
                untrustedTimelineMutation = false
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_IO))
        }
    }

    private fun restoreQueueSnapshot() {
        val saved = queueStore.read() ?: return
        activeQueueContextType = saved.contextType
        activeQueueContextId = saved.contextId
        val playlists = PlaylistRepository(this).all().associate { it.id to it.songIds.map(Long::toString) }
        expectedPlaylistIds = if (saved.contextType == PlaybackQueueRestorer.CONTEXT_PLAYLIST) {
            playlists[saved.contextId]?.takeIf { it == saved.items.map(QueueMediaDescriptor::mediaId) }
        } else null
        if (saved.contextType == PlaybackQueueRestorer.CONTEXT_PLAYLIST && expectedPlaylistIds == null) {
            activeQueueContextType = PlaybackQueueRestorer.CONTEXT_CUSTOM
            activeQueueContextId = "Fila personalizada"
            android.util.Log.w("PlaybackService", "Saved playlist context does not exactly match its saved timeline")
        }
        val restorable = saved.items.filter { it.uri.isNotBlank() }
        if (restorable.size != saved.items.size) {
            android.util.Log.w("PlaybackService", "Saved queue contains items without URIs; preserving the snapshot for later reconciliation")
            snapshotRestoreDeferred = true
            return
        }
        val plan = PlaybackQueueRestorer.plan(saved, restorable, playlists, CatalogStatus.INCOMPLETE)
        if (plan == null) {
            if (saved.items.isNotEmpty()) android.util.Log.w("PlaybackService", "Saved queue has no restorable media items")
            snapshotRestoreDeferred = saved.items.isNotEmpty()
            return
        }
        val mediaItems = plan.state.items.map { descriptor ->
            androidx.media3.common.MediaItem.Builder()
                .setMediaId(descriptor.mediaId)
                .setUri(Uri.parse(descriptor.uri))
                .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(descriptor.title)
                    .setArtist(descriptor.artist)
                    .setAlbumTitle(descriptor.album)
                    .setArtworkUri(descriptor.artworkUri?.let(Uri::parse))
                    .build())
                .build()
        }
        if (mediaItems.isEmpty()) return
        queueRestorationApplying = true
        try {
            player.setMediaItems(mediaItems, plan.currentIndex, plan.state.positionMs)
            player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(plan.shuffleOrderIndices, 0L))
            player.shuffleModeEnabled = plan.state.shuffleEnabled
            player.repeatMode = plan.state.repeatMode.takeIf {
                it == Player.REPEAT_MODE_OFF || it == Player.REPEAT_MODE_ONE || it == Player.REPEAT_MODE_ALL
            } ?: Player.REPEAT_MODE_OFF
            val readAudioPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
            player.playWhenReady = plan.state.playWhenReady &&
                ContextCompat.checkSelfPermission(this, readAudioPermission) == PackageManager.PERMISSION_GRANTED
            player.prepare()
        } finally {
            queueRestorationApplying = false
        }
        persistQueueSnapshot()
    }

    private fun persistQueueSnapshot() {
        if (!::player.isInitialized || !::queueStore.isInitialized) return
        if (untrustedTimelineMutation || queueTransactionApplying || queueRestorationApplying || queueStateDiverged) return
        if (snapshotRestoreDeferred && player.mediaItemCount == 0) return
        val currentIds = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
        var contextType = activeQueueContextType
        var contextId = activeQueueContextId
        if (contextType == PlaybackQueueRestorer.CONTEXT_PLAYLIST && expectedPlaylistIds != null && currentIds != expectedPlaylistIds) {
            contextType = PlaybackQueueRestorer.CONTEXT_CUSTOM
            contextId = "Fila personalizada"
            activeQueueContextType = contextType
            activeQueueContextId = contextId
        }
        queueStore.write(playbackQueue.capture(player, contextType, contextId))
    }

    private fun persistQueuePosition() {
        queueStore.writePosition(
            mediaId = player.currentMediaItem?.mediaId,
            mediaIndex = player.currentMediaItemIndex,
            positionMs = player.currentPosition.coerceAtLeast(0L)
        )
    }

    override fun onDestroy() {
        if (untrustedTimelineMutation) {
            val context = PlaybackQueuePolicies.contextForMutation(
                pendingMutationOrigin, activeQueueContextType, activeQueueContextId
            )
            activeQueueContextType = context.first
            activeQueueContextId = context.second
            untrustedTimelineMutation = false
        }
        if (!queueStateDiverged) persistQueueSnapshot()
        meterHandler.removeCallbacks(meterPublisher)
        meterHandler.removeCallbacks(queueCheckpoint)
        meterHandler.removeCallbacks(untrustedMutationFallback)
        player.removeListener(queueListener)
        mediaSession?.release()
        player.release()
        mediaSession = null
        super.onDestroy()
    }

    private companion object {
        const val QUEUE_POSITION_CHECKPOINT_MS = 5_000L
        const val UNTRUSTED_MUTATION_TIMEOUT_MS = 1_500L
    }
}
