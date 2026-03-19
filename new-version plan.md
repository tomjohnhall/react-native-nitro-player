# NitroPlayer v2 — Architecture Plan

## Table of Contents

1. [Current State Analysis](#1-current-state-analysis)
2. [Problems with Current Architecture](#2-problems-with-current-architecture)
3. [Proposed API Surface (All Specs)](#3-proposed-api-surface-all-specs)
4. [Temporary Queue Architecture (PlayNext / UpNext)](#4-temporary-queue-architecture-playnext--upnext)
5. [Listener and Callback Architecture](#5-listener-and-callback-architecture)
6. [Android Architecture (v2)](#6-android-architecture-v2)
7. [iOS Architecture (v2)](#7-ios-architecture-v2)
8. [Shared Patterns](#8-shared-patterns)
9. [Migration Checklist](#9-migration-checklist)

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

```kotlin
// CURRENT -- blocks Nitro worker thread for up to 5 seconds
fun getState(): PlayerState {
    if (Looper.myLooper() == handler.looper) return getStateInternal()
    val latch = CountDownLatch(1)
    var result: PlayerState? = null
    handler.post { result = getStateInternal(); latch.countDown() }
    latch.await(5, TimeUnit.SECONDS) // BLOCKS
    return result ?: fallback
}
```

**Affected:** `getState()`, `skipToIndex()`, `getActualQueue()`, `getTracksById()`, `getTracksNeedingUrls()`, `getNextTracks()`, `getCurrentTrackIndex()`, `setPlayBackSpeed()`, `getPlayBackSpeed()`

#### iOS -- `DispatchQueue.main.sync` (14 call sites in `TrackPlayerCore.swift`)

```swift
// CURRENT -- blocks calling thread until main finishes
func getState() -> PlayerState {
    if Thread.isMainThread { return getStateInternal() }
    var state: PlayerState!
    DispatchQueue.main.sync { state = self?.getStateInternal() ?? fallback } // BLOCKS
    return state
}
```

**Affected:** `play()`, `pause()`, `seek()`, `skipToNext()`, `skipToPrevious()`, `skipToIndex()`, `getState()`, `getActualQueue()`, `getTracksById()`, `getTracksNeedingUrls()`, `getNextTracks()`, `getCurrentTrackIndex()`, `loadPlaylist()`

#### Android -- `synchronized(this)` on database / playlist I/O

`DownloadDatabase.kt` wraps every method in `synchronized(this)` including `File.exists()` and JSON I/O. `PlaylistManager.kt` uses `synchronized(playlists)` around map access.

---

## 2. Problems with Current Architecture

### 2.1 Thread Blocking

| Problem | Impact | Severity |
|---------|--------|----------|
| `CountDownLatch.await(5s)` blocks Nitro worker thread | Nitro dispatches JS promises on a thread pool. Blocking a pool thread starves other promises. Can cascade to ANR. | **High** |
| `DispatchQueue.main.sync` blocks calling thread | Background thread parks until main run loop processes the block. Under heavy UI load, stalls the bridge. If ever called from main (despite guard), instant deadlock. | **High** |
| 5-second timeout returns fallback state | Timeout silently returns wrong data (PlayerState with zeros / STOPPED). JS has no idea data is stale. | **Medium** |
| `synchronized` around disk I/O | Every query holds lock while checking `File.exists()`. Concurrent callers block. | **Medium** |

### 2.2 Fire-and-Forget Hides Failures

Current `play()`, `pause()`, `seek()` are sync void. If native side fails (no player, invalid state), JS never finds out. Solution: make them `Promise<void>` -- rejects on failure, resolves on success. <1ms overhead.

### 2.3 Temporary Queue Issues

| Problem | Detail |
|---------|--------|
| No remove API | Once added, a track can only leave by being played or `playSong` (clears all) |
| No clear API | Cannot clear just playNext or just upNext |
| No reorder API | Users can't rearrange upcoming temp tracks |
| No query API | Can only see via `getActualQueue()` which merges everything |
| No dedicated event | Must poll to know when temp queue changed |
| Silent failures | `playNext(trackId)` logs error but resolves Promise -- caller never knows |
| `determineCurrentTemporaryType` scans both lists | O(n) scan on every transition could be avoided with explicit tracking |

### 2.4 Listener Issues

| Problem | Detail |
|---------|--------|
| `WeakReference(this)` owner is always `TrackPlayerCore` | Every listener uses the singleton as owner, so `isAlive` check is meaningless -- they never get GC'd while the singleton exists |
| `synchronized` lock during iteration | `notifyTrackChange` takes a lock on `synchronizedList` to snapshot, then posts. Under rapid events, creates contention |
| No unsubscribe | No way to remove a specific listener once added |
| Progress listener cleanup every 10th call | Arbitrary; dead listeners accumulate between cleanups |
| iOS `listenersQueue` (concurrent + barrier) exists but unused for most listeners | `onChangeTrackListeners` etc. are plain arrays accessed on main thread -- the concurrent queue is declared but most callbacks bypass it |
| Callbacks dispatched on main thread (Android `handler.post`) | Ties listener invocation to UI thread, which is wrong for Nitro bridge callbacks |

### 2.5 Concurrency Model Issues

| Problem | Detail |
|---------|--------|
| No dedicated player thread (Android) | ExoPlayer on main looper. Every player API contends with UI rendering. |
| No serial isolation (iOS) | All state mutated on main. Relies on runtime discipline. |
| Singleton + mutable state | ~15 mutable vars with no compile-time thread safety. |

---

## 3. Proposed API Surface (All Specs)

### 3.1 TrackPlayer

```typescript
export interface TrackPlayer
  extends HybridObject<{ android: 'kotlin'; ios: 'swift' }> {

  // ── PLAYBACK COMMANDS ──
  // All Promise<void>: resolves on success, rejects on failure.
  // Native: dispatch to player thread, do work, resolve/reject.

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

  // ── TEMPORARY QUEUE (PlayNext / UpNext) ──

  playNext(trackId: string): Promise<void>
  addToUpNext(trackId: string): Promise<void>
  removeFromPlayNext(trackId: string): Promise<boolean>
  removeFromUpNext(trackId: string): Promise<boolean>
  clearPlayNext(): Promise<void>
  clearUpNext(): Promise<void>
  reorderTemporaryTrack(trackId: string, newIndex: number): Promise<boolean>
  getPlayNextQueue(): Promise<TrackItem[]>
  getUpNextQueue(): Promise<TrackItem[]>

  // ── QUERIES ──

  getState(): Promise<PlayerState>
  getActualQueue(): Promise<TrackItem[]>
  getTracksById(trackIds: string[]): Promise<TrackItem[]>
  getTracksNeedingUrls(): Promise<TrackItem[]>
  getNextTracks(count: number): Promise<TrackItem[]>
  getCurrentTrackIndex(): Promise<number>
  getPlaybackSpeed(): Promise<number>

  // ── TRACK UPDATES ──

  updateTracks(tracks: TrackItem[]): Promise<void>

  // ── PURE READS (no player thread, atomic/volatile) ──

  getRepeatMode(): RepeatMode
  isAndroidAutoConnected(): boolean

  // ── EVENTS ──

  onChangeTrack(callback: (track: TrackItem, reason?: Reason) => void): () => void
  onPlaybackStateChange(callback: (state: TrackPlayerState, reason?: Reason) => void): () => void
  onSeek(callback: (position: number, totalDuration: number) => void): () => void
  onPlaybackProgressChange(
    callback: (position: number, totalDuration: number, isManuallySeeked?: boolean) => void
  ): () => void
  onTracksNeedUpdate(callback: (tracks: TrackItem[], lookahead: number) => void): () => void
  onAndroidAutoConnectionChange(callback: (connected: boolean) => void): () => void
  onTemporaryQueueChange(
    callback: (playNextQueue: TrackItem[], upNextQueue: TrackItem[]) => void
  ): () => void
}
```

**NOTE on `() => void` return from events:** Every `on*` method returns an unsubscribe function. This is the standard React pattern and enables proper cleanup in `useEffect`. If Nitro doesn't support returning functions, we can use a `subscriptionId` approach instead (see Section 5.5).

### 3.2 PlayerQueue

```typescript
export interface PlayerQueue
  extends HybridObject<{ android: 'kotlin'; ios: 'swift' }> {

  // ── PLAYLIST MANAGEMENT ──

  createPlaylist(name: string, description?: string, artwork?: string): Promise<string>
  deletePlaylist(playlistId: string): Promise<void>
  updatePlaylist(playlistId: string, name?: string, description?: string, artwork?: string): Promise<void>
  getPlaylist(playlistId: string): Playlist | null
  getAllPlaylists(): Playlist[]

  // ── TRACK MANAGEMENT ──

  addTrackToPlaylist(playlistId: string, track: TrackItem, index?: number): Promise<void>
  addTracksToPlaylist(playlistId: string, tracks: TrackItem[], index?: number): Promise<void>
  removeTrackFromPlaylist(playlistId: string, trackId: string): Promise<void>
  reorderTrackInPlaylist(playlistId: string, trackId: string, newIndex: number): Promise<void>

  // ── PLAYBACK ──

  loadPlaylist(playlistId: string): Promise<void>
  getCurrentPlaylistId(): string | null

  // ── EVENTS ──

  onPlaylistsChanged(callback: (playlists: Playlist[], operation?: QueueOperation) => void): () => void
  onPlaylistChanged(
    callback: (playlistId: string, playlist: Playlist, operation?: QueueOperation) => void
  ): () => void
}
```

### 3.3 DownloadManager

```typescript
export interface DownloadManager
  extends HybridObject<{ android: 'kotlin'; ios: 'swift' }> {

  // ── CONFIGURATION ──

  configure(config: DownloadConfig): void
  getConfig(): DownloadConfig

  // ── DOWNLOAD OPERATIONS ──

  downloadTrack(track: TrackItem, playlistId?: string): Promise<string>
  downloadPlaylist(playlistId: string, tracks: TrackItem[]): Promise<string[]>
  pauseDownload(downloadId: string): Promise<void>
  resumeDownload(downloadId: string): Promise<void>
  cancelDownload(downloadId: string): Promise<void>
  retryDownload(downloadId: string): Promise<void>
  pauseAllDownloads(): Promise<void>
  resumeAllDownloads(): Promise<void>
  cancelAllDownloads(): Promise<void>

  // ── DOWNLOAD QUERIES (in-memory, sync) ──

  getDownloadTask(downloadId: string): DownloadTask | null
  getActiveDownloads(): DownloadTask[]
  getQueueStatus(): DownloadQueueStatus
  isDownloading(trackId: string): boolean
  getDownloadState(trackId: string): DownloadState | null

  // ── DOWNLOADED CONTENT QUERIES (file I/O, async) ──

  isTrackDownloaded(trackId: string): Promise<boolean>
  isPlaylistDownloaded(playlistId: string): Promise<boolean>
  isPlaylistPartiallyDownloaded(playlistId: string): Promise<boolean>
  getDownloadedTrack(trackId: string): Promise<DownloadedTrack | null>
  getAllDownloadedTracks(): Promise<DownloadedTrack[]>
  getDownloadedPlaylist(playlistId: string): Promise<DownloadedPlaylist | null>
  getAllDownloadedPlaylists(): Promise<DownloadedPlaylist[]>
  getLocalPath(trackId: string): Promise<string | null>

  // ── DELETE ──

  deleteDownloadedTrack(trackId: string): Promise<void>
  deleteDownloadedPlaylist(playlistId: string): Promise<void>
  deleteAllDownloads(): Promise<void>

  // ── STORAGE ──

  getStorageInfo(): Promise<DownloadStorageInfo>
  syncDownloads(): Promise<number>

  // ── PLAYBACK SOURCE ──

  setPlaybackSourcePreference(preference: PlaybackSource): void
  getPlaybackSourcePreference(): PlaybackSource
  getEffectiveUrl(track: TrackItem): Promise<string>

  // ── EVENTS ──

  onDownloadProgress(callback: (progress: DownloadProgress) => void): () => void
  onDownloadStateChange(
    callback: (downloadId: string, trackId: string, state: DownloadState, error?: DownloadError) => void
  ): () => void
  onDownloadComplete(callback: (downloadedTrack: DownloadedTrack) => void): () => void
}
```

### 3.4 Equalizer

```typescript
export interface Equalizer
  extends HybridObject<{ android: 'kotlin'; ios: 'swift' }> {

  setEnabled(enabled: boolean): Promise<void>
  isEnabled(): boolean

  getBands(): Promise<EqualizerBand[]>
  setBandGain(bandIndex: number, gainDb: number): Promise<void>
  setAllBandGains(gains: number[]): Promise<void>
  getBandRange(): GainRange

  getPresets(): EqualizerPreset[]
  getBuiltInPresets(): EqualizerPreset[]
  getCustomPresets(): EqualizerPreset[]
  applyPreset(presetName: string): Promise<void>
  getCurrentPresetName(): string | null
  saveCustomPreset(name: string): Promise<void>
  deleteCustomPreset(name: string): Promise<void>

  getState(): Promise<EqualizerState>
  reset(): Promise<void>

  onEnabledChange(callback: (enabled: boolean) => void): () => void
  onBandChange(callback: (bands: EqualizerBand[]) => void): () => void
  onPresetChange(callback: (presetName: string | null) => void): () => void
}
```

### 3.5 AudioDevices (Android only)

```typescript
export interface AudioDevices extends HybridObject<{ android: 'kotlin' }> {
  getAudioDevices(): TAudioDevice[]
  setAudioDevice(deviceId: number): Promise<void>
}
```

### 3.6 AudioRoutePicker (iOS only)

```typescript
export interface AudioRoutePicker extends HybridObject<{ ios: 'swift' }> {
  showRoutePicker(): void
}
```

### 3.7 AndroidAutoMediaLibrary (Android only)

```typescript
export interface AndroidAutoMediaLibrary extends HybridObject<{ android: 'kotlin' }> {
  setMediaLibrary(libraryJson: string): Promise<void>
  clearMediaLibrary(): Promise<void>
}
```

---

## 4. Temporary Queue Architecture (PlayNext / UpNext)

### 4.1 Mental Model

The playback queue at any moment is a computed view of four sources:

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        ACTUAL PLAYBACK QUEUE                            │
│                                                                         │
│  [PLAYED]  [CURRENT]  [playNextStack]  [upNextQueue]  [REMAINDER]       │
│  ───────   ────────   ──────────────   ────────────   ──────────────    │
│  tracks    the one    LIFO stack       FIFO queue     remaining         │
│  before    playing    most-recent-     first-added-   playlist          │
│  current   right now  added plays      added plays    tracks after      │
│  index                first            first          current index     │
│                                                                         │
│  Source: original     Source: varies    Source: temp   Source: temp      │
│  playlist             (see state       list           list              │
│                       machine)                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

**Key invariant:** `currentTrackIndex` always refers to the position in the **original playlist** (`currentTracks`). It does NOT advance when temporary tracks play. It only advances when an original playlist track starts playing.

### 4.2 Data Structures

```
playNextStack: [TrackItem]    -- LIFO, insert at index 0
                                 Last added = first to play
                                 Removed after being played

upNextQueue:   [TrackItem]    -- FIFO, append to end
                                 First added = first to play
                                 Removed after being played

currentTemporaryType: enum    -- Tracks what SOURCE the currently
                                 playing track came from
    .none      = original playlist
    .playNext  = playNextStack[0]
    .upNext    = upNextQueue[0]
```

### 4.3 State Machine

The `currentTemporaryType` transitions are the heart of the system. Here is every possible transition:

```
                           ┌──────────────────┐
                           │   Initial State   │
                           │   type = .none     │
                           │   (playlist track) │
                           └────────┬──────────┘
                                    │
                    track finishes naturally
                    (AUTO transition / playerItemDidPlayToEndTime)
                                    │
                                    ▼
                   ┌────────────────────────────────┐
                   │  Decision: What plays next?     │
                   │                                 │
                   │  if playNextStack is NOT empty:  │
                   │    pop index 0                   │
                   │    type = .playNext              │──────┐
                   │                                 │      │
                   │  else if upNextQueue NOT empty:  │      │
                   │    pop index 0                   │      │
                   │    type = .upNext                │──┐   │
                   │                                 │  │   │
                   │  else:                           │  │   │
                   │    advance currentTrackIndex     │  │   │
                   │    type = .none                  │  │   │
                   └────────────────────────────────┘  │   │
                                                       │   │
              ┌────────────────────────────────────────┘   │
              │                                            │
              ▼                                            ▼
    ┌─────────────────┐                      ┌─────────────────┐
    │  type = .upNext   │                      │  type = .playNext │
    │  (upNext track    │                      │  (playNext track  │
    │   is playing)     │                      │   is playing)     │
    └────────┬─────────┘                      └────────┬──────────┘
             │                                          │
             │ track finishes                           │ track finishes
             │                                          │
             ▼                                          ▼
    ┌────────────────────────────────┐   ┌────────────────────────────────┐
    │  REMOVE from upNextQueue       │   │  REMOVE from playNextStack     │
    │  (it was played, so delete it) │   │  (it was played, so delete it) │
    │                                │   │                                │
    │  Then same Decision:           │   │  Then same Decision:           │
    │  playNext? upNext? playlist?   │   │  playNext? upNext? playlist?   │
    └────────────────────────────────┘   └────────────────────────────────┘
```

### 4.4 Track Lifecycle (CRITICAL: "Deleted After Song Played")

A temporary track goes through these states:

```
ADDED ──> QUEUED ──> PLAYING ──> REMOVED (deleted from list)
```

Detailed lifecycle for a `playNext` track:

```
1. User calls playNext("track-A")
   ├─ findTrackById("track-A") from playlists
   ├─ Insert at playNextStack[0]
   ├─ Rebuild native player queue (ExoPlayer/AVQueuePlayer)
   ├─ Emit onTemporaryQueueChange event
   └─ Resolve Promise<void>

2. Current song finishes playing
   ├─ onMediaItemTransition / playerItemDidPlayToEndTime fires
   ├─ Previous track (the one that just finished) is identified
   ├─ If previous was a temp track, remove it from its list
   ├─ determineCurrentTemporaryType() runs
   ├─ New current = playNextStack[0] = "track-A"
   ├─ currentTemporaryType = .playNext
   ├─ Emit onChangeTrack("track-A", reason: .end)
   ├─ Emit onTemporaryQueueChange (playNextStack is now shorter)
   └─ currentTrackIndex stays the same (not advanced)

3. "track-A" finishes playing
   ├─ onMediaItemTransition / playerItemDidPlayToEndTime fires
   ├─ Previous track = "track-A"
   ├─ "track-A" found in playNextStack → REMOVE it (DELETED)
   ├─ determineCurrentTemporaryType() runs
   ├─ If more playNext/upNext exist → play next temp
   │   Otherwise → advance currentTrackIndex, type = .none
   ├─ Emit onChangeTrack(nextTrack, reason: .end)
   └─ Emit onTemporaryQueueChange (track-A gone)
```

Same lifecycle applies to `upNext` tracks.

### 4.5 Every Edge Case and How to Handle It

#### 4.5.1 User skips forward (`skipToNext`)

```
State: type = .none, playing original track at index 5
playNextStack = [A, B]
upNextQueue = [C]

Action: skipToNext()

1. Current track at index 5 does NOT get removed (it's an original track)
2. Next track = playNextStack[0] = A (LIFO, most recently added)
3. type = .playNext
4. A starts playing
5. Emit onChangeTrack(A, reason: .skip)
6. Emit onTemporaryQueueChange (still [A, B] -- A hasn't finished yet)
7. currentTrackIndex stays at 5
```

#### 4.5.2 User skips forward while playing a temp track

```
State: type = .playNext, playing A
playNextStack = [A, B]    (A at index 0 is currently playing)
upNextQueue = [C]

Action: skipToNext()

1. A is currently playing temp → REMOVE A from playNextStack
2. playNextStack = [B]
3. Next track = playNextStack[0] = B
4. type = .playNext
5. B starts playing
6. Emit onChangeTrack(B, reason: .skip)
7. Emit onTemporaryQueueChange([B], [C])
```

#### 4.5.3 User skips backward (`skipToPrevious`)

Two sub-cases based on position within the track:

```
Case A: More than 2 seconds into the track
  → Seek to beginning of current track (restart)
  → No temp track changes

Case B: Less than 2 seconds into the track AND playing a temp track
  → REMOVE current temp track from its list
  → type = .none
  → Go back to original playlist track at currentTrackIndex
  → Emit onChangeTrack and onTemporaryQueueChange

Case C: Less than 2 seconds AND playing original track
  → Go to previous original track (currentTrackIndex - 1)
  → No temp track changes
```

#### 4.5.4 User calls `skipToIndex(index)` targeting a temp track

The actual queue is: `[played] + [current] + [playNext] + [upNext] + [remainder]`

When targeting an index in the playNext section:
```
1. Remove all playNext tracks BEFORE the target (they're being skipped over)
2. The target becomes the new current
3. type = .playNext
4. Rebuild native queue
```

When targeting an index in the upNext section:
```
1. Clear ALL playNext tracks (skipping past them)
2. Remove all upNext tracks BEFORE the target
3. The target becomes the new current
4. type = .upNext
5. Rebuild native queue
```

When targeting an index in the remainder section:
```
1. Clear ALL playNext and upNext tracks (user chose to jump to playlist)
2. type = .none
3. Play from the target playlist index
```

#### 4.5.5 User calls `playSong(songId)` directly

```
1. Clear ALL temp tracks: playNextStack.clear(), upNextQueue.clear()
2. type = .none
3. Find song in playlists
4. Load playlist and play from that index
5. Emit onTemporaryQueueChange([], [])
```

#### 4.5.6 User calls `loadPlaylist(playlistId)`

```
1. Clear ALL temp tracks
2. type = .none
3. Load new playlist
4. Emit onTemporaryQueueChange([], [])
```

#### 4.5.7 Repeat modes interaction

```
RepeatMode.TRACK:
  - When track finishes, seek to 0 and replay
  - Do NOT remove temp track (it loops infinitely)
  - Do NOT advance to next temp/playlist track
  - User must skipToNext to break out of track repeat

RepeatMode.PLAYLIST:
  - When STATE_ENDED / queue exhausted:
  - Clear ALL temp tracks
  - type = .none
  - Rebuild queue from index 0
  - Start playing first track
  - Emit onTemporaryQueueChange([], [])

RepeatMode.OFF:
  - When last track in queue finishes → STOP
  - Clear temp tracks
  - Emit stopped state
```

#### 4.5.8 `removeFromPlayNext` while that track is currently playing

```
State: type = .playNext, playing track A
playNextStack = [A, B]

Action: removeFromPlayNext("A")

1. A is found in playNextStack
2. REMOVE A from playNextStack → [B]
3. BUT A is currently playing — we don't stop it mid-play
4. When A finishes naturally, transition logic won't find it
   in playNextStack (already removed), so it moves to next:
   playNextStack[0] = B
5. Return true (track was found and removed from list)
6. Emit onTemporaryQueueChange([B], upNextQueue)

Alternative behavior (stricter): skip to next immediately.
Recommended: let it finish playing. The "remove" means
"don't play it AGAIN if it appears later" and cleans the queue view.
```

#### 4.5.9 Same track ID added to both playNext and upNext

```
Allowed. Each list is independent. When the track finishes in
playNext, it's removed from playNextStack. The copy in upNextQueue
remains and will play when its turn comes.

This matches user intent: "play this next AND also add to my up-next"
```

#### 4.5.10 Track URL update while temp track is queued

```
When updateTracks([...]) is called:
1. Update the TrackItem in ALL playlists (existing behavior)
2. ALSO update matching items in playNextStack and upNextQueue
3. Rebuild native player queue with new URLs
4. Emit onTemporaryQueueChange (items have updated URLs)
```

### 4.6 Reorder Within Temporary Queue

The `reorderTemporaryTrack(trackId, newIndex)` API operates on a **virtual combined queue**:

```
Virtual temp queue = playNextStack + upNextQueue
Index:               0..pn-1         pn..pn+un-1

reorderTemporaryTrack("C", 0):
  Before: playNext=[A, B], upNext=[C, D]
  Virtual: [A, B, C, D]
  Move C from index 2 to index 0
  After virtual: [C, A, B, D]
  Split back: playNext=[C, A, B], upNext=[D]
  Rebuild native queue

reorderTemporaryTrack("A", 3):
  Before: playNext=[A, B], upNext=[C, D]
  Virtual: [A, B, C, D]
  Move A from index 0 to index 3
  After virtual: [B, C, D, A]
  Split: playNext stays the first N items? No -- we need a rule.
```

**Split rule:** After reorder, the first `playNextStack.count` items stay in playNext, the rest go to upNext. Or simpler: once reordered, everything becomes a single `upNextQueue` (since the user explicitly chose the order, LIFO/FIFO distinction is moot).

**Recommended:** Keep it simple. Reorder within the virtual combined list. Maintain the split point at the original playNext count unless the track crosses the boundary, in which case adjust both lists.

### 4.7 Thread Safety (CRITICAL)

All temporary queue state lives exclusively on the **player thread/queue**:

```
Android: playerHandler (HandlerThread)
iOS:     playerQueue (serial DispatchQueue)
```

**No locks needed.** All access is serialized by the single-threaded context:

- `playNextStack` -- read/write only on player thread
- `upNextQueue` -- read/write only on player thread
- `currentTemporaryType` -- read/write only on player thread
- `currentTrackIndex` -- read/write only on player thread

External callers (JS bridge, Android Auto, CarPlay) go through `withPlayerContext` / `withPlayerQueue` which suspends them until the player thread processes the request.

### 4.8 Native Implementation Patterns

#### Android (Kotlin coroutines)

```kotlin
suspend fun playNext(trackId: String) = withPlayerContext {
    val track = findTrackById(trackId)
        ?: throw IllegalArgumentException("Track $trackId not found in any playlist")

    playNextStack.add(0, track)

    if (::player.isInitialized && player.currentMediaItem != null) {
        rebuildQueueFromCurrentPosition()
    }
    notifyTemporaryQueueChange()
}

suspend fun removeFromPlayNext(trackId: String): Boolean = withPlayerContext {
    val idx = playNextStack.indexOfFirst { it.id == trackId }
    if (idx < 0) return@withPlayerContext false

    playNextStack.removeAt(idx)

    if (::player.isInitialized && player.currentMediaItem != null) {
        rebuildQueueFromCurrentPosition()
    }
    notifyTemporaryQueueChange()
    true
}

suspend fun clearPlayNext() = withPlayerContext {
    if (playNextStack.isEmpty()) return@withPlayerContext
    playNextStack.clear()
    rebuildQueueFromCurrentPosition()
    notifyTemporaryQueueChange()
}

suspend fun getPlayNextQueue(): List<TrackItem> = withPlayerContext {
    playNextStack.toList()
}

suspend fun getUpNextQueue(): List<TrackItem> = withPlayerContext {
    upNextQueue.toList()
}
```

#### iOS (Swift async)

```swift
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
        guard let idx = self.playNextStack.firstIndex(where: { $0.id == trackId }) else {
            return false
        }
        self.playNextStack.remove(at: idx)

        if self.player?.currentItem != nil {
            self.rebuildAVQueueFromCurrentPosition()
        }
        self.notifyTemporaryQueueChange()
        return true
    }
}

func getPlayNextQueue() async -> [TrackItem] {
    await withPlayerQueue { self.playNextStack }
}
```

---

## 5. Listener and Callback Architecture

### 5.1 Current Problems (Detailed)

```kotlin
// PROBLEM 1: WeakReference owner is always the singleton
private data class WeakCallbackBox<T>(
    private val ownerRef: WeakReference<Any>,  // Always WeakReference(this) = TrackPlayerCore
    val callback: T,
) {
    val isAlive: Boolean get() = ownerRef.get() != null  // Always true while singleton exists
}

// PROBLEM 2: add uses synchronizedList, notify takes synchronized lock
fun addOnChangeTrackListener(callback: ...) {
    onChangeTrackListeners.add(WeakCallbackBox(WeakReference(this), callback))
    // ↑ Added from background thread (Nitro bridge)
}

private fun notifyTrackChange(track: TrackItem, reason: Reason?) {
    val liveCallbacks = synchronized(onChangeTrackListeners) {
        // ↑ Takes lock on main thread during rapid events
        onChangeTrackListeners.removeAll { !it.isAlive }  // Always alive (problem 1)
        onChangeTrackListeners.map { it.callback }
    }
    handler.post { /* invoke callbacks on main */ }
    // ↑ Dispatches to main thread, but Nitro bridge callbacks
    //   should be invoked on the Nitro callback thread, not main
}

// PROBLEM 3: No way to remove a specific listener
// Once registered, a callback stays forever (until WeakRef dies, which never happens)

// PROBLEM 4: Progress callback cleanup is arbitrary
if (++progressNotifyCounter % 10 == 0) {
    onPlaybackProgressChangeListeners.removeAll { !it.isAlive }  // Every 10th call
}
```

### 5.2 v2 Listener Design

#### Core Principles

1. **Typed listener ID** -- Every registration returns a unique ID. The ID is used to unsubscribe.
2. **No WeakReference** -- Explicit unsubscribe instead of GC-based cleanup. WeakRef was broken anyway.
3. **Player thread dispatch** -- All listener invocations happen on the player thread. Nitro bridge handles cross-thread delivery to JS.
4. **CopyOnWriteArrayList (Android) / Copy-on-write array (iOS)** -- Lock-free iteration during notify. Writes (add/remove) are rare; reads (notify) are frequent.
5. **Single generic ListenerRegistry** -- DRY implementation shared by all event types.

#### ListenerRegistry (Android)

```kotlin
class ListenerRegistry<T> {
    private data class Entry<T>(val id: Long, val callback: T)

    private val listeners = CopyOnWriteArrayList<Entry<T>>()
    private val nextId = AtomicLong(0)

    /** Register a callback. Returns a unique listener ID for unsubscribe. */
    fun add(callback: T): Long {
        val id = nextId.incrementAndGet()
        listeners.add(Entry(id, callback))
        return id
    }

    /** Unsubscribe by ID. Returns true if found. */
    fun remove(id: Long): Boolean {
        return listeners.removeAll { it.id == id }
    }

    /** Remove all listeners. */
    fun clear() {
        listeners.clear()
    }

    /** Iterate over all listeners. Safe to call during concurrent add/remove. */
    inline fun forEach(action: (T) -> Unit) {
        for (entry in listeners) {
            try {
                action(entry.callback)
            } catch (e: Exception) {
                NitroPlayerLogger.log("ListenerRegistry") {
                    "Error in listener ${entry.id}: ${e.message}"
                }
            }
        }
    }

    val size: Int get() = listeners.size
    val isEmpty: Boolean get() = listeners.isEmpty()
}
```

#### ListenerRegistry (iOS)

```swift
final class ListenerRegistry<T> {
    private struct Entry {
        let id: Int64
        let callback: T
    }

    // Serial queue protects the mutable array.
    // Reads during notify use a snapshot taken inside the queue.
    private let queue = DispatchQueue(label: "com.nitroplayer.listeners.\(T.self)")
    private var listeners: [Entry] = []
    private var nextId: Int64 = 0

    func add(_ callback: T) -> Int64 {
        var id: Int64 = 0
        queue.sync {
            nextId += 1
            id = nextId
            listeners.append(Entry(id: id, callback: callback))
        }
        return id
    }

    func remove(id: Int64) -> Bool {
        var found = false
        queue.sync {
            if let idx = listeners.firstIndex(where: { $0.id == id }) {
                listeners.remove(at: idx)
                found = true
            }
        }
        return found
    }

    func clear() {
        queue.sync { listeners.removeAll() }
    }

    /// Returns a snapshot for safe iteration outside the lock.
    func snapshot() -> [T] {
        queue.sync { listeners.map { $0.callback } }
    }

    func forEach(_ action: (T) -> Void) {
        let snap = snapshot()
        for callback in snap {
            action(callback)
        }
    }
}
```

### 5.3 Usage in TrackPlayerCore

#### Android

```kotlin
class TrackPlayerCore private constructor(context: Context) {

    // Typed registries -- one per event
    private val onChangeTrackListeners = ListenerRegistry<(TrackItem, Reason?) -> Unit>()
    private val onPlaybackStateChangeListeners = ListenerRegistry<(TrackPlayerState, Reason?) -> Unit>()
    private val onSeekListeners = ListenerRegistry<(Double, Double) -> Unit>()
    private val onProgressListeners = ListenerRegistry<(Double, Double, Boolean?) -> Unit>()
    private val onTracksNeedUpdateListeners = ListenerRegistry<(List<TrackItem>, Int) -> Unit>()
    private val onTemporaryQueueChangeListeners = ListenerRegistry<(List<TrackItem>, List<TrackItem>) -> Unit>()
    private val onAndroidAutoConnectionListeners = ListenerRegistry<(Boolean) -> Unit>()

    // Registration (called from HybridTrackPlayer bridge)
    fun addOnChangeTrackListener(callback: (TrackItem, Reason?) -> Unit): Long =
        onChangeTrackListeners.add(callback)

    fun removeOnChangeTrackListener(id: Long): Boolean =
        onChangeTrackListeners.remove(id)

    // Notification (called from player thread -- no locks needed)
    private fun notifyTrackChange(track: TrackItem, reason: Reason?) {
        onChangeTrackListeners.forEach { it(track, reason) }
    }

    private fun notifyTemporaryQueueChange() {
        val pn = playNextStack.toList()
        val un = upNextQueue.toList()
        onTemporaryQueueChangeListeners.forEach { it(pn, un) }
    }

    private fun notifyPlaybackProgress(position: Double, duration: Double, isSeeked: Boolean?) {
        onProgressListeners.forEach { it(position, duration, isSeeked) }
    }
}
```

#### iOS

```swift
class TrackPlayerCore: NSObject {

    private let onChangeTrackListeners = ListenerRegistry<(TrackItem, Reason?) -> Void>()
    private let onPlaybackStateChangeListeners = ListenerRegistry<(TrackPlayerState, Reason?) -> Void>()
    private let onSeekListeners = ListenerRegistry<(Double, Double) -> Void>()
    private let onProgressListeners = ListenerRegistry<(Double, Double, Bool?) -> Void>()
    private let onTracksNeedUpdateListeners = ListenerRegistry<([TrackItem], Int) -> Void>()
    private let onTemporaryQueueChangeListeners = ListenerRegistry<([TrackItem], [TrackItem]) -> Void>()

    func addOnChangeTrackListener(_ callback: @escaping (TrackItem, Reason?) -> Void) -> Int64 {
        onChangeTrackListeners.add(callback)
    }

    func removeOnChangeTrackListener(id: Int64) -> Bool {
        onChangeTrackListeners.remove(id: id)
    }

    private func notifyTrackChange(_ track: TrackItem, _ reason: Reason?) {
        onChangeTrackListeners.forEach { $0(track, reason) }
    }

    private func notifyTemporaryQueueChange() {
        let pn = playNextStack
        let un = upNextQueue
        onTemporaryQueueChangeListeners.forEach { $0(pn, un) }
    }
}
```

### 5.4 HybridTrackPlayer Bridge -- Connecting JS to Native Listeners

The bridge layer translates between the Nitro callback interface and the `ListenerRegistry`.

#### Android (HybridTrackPlayer.kt)

```kotlin
class HybridTrackPlayer : HybridTrackPlayerSpec() {
    private val core = TrackPlayerCore.getInstance(context)

    // Store listener IDs so the bridge can clean up when the HybridObject is GC'd
    private val activeListenerIds = mutableListOf<Pair<String, Long>>()

    override fun onChangeTrack(callback: (TrackItem, Reason?) -> Unit) {
        val id = core.addOnChangeTrackListener(callback)
        activeListenerIds.add("onChangeTrack" to id)
    }

    override fun onTemporaryQueueChange(callback: (List<TrackItem>, List<TrackItem>) -> Unit) {
        val id = core.addOnTemporaryQueueChangeListener(callback)
        activeListenerIds.add("onTemporaryQueueChange" to id)
    }

    // Called when HybridObject is destroyed (Nitro GC bridge)
    override fun finalize() {
        for ((type, id) in activeListenerIds) {
            when (type) {
                "onChangeTrack" -> core.removeOnChangeTrackListener(id)
                "onTemporaryQueueChange" -> core.removeOnTemporaryQueueChangeListener(id)
                // ... etc
            }
        }
        activeListenerIds.clear()
    }
}
```

#### iOS (HybridTrackPlayer.swift)

```swift
class HybridTrackPlayer: HybridTrackPlayerSpec {
    private let core = TrackPlayerCore.shared
    private var activeListenerIds: [(String, Int64)] = []

    func onChangeTrack(callback: @escaping (TrackItem, Reason?) -> Void) {
        let id = core.addOnChangeTrackListener(callback)
        activeListenerIds.append(("onChangeTrack", id))
    }

    func onTemporaryQueueChange(callback: @escaping ([TrackItem], [TrackItem]) -> Void) {
        let id = core.addOnTemporaryQueueChangeListener(callback)
        activeListenerIds.append(("onTemporaryQueueChange", id))
    }

    deinit {
        for (type, id) in activeListenerIds {
            switch type {
            case "onChangeTrack": _ = core.removeOnChangeTrackListener(id: id)
            case "onTemporaryQueueChange": _ = core.removeOnTemporaryQueueChangeListener(id: id)
            default: break
            }
        }
    }
}
```

### 5.5 Unsubscribe from JS Side

If Nitro supports returning values from `on*` methods, we can return the listener ID:

```typescript
// Option A: Return unsubscribe function (preferred)
onChangeTrack(callback: (track: TrackItem, reason?: Reason) => void): () => void

// Usage:
const unsubscribe = trackPlayer.onChangeTrack((track, reason) => { ... })
// Later:
unsubscribe()
```

If Nitro does NOT support returning functions from `on*` methods, we add explicit unsubscribe APIs:

```typescript
// Option B: Explicit unsubscribe methods
onChangeTrack(callback: (track: TrackItem, reason?: Reason) => void): void
removeOnChangeTrackListener(): void

// Or with subscription IDs:
onChangeTrack(callback: ...): number  // returns subscriptionId
removeListener(subscriptionId: number): void
```

**Recommendation:** Start with Option B (explicit unsubscribe) as it's guaranteed to work with Nitro's codegen. The HybridObject cleanup (`finalize`/`deinit`) handles the common case automatically. The explicit method is for React hooks that need to clean up on unmount.

### 5.6 All Listener Types

| Event | Callback Signature | Fires When | Thread |
|-------|-------------------|------------|--------|
| `onChangeTrack` | `(track: TrackItem, reason?: Reason)` | New track starts playing (any source) | Player |
| `onPlaybackStateChange` | `(state: TrackPlayerState, reason?: Reason)` | Playing/paused/stopped state changes | Player |
| `onSeek` | `(position: Double, totalDuration: Double)` | User or programmatic seek completes | Player |
| `onPlaybackProgressChange` | `(position: Double, duration: Double, isSeeked?: Bool)` | Every 250ms during playback | Player |
| `onTracksNeedUpdate` | `(tracks: [TrackItem], lookahead: Int)` | Upcoming tracks have empty URLs | Player |
| `onTemporaryQueueChange` | `(playNext: [TrackItem], upNext: [TrackItem])` | Any mutation to temp queues | Player |
| `onAndroidAutoConnectionChange` | `(connected: Bool)` | Android Auto connects/disconnects | Player |
| `onPlaylistsChanged` | `(playlists: [Playlist], op?: QueueOperation)` | Any playlist created/deleted | Player |
| `onPlaylistChanged` | `(playlistId: String, playlist: Playlist, op?: QueueOperation)` | Specific playlist modified | Player |
| `onDownloadProgress` | `(progress: DownloadProgress)` | Download progress update | IO |
| `onDownloadStateChange` | `(downloadId, trackId, state, error?)` | Download state transition | IO |
| `onDownloadComplete` | `(downloadedTrack: DownloadedTrack)` | Download finished successfully | IO |
| `onEnabledChange` | `(enabled: Bool)` | Equalizer toggled | Player |
| `onBandChange` | `(bands: [EqualizerBand])` | Any EQ band gain changed | Player |
| `onPresetChange` | `(presetName: String?)` | EQ preset applied/cleared | Player |

### 5.7 Listener Dispatch Thread Rules

```
RULE: Listeners are always invoked on the thread/queue that owns the data.

Player events (track, state, seek, progress, temp queue):
  Android: Invoked inside playerHandler.post { }
  iOS:     Invoked inside playerQueue.async { }
  Nitro bridge handles delivery to JS thread.

Download events (progress, state, complete):
  Android: Invoked on Dispatchers.IO coroutine
  iOS:     Invoked on download queue

Playlist events:
  Android: Invoked on playerHandler (mutations go through player thread)
  iOS:     Invoked on playerQueue

NEVER invoke listeners on the main/UI thread.
The Nitro bridge handles the JS thread hop automatically.
```

### 5.8 Progress Listener Optimization

The progress callback fires every 250ms. With `CopyOnWriteArrayList` on Android and snapshot-based iteration on iOS, this is already fast (no lock contention). But we can optimize further:

```kotlin
// Android: Pre-allocated scratch list to avoid allocations in hot path
private val progressCallbackScratch = ArrayList<(Double, Double, Boolean?) -> Unit>(4)

private fun notifyPlaybackProgress(position: Double, duration: Double, isSeeked: Boolean?) {
    if (onProgressListeners.isEmpty) return

    // CopyOnWriteArrayList iteration creates a snapshot automatically
    onProgressListeners.forEach { it(position, duration, isSeeked) }
}
```

```swift
// iOS: Direct iteration on snapshot (already O(1) copy for small arrays)
private func notifyPlaybackProgress(_ position: Double, _ duration: Double, _ isSeeked: Bool?) {
    onProgressListeners.forEach { $0(position, duration, isSeeked) }
}
```

For **very** high frequency events (250ms is fine, but if we ever go to 60fps):
- Batch multiple updates and deliver once per frame
- Use a ring buffer instead of listener invocation

Not needed for 250ms interval. Keep it simple.

---

## 6. Android Architecture (v2)

### 6.1 Dedicated Player Looper Thread

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
        }
    }
}
```

### 6.2 `withPlayerContext` -- Replaces All Latches

```kotlin
private suspend fun <T> withPlayerContext(block: () -> T): T {
    if (Looper.myLooper() == playerThread.looper) return block()
    return suspendCancellableCoroutine { cont ->
        val runnable = Runnable {
            try { cont.resume(block()) }
            catch (e: Exception) { cont.resumeWithException(e) }
        }
        playerHandler.post(runnable)
        cont.invokeOnCancellation { playerHandler.removeCallbacks(runnable) }
    }
}
```

### 6.3 Supporting Infrastructure

```
PlaylistManager:    ConcurrentHashMap + save debounced on Dispatchers.IO
DownloadDatabase:   Mutex for in-memory map + disk I/O on Dispatchers.IO
EqualizerCore:      Operations on player thread (EQ nodes tied to ExoPlayer)
MediaSessionManager: On player thread. Metadata updates forwarded to system.
MediaBrowserService: Own thread (Android Auto requirement). Reads via suspend.
```

### 6.4 Architecture Diagram

```
                     JS Thread (Hermes)
                     trackPlayer.play() -> Promise<void>
                            |
                            | Nitro bridge (JNI)
                            v
                  HybridTrackPlayer (Kotlin)
                  ┌──────────────────────────┐
                  │ Maps Promise -> suspend   │
                  │ Stores activeListenerIds  │
                  │ Cleans up on finalize()   │
                  └────────────┬─────────────┘
                               |
                               v
             ┌──────────────────────────────────┐
             │       TrackPlayerCore             │
             │                                   │
             │  ┌─────────────────────────────┐  │
             │  │ Player Thread (HandlerThread)│  │
             │  │                              │  │
             │  │ ExoPlayer                    │  │
             │  │ currentTracks                │  │
             │  │ playNextStack                │  │
             │  │ upNextQueue                  │  │
             │  │ currentTrackIndex            │  │
             │  │ currentTemporaryType         │  │
             │  │                              │  │
             │  │ ALL reads/writes here        │  │
             │  │ Zero locks needed            │  │
             │  └─────────────────────────────┘  │
             │                                   │
             │  Listeners: ListenerRegistry<T>   │
             │  ├── CopyOnWriteArrayList         │
             │  ├── AtomicLong IDs               │
             │  ├── add() returns Long           │
             │  └── remove(id) unsubscribes      │
             │                                   │
             │  Notify: direct invocation on     │
             │  player thread. No locks. No post.│
             └──────────────────────────────────┘
```

---

## 7. iOS Architecture (v2)

### 7.1 Dedicated Serial Queue

```swift
class TrackPlayerCore: NSObject {
    private let playerQueue = DispatchQueue(
        label: "com.nitroplayer.player", qos: .userInitiated)
    private var player: AVQueuePlayer?

    private func setupPlayer() {
        playerQueue.async { [weak self] in
            self?.player = AVQueuePlayer()
        }
    }
}
```

### 7.2 `withPlayerQueue` -- Replaces All `DispatchQueue.main.sync`

```swift
private func withPlayerQueue<T>(_ block: @escaping () throws -> T) async throws -> T {
    try await withCheckedThrowingContinuation { continuation in
        playerQueue.async {
            do { continuation.resume(returning: try block()) }
            catch { continuation.resume(throwing: error) }
        }
    }
}

private func withPlayerQueue<T>(_ block: @escaping () -> T) async -> T {
    await withCheckedContinuation { continuation in
        playerQueue.async { continuation.resume(returning: block()) }
    }
}
```

### 7.3 Supporting Infrastructure

```
PlaylistManager:    Own serial queue for dict + save
DownloadDatabase:   Own serial queue for map + file I/O
EqualizerCore:      Operations on playerQueue (AVAudioEngine nodes)
MediaSessionManager: Gathers data on playerQueue, dispatches to main ONLY
                     for MPNowPlayingInfoCenter updates
```

### 7.4 Architecture Diagram

```
                     JS Thread (Hermes)
                     trackPlayer.play() -> Promise<void>
                            |
                            | Nitro bridge (ObjC++)
                            v
                  HybridTrackPlayer (Swift)
                  ┌──────────────────────────┐
                  │ Maps Promise -> async     │
                  │ Stores activeListenerIds  │
                  │ Cleans up on deinit       │
                  └────────────┬─────────────┘
                               |
                               v
             ┌──────────────────────────────────┐
             │       TrackPlayerCore             │
             │                                   │
             │  ┌─────────────────────────────┐  │
             │  │ playerQueue (serial)         │  │
             │  │ QoS: .userInitiated          │  │
             │  │                              │  │
             │  │ AVQueuePlayer                │  │
             │  │ currentTracks                │  │
             │  │ playNextStack                │  │
             │  │ upNextQueue                  │  │
             │  │ currentTrackIndex            │  │
             │  │ currentTemporaryType         │  │
             │  │                              │  │
             │  │ ALL reads/writes here        │  │
             │  │ Serialized = zero locks      │  │
             │  └─────────────────────────────┘  │
             │                                   │
             │  ┌─────────────────────────────┐  │
             │  │ preloadQueue (serial)        │  │
             │  │ QoS: .utility                │  │
             │  │ AVURLAsset preloading        │  │
             │  └─────────────────────────────┘  │
             │                                   │
             │  Listeners: ListenerRegistry<T>   │
             │  ├── Own serial queue per registry│
             │  ├── Int64 IDs                    │
             │  ├── add() returns Int64          │
             │  └── remove(id:) unsubscribes     │
             │                                   │
             │  Notify: snapshot() then iterate  │
             │  on playerQueue. No contention.   │
             └──────────────────────────────────┘
```

---

## 8. Shared Patterns

### 8.1 The "withPlayerContext" Pattern

Both platforms: post to dedicated serial context, suspend caller, resume with result.

| Platform | Mechanism | Caller |
|----------|-----------|--------|
| Android | `suspendCancellableCoroutine` + `playerHandler.post` | Suspended coroutine |
| iOS | `withCheckedContinuation` + `playerQueue.async` | Suspended async fn |

### 8.2 Thread Ownership Map

| State | Owner | Android | iOS |
|-------|-------|---------|-----|
| ExoPlayer / AVQueuePlayer | Player thread/queue | `playerHandler` | `playerQueue` |
| `currentTracks`, `currentTrackIndex` | Player thread/queue | `playerHandler` | `playerQueue` |
| `playNextStack`, `upNextQueue` | Player thread/queue | `playerHandler` | `playerQueue` |
| `currentTemporaryType` | Player thread/queue | `playerHandler` | `playerQueue` |
| `currentRepeatMode` | Atomic | `@Volatile` | `playerQueue` |
| Playlist map | Own context | `ConcurrentHashMap` | Own serial queue |
| Download records | Own context | `Mutex` | Own serial queue |
| EQ band state | Player thread/queue | `playerHandler` | `playerQueue` |
| MPNowPlayingInfo | Main thread | N/A | `DispatchQueue.main.async` |
| ListenerRegistry | Own internal lock | `CopyOnWriteArrayList` | Own serial queue |

### 8.3 Error Propagation

```
Native throws  →  Nitro bridge  →  JS Promise.reject  →  .catch() / try-catch

Android: throw IllegalStateException("msg")  →  JS Error("msg")
iOS:     throw NitroPlayerError.xyz           →  JS Error("msg")
```

### 8.4 Performance Characteristics

| Operation | v1 Latency | v2 Latency | Why |
|-----------|-----------|-----------|-----|
| `play()` | 0-5000ms (latch) | <1ms | Async dispatch |
| `getState()` | 0-5000ms (latch) | <1ms | Suspend + post |
| `playNext()` | <1ms (already async) | <1ms | Same, now rejects on error |
| `notifyTrackChange()` | Lock + post to main | Direct invoke on player thread | No lock, no thread hop |
| `notifyProgress()` | Lock + post to main | COWL iterate on player thread | No lock, no thread hop |
| UI thread impact | Blocked by player ops | **Zero** | Player off main |

---

## 9. Migration Checklist

### Phase 1 -- Core Infrastructure

- [ ] Add `kotlinx-coroutines-android` dependency
- [ ] Create `ListenerRegistry<T>` class (Android)
- [ ] Create `ListenerRegistry<T>` class (iOS)
- [ ] Create `NitroPlayerError` enum (iOS)

### Phase 2 -- Android: Player Thread + Coroutines

- [ ] Create `HandlerThread("NitroPlayer")` in `TrackPlayerCore`
- [ ] Initialize ExoPlayer with `.setLooper(playerThread.looper)`
- [ ] Create `playerDispatcher`, `scope`, `withPlayerContext`
- [ ] Migrate all 10 latch methods to `withPlayerContext`
- [ ] Migrate fire-and-forget commands to `withPlayerContext` with error reporting
- [ ] Replace `handler` (main looper) with `playerHandler` throughout
- [ ] Replace listener arrays with `ListenerRegistry<T>` instances
- [ ] Add temp queue APIs: `removeFromPlayNext/UpNext`, `clearPlayNext/UpNext`, `reorderTemporaryTrack`, `getPlayNextQueue`, `getUpNextQueue`
- [ ] Add `notifyTemporaryQueueChange()` calls to all temp queue mutations
- [ ] Replace `Collections.synchronizedList` with `CopyOnWriteArrayList` (or just use `ListenerRegistry`)
- [ ] Replace `synchronized(playlists)` in `PlaylistManager` with `ConcurrentHashMap`
- [ ] Move `PlaylistManager.scheduleSave()` to `Dispatchers.IO`
- [ ] Migrate `DownloadDatabase` synchronized to Mutex + IO
- [ ] Remove `CountDownLatch`, `TimeUnit` imports
- [ ] Remove all `Looper.myLooper()` guards
- [ ] Remove timeout fallback states
- [ ] Update `HybridTrackPlayer.kt` bridge with listener ID tracking + `finalize()`

### Phase 3 -- iOS: Player Queue + Async

- [ ] Create `playerQueue` serial `DispatchQueue`
- [ ] Move `AVQueuePlayer` init to `playerQueue`
- [ ] Implement `withPlayerQueue` helpers
- [ ] Migrate all 14 `DispatchQueue.main.sync` methods to `withPlayerQueue`
- [ ] Migrate fire-and-forget commands with error reporting
- [ ] Replace listener arrays with `ListenerRegistry<T>` instances
- [ ] Add temp queue APIs (same as Android)
- [ ] Add `notifyTemporaryQueueChange()` calls
- [ ] Move KVO/notification handlers to `playerQueue`
- [ ] Keep `MPNowPlayingInfoCenter` updates on main (async only)
- [ ] Move `DownloadDatabase` to own serial queue
- [ ] Remove all `Thread.isMainThread` guards
- [ ] Remove all `DispatchQueue.main.sync`
- [ ] Update `HybridTrackPlayer.swift` bridge with listener ID tracking + `deinit`

### Phase 4 -- TypeScript Specs + Codegen

- [ ] Update `TrackPlayer.nitro.ts` with v2 API
- [ ] Update `PlayerQueue.nitro.ts` with v2 API
- [ ] Update `DownloadManager.nitro.ts` with v2 API
- [ ] Update `Equalizer.nitro.ts` with v2 API
- [ ] Update `AudioDevices.nitro.ts` and `AndroidAutoMediaLibrary.nitro.ts`
- [ ] Run `npx nitrogen` to regenerate
- [ ] Update all bridge files
- [ ] Update React hooks

### Phase 5 -- Validation

- [ ] Run harness tests on Android
- [ ] Run harness tests on iOS
- [ ] Verify Android Auto browsing + playback
- [ ] Verify CarPlay command handlers
- [ ] Verify gapless playback still works
- [ ] Verify lazy URL loading flow
- [ ] Verify download + offline playback
- [ ] Verify EQ persists across track changes
- [ ] Test temp queue: add, play, auto-remove after played
- [ ] Test temp queue: remove while playing
- [ ] Test temp queue: clear during playback
- [ ] Test temp queue: reorder
- [ ] Test temp queue: interaction with repeat modes
- [ ] Test temp queue: interaction with skipToIndex
- [ ] Test temp queue: interaction with playSong
- [ ] Test listeners: register, receive events, unsubscribe
- [ ] Test listeners: multiple subscribers
- [ ] Test listeners: cleanup on HybridObject destroy
- [ ] Load test: rapid play/pause/skip/seek, 100+ track playlists

---

## Appendix A: Full API Change Summary

### TrackPlayer

| Method | v1 | v2 | Reason |
|--------|----|----|--------|
| `play()` | `void` | `Promise<void>` | Error feedback |
| `pause()` | `void` | `Promise<void>` | Error feedback |
| `seek()` | `void` | `Promise<void>` | Error feedback |
| `skipToNext()` | `void` | `Promise<void>` | Error feedback |
| `skipToPrevious()` | `void` | `Promise<void>` | Error feedback |
| `setRepeatMode()` | `boolean` | `Promise<void>` | Throws on invalid |
| `setVolume()` | `boolean` | `Promise<void>` | Throws on invalid |
| `configure()` | `void` | `Promise<void>` | Confirms applied |
| `playNext()` | `Promise<void>` (silent) | `Promise<void>` (rejects) | Error on not found |
| `addToUpNext()` | `Promise<void>` (silent) | `Promise<void>` (rejects) | Error on not found |
| `on*()` events | `void` | `void` (cleanup via bridge deinit) | Auto-cleanup |
| NEW `removeFromPlayNext()` | -- | `Promise<boolean>` | |
| NEW `removeFromUpNext()` | -- | `Promise<boolean>` | |
| NEW `clearPlayNext()` | -- | `Promise<void>` | |
| NEW `clearUpNext()` | -- | `Promise<void>` | |
| NEW `reorderTemporaryTrack()` | -- | `Promise<boolean>` | |
| NEW `getPlayNextQueue()` | -- | `Promise<TrackItem[]>` | |
| NEW `getUpNextQueue()` | -- | `Promise<TrackItem[]>` | |
| NEW `onTemporaryQueueChange()` | -- | Event | |

### PlayerQueue

| Method | v1 | v2 | Reason |
|--------|----|----|--------|
| `createPlaylist()` | `string` | `Promise<string>` | Confirms persisted |
| `deletePlaylist()` | `void` | `Promise<void>` | Error if not found |
| `updatePlaylist()` | `void` | `Promise<void>` | Error if not found |
| `addTrack*()` | `void` | `Promise<void>` | Error feedback |
| `removeTrack*()` | `void` | `Promise<void>` | Error feedback |
| `reorderTrack*()` | `void` | `Promise<void>` | Error feedback |
| `loadPlaylist()` | `void` | `Promise<void>` | Confirms queue rebuilt |

### DownloadManager

| Method | v1 | v2 | Reason |
|--------|----|----|--------|
| `isTrackDownloaded()` | `boolean` | `Promise<boolean>` | File I/O async |
| `isPlaylist*Downloaded()` | `boolean` | `Promise<boolean>` | File I/O async |
| `getDownloaded*()` | sync | `Promise<T>` | File validation |
| `getLocalPath()` | sync | `Promise<T>` | File check |
| `syncDownloads()` | `number` | `Promise<number>` | Disk scan |
| `getEffectiveUrl()` | `string` | `Promise<string>` | May check file |

### Equalizer

| Method | v1 | v2 | Reason |
|--------|----|----|--------|
| `setEnabled()` | `boolean` | `Promise<void>` | Rejects on failure |
| `setBandGain()` | `boolean` | `Promise<void>` | Player thread |
| `setAllBandGains()` | `boolean` | `Promise<void>` | Player thread |
| `applyPreset()` | `boolean` | `Promise<void>` | Player thread |
| `save/deleteCustomPreset()` | `boolean` | `Promise<void>` | Disk persistence |
| `getBands()`, `getState()` | sync | `Promise<T>` | Player thread |
| `reset()` | `void` | `Promise<void>` | Player thread |

## Appendix B: Risk Assessment

| Risk | Mitigation |
|------|-----------|
| ExoPlayer on non-main looper | Media3 supports via `setLooper()`. Verified. |
| AVQueuePlayer off main thread | Works on serial queue. KVO must specify queue. |
| Nitro suspend/async mapping | Already works for existing Promise methods. |
| Android Auto thread interaction | `MediaBrowserService` on own thread. Reads via suspend. |
| CarPlay command handlers | Dispatch to `playerQueue`. Test explicitly. |
| Removing timeouts = hung player | Watchdog: log warning if `withPlayerContext` > 1s. |
| `Promise<void>` for play/pause overhead | ~0.1ms. Unnoticeable. |
| `ListenerRegistry` memory leak | Bridge `finalize`/`deinit` removes all listeners. |
| Same track in both playNext and upNext | Allowed by design. Each list independent. |
