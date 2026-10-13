package com.mariopitch.livebpm;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.media.*;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import java.util.Arrays;
import java.util.Locale;

final class BpmView extends View {
    private final Paint p=new Paint(3); private final Paint stroke=new Paint(3); private final Bitmap logo;
    private final RectF actionButton=new RectF(),cabinButton=new RectF();
    private final float[] history=new float[80]; private int historyPos;
    private volatile float bpm,confidence,level; private volatile boolean running,permissionDenied,bpmLocked,cabinMode; private volatile String errorMessage;
    private volatile long bassFlashUntil;
    private AudioRecord recorder; private Thread worker;
    private final int red=Color.rgb(255,24,35), blue=Color.rgb(8,123,200), bg=Color.rgb(7,21,37), card=Color.rgb(13,37,62);
    BpmView(Context c){super(c);stroke.setStyle(Paint.Style.STROKE);stroke.setStrokeWidth(dp(2));setBackgroundColor(bg);logo=BitmapFactory.decodeResource(getResources(),R.drawable.wakeup_bpm);}
    float dp(float n){return n*getResources().getDisplayMetrics().density;}

    void startListening(){
        if(running)return;
        if(getContext().checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            if(getContext() instanceof MainActivity)((MainActivity)getContext()).requestMicrophone();
            return;
        }
        try {
            int rate=44100,min=AudioRecord.getMinBufferSize(rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
            if(min<=0) min=8192;
            final AudioRecord rec=new AudioRecord(MediaRecorder.AudioSource.MIC,rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(min*2,8192));
            if(rec.getState()!=AudioRecord.STATE_INITIALIZED){rec.release();showAudioError("No se pudo iniciar el micrófono");return;}
            recorder=rec; running=true; permissionDenied=false; errorMessage=null;bpm=0;confidence=0;bpmLocked=false;Arrays.fill(history,0);historyPos=0;BeatDetector detector=new BeatDetector(rate);
            worker=new Thread(()->{try{short[] b=new short[512];long lastHistory=0;float averageLevel=.02f;rec.startRecording();while(running){int n=rec.read(b,0,b.length);if(n>0){BeatDetector.Result r=detector.process(b,n);long now=SystemClock.elapsedRealtime();level=r.level;averageLevel=averageLevel*.94f+r.level*.06f;if(r.level>Math.max(.13f,averageLevel*1.55f))bassFlashUntil=now+130;if(r.bpm>0)bpm=r.bpm;confidence=r.confidence;bpmLocked=r.locked;if(r.bpm>0&&now-lastHistory>=100){lastHistory=now;history[historyPos++%history.length]=r.bpm;}postInvalidateOnAnimation();}}}catch(Throwable e){if(running)showAudioError("Error al leer el micrófono");}finally{detector.close();try{rec.release();}catch(Throwable ignored){}recorder=null;}},"BPM audio");worker.start();
        } catch(Throwable e){showAudioError("No se pudo acceder al micrófono");}
    }
    private void showAudioError(String message){running=false;errorMessage=message;postInvalidate();}
    void stopListening(){running=false;AudioRecord rec=recorder;recorder=null;if(rec!=null){try{rec.stop();}catch(Exception ignored){}try{rec.release();}catch(Exception ignored){}}worker=null;invalidate();}
    void showPermissionMessage(){permissionDenied=true;invalidate();}
    @Override protected void onDraw(Canvas c){super.onDraw(c);float w=getWidth(),h=getHeight(),pad=dp(20);boolean landscape=w>h;
        float logoH=landscape?dp(140):dp(120),logoW=logoH*logo.getWidth()/logo.getHeight();
        float logoLeft=landscape?(w*.44f-logoW)/2:(w-logoW)/2,logoTop=landscape?dp(30):dp(46);
        RectF logoBox=new RectF(logoLeft,logoTop,logoLeft+logoW,logoTop+logoH);c.drawBitmap(logo,null,logoBox,p);
        if(cabinMode)drawCabin(c,w,h,pad,logoBox.bottom,landscape);else if(landscape) drawLandscape(c,w,h,pad,logoBox.bottom); else drawPortrait(c,w,h,pad,logoBox.bottom);
        if(permissionDenied){p.setColor(Color.argb(235,8,16,20));c.drawRect(0,0,w,h,p);p.setColor(Color.WHITE);p.setTextSize(dp(18));c.drawText("Activa el permiso del micrófono",w/2,h/2,p);p.setTextSize(dp(13));p.setColor(Color.LTGRAY);c.drawText("Ajustes  ›  Aplicaciones  ›  Wake UP! BPM",w/2,h/2+dp(30),p);}p.setTextAlign(Paint.Align.LEFT);
        if(errorMessage!=null){p.setColor(Color.argb(235,8,16,20));c.drawRect(0,0,w,h,p);p.setColor(Color.WHITE);p.setTextAlign(Paint.Align.CENTER);p.setTextSize(dp(18));c.drawText(errorMessage,w/2,h/2,p);p.setTextSize(dp(13));p.setColor(Color.LTGRAY);c.drawText("Pulsa para volver a intentarlo",w/2,h/2+dp(30),p);p.setTextAlign(Paint.Align.LEFT);}
    }
    private void drawPortrait(Canvas c,float w,float h,float pad,float headerBottom){float cy=headerBottom+dp(105);drawBpm(c,w/2,cy,dp(88));drawTempoLights(c,pad,cy+dp(67),w-pad);RectF graph=new RectF(pad,cy+dp(92),w-pad,Math.max(cy+dp(150),h-dp(205)));drawGraph(c,graph);drawStability(c,pad,graph.bottom+dp(25),w-pad);setBottomButtons(w,h,pad,w-pad);drawButtons(c);drawMicLevel(c,pad,h-dp(25),w-pad);}
    private void drawLandscape(Canvas c,float w,float h,float pad,float headerBottom){float split=w*.44f,centerY=headerBottom+(h-headerBottom)*.30f;drawBpm(c,split/2,centerY,dp(80));drawTempoLights(c,pad,centerY+dp(64),split-dp(18));RectF graph=new RectF(split,headerBottom+dp(8),w-pad,h-dp(92));drawGraph(c,graph);drawStability(c,split,graph.bottom+dp(25),w-pad);setBottomButtons(w,h,pad,split-dp(18));drawButtons(c);drawMicLevel(c,split,h-dp(15),w-pad);}
    private void drawCabin(Canvas c,float w,float h,float pad,float headerBottom,boolean landscape){if(landscape){float x=w*.69f,baseline=h*.57f;drawBpm(c,x,baseline,dp(120));drawTempoLights(c,w*.47f,baseline+dp(68),w-pad);setBottomButtons(w,h,w*.47f,w-pad);drawMicLevel(c,pad,h-dp(15),w*.44f-dp(18));}else{float baseline=headerBottom+dp(150);drawBpm(c,w/2,baseline,dp(108));drawTempoLights(c,pad,baseline+dp(72),w-pad);setBottomButtons(w,h,pad,w-pad);drawMicLevel(c,pad,h-dp(25),w-pad);}drawButtons(c);}
    private void drawBpm(Canvas c,float x,float baseline,float size){boolean bass=running&&SystemClock.elapsedRealtime()<bassFlashUntil;float lampY=baseline-size*1.10f, lampW=Math.max(dp(80),size*1.25f);RectF bassLamp=new RectF(x-lampW/2,lampY,x+lampW/2,lampY+dp(12));p.setColor(bass?red:Color.rgb(91,39,46));c.drawRoundRect(bassLamp,dp(7),dp(7),p);p.setTextAlign(Paint.Align.CENTER);p.setTypeface(Typeface.create("sans",Typeface.BOLD));p.setTextSize(size);p.setColor(Color.WHITE);String value=bpm>0?String.format(Locale.US,"%.2f",bpm):"—.—";c.drawText(value,x,baseline,p);p.setTextSize(dp(16));p.setColor(red);c.drawText("BEATS PER MINUTE",x,baseline+dp(28),p);p.setTextSize(dp(14));p.setColor(Color.rgb(164,190,211));String state=running?(bpmLocked?"BPM ESTABLE":bpm>0?"●  ESTABILIZANDO…":"●  ANALIZANDO…"):"LISTO PARA INICIAR";c.drawText(state,x,baseline+dp(53),p);}
    private void drawTempoLights(Canvas c,float left,float y,float right){float gap=dp(6),segmentW=(right-left-gap*5)/6f;int lit=Math.min(6,Math.max(0,(int)Math.ceil(confidence*6)));float x=left;for(int i=0;i<6;i++){RectF segment=new RectF(x,y,x+segmentW,y+dp(12));p.setColor(i<lit?Color.rgb(79,229,113):Color.rgb(25,67,55));c.drawRoundRect(segment,dp(6),dp(6),p);x+=segmentW+gap;}}
    private void drawGraph(Canvas c,RectF graph){p.setColor(card);c.drawRoundRect(graph,dp(20),dp(20),p);stroke.setColor(Color.rgb(31,68,98));stroke.setStrokeWidth(dp(1));for(int i=1;i<4;i++){float y=graph.top+graph.height()*i/4;c.drawLine(graph.left+dp(16),y,graph.right-dp(16),y,stroke);}Path path=new Path();boolean begun=false;stroke.setColor(blue);stroke.setStrokeWidth(dp(3));for(int i=0;i<history.length;i++){float v=history[(historyPos+i)%history.length];if(v<=0)continue;float x=graph.left+dp(16)+(graph.width()-dp(32))*i/(history.length-1f);float y=graph.centerY()-(v-(bpm>0?bpm:120))*dp(2.1f);y=Math.max(graph.top+dp(14),Math.min(graph.bottom-dp(14),y));if(!begun){path.moveTo(x,y);begun=true;}else path.lineTo(x,y);}c.drawPath(path,stroke);}
    private void drawStability(Canvas c,float left,float y,float right){p.setTypeface(Typeface.create("sans",Typeface.BOLD));p.setTextAlign(Paint.Align.LEFT);p.setTextSize(dp(15));p.setColor(Color.rgb(164,190,211));c.drawText("ESTABILIDAD",left,y,p);p.setColor(Color.WHITE);p.setTextAlign(Paint.Align.RIGHT);c.drawText(Math.round(confidence*100)+"%",right,y,p);RectF bar=new RectF(left,y+dp(12),right,y+dp(23));p.setColor(Color.rgb(27,58,82));c.drawRoundRect(bar,dp(6),dp(6),p);bar.right=left+(right-left)*confidence;p.setColor(red);c.drawRoundRect(bar,dp(6),dp(6),p);}
    private void drawMicLevel(Canvas c,float left,float y,float right){float db=level>0?(float)(20*Math.log10(Math.max(.0001f,level/7f))):-80f;db=Math.max(-80,Math.min(0,db));float meterLeft=left+dp(54),meterRight=right-dp(70),gap=dp(2),count=20,segment=(meterRight-meterLeft-gap*(count-1))/count,fill=Math.max(0,Math.min(1,(db+60)/60f));p.setTypeface(Typeface.create("sans",Typeface.NORMAL));p.setTextSize(dp(11));p.setTextAlign(Paint.Align.LEFT);p.setColor(running?Color.WHITE:Color.rgb(105,122,138));p.setStyle(Paint.Style.FILL);c.drawCircle(left+dp(4),y-dp(4),dp(3),p);c.drawText("MIC",left+dp(12),y,p);for(int i=0;i<count;i++){float x=meterLeft+i*(segment+gap);RectF part=new RectF(x,y-dp(10),x+segment,y);p.setColor(i<count*fill?Color.rgb(22,207,135):Color.rgb(36,48,59));c.drawRoundRect(part,dp(2),dp(2),p);}p.setTextAlign(Paint.Align.RIGHT);p.setColor(Color.rgb(164,190,211));c.drawText(String.format(Locale.US,"%.1f dB",db),right,y,p);}
    private void setBottomButtons(float w,float h,float left,float right){float gap=dp(8),mid=(left+right)/2,top=w>h?h-dp(72):h-dp(118),bottom=w>h?h-dp(18):h-dp(64);cabinButton.set(left,top,mid-gap/2,bottom);actionButton.set(mid+gap/2,top,right,bottom);}
    private void drawButtons(Canvas c){p.setStyle(Paint.Style.FILL);p.setColor(cabinMode?Color.rgb(79,229,113):Color.rgb(24,59,84));c.drawRoundRect(cabinButton,dp(13),dp(13),p);p.setColor(cabinMode?bg:Color.WHITE);p.setTextAlign(Paint.Align.CENTER);p.setTypeface(Typeface.create("sans",Typeface.BOLD));p.setTextSize(dp(12));c.drawText(cabinMode?"SALIR MODO CABINA":"MODO CABINA",cabinButton.centerX(),cabinButton.centerY()+dp(4),p);p.setColor(running?blue:red);c.drawRoundRect(actionButton,dp(16),dp(16),p);p.setColor(Color.WHITE);p.setTextSize(dp(14));c.drawText(running?"DETENER":"INICIAR",actionButton.centerX(),actionButton.centerY()+dp(5),p);}
    @Override public boolean onTouchEvent(MotionEvent e){if(e.getAction()==MotionEvent.ACTION_UP){if(errorMessage!=null){errorMessage=null;invalidate();return true;}if(cabinButton.contains(e.getX(),e.getY())){cabinMode=!cabinMode;invalidate();return true;}if(actionButton.contains(e.getX(),e.getY())){if(running)stopListening();else startListening();return true;}}return true;}
}
