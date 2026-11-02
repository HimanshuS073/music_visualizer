package local.music.visualizer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.exoplayer.audio.TeeAudioProcessor;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MergingMediaSource;
import androidx.media3.common.util.UnstableApi;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@UnstableApi
public final class MainActivity extends Activity {
    private final ExecutorService workers=Executors.newFixedThreadPool(3);
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final AtomicInteger searchVersion=new AtomicInteger(),playVersion=new AtomicInteger();
    private final List<MusicSearch.Song> songs=new ArrayList<>();
    private ExoPlayer player;
    private SpectrumView spectrum;
    private TextureView video;
    private ImageView background;
    private LinearLayout content;
    private EditText query;
    private TextView message,title,clock;
    private Button pause;
    private ArrayAdapter<String> results;
    private SharedPreferences settings;
    private boolean closed=false;
    private int framesPerSecond=8;
    private long lastFrame=0;
    private final Runnable update=new Runnable() {
        @Override public void run() {
            if(closed)return;
            if(player!=null) {
                clock.setText(time(player.getCurrentPosition())+" / "+time(player.getDuration()));
                long now=android.os.SystemClock.uptimeMillis();
                if(player.isPlaying() && video.isAvailable() && now-lastFrame>=1000/framesPerSecond) {
                    lastFrame=now;
                    Bitmap frame=video.getBitmap(64,36);
                    if(frame!=null) {
                        int[] colors=new int[32];
                        for(int i=0;i<32;i++)colors[i]=frame.getPixel((i%8)*8+4,(i/8)*9+4);
                        spectrum.setPalette(colors);frame.recycle();
                    }
                }
            }
            ui.postDelayed(this,player!=null&&player.isPlaying()?Math.max(16,1000/framesPerSecond):500);
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        settings=getSharedPreferences("music",MODE_PRIVATE);
        layout();
        spectrum.count=settings.getInt("bars",48);
        spectrum.colorSpeed=settings.getFloat("colorSpeed",10);
        framesPerSecond=settings.getInt("videoFps",8);
        DefaultRenderersFactory renderers=new DefaultRenderersFactory(this) {
            @Override protected AudioSink buildAudioSink(Context context,boolean floatOutput,boolean playbackParams) {
                return new DefaultAudioSink.Builder(context).setAudioProcessors(
                    new AudioProcessor[]{new TeeAudioProcessor(spectrum)}).build();
            }
        };
        player=new ExoPlayer.Builder(this,renderers).build();
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),true);
        player.setHandleAudioBecomingNoisy(true);
        player.setVideoTextureView(video);
        player.addListener(new Player.Listener() {
            @Override public void onIsPlayingChanged(boolean playing) {
                spectrum.setPlaying(playing);pause.setText(playing?"Pause":"Play");
            }
            @Override public void onPlaybackStateChanged(int state) {
                if(state==Player.STATE_BUFFERING)message.setText("Loading audio…");
                else if(state==Player.STATE_READY)message.setText(player.isPlaying()?"Playing":"Ready");
                else if(state==Player.STATE_ENDED)message.setText("Track finished");
            }
            @Override public void onPlayerError(PlaybackException error) {
                message.setText("Playback failed. Select the song again to refresh its stream.");
            }
        });
        ui.post(update);
    }
    private void layout() {
        FrameLayout root=new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(13,17,24));
        background=new ImageView(this);background.setScaleType(ImageView.ScaleType.CENTER_CROP);
        background.setAlpha(.28f);
        ColorMatrix matrix=new ColorMatrix();matrix.setSaturation(.3f);
        background.setColorFilter(new ColorMatrixColorFilter(matrix));
        if(Build.VERSION.SDK_INT>=31)background.setRenderEffect(RenderEffect.createBlurEffect(18,18,Shader.TileMode.CLAMP));
        root.addView(background,new FrameLayout.LayoutParams(-1,-1));
        content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18),dp(14),dp(18),dp(14));
        root.addView(content,new FrameLayout.LayoutParams(-1,-1));
        root.setOnApplyWindowInsetsListener((view,insets)->{
            int top=insets.getSystemWindowInsetTop(),bottom=insets.getSystemWindowInsetBottom();
            content.setPadding(dp(18),top+dp(12),dp(18),bottom+dp(12));return insets;
        });
        LinearLayout heading=new LinearLayout(this);
        TextView brand=text("MUSIC ROOM",16);brand.setTextColor(Color.rgb(105,205,189));
        heading.addView(brand,new LinearLayout.LayoutParams(0,dp(46),1));
        heading.addView(button("Settings",v->showSettings()));content.addView(heading);
        LinearLayout search=new LinearLayout(this);
        query=new EditText(this);query.setSingleLine();query.setTextColor(Color.WHITE);
        query.setHintTextColor(Color.GRAY);query.setHint("Find a song");query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        query.setOnEditorActionListener((view,action,event)->{if(action==EditorInfo.IME_ACTION_SEARCH){search();return true;}return false;});
        search.addView(query,new LinearLayout.LayoutParams(0,dp(52),1));search.addView(button("Search",v->search()));
        content.addView(search);
        message=text("Search for music to start.",12);content.addView(message);
        ListView list=new ListView(this);list.setDividerHeight(1);
        results=new ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,new ArrayList<String>()) {
            @Override public View getView(int position,View recycled,android.view.ViewGroup parent) {
                TextView row=(TextView)super.getView(position,recycled,parent);
                row.setTextColor(Color.rgb(221,230,236));row.setTextSize(14);return row;
            }
        };
        list.setAdapter(results);list.setOnItemClickListener((parent,view,index,id)->select(index));
        content.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        title=text("Nothing playing",15);title.setMaxLines(2);content.addView(title);
        video=new TextureView(this);content.addView(video,new LinearLayout.LayoutParams(-1,dp(130)));
        spectrum=new SpectrumView(this);content.addView(spectrum,new LinearLayout.LayoutParams(-1,dp(110)));
        clock=text("0:00 / --:--",12);clock.setGravity(Gravity.CENTER);content.addView(clock);
        LinearLayout controls=new LinearLayout(this);controls.setGravity(Gravity.CENTER);
        controls.addView(button("−10s",v->seek(-10000)));
        pause=button("Play",v->{if(player.isPlaying())player.pause();else player.play();});controls.addView(pause);
        controls.addView(button("+10s",v->seek(10000)));content.addView(controls);
        setContentView(root);
    }
    private void search() {
        String name=query.getText().toString().trim();if(name.isEmpty())return;
        int version=searchVersion.incrementAndGet();message.setText("Searching for “"+name+"”…");
        results.clear();songs.clear();
        workers.execute(()->{
            try {
                List<MusicSearch.Song> found=MusicSearch.search(name);
                ui.post(()->{
                    if(closed||version!=searchVersion.get())return;
                    songs.addAll(found);
                    for(MusicSearch.Song song:found)results.add(song.title+"\n"+song.artist+"  ·  "+time(song.duration*1000));
                    message.setText(found.isEmpty()?"No songs found. Try another name.":"Tap a song to play.");
                });
            } catch(Exception error) { ui.post(()->{if(!closed&&version==searchVersion.get())message.setText("Search failed: "+error.getMessage());}); }
        });
    }
    private void select(int index) {
        if(index<0||index>=songs.size())return;
        MusicSearch.Song song=songs.get(index);int version=playVersion.incrementAndGet();
        player.stop();player.clearMediaItems();spectrum.reset();title.setText(song.title);message.setText("Loading stream…");
        ((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE))
            .hideSoftInputFromWindow(query.getWindowToken(),0);
        background.setImageDrawable(null);
        workers.execute(()->loadCover(song,version));
        workers.execute(()->{
            try {
                MusicSearch.Stream stream=MusicSearch.resolve(song);
                ui.post(()->{
                    if(closed||version!=playVersion.get())return;
                    DefaultHttpDataSource.Factory http=new DefaultHttpDataSource.Factory().setUserAgent("Mozilla/5.0");
                    DefaultMediaSourceFactory factory=new DefaultMediaSourceFactory(new DefaultDataSource.Factory(this,http));
                    MediaSource source=factory.createMediaSource(MediaItem.fromUri(stream.audio));
                    if(!stream.video.isEmpty())source=new MergingMediaSource(source,factory.createMediaSource(MediaItem.fromUri(stream.video)));
                    player.setMediaSource(source);player.prepare();player.play();
                });
            } catch(Exception error) { ui.post(()->{if(!closed&&version==playVersion.get())message.setText("Cannot load this song: "+error.getMessage());}); }
        });
    }
    private void loadCover(MusicSearch.Song song,int version) {
        if(song.thumbnail.isEmpty())return;
        try(okhttp3.Response response=MusicSearch.HTTP.newCall(new okhttp3.Request.Builder().url(song.thumbnail).build()).execute()) {
            if(response.body()==null||!response.isSuccessful())return;
            byte[] bytes=response.body().bytes();
            BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
            BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
            options.inSampleSize=Math.max(1,options.outWidth/1280);options.inJustDecodeBounds=false;
            Bitmap cover=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
            ui.post(()->{if(!closed&&version==playVersion.get())background.setImageBitmap(cover);else if(cover!=null)cover.recycle();});
        } catch(Exception ignored) { /* Music can play even if the cover is unavailable. */ }
    }
    private void seek(long amount) {
        long position=Math.max(0,player.getCurrentPosition()+amount),duration=player.getDuration();
        if(duration>0)position=Math.min(position,duration);player.seekTo(position);
    }
    private void showSettings() {
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(24),dp(8),dp(24),0);
        form.addView(text("Bar count (8–128)",14));EditText count=number(""+spectrum.count);form.addView(count);
        form.addView(text("Color changes per second (0.1–60)",14));EditText speed=number(""+spectrum.colorSpeed);form.addView(speed);
        form.addView(text("Video color samples per second (1–10)",14));EditText frames=number(""+framesPerSecond);form.addView(frames);
        new AlertDialog.Builder(this).setTitle("Visualizer settings").setView(form).setNegativeButton("Cancel",null)
            .setPositiveButton("Save",(dialog,which)->{
                try {
                    int bars=Math.max(8,Math.min(128,Integer.parseInt(count.getText().toString())));
                    float color=Math.max(.1f,Math.min(60,Float.parseFloat(speed.getText().toString())));
                    int fps=Math.max(1,Math.min(10,Integer.parseInt(frames.getText().toString())));
                    if(!Float.isFinite(color))return;
                    spectrum.count=bars;spectrum.colorSpeed=color;framesPerSecond=fps;
                    settings.edit().putInt("bars",bars).putFloat("colorSpeed",color).putInt("videoFps",fps).apply();
                }catch(NumberFormatException ignored){message.setText("Enter valid settings numbers.");}
            }).show();
    }
    private EditText number(String value) { EditText input=new EditText(this);input.setText(value);input.setInputType(8194);return input; }
    private Button button(String label,View.OnClickListener action) { Button button=new Button(this);button.setText(label);button.setOnClickListener(action);return button; }
    private TextView text(String value,int size) { TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(Color.rgb(212,224,232));view.setPadding(0,dp(4),0,dp(4));return view; }
    private int dp(int value) { return (int)(value*getResources().getDisplayMetrics().density+.5f); }
    private static String time(long value) { return value<0?"--:--":String.format(Locale.ROOT,"%d:%02d",value/60000,value/1000%60); }
    @Override protected void onStart() { super.onStart();ui.removeCallbacks(update);ui.post(update); }
    @Override protected void onStop() { super.onStop();ui.removeCallbacks(update);if(player!=null)player.pause(); }
    @Override protected void onDestroy() {
        closed=true;searchVersion.incrementAndGet();playVersion.incrementAndGet();ui.removeCallbacksAndMessages(null);
        workers.shutdownNow();if(player!=null){player.release();player=null;}super.onDestroy();
    }
}
