package android.media.audiopolicy;

import android.content.Context;
import android.media.AudioFocusInfo;

/** Copia mínima para compilar (clase interna de Android). */
public class AudioPolicy {

    public static abstract class AudioPolicyFocusListener {
        public void onAudioFocusGrant(AudioFocusInfo afi, int requestResult) {
        }

        public void onAudioFocusLoss(AudioFocusInfo afi, boolean wasNotified) {
        }

        public void onAudioFocusRequest(AudioFocusInfo afi, int requestResult) {
        }

        public void onAudioFocusAbandon(AudioFocusInfo afi) {
        }
    }

    public static class Builder {
        public Builder(Context context) {
            throw new RuntimeException("stub");
        }

        // En Android esta función no devuelve nada (no se puede encadenar).
        public void setAudioPolicyFocusListener(AudioPolicyFocusListener l) {
            throw new RuntimeException("stub");
        }

        public Builder setIsAudioFocusPolicy(boolean isFocusPolicy) {
            throw new RuntimeException("stub");
        }

        public AudioPolicy build() {
            throw new RuntimeException("stub");
        }
    }

    public int setFocusRequestResult(AudioFocusInfo afi, int requestResult) {
        throw new RuntimeException("stub");
    }
}
