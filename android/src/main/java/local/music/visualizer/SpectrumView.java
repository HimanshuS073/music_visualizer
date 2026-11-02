package local.music.visualizer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;
import androidx.media3.common.C;
import androidx.media3.exoplayer.audio.TeeAudioProcessor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

// Audio comes directly from this player's PCM, without recording the microphone.
final class SpectrumView extends View implements TeeAudioProcessor.AudioBufferSink {
    private final int N=1024;
    private final double[] samples=new double[N],real=new double[N],imag=new double[N];
    private final double[] window=new double[N],cosine=new double[N/2],sine=new double[N/2];
    private final int[] reverse=new int[N];
    private final float[] target=new float[128],height=new float[128];
    private final int[] colors=new int[128];
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random random=new Random();
    private int channels=2,encoding=C.ENCODING_PCM_16BIT,rate=44100,filled=0;
    private int[] palette={Color.rgb(92,185,174),Color.rgb(129,155,205)};
    private boolean playing=false;
    private long previous=0,nextColor=0;
    int count=48;
    float colorSpeed=10;
    SpectrumView(Context context) {
        super(context);
        for(int i=0;i<colors.length;i++)colors[i]=palette[i%palette.length];
        for(int i=0;i<N;i++) {
            reverse[i]=Integer.reverse(i) >>> (32-Integer.numberOfTrailingZeros(N));
            window[i]=.5-.5*Math.cos(2*Math.PI*i/(N-1));
        }
        for(int i=0;i<N/2;i++){cosine[i]=Math.cos(2*Math.PI*i/N);sine[i]=-Math.sin(2*Math.PI*i/N);}
    }
    void setPlaying(boolean value) { playing=value; if(value)postInvalidateOnAnimation();else invalidate(); }
    void setPalette(int[] value) { if(value.length>0)palette=value; }
    void reset() { synchronized(target) { java.util.Arrays.fill(target,0); } java.util.Arrays.fill(height,0);invalidate(); }
    @Override public void flush(int sampleRateHz,int channelCount,int encoding) {
        this.rate=sampleRateHz;this.channels=channelCount;this.encoding=encoding;filled=0;
    }
    @Override public void handleBuffer(ByteBuffer buffer) {
        ByteBuffer data=buffer.duplicate().order(ByteOrder.nativeOrder());
        int bytes=encoding==C.ENCODING_PCM_FLOAT?4:2;
        if(encoding!=C.ENCODING_PCM_FLOAT && encoding!=C.ENCODING_PCM_16BIT)return;
        while(data.remaining()>=channels*bytes) {
            double sample=0;
            for(int i=0;i<channels;i++)sample+=bytes==4?data.getFloat():data.getShort()/32768.0;
            samples[filled++]=sample/channels;
            if(filled==N) { transform();filled=0; }
        }
    }
    private void transform() {
        for(int i=0;i<N;i++) {
            int reversed=reverse[i];
            real[reversed]=samples[i]*window[i];imag[reversed]=0;
        }
        for(int length=2;length<=N;length*=2) {
            int half=length/2;
            for(int start=0;start<N;start+=length)for(int j=0;j<half;j++) {
                int a=start+j,b=a+half;
                int angle=j*N/length;double c=cosine[angle],s=sine[angle];
                double r=c*real[b]-s*imag[b],im=c*imag[b]+s*real[b];
                real[b]=real[a]-r;imag[b]=imag[a]-im;real[a]+=r;imag[a]+=im;
            }
        }
        synchronized(target) {
            for(int bar=0;bar<count;bar++) {
                int low=Math.max(1,(int)(40*Math.pow(250,(double)bar/count)*N/rate));
                int high=Math.min(N/2,Math.max(low+1,(int)(40*Math.pow(250,(double)(bar+1)/count)*N/rate)));
                double peak=0;
                for(int bin=low;bin<high;bin++)peak=Math.max(peak,Math.hypot(real[bin],imag[bin])*4/N);
                double db=20*Math.log10(Math.max(1e-9,peak));
                target[bar]=(float)Math.max(0,Math.min(1,(db+65)/57));
            }
        }
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        long now=android.os.SystemClock.uptimeMillis();
        float dt=previous==0?.016f:Math.min(.1f,(now-previous)/1000f);previous=now;
        if(playing && now>=nextColor) {
            for(int i=0;i<count;i++)colors[i]=palette[random.nextInt(palette.length)];
            nextColor=now+(long)(1000/Math.max(.1f,colorSpeed));
        }
        float width=getWidth()/(float)count;
        boolean moving=false;
        synchronized(target) {
            for(int i=0;i<count;i++) {
                float value=playing?target[i]:0;
                float speed=value>height[i]?40:15;
                height[i]+=(value-height[i])*(1-(float)Math.exp(-speed*dt));
                if(height[i]>.001f)moving=true;
                paint.setColor(colors[i]);paint.setAlpha(185);
                canvas.drawRoundRect(i*width+1,getHeight()-height[i]*getHeight(),(i+1)*width-1,getHeight(),2,2,paint);
            }
        }
        if(playing||moving)postInvalidateOnAnimation();
    }
}
