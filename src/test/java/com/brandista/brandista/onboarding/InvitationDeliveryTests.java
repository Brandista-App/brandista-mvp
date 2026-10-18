package com.brandista.brandista.onboarding;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class InvitationDeliveryTests {
    @TempDir Path directory;
    private final ObjectMapper json=new ObjectMapper();
    private final InvitationDeliveryConfiguration config=new InvitationDeliveryConfiguration();

    @Test void defaultDeliveryFailsClosedAndNeverWritesTokenFiles() throws Exception {
        var delivery=config.invitationDelivery(new MockEnvironment(),json);
        assertThatThrownBy(()->delivery.deliver(1,"synthetic@example.invalid","synthetic-token"))
                .isInstanceOf(OnboardingFailure.class).hasMessage("Invitation delivery is not configured.");
        try(var files=Files.list(directory)) { assertThat(files.count()).isZero(); }
    }

    @Test void encryptedDevelopmentDeliveryRequiresExplicitIsolatedProfileAndKey() {
        for(String profile:List.of("default","prod","production")) {
            var env=environment(); env.setActiveProfiles(profile,"invitation-dev");
            if(profile.equals("default")) env.setActiveProfiles("default");
            assertThatThrownBy(()->config.invitationDelivery(env,json)).isInstanceOf(IllegalStateException.class);
        }
        var env=environment(); env.setActiveProfiles("invitation-dev");
        env.setProperty("brandista.invitations.dev-delivery-key-base64","synthetic-bad-secret");
        assertThatThrownBy(()->config.invitationDelivery(env,json)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Development invitation delivery configuration is invalid.").hasNoCause();
    }

    @Test void encryptedEnvelopeHasFreshNonceAuthenticatedCiphertextAndNoPlaintext() throws Exception {
        byte[] key=new byte[32]; new SecureRandom().nextBytes(key);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        var env=environment().withProperty("brandista.invitations.dev-delivery-key-base64",Base64.getEncoder().encodeToString(key));
        env.setActiveProfiles("invitation-dev");
        var delivery=config.invitationDelivery(env,json);
        delivery.deliver(7,"synthetic@example.invalid",token);
        delivery.deliver(7,"synthetic@example.invalid",token);
        List<Path> files;
        try(var stream=Files.list(directory)) { files=stream.toList(); }
        assertThat(files).hasSize(2).allMatch(path->path.toString().endsWith(".invitation.enc"));
        byte[] first=Files.readAllBytes(files.get(0)), second=Files.readAllBytes(files.get(1));
        assertThat(first).isNotEqualTo(second);
        assertThat(new String(first,StandardCharsets.ISO_8859_1)).doesNotContain(token,"synthetic@example.invalid");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,Arrays.copyOf(first,12)));
        var payload=json.readTree(cipher.doFinal(first,12,first.length-12));
        assertThat(payload.path("token").asText()).isEqualTo(token);
        assertThat(payload.path("recipientEmail").asText()).isEqualTo("synthetic@example.invalid");
        assertThat(payload.path("invitationId").asLong()).isEqualTo(7);
        first[first.length-1]^=1;
        assertThatThrownBy(()->cipher.doFinal(first,12,first.length-12)).isInstanceOf(AEADBadTagException.class);
    }

    private MockEnvironment environment() { return new MockEnvironment()
            .withProperty("brandista.invitations.dev-delivery-enabled","true")
            .withProperty("brandista.invitations.dev-delivery-directory",directory.toString()); }
}
