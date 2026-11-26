package uk.hamdigital.icom;
/**
 * WIFI模式下iCom电台操作。
 * @author BGY70Z
 * @date 2023-03-20
 */

import android.media.AudioTrack;
import android.net.Network;

import uk.hamdigital.icom.IcomUdpBase.IcomUdpStyle;

import java.io.IOException;

public class IComWifiRig extends WifiRig{
    private final int civRigPortOverride;
    private final int audioRigPortOverride;
    private volatile boolean closed = false;

    private final Network preferredNetwork;

    public IComWifiRig(String ip, int port, String userName, String password,
                       int civRigPortOverride, int audioRigPortOverride, Network preferredNetwork) {
        super(ip,port,userName,password);
        this.civRigPortOverride = civRigPortOverride;
        this.audioRigPortOverride = audioRigPortOverride;
        this.preferredNetwork = preferredNetwork;
    }

    @Override
    public void start(){
        if (closed) return;
        opened=true;
        controlUdp=new IcomControlUdp(userName,password,ip,port,civRigPortOverride,audioRigPortOverride,preferredNetwork);
        if (!opened) { controlUdp.closeAll(); return; }

        //设置事件，这里可以处理电台状态，和接收电台送来的音频数据
        controlUdp.setOnStreamEvents(new IcomUdpBase.OnStreamEvents() {
            @Override
            public void OnReceivedIAmHere(byte[] data) {

            }

            @Override
            public void OnReceivedCivData(byte[] data) {
                if (onDataEvents!=null){
                    onDataEvents.onReceivedCivData(data);
                }
            }

            @Override
            public void OnReceivedAudioData(byte[] audioData) {
                if (isPttOn) return; // never feed the phone speaker back into the operator while TX is active
                if (onDataEvents!=null){
                    onDataEvents.onReceivedWaveData(audioData);
                }
                if (audioTrack!=null){
                    audioTrack.write(audioData, 0, audioData.length, AudioTrack.WRITE_NON_BLOCKING);
                }
            }

            @Override
            public void OnUdpSendIOException(IcomUdpStyle style,IOException e) {
                if (onStatus != null) onStatus.onStatus("网络错误（" + IcomUdpBase.getUdpStyle(style) + "）：" + e.getMessage(), false);
                close();
            }

            @Override
            public void OnTransportReady() {
                if (onStatus != null) onStatus.onStatus("IC-705 传输通道已就绪", true);
            }

            @Override
            public void OnTransportRejected(String reason) {
                if (onStatus != null) onStatus.onStatus(reason, false);
                close();
            }

            @Override
            public void OnLoginResponse(boolean authIsOK) {
                if (authIsOK){
                    if (onStatus != null) onStatus.onStatus("认证成功，正在建立传输通道…", false);
                }else {
                    if (onStatus != null) onStatus.onStatus("认证未通过：用户名或密码错误", false);
                    controlUdp.closeAll();
                }
            }

        });
        if (!opened) { controlUdp.closeAll(); return; }
        controlUdp.openStream();
        if (!opened) { controlUdp.closeAll(); return; }
        controlUdp.startAreYouThereTimer();
    }

    @Override
    public synchronized void setPttOn(boolean on){
        isPttOn=on;
        controlUdp.civUdp.sendPttAction(on);
        controlUdp.audioUdp.isPttOn=on;
    }

    @Override
    public void sendCivData(byte[] data){
        controlUdp.sendCivData(data);
    }

    @Override
    public void sendWaveData(float[] data){//发送音频数据到电台
        controlUdp.sendWaveData(data);
    }

    /** Fast, non-blocking PTT release used as soon as a disconnect starts. */
    public void quickUnkey() {
        try {
            if (controlUdp != null && controlUdp.civUdp != null) {
                if (controlUdp.audioUdp != null) controlUdp.audioUdp.stopTXAudio();
                controlUdp.civUdp.sendPttAction(false);
            }
        } catch (Exception ignored) {
        } finally {
            isPttOn = false;
        }
    }

    /**
     * 关闭各种连接，以及音频
     */
    public void forceUnkey() {
        try {
            if (controlUdp != null && controlUdp.civUdp != null) {
                if (controlUdp.audioUdp != null) controlUdp.audioUdp.stopTXAudio();
                controlUdp.civUdp.sendPttAction(false);
                Thread.sleep(80L);
                controlUdp.civUdp.sendPttAction(false);
                Thread.sleep(120L);
            }
        } catch (Exception ignored) {
        } finally {
            isPttOn = false;
        }
    }

    public synchronized void close(){
        forceUnkey();
        closed=true;
        opened=false;
        if (controlUdp != null) controlUdp.closeAll();
        closeAudio();
    }


}
