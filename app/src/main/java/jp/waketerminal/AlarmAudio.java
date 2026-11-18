package jp.waketerminal;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.VibrationEffect;
import android.os.Vibrator;

/** Plays the persisted selection. SoundSource remains injectable for playback tests. */
final class AlarmAudio {
    interface SoundSource { Uri uri(Context context); }
    private final Context context;
    private final AudioManager audio;
    private final Vibrator vibrator;
    private final SoundSource source;
    private MediaPlayer player;
    private ToneGenerator fallback;
    private AudioFocusRequest focus;
    private boolean active;
    private String error = "";

    AlarmAudio(Context c) {
        this(c, ctx -> new SoundSettings(ctx).selected());
    }
    AlarmAudio(Context c, SoundSource s) {
        context = c; source = s; audio = c.getSystemService(AudioManager.class);
        vibrator = c.getSystemService(Vibrator.class);
    }
    String error() { return error; }

    void start() {
        if (active) { enforceVolume(); return; }
        active = true;
        error = "";
        AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(attributes).setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener(change -> { /* An alarm must not silently stop on transient focus loss. */ }).build();
        audio.requestAudioFocus(focus);
        enforceVolume();
        if (vibrator != null && vibrator.hasVibrator())
            vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 700, 350, 700, 350}, 0), attributes);
        Uri uri = source.uri(context);
        if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(attributes);
            player.setWakeMode(context, android.os.PowerManager.PARTIAL_WAKE_LOCK);
            player.setDataSource(context, uri);
            player.setLooping(true);
            player.setVolume(1f, 1f);
            player.setOnErrorListener((mp, what, extra) -> {
                error = "選択したアラーム音の再生に失敗。代替音で鳴動中";
                releasePlayer(); startFallback(); return true;
            });
            player.prepare(); player.start();
        } catch (Exception e) {
            error = "選択したアラーム音を開けません。代替音で鳴動中";
            releasePlayer(); startFallback();
        }
    }

    void pulse() {
        if (!active) return;
        enforceVolume();
        if (fallback != null) fallback.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 900);
        else if (player != null && !player.isPlaying()) {
            try { player.start(); } catch (IllegalStateException e) { releasePlayer(); startFallback(); }
        }
    }

    private void enforceVolume() {
        try { audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0); }
        catch (SecurityException e) { error = "アラーム音量を変更できません。おやすみモードのアクセス権を確認してください"; }
    }
    private void startFallback() {
        if (!active || fallback != null) return;
        try {
            fallback = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
            fallback.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 900);
        } catch (RuntimeException e) { error = "音声出力に失敗。端末の音量・スピーカーを確認してください"; }
    }
    private void releasePlayer() {
        if (player != null) { player.release(); player = null; }
    }
    void stop() {
        active = false; releasePlayer();
        if (fallback != null) { fallback.release(); fallback = null; }
        if (vibrator != null) vibrator.cancel();
        if (focus != null) { audio.abandonAudioFocusRequest(focus); focus = null; }
    }
}
