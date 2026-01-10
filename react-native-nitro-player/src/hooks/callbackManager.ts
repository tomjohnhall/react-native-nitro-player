import { TrackPlayer } from '../index'
import type { TrackItem, TrackPlayerState, Reason } from '../types/PlayerQueue'

type PlaybackStateCallback = (state: TrackPlayerState, reason?: Reason) => void
type TrackChangeCallback = (track: TrackItem, reason?: Reason) => void

/**
 * Internal subscription manager that allows multiple hooks to subscribe
 * to a single native callback. This solves the problem where registering
 * a new callback overwrites the previous one.
 */
class CallbackSubscriptionManager {
  private playbackStateSubscribers = new Set<PlaybackStateCallback>()
  private trackChangeSubscribers = new Set<TrackChangeCallback>()
  private isPlaybackStateRegistered = false
  private isTrackChangeRegistered = false

  /**
   * Subscribe to playback state changes
   * @returns Unsubscribe function
   */
  subscribeToPlaybackState(callback: PlaybackStateCallback): () => void {
    this.playbackStateSubscribers.add(callback)
    this.ensurePlaybackStateRegistered()

    return () => {
      this.playbackStateSubscribers.delete(callback)
    }
  }

  /**
   * Subscribe to track changes
   * @returns Unsubscribe function
   */
  subscribeToTrackChange(callback: TrackChangeCallback): () => void {
    this.trackChangeSubscribers.add(callback)
    this.ensureTrackChangeRegistered()

    return () => {
      this.trackChangeSubscribers.delete(callback)
    }
  }

  private ensurePlaybackStateRegistered(): void {
    if (this.isPlaybackStateRegistered) return

    try {
      TrackPlayer.onPlaybackStateChange((state, reason) => {
        this.playbackStateSubscribers.forEach((subscriber) => {
          try {
            subscriber(state, reason)
          } catch (error) {
            console.error(
              '[CallbackManager] Error in playback state subscriber:',
              error
            )
          }
        })
      })
      this.isPlaybackStateRegistered = true
    } catch (error) {
      console.error(
        '[CallbackManager] Failed to register playback state callback:',
        error
      )
    }
  }

  private ensureTrackChangeRegistered(): void {
    if (this.isTrackChangeRegistered) return

    try {
      TrackPlayer.onChangeTrack((track, reason) => {
        this.trackChangeSubscribers.forEach((subscriber) => {
          try {
            subscriber(track, reason)
          } catch (error) {
            console.error(
              '[CallbackManager] Error in track change subscriber:',
              error
            )
          }
        })
      })
      this.isTrackChangeRegistered = true
    } catch (error) {
      console.error(
        '[CallbackManager] Failed to register track change callback:',
        error
      )
    }
  }
}

// Export singleton instance
export const callbackManager = new CallbackSubscriptionManager()
