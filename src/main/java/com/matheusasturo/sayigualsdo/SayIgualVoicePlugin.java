package com.matheusasturo.sayigualsdo;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;

/**
 * Plugin do Simple Voice Chat: escuta o microfone de todos os jogadores no servidor.
 */
public class SayIgualVoicePlugin implements VoicechatPlugin {

    @Override
    public String getPluginId() {
        return SayIgualMod.MOD_ID;
    }

    @Override
    public void initialize(VoicechatApi api) {
        SayIgualMod.LOGGER.info("SayIgual conectado ao Simple Voice Chat");
        SayIgualMod.speech().setVoicechat(api);
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophone);
    }

    private void onMicrophone(MicrophonePacketEvent event) {
        VoicechatConnection sender = event.getSenderConnection();
        if (sender == null) {
            return;
        }
        SayIgualMod.speech().onMicrophonePacket(sender.getPlayer().getUuid(), event.getPacket().getOpusEncodedData());
    }
}
