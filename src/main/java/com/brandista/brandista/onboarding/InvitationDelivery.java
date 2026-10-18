package com.brandista.brandista.onboarding;

/** Secret-bearing transport boundary. Implementations must never log payloads. */
public interface InvitationDelivery {
    void deliver(long invitationId,String recipientEmail,String rawToken);
}
