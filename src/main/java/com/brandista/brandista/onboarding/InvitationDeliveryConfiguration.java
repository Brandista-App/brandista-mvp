package com.brandista.brandista.onboarding;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

@Configuration(proxyBeanMethods=false)
public class InvitationDeliveryConfiguration {
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(InvitationDelivery.class)
    InvitationDelivery invitationDelivery(Environment environment,ObjectMapper json) {
        if(!environment.getProperty("brandista.invitations.dev-delivery-enabled",Boolean.class,false))
            return (id,email,token) -> { throw new OnboardingFailure(SERVICE_UNAVAILABLE,"Invitation delivery is not configured."); };
        if(!environment.acceptsProfiles(Profiles.of("invitation-dev")) || environment.acceptsProfiles(Profiles.of("prod","production")))
            throw new IllegalStateException("Development invitation delivery requires an isolated invitation-dev profile.");
        try {
            byte[] key=Base64.getDecoder().decode(environment.getRequiredProperty("brandista.invitations.dev-delivery-key-base64"));
            if(key.length!=32) throw new IllegalArgumentException();
            Path directory=Path.of(environment.getRequiredProperty("brandista.invitations.dev-delivery-directory")).toAbsolutePath();
            Files.createDirectories(directory);
            return new EncryptedDevelopmentDelivery(directory,key,json);
        } catch(Exception invalid) { throw new IllegalStateException("Development invitation delivery configuration is invalid."); }
    }

    /** Development-only encrypted files; no HTTP retrieval endpoint or raw-token files. */
    public static final class EncryptedDevelopmentDelivery implements InvitationDelivery {
        private final Path directory;
        private final byte[] key;
        private final ObjectMapper json;
        public EncryptedDevelopmentDelivery(Path directory,byte[] key,ObjectMapper json) {
            this.directory=directory; this.key=key.clone(); this.json=json;
        }
        @Override public void deliver(long id,String email,String token) {
            byte[] plaintext=null;
            Path temporary=null;
            try {
                plaintext=json.writeValueAsBytes(Map.of("invitationId",id,"recipientEmail",email,"token",token));
                byte[] nonce=new byte[12]; new SecureRandom().nextBytes(nonce);
                Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
                byte[] ciphertext=cipher.doFinal(plaintext);
                byte[] envelope=new byte[nonce.length+ciphertext.length];
                System.arraycopy(nonce,0,envelope,0,nonce.length);
                System.arraycopy(ciphertext,0,envelope,nonce.length,ciphertext.length);
                temporary=Files.createTempFile(directory,"delivery-",".tmp");
                Files.write(temporary,envelope);
                Files.move(temporary,directory.resolve(id+"-"+UUID.randomUUID()+".invitation.enc"),StandardCopyOption.ATOMIC_MOVE);
            } catch(Exception unavailable) {
                throw new OnboardingFailure(SERVICE_UNAVAILABLE,"Invitation delivery failed.");
            } finally {
                if(plaintext!=null) Arrays.fill(plaintext,(byte)0);
                if(temporary!=null) try { Files.deleteIfExists(temporary); } catch(Exception ignored) { }
            }
        }
    }
}
