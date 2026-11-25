package com.brandista.brandista.onboarding;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Only SHA-256 token hashes are persisted. Nonces are not bearer tokens. */
@Component
public class InvitationTokens {
    private final byte[] key;
    public InvitationTokens(@Value("${brandista.invitations.token-key-base64:}") String configured) {
        try { key=configured.isBlank()?new byte[0]:Base64.getDecoder().decode(configured); }
        catch(IllegalArgumentException invalid){throw new IllegalStateException("Invalid invitation token key.");}
        if(key.length!=0 && key.length!=32)throw new IllegalStateException("Invitation token key must contain 32 random bytes.");
    }
    public boolean configured(){return key.length==32;}
    public String nonce(){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    public String token(String nonce){
        if(!configured())throw new IllegalStateException("Invitation token key is not configured.");
        try {var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(("brandista-merchant-v1:"+nonce).getBytes(StandardCharsets.US_ASCII)));
        }catch(GeneralSecurityException unavailable){throw new IllegalStateException("Invitation token generation unavailable.");}
    }
    public static String hash(String token){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));}
        catch(NoSuchAlgorithmException unavailable){throw new IllegalStateException("Invitation hashing unavailable.");}
    }
}
