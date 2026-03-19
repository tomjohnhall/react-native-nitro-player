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

## Appendix B: React Hooks — Robust Implementation Guide

This section specifies exactly how `useActualQueue`, `useNowPlaying`, and `useOnPlaybackProgressChange` must be implemented in v2. The design principles are:

1. **Event-driven only.** No `setTimeout`, no `setInterval`, no polling, no artificial delays.
2. **Single source of truth = native events.** The native player thread is the authority. JS never constructs state from multiple async fetches that could race.
3. **Batch state updates.** One state object per hook → one re-render per event, not N re-renders for N fields.
4. **No stale window.** Between mount and first event, the hook fetches initial state once. A version counter discards stale fetches if an event arrives before the fetch resolves.

### B.1 Current Problems

| Hook | Problem | Impact |
|------|---------|--------|
| `useActualQueue` | `setTimeout(updateQueue, 50)` after track change | Hack. 50ms is arbitrary. Queue can still be stale if native rebuild takes longer. |
| `useActualQueue` | Re-fetches entire queue on every track change AND state change | Wasteful. State changes (play/pause) don't change the queue order. |
| `useActualQueue` | No listener for temp queue changes | After `playNext()`/`addToUpNext()`, user must manually call `refreshQueue()` with another `setTimeout` |
| `useNowPlaying` | Calls `getState()` on every track change AND state change | Double async fetch per event. Race condition: event fires, fetch starts, another event fires, first fetch resolves with stale data. |
| `useNowPlaying` | 4 separate `useEffect` hooks subscribing to 4 different events | Complex. Each triggers an independent `setState`. Multiple re-renders per logical state change. |
| `useOnPlaybackProgressChange` | 3 separate `useState` calls | 3 re-renders per progress tick (position, duration, isManuallySeeked). At 4 ticks/sec = 12 re-renders/sec. |
| `callbackManager` | Never unregisters native callbacks | Memory leak. Native side accumulates dead callback references. |
| All hooks | `isMounted` ref pattern | Unnecessary with proper cleanup. React 18 batches updates. The ref is a workaround for poor architecture. |

### B.2 v2 CallbackManager

The v2 `callbackManager` is the fan-out layer between native (which supports one callback per event type) and React (which has many hook instances).

```typescript
// callbackManager.ts

import { TrackPlayer } from '../index'
import type {
  TrackItem,
  TrackPlayerState,
  Reason,
  PlayerState,
} from '../types/PlayerQueue'

type Unsubscribe = () => void

class CallbackManager {
  // Subscriber sets
  private trackChange = new Set<(track: TrackItem, reason?: Reason) => void>()
  private playbackState = new Set<(state: TrackPlayerState, reason?: Reason) => void>()
  private progress = new Set<(position: number, duration: number, isSeeked?: boolean) => void>()
  private seek = new Set<(position: number, duration: number) => void>()
  private tempQueueChange = new Set<(playNext: TrackItem[], upNext: TrackItem[]) => void>()

  // Registration flags
  private registered = {
    trackChange: false,
    playbackState: false,
    progress: false,
    seek: false,
    tempQueueChange: false,
  }

  // ── Subscribe methods ──

  subscribeTrackChange(cb: (track: TrackItem, reason?: Reason) => void): Unsubscribe {
    this.trackChange.add(cb)
    this.ensureRegistered('trackChange')
    return () => { this.trackChange.delete(cb) }
  }

  subscribePlaybackState(cb: (state: TrackPlayerState, reason?: Reason) => void): Unsubscribe {
    this.playbackState.add(cb)
    this.ensureRegistered('playbackState')
    return () => { this.playbackState.delete(cb) }
  }

  subscribeProgress(cb: (pos: number, dur: number, isSeeked?: boolean) => void): Unsubscribe {
    this.progress.add(cb)
    this.ensureRegistered('progress')
    return () => { this.progress.delete(cb) }
  }

  subscribeSeek(cb: (pos: number, dur: number) => void): Unsubscribe {
    this.seek.add(cb)
    this.ensureRegistered('seek')
    return () => { this.seek.delete(cb) }
  }

  subscribeTempQueueChange(cb: (playNext: TrackItem[], upNext: TrackItem[]) => void): Unsubscribe {
    this.tempQueueChange.add(cb)
    this.ensureRegistered('tempQueueChange')
    return () => { this.tempQueueChange.delete(cb) }
  }

  // ── Native registration (once per event type) ──

  private ensureRegistered(type: keyof typeof this.registered) {
    if (this.registered[type]) return
    this.registered[type] = true

    switch (type) {
      case 'trackChange':
        TrackPlayer.onChangeTrack((track, reason) => {
          this.trackChange.forEach((cb) => cb(track, reason))
        })
        break
      case 'playbackState':
        TrackPlayer.onPlaybackStateChange((state, reason) => {
          this.playbackState.forEach((cb) => cb(state, reason))
        })
        break
      case 'progress':
        TrackPlayer.onPlaybackProgressChange((pos, dur, isSeeked) => {
          this.progress.forEach((cb) => cb(pos, dur, isSeeked))
        })
        break
      case 'seek':
        TrackPlayer.onSeek((pos, dur) => {
          this.seek.forEach((cb) => cb(pos, dur))
        })
        break
      case 'tempQueueChange':
        TrackPlayer.onTemporaryQueueChange((playNext, upNext) => {
          this.tempQueueChange.forEach((cb) => cb(playNext, upNext))
        })
        break
    }
  }
}

export const callbackManager = new CallbackManager()
```

**What changed from v1:**
- Added `tempQueueChange` subscriber type (new v2 event).
- All methods return proper `Unsubscribe` function.
- Consistent naming.
- The native callback registration happens once per type (same pattern, cleaner implementation).

### B.3 `useActualQueue` — Event-Driven, Zero Polling

**Design:** The queue only changes when:
1. A track changes (skip, auto-advance, playSong)
2. The temp queue mutates (playNext, addToUpNext, remove, clear, reorder)
3. A playlist mutation affects the loaded playlist (add/remove/reorder tracks)

We listen to exactly these events. No `setTimeout`. No state-change listener (play/pause doesn't change queue order).

```typescript
// useActualQueue.ts

import { useEffect, useReducer, useCallback, useRef } from 'react'
import { TrackPlayer } from '../index'
import { callbackManager } from './callbackManager'
import type { TrackItem } from '../types/PlayerQueue'

export interface UseActualQueueResult {
  queue: TrackItem[]
  isLoading: boolean
}

interface QueueState {
  queue: TrackItem[]
  isLoading: boolean
  version: number     // monotonic counter to discard stale fetches
}

type QueueAction =
  | { type: 'FETCH_START' }
  | { type: 'FETCH_COMPLETE'; queue: TrackItem[]; version: number }
  | { type: 'INVALIDATE' }  // bump version, trigger re-fetch

function queueReducer(state: QueueState, action: QueueAction): QueueState {
  switch (action.type) {
    case 'FETCH_START':
      return { ...state, isLoading: true }
    case 'FETCH_COMPLETE':
      // Discard if a newer invalidation happened while we were fetching
      if (action.version < state.version) return state
      return { queue: action.queue, isLoading: false, version: state.version }
    case 'INVALIDATE':
      return { ...state, version: state.version + 1 }
  }
}

export function useActualQueue(): UseActualQueueResult {
  const [state, dispatch] = useReducer(queueReducer, {
    queue: [],
    isLoading: true,
    version: 0,
  })

  const versionRef = useRef(0)

  // Fetch queue from native. Captures version at call time
  // to discard results if state was invalidated during the fetch.
  const fetchQueue = useCallback(async () => {
    dispatch({ type: 'FETCH_START' })
    const capturedVersion = versionRef.current
    try {
      const queue = await TrackPlayer.getActualQueue()
      dispatch({ type: 'FETCH_COMPLETE', queue, version: capturedVersion })
    } catch {
      dispatch({ type: 'FETCH_COMPLETE', queue: [], version: capturedVersion })
    }
  }, [])

  // Invalidate + re-fetch. Called by event handlers.
  const invalidateAndFetch = useCallback(() => {
    versionRef.current += 1
    dispatch({ type: 'INVALIDATE' })
    fetchQueue()
  }, [fetchQueue])

  // Initial fetch
  useEffect(() => {
    fetchQueue()
  }, [fetchQueue])

  // Queue changes when a track transition happens
  useEffect(() => {
    return callbackManager.subscribeTrackChange(() => {
      invalidateAndFetch()
    })
  }, [invalidateAndFetch])

  // Queue changes when temp queue mutates (playNext, addToUpNext, remove, clear)
  useEffect(() => {
    return callbackManager.subscribeTempQueueChange(() => {
      invalidateAndFetch()
    })
  }, [invalidateAndFetch])

  return { queue: state.queue, isLoading: state.isLoading }
}
```

**Why this is robust:**

1. **No `setTimeout`.** The `onTemporaryQueueChange` event fires AFTER the native player queue is rebuilt. By the time JS receives it, `getActualQueue()` is guaranteed to return the new state.

2. **No `refreshQueue()` needed.** The old API required callers to manually call `refreshQueue()` after `playNext()`. In v2, the native `playNext()` implementation calls `notifyTemporaryQueueChange()` which triggers `onTemporaryQueueChange` which triggers `invalidateAndFetch()`. Fully automatic.

3. **Version counter prevents stale data.** If two events fire in rapid succession:
   - Event 1 fires → `versionRef = 1`, starts `getActualQueue()` (fetch A)
   - Event 2 fires → `versionRef = 2`, starts `getActualQueue()` (fetch B)
   - Fetch A resolves with `version=1` but state.version is now `2` → discarded
   - Fetch B resolves with `version=2` → accepted

4. **Single re-render per fetch.** `useReducer` with object state means one dispatch = one render.

5. **Correct event triggers.** Only `trackChange` and `tempQueueChange` refresh the queue. `playbackStateChange` (play/pause) does NOT — because play/pause doesn't change queue order. This avoids unnecessary work.

### B.4 `useNowPlaying` — Incremental State, No Double Fetch

**Design:** Instead of re-fetching the entire `PlayerState` on every event, we:
1. Fetch full state ONCE on mount.
2. Apply incremental updates from specific events.
3. Only use `getState()` for the initial load — never again.

The native events carry ALL the data needed to update the JS state:
- `onChangeTrack(track, reason)` → update `currentTrack`, `currentPlayingType`, `currentIndex`
- `onPlaybackStateChange(state, reason)` → update `currentState`
- `onPlaybackProgressChange(pos, dur)` → update `currentPosition`, `totalDuration`
- `onSeek(pos, dur)` → update `currentPosition`, `totalDuration`
- `onTemporaryQueueChange(...)` → update `currentPlayingType` (if needed)

But there's a subtlety: `onChangeTrack` only gives us the `TrackItem`, not the `currentIndex` or `currentPlayingType`. We need these from `getState()`.

**Revised design:** Use a hybrid approach:
- Progress/seek updates: apply incrementally (high frequency, no fetch).
- Track change / state change: fetch full state (low frequency, <1ms with v2 architecture, guaranteed consistent snapshot).

```typescript
// useNowPlaying.ts

import { useEffect, useReducer, useCallback, useRef } from 'react'
import { TrackPlayer } from '../index'
import { callbackManager } from './callbackManager'
import type { PlayerState } from '../types/PlayerQueue'

const DEFAULT_STATE: PlayerState = {
  currentTrack: null,
  currentPosition: 0,
  totalDuration: 0,
  currentState: 'stopped',
  currentPlaylistId: null,
  currentIndex: -1,
  currentPlayingType: 'not-playing',
}

interface NowPlayingState {
  playerState: PlayerState
  isReady: boolean
  version: number
}

type NowPlayingAction =
  | { type: 'FULL_STATE'; state: PlayerState; version: number }
  | { type: 'PROGRESS'; position: number; duration: number }
  | { type: 'SEEK'; position: number; duration: number }
  | { type: 'INVALIDATE' }

function nowPlayingReducer(state: NowPlayingState, action: NowPlayingAction): NowPlayingState {
  switch (action.type) {
    case 'FULL_STATE':
      if (action.version < state.version) return state
      return {
        playerState: action.state,
        isReady: true,
        version: state.version,
      }
    case 'PROGRESS':
      return {
        ...state,
        playerState: {
          ...state.playerState,
          currentPosition: action.position,
          totalDuration: action.duration,
        },
      }
    case 'SEEK':
      return {
        ...state,
        playerState: {
          ...state.playerState,
          currentPosition: action.position,
          totalDuration: action.duration,
        },
      }
    case 'INVALIDATE':
      return { ...state, version: state.version + 1 }
  }
}

export function useNowPlaying(): PlayerState & { isReady: boolean } {
  const [state, dispatch] = useReducer(nowPlayingReducer, {
    playerState: DEFAULT_STATE,
    isReady: false,
    version: 0,
  })

  const versionRef = useRef(0)

  const fetchFullState = useCallback(async () => {
    versionRef.current += 1
    dispatch({ type: 'INVALIDATE' })
    const capturedVersion = versionRef.current
    try {
      const newState = await TrackPlayer.getState()
      dispatch({ type: 'FULL_STATE', state: newState, version: capturedVersion })
    } catch {
      // Keep existing state on error
    }
  }, [])

  // 1. Initial state fetch (once)
  useEffect(() => {
    fetchFullState()
  }, [fetchFullState])

  // 2. Track change → full state refresh (low frequency: on skip/auto-advance)
  //    This gives us correct currentTrack, currentIndex, currentPlayingType,
  //    currentPlaylistId — all in one consistent snapshot from native.
  useEffect(() => {
    return callbackManager.subscribeTrackChange(() => {
      fetchFullState()
    })
  }, [fetchFullState])

  // 3. Playback state change → full state refresh (low frequency: play/pause/stop)
  //    This gives us correct currentState.
  useEffect(() => {
    return callbackManager.subscribePlaybackState(() => {
      fetchFullState()
    })
  }, [fetchFullState])

  // 4. Progress update → incremental (HIGH frequency: every 250ms)
  //    Only updates position and duration. No async fetch.
  //    This is the hot path — must be zero-allocation, zero-async.
  useEffect(() => {
    return callbackManager.subscribeProgress((position, duration) => {
      dispatch({ type: 'PROGRESS', position, duration })
    })
  }, [])

  // 5. Seek → incremental (low frequency: user seeks)
  useEffect(() => {
    return callbackManager.subscribeSeek((position, duration) => {
      dispatch({ type: 'SEEK', position, duration })
    })
  }, [])

  return { ...state.playerState, isReady: state.isReady }
}
```

**Why this is robust:**

1. **No stale data.** The version counter ensures that if a track change fires while a previous `getState()` is in flight, the stale result is discarded.

2. **No double-fetch.** Track change and state change each trigger ONE `getState()` call. In v2, `getState()` is <1ms (no latch, no thread blocking), so this is fast.

3. **Progress never triggers a fetch.** At 4 ticks/sec, we absolutely cannot call `getState()` each time. Instead, we apply the position/duration incrementally via a reducer dispatch. This is a single object spread — zero async, zero bridge calls.

4. **Single re-render per event.** `useReducer` batches the state update into one render.

5. **Correct initial state.** On mount, `fetchFullState()` gets the complete snapshot. Between mount and the first event, the hook shows real data (not DEFAULT_STATE forever).

6. **Why `getState()` on track change instead of incremental?** Because `onChangeTrack` only gives `(track, reason)`. We also need `currentIndex`, `currentPlayingType`, `currentPlaylistId`, and accurate `currentPosition` (which resets to 0 on track change). Fetching the full state from native guarantees a consistent snapshot of all fields. Since track changes happen maybe once per 3-4 minutes (song length), this is perfectly acceptable.

### B.5 `useOnPlaybackProgressChange` — Single State Object, Zero Allocation

**Design:** One `useState` with an object. One `dispatch` per tick. One re-render per tick.

```typescript
// useOnPlaybackProgressChange.ts

import { useEffect, useState } from 'react'
import { callbackManager } from './callbackManager'

export interface PlaybackProgress {
  position: number
  totalDuration: number
  isManuallySeeked: boolean | undefined
}

const DEFAULT_PROGRESS: PlaybackProgress = {
  position: 0,
  totalDuration: 0,
  isManuallySeeked: undefined,
}

export function useOnPlaybackProgressChange(): PlaybackProgress {
  const [progress, setProgress] = useState<PlaybackProgress>(DEFAULT_PROGRESS)

  useEffect(() => {
    return callbackManager.subscribeProgress(
      (position, totalDuration, isManuallySeeked) => {
        setProgress({ position, totalDuration, isManuallySeeked })
      }
    )
  }, [])

  return progress
}
```

**Why this is robust:**

1. **Single `useState`.** v1 used 3 separate `useState` hooks → 3 `setState` calls per tick → potentially 3 re-renders per tick (React batches in event handlers but not always in async callbacks). v2 uses one object → one `setState` → one re-render.

2. **No `setTimeout` or interval.** The native side fires `onPlaybackProgressChange` every 250ms from the player thread. The JS side passively receives it. If playback stops, the native side stops firing → the hook naturally stops updating.

3. **No `isMounted` ref.** React 18+ batches state updates and the cleanup function from `useEffect` removes the subscriber. No need for manual mount tracking.

4. **Object identity for memoization.** Downstream components can use `useMemo` / `React.memo` with the progress object. Since we create a new object each tick, `React.memo` with shallow compare will correctly detect changes.

### B.6 Data Flow Diagram — Single Source of Truth

```
┌─────────────────────────────────────────────────────────┐
│                NATIVE (Player Thread)                     │
│                                                           │
│  ExoPlayer / AVQueuePlayer                                │
│  ├── onMediaItemTransition / currentItemDidChange         │
│  │   └── notifyTrackChange(track, reason)                 │
│  │   └── notifyTemporaryQueueChange(playNext, upNext)     │
│  ├── onPlaybackStateChanged                               │
│  │   └── notifyPlaybackStateChange(state, reason)         │
│  ├── progressUpdateRunnable (250ms)                       │
│  │   └── notifyPlaybackProgress(pos, dur, isSeeked)       │
│  └── onPositionDiscontinuity / timeJumped                 │
│      └── notifySeek(pos, dur)                             │
│                                                           │
│  getActualQueue() → snapshot of player thread state       │
│  getState()       → snapshot of player thread state       │
│                                                           │
│  Both return data from the SAME thread that fires events. │
│  No race. No stale data. No cross-thread read.            │
└─────────────────┬───────────────────────────────────────┘
                  │ Nitro bridge (auto thread hop)
                  ▼
┌─────────────────────────────────────────────────────────┐
│                JS (Hermes, single thread)                  │
│                                                           │
│  callbackManager (singleton)                              │
│  ├── Native event → fan out to all subscribers            │
│  └── One native registration per event type               │
│                                                           │
│  ┌─────────────────────────────────────────────┐          │
│  │  useActualQueue                              │          │
│  │                                              │          │
│  │  Listens to:                                 │          │
│  │  ├── onChangeTrack → invalidate + fetch      │          │
│  │  └── onTemporaryQueueChange → invalidate     │          │
│  │      + fetch                                 │          │
│  │                                              │          │
│  │  Does NOT listen to:                         │          │
│  │  ├── onPlaybackStateChange (no queue change) │          │
│  │  └── onProgress (no queue change)            │          │
│  │                                              │          │
│  │  getActualQueue() fetches from native.       │          │
│  │  Native returns queue from player thread.    │          │
│  │  The event that triggered the fetch was      │          │
│  │  emitted AFTER the queue was rebuilt.         │          │
│  │  Therefore: fetch always returns new state.   │          │
│  │  No setTimeout needed.                        │          │
│  └─────────────────────────────────────────────┘          │
│                                                           │
│  ┌─────────────────────────────────────────────┐          │
│  │  useNowPlaying                               │          │
│  │                                              │          │
│  │  Listens to:                                 │          │
│  │  ├── onChangeTrack → full getState() fetch   │          │
│  │  ├── onPlaybackStateChange → full fetch      │          │
│  │  ├── onProgress → incremental pos/dur only   │          │
│  │  └── onSeek → incremental pos/dur only       │          │
│  │                                              │          │
│  │  Full fetch: ~once per track change          │          │
│  │  Incremental: ~4x/sec (just 2 numbers)      │          │
│  │                                              │          │
│  │  Version counter discards stale fetches.     │          │
│  └─────────────────────────────────────────────┘          │
│                                                           │
│  ┌─────────────────────────────────────────────┐          │
│  │  useOnPlaybackProgressChange                 │          │
│  │                                              │          │
│  │  Listens to:                                 │          │
│  │  └── onProgress → single setState({...})     │          │
│  │                                              │          │
│  │  Pure event consumer. No fetch. No async.    │          │
│  │  One re-render per tick (250ms).             │          │
│  └─────────────────────────────────────────────┘          │
└─────────────────────────────────────────────────────────┘
```

### B.7 Why There Are Zero Data Discrepancies

The guarantee chain:

```
1. Native player thread is the SINGLE OWNER of all state.
2. Events are emitted ON the player thread AFTER state mutation.
3. getActualQueue() / getState() run ON the player thread.
4. Therefore: when JS receives an event and calls getActualQueue(),
   the native function runs AFTER the mutation that triggered the event.
   The returned data always reflects the new state.

Example flow:
  a. User calls playNext("track-A")
  b. Native player thread: insert track-A into playNextStack
  c. Native player thread: rebuildQueueFromCurrentPosition()
  d. Native player thread: notifyTemporaryQueueChange()  ← emitted AFTER rebuild
  e. Nitro bridge delivers event to JS
  f. JS useActualQueue: calls getActualQueue()
  g. Native player thread: getActualQueueInternal()  ← runs AFTER step c
  h. Returns queue WITH track-A included
  i. JS updates state. UI shows track-A in queue.

There is no window where the event arrives but the state hasn't been updated.
The serial player thread guarantees causal ordering.
```

### B.8 Performance Characteristics

| Hook | Events/sec | Async calls/sec | Re-renders/sec | Bridge calls/sec |
|------|-----------|-----------------|-----------------|------------------|
| `useActualQueue` | ~0.01 (track changes) | ~0.01 (`getActualQueue`) | ~0.01 | ~0.01 |
| `useNowPlaying` | 4 (progress) + ~0.02 (track/state) | ~0.02 (`getState`) | 4 (progress ticks) | ~0.02 |
| `useOnPlaybackProgressChange` | 4 | 0 | 4 | 0 |

Notes:
- `useActualQueue` is essentially idle during normal playback. It only works when the queue actually changes.
- `useNowPlaying` does 4 re-renders/sec from progress, but each is a single object spread (zero async, zero bridge). The full `getState()` fetch happens only ~once per 3-4 minutes (song length).
- `useOnPlaybackProgressChange` does zero bridge calls. It's a pure event consumer. 4 re-renders/sec is well within React's capability.

### B.9 Edge Cases

| Scenario | Behavior |
|----------|----------|
| Mount during playback | `useNowPlaying` fetches full state → shows current track immediately. Progress events start flowing → position updates live. |
| Mount before any playback | All hooks show default state. When first track plays, events trigger updates. |
| Rapid skip (spam skipToNext) | Each skip fires `onChangeTrack`. Each triggers `fetchFullState()`. Version counter ensures only the last one's result is kept. `useActualQueue` fetches queue for each, version counter keeps last. |
| `playNext()` then immediately `getActualQueue()` from another component | The `playNext()` Promise resolves AFTER native has rebuilt the queue and emitted `onTemporaryQueueChange`. Any subsequent `getActualQueue()` call returns the new queue. |
| Component unmounts during pending `getState()` fetch | The `useEffect` cleanup removes the subscriber. The `dispatch` call after the fetch resolves is a no-op on an unmounted component (React ignores it). No memory leak. |
| Two `useNowPlaying` hooks in different components | Both subscribe via `callbackManager`. Both receive the same events. Both call `getState()` independently. This is fine — `getState()` is <1ms and they may mount at different times. |
| Seek while track is changing | `onSeek` fires → incremental position update. `onChangeTrack` fires → full state fetch overwrites position with new track's position. Correct. |
| Progress event arrives before initial `getState()` resolves | The progress `dispatch` updates position/duration on `DEFAULT_STATE`. When `getState()` resolves, `FULL_STATE` dispatch overwrites everything including the correct position. No stale data persists. |

### B.10 Native Callback Implementation — Correctness Audit and v2 Spec

The hooks above are only as correct as the native callbacks that feed them. This section specifies exactly how each callback must be implemented on both platforms to guarantee the zero-discrepancy contract.

#### B.10.1 Current Problems in Native Callbacks

| Problem | Platform | Detail |
|---------|----------|--------|
| Callbacks dispatched to **main thread** | Both | Android: `handler.post { callback() }` dispatches to main looper. iOS: `DispatchQueue.main.async { callback() }`. This means callbacks arrive on a DIFFERENT thread than the one that mutated state. A `getState()` call from within a callback could race with the next mutation on the player thread. |
| `synchronized` lock during snapshot | Android | `notifyTrackChange` takes `synchronized(onChangeTrackListeners)` to snapshot, then `handler.post` to invoke. Under rapid track changes, the lock contends with `addOnChangeTrackListener`. |
| `listenersQueue` barrier writes for EVERY notify | iOS | `notifyTrackChange` uses `listenersQueue.async(flags: .barrier)` — this is a WRITE lock just to read the list and snapshot it. Should be a non-barrier read. |
| Progress fires only when `player.rate > 0` (iOS) | iOS | When paused, no progress events fire. The hook has no way to know the current position after a pause unless it fetches state separately. |
| Progress stops when `STATE_IDLE` (Android) | Android | `progressUpdateRunnable` checks `player.playbackState != Player.STATE_IDLE`. After stop, no more progress events. Same issue. |
| `notifyPlaybackProgress` cleanup every 10th call | Android | `progressNotifyCounter % 10` — arbitrary cleanup interval. Dead callbacks accumulate in between. |
| Progress uses a mutable scratch list | Android | `progressCallbackScratch` is an `ArrayList` reused across calls. Not thread-safe if `notifyPlaybackProgress` is re-entered. |
| `WeakCallbackBox` owner is always the singleton | Both | See Section 5.1. The `isAlive` check never triggers. |
| No `onTemporaryQueueChange` callback exists | Both | This is a new v2 event that needs to be implemented. |
| iOS periodic time observer fires on `.main` queue | iOS | `player.addPeriodicTimeObserver(queue: .main)` — forces progress callbacks onto the main thread. Should be the player queue. |

#### B.10.2 v2 Native Callback Spec

Every callback must satisfy these rules:

```
RULE 1: Callbacks are invoked on the player thread/queue.
        Never on main. Nitro bridge handles the JS thread hop.

RULE 2: Callbacks are invoked AFTER the state mutation is complete.
        If a callback fires, any getState()/getActualQueue() call
        from within that callback (or triggered by it) returns the
        new state.

RULE 3: No locks during callback invocation.
        Use CopyOnWriteArrayList (Android) or snapshot from
        ListenerRegistry (iOS).

RULE 4: Progress callbacks fire during playback AND emit one
        final position update on pause/stop so the hook has
        the exact position where playback stopped.

RULE 5: Every state mutation that changes the temp queue calls
        notifyTemporaryQueueChange() before returning.
```

#### B.10.3 `onChangeTrack` — Native Implementation

**Fires when:** A new track starts playing (any source: auto-advance, skip, playSong, temp track transition).

**Data:** `(track: TrackItem, reason: Reason?)`

**Android v2:**

```kotlin
// Called on player thread (inside ExoPlayer listener, which runs on playerThread.looper)
private fun notifyTrackChange(track: TrackItem, reason: Reason?) {
    // Already on player thread — no lock, no post, direct invocation
    onChangeTrackListeners.forEach { it(track, reason) }
}

// Where it's called (all already on player thread):
// 1. onMediaItemTransition → after currentTemporaryType and currentTrackIndex are updated
// 2. rebuildQueueAndPlayFromIndex → after queue is rebuilt and playing
// 3. Playlist repeat → after queue is rebuilt from index 0
```

**iOS v2:**

```swift
// Called on playerQueue
private func notifyTrackChange(_ track: TrackItem, _ reason: Reason?) {
    // Already on playerQueue — direct invocation on snapshot
    onChangeTrackListeners.forEach { $0(track, reason) }
}

// Where it's called (all on playerQueue):
// 1. currentItemDidChange → after currentTemporaryType and currentTrackIndex updated
// 2. rebuildQueueFromPlaylistIndex → after queue rebuilt
// 3. Playlist repeat → after queue rebuilt from index 0
```

**Ordering guarantee:** The track change callback fires AFTER:
- `currentTemporaryType` is updated
- `currentTrackIndex` is updated (if returning to original playlist)
- The temp track is removed from its list (if it just finished)
- The native player queue is rebuilt

Therefore: `getState()` and `getActualQueue()` called from the JS callback handler will return data consistent with the new track.

#### B.10.4 `onPlaybackStateChange` — Native Implementation

**Fires when:** Playing/paused/stopped state changes.

**Data:** `(state: TrackPlayerState, reason: Reason?)`

**Android v2:**

```kotlin
private fun emitStateChange(reason: Reason? = null) {
    // Already on player thread (ExoPlayer callbacks run on playerThread.looper)
    val state = when (player.playbackState) {
        Player.STATE_IDLE -> TrackPlayerState.STOPPED
        Player.STATE_BUFFERING ->
            if (player.playWhenReady) TrackPlayerState.PLAYING else TrackPlayerState.PAUSED
        Player.STATE_READY ->
            if (player.isPlaying) TrackPlayerState.PLAYING else TrackPlayerState.PAUSED
        Player.STATE_ENDED -> TrackPlayerState.STOPPED
        else -> TrackPlayerState.STOPPED
    }
    val actualReason = reason ?: if (player.playbackState == Player.STATE_ENDED) Reason.END else null

    onPlaybackStateChangeListeners.forEach { it(state, actualReason) }
    mediaSessionManager?.onPlaybackStateChanged()
}

// Called from:
// 1. ExoPlayer.Listener.onPlayWhenReadyChanged
// 2. ExoPlayer.Listener.onPlaybackStateChanged
// 3. ExoPlayer.Listener.onIsPlayingChanged
// 4. After play()/pause() commands complete
```

**iOS v2:**

```swift
private func emitStateChange(reason: Reason? = nil) {
    guard let player = player else { return }

    let state: TrackPlayerState
    if player.rate == 0 {
        state = .paused
    } else if player.timeControlStatus == .playing {
        state = .playing
    } else {
        state = .stopped
    }

    onPlaybackStateChangeListeners.forEach { $0(state, reason) }
    mediaSessionManager?.onPlaybackStateChanged()
}

// Called from:
// 1. KVO on "rate" change
// 2. KVO on "timeControlStatus" change
// 3. KVO on "status" (readyToPlay / failed)
// 4. After play()/pause() commands complete
```

#### B.10.5 `onPlaybackProgressChange` — Native Implementation (CRITICAL for hooks)

**Fires when:** Every ~250ms during playback. Also fires once on pause/stop with the final position.

**Data:** `(position: Double, totalDuration: Double, isManuallySeeked: Boolean?)`

**Android v2:**

```kotlin
private val progressUpdateRunnable = object : Runnable {
    override fun run() {
        if (!::player.isInitialized) return

        val isPlaying = player.isPlaying
        val state = player.playbackState

        if (state != Player.STATE_IDLE) {
            val position = player.currentPosition / 1000.0
            val duration = if (player.duration > 0) player.duration / 1000.0 else 0.0
            val seekFlag = if (isManuallySeeked) true else null
            isManuallySeeked = false

            onProgressListeners.forEach { it(position, duration, seekFlag) }
        }

        // Continue posting while playing; post one final update when pausing/stopping
        if (isPlaying) {
            playerHandler.postDelayed(this, 250)
        }
        // When not playing, this runnable stops naturally.
        // It will be re-posted when play() is called.
    }
}

// Start progress updates (called from play())
private fun startProgressUpdates() {
    playerHandler.removeCallbacks(progressUpdateRunnable)
    playerHandler.post(progressUpdateRunnable)
}

// Emit final position on pause/stop
suspend fun pause() = withPlayerContext {
    if (!::player.isInitialized) throw IllegalStateException("Player not initialized")
    player.pause()

    // Emit final position so hooks know exactly where we paused
    val position = player.currentPosition / 1000.0
    val duration = if (player.duration > 0) player.duration / 1000.0 else 0.0
    onProgressListeners.forEach { it(position, duration, null) }

    emitStateChange(Reason.USER_ACTION)
}

suspend fun play() = withPlayerContext {
    if (!::player.isInitialized) throw IllegalStateException("Player not initialized")
    player.play()
    startProgressUpdates()
    emitStateChange(Reason.USER_ACTION)
}
```

**iOS v2:**

```swift
private func setupPeriodicTimeObserver() {
    if let existing = boundaryTimeObserver, let p = player {
        p.removeTimeObserver(existing)
        boundaryTimeObserver = nil
    }

    guard let player = player else { return }

    let interval = CMTime(seconds: 0.25, preferredTimescale: CMTimeScale(NSEC_PER_SEC))

    // CRITICAL: queue: playerQueue, NOT .main
    boundaryTimeObserver = player.addPeriodicTimeObserver(
        forInterval: interval,
        queue: playerQueue
    ) { [weak self] _ in
        self?.handleProgressTick()
    }
}

private func handleProgressTick() {
    guard let player = player, let item = player.currentItem else { return }
    guard player.rate > 0 else { return }

    let position = item.currentTime().seconds
    let duration = item.duration.seconds
    guard duration > 0 && !duration.isNaN && !duration.isInfinite else { return }

    let seekFlag = isManuallySeeked ? true : nil
    isManuallySeeked = false

    onProgressListeners.forEach { $0(position, duration, seekFlag) }
}

// Emit final position on pause
func pause() async throws {
    try await withPlayerQueue {
        guard let player = self.player else {
            throw NitroPlayerError.playerNotInitialized
        }
        player.pause()

        // Final position update
        if let item = player.currentItem {
            let position = item.currentTime().seconds
            let duration = item.duration.seconds
            if duration > 0 && !duration.isNaN {
                self.onProgressListeners.forEach { $0(position, duration, nil) }
            }
        }

        self.emitStateChange(reason: .userAction)
    }
}
```

**Why the final position on pause matters:**

Without it, `useNowPlaying` would show the last progress tick's position (up to 250ms ago), not the actual pause position. For seek bars that snap to the pause point, this causes a visible jump.

```
Without final update:
  Progress tick: position = 42.0s
  User pauses at 42.15s
  Hook shows 42.0s → then getState() returns 42.15s → visible jump

With final update:
  Progress tick: position = 42.0s
  User pauses at 42.15s
  Final update: position = 42.15s → hook shows 42.15s → no jump
```

#### B.10.6 `onSeek` — Native Implementation

**Fires when:** User or programmatic seek completes.

**Data:** `(position: Double, totalDuration: Double)`

**Android v2:**

```kotlin
// Called from ExoPlayer.Listener.onPositionDiscontinuity (already on player thread)
override fun onPositionDiscontinuity(
    oldPosition: Player.PositionInfo,
    newPosition: Player.PositionInfo,
    reason: Int,
) {
    if (reason == Player.DISCONTINUITY_REASON_SEEK) {
        isManuallySeeked = true
        val pos = newPosition.positionMs / 1000.0
        val dur = if (player.duration > 0) player.duration / 1000.0 else 0.0
        onSeekListeners.forEach { it(pos, dur) }
    }
}
```

**iOS v2:**

```swift
// Called from playerItemTimeJumped notification (must dispatch to playerQueue)
@objc private func playerItemTimeJumped(notification: Notification) {
    playerQueue.async { [weak self] in
        guard let self = self, let player = self.player, let item = player.currentItem else { return }

        let position = item.currentTime().seconds
        let duration = item.duration.seconds

        self.isManuallySeeked = true
        self.onSeekListeners.forEach { $0(position, duration) }

        // Trigger immediate progress update
        self.handleProgressTick()
    }
}
```

#### B.10.7 `onTemporaryQueueChange` — Native Implementation (NEW)

**Fires when:** Any mutation to `playNextStack` or `upNextQueue`.

**Data:** `(playNextQueue: [TrackItem], upNextQueue: [TrackItem])`

**Call sites (both platforms):**

```
1. playNext(trackId)       → after insert at playNextStack[0]
2. addToUpNext(trackId)    → after append to upNextQueue
3. removeFromPlayNext()    → after remove from playNextStack
4. removeFromUpNext()      → after remove from upNextQueue
5. clearPlayNext()         → after playNextStack.clear()
6. clearUpNext()           → after upNextQueue.clear()
7. reorderTemporaryTrack() → after reorder
8. onMediaItemTransition   → after temp track is auto-removed (played and deleted)
9. skipToNext (temp)       → after temp track is removed on skip
10. skipToPrevious (temp)  → after temp track is removed on back
11. skipToIndex (cross-section) → after temp lists modified
12. playSong()             → after all temps cleared
13. loadPlaylist()         → after all temps cleared
14. Playlist repeat        → after all temps cleared
```

**Android v2:**

```kotlin
private fun notifyTemporaryQueueChange() {
    // Already on player thread — snapshot and invoke directly
    val pn = playNextStack.toList()
    val un = upNextQueue.toList()
    onTemporaryQueueChangeListeners.forEach { it(pn, un) }
}
```

**iOS v2:**

```swift
private func notifyTemporaryQueueChange() {
    // Already on playerQueue — snapshot and invoke directly
    let pn = playNextStack
    let un = upNextQueue
    onTemporaryQueueChangeListeners.forEach { $0(pn, un) }
}
```

**Ordering guarantee:** This callback fires AFTER:
- The temp list mutation is complete
- `rebuildQueueFromCurrentPosition()` has been called (native player queue updated)
- `currentTemporaryType` is updated (if a temp track was removed)

Therefore: `getActualQueue()` called from the JS callback will return the new queue. `useActualQueue` never sees stale data.

#### B.10.8 v2 Bridge Layer (connects native ListenerRegistry to Nitro)

**Android v2 HybridTrackPlayer.kt:**

```kotlin
class HybridTrackPlayer : HybridTrackPlayerSpec() {
    private val core: TrackPlayerCore
    private val listenerIds = mutableListOf<Pair<String, Long>>()

    init {
        val context = NitroModules.applicationContext
            ?: throw IllegalStateException("React Context is not initialized")
        core = TrackPlayerCore.getInstance(context)
    }

    // Commands — all suspend (maps to Promise<void>)
    override fun play(): Promise<Unit> = Promise.async { core.play() }
    override fun pause(): Promise<Unit> = Promise.async { core.pause() }
    override fun seek(position: Double): Promise<Unit> = Promise.async { core.seek(position) }
    // ... etc

    // Callbacks — register with core, track IDs for cleanup
    override fun onChangeTrack(callback: (TrackItem, Reason?) -> Unit) {
        val id = core.onChangeTrackListeners.add(callback)
        listenerIds.add("onChangeTrack" to id)
    }

    override fun onPlaybackStateChange(callback: (TrackPlayerState, Reason?) -> Unit) {
        val id = core.onPlaybackStateChangeListeners.add(callback)
        listenerIds.add("onPlaybackStateChange" to id)
    }

    override fun onPlaybackProgressChange(callback: (Double, Double, Boolean?) -> Unit) {
        val id = core.onProgressListeners.add(callback)
        listenerIds.add("onProgress" to id)
    }

    override fun onSeek(callback: (Double, Double) -> Unit) {
        val id = core.onSeekListeners.add(callback)
        listenerIds.add("onSeek" to id)
    }

    override fun onTemporaryQueueChange(callback: (Array<TrackItem>, Array<TrackItem>) -> Unit) {
        val wrappedCallback = { pn: List<TrackItem>, un: List<TrackItem> ->
            callback(pn.toTypedArray(), un.toTypedArray())
        }
        val id = core.onTemporaryQueueChangeListeners.add(wrappedCallback)
        listenerIds.add("onTempQueue" to id)
    }

    override fun onTracksNeedUpdate(callback: (Array<TrackItem>, Double) -> Unit) {
        val wrappedCallback = { tracks: List<TrackItem>, lookahead: Int ->
            callback(tracks.toTypedArray(), lookahead.toDouble())
        }
        val id = core.onTracksNeedUpdateListeners.add(wrappedCallback)
        listenerIds.add("onTracksNeedUpdate" to id)
    }

    override fun onAndroidAutoConnectionChange(callback: (Boolean) -> Unit) {
        val id = core.onAndroidAutoConnectionListeners.add(callback)
        listenerIds.add("onAAConnection" to id)
    }

    // Cleanup: remove all listeners when HybridObject is GC'd
    protected fun finalize() {
        for ((type, id) in listenerIds) {
            when (type) {
                "onChangeTrack" -> core.onChangeTrackListeners.remove(id)
                "onPlaybackStateChange" -> core.onPlaybackStateChangeListeners.remove(id)
                "onProgress" -> core.onProgressListeners.remove(id)
                "onSeek" -> core.onSeekListeners.remove(id)
                "onTempQueue" -> core.onTemporaryQueueChangeListeners.remove(id)
                "onTracksNeedUpdate" -> core.onTracksNeedUpdateListeners.remove(id)
                "onAAConnection" -> core.onAndroidAutoConnectionListeners.remove(id)
            }
        }
        listenerIds.clear()
    }
}
```

**iOS v2 HybridTrackPlayer.swift:**

```swift
final class HybridTrackPlayer: HybridTrackPlayerSpec {
    private let core = TrackPlayerCore.shared
    private var listenerIds: [(String, Int64)] = []

    // Commands — all async throws (maps to Promise<void>)
    func play() async throws { try await core.play() }
    func pause() async throws { try await core.pause() }
    func seek(position: Double) async throws { try await core.seek(position: position) }
    // ... etc

    // Callbacks — register with core, track IDs for cleanup
    func onChangeTrack(callback: @escaping (TrackItem, Reason?) -> Void) throws {
        let id = core.onChangeTrackListeners.add(callback)
        listenerIds.append(("onChangeTrack", id))
    }

    func onPlaybackStateChange(callback: @escaping (TrackPlayerState, Reason?) -> Void) throws {
        let id = core.onPlaybackStateChangeListeners.add(callback)
        listenerIds.append(("onPlaybackStateChange", id))
    }

    func onPlaybackProgressChange(callback: @escaping (Double, Double, Bool?) -> Void) throws {
        let id = core.onProgressListeners.add(callback)
        listenerIds.append(("onProgress", id))
    }

    func onSeek(callback: @escaping (Double, Double) -> Void) throws {
        let id = core.onSeekListeners.add(callback)
        listenerIds.append(("onSeek", id))
    }

    func onTemporaryQueueChange(callback: @escaping ([TrackItem], [TrackItem]) -> Void) throws {
        let id = core.onTemporaryQueueChangeListeners.add(callback)
        listenerIds.append(("onTempQueue", id))
    }

    func onTracksNeedUpdate(callback: @escaping ([TrackItem], Double) -> Void) throws {
        let wrappedCallback: ([TrackItem], Int) -> Void = { tracks, lookahead in
            callback(tracks, Double(lookahead))
        }
        let id = core.onTracksNeedUpdateListeners.add(wrappedCallback)
        listenerIds.append(("onTracksNeedUpdate", id))
    }

    func onAndroidAutoConnectionChange(callback: @escaping (Bool) -> Void) throws {
        // iOS no-op
    }

    func isAndroidAutoConnected() throws -> Bool { false }

    // Cleanup
    deinit {
        for (type, id) in listenerIds {
            switch type {
            case "onChangeTrack": _ = core.onChangeTrackListeners.remove(id: id)
            case "onPlaybackStateChange": _ = core.onPlaybackStateChangeListeners.remove(id: id)
            case "onProgress": _ = core.onProgressListeners.remove(id: id)
            case "onSeek": _ = core.onSeekListeners.remove(id: id)
            case "onTempQueue": _ = core.onTemporaryQueueChangeListeners.remove(id: id)
            case "onTracksNeedUpdate": _ = core.onTracksNeedUpdateListeners.remove(id: id)
            default: break
            }
        }
    }
}
```

#### B.10.9 End-to-End Data Flow Proof

Here is the exact sequence for `playNext("track-A")` → `useActualQueue` updates:

```
JS Thread                 Nitro Bridge              Player Thread
─────────                 ────────────              ─────────────
trackPlayer.playNext("A")
  │
  └──── Promise.async ──────►
                              │
                              └── withPlayerContext ──────►
                                                          │
                                                    1. findTrackById("A")
                                                    2. playNextStack.add(0, track)
                                                    3. rebuildQueueFromCurrentPosition()
                                                       (ExoPlayer queue rebuilt)
                                                    4. notifyTemporaryQueueChange()
                                                       │
                                                       ├── snapshot playNextStack
                                                       ├── snapshot upNextQueue
                                                       └── for listener in registry:
                                                             listener(pn, un)
                                                             │
                              ◄────── Nitro delivers ────────┘
                              │       callback to JS
  ◄───── callback arrives ────┘
  │
  callbackManager.tempQueueChange
  subscribers.forEach(cb => cb(pn, un))
  │
  └── useActualQueue handler fires
      │
      dispatch({ type: 'INVALIDATE' })
      versionRef.current += 1
      │
      └── fetchQueue() → TrackPlayer.getActualQueue()
          │
          └──── Promise.async ──────►
                                      │
                                      └── withPlayerContext ──────►
                                                                  │
                                                            getActualQueueInternal()
                                                            (reads playNextStack which
                                                             ALREADY contains track-A
                                                             because step 2 completed
                                                             before step 4 was called)
                                                                  │
                              ◄────── returns queue ──────────────┘
          ◄───── queue data ────┘
          │
          dispatch({ type: 'FETCH_COMPLETE', queue, version })
          │
          React re-renders with new queue
          UI shows track-A in the queue ✓
```

**The guarantee:** Step 4 (`notifyTemporaryQueueChange`) fires AFTER step 2 (`playNextStack.add`). The `getActualQueue()` call in the JS callback reads from the same player thread that already executed steps 1-3. There is no window where the event arrives but the data isn't ready. The serial player thread is the single source of truth.

## Appendix C: Risk Assessment

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
| `useNowPlaying` progress re-renders at 4/sec | Only updates 2 numbers. React handles this easily. Can add `useDeferredValue` if needed. |
| `useActualQueue` fetches on every track change | Track changes happen every 3-4 min. `getActualQueue()` is <1ms. Negligible. |
| Version counter overflow | `Number.MAX_SAFE_INTEGER` = 9 quadrillion. At 1 event/sec = 285 million years. |
