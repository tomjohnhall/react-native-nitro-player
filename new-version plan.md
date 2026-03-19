# NitroPlayer v2 — Architecture Plan

## Table of Contents

1. [Current State Analysis](#1-current-state-analysis)
2. [Problems with Current Architecture](#2-problems-with-current-architecture)
3. [Proposed API Surface (All Specs)](#3-proposed-api-surface-all-specs)
4. [Temporary Queue Architecture (PlayNext / UpNext)](#4-temporary-queue-architecture-playnext--upnext)
5. [Android Architecture (v2)](#5-android-architecture-v2)
6. [iOS Architecture (v2)](#6-ios-architecture-v2)
7. [Shared Patterns](#7-shared-patterns)
8. [Migration Checklist](#8-migration-checklist)

---

## 1. Current State Analysis

### 1.1 Features

| Feature | Android | iOS |
|---------|---------|-----|
| Playlist CRUD (create, delete, update, reorder) | Yes | Yes |
| Queue management (playNext LIFO, addToUpNext FIFO, actual queue) | Yes | Yes |
| Playback controls (play, pause, seek, skip, repeat, volume, speed) | Yes | Yes |
| Gapless playback (buffer config, preloaded assets) | Yes | Yes |
| Lazy URL loading (tracks with empty URLs, lookahead preload) | Yes | Yes |
| Offline downloads (single track, playlist, pause/resume/cancel) | Yes | Yes |
| Equalizer (5-band, presets, custom presets) | Yes | Yes |
| Media session (lock screen, notification controls) | Yes | Yes |
| Android Auto media browser | Yes | -- |
| CarPlay integration | -- | Yes |
| Audio route picker (AirPlay) | -- | Yes |
| Audio output device selection | Yes | -- |
| Event system (track change, state, seek, progress, download) | Yes | Yes |

### 1.2 Blocking Patterns Found

#### Android -- `CountDownLatch` (10 call sites in `TrackPlayerCore.kt`)

Every method that needs to read ExoPlayer state from a background thread uses this pattern:

```kotlin
// CURRENT -- blocks the Nitro worker thread for up to 5 seconds
fun getState(): PlayerState {
    if (Looper.myLooper() == handler.looper) return getStateInternal()

    val latch = CountDownLatch(1)
    var result: PlayerState? = null
    handler.post {
        try { result = getStateInternal() }
        finally { latch.countDown() }
    }
    latch.await(5, TimeUnit.SECONDS) // BLOCKS
    return result ?: fallback
}
```

**Affected methods:** `getState()`, `skipToIndex()`, `getActualQueue()`, `getTracksById()`, `getTracksNeedingUrls()`, `getNextTracks()`, `getCurrentTrackIndex()`, `setPlayBackSpeed()`, `getPlayBackSpeed()`

#### iOS -- `DispatchQueue.main.sync` (14 call sites in `TrackPlayerCore.swift`)

```swift
// CURRENT -- blocks the calling thread until main finishes
func getState() -> PlayerState {
    if Thread.isMainThread { return getStateInternal() }
    var state: PlayerState!
    DispatchQueue.main.sync { state = self?.getStateInternal() ?? fallback } // BLOCKS
    return state
}
```

**Affected methods:** `play()`, `pause()`, `seek()`, `skipToNext()`, `skipToPrevious()`, `skipToIndex()`, `getState()`, `getActualQueue()`, `getTracksById()`, `getTracksNeedingUrls()`, `getNextTracks()`, `getCurrentTrackIndex()`, `loadPlaylist()`

#### Android -- `synchronized(this)` on database / playlist I/O

`DownloadDatabase.kt` wraps every method in `synchronized(this)`, including file I/O (`saveToDisk`, `loadFromDisk`) and `File.exists()` checks. `PlaylistManager.kt` uses `synchronized(playlists)` around map access.

---

## 2. Problems with Current Architecture

### 2.1 Thread Blocking

| Problem | Impact | Severity |
|---------|--------|----------|
| `CountDownLatch.await(5s)` blocks Nitro worker thread | Nitro dispatches JS promises on a thread pool. Blocking a pool thread for 5s starves other promises and can cascade into ANR if pool is exhausted. | **High** |
| `DispatchQueue.main.sync` blocks the calling thread | If Nitro calls from a background thread (it does for `Promise` returns), the background thread parks until the main run loop processes the block. Under heavy UI load, this stalls the bridge. If ever called from main (despite guard), instant deadlock. | **High** |
| 5-second timeout returns fallback state | A timeout silently returns wrong data (`PlayerState` with all zeros / STOPPED). JS has no idea the data is stale. | **Medium** |
| `synchronized` around disk I/O | Every query holds `this` while checking `File.exists()` and reading JSON. Concurrent callers block. | **Medium** |

### 2.2 Fire-and-Forget Hides Failures

Current `play()`, `pause()`, `seek()` are sync void. If the native side fails (no player initialized, invalid state), the JS side never finds out. There are two approaches:

**Option A: Make them Promise-based (chosen)**
- `play(): Promise<void>` -- rejects if player not initialized or in error state.
- UI gets immediate feedback on success/failure.
- On the native side, dispatch to player thread, do the work, resolve/reject.
- Only microseconds of async overhead -- player operations complete in <1ms.

**Option B: Sync void + error events (rejected)**
- Less clean -- caller has no way to associate an error event with a specific call.
- Race conditions between multiple rapid calls and their error events.

### 2.3 Concurrency Model Issues

| Problem | Detail |
|---------|--------|
| **No dedicated player thread (Android)** | ExoPlayer is on the main looper. Every player API call contends with UI rendering. |
| **No serial isolation (iOS)** | All state mutated on main thread. Relies on runtime discipline. |
| **Singleton + mutable state** | `TrackPlayerCore` has ~15 mutable instance variables. No compile-time thread safety. |

---

## 3. Proposed API Surface (All Specs)

### 3.1 TrackPlayer

Every method that touches player state goes through the player thread/queue. Commands return `Promise<void>` so the caller knows if it succeeded. Queries return `Promise<T>`. Pure reads of atomic/volatile values stay sync.

```typescript
export interface TrackPlayer
  extends HybridObject<{ android: 'kotlin'; ios: 'swift' }> {

  // ============================================================
  // PLAYBACK COMMANDS
  // All return Promise so caller knows success/failure.
  // Native side: dispatch to player thread, do work, resolve/reject.
  // ============================================================

  play(): Promise<void>
  pause(): Promise<void>
  seek(position: number): Promise<void>
  skipToNext(): Promise<void>
  skipToPrevious(): Promise<void>
  playSong(songId: string, fromPlaylist?: string): Promise<void>
  skipToIndex(index: number): Promise<boolean>
  setVolume(volume: number): Promise<void>
  setRepeatMode(mode: RepeatMode): Promise<void>
  setPlaybackSpeed(speed: number): Promise<void>
  configure(config: PlayerConfig): Promise<void>

  // ============================================================
  // TEMPORARY QUEUE (PlayNext / UpNext)
  // ============================================================

  /** Insert track to play immediately after current (LIFO stack) */
  playNext(trackId: string): Promise<void>

  /** Append track to end of temporary up-next queue (FIFO) */
  addToUpNext(trackId: string): Promise<void>

  /** Remove a specific track from the playNext stack */
  removeFromPlayNext(trackId: string): Promise<boolean>

  /** Remove a specific track from the upNext queue */
  removeFromUpNext(trackId: string): Promise<boolean>

  /** Clear all playNext tracks */
  clearPlayNext(): Promise<void>

  /** Clear all upNext tracks */
  clearUpNext(): Promise<void>

  /** Move a temporary track to a different position in the combined temp queue */
  reorderTemporaryTrack(trackId: string, newIndex: number): Promise<boolean>

  /** Get the current playNext stack (LIFO order) */
  getPlayNextQueue(): Promise<TrackItem[]>

  /** Get the current upNext queue (FIFO order) */
  getUpNextQueue(): Promise<TrackItem[]>

  // ============================================================
  // TRACK QUERIES
  // All truly async -- no thread ever blocked.
  // ============================================================

  getState(): Promise<PlayerState>
  getActualQueue(): Promise<TrackItem[]>
  getTracksById(trackIds: string[]): Promise<TrackItem[]>
  getTracksNeedingUrls(): Promise<TrackItem[]>
  getNextTracks(count: number): Promise<TrackItem[]>
  getCurrentTrackIndex(): Promise<number>
  getPlaybackSpeed(): Promise<number>

  // ============================================================
  // TRACK UPDATES (lazy URL loading)
  // ============================================================

  updateTracks(tracks: TrackItem[]): Promise<void>

  // ============================================================
  // PURE READS (no player thread access, atomic/volatile)
  // ============================================================

  getRepeatMode(): RepeatMode
  isAndroidAutoConnected(): boolean

  // ============================================================
  // EVENTS
  // ============================================================

  onChangeTrack(callback: (track: TrackItem, reason?: Reason) => void): void
  onPlaybackStateChange(callback: (state: TrackPlayerState, reason?: Reason) => void): void
  onSeek(callback: (position: number, totalDuration: number) => void): void
  onPlaybackProgressChange(
    callback: (position: number, totalDuration: number, isManuallySeeked?: boolean) => void
  ): void
  onTracksNeedUpdate(callback: (tracks: TrackItem[], lookahead: number) => void): void
  onAndroidAutoConnectionChange(callback: (connected: boolean) => void): void

  /** Fires when the temporary queue changes (playNext/upNext add/remove/reorder) */
  onTemporaryQueueChange(
    callback: (playNextQueue: TrackItem[], upNextQueue: TrackItem[]) => void
  ): void
}
```

**Why `Promise<void>` for commands:**
- `play()` rejects if player not initialized, item in error state, or audio session activation fails.
- `pause()` rejects if player not initialized.
- `seek(position)` rejects if position is out of bounds or no current item.
- `setVolume(volume)` rejects if volume is out of range (0-100).
- `playNext(trackId)` rejects if track ID is not found in any playlist.
- UI can `await` or `.catch()` to show error toasts.
- Native side resolves in <1ms -- zero perceptible latency.
- If the caller doesn't care about errors, they simply don't `await`.

### 3.2 PlayerQueue

```typescript
export interface PlayerQueue
  extends HybridObject<{ android: 'kotlin'; ios: 'swift' }> {

  // ============================================================
  // PLAYLIST MANAGEMENT
  // Mutating ops return Promise for success/failure feedback.
  // Read ops stay sync (in-memory map, fast).
  // ============================================================

  createPlaylist(name: string, description?: string, artwork?: string): Promise<string>
  deletePlaylist(playlistId: string): Promise<void>
  updatePlaylist(
    playlistId: string,
    name?: string,
    description?: string,
    artwork?: string
  ): Promise<void>
  getPlaylist(playlistId: string): Playlist | null
  getAllPlaylists(): Playlist[]

  // ============================================================
  // TRACK MANAGEMENT WITHIN PLAYLISTS
  // ============================================================

  addTrackToPlaylist(playlistId: string, track: TrackItem, index?: number): Promise<void>
  addTracksToPlaylist(playlistId: string, tracks: TrackItem[], index?: number): Promise<void>
  removeTrackFromPlaylist(playlistId: string, trackId: string): Promise<void>
  reorderTrackInPlaylist(playlistId: string, trackId: string, newIndex: number): Promise<void>

  // ============================================================
  // PLAYBACK CONTROL
  // ============================================================

  loadPlaylist(playlistId: string): Promise<void>
  getCurrentPlaylistId(): string | null

  // ============================================================
  // EVENTS
  // ============================================================

  onPlaylistsChanged(
    callback: (playlists: Playlist[], operation?: QueueOperation) => void
  ): void
  onPlaylistChanged(
    callback: (playlistId: string, playlist: Playlist, operation?: QueueOperation) => void
  ): void
}
```

**Key changes from v1:**
- `createPlaylist` returns `Promise<string>` instead of `string` -- confirms the ID was persisted.
- `deletePlaylist`, `updatePlaylist`, `addTrack*`, `removeTrack*`, `reorderTrack*` return `Promise<void>` so failures (playlist not found, invalid index) are reported to the caller.
- `loadPlaylist` returns `Promise<void>` -- resolves when the player queue is rebuilt and ready.
- `getPlaylist`, `getAllPlaylists`, `getCurrentPlaylistId` stay sync -- they read an in-memory `ConcurrentHashMap` / Swift dictionary on a serial queue, no blocking needed.

### 3.3 DownloadManager

```typescript
export interface DownloadManager
  extends HybridObject<{ android: 'kotlin'; ios: 'swift' }> {

  // ============================================================
  // CONFIGURATION
  // ============================================================

  configure(config: DownloadConfig): void
  getConfig(): DownloadConfig

  // ============================================================
  // DOWNLOAD OPERATIONS
  // ============================================================

  downloadTrack(track: TrackItem, playlistId?: string): Promise<string>
  downloadPlaylist(playlistId: string, tracks: TrackItem[]): Promise<string[]>
  pauseDownload(downloadId: string): Promise<void>
  resumeDownload(downloadId: string): Promise<void>
  cancelDownload(downloadId: string): Promise<void>
  retryDownload(downloadId: string): Promise<void>
  pauseAllDownloads(): Promise<void>
  resumeAllDownloads(): Promise<void>
  cancelAllDownloads(): Promise<void>

  // ============================================================
  // DOWNLOAD QUERIES (in-memory, sync is fine)
  // ============================================================

  getDownloadTask(downloadId: string): DownloadTask | null
  getActiveDownloads(): DownloadTask[]
  getQueueStatus(): DownloadQueueStatus
  isDownloading(trackId: string): boolean
  getDownloadState(trackId: string): DownloadState | null

  // ============================================================
  // DOWNLOADED CONTENT QUERIES
  // File existence checks can be slow -- make async.
  // ============================================================

  isTrackDownloaded(trackId: string): Promise<boolean>
  isPlaylistDownloaded(playlistId: string): Promise<boolean>
  isPlaylistPartiallyDownloaded(playlistId: string): Promise<boolean>
  getDownloadedTrack(trackId: string): Promise<DownloadedTrack | null>
  getAllDownloadedTracks(): Promise<DownloadedTrack[]>
  getDownloadedPlaylist(playlistId: string): Promise<DownloadedPlaylist | null>
  getAllDownloadedPlaylists(): Promise<DownloadedPlaylist[]>
  getLocalPath(trackId: string): Promise<string | null>

  // ============================================================
  // DELETE OPERATIONS
  // ============================================================

  deleteDownloadedTrack(trackId: string): Promise<void>
  deleteDownloadedPlaylist(playlistId: string): Promise<void>
  deleteAllDownloads(): Promise<void>

  // ============================================================
  // STORAGE
  // ============================================================

  getStorageInfo(): Promise<DownloadStorageInfo>
  syncDownloads(): Promise<number>

  // ============================================================
  // PLAYBACK SOURCE
  // ============================================================

  setPlaybackSourcePreference(preference: PlaybackSource): void
  getPlaybackSourcePreference(): PlaybackSource
  getEffectiveUrl(track: TrackItem): Promise<string>

  // ============================================================
  // EVENTS
  // ============================================================

  onDownloadProgress(callback: (progress: DownloadProgress) => void): void
  onDownloadStateChange(
    callback: (downloadId: string, trackId: string, state: DownloadState, error?: DownloadError) => void
  ): void
  onDownloadComplete(callback: (downloadedTrack: DownloadedTrack) => void): void
}
```

**Key changes from v1:**
- `isTrackDownloaded`, `isPlaylistDownloaded`, `isPlaylistPartiallyDownloaded` -> `Promise<boolean>`. These check `File.exists()` on disk; making them async avoids holding a lock during I/O.
- `getDownloadedTrack`, `getAllDownloadedTracks`, `getDownloadedPlaylist`, `getAllDownloadedPlaylists`, `getLocalPath` -> `Promise<T>`. Same reason -- file validation.
- `syncDownloads` -> `Promise<number>`. This scans all files on disk.
- `getEffectiveUrl` -> `Promise<string>`. May check file existence.

### 3.4 Equalizer

```typescript
export interface Equalizer
  extends HybridObject<{ android: 'kotlin'; ios: 'swift' }> {

  // ============================================================
  // ENABLE / DISABLE
  // ============================================================

  setEnabled(enabled: boolean): Promise<void>
  isEnabled(): boolean

  // ============================================================
  // BAND CONTROL
  // All mutations go through the player thread (EQ nodes are
  // tied to ExoPlayer / AVAudioEngine on the player thread).
  // ============================================================

  getBands(): Promise<EqualizerBand[]>
  setBandGain(bandIndex: number, gainDb: number): Promise<void>
  setAllBandGains(gains: number[]): Promise<void>
  getBandRange(): GainRange

  // ============================================================
  // PRESETS
  // ============================================================

  getPresets(): EqualizerPreset[]
  getBuiltInPresets(): EqualizerPreset[]
  getCustomPresets(): EqualizerPreset[]
  applyPreset(presetName: string): Promise<void>
  getCurrentPresetName(): string | null
  saveCustomPreset(name: string): Promise<void>
  deleteCustomPreset(name: string): Promise<void>

  // ============================================================
  // STATE
  // ============================================================

  getState(): Promise<EqualizerState>
  reset(): Promise<void>

  // ============================================================
  // EVENTS
  // ============================================================

  onEnabledChange(callback: (enabled: boolean) => void): void
  onBandChange(callback: (bands: EqualizerBand[]) => void): void
  onPresetChange(callback: (presetName: string | null) => void): void
}
```

**Key changes from v1:**
- `setEnabled` -> `Promise<void>` instead of `boolean`. Rejects on failure, resolves on success.
- `setBandGain`, `setAllBandGains`, `applyPreset` -> `Promise<void>`. These mutate audio engine nodes on the player thread.
- `saveCustomPreset`, `deleteCustomPreset` -> `Promise<void>`. Involves disk persistence.
- `getBands`, `getState` -> `Promise<T>`. Reads EQ state from the player thread.
- `reset` -> `Promise<void>`. Resets all bands on the player thread.
- `isEnabled`, `getBandRange`, `getPresets`, `getBuiltInPresets`, `getCustomPresets`, `getCurrentPresetName` stay sync -- pure in-memory reads.

### 3.5 AudioDevices (Android only)

```typescript
export interface AudioDevices extends HybridObject<{ android: 'kotlin' }> {
  getAudioDevices(): TAudioDevice[]
  setAudioDevice(deviceId: number): Promise<void>
}
```

**Changes:** `setAudioDevice` -> `Promise<void>`. Confirms the device switch succeeded.

### 3.6 AudioRoutePicker (iOS only)

```typescript
export interface AudioRoutePicker extends HybridObject<{ ios: 'swift' }> {
  showRoutePicker(): void
}
```

No changes. This is a pure UI action (presents a system view).

### 3.7 AndroidAutoMediaLibrary (Android only)

```typescript
export interface AndroidAutoMediaLibrary extends HybridObject<{ android: 'kotlin' }> {
  setMediaLibrary(libraryJson: string): Promise<void>
  clearMediaLibrary(): Promise<void>
}
```

**Changes:** Both -> `Promise<void>`. Confirms the media tree was rebuilt and notified to Android Auto.

---

## 4. Temporary Queue Architecture (PlayNext / UpNext)

### 4.1 Current Design

The temporary queue system uses two data structures:

- **`playNextStack`** (LIFO): When a user taps "Play Next", the track is inserted at index 0. The most recently added track plays first.
- **`upNextQueue`** (FIFO): When a user taps "Add to Up Next", the track is appended. First added plays first.

The actual playback queue is computed as:

```
[tracks before current] + [current track] + [playNextStack] + [upNextQueue] + [remaining playlist tracks]
```

A `currentTemporaryType` enum tracks whether the currently playing track came from the playlist, playNext, or upNext. When a temporary track finishes, the system transitions to the next source (playNext -> upNext -> playlist).

### 4.2 Current Problems

| Problem | Detail |
|---------|--------|
| No remove/clear API for temporary tracks | Once added, a track can only leave the temp queue by being played or by calling `playSong` (which clears all temps). |
| No reorder within temporary queue | Users can't rearrange the upcoming temp tracks. |
| No query API for temp queues | Can only see temp tracks via `getActualQueue()` which merges everything. No way to get just the playNext stack or upNext queue. |
| No event when temp queue changes | UI has no direct notification when a temp track is added/removed/played. Must poll `getActualQueue()`. |
| `addToUpNext` / `playNext` silently fail | If the track ID is not found, they log an error but never tell JS. The Promise resolves without the caller knowing the track wasn't added. |

### 4.3 v2 Temporary Queue Design

#### Data Structures (same, proven design)

```
playNextStack: [TrackItem]  -- LIFO, insert at 0, plays in stack order
upNextQueue:   [TrackItem]  -- FIFO, append, plays in insertion order
```

#### Thread Safety

Both structures live on the player thread/queue. All access is serialized. No locks needed.

```
Android: accessed only inside playerHandler.post { } or withPlayerContext { }
iOS:     accessed only inside playerQueue.async { } or withPlayerQueue { }
```

#### New APIs

```typescript
// Mutations -- dispatch to player thread, resolve/reject
playNext(trackId: string): Promise<void>             // reject if track not found
addToUpNext(trackId: string): Promise<void>           // reject if track not found
removeFromPlayNext(trackId: string): Promise<boolean>  // true if found and removed
removeFromUpNext(trackId: string): Promise<boolean>    // true if found and removed
clearPlayNext(): Promise<void>                        // clears entire playNext stack
clearUpNext(): Promise<void>                          // clears entire upNext queue
reorderTemporaryTrack(trackId: string, newIndex: number): Promise<boolean>

// Queries -- read from player thread, no blocking
getPlayNextQueue(): Promise<TrackItem[]>     // snapshot of the playNext stack
getUpNextQueue(): Promise<TrackItem[]>       // snapshot of the upNext queue
getActualQueue(): Promise<TrackItem[]>       // full computed queue (existing)

// Events
onTemporaryQueueChange(
  callback: (playNextQueue: TrackItem[], upNextQueue: TrackItem[]) => void
): void
```

#### Implementation Pattern (Android)

```kotlin
// In TrackPlayerCore (all runs on playerDispatcher)

suspend fun playNext(trackId: String) = withPlayerContext {
    val track = findTrackById(trackId)
        ?: throw IllegalArgumentException("Track $trackId not found")

    playNextStack.add(0, track)

    if (::player.isInitialized && player.currentMediaItem != null) {
        rebuildQueueFromCurrentPosition()
    }

    notifyTemporaryQueueChange()
}

suspend fun removeFromPlayNext(trackId: String): Boolean = withPlayerContext {
    val removed = playNextStack.removeAll { it.id == trackId }
    if (removed) {
        rebuildQueueFromCurrentPosition()
        notifyTemporaryQueueChange()
    }
    removed
}

suspend fun clearPlayNext() = withPlayerContext {
    playNextStack.clear()
    rebuildQueueFromCurrentPosition()
    notifyTemporaryQueueChange()
}

suspend fun getPlayNextQueue(): List<TrackItem> = withPlayerContext {
    playNextStack.toList() // snapshot
}

private fun notifyTemporaryQueueChange() {
    val playNext = playNextStack.toList()
    val upNext = upNextQueue.toList()
    for (listener in onTemporaryQueueChangeListeners) {
        listener.callback(playNext, upNext)
    }
}
```

#### Implementation Pattern (iOS)

```swift
// In TrackPlayerCore (all runs on playerQueue)

func playNext(trackId: String) async throws {
    try await withPlayerQueue {
        guard let track = self.findTrackById(trackId) else {
            throw NitroPlayerError.trackNotFound(trackId)
        }

        self.playNextStack.insert(track, at: 0)

        if self.player?.currentItem != nil {
            self.rebuildAVQueueFromCurrentPosition()
        }

        self.notifyTemporaryQueueChange()
    }
}

func removeFromPlayNext(trackId: String) async -> Bool {
    await withPlayerQueue {
        let before = self.playNextStack.count
        self.playNextStack.removeAll { $0.id == trackId }
        let removed = self.playNextStack.count < before
        if removed {
            self.rebuildAVQueueFromCurrentPosition()
            self.notifyTemporaryQueueChange()
        }
        return removed
    }
}

func getPlayNextQueue() async -> [TrackItem] {
    await withPlayerQueue { self.playNextStack }
}
```

#### Queue Computation (unchanged, thread-safe by design)

Both `getActualQueueInternal` and `rebuildQueueFromCurrentPosition` already produce the correct merged queue. Because they run on the player thread/queue, they have exclusive access to `playNextStack`, `upNextQueue`, `currentTracks`, and `currentTrackIndex`. No locks or defensive copies needed during computation.

```
Actual Queue = [before_current] + [current] + [playNextStack] + [upNextQueue] + [after_current]
```

#### Transition Logic (when a track finishes)

```
if playNextStack is not empty:
    pop index 0 from playNextStack
    set currentTemporaryType = PLAY_NEXT
    play it
else if upNextQueue is not empty:
    pop index 0 from upNextQueue
    set currentTemporaryType = UP_NEXT
    play it
else:
    advance currentTrackIndex in original playlist
    set currentTemporaryType = NONE
    play it (or handle repeat mode / end of playlist)
```

This logic already exists and remains unchanged. The new APIs just add removal/reorder on top.

---

## 5. Android Architecture (v2)

### 5.1 Dedicated Player Looper Thread

ExoPlayer runs on its own looper, not the main (UI) looper.

```kotlin
class TrackPlayerCore private constructor(context: Context) {

    private val playerThread = HandlerThread("NitroPlayer").apply { start() }
    private val playerHandler = Handler(playerThread.looper)
    private val playerDispatcher = playerThread.looper.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + playerDispatcher)

    init {
        playerHandler.post {
            player = ExoPlayer.Builder(context)
                .setLooper(playerThread.looper)
                .setLoadControl(gaplessLoadControl)
                .setAudioAttributes(audioAttributes, true)
                .build()
            // ... attach listeners ...
        }
    }
}
```

**Why:** ExoPlayer supports running on any looper via `setLooper()`. Moving it off main means UI rendering is never blocked by player operations.

### 5.2 The `withPlayerContext` Pattern -- Replaces All Latches

```kotlin
private suspend fun <T> withPlayerContext(block: () -> T): T {
    if (Looper.myLooper() == playerThread.looper) return block()

    return suspendCancellableCoroutine { cont ->
        val runnable = Runnable {
            try {
                cont.resume(block())
            } catch (e: Exception) {
                cont.resumeWithException(e)
            }
        }
        playerHandler.post(runnable)
        cont.invokeOnCancellation { playerHandler.removeCallbacks(runnable) }
    }
}
```

Every latch call site becomes a one-liner:

```kotlin
// BEFORE (blocks for 5 seconds)
fun getState(): PlayerState {
    if (Looper.myLooper() == handler.looper) return getStateInternal()
    val latch = CountDownLatch(1)
    var result: PlayerState? = null
    handler.post { result = getStateInternal(); latch.countDown() }
    latch.await(5, TimeUnit.SECONDS)
    return result ?: fallback
}

// AFTER (suspends, zero threads blocked)
suspend fun getState(): PlayerState = withPlayerContext { getStateInternal() }
```

### 5.3 Fire-and-Forget Commands (now with error reporting)

Commands dispatch to the player thread and resolve/reject the Promise:

```kotlin
// BEFORE (fire-and-forget, caller has no idea if it worked)
fun play() {
    handler.post { player.play() }
}

// AFTER (Promise-based, caller gets error feedback)
suspend fun play() = withPlayerContext {
    if (!::player.isInitialized) throw IllegalStateException("Player not initialized")
    player.play()
    emitStateChange()
}
```

The Nitro bridge maps Kotlin `suspend fun` -> JS `Promise<void>` automatically.

### 5.4 DownloadDatabase -- Coroutine Mutex + IO Dispatcher

```kotlin
class DownloadDatabase private constructor(context: Context) {

    private val mutex = Mutex()
    private val ioDispatcher = Dispatchers.IO

    suspend fun saveDownloadedTrack(track: DownloadedTrack, playlistId: String?) {
        mutex.withLock {
            downloadedTracks[track.trackId] = record
            playlistId?.let { playlistTracks.getOrPut(it) { mutableSetOf() }.add(track.trackId) }
        }
        withContext(ioDispatcher) { saveToDisk() }
    }

    suspend fun isTrackDownloaded(trackId: String): Boolean = mutex.withLock {
        val record = downloadedTracks[trackId] ?: return false
        withContext(ioDispatcher) { File(record.localPath).exists() }
    }
}
```

### 5.5 PlaylistManager -- ConcurrentHashMap + IO Save

```kotlin
class PlaylistManager private constructor(context: Context) {
    private val playlists = ConcurrentHashMap<String, Playlist>()

    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saveJob: Job? = null

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = saveScope.launch {
            delay(300)
            saveToFile()
        }
    }
}
```

### 5.6 Listener Management -- CopyOnWriteArrayList

```kotlin
// BEFORE
private val onChangeTrackListeners =
    Collections.synchronizedList(mutableListOf<WeakCallbackBox<...>>())
// requires manual synchronized block for iteration

// AFTER
private val onChangeTrackListeners =
    CopyOnWriteArrayList<WeakCallbackBox<...>>()
// safe iteration without external sync, writes are rare (add/remove listeners)
```

### 5.7 Complete Android Architecture Diagram

```
                     JS Thread (Hermes)
                     trackPlayer.play() -> Promise<void>
                            |
                            | Nitro bridge (JNI, suspend -> Promise)
                            v
                  HybridTrackPlayer (Kotlin)
                  Generated spec bridge
                            |
                            v
             +-------------------------------+
             |     TrackPlayerCore (Kotlin)   |
             |                               |
             |  +-------------------------+  |
             |  | Player Thread            |  |
             |  | (HandlerThread)          |  |
             |  |                          |  |
             |  | - ExoPlayer instance     |  |
             |  | - currentTracks          |  |
             |  | - playNextStack          |  |
             |  | - upNextQueue            |  |
             |  | - currentTrackIndex      |  |
             |  | - currentTemporaryType   |  |
             |  |                          |  |
             |  | All state reads/writes   |  |
             |  | happen HERE exclusively  |  |
             |  +-------------------------+  |
             |                               |
             |  suspend fun play() =         |
             |    withPlayerContext {         |
             |      player.play() // on thd  |
             |    }                          |
             |                               |
             |  Listeners: COWL<WeakCB>      |
             |  Dispatched on playerHandler  |
             +-------------------------------+
                            |
          +-----------------+-----------------+
          |                 |                 |
          v                 v                 v
    PlaylistManager   DownloadDatabase   EqualizerCore
    ConcurrentHashMap  Mutex + IO disp   Player thread
    IO save debounce   File ops on IO    EQ bands on
                                         player thread
```

---

## 6. iOS Architecture (v2)

### 6.1 Dedicated Serial Queue

AVQueuePlayer does not require the main thread -- it requires consistent serial access. We create a dedicated serial dispatch queue.

```swift
class TrackPlayerCore: NSObject {

    private let playerQueue = DispatchQueue(
        label: "com.nitroplayer.player",
        qos: .userInitiated
    )

    private var player: AVQueuePlayer?

    private func setupPlayer() {
        playerQueue.async { [weak self] in
            self?.player = AVQueuePlayer()
            // ... configure ...
        }
    }
}
```

### 6.2 The `withPlayerQueue` Pattern -- Replaces All `DispatchQueue.main.sync`

```swift
private func withPlayerQueue<T>(_ block: @escaping () throws -> T) async throws -> T {
    try await withCheckedThrowingContinuation { continuation in
        playerQueue.async {
            do {
                let result = try block()
                continuation.resume(returning: result)
            } catch {
                continuation.resume(throwing: error)
            }
        }
    }
}

// Non-throwing variant
private func withPlayerQueue<T>(_ block: @escaping () -> T) async -> T {
    await withCheckedContinuation { continuation in
        playerQueue.async {
            continuation.resume(returning: block())
        }
    }
}
```

Every blocking method transforms:

```swift
// BEFORE (blocks)
func getState() -> PlayerState {
    if Thread.isMainThread { return getStateInternal() }
    var state: PlayerState!
    DispatchQueue.main.sync { state = getStateInternal() }
    return state
}

// AFTER (suspends, zero threads blocked)
func getState() async -> PlayerState {
    await withPlayerQueue { self.getStateInternal() }
}
```

### 6.3 Commands with Error Reporting

```swift
// BEFORE (sync void, failures are silent)
func play() {
    if Thread.isMainThread { playInternal() }
    else { DispatchQueue.main.sync { self?.playInternal() } }
}

// AFTER (async, throws on failure)
func play() async throws {
    try await withPlayerQueue {
        guard let player = self.player else {
            throw NitroPlayerError.playerNotInitialized
        }
        player.play()
        self.emitStateChange()
    }
}
```

### 6.4 Media Session -- Main Thread Only for MPNowPlayingInfoCenter

```swift
class MediaSessionManager {
    func updateNowPlayingInfo(track: TrackItem, position: Double, duration: Double) {
        let info = buildNowPlayingDictionary(track: track, position: position, duration: duration)

        // Only the MPNowPlayingInfoCenter update goes to main
        DispatchQueue.main.async {
            MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        }
    }
}
```

### 6.5 KVO Observers -- Target the Player Queue

```swift
private func setupCurrentItemObservers() {
    // Modern KVO automatically dispatches to the queue the observation was set up on.
    // Since we set this up from playerQueue, callbacks arrive on playerQueue.
    currentItemObservers.append(
        player?.currentItem?.observe(\.status) { [weak self] item, _ in
            self?.playerQueue.async {
                self?.handleItemStatusChange(item)
            }
        }
    )
}
```

### 6.6 DownloadDatabase -- Serial Queue for File I/O

```swift
class DownloadDatabase {
    static let shared = DownloadDatabase()

    private let dbQueue = DispatchQueue(label: "com.nitroplayer.downloaddb")

    func isTrackDownloaded(trackId: String) async -> Bool {
        await withCheckedContinuation { continuation in
            dbQueue.async {
                guard let record = self.downloadedTracks[trackId] else {
                    continuation.resume(returning: false)
                    return
                }
                let exists = FileManager.default.fileExists(atPath: record.localPath)
                continuation.resume(returning: exists)
            }
        }
    }
}
```

### 6.7 Complete iOS Architecture Diagram

```
                     JS Thread (Hermes)
                     trackPlayer.play() -> Promise<void>
                            |
                            | Nitro bridge (ObjC++, async -> Promise)
                            v
                  HybridTrackPlayer (Swift)
                  Generated spec bridge
                            |
                            v
             +-------------------------------+
             |     TrackPlayerCore (Swift)    |
             |                               |
             |  +-------------------------+  |
             |  | playerQueue              |  |
             |  | (serial DispatchQueue)   |  |
             |  | QoS: .userInitiated      |  |
             |  |                          |  |
             |  | - AVQueuePlayer          |  |
             |  | - currentTracks          |  |
             |  | - playNextStack          |  |
             |  | - upNextQueue            |  |
             |  | - currentTrackIndex      |  |
             |  | - currentTemporaryType   |  |
             |  |                          |  |
             |  | All state reads/writes   |  |
             |  | happen HERE exclusively  |  |
             |  +-------------------------+  |
             |                               |
             |  +-------------------------+  |
             |  | listenersQueue           |  |
             |  | (concurrent + barrier)   |  |
             |  | Reader-writer for cbs    |  |
             |  +-------------------------+  |
             |                               |
             |  +-------------------------+  |
             |  | preloadQueue             |  |
             |  | (serial, QoS: .utility)  |  |
             |  | AVURLAsset preloading    |  |
             |  +-------------------------+  |
             +-------------------------------+
                            |
          +-----------------+-----------------+
          |                 |                 |
          v                 v                 v
    PlaylistManager   DownloadDatabase   EqualizerCore
    Own serial queue   Own serial queue   Player queue
    for dict + save    for map + file IO  for DSP nodes
                                              |
                                              v
                                     MediaSessionManager
                                     Gathers data on playerQueue
                                     MPNowPlayingInfoCenter
                                       on main (async only)
```

---

## 7. Shared Patterns

### 7.1 The "withPlayerContext" Pattern

Both platforms: **post to dedicated serial context, suspend the caller, resume with result.** Zero threads blocked.

| Platform | Mechanism | Caller Behavior |
|----------|-----------|-----------------|
| Android | `suspendCancellableCoroutine` + `playerHandler.post` | Suspended (coroutine) |
| iOS | `withCheckedContinuation` + `playerQueue.async` | Suspended (Swift async) |

### 7.2 Command / Query Classification (v2)

| Type | Returns | Native Pattern | Error Reporting | Examples |
|------|---------|---------------|-----------------|----------|
| **Command** | `Promise<void>` | Post to player thread, do work, resolve/reject | Yes -- reject on failure | `play()`, `pause()`, `seek()`, `skipToNext()`, `playNext()`, `clearPlayNext()` |
| **Async Command** | `Promise<T>` | Post, suspend, resume with result or error | Yes | `playSong()`, `skipToIndex()`, `createPlaylist()` |
| **Query** | `Promise<T>` | Post, suspend, resume with result | Yes (if player not init) | `getState()`, `getActualQueue()`, `getPlayNextQueue()` |
| **Pure Read** | `T` (sync) | No player thread access, atomic/volatile | N/A | `getRepeatMode()`, `isAndroidAutoConnected()`, `getPlaylist()` |

### 7.3 Error Propagation

In v1, errors were silently swallowed (log + fallback). In v2:

```
Native throws/rejects  ->  Nitro bridge  ->  JS Promise.reject  ->  .catch() or try/catch
```

The `HybridTrackPlayer` bridge (generated by Nitrogen) automatically maps:
- Kotlin: `throw IllegalStateException(msg)` -> JS `Promise.reject(new Error(msg))`
- Swift: `throw NitroPlayerError.xyz` -> JS `Promise.reject(new Error(msg))`

### 7.4 Thread Ownership Map

Every piece of mutable state has a single owner (thread/queue). No locks needed for correctly scoped access:

| State | Owner | Android | iOS |
|-------|-------|---------|-----|
| ExoPlayer / AVQueuePlayer | Player thread/queue | `playerHandler` | `playerQueue` |
| `currentTracks`, `currentTrackIndex` | Player thread/queue | `playerHandler` | `playerQueue` |
| `playNextStack`, `upNextQueue` | Player thread/queue | `playerHandler` | `playerQueue` |
| `currentTemporaryType` | Player thread/queue | `playerHandler` | `playerQueue` |
| `currentRepeatMode` | Atomic / volatile | `@Volatile` | `playerQueue` (or atomic) |
| Playlist map | Own context | `ConcurrentHashMap` | Own serial queue |
| Download records | Own context | `Mutex` | Own serial queue |
| EQ band state | Player thread/queue | `playerHandler` | `playerQueue` |
| MPNowPlayingInfo | Main thread | N/A | `DispatchQueue.main.async` |
| MediaSession | Player thread | `playerHandler` | `playerQueue` |

### 7.5 Performance Characteristics

| Operation | v1 Latency | v2 Latency | Why |
|-----------|-----------|-----------|-----|
| `play()` | 0-5000ms (latch timeout) | <1ms (async dispatch) | No thread parking |
| `getState()` | 0-5000ms (latch timeout) | <1ms (suspend + handler post) | No thread parking |
| `getActualQueue()` | 0-5000ms (latch timeout) | <1ms | No thread parking |
| `playNext(trackId)` | <1ms (already async) | <1ms (same, now with error) | Already fast, now reports errors |
| `isTrackDownloaded()` | 0-Nms (synchronized + File.exists) | <5ms (async) | No lock contention |
| UI thread impact | Blocked by player ops on main | Zero -- player off main | Freed for rendering |

---

## 8. Migration Checklist

### Phase 1 -- Android: Player Thread + Coroutines

- [ ] Add `kotlinx-coroutines-android` dependency
- [ ] Create `HandlerThread("NitroPlayer")` in `TrackPlayerCore`
- [ ] Initialize ExoPlayer with `.setLooper(playerThread.looper)`
- [ ] Create `playerDispatcher` from `playerThread.looper.asCoroutineDispatcher()`
- [ ] Create `scope = CoroutineScope(SupervisorJob() + playerDispatcher)`
- [ ] Implement `withPlayerContext` helper
- [ ] Migrate `play()` -- remove `handler.post`, use `withPlayerContext`, throw on failure
- [ ] Migrate `pause()` -- same
- [ ] Migrate `seek()` -- same
- [ ] Migrate `skipToNext()` -- same
- [ ] Migrate `skipToPrevious()` -- same
- [ ] Migrate `getState()` -- remove latch, use `withPlayerContext`
- [ ] Migrate `getActualQueue()` -- remove latch
- [ ] Migrate `skipToIndex()` -- remove latch
- [ ] Migrate `getTracksById()` -- remove latch
- [ ] Migrate `getTracksNeedingUrls()` -- remove latch
- [ ] Migrate `getNextTracks()` -- remove latch
- [ ] Migrate `getCurrentTrackIndex()` -- remove latch
- [ ] Migrate `setPlayBackSpeed()` -- remove latch
- [ ] Migrate `getPlayBackSpeed()` -- remove latch
- [ ] Migrate `playNext()` -- use `withPlayerContext`, throw if track not found
- [ ] Migrate `addToUpNext()` -- use `withPlayerContext`, throw if track not found
- [ ] Add `removeFromPlayNext()`, `removeFromUpNext()`, `clearPlayNext()`, `clearUpNext()`
- [ ] Add `reorderTemporaryTrack()`
- [ ] Add `getPlayNextQueue()`, `getUpNextQueue()`
- [ ] Add `notifyTemporaryQueueChange()` and `onTemporaryQueueChange` listener
- [ ] Replace `handler` (main looper) with `playerHandler` throughout
- [ ] Move `MediaSessionManager` to player thread
- [ ] Keep `MediaBrowserService` on its own thread (Android Auto requirement)
- [ ] Replace `Collections.synchronizedList` with `CopyOnWriteArrayList` for listeners
- [ ] Replace `synchronized(playlists)` in `PlaylistManager` with `ConcurrentHashMap`
- [ ] Move `PlaylistManager.scheduleSave()` to `Dispatchers.IO`
- [ ] Migrate `DownloadDatabase` -- `synchronized` to coroutine `Mutex`, disk I/O to `Dispatchers.IO`
- [ ] Remove `CountDownLatch` and `TimeUnit` imports
- [ ] Remove all `Looper.myLooper() == handler.looper` guards (no longer needed)
- [ ] Remove 5-second timeout fallback states
- [ ] Add `destroy()` to cancel scope and quit handler thread
- [ ] Run harness tests on Android

### Phase 2 -- iOS: Player Queue + Async Continuations

- [ ] Create `playerQueue` serial `DispatchQueue` in `TrackPlayerCore`
- [ ] Move `AVQueuePlayer` initialization to `playerQueue`
- [ ] Implement `withPlayerQueue` async helpers (throwing + non-throwing)
- [ ] Migrate `play()` -- remove `DispatchQueue.main.sync`, use `withPlayerQueue`, throw on failure
- [ ] Migrate `pause()` -- same
- [ ] Migrate `seek()` -- same
- [ ] Migrate `skipToNext()` -- same
- [ ] Migrate `skipToPrevious()` -- same
- [ ] Migrate `getState()` -- remove `DispatchQueue.main.sync`, use `withPlayerQueue`
- [ ] Migrate `getActualQueue()` -- remove `DispatchQueue.main.sync`
- [ ] Migrate `skipToIndex()` -- remove `DispatchQueue.main.sync`
- [ ] Migrate `getTracksById()` -- remove `DispatchQueue.main.sync`
- [ ] Migrate `getTracksNeedingUrls()` -- remove `DispatchQueue.main.sync`
- [ ] Migrate `getNextTracks()` -- remove `DispatchQueue.main.sync`
- [ ] Migrate `getCurrentTrackIndex()` -- remove `DispatchQueue.main.sync`
- [ ] Migrate `loadPlaylist()` -- remove `DispatchQueue.main.sync`
- [ ] Migrate `playNext()` -- use `withPlayerQueue`, throw if track not found
- [ ] Migrate `addToUpNext()` -- use `withPlayerQueue`, throw if track not found
- [ ] Add `removeFromPlayNext()`, `removeFromUpNext()`, `clearPlayNext()`, `clearUpNext()`
- [ ] Add `reorderTemporaryTrack()`
- [ ] Add `getPlayNextQueue()`, `getUpNextQueue()`
- [ ] Add `notifyTemporaryQueueChange()` and `onTemporaryQueueChange` listener
- [ ] Move KVO observer handling to `playerQueue`
- [ ] Move boundary time observer callbacks to `playerQueue`
- [ ] Keep `MediaSessionManager.updateNowPlayingInfo()` dispatching to main for `MPNowPlayingInfoCenter`
- [ ] Move `DownloadDatabase` to own serial queue for file I/O
- [ ] Remove all `Thread.isMainThread` guards (no longer needed)
- [ ] Remove all `DispatchQueue.main.sync` usage
- [ ] Add `deinit` cleanup for playerQueue resources

### Phase 3 -- TypeScript Spec Sync + Codegen

- [ ] Update `TrackPlayer.nitro.ts` with v2 API
- [ ] Update `PlayerQueue.nitro.ts` with v2 API
- [ ] Update `DownloadManager.nitro.ts` with v2 API
- [ ] Update `Equalizer.nitro.ts` with v2 API
- [ ] Update `AudioDevices.nitro.ts` with v2 API
- [ ] Update `AndroidAutoMediaLibrary.nitro.ts` with v2 API
- [ ] Run `npx nitrogen` to regenerate bridge code
- [ ] Update `HybridTrackPlayer.kt` bridge to call new suspend functions
- [ ] Update `HybridTrackPlayer.swift` bridge to call new async functions
- [ ] Update `HybridPlayerQueue.kt` and `.swift` bridges
- [ ] Update `HybridDownloadManager.kt` and `.swift` bridges
- [ ] Update `HybridEqualizer.kt` and `.swift` bridges
- [ ] Update React hooks if any API shapes changed (e.g. `play()` now returns Promise)
- [ ] Update `useActualQueue` hook to use `onTemporaryQueueChange`
- [ ] Run full example app on both platforms
- [ ] Run harness test suite

### Phase 4 -- Cleanup + Validation

- [ ] Audit for any remaining `DispatchQueue.main.sync` or `CountDownLatch`
- [ ] Audit for any remaining `synchronized` blocks that should be coroutine-based
- [ ] Verify Android Auto browsing + playback works on player thread
- [ ] Verify CarPlay command handlers dispatch to `playerQueue`
- [ ] Verify gapless playback still works (preload, buffer transitions)
- [ ] Verify lazy URL loading flow (onTracksNeedUpdate -> updateTracks -> queue rebuild)
- [ ] Verify download + offline playback flow
- [ ] Verify equalizer persists across track changes
- [ ] Load test: rapid play/pause/skip/seek under 100+ track playlists
- [ ] Measure actual latency of `getState()` calls (target: <2ms p99)

---

## Appendix A: Risk Assessment

| Risk | Mitigation |
|------|-----------|
| ExoPlayer on non-main looper may break some Media3 APIs | Media3 explicitly supports custom loopers via `setLooper()`. Verified in docs. |
| AVQueuePlayer off main thread may have edge cases | AVPlayer works on any serial queue. KVO callbacks must specify the queue. |
| Nitro bridge suspend/async mapping | Nitro maps `Promise<T>` to Kotlin `suspend fun` and Swift async automatically. Already verified. |
| Android Auto `MediaBrowserService` thread interaction | It runs on its own thread. Public methods on `TrackPlayerCore` will now suspend/queue instead of blocking. |
| CarPlay command handlers | Should dispatch to `playerQueue`. Test explicitly. |
| Removing timeouts = stuck player thread hangs forever | Add watchdog: if `withPlayerContext` takes >1s, log a warning. Player ops complete in <10ms normally. |
| `Promise<void>` for `play()`/`pause()` adds overhead | Overhead is ~0.1ms (one async dispatch). Unnoticeable to humans. Benefit: UI gets error feedback. |

## Appendix B: Full API Change Summary

### TrackPlayer Changes

| Method | v1 | v2 | Reason |
|--------|----|----|--------|
| `play()` | `void` | `Promise<void>` | Error feedback to UI |
| `pause()` | `void` | `Promise<void>` | Error feedback to UI |
| `seek()` | `void` | `Promise<void>` | Error feedback to UI |
| `skipToNext()` | `void` | `Promise<void>` | Error feedback to UI |
| `skipToPrevious()` | `void` | `Promise<void>` | Error feedback to UI |
| `setRepeatMode()` | `boolean` | `Promise<void>` | Throws on invalid; no boolean needed |
| `setVolume()` | `boolean` | `Promise<void>` | Throws on invalid; no boolean needed |
| `configure()` | `void` | `Promise<void>` | Confirms config applied on player thread |
| `playNext()` | `Promise<void>` (silent fail) | `Promise<void>` (rejects on not found) | Error reporting |
| `addToUpNext()` | `Promise<void>` (silent fail) | `Promise<void>` (rejects on not found) | Error reporting |
| NEW `removeFromPlayNext()` | -- | `Promise<boolean>` | New API |
| NEW `removeFromUpNext()` | -- | `Promise<boolean>` | New API |
| NEW `clearPlayNext()` | -- | `Promise<void>` | New API |
| NEW `clearUpNext()` | -- | `Promise<void>` | New API |
| NEW `reorderTemporaryTrack()` | -- | `Promise<boolean>` | New API |
| NEW `getPlayNextQueue()` | -- | `Promise<TrackItem[]>` | New API |
| NEW `getUpNextQueue()` | -- | `Promise<TrackItem[]>` | New API |
| NEW `onTemporaryQueueChange()` | -- | Event | New API |

### PlayerQueue Changes

| Method | v1 | v2 | Reason |
|--------|----|----|--------|
| `createPlaylist()` | `string` | `Promise<string>` | Confirms persistence |
| `deletePlaylist()` | `void` | `Promise<void>` | Error if not found |
| `updatePlaylist()` | `void` | `Promise<void>` | Error if not found |
| `addTrackToPlaylist()` | `void` | `Promise<void>` | Error feedback |
| `addTracksToPlaylist()` | `void` | `Promise<void>` | Error feedback |
| `removeTrackFromPlaylist()` | `void` | `Promise<void>` | Error feedback |
| `reorderTrackInPlaylist()` | `void` | `Promise<void>` | Error feedback |
| `loadPlaylist()` | `void` | `Promise<void>` | Confirms queue rebuilt |

### DownloadManager Changes

| Method | v1 | v2 | Reason |
|--------|----|----|--------|
| `isTrackDownloaded()` | `boolean` | `Promise<boolean>` | File I/O off main |
| `isPlaylistDownloaded()` | `boolean` | `Promise<boolean>` | File I/O off main |
| `isPlaylistPartiallyDownloaded()` | `boolean` | `Promise<boolean>` | File I/O off main |
| `getDownloadedTrack()` | sync | `Promise<T>` | File validation |
| `getAllDownloadedTracks()` | sync | `Promise<T>` | File validation |
| `getDownloadedPlaylist()` | sync | `Promise<T>` | File validation |
| `getAllDownloadedPlaylists()` | sync | `Promise<T>` | File validation |
| `getLocalPath()` | sync | `Promise<T>` | File validation |
| `syncDownloads()` | `number` | `Promise<number>` | Disk scan |
| `getEffectiveUrl()` | `string` | `Promise<string>` | May check file |

### Equalizer Changes

| Method | v1 | v2 | Reason |
|--------|----|----|--------|
| `setEnabled()` | `boolean` | `Promise<void>` | Rejects on failure |
| `setBandGain()` | `boolean` | `Promise<void>` | Player thread access |
| `setAllBandGains()` | `boolean` | `Promise<void>` | Player thread access |
| `applyPreset()` | `boolean` | `Promise<void>` | Player thread access |
| `saveCustomPreset()` | `boolean` | `Promise<void>` | Disk persistence |
| `deleteCustomPreset()` | `boolean` | `Promise<void>` | Disk persistence |
| `getBands()` | sync | `Promise<T>` | Player thread access |
| `getState()` | sync | `Promise<T>` | Player thread access |
| `reset()` | `void` | `Promise<void>` | Player thread access |

### AndroidAutoMediaLibrary Changes

| Method | v1 | v2 | Reason |
|--------|----|----|--------|
| `setMediaLibrary()` | `void` | `Promise<void>` | Confirms tree rebuilt |
| `clearMediaLibrary()` | `void` | `Promise<void>` | Confirms cleared |

### AudioDevices Changes

| Method | v1 | v2 | Reason |
|--------|----|----|--------|
| `setAudioDevice()` | `boolean` | `Promise<void>` | Confirms switch |
