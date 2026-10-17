package mx.coberturapunto;

import android.content.Context;
import android.media.*;
import java.util.HashSet;
import java.util.Set;

/** Bundled, very short UI cues. Respect silent mode and do not request audio focus. */
final class FeedbackSounds {
    private final SoundPool pool;
    private final AudioManager audio;
    private final Set<Integer> loaded=new HashSet<>();
    private final int location,capture;
    private boolean pendingCapture;
    FeedbackSounds(Context context){
        audio=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        pool=new SoundPool.Builder().setMaxStreams(2).setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
        location=pool.load(context,R.raw.location_ready,1);
        capture=pool.load(context,R.raw.capture_saved,1);
        final int cap=capture;
        pool.setOnLoadCompleteListener((p,id,status)->{
            if(status==0){
                synchronized(this){
                    loaded.add(id);
                    if(id==cap && pendingCapture){
                        pendingCapture=false;
                        play(cap,.75f);
                    }
                }
            }
        });
    }
    void location(){play(location,.55f);}
    void capture(){
        synchronized(this){
            if(loaded.contains(capture))play(capture,.75f);
            else pendingCapture=true;
        }
    }
    void cancelPendingCapture(){
        synchronized(this){pendingCapture=false;}
    }
    private void play(int id,float volume){if(audio!=null && audio.getRingerMode()==AudioManager.RINGER_MODE_NORMAL && loaded.contains(id))pool.play(id,volume,volume,1,0,1f);}
    void close(){
        synchronized(this){
            pendingCapture=false;
            pool.release();
            loaded.clear();
        }
    }
}
