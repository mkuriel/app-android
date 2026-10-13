package com.mariopitch.livebpm;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;

/** Fast real-time tempo estimator tuned for dance music and live microphone input. */
final class BeatDetector {
    static final class Result {
        final float bpm, confidence, level; final boolean locked;
        Result(float bpm, float confidence, float level, boolean locked) { this.bpm=bpm; this.confidence=confidence; this.level=level; this.locked=locked; }
    }

    private static final int HISTORY = 384;
    private final int sampleRate;
    private final SpectrumAnalyzer spectrum;
    private final float[] novelty = new float[HISTORY];
    private int write, frames;
    private long samples;
    private double lastOnsetSample;
    private float previousPreviousOnset, previousOnset;
    private int previousPeakIndex;
    private final ArrayDeque<Double> onsets=new ArrayDeque<>();
    private float lowPass, slowEnergy=.01f, slowNovelty=.001f, previousEnergy;
    private float displayedBpm, displayedConfidence;
    private final float[] candidates=new float[32];
    private int candidateWrite,candidateCount,changeVotes;
    private float lockedBpm,changeTarget;
    private com.kottov.btt_engine.BttNative nativeEngine;
    private long nativeHandle;
    private float nativeBpm;
    private int nativeFrames;

    BeatDetector(int sampleRate) {
        this.sampleRate=sampleRate;
        this.spectrum=new SpectrumAnalyzer(sampleRate);
        try {
            nativeEngine=new com.kottov.btt_engine.BttNative();
            nativeHandle=nativeEngine.initBtt();
            if(nativeHandle==0)nativeEngine=null;
        } catch(Throwable ignored) {
            nativeEngine=null;
            nativeHandle=0;
        }
    }

    synchronized Result process(short[] data, int count) {
        if(nativeEngine!=null&&nativeHandle!=0)return processNative(data,count);
        double full=0, bass=0; float peak=0; int peakIndex=0;
        for(int i=0;i<count;i++) {
            float x=data[i]/32768f;
            lowPass += .045f*(x-lowPass);
            full += x*x;
            bass += lowPass*lowPass;
            float magnitude=Math.abs(lowPass);
            if(magnitude>peak){peak=magnitude;peakIndex=i;}
        }
        float fullRms=(float)Math.sqrt(full/Math.max(1,count));
        float bassRms=(float)Math.sqrt(bass/Math.max(1,count));
        float energy=bassRms;
        slowEnergy=slowEnergy*.985f+energy*.015f;

        float change=Math.max(0,energy-previousEnergy*.82f-slowEnergy*.035f);
        previousEnergy=energy;
        slowNovelty=slowNovelty*.97f+change*.03f;
        float energyOnset=Math.min(4f,change/Math.max(.0005f,slowNovelty));
        float spectralOnset=spectrum.process(data,count);
        float onset=energyOnset*.58f+spectralOnset*.42f;
        novelty[write]=onset;
        write=(write+1)%HISTORY;
        frames++;
        samples+=count;
        if(previousOnset>1.35f && previousOnset>onset && previousOnset>previousPreviousOnset && samples-lastOnsetSample>sampleRate*.24f) {
            lastOnsetSample=samples-2.0*count+previousPeakIndex;
            onsets.add(lastOnsetSample);
            while(onsets.size()>10)onsets.removeFirst();
        }
        previousPreviousOnset=previousOnset;
        previousOnset=onset;
        previousPeakIndex=peakIndex;

        if(frames>=64 && (frames&7)==0) estimate(count);
        float level=Math.min(1f,fullRms*7f);
        return new Result(displayedBpm,displayedConfidence,level,lockedBpm>0);
    }

    private Result processNative(short[] data,int count) {
        double energy=0;
        for(int i=0;i<count;i++){float x=data[i]/32768f;energy+=x*x;}
        float level=Math.min(1f,(float)Math.sqrt(energy/Math.max(1,count))*7f);
        try {
            nativeEngine.processBtt(nativeHandle,data,count);
            double measured=nativeEngine.getBpm(nativeHandle);
            double certainty=nativeEngine.getCertainty(nativeHandle);
            double stability=nativeEngine.bttGetStability(nativeHandle);
            double quality=nativeEngine.bttGetSignalQuality(nativeHandle);
            if(Double.isFinite(measured)&&measured>=35&&measured<=260)nativeBpm=(float)measured;
            float confidence=(float)Math.max(certainty,Math.max(stability,quality*.8));
            if(!Float.isFinite(confidence))confidence=0;
            confidence=Math.max(0,Math.min(1,confidence));
            // Apply the same temporal lock to the native engine. Sampling every
            // eight audio blocks avoids reacting to individual noisy estimates.
            if(nativeBpm>0&&confidence>.30f&&(++nativeFrames&7)==0)stabilize(nativeBpm,confidence);
            return new Result(displayedBpm,displayedConfidence,level,lockedBpm>0);
        } catch(Throwable failure) {
            close();
            return process(data,count);
        }
    }

    synchronized void close() {
        if(nativeEngine!=null&&nativeHandle!=0){
            try{nativeEngine.destroyBtt(nativeHandle);}catch(Throwable ignored){}
        }
        nativeHandle=0;
        nativeEngine=null;
    }

    private void estimate(int hop) {
        int available=Math.min(frames,HISTORY);
        float frameRate=sampleRate/(float)hop;
        int minLag=Math.max(2,Math.round(frameRate*60f/200f));
        int maxLag=Math.min(available/2,Math.round(frameRate*60f/60f));
        if(maxLag<=minLag+2)return;

        float[] correlation=new float[maxLag+1];
        for(int lag=minLag;lag<=maxLag;lag++) {
            double cross=0,a2=0,b2=0;
            int pairs=Math.min(available-lag,300);
            for(int j=0;j<pairs;j++) {
                float a=get(j),b=get(j+lag);
                cross+=a*b; a2+=a*a; b2+=b*b;
            }
            correlation[lag]=(float)(cross/Math.sqrt(Math.max(1e-9,a2*b2)));
        }
        float[] score=new float[maxLag+1];
        float best=-1; int bestLag=minLag;
        for(int lag=minLag;lag<=maxLag;lag++) {
            float bpm=frameRate*60f/lag;
            float prior=(bpm>=82&&bpm<=180)?1f:.94f;
            float harmonic=lag*2<=maxLag?correlation[lag*2]*.32f:0;
            score[lag]=(correlation[lag]+harmonic)*prior;
            if(score[lag]>best){best=score[lag];bestLag=lag;}
        }

        float refined=bestLag;
        if(bestLag>minLag&&bestLag<maxLag) {
            float left=score[bestLag-1],center=score[bestLag],right=score[bestLag+1];
            float denominator=left-2*center+right;
            if(Math.abs(denominator)>.0001f) refined+=.5f*(left-right)/denominator;
        }
        float raw=frameRate*60f/refined;
        while(raw<60)raw*=2; while(raw>200)raw/=2;
        float confidence=Math.max(0,Math.min(1,(best-.12f)/.58f));

        // Beat-to-beat timing gives sub-frame precision and stabilizes the decimals.
        float timingConfidence=0;
        boolean hasTiming=onsets.size()>=6;
        if(hasTiming) {
            ArrayList<Float> intervals=new ArrayList<>();
            Double previous=null;
            for(Double current:onsets) {
                if(previous!=null) {
                    float beat=(float)(60.0*sampleRate/(current-previous));
                    while(beat<60)beat*=2; while(beat>200)beat/=2;
                    intervals.add(beat);
                }
                previous=current;
            }
            Collections.sort(intervals);
            float timed=intervals.get(intervals.size()/2);
            float deviation=0;
            for(float value:intervals)deviation+=Math.abs(value-timed);
            deviation/=intervals.size();
            timingConfidence=Math.max(0,1-deviation/Math.max(1,timed*.10f));
            raw=timed;
            confidence=Math.max(confidence,timingConfidence);
        }

        if(hasTiming && confidence>.45f) stabilize(raw,confidence);
    }

    private void stabilize(float raw,float confidence) {
        if(lockedBpm>0) {
            float half=raw*.5f,twice=raw*2;
            if(half>=60&&Math.abs(half-lockedBpm)<Math.abs(raw-lockedBpm))raw=half;
            if(twice<=200&&Math.abs(twice-lockedBpm)<Math.abs(raw-lockedBpm))raw=twice;
        } else if(candidateCount>0) {
            float center=densestCenter(4f),half=raw*.5f,twice=raw*2;
            if(half>=60&&Math.abs(half-center)<Math.abs(raw-center))raw=half;
            if(twice<=200&&Math.abs(twice-center)<Math.abs(raw-center))raw=twice;
        } else {
            // Dance-music octave preference for the first usable estimate.
            if(raw<75&&raw*2<=200)raw*=2;
            else if(raw>190&&raw*.5f>=60)raw*=.5f;
        }
        addCandidate(raw);

        if(lockedBpm==0) {
            float center=densestCenter(2.4f);
            int support=support(center,2.4f);
            if(support>=8) {
                lockedBpm=clusterMean(center,2.4f);
                displayedBpm=round2(lockedBpm);
                displayedConfidence=Math.min(1,support/12f);
            }
            return;
        }

        float difference=Math.abs(raw-lockedBpm);
        if(difference<=3.2f) {
            float center=densestCenter(2.2f);
            float stable=clusterMean(center,2.2f);
            lockedBpm=lockedBpm*.92f+stable*.08f;
            displayedBpm=round2(lockedBpm);
            displayedConfidence=displayedConfidence*.85f+confidence*.15f;
            changeVotes=0;
        } else {
            if(changeVotes==0||Math.abs(raw-changeTarget)>3f){changeTarget=raw;changeVotes=1;}
            else {changeTarget=changeTarget*.75f+raw*.25f;changeVotes++;}
            // A new range must persist for roughly 1.3 seconds.
            if(changeVotes>=14) {
                lockedBpm=changeTarget;displayedBpm=round2(lockedBpm);
                candidateCount=0;candidateWrite=0;changeVotes=0;
                addCandidate(lockedBpm);
            }
            displayedConfidence*=.98f;
        }
    }

    private void addCandidate(float value){candidates[candidateWrite]=value;candidateWrite=(candidateWrite+1)%candidates.length;if(candidateCount<candidates.length)candidateCount++;}
    private int support(float center,float radius){int n=0;for(int i=0;i<candidateCount;i++)if(Math.abs(candidates[i]-center)<=radius)n++;return n;}
    private float densestCenter(float radius){float best=candidates[0];int count=-1;for(int i=0;i<candidateCount;i++){int n=support(candidates[i],radius);if(n>count){count=n;best=candidates[i];}}return best;}
    private float clusterMean(float center,float radius){float sum=0;int n=0;for(int i=0;i<candidateCount;i++)if(Math.abs(candidates[i]-center)<=radius){sum+=candidates[i];n++;}return n>0?sum/n:center;}
    private float round2(float value){return Math.round(value*100f)/100f;}

    private float get(int j) {
        int available=Math.min(frames,HISTORY);
        int oldest=(write-available+HISTORY)%HISTORY;
        return novelty[(oldest+j)%HISTORY];
    }

    /** 2048-point overlapping FFT and multi-band positive spectral-flux detector. */
    private static final class SpectrumAnalyzer {
        private static final int SIZE=2048;
        private final int sampleRate;
        private final double[] audio=new double[SIZE],real=new double[SIZE],imag=new double[SIZE],window=new double[SIZE];
        private final float[] previousMagnitude=new float[SIZE/2+1];
        private float slowBass=.0001f,slowMid=.0001f;
        private int filled;

        SpectrumAnalyzer(int sampleRate){this.sampleRate=sampleRate;for(int i=0;i<SIZE;i++)window[i]=.5-.5*Math.cos(2*Math.PI*i/(SIZE-1));}

        float process(short[] input,int count){
            int n=Math.min(count,SIZE);
            System.arraycopy(audio,n,audio,0,SIZE-n);
            for(int i=0;i<n;i++)audio[SIZE-n+i]=input[count-n+i]/32768.0;
            filled=Math.min(SIZE,filled+n);
            if(filled<SIZE)return 0;
            for(int i=0;i<SIZE;i++){real[i]=audio[i]*window[i];imag[i]=0;}
            fft(real,imag);
            int bassStart=Math.max(1,Math.round(35f*SIZE/sampleRate));
            int bassEnd=Math.round(260f*SIZE/sampleRate);
            int midEnd=Math.round(2200f*SIZE/sampleRate);
            float bassFlux=0,midFlux=0;
            for(int bin=bassStart;bin<=midEnd;bin++){
                float magnitude=(float)Math.sqrt(real[bin]*real[bin]+imag[bin]*imag[bin]);
                float positive=Math.max(0,magnitude-previousMagnitude[bin]);
                previousMagnitude[bin]=magnitude;
                if(bin<=bassEnd)bassFlux+=positive/(float)Math.sqrt(bin);
                else midFlux+=positive/(float)Math.sqrt(bin);
            }
            slowBass=slowBass*.975f+bassFlux*.025f;
            slowMid=slowMid*.975f+midFlux*.025f;
            float bass=Math.min(4f,bassFlux/Math.max(.0001f,slowBass));
            float mid=Math.min(4f,midFlux/Math.max(.0001f,slowMid));
            // Mid-band attacks contribute only when supported by low-frequency energy.
            return bass*.86f+Math.min(mid,bass*1.15f)*.14f;
        }

        private static void fft(double[] re,double[] im){
            int n=re.length;
            for(int i=1,j=0;i<n;i++){int bit=n>>1;for(; (j&bit)!=0;bit>>=1)j^=bit;j^=bit;if(i<j){double t=re[i];re[i]=re[j];re[j]=t;t=im[i];im[i]=im[j];im[j]=t;}}
            for(int len=2;len<=n;len<<=1){double angle=-2*Math.PI/len,wLenR=Math.cos(angle),wLenI=Math.sin(angle);for(int i=0;i<n;i+=len){double wr=1,wi=0;for(int j=0;j<len/2;j++){int u=i+j,v=i+j+len/2;double vr=re[v]*wr-im[v]*wi,vi=re[v]*wi+im[v]*wr;re[v]=re[u]-vr;im[v]=im[u]-vi;re[u]+=vr;im[u]+=vi;double next=wr*wLenR-wi*wLenI;wi=wr*wLenI+wi*wLenR;wr=next;}}}
        }
    }
}
